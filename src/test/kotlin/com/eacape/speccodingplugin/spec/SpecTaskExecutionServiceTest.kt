package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.llm.LlmResponse
import com.eacape.speccodingplugin.llm.LlmUsage
import com.eacape.speccodingplugin.session.ConversationRole
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.session.WorkflowChatActionIntent
import com.eacape.speccodingplugin.session.WorkflowChatEntrySource
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import com.eacape.speccodingplugin.ui.chat.TraceEventMetadataCodec
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SpecTaskExecutionServiceTest {

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
    fun `createRun should persist execution run metadata and audit`() {
        val executionService = newExecutionService()
        val workflowId = "wf-run-create"
        seedWorkflow(workflowId)

        val run = executionService.createRun(
            workflowId = workflowId,
            taskId = "T-001",
            status = TaskExecutionRunStatus.QUEUED,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-13T08:00:00Z",
            summary = "Queued from task action.",
        )

        val loaded = storage.loadWorkflow(workflowId).getOrThrow()
        val auditEvents = storage.listAuditEvents(workflowId).getOrThrow()
        val runCreated = auditEvents.last { event -> event.eventType == SpecAuditEventType.TASK_EXECUTION_RUN_CREATED }

        assertEquals(1, loaded.taskExecutionRuns.size)
        assertEquals(run, loaded.taskExecutionRuns.single())
        assertEquals(TaskExecutionRunStatus.QUEUED, loaded.taskExecutionRuns.single().status)
        assertEquals("T-001", runCreated.details["taskId"])
        assertEquals(run.runId, runCreated.details["runId"])
        assertEquals(ExecutionTrigger.USER_EXECUTE.name, runCreated.details["trigger"])
    }

    @Test
    fun `migrateLegacyInProgressTasks should create system recovery run once`() {
        val executionService = newExecutionService()
        val workflowId = "wf-run-migrate"
        seedWorkflow(
            workflowId = workflowId,
            tasksMarkdown = """
                # Implement Document

                ## Task List

                ### T-001: Recover me
                ```spec-task
                status: IN_PROGRESS
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```
                - [ ] Resume later.
            """.trimIndent(),
        )

        val firstMigration = executionService.migrateLegacyInProgressTasks(
            storage.loadWorkflow(workflowId).getOrThrow(),
        )
        val secondMigration = executionService.migrateLegacyInProgressTasks(firstMigration.workflow)
        val runs = executionService.listRuns(workflowId, "T-001")
        val migratedTask = tasksService.parse(workflowId).single()
        val persistedTasks = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()

        assertTrue(firstMigration.migrated)
        assertEquals(1, firstMigration.migratedRuns.size)
        assertFalse(secondMigration.migrated)
        assertEquals(1, runs.size)
        assertEquals(TaskExecutionRunStatus.WAITING_CONFIRMATION, runs.single().status)
        assertEquals(ExecutionTrigger.SYSTEM_RECOVERY, runs.single().trigger)
        assertEquals(TaskStatus.PENDING, migratedTask.status)
        assertTrue(persistedTasks.contains("status: PENDING"))
        assertFalse(persistedTasks.contains("status: IN_PROGRESS"))

        val recoveryAudit = storage.listAuditEvents(workflowId).getOrThrow()
            .last { event -> event.eventType == SpecAuditEventType.TASK_EXECUTION_RUN_CREATED }
        assertEquals(TaskStatus.IN_PROGRESS.name, recoveryAudit.details["migratedFromStatus"])
    }

    @Test
    fun `updateRunStatus should reject invalid transitions`() {
        val executionService = newExecutionService()
        val workflowId = "wf-run-transition"
        seedWorkflow(workflowId)
        val run = executionService.createRun(
            workflowId = workflowId,
            taskId = "T-001",
            status = TaskExecutionRunStatus.QUEUED,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-13T09:00:00Z",
        )

        val error = assertThrows(InvalidTaskExecutionRunTransitionError::class.java) {
            executionService.updateRunStatus(
                workflowId = workflowId,
                runId = run.runId,
                status = TaskExecutionRunStatus.SUCCEEDED,
            )
        }

        assertTrue(error.message?.contains("QUEUED -> SUCCEEDED") == true)
    }

    @Test
    fun `startAiExecution should reject tasks whose dependencies are not completed`() {
        val workflowId = "wf-ai-dependency-blocked"
        seedWorkflow(
            workflowId = workflowId,
            tasksMarkdown = """
                # Implement Document

                ## Task List

                ### T-001: Unfinished dependency
                ```spec-task
                status: PENDING
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```

                ### T-002: Blocked by dependency
                ```spec-task
                status: PENDING
                priority: P1
                dependsOn:
                  - T-001
                relatedFiles: []
                verificationResult: null
                ```
            """.trimIndent(),
        )
        val executionService = newExecutionService()

        val error = assertThrows(TaskExecutionBlockedByDependenciesError::class.java) {
            executionService.startAiExecution(
                workflowId = workflowId,
                taskId = "T-002",
                providerId = "mock",
                modelId = "mock-model-v1",
                operationMode = OperationMode.AUTO,
            )
        }

        assertEquals(
            "Task T-002 cannot execute until dependencies are COMPLETED: T-001",
            error.message,
        )
        assertTrue(storage.loadWorkflow(workflowId).getOrThrow().taskExecutionRuns.isEmpty())
        assertTrue(sessionManager.listSessions().isEmpty())
    }

    @Test
    fun `startAiExecution should create task scoped session with prompt context and waiting confirmation run`() {
        val workflowId = "wf-ai-start"
        val contextFile = tempDir.resolve("src/main/kotlin/demo/FeatureService.kt")
        Files.createDirectories(contextFile.parent)
        Files.writeString(
            contextFile,
            """
                package demo

                class FeatureService {
                    fun apply(): String = "done"
                }
            """.trimIndent(),
        )
        seedWorkflow(
            workflowId = workflowId,
            requirementsMarkdown = """
                # Requirements

                ## Summary
                Offline mode must stay available for task execution.

                ## Clarifications
                - The workflow must remain file-first.
            """.trimIndent(),
            designMarkdown = """
                # Design

                ## Decisions
                - Use task-scoped execution runs for AI execution.
            """.trimIndent(),
            tasksMarkdown = """
                # Implement Document

                ## Task List

                ### T-001: Prepare base
                ```spec-task
                status: COMPLETED
                priority: P0
                dependsOn: []
                relatedFiles:
                  - src/main/kotlin/demo/FeatureService.kt
                verificationResult: null
                ```
                - [x] Done.

                ### T-002: Execute AI task
                ```spec-task
                status: PENDING
                priority: P0
                dependsOn:
                  - T-001
                relatedFiles: []
                verificationResult: null
                ```
                - [ ] Finish the feature.
            """.trimIndent(),
        )

        var capturedRequest: SpecTaskExecutionService.TaskExecutionChatRequest? = null
        var registeredHandle: SpecTaskExecutionService.TaskExecutionCancellationHandle? = null
        val executionService = newExecutionService(
            relatedFilesResolver = { _, _ -> listOf("src/main/kotlin/demo/FeatureService.kt") },
            chatExecutor = { request ->
                capturedRequest = request
                LlmResponse(
                    content = "Implemented demo feature. Suggested relatedFiles: src/main/kotlin/demo/FeatureService.kt",
                    model = "mock-model-v1",
                    usage = LlmUsage(promptTokens = 10, completionTokens = 5),
                    finishReason = "stop",
                )
            },
        )

        val result = executionService.startAiExecution(
            workflowId = workflowId,
            taskId = "T-002",
            providerId = "mock",
            modelId = "mock-model-v1",
            operationMode = OperationMode.AUTO,
            supplementalInstruction = "Prefer the existing file-first workflow behavior.",
            auditContext = mapOf("triggerSource" to "TEST"),
            onRequestRegistered = { handle -> registeredHandle = handle },
        )

        val loadedWorkflow = storage.loadWorkflow(workflowId).getOrThrow()
        val latestTask = tasksService.parse(workflowId).first { task -> task.id == "T-002" }
        val persistedTasks = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()
        val session = sessionManager.listSessions().single()
        val messages = sessionManager.listMessages(session.id)
        val userMetadata = TaskExecutionSessionMetadataCodec.decode(messages.first().metadataJson)
        val launchPayload = userMetadata.resolveExecutionLaunchRestorePayload(messages.first().content)
        val launchAudit = storage.listAuditEvents(workflowId).getOrThrow()
            .last { event -> event.eventType == SpecAuditEventType.TASK_EXECUTION_REQUEST_RECORDED }

        assertEquals(TaskExecutionRunStatus.WAITING_CONFIRMATION, result.run.status)
        assertEquals(
            TaskExecutionRunStatus.WAITING_CONFIRMATION,
            loadedWorkflow.taskExecutionRuns.single { run -> run.taskId == "T-002" }.status,
        )
        assertEquals(TaskStatus.PENDING, latestTask.status)
        assertTrue(persistedTasks.contains("### T-002: Execute AI task"))
        assertTrue(persistedTasks.contains("status: PENDING"))
        assertFalse(persistedTasks.contains("status: IN_PROGRESS"))
        assertEquals(workflowId, session.specTaskId)
        assertEquals(session.id, result.sessionId)
        assertEquals(workflowId, session.workflowChatBinding?.workflowId)
        assertEquals(StageId.IMPLEMENT, session.workflowChatBinding?.focusedStage)
        assertEquals(WorkflowChatEntrySource.TASK_PANEL, session.workflowChatBinding?.source)
        assertEquals(WorkflowChatActionIntent.EXECUTE_TASK, session.workflowChatBinding?.actionIntent)
        assertTrue(session.title.contains("T-002"))
        assertEquals(listOf(ConversationRole.USER, ConversationRole.ASSISTANT), messages.map { it.role })
        assertTrue(messages.first().content.contains("## Execution Request"))
        assertTrue(messages.first().content.contains("Task: T-002"))
        assertTrue(messages.first().content.contains("## System Context"))
        assertTrue(messages.first().content.contains("## User Note"))
        assertTrue(messages.first().content.contains("Prefer the existing file-first workflow behavior."))
        assertTrue(messages.first().content.contains("T-001 · COMPLETED · Prepare base"))
        assertTrue(messages.first().content.contains("requirements.md:"))
        assertTrue(messages.first().content.contains("src/main/kotlin/demo/FeatureService.kt"))
        assertTrue(messages.first().content.contains("Internal execution prompt hidden from default chat view"))
        assertTrue(messages.last().content.contains("Implemented demo feature"))
        assertEquals(result.run.runId, userMetadata.runId)
        assertEquals("T-002", userMetadata.taskId)
        assertEquals(workflowId, userMetadata.workflowId)
        assertEquals(ExecutionTrigger.USER_EXECUTE, userMetadata.trigger)
        assertEquals(result.requestId, userMetadata.requestId)
        assertNotNull(userMetadata.launchPresentation)
        assertEquals(
            WorkflowChatExecutionLaunchSurface.TASK_ROW,
            userMetadata.launchPresentation?.launchSurface,
        )
        assertEquals(
            "Prefer the existing file-first workflow behavior.",
            userMetadata.launchPresentation?.supplementalInstruction,
        )
        assertEquals(result.prompt, userMetadata.resolveExecutionLaunchRawPrompt())
        assertTrue(launchPayload is WorkflowChatExecutionLaunchRestorePayload.Presentation)
        val presentation = (launchPayload as WorkflowChatExecutionLaunchRestorePayload.Presentation).launch
        assertEquals(result.run.runId, presentation.runId)
        assertEquals("T-002", presentation.taskId)
        assertTrue(
            presentation.sections.any { section ->
                section.kind == WorkflowChatExecutionPresentationSectionKind.ARTIFACT_SUMMARIES &&
                    section.itemCount > 0
            },
        )
        assertNotNull(registeredHandle)
        assertEquals(result.run.runId, registeredHandle!!.runId)
        assertEquals(result.sessionId, registeredHandle!!.sessionId)
        assertEquals("T-002", registeredHandle!!.taskId)
        assertEquals(result.run.runId, launchAudit.details["runId"])
        assertEquals(session.id, launchAudit.details["sessionId"])
        assertEquals("session_message_metadata", launchAudit.details["rawPromptStorage"])
        assertTrue(launchAudit.details["visibleSummaryPreview"].orEmpty().contains("Execution Request"))
        assertEquals("true", launchAudit.details["rawPromptDebugAvailable"])
        assertNotNull(capturedRequest)
        assertEquals(result.requestId, capturedRequest!!.requestId)
        assertEquals("mock-model-v1", capturedRequest!!.modelId)
        assertTrue(capturedRequest!!.userInput.contains("EXECUTE_WITH_AI"))
        assertTrue(capturedRequest!!.userInput.contains("Prefer the existing file-first workflow behavior."))
        assertEquals(
            "src/main/kotlin/demo/FeatureService.kt",
            capturedRequest!!.contextSnapshot?.items?.single()?.label,
        )
    }

    @Test
    fun `startAiExecution should publish live progress milestones and aggregate trace events`() {
        val workflowId = "wf-ai-live-progress"
        seedWorkflow(workflowId)
        val observedPhases = mutableListOf<ExecutionLivePhase>()
        val executionService = newExecutionService(
            chatExecutor = { request ->
                request.onChunk(
                    com.eacape.speccodingplugin.llm.LlmChunk(
                        delta = "",
                        event = ChatStreamEvent(
                            kind = ChatTraceKind.READ,
                            detail = "SpecWorkflowPanel.kt",
                            status = ChatTraceStatus.RUNNING,
                        ),
                    ),
                )
                request.onChunk(
                    com.eacape.speccodingplugin.llm.LlmChunk(
                        delta = "Applying change",
                        isLast = false,
                    ),
                )
                LlmResponse(
                    content = "Implemented task update.",
                    model = "mock-model-v1",
                    usage = LlmUsage(),
                    finishReason = "stop",
                )
            },
        )
        executionService.addLiveProgressListener { progress ->
            if (progress.workflowId == workflowId) {
                observedPhases += progress.phase
            }
        }

        val result = executionService.startAiExecution(
            workflowId = workflowId,
            taskId = "T-001",
            providerId = "mock",
            modelId = "mock-model-v1",
            operationMode = OperationMode.AUTO,
        )

        val liveProgress = executionService.getLiveProgress(workflowId, result.run.runId)
        val session = sessionManager.listSessions().single()
        val messages = sessionManager.listMessages(session.id)
        val assistantTrace = TraceEventMetadataCodec.decodePayload(messages.last().metadataJson)

        assertNotNull(liveProgress)
        assertEquals(ExecutionLivePhase.WAITING_CONFIRMATION, liveProgress!!.phase)
        assertEquals("mock", liveProgress.providerId)
        assertEquals("mock-model-v1", liveProgress.modelId)
        assertTrue(observedPhases.containsAll(listOf(
            ExecutionLivePhase.QUEUED,
            ExecutionLivePhase.PREPARING_CONTEXT,
            ExecutionLivePhase.REQUEST_DISPATCHED,
            ExecutionLivePhase.STREAMING,
            ExecutionLivePhase.WAITING_CONFIRMATION,
        )))
        assertTrue(liveProgress.recentEvents.any { event ->
            event.kind == ChatTraceKind.READ && event.detail == "SpecWorkflowPanel.kt"
        })
        assertTrue(liveProgress.lastUpdatedAt >= liveProgress.startedAt)
        assertNotNull(assistantTrace.startedAtMillis)
        assertNotNull(assistantTrace.finishedAtMillis)
        assertTrue(assistantTrace.events.any { event ->
            event.kind == ChatTraceKind.READ && event.detail == "SpecWorkflowPanel.kt"
        })
    }

    @Test
    fun `startAiExecution should synthesize generic recent activity when provider emits delta without trace`() {
        val workflowId = "wf-ai-live-progress-fallback"
        seedWorkflow(workflowId)
        val executionService = newExecutionService(
            chatExecutor = { request ->
                request.onChunk(
                    com.eacape.speccodingplugin.llm.LlmChunk(
                        delta = "Applying implementation change",
                        isLast = false,
                    ),
                )
                LlmResponse(
                    content = "Implemented task update.",
                    model = "mock-model-v1",
                    usage = LlmUsage(),
                    finishReason = "stop",
                )
            },
        )

        val result = executionService.startAiExecution(
            workflowId = workflowId,
            taskId = "T-001",
            providerId = "mock",
            modelId = "mock-model-v1",
            operationMode = OperationMode.AUTO,
        )

        val liveProgress = executionService.getLiveProgress(workflowId, result.run.runId)

        assertNotNull(liveProgress)
        assertTrue(liveProgress!!.recentEvents.any { event ->
            event.kind == ChatTraceKind.OUTPUT &&
                event.detail.contains("returning content", ignoreCase = true)
        })
        assertEquals(ExecutionLivePhase.WAITING_CONFIRMATION, liveProgress.phase)
    }

    @Test
    fun `cancelExecution should stop active run without blocking task lifecycle`() {
        val workflowId = "wf-ai-cancel"
        seedWorkflow(workflowId)

        val requestReady = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        val capturedHandle =
            AtomicReference<SpecTaskExecutionService.TaskExecutionCancellationHandle?>()
        val cancelledRequests = mutableListOf<String>()
        val executionService = newExecutionService(
            chatExecutor = {
                requestReady.countDown()
                allowResponse.await(5, TimeUnit.SECONDS)
                LlmResponse(
                    content = "Should not be applied after cancellation.",
                    model = "mock-model-v1",
                    usage = LlmUsage(),
                    finishReason = "stop",
                )
            },
            requestCanceller = { _, requestId ->
                cancelledRequests += requestId
            },
        )

        val executionThread = Thread {
            runCatching {
                executionService.startAiExecution(
                    workflowId = workflowId,
                    taskId = "T-001",
                    providerId = "mock",
                    modelId = "mock-model-v1",
                    operationMode = OperationMode.AUTO,
                    onRequestRegistered = capturedHandle::set,
                )
            }
        }

        executionThread.start()
        assertTrue(requestReady.await(5, TimeUnit.SECONDS))

        val handle = capturedHandle.get()
        assertNotNull(handle)
        val cancelledRun = executionService.cancelExecution(workflowId, "T-001")
        allowResponse.countDown()
        executionThread.join(5_000)

        val persistedRun = executionService.listRuns(workflowId, "T-001").single()
        val persistedTask = tasksService.parse(workflowId).single()

        assertEquals(TaskExecutionRunStatus.CANCELLED, cancelledRun?.status)
        assertEquals(TaskExecutionRunStatus.CANCELLED, persistedRun.status)
        assertEquals(TaskStatus.PENDING, persistedTask.status)
        assertEquals(listOf(handle!!.requestId), cancelledRequests)
        assertTrue(persistedRun.summary.orEmpty().contains("cancelled", ignoreCase = true))
    }

    @Test
    fun `cancelExecutionRun should expose cancelling live progress before terminal cancellation`() {
        val workflowId = "wf-ai-live-cancelling"
        seedWorkflow(workflowId)

        val requestReady = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        val capturedHandle =
            AtomicReference<SpecTaskExecutionService.TaskExecutionCancellationHandle?>()
        val executionService = newExecutionService(
            chatExecutor = {
                requestReady.countDown()
                allowResponse.await(5, TimeUnit.SECONDS)
                throw CancellationException("AI execution cancelled by user.")
            },
        )

        val executionThread = Thread {
            runCatching {
                executionService.startAiExecution(
                    workflowId = workflowId,
                    taskId = "T-001",
                    providerId = "mock",
                    modelId = "mock-model-v1",
                    operationMode = OperationMode.AUTO,
                    onRequestRegistered = capturedHandle::set,
                )
            }
        }

        executionThread.start()
        assertTrue(requestReady.await(5, TimeUnit.SECONDS))

        val handle = capturedHandle.get()
        assertNotNull(handle)
        executionService.cancelExecutionRun(workflowId, handle!!.runId)

        val cancellingProgress = executionService.getLiveProgress(workflowId, handle.runId)
        assertNotNull(cancellingProgress)
        assertEquals(ExecutionLivePhase.CANCELLING, cancellingProgress!!.phase)

        allowResponse.countDown()
        executionThread.join(5_000)

        val terminalProgress = executionService.getLiveProgress(workflowId, handle.runId)
        assertNotNull(terminalProgress)
        assertEquals(ExecutionLivePhase.TERMINAL, terminalProgress!!.phase)
        assertTrue(terminalProgress.lastDetail.orEmpty().contains("cancelled", ignoreCase = true))
    }

    @Test
    fun `startAiExecution should keep cancellation semantics when provider exits with error after stop request`() {
        val workflowId = "wf-ai-cancel-provider-error"
        seedWorkflow(workflowId)

        val requestReady = CountDownLatch(1)
        val allowFailure = CountDownLatch(1)
        val capturedHandle =
            AtomicReference<SpecTaskExecutionService.TaskExecutionCancellationHandle?>()
        val capturedError = AtomicReference<Throwable?>()
        val cancelledRequests = mutableListOf<String>()
        val executionService = newExecutionService(
            chatExecutor = {
                requestReady.countDown()
                allowFailure.await(5, TimeUnit.SECONDS)
                throw IllegalStateException("CLI process destroyed")
            },
            requestCanceller = { _, requestId ->
                cancelledRequests += requestId
            },
        )

        val executionThread = Thread {
            runCatching {
                executionService.startAiExecution(
                    workflowId = workflowId,
                    taskId = "T-001",
                    providerId = "mock",
                    modelId = "mock-model-v1",
                    operationMode = OperationMode.AUTO,
                    onRequestRegistered = capturedHandle::set,
                )
            }.onFailure { error ->
                capturedError.set(error)
            }
        }

        executionThread.start()
        assertTrue(requestReady.await(5, TimeUnit.SECONDS))

        val handle = capturedHandle.get()
        assertNotNull(handle)
        executionService.cancelExecution(workflowId, "T-001")
        allowFailure.countDown()
        executionThread.join(5_000)

        val persistedRun = executionService.listRuns(workflowId, "T-001").single()
        val persistedTask = tasksService.parse(workflowId).single()
        val session = sessionManager.listSessions().single()
        val messages = sessionManager.listMessages(session.id)

        assertTrue(capturedError.get() is CancellationException)
        assertEquals(TaskExecutionRunStatus.CANCELLED, persistedRun.status)
        assertEquals(TaskStatus.PENDING, persistedTask.status)
        assertEquals(listOf(handle!!.requestId), cancelledRequests)
        assertTrue(messages.last().content.contains("cancelled", ignoreCase = true))
    }

    @Test
    fun `listLiveProgress should synthesize waiting confirmation state from persisted run without memory snapshot`() {
        val workflowId = "wf-ai-live-recovery"
        seedWorkflow(workflowId)
        val executionService = newExecutionService()
        val run = executionService.createRun(
            workflowId = workflowId,
            taskId = "T-001",
            status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
            trigger = ExecutionTrigger.SYSTEM_RECOVERY,
            startedAt = "2026-03-15T10:00:00Z",
            summary = "Recovered run awaiting confirmation.",
        )

        val liveProgress = executionService.listLiveProgress(workflowId, "T-001").single()

        assertEquals(run.runId, liveProgress.runId)
        assertEquals(ExecutionLivePhase.WAITING_CONFIRMATION, liveProgress.phase)
        assertEquals("Recovered run awaiting confirmation.", liveProgress.lastDetail)
        assertTrue(liveProgress.recentEvents.isNotEmpty())
    }

    @Test
    fun `listLiveProgress should not rewrite tasks markdown when restoring persisted waiting confirmation state`() {
        val workflowId = "wf-ai-live-recovery-no-writeback"
        seedWorkflow(workflowId)
        val executionService = newExecutionService()
        val tasksBeforeRestore = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()

        executionService.createRun(
            workflowId = workflowId,
            taskId = "T-001",
            status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
            trigger = ExecutionTrigger.SYSTEM_RECOVERY,
            startedAt = "2026-03-15T10:00:00Z",
            summary = "Recovered run awaiting confirmation.",
        )

        val liveProgress = executionService.getTaskLiveProgress(workflowId, "T-001")
        val tasksAfterRestore = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()
        val persistedTask = tasksService.parse(workflowId).single()

        assertNotNull(liveProgress)
        assertEquals(ExecutionLivePhase.WAITING_CONFIRMATION, liveProgress!!.phase)
        assertEquals("Recovered run awaiting confirmation.", liveProgress.lastDetail)
        assertEquals(tasksBeforeRestore, tasksAfterRestore)
        assertEquals(TaskStatus.PENDING, persistedTask.status)
        assertTrue(tasksAfterRestore.contains("status: PENDING"))
        assertFalse(tasksAfterRestore.contains("status: IN_PROGRESS"))
    }

    @Test
    fun `startAiExecution should fail run and block task when chat execution fails`() {
        val workflowId = "wf-ai-fail"
        seedWorkflow(workflowId)
        val executionService = newExecutionService(
            chatExecutor = {
                throw IllegalStateException("CLI unavailable")
            },
        )

        val error = assertThrows(IllegalStateException::class.java) {
            executionService.startAiExecution(
                workflowId = workflowId,
                taskId = "T-001",
                providerId = "mock",
                modelId = "mock-model-v1",
                operationMode = OperationMode.AUTO,
            )
        }

        val runs = executionService.listRuns(workflowId, "T-001")
        val task = tasksService.parse(workflowId).first { it.id == "T-001" }
        val session = sessionManager.listSessions().single()
        val messages = sessionManager.listMessages(session.id)

        assertEquals("CLI unavailable", error.message)
        assertEquals(TaskExecutionRunStatus.FAILED, runs.single().status)
        assertEquals(TaskStatus.BLOCKED, task.status)
        assertEquals(listOf(ConversationRole.USER, ConversationRole.SYSTEM), messages.map { it.role })
        assertTrue(messages.last().content.contains("CLI unavailable"))
    }

    @Test
    fun `retryAiExecution should create retry run and include previous run context`() {
        val workflowId = "wf-ai-retry"
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
                - [ ] Retry later.
            """.trimIndent(),
        )
        val bootstrapService = newExecutionService()
        val previousRun = bootstrapService.createRun(
            workflowId = workflowId,
            taskId = "T-001",
            status = TaskExecutionRunStatus.FAILED,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-13T12:00:00Z",
            finishedAt = "2026-03-13T12:05:00Z",
            summary = "First attempt failed.",
        )
        var capturedRequest: SpecTaskExecutionService.TaskExecutionChatRequest? = null
        val executionService = newExecutionService(
            chatExecutor = { request ->
                capturedRequest = request
                LlmResponse(
                    content = "Retried task successfully.",
                    model = "mock-model-v1",
                    usage = LlmUsage(),
                    finishReason = "stop",
                )
            },
        )

        val result = executionService.retryAiExecution(
            workflowId = workflowId,
            taskId = "T-001",
            providerId = "mock",
            modelId = "mock-model-v1",
            operationMode = OperationMode.AUTO,
            previousRunId = previousRun.runId,
        )

        val runs = executionService.listRuns(workflowId, "T-001")
        val latestTask = tasksService.parse(workflowId).first { it.id == "T-001" }
        val session = sessionManager.getSession(result.sessionId)
        val userMessage = sessionManager.listMessages(result.sessionId).first()
        val userMetadata = TaskExecutionSessionMetadataCodec.decode(userMessage.metadataJson)

        assertEquals(2, runs.size)
        assertEquals(ExecutionTrigger.USER_RETRY, result.run.trigger)
        assertEquals(TaskExecutionRunStatus.WAITING_CONFIRMATION, result.run.status)
        assertEquals(TaskStatus.PENDING, latestTask.status)
        assertEquals(workflowId, session?.workflowChatBinding?.workflowId)
        assertEquals(WorkflowChatActionIntent.RETRY_TASK, session?.workflowChatBinding?.actionIntent)
        assertTrue(userMessage.content.contains("Trigger: Retry execution"))
        assertTrue(userMessage.content.contains("Internal execution prompt hidden from default chat view"))
        assertEquals(previousRun.runId, userMetadata.previousRunId)
        assertEquals(result.prompt, userMetadata.resolveExecutionLaunchRawPrompt())
        assertEquals(previousRun.runId, result.previousRunId)
        assertNotNull(capturedRequest)
        assertTrue(capturedRequest!!.userInput.contains("RETRY_EXECUTION"))
    }

    @Test
    fun `resolveWaitingConfirmationRun should mark latest pending confirmation run as succeeded`() {
        val workflowId = "wf-run-resolve"
        seedWorkflow(workflowId)
        val executionService = newExecutionService()
        val waitingRun = executionService.createRun(
            workflowId = workflowId,
            taskId = "T-001",
            status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-13T12:00:00Z",
            summary = "Ready for completion",
        )

        val resolvedRun = executionService.resolveWaitingConfirmationRun(
            workflowId = workflowId,
            taskId = "T-001",
            summary = "Completed from test",
        )

        val runs = executionService.listRuns(workflowId, "T-001")
        assertEquals(waitingRun.runId, resolvedRun?.runId)
        assertEquals(TaskExecutionRunStatus.SUCCEEDED, resolvedRun?.status)
        assertEquals(TaskExecutionRunStatus.SUCCEEDED, runs.single().status)
        assertEquals("Completed from test", runs.single().summary)
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
        requestCanceller: (String?, String) -> Unit = { _, _ -> },
    ): SpecTaskExecutionService {
        return SpecTaskExecutionService(
            project = project,
            storage = storage,
            tasksService = tasksService,
            relatedFilesResolver = relatedFilesResolver,
            projectService = projectService,
            sessionManager = sessionManager,
            chatExecutor = chatExecutor,
            requestCanceller = requestCanceller,
        )
    }

    private fun seedWorkflow(
        workflowId: String,
        requirementsMarkdown: String? = null,
        designMarkdown: String? = null,
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
                description = "execution run test",
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
                    StageId.ARCHIVE to StageState(
                        active = true,
                        status = StageProgress.NOT_STARTED,
                        enteredAt = null,
                        completedAt = null,
                    ),
                ),
            ),
        ).getOrThrow()
        requirementsMarkdown?.let { markdown ->
            artifactService.writeArtifact(workflowId, StageId.REQUIREMENTS, markdown)
        }
        designMarkdown?.let { markdown ->
            artifactService.writeArtifact(workflowId, StageId.DESIGN, markdown)
        }
        artifactService.writeArtifact(workflowId, StageId.TASKS, tasksMarkdown)
    }
}
