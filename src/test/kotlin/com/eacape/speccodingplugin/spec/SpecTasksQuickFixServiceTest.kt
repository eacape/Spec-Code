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
import java.nio.file.Path

class SpecTasksQuickFixServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage
    private lateinit var artifactService: SpecArtifactService
    private lateinit var tasksService: SpecTasksService
    private lateinit var service: SpecTasksQuickFixService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
        artifactService = SpecArtifactService(project)
        tasksService = SpecTasksService(project)
        service = SpecTasksQuickFixService(project, storage, artifactService, tasksService)
    }

    @Test
    fun `repairTasksArtifact should repair headings fences yaml and append audit`() {
        val workflow = directImplementWorkflow(
            workflowId = "wf-editor-fix-tasks",
            verifyEnabled = false,
        )
        storage.saveWorkflow(workflow).getOrThrow()

        val broken = """
            # Implement Document

            ## Task List

            ### T-1 First task
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn: T-002
            relatedFiles: []
            verificationResult: null
            extra: 1
            ```
            - [ ] Body line must stay.

            ### T-002: Second task
            This line should be moved after the spec-task fence.
            ```spec-task
            status: DONE
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```

            ```spec-task
            status: PENDING
            priority: P1
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```

            ### T-003: Third task
            - [ ] Missing fence.
        """.trimIndent()
        artifactService.writeArtifact(workflow.id, StageId.TASKS, broken)

        val result = service.repairTasksArtifact(workflow.id)
        val persisted = artifactService.readArtifact(workflow.id, StageId.TASKS).orEmpty()
        val auditEvents = storage.listAuditEvents(workflow.id).getOrThrow()
            .filter { event -> event.eventType == SpecAuditEventType.TASKS_ARTIFACT_REPAIRED }

        assertTrue(result.changed)
        assertTrue(result.issuesBefore.isNotEmpty())
        assertTrue(result.issuesAfter.isEmpty())

        assertTrue(persisted.contains("### T-001: First task"))
        assertTrue(persisted.contains("dependsOn:\n  - T-002"))
        assertFalse(persisted.contains("extra:"))
        assertTrue(persisted.contains("### T-003: Third task\n```spec-task"))

        assertEquals(1, auditEvents.size)
        assertEquals("editor-popup", auditEvents.single().details["trigger"])
        assertEquals("tasks.md", auditEvents.single().details["file"])
    }

    @Test
    fun `repairTasksArtifact should be noop when tasks md is already canonical`() {
        val workflow = directImplementWorkflow(
            workflowId = "wf-editor-fix-tasks-noop",
            verifyEnabled = false,
        )
        storage.saveWorkflow(workflow).getOrThrow()

        val canonical = """
            # Implement Document

            ## Task List

            ### T-001: Bootstrap task
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] Keep body.
        """.trimIndent()
        artifactService.writeArtifact(workflow.id, StageId.TASKS, canonical)

        val result = service.repairTasksArtifact(workflow.id)
        val auditEvents = storage.listAuditEvents(workflow.id).getOrThrow()
            .filter { event -> event.eventType == SpecAuditEventType.TASKS_ARTIFACT_REPAIRED }

        assertFalse(result.changed)
        assertTrue(result.issuesAfter.isEmpty())
        assertTrue(auditEvents.isEmpty())
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
            description = "editor tasks quick fix",
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

