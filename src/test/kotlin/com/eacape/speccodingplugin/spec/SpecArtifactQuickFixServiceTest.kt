package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecArtifactQuickFixServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage
    private lateinit var service: SpecArtifactQuickFixService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
        service = SpecArtifactQuickFixService(project, storage, SpecArtifactService(project))
    }

    @Test
    fun `scaffoldMissingArtifacts should create workflow scoped skeletons and append audit`() {
        val workflow = directImplementWorkflow(
            workflowId = "wf-editor-fix",
            verifyEnabled = true,
        )
        storage.saveWorkflow(workflow).getOrThrow()

        val result = service.scaffoldMissingArtifacts(workflow.id)
        val createdFiles = result.createdArtifacts.map { it.fileName.toString() }
        val auditEvents = storage.listAuditEvents(workflow.id).getOrThrow()
            .filter { event -> event.eventType == SpecAuditEventType.ARTIFACT_SCAFFOLDED }

        assertEquals(listOf("tasks.md", "verification.md"), createdFiles)
        assertTrue(Files.isRegularFile(result.createdArtifacts[0]))
        assertTrue(Files.isRegularFile(result.createdArtifacts[1]))
        assertEquals(1, auditEvents.size)
        assertEquals("editor-popup", auditEvents.single().details["trigger"])
        assertEquals("2", auditEvents.single().details["createdCount"])
        assertEquals("tasks.md,verification.md", auditEvents.single().details["createdFiles"])
    }

    @Test
    fun `scaffoldMissingArtifacts should not append audit when nothing new is created`() {
        val workflow = directImplementWorkflow(
            workflowId = "wf-editor-fix-noop",
            verifyEnabled = true,
        )
        storage.saveWorkflow(workflow).getOrThrow()

        service.scaffoldMissingArtifacts(workflow.id)
        val second = service.scaffoldMissingArtifacts(workflow.id)
        val auditEvents = storage.listAuditEvents(workflow.id).getOrThrow()
            .filter { event -> event.eventType == SpecAuditEventType.ARTIFACT_SCAFFOLDED }

        assertTrue(second.createdArtifacts.isEmpty())
        assertEquals(1, auditEvents.size)
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
            description = "editor quick fix",
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
