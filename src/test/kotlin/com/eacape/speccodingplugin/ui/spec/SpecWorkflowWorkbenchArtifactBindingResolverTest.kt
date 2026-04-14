package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecWorkflowWorkbenchArtifactBindingResolverTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `resolve should use workflow document content for document-backed binding`() {
        val workflow = workflow(
            documents = mapOf(
                SpecPhase.DESIGN to SpecDocument(
                    id = "design",
                    phase = SpecPhase.DESIGN,
                    content = "# Design\n\nDesign content",
                    metadata = SpecMetadata(
                        title = "Design",
                        description = "resolver test design document",
                    ),
                ),
            ),
        )
        val resolver = resolver()

        val resolved = resolver.resolve(
            workflow = workflow,
            state = workbenchState(
                binding = artifactBinding(
                    stageId = StageId.DESIGN,
                    documentPhase = SpecPhase.DESIGN,
                    fileName = null,
                ),
            ),
        )

        assertTrue(resolved.artifactBinding.available)
        assertEquals("# Design\n\nDesign content", resolved.artifactBinding.previewContent)
    }

    @Test
    fun `resolve should read preview content for existing file-backed binding`() {
        val artifactPath = tempDir.resolve("tasks.md")
        Files.writeString(artifactPath, "# Tasks\n\n- [ ] task-1")
        val resolver = resolver(
            locateArtifact = { _, _ -> artifactPath },
        )

        val resolved = resolver.resolve(
            workflow = workflow(),
            state = workbenchState(
                binding = artifactBinding(
                    stageId = StageId.IMPLEMENT,
                    documentPhase = null,
                    fileName = "tasks.md",
                ),
            ),
        )

        assertTrue(resolved.artifactBinding.available)
        assertEquals("# Tasks\n\n- [ ] task-1", resolved.artifactBinding.previewContent)
    }

    @Test
    fun `resolve should clear preview when file-backed artifact is missing`() {
        val missingPath = tempDir.resolve("missing.md")
        val resolver = resolver(
            locateArtifact = { _, _ -> missingPath },
        )

        val resolved = resolver.resolve(
            workflow = workflow(),
            state = workbenchState(
                binding = artifactBinding(
                    stageId = StageId.ARCHIVE,
                    documentPhase = null,
                    fileName = "missing.md",
                    available = true,
                    previewContent = "stale",
                ),
            ),
        )

        assertFalse(resolved.artifactBinding.available)
        assertNull(resolved.artifactBinding.previewContent)
    }

    @Test
    fun `resolve should ignore locate failure for file-backed binding`() {
        val resolver = resolver(
            locateArtifact = { _, _ -> error("boom") },
        )

        val resolved = resolver.resolve(
            workflow = workflow(),
            state = workbenchState(
                binding = artifactBinding(
                    stageId = StageId.ARCHIVE,
                    documentPhase = null,
                    fileName = "archive.md",
                ),
            ),
        )

        assertFalse(resolved.artifactBinding.available)
        assertNull(resolved.artifactBinding.previewContent)
    }

    @Test
    fun `resolve should keep availability when file content cannot be read`() {
        val artifactPath = tempDir.resolve("verify.md")
        Files.writeString(artifactPath, "# Verify")
        val resolver = resolver(
            locateArtifact = { _, _ -> artifactPath },
            readArtifactContent = { error("read failed") },
        )

        val resolved = resolver.resolve(
            workflow = workflow(),
            state = workbenchState(
                binding = artifactBinding(
                    stageId = StageId.VERIFY,
                    documentPhase = null,
                    fileName = "verify.md",
                ),
            ),
        )

        assertTrue(resolved.artifactBinding.available)
        assertNull(resolved.artifactBinding.previewContent)
    }

    private fun resolver(
        locateArtifact: (workflowId: String, fileName: String) -> Path = { _, fileName ->
            tempDir.resolve(fileName)
        },
        readArtifactContent: (Path) -> String? = { path ->
            Files.readString(path)
        },
    ): SpecWorkflowWorkbenchArtifactBindingResolver {
        return SpecWorkflowWorkbenchArtifactBindingResolver(
            locateArtifact = locateArtifact,
            readArtifactContent = readArtifactContent,
        )
    }

    private fun workflow(
        documents: Map<SpecPhase, SpecDocument> = emptyMap(),
    ): SpecWorkflow {
        return SpecWorkflow(
            id = "wf-workbench",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = documents,
            status = WorkflowStatus.IN_PROGRESS,
            title = "Workflow Workbench",
            description = "resolver test",
            template = WorkflowTemplate.FULL_SPEC,
            currentStage = StageId.IMPLEMENT,
            createdAt = 1L,
            updatedAt = 2L,
            changeIntent = SpecChangeIntent.FULL,
        )
    }

    private fun workbenchState(
        binding: SpecWorkflowStageArtifactBinding,
    ): SpecWorkflowStageWorkbenchState {
        return SpecWorkflowStageWorkbenchState(
            currentStage = binding.stageId,
            focusedStage = binding.stageId,
            progress = SpecWorkflowStageProgressView(
                stepIndex = 1,
                totalSteps = 1,
                stageStatus = StageProgress.IN_PROGRESS,
                completedCheckCount = 0,
                totalCheckCount = 0,
                completionChecks = emptyList(),
            ),
            primaryAction = null,
            overflowActions = emptyList(),
            blockers = emptyList(),
            artifactBinding = binding,
            visibleSections = emptySet(),
        )
    }

    private fun artifactBinding(
        stageId: StageId,
        documentPhase: SpecPhase?,
        fileName: String?,
        available: Boolean = false,
        previewContent: String? = null,
    ): SpecWorkflowStageArtifactBinding {
        return SpecWorkflowStageArtifactBinding(
            stageId = stageId,
            title = "Artifact",
            fileName = fileName,
            documentPhase = documentPhase,
            mode = SpecWorkflowWorkbenchDocumentMode.READ_ONLY,
            fallbackEditable = false,
            available = available,
            previewContent = previewContent,
        )
    }
}
