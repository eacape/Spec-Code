package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.llm.LlmResponse
import com.eacape.speccodingplugin.llm.LlmUsage
import com.eacape.speccodingplugin.session.SessionManager
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SpecTaskCompletionServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var artifactService: SpecArtifactService
    private lateinit var storage: SpecStorage
    private lateinit var tasksService: SpecTasksService
    private lateinit var sessionManager: SessionManager
    private lateinit var projectService: SpecCodingProjectService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        artifactService = SpecArtifactService(project)
        storage = SpecStorage.getInstance(project)
        tasksService = SpecTasksService(project)
        sessionManager = SessionManager(project)
        projectService = mockk(relaxed = true)
    }

    @Test
    fun `completeTask should auto repair tasks md before applying completion updates`() {
        val workflowId = "wf-complete-auto-repair"
        seedWorkflow(
            workflowId = workflowId,
            tasksMarkdown = """
                # Implement Document

                ## Task List

                ### T-001: Finish task
                ```spec-task
                status: PENDING
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult: PASS
                ```
                - [ ] Finish task.
            """.trimIndent(),
        )
        val executionService = newExecutionService()
        val completionService = SpecTaskCompletionService(
            project = project,
            storage = storage,
            tasksService = tasksService,
            relatedFilesResolver = { _, existing -> existing },
            taskExecutionService = executionService,
        )
        executionService.createRun(
            workflowId = workflowId,
            taskId = "T-001",
            status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-18T05:00:00Z",
            summary = "Ready for confirmation.",
        )

        val completedTask = completionService.completeTask(
            workflowId = workflowId,
            taskId = "T-001",
            relatedFiles = listOf("src/main/kotlin/demo/Feature.kt"),
            verificationResult = null,
            completionRunSummary = "Completed after auto repair.",
        )

        val persistedTask = tasksService.parse(workflowId).single()
        val persistedMarkdown = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()
        val runs = executionService.listRuns(workflowId, "T-001")
        val repairAudit = storage.listAuditEvents(workflowId).getOrThrow()
            .firstOrNull { event -> event.eventType == SpecAuditEventType.TASKS_ARTIFACT_REPAIRED }

        assertEquals(TaskStatus.COMPLETED, completedTask.status)
        assertEquals(TaskStatus.COMPLETED, persistedTask.status)
        assertEquals(listOf("src/main/kotlin/demo/Feature.kt"), persistedTask.relatedFiles)
        assertNull(persistedTask.verificationResult)
        assertTrue(persistedMarkdown.contains("verificationResult: null"))
        assertTrue(persistedMarkdown.contains("  - src/main/kotlin/demo/Feature.kt"))
        assertEquals(TaskExecutionRunStatus.SUCCEEDED, runs.single().status)
        assertNotNull(repairAudit)
        assertEquals("task-completion-auto-repair", repairAudit!!.details["trigger"])
    }

    private fun newExecutionService(): SpecTaskExecutionService {
        return SpecTaskExecutionService(
            project = project,
            storage = storage,
            tasksService = tasksService,
            relatedFilesResolver = { _, existing -> existing },
            projectService = projectService,
            sessionManager = sessionManager,
            chatExecutor = {
                LlmResponse(
                    content = "Default assistant reply.",
                    model = "mock-model-v1",
                    usage = LlmUsage(),
                    finishReason = "stop",
                )
            },
            requestCanceller = { _, _ -> },
        )
    }

    private fun seedWorkflow(
        workflowId: String,
        tasksMarkdown: String,
    ) {
        storage.saveWorkflow(
            SpecWorkflow(
                id = workflowId,
                currentPhase = SpecPhase.IMPLEMENT,
                currentStage = StageId.IMPLEMENT,
                title = workflowId,
                description = "completion test",
                documents = emptyMap(),
                status = WorkflowStatus.IN_PROGRESS,
                stageStates = linkedMapOf(
                    StageId.REQUIREMENTS to StageState(
                        active = true,
                        status = StageProgress.DONE,
                        enteredAt = "2026-03-18T04:00:00Z",
                        completedAt = "2026-03-18T04:10:00Z",
                    ),
                    StageId.DESIGN to StageState(
                        active = true,
                        status = StageProgress.DONE,
                        enteredAt = "2026-03-18T04:10:00Z",
                        completedAt = "2026-03-18T04:20:00Z",
                    ),
                    StageId.TASKS to StageState(
                        active = true,
                        status = StageProgress.DONE,
                        enteredAt = "2026-03-18T04:20:00Z",
                        completedAt = "2026-03-18T04:30:00Z",
                    ),
                    StageId.IMPLEMENT to StageState(
                        active = true,
                        status = StageProgress.IN_PROGRESS,
                        enteredAt = "2026-03-18T04:30:00Z",
                        completedAt = null,
                    ),
                    StageId.ARCHIVE to StageState(
                        active = true,
                        status = StageProgress.NOT_STARTED,
                        enteredAt = null,
                        completedAt = null,
                    ),
                ),
            ),
        ).getOrThrow()
        artifactService.writeArtifact(workflowId, StageId.TASKS, tasksMarkdown)
    }
}