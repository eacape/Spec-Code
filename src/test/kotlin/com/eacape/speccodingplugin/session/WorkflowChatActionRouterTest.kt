package com.eacape.speccodingplugin.session

import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.llm.LlmResponse
import com.eacape.speccodingplugin.llm.LlmUsage
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.SpecArtifactService
import com.eacape.speccodingplugin.spec.SpecAuditEventType
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecStorage
import com.eacape.speccodingplugin.spec.SpecTaskCompletionService
import com.eacape.speccodingplugin.spec.SpecTaskExecutionService
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.eacape.speccodingplugin.spec.TaskExecutionSessionMetadataCodec
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.TaskVerificationResult
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.resolveExecutionLaunchRawPrompt
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class WorkflowChatActionRouterTest {

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
    fun `executeTask should reuse current workflow chat session`() {
        val workflowId = "wf-chat-execute"
        seedWorkflow(workflowId)
        val session = createBoundSession(
            workflowId = workflowId,
            taskId = "T-001",
            source = WorkflowChatEntrySource.SIDEBAR,
        )
        var capturedRequest: SpecTaskExecutionService.TaskExecutionChatRequest? = null
        val router = newRouter(
            chatExecutor = { request ->
                capturedRequest = request
                LlmResponse(
                    content = "Executed from workflow chat.",
                    model = "mock-model-v1",
                    usage = LlmUsage(promptTokens = 11, completionTokens = 7),
                    finishReason = "stop",
                )
            },
        )

        val result = router.executeTask(
            sessionId = session.id,
            taskId = "T-001",
            providerId = "mock",
            modelId = "mock-model-v1",
            operationMode = OperationMode.AUTO,
            supplementalInstruction = "Also update the related tests.",
        )

        val loadedSession = sessionManager.getSession(session.id)
        val messages = sessionManager.listMessages(session.id)
        val userMetadata = TaskExecutionSessionMetadataCodec.decode(messages.first().metadataJson)

        assertEquals(session.id, result.sessionId)
        assertEquals(1, sessionManager.listSessions().size)
        assertEquals(WorkflowChatActionIntent.EXECUTE_TASK, loadedSession?.workflowChatBinding?.actionIntent)
        assertEquals(WorkflowChatEntrySource.SIDEBAR, loadedSession?.workflowChatBinding?.source)
        assertEquals(
            listOf(ConversationRole.USER, ConversationRole.ASSISTANT),
            messages.map { message -> message.role },
        )
        assertEquals(result.run.runId, userMetadata.runId)
        assertTrue(messages.first().content.contains("## Execution Request"))
        assertTrue(userMetadata.launchPresentation != null)
        assertEquals(result.prompt, userMetadata.resolveExecutionLaunchRawPrompt())
        assertTrue(capturedRequest?.userInput?.contains("EXECUTE_WITH_AI") == true)
        assertTrue(capturedRequest?.userInput?.contains("## Supplemental Instruction") == true)
        assertTrue(capturedRequest?.userInput?.contains("Also update the related tests.") == true)
    }

    @Test
    fun `retryTask should reuse current workflow chat session and previous run context`() {
        val workflowId = "wf-chat-retry"
        seedWorkflow(
            workflowId = workflowId,
            tasksMarkdown = """
                # Implement Document

                ## Task List

                ### T-001: Retry me
                ```spec-task
                status: BLOCKED
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```
                - [ ] Retry from workflow chat.
            """.trimIndent(),
        )
        val bootstrapExecutionService = newExecutionService()
        val previousRun = bootstrapExecutionService.createRun(
            workflowId = workflowId,
            taskId = "T-001",
            status = TaskExecutionRunStatus.FAILED,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-13T11:00:00Z",
            finishedAt = "2026-03-13T11:05:00Z",
            summary = "First attempt failed.",
        )
        val session = createBoundSession(
            workflowId = workflowId,
            taskId = "T-001",
            source = WorkflowChatEntrySource.SPEC_PAGE,
        )
        var capturedRequest: SpecTaskExecutionService.TaskExecutionChatRequest? = null
        val router = newRouter(
            chatExecutor = { request ->
                capturedRequest = request
                LlmResponse(
                    content = "Retried from workflow chat.",
                    model = "mock-model-v1",
                    usage = LlmUsage(),
                    finishReason = "stop",
                )
            },
        )

        val result = router.retryTask(
            sessionId = session.id,
            taskId = "T-001",
            providerId = "mock",
            modelId = "mock-model-v1",
            operationMode = OperationMode.AUTO,
            previousRunId = previousRun.runId,
        )

        val loadedSession = sessionManager.getSession(session.id)
        val task = tasksService.parse(workflowId).single()
        val userMessage = sessionManager.listMessages(session.id).first()

        assertEquals(session.id, result.sessionId)
        assertEquals(1, sessionManager.listSessions().size)
        assertEquals(TaskStatus.PENDING, task.status)
        assertEquals(WorkflowChatActionIntent.RETRY_TASK, loadedSession?.workflowChatBinding?.actionIntent)
        assertEquals(WorkflowChatEntrySource.SPEC_PAGE, loadedSession?.workflowChatBinding?.source)
        assertEquals(previousRun.runId, result.previousRunId)
        assertTrue(userMessage.content.contains("Trigger: Retry execution"))
        assertTrue(capturedRequest?.userInput?.contains("RETRY_EXECUTION") == true)
    }

    @Test
    fun `completeBoundTask should reuse shared completion flow and resolve waiting run`() {
        val workflowId = "wf-chat-complete"
        seedWorkflow(workflowId)
        val executionService = newExecutionService()
        executionService.createRun(
            workflowId = workflowId,
            taskId = "T-001",
            status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-13T12:00:00Z",
            summary = "Ready for completion.",
        )
        val session = createBoundSession(
            workflowId = workflowId,
            taskId = "T-001",
            source = WorkflowChatEntrySource.TASK_PANEL,
        )
        val relatedFiles = listOf("src/main/kotlin/demo/FeatureService.kt")
        val completionService = SpecTaskCompletionService(
            project = project,
            storage = storage,
            tasksService = tasksService,
            relatedFilesResolver = { _, _ -> relatedFiles },
            taskExecutionService = executionService,
        )
        val router = WorkflowChatActionRouter(
            project = project,
            sessionManager = sessionManager,
            storage = storage,
            tasksService = tasksService,
            executionService = executionService,
            completionService = completionService,
        )
        val plan = router.previewCompleteBoundTask(session.id)
        val verificationResult = TaskVerificationResult(
            conclusion = VerificationConclusion.PASS,
            runId = "verify-001",
            summary = "Verified from workflow chat.",
            at = "2026-03-13T12:10:00Z",
        )

        val completedTask = router.completeBoundTask(
            sessionId = session.id,
            planId = plan.planId,
            relatedFiles = relatedFiles,
            verificationResult = verificationResult,
        )

        val loadedSession = sessionManager.getSession(session.id)
        val persistedTask = tasksService.parse(workflowId).single()
        val run = executionService.listRuns(workflowId, "T-001").single()
        val statusAudit = storage.listAuditEvents(workflowId).getOrThrow()
            .last { event -> event.eventType == SpecAuditEventType.TASK_STATUS_CHANGED }

        assertTrue(plan.awaitingExecutionConfirmation)
        assertEquals(TaskStatus.COMPLETED, completedTask.status)
        assertEquals(TaskStatus.COMPLETED, persistedTask.status)
        assertEquals(relatedFiles, completedTask.relatedFiles)
        assertEquals(VerificationConclusion.PASS, completedTask.verificationResult?.conclusion)
        assertEquals(TaskExecutionRunStatus.SUCCEEDED, run.status)
        assertEquals(WorkflowChatActionIntent.COMPLETE_TASK, loadedSession?.workflowChatBinding?.actionIntent)
        assertEquals("WORKFLOW_CHAT", statusAudit.details["triggerSource"])
        assertEquals(session.id, statusAudit.details["workflowChatSessionId"])
        assertEquals(WorkflowChatActionIntent.COMPLETE_TASK.name, statusAudit.details["workflowChatActionIntent"])
    }

    private fun newRouter(
        relatedFilesResolver: (String, List<String>) -> List<String> = { _, existing -> existing },
        chatExecutor: suspend (SpecTaskExecutionService.TaskExecutionChatRequest) -> LlmResponse = {
            LlmResponse(
                content = "Default workflow chat reply.",
                model = "mock-model-v1",
                usage = LlmUsage(),
                finishReason = "stop",
            )
        },
    ): WorkflowChatActionRouter {
        val executionService = newExecutionService(
            relatedFilesResolver = relatedFilesResolver,
            chatExecutor = chatExecutor,
        )
        val completionService = SpecTaskCompletionService(
            project = project,
            storage = storage,
            tasksService = tasksService,
            relatedFilesResolver = relatedFilesResolver,
            taskExecutionService = executionService,
        )
        return WorkflowChatActionRouter(
            project = project,
            sessionManager = sessionManager,
            storage = storage,
            tasksService = tasksService,
            executionService = executionService,
            completionService = completionService,
        )
    }

    private fun newExecutionService(
        relatedFilesResolver: (String, List<String>) -> List<String> = { _, existing -> existing },
        chatExecutor: suspend (SpecTaskExecutionService.TaskExecutionChatRequest) -> LlmResponse = {
            LlmResponse(
                content = "Default assistant reply.",
                model = "mock-model-v1",
                usage = LlmUsage(),
                finishReason = "stop",
            )
        },
    ): SpecTaskExecutionService {
        return SpecTaskExecutionService(
            project = project,
            storage = storage,
            tasksService = tasksService,
            relatedFilesResolver = relatedFilesResolver,
            projectService = projectService,
            sessionManager = sessionManager,
            chatExecutor = chatExecutor,
        )
    }

    private fun createBoundSession(
        workflowId: String,
        taskId: String,
        source: WorkflowChatEntrySource,
    ): ConversationSession {
        return sessionManager.createSession(
            title = "Workflow Chat $taskId",
            workflowChatBinding = WorkflowChatBinding(
                workflowId = workflowId,
                focusedStage = StageId.IMPLEMENT,
                source = source,
                actionIntent = WorkflowChatActionIntent.DISCUSS,
            ),
        ).getOrThrow()
    }

    private fun seedWorkflow(
        workflowId: String,
        tasksMarkdown: String = """
            # Implement Document

            ## Task List

            ### T-001: Execute task
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] Keep this task.
        """.trimIndent(),
    ) {
        storage.saveWorkflow(
            SpecWorkflow(
                id = workflowId,
                currentPhase = SpecPhase.IMPLEMENT,
                currentStage = StageId.IMPLEMENT,
                title = workflowId,
                description = "workflow chat action router test",
                documents = emptyMap(),
                status = WorkflowStatus.IN_PROGRESS,
                stageStates = linkedMapOf(
                    StageId.REQUIREMENTS to StageState(
                        active = true,
                        status = StageProgress.DONE,
                        enteredAt = "2026-03-13T07:30:00Z",
                        completedAt = "2026-03-13T07:40:00Z",
                    ),
                    StageId.DESIGN to StageState(
                        active = true,
                        status = StageProgress.DONE,
                        enteredAt = "2026-03-13T07:40:00Z",
                        completedAt = "2026-03-13T07:50:00Z",
                    ),
                    StageId.TASKS to StageState(
                        active = true,
                        status = StageProgress.DONE,
                        enteredAt = "2026-03-13T07:50:00Z",
                        completedAt = "2026-03-13T07:55:00Z",
                    ),
                    StageId.IMPLEMENT to StageState(
                        active = true,
                        status = StageProgress.IN_PROGRESS,
                        enteredAt = "2026-03-13T08:00:00Z",
                        completedAt = null,
                    ),
                    StageId.VERIFY to StageState(active = true, status = StageProgress.NOT_STARTED),
                    StageId.ARCHIVE to StageState(active = true, status = StageProgress.NOT_STARTED),
                ),
            ),
        ).getOrThrow()
        artifactService.writeArtifact(
            workflowId = workflowId,
            stageId = StageId.TASKS,
            content = tasksMarkdown,
        )
    }
}
