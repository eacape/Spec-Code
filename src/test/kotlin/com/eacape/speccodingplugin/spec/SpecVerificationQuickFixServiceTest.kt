package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecVerificationQuickFixServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage
    private lateinit var artifactService: SpecArtifactService
    private lateinit var service: SpecVerificationQuickFixService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
        artifactService = SpecArtifactService(project)
        service = SpecVerificationQuickFixService(project, storage, artifactService, SpecTasksService(project))
    }

    @Test
    fun `scaffoldVerificationArtifact should create verification skeleton with selected task scope and append audit`() {
        val workflow = directImplementWorkflow(
            workflowId = "wf-editor-fix-verify",
            verifyEnabled = true,
        )
        storage.saveWorkflow(workflow).getOrThrow()

        artifactService.writeArtifact(workflow.id, StageId.TASKS, canonicalTasksMarkdown())

        val result = service.scaffoldVerificationArtifact(workflow.id, listOf("T-002"))
        val verificationContent = artifactService.readArtifact(workflow.id, StageId.VERIFY).orEmpty()
        val auditEvents = storage.listAuditEvents(workflow.id).getOrThrow()
            .filter { event ->
                event.eventType == SpecAuditEventType.ARTIFACT_SCAFFOLDED &&
                    event.details["file"] == StageId.VERIFY.artifactFileName
            }

        assertTrue(result.created)
        assertTrue(Files.isRegularFile(result.verificationDocumentPath))
        assertEquals(listOf("T-002"), result.scopeTaskIds)

        assertTrue(verificationContent.contains("## Verification Scope"))
        assertTrue(verificationContent.contains("`T-002` Second task"))
        assertFalse(verificationContent.contains("`T-001` First task"))

        assertEquals(1, auditEvents.size)
        assertEquals("editor-popup", auditEvents.single().details["trigger"])
        assertEquals("verification.md", auditEvents.single().details["file"])
        assertEquals("1", auditEvents.single().details["scopeTaskCount"])
        assertEquals("T-002", auditEvents.single().details["scopeTaskIds"])
    }

    @Test
    fun `scaffoldVerificationArtifact should be noop when verification md already exists`() {
        val workflow = directImplementWorkflow(
            workflowId = "wf-editor-fix-verify-noop",
            verifyEnabled = true,
        )
        storage.saveWorkflow(workflow).getOrThrow()

        artifactService.writeArtifact(workflow.id, StageId.TASKS, canonicalTasksMarkdown())

        val first = service.scaffoldVerificationArtifact(workflow.id, listOf("T-001", "T-002"))
        val second = service.scaffoldVerificationArtifact(workflow.id, listOf("T-001"))
        val auditEvents = storage.listAuditEvents(workflow.id).getOrThrow()
            .filter { event ->
                event.eventType == SpecAuditEventType.ARTIFACT_SCAFFOLDED &&
                    event.details["file"] == StageId.VERIFY.artifactFileName
            }

        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(1, auditEvents.size)
    }

    private fun canonicalTasksMarkdown(): String {
        return """
            # Implement Document

            ## Task List

            ### T-001: First task
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```

            ### T-002: Second task
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
        """.trimIndent()
    }

    private fun directImplementWorkflow(
        workflowId: String,
        verifyEnabled: Boolean,
    ): SpecWorkflow {
        val createdAt = 1_700_000_000_000L
        return SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = workflowId,
            description = "editor verification quick fix",
            changeIntent = SpecChangeIntent.FULL,
            template = WorkflowTemplate.DIRECT_IMPLEMENT,
            stageStates = buildStageStates(
                currentStage = StageId.IMPLEMENT,
                verifyEnabled = verifyEnabled,
            ),
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = verifyEnabled,
            baselineWorkflowId = null,
            configPinHash = null,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }

    private fun buildStageStates(
        currentStage: StageId,
        verifyEnabled: Boolean,
    ): Map<StageId, StageState> {
        val activeStages = linkedSetOf(StageId.IMPLEMENT, StageId.ARCHIVE)
        if (verifyEnabled) {
            activeStages += StageId.VERIFY
        }

        return StageId.entries.associateWith { stageId ->
            when {
                stageId == currentStage -> StageState(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                    enteredAt = "2026-03-10T00:00:00Z",
                )

                stageId in activeStages -> StageState(
                    active = true,
                    status = StageProgress.NOT_STARTED,
                )

                else -> StageState(
                    active = false,
                    status = StageProgress.NOT_STARTED,
                )
            }
        }
    }
}

