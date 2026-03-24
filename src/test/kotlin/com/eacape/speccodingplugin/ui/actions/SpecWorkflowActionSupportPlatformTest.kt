package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.SpecArtifactService
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.Violation
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path
import kotlin.io.path.name

class SpecWorkflowActionSupportPlatformTest : BasePlatformTestCase() {

    fun `test resolve gate violation path returns workflow artifact path`() {
        val workflowId = "wf-123"
        val artifactService = SpecArtifactService(project)
        val writtenPath = artifactService.writeArtifact(
            workflowId,
            StageId.TASKS,
            "# Tasks\n",
        )

        val resolvedPath = SpecWorkflowActionSupport.resolveGateViolationPath(
            project = project,
            workflowId = workflowId,
            violation = violation(fileName = "tasks.md"),
        )

        assertEquals(writtenPath.normalize(), resolvedPath?.normalize())
    }

    fun `test resolve gate violation path falls back to existing case insensitive match`() {
        val workflowId = "wf-456"
        val artifactService = SpecArtifactService(project)
        val writtenPath = artifactService.writeArtifact(
            workflowId,
            fileName = "TASKS.md",
            content = "# Tasks\n",
        )

        val resolvedPath = SpecWorkflowActionSupport.resolveGateViolationPath(
            project = project,
            workflowId = workflowId,
            violation = violation(fileName = "tasks.md"),
        )

        assertEquals(writtenPath.name, resolvedPath?.name)
    }

    fun `test resolve gate violation path returns null for missing file`() {
        val resolvedPath = SpecWorkflowActionSupport.resolveGateViolationPath(
            project = project,
            workflowId = "wf-missing",
            violation = violation(fileName = "verification.md"),
        )

        assertNull(resolvedPath)
    }

    fun `test open file opens verification artifact`() {
        val workflowId = "wf-open-verify"
        val artifactService = SpecArtifactService(project)
        val verificationPath = artifactService.writeArtifact(
            workflowId,
            StageId.VERIFY,
            "# Verification Document\n",
        )

        assertTrue(SpecWorkflowActionSupport.openFile(project, verificationPath))
        assertEquals(
            verificationPath.fileName.toString(),
            FileEditorManager.getInstance(project).selectedFiles.singleOrNull()?.name,
        )
    }

    private fun violation(fileName: String): Violation {
        return Violation(
            ruleId = "rule-1",
            severity = GateStatus.ERROR,
            fileName = fileName,
            line = 3,
            message = "Broken workflow artifact",
        )
    }
}
