package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.llm.ModelCapability
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.llm.MockLlmProvider
import com.eacape.speccodingplugin.session.ConversationRole
import com.eacape.speccodingplugin.session.WorkflowChatActionIntent
import com.eacape.speccodingplugin.session.WorkflowChatBinding
import com.eacape.speccodingplugin.session.WorkflowChatEntrySource
import com.eacape.speccodingplugin.spec.ExecutionLivePhase
import com.eacape.speccodingplugin.spec.SpecArtifactService
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecStorage
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.SpecTaskExecutionService
import com.eacape.speccodingplugin.spec.TaskExecutionRun
import com.eacape.speccodingplugin.spec.TaskExecutionSessionMetadataCodec
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchPresentation
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchSurface
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import com.eacape.speccodingplugin.ui.history.HistorySessionOpenListener
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.time.Instant

class WorkflowChatExecutionContextLinkPlatformTest : BasePlatformTestCase() {

    fun `test task execution should switch to chat session and clear temporary task chip after terminal run`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Chat Execution Link",
            description = "task 138 execution session link",
        ).getOrThrow()
        val task = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Link spec task execution to workflow chat",
            priority = TaskPriority.P0,
        )
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = false,
            includeTasksDocument = true,
        )

        val modelId = "task-138-${System.nanoTime()}"
        ModelRegistry.getInstance().register(
            ModelInfo(
                id = modelId,
                name = "Task 138 Mock Model",
                provider = MockLlmProvider.ID,
                contextWindow = 32_000,
                capabilities = setOf(ModelCapability.CHAT),
            ),
        )

        val toolWindow = registerSpecCodeToolWindow()
        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val specPanel = currentSpecPanel(toolWindow)
        val chatPanel = currentChatPanel(toolWindow)
        val executionService = SpecTaskExecutionService.getInstance(project)

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.openWorkflowForTest(workflow.id)
        }
        waitUntil {
            specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(task.id)
        }

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.selectToolbarModelForTest(MockLlmProvider.ID, modelId)
            assertTrue(specPanel.requestExecutionForTaskForTest(task.id))
        }

        waitUntil(timeoutMs = 10_000) {
            ChatToolWindowFactory.isChatContent(toolWindow.contentManager.selectedContent) &&
                chatPanel.workflowBindingSnapshotForTest().getValue("sessionId").isNotBlank() &&
                chatPanel.workflowBindingSnapshotForTest().getValue("workflowId") == workflow.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == task.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskChipVisible") == "true" &&
                chatPanel.liveTaskExecutionSnapshotForTest().getValue("visible") == "true" &&
                chatPanel.executionLaunchSnapshotForTest().getValue("visible") == "true"
        }

        waitUntil(timeoutMs = 10_000) {
            executionService.listRuns(workflow.id, task.id).firstOrNull()?.status ==
                TaskExecutionRunStatus.WAITING_CONFIRMATION
        }

        val runId = executionService.listRuns(workflow.id, task.id).first().runId
        val bindingSnapshot = chatPanel.workflowBindingSnapshotForTest()
        val liveSnapshot = chatPanel.liveTaskExecutionSnapshotForTest()
        val launchSnapshot = chatPanel.executionLaunchSnapshotForTest()
        assertEquals(workflow.id, bindingSnapshot.getValue("workflowId"))
        assertEquals(task.id, bindingSnapshot.getValue("taskId"))
        assertEquals("true", bindingSnapshot.getValue("taskChipVisible"))
        assertEquals(runId, liveSnapshot.getValue("runId"))
        assertEquals(task.id, liveSnapshot.getValue("taskId"))
        assertEquals("true", liveSnapshot.getValue("visible"))
        assertTrue(liveSnapshot.getValue("eventCount").toInt() >= 1)
        assertEquals("presentation", launchSnapshot.getValue("kind"))
        assertEquals(workflow.id, launchSnapshot.getValue("workflowId"))
        assertEquals(task.id, launchSnapshot.getValue("taskId"))
        assertEquals(runId, launchSnapshot.getValue("runId"))
        assertEquals("false", launchSnapshot.getValue("rawPromptVisible"))
        assertTrue(launchSnapshot.getValue("labels").contains("Execution Request"))
        assertFalse(launchSnapshot.getValue("content").contains("Interaction mode: workflow"))

        executionService.resolveWaitingConfirmationRun(
            workflowId = workflow.id,
            taskId = task.id,
            summary = "Completed from task 138 platform test.",
        )

        waitUntil(timeoutMs = 10_000) {
            chatPanel.workflowBindingSnapshotForTest().getValue("taskChipVisible") == "false" &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskId").isBlank()
        }

        val clearedSnapshot = chatPanel.workflowBindingSnapshotForTest()
        assertEquals(workflow.id, clearedSnapshot.getValue("workflowId"))
        assertEquals("", clearedSnapshot.getValue("taskId"))
        assertEquals("false", clearedSnapshot.getValue("taskChipVisible"))
    }

    fun `test sequential task executions should reuse same workflow chat session`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Chat Session Reuse",
            description = "task 140 reuse workflow chat session",
        ).getOrThrow()
        val firstTask = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Implement session reuse entry",
            priority = TaskPriority.P0,
        )
        val secondTask = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Implement follow-up in same chat",
            priority = TaskPriority.P1,
        )
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = false,
            includeTasksDocument = true,
        )

        val modelId = "task-140-${System.nanoTime()}"
        ModelRegistry.getInstance().register(
            ModelInfo(
                id = modelId,
                name = "Task 140 Mock Model",
                provider = MockLlmProvider.ID,
                contextWindow = 32_000,
                capabilities = setOf(ModelCapability.CHAT),
            ),
        )

        val toolWindow = registerSpecCodeToolWindow()
        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val specPanel = currentSpecPanel(toolWindow)
        val chatPanel = currentChatPanel(toolWindow)
        val executionService = SpecTaskExecutionService.getInstance(project)
        val sessionManager = SessionManager.getInstance(project)

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.openWorkflowForTest(workflow.id)
        }
        waitUntil {
            specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(firstTask.id) &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(secondTask.id)
        }

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.selectToolbarModelForTest(MockLlmProvider.ID, modelId)
            assertTrue(specPanel.requestExecutionForTaskForTest(firstTask.id))
        }

        waitUntil(timeoutMs = 10_000) {
            chatPanel.workflowBindingSnapshotForTest().getValue("workflowId") == workflow.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == firstTask.id
        }
        waitUntil(timeoutMs = 10_000) {
            executionService.listRuns(workflow.id, firstTask.id).firstOrNull()?.status ==
                TaskExecutionRunStatus.WAITING_CONFIRMATION
        }

        val firstSessionId = chatPanel.workflowBindingSnapshotForTest().getValue("sessionId")
        assertTrue(firstSessionId.isNotBlank())
        assertEquals(1, sessionManager.listSessions().size)

        executionService.resolveWaitingConfirmationRun(
            workflowId = workflow.id,
            taskId = firstTask.id,
            summary = "Complete first task before starting second.",
        )

        waitUntil(timeoutMs = 10_000) {
            chatPanel.workflowBindingSnapshotForTest().getValue("taskChipVisible") == "false"
        }

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(specPanel.requestExecutionForTaskForTest(secondTask.id))
        }

        waitUntil(timeoutMs = 10_000) {
            chatPanel.workflowBindingSnapshotForTest().getValue("workflowId") == workflow.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == secondTask.id
        }
        waitUntil(timeoutMs = 10_000) {
            executionService.listRuns(workflow.id, secondTask.id).firstOrNull()?.status ==
                TaskExecutionRunStatus.WAITING_CONFIRMATION
        }

        val secondSnapshot = chatPanel.workflowBindingSnapshotForTest()
        assertEquals(firstSessionId, secondSnapshot.getValue("sessionId"))
        assertEquals(secondTask.id, secondSnapshot.getValue("taskId"))
        assertEquals("true", secondSnapshot.getValue("taskChipVisible"))
        assertEquals(1, sessionManager.listSessions().size)
    }

    fun `test task execution should restore structured execution launch card instead of raw prompt bubble`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Chat Launch Card",
            description = "task 149 execution launch card restore",
        ).getOrThrow()
        val task = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Render structured execution launch card",
            priority = TaskPriority.P1,
        )
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = false,
            includeTasksDocument = true,
        )

        val modelId = "task-149-${System.nanoTime()}"
        ModelRegistry.getInstance().register(
            ModelInfo(
                id = modelId,
                name = "Task 149 Mock Model",
                provider = MockLlmProvider.ID,
                contextWindow = 32_000,
                capabilities = setOf(ModelCapability.CHAT),
            ),
        )

        val toolWindow = registerSpecCodeToolWindow()
        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val specPanel = currentSpecPanel(toolWindow)
        val chatPanel = currentChatPanel(toolWindow)
        val executionService = SpecTaskExecutionService.getInstance(project)

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.openWorkflowForTest(workflow.id)
        }
        waitUntil {
            specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(task.id)
        }

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.selectToolbarModelForTest(MockLlmProvider.ID, modelId)
            assertTrue(specPanel.requestExecutionForTaskForTest(task.id))
        }

        waitUntil(timeoutMs = 10_000) {
            executionService.listRuns(workflow.id, task.id).firstOrNull()?.status ==
                TaskExecutionRunStatus.WAITING_CONFIRMATION
        }

        val sessionId = chatPanel.workflowBindingSnapshotForTest().getValue("sessionId")
        assertTrue(sessionId.isNotBlank())

        waitUntil(timeoutMs = 10_000) {
            val snapshot = chatPanel.executionLaunchSnapshotForTest()
            snapshot.getValue("visible") == "true" &&
                snapshot.getValue("kind") == "presentation" &&
                snapshot.getValue("taskId") == task.id &&
                snapshot.getValue("workflowId") == workflow.id
        }

        val initialSnapshot = chatPanel.executionLaunchSnapshotForTest()
        assertEquals("presentation", initialSnapshot.getValue("kind"))
        assertEquals(task.id, initialSnapshot.getValue("taskId"))
        assertEquals(workflow.id, initialSnapshot.getValue("workflowId"))
        assertEquals("false", initialSnapshot.getValue("rawPromptVisible"))
        assertTrue(initialSnapshot.getValue("labels").contains("Execution Request"))
        assertFalse(initialSnapshot.getValue("content").contains("Interaction mode: workflow"))

        openSessionFromHistory(sessionId)

        waitUntil(timeoutMs = 10_000) {
            val snapshot = chatPanel.executionLaunchSnapshotForTest()
            snapshot.getValue("visible") == "true" &&
                snapshot.getValue("kind") == "presentation" &&
                snapshot.getValue("taskId") == task.id
        }

        val restoredSnapshot = chatPanel.executionLaunchSnapshotForTest()
        assertEquals("presentation", restoredSnapshot.getValue("kind"))
        assertEquals(task.id, restoredSnapshot.getValue("taskId"))
        assertEquals(workflow.id, restoredSnapshot.getValue("workflowId"))
        assertEquals("false", restoredSnapshot.getValue("rawPromptVisible"))
        assertTrue(restoredSnapshot.getValue("labels").contains("Execution Request"))
        assertFalse(restoredSnapshot.getValue("content").contains("Interaction mode: workflow"))
    }

    fun `test history restore should degrade legacy execution prompt into compact launch card`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Chat Legacy Launch Restore",
            description = "task 151 legacy execution launch restore",
        ).getOrThrow()
        val task = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Restore legacy execution launch prompt",
            priority = TaskPriority.P1,
        )
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = false,
            includeTasksDocument = true,
        )

        val sessionManager = SessionManager.getInstance(project)
        val session = sessionManager.createSession(
            title = "Legacy execution launch restore",
            specTaskId = workflow.id,
            workflowChatBinding = WorkflowChatBinding(
                workflowId = workflow.id,
                focusedStage = StageId.IMPLEMENT,
                source = WorkflowChatEntrySource.SESSION_RESTORE,
                actionIntent = WorkflowChatActionIntent.EXECUTE_TASK,
            ),
        ).getOrThrow()
        val run = TaskExecutionRun(
            runId = "run-legacy-151",
            taskId = task.id,
            status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-18T10:15:00Z",
        )
        val metadataJson = TaskExecutionSessionMetadataCodec.encode(
            run = run,
            workflowId = workflow.id,
            requestId = "request-legacy-151",
            providerId = "mock",
            modelId = "mock-model-v1",
            previousRunId = null,
        )
        sessionManager.addMessage(
            sessionId = session.id,
            role = ConversationRole.USER,
            content = legacyExecutionPrompt(
                workflowId = workflow.id,
                taskId = task.id,
                taskTitle = task.title,
                runId = run.runId,
            ),
            metadataJson = metadataJson,
        ).getOrThrow()
        sessionManager.addMessage(
            sessionId = session.id,
            role = ConversationRole.ASSISTANT,
            content = "Legacy execution is waiting for confirmation.",
        ).getOrThrow()

        val toolWindow = registerSpecCodeToolWindow()
        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val chatPanel = currentChatPanel(toolWindow)

        openSessionFromHistory(session.id)

        waitUntil(timeoutMs = 10_000) {
            val snapshot = chatPanel.executionLaunchSnapshotForTest()
            snapshot.getValue("visible") == "true" &&
                snapshot.getValue("kind") == "legacy" &&
                snapshot.getValue("taskId") == task.id &&
                snapshot.getValue("workflowId") == workflow.id
        }

        val snapshot = chatPanel.executionLaunchSnapshotForTest()
        assertEquals("legacy", snapshot.getValue("kind"))
        assertEquals(workflow.id, snapshot.getValue("workflowId"))
        assertEquals(task.id, snapshot.getValue("taskId"))
        assertEquals(run.runId, snapshot.getValue("runId"))
        assertEquals("MISSING_PRESENTATION_METADATA", snapshot.getValue("fallbackReason"))
        assertEquals("true", snapshot.getValue("debugEntryVisible"))
        assertEquals("false", snapshot.getValue("rawPromptVisible"))
        assertTrue(snapshot.getValue("labels").contains("Execution Request"))
        assertFalse(snapshot.getValue("content").contains("Interaction mode: workflow"))
    }

    fun `test cancelling task execution should keep one finished trace panel in workflow chat`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Chat Cancel Dedupe",
            description = "task 154 cancel execution panel dedupe",
        ).getOrThrow()
        val task = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Collapse cancelled execution lifecycle",
            priority = TaskPriority.P1,
        )
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = false,
            includeTasksDocument = true,
        )

        val modelId = "task-154-${System.nanoTime()}"
        ModelRegistry.getInstance().register(
            ModelInfo(
                id = modelId,
                name = "Task 154 Mock Model",
                provider = MockLlmProvider.ID,
                contextWindow = 32_000,
                capabilities = setOf(ModelCapability.CHAT),
            ),
        )

        val toolWindow = registerSpecCodeToolWindow()
        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val specPanel = currentSpecPanel(toolWindow)
        val chatPanel = currentChatPanel(toolWindow)
        val executionService = SpecTaskExecutionService.getInstance(project)

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.openWorkflowForTest(workflow.id)
        }
        waitUntil {
            specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(task.id)
        }

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.selectToolbarModelForTest(MockLlmProvider.ID, modelId)
            assertTrue(specPanel.requestExecutionForTaskForTest(task.id))
        }

        waitUntil(timeoutMs = 10_000) {
            chatPanel.workflowBindingSnapshotForTest().getValue("workflowId") == workflow.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == task.id &&
                chatPanel.liveTaskExecutionSnapshotForTest().getValue("visible") == "true"
        }
        waitUntil(timeoutMs = 10_000) {
            executionService.listRuns(workflow.id, task.id).firstOrNull()?.status in
                setOf(TaskExecutionRunStatus.QUEUED, TaskExecutionRunStatus.RUNNING)
        }

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(specPanel.requestExecutionForTaskForTest(task.id))
        }

        waitUntil(timeoutMs = 15_000) {
            executionService.listRuns(workflow.id, task.id).firstOrNull()?.status ==
                TaskExecutionRunStatus.CANCELLED
        }
        waitUntil(timeoutMs = 15_000) {
            chatPanel.liveTaskExecutionSnapshotForTest().getValue("visible") == "false" &&
                chatPanel.executionTracePanelsSnapshotForTest().getValue("count") == "1" &&
                chatPanel.executionTracePanelsSnapshotForTest().getValue("unfinishedCount") == "0"
        }

        val traceSnapshot = chatPanel.executionTracePanelsSnapshotForTest()
        assertEquals("1", traceSnapshot.getValue("count"))
        assertEquals("0", traceSnapshot.getValue("unfinishedCount"))
        assertEquals("false", chatPanel.liveTaskExecutionSnapshotForTest().getValue("visible"))
    }

    fun `test task execution session sync should append only current cancelled run messages`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Chat Cancel Sync",
            description = "avoid loading historical execution traces on cancel",
        ).getOrThrow()
        val task = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Cancel current run without flooding chat history",
            priority = TaskPriority.P1,
        )
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = false,
            includeTasksDocument = true,
        )

        val sessionManager = SessionManager.getInstance(project)
        val session = sessionManager.createSession(
            title = "Workflow Chat Cancel Sync",
            specTaskId = workflow.id,
            workflowChatBinding = WorkflowChatBinding(
                workflowId = workflow.id,
                focusedStage = StageId.IMPLEMENT,
                source = WorkflowChatEntrySource.SESSION_RESTORE,
                actionIntent = WorkflowChatActionIntent.DISCUSS,
            ),
        ).getOrThrow()

        val toolWindow = registerSpecCodeToolWindow()
        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val chatPanel = currentChatPanel(toolWindow)
        openSessionFromHistory(session.id)
        waitUntil(timeoutMs = 10_000) {
            chatPanel.workflowBindingSnapshotForTest().getValue("sessionId") == session.id
        }

        val historicalRunA = TaskExecutionRun(
            runId = "run-history-a",
            taskId = task.id,
            status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-18T10:00:00Z",
        )
        val historicalRunB = TaskExecutionRun(
            runId = "run-history-b",
            taskId = task.id,
            status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
            trigger = ExecutionTrigger.USER_RETRY,
            startedAt = "2026-03-18T10:05:00Z",
        )
        sessionManager.addMessage(
            sessionId = session.id,
            role = ConversationRole.ASSISTANT,
            content = "Historical execution A",
            metadataJson = TaskExecutionSessionMetadataCodec.encode(
                run = historicalRunA,
                workflowId = workflow.id,
                requestId = "request-history-a",
                providerId = MockLlmProvider.ID,
                modelId = "mock-cancel-sync",
                previousRunId = null,
                traceEvents = listOf(
                    ChatStreamEvent(
                        kind = ChatTraceKind.TASK,
                        detail = "Historical execution A",
                        status = ChatTraceStatus.INFO,
                    ),
                ),
                startedAtMillis = Instant.parse("2026-03-18T10:00:00Z").toEpochMilli(),
                finishedAtMillis = Instant.parse("2026-03-18T10:01:00Z").toEpochMilli(),
            ),
        ).getOrThrow()
        sessionManager.addMessage(
            sessionId = session.id,
            role = ConversationRole.ASSISTANT,
            content = "Historical execution B",
            metadataJson = TaskExecutionSessionMetadataCodec.encode(
                run = historicalRunB,
                workflowId = workflow.id,
                requestId = "request-history-b",
                providerId = MockLlmProvider.ID,
                modelId = "mock-cancel-sync",
                previousRunId = historicalRunA.runId,
                traceEvents = listOf(
                    ChatStreamEvent(
                        kind = ChatTraceKind.TASK,
                        detail = "Historical execution B",
                        status = ChatTraceStatus.INFO,
                    ),
                ),
                startedAtMillis = Instant.parse("2026-03-18T10:05:00Z").toEpochMilli(),
                finishedAtMillis = Instant.parse("2026-03-18T10:06:00Z").toEpochMilli(),
            ),
        ).getOrThrow()

        val currentRun = TaskExecutionRun(
            runId = "run-cancel-current",
            taskId = task.id,
            status = TaskExecutionRunStatus.RUNNING,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-18T10:10:00Z",
        )
        val currentRequestId = "request-cancel-current"
        val launchMetadata = TaskExecutionSessionMetadataCodec.encode(
            run = currentRun,
            workflowId = workflow.id,
            requestId = currentRequestId,
            providerId = MockLlmProvider.ID,
            modelId = "mock-cancel-sync",
            previousRunId = historicalRunB.runId,
            launchPresentation = WorkflowChatExecutionLaunchPresentation(
                workflowId = workflow.id,
                taskId = task.id,
                taskTitle = task.title,
                runId = currentRun.runId,
                focusedStage = StageId.TASKS,
                trigger = ExecutionTrigger.USER_EXECUTE,
                launchSurface = WorkflowChatExecutionLaunchSurface.TASK_ROW,
                taskStatusBeforeExecution = TaskStatus.PENDING,
                taskPriority = task.priority,
            ),
        )
        sessionManager.addMessage(
            sessionId = session.id,
            role = ConversationRole.USER,
            content = "## Execution Request\n- Task: ${task.id}",
            metadataJson = launchMetadata,
        ).getOrThrow()
        sessionManager.addMessage(
            sessionId = session.id,
            role = ConversationRole.SYSTEM,
            content = "AI execution cancelled by user.",
            metadataJson = TaskExecutionSessionMetadataCodec.encode(
                run = currentRun,
                workflowId = workflow.id,
                requestId = currentRequestId,
                providerId = MockLlmProvider.ID,
                modelId = "mock-cancel-sync",
                previousRunId = historicalRunB.runId,
            ),
        ).getOrThrow()

        ApplicationManager.getApplication().invokeAndWait {
            chatPanel.applyTaskLiveProgressForTest(
                TaskExecutionLiveProgress(
                    workflowId = workflow.id,
                    runId = currentRun.runId,
                    taskId = task.id,
                    phase = ExecutionLivePhase.STREAMING,
                    startedAt = Instant.parse("2026-03-18T10:10:00Z"),
                    lastUpdatedAt = Instant.parse("2026-03-18T10:10:30Z"),
                    lastDetail = "Running current execution",
                    recentEvents = listOf(
                        ChatStreamEvent(
                            kind = ChatTraceKind.TASK,
                            detail = "Running current execution",
                            status = ChatTraceStatus.RUNNING,
                        ),
                    ),
                    providerId = MockLlmProvider.ID,
                    modelId = "mock-cancel-sync",
                ),
            )
        }
        waitUntil(timeoutMs = 10_000) {
            chatPanel.liveTaskExecutionSnapshotForTest().getValue("visible") == "true"
        }

        val initialMessageCount = chatPanel.messageCountForTest()
        assertEquals(2, initialMessageCount)
        assertEquals("false", chatPanel.liveTaskExecutionSnapshotForTest().getValue("finished"))

        ApplicationManager.getApplication().invokeAndWait {
            chatPanel.syncTaskExecutionSessionMessagesForTest(session.id, currentRun.runId, currentRequestId)
        }

        waitUntil(timeoutMs = 10_000) {
            chatPanel.executionLaunchSnapshotForTest().getValue("runId") == currentRun.runId &&
                chatPanel.liveTaskExecutionSnapshotForTest().getValue("visible") == "false" &&
                chatPanel.executionTracePanelsSnapshotForTest().getValue("unfinishedCount") == "0"
        }

        val launchSnapshot = chatPanel.executionLaunchSnapshotForTest()
        val liveSnapshot = chatPanel.liveTaskExecutionSnapshotForTest()
        val traceSnapshot = chatPanel.executionTracePanelsSnapshotForTest()
        assertEquals(currentRun.runId, launchSnapshot.getValue("runId"))
        assertEquals(task.id, launchSnapshot.getValue("taskId"))
        assertEquals("false", liveSnapshot.getValue("visible"))
        assertEquals("0", traceSnapshot.getValue("unfinishedCount"))
        assertEquals(4, chatPanel.messageCountForTest())
    }

    private fun registerSpecCodeToolWindow(): ToolWindow {
        var toolWindow: ToolWindow? = null
        ApplicationManager.getApplication().invokeAndWait {
            toolWindow = ToolWindowManager.getInstance(project).registerToolWindow(
                RegisterToolWindowTask.notClosable(
                    ChatToolWindowFactory.TOOL_WINDOW_ID,
                    ToolWindowAnchor.RIGHT,
                ),
            )
        }
        return toolWindow ?: error("Failed to register test tool window")
    }

    private fun currentChatPanel(toolWindow: ToolWindow): ImprovedChatPanel {
        return toolWindow.contentManager.contents
            .first(ChatToolWindowFactory::isChatContent)
            .component as ImprovedChatPanel
    }

    private fun currentSpecPanel(toolWindow: ToolWindow): SpecWorkflowPanel {
        return toolWindow.contentManager.contents
            .first(ChatToolWindowFactory::isSpecContent)
            .component as SpecWorkflowPanel
    }

    private fun openSessionFromHistory(sessionId: String) {
        ApplicationManager.getApplication().invokeAndWait {
            project.messageBus.syncPublisher(HistorySessionOpenListener.TOPIC)
                .onSessionOpenRequested(sessionId)
        }
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun legacyExecutionPrompt(
        workflowId: String,
        taskId: String,
        taskTitle: String,
        runId: String,
    ): String {
        return """
            Interaction mode: workflow
            Workflow=$workflowId (docs: .spec-coding/specs/$workflowId/{requirements,design,tasks}.md)
            Execution action: EXECUTE_WITH_AI
            Run ID: $runId
            
            ## Task
            Task ID: $taskId
            Task Title: $taskTitle
            Task Status: PENDING
            Priority: P1
            
            ## Stage Context
            Current phase: IMPLEMENT
            Current stage: TASKS
            
            ## Artifact Summaries
            - requirements.md: Workflow Chat should show a structured execution request card.
            
            ## Candidate Related Files
            - src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt
            
            ## Supplemental Instruction
            Keep the launch summary compact.
            
            ## Execution Request
            Continue this task in the bound workflow chat session.
        """.trimIndent()
    }

    private fun stageWorkflow(
        workflowId: String,
        currentStage: StageId,
        verifyEnabled: Boolean,
        includeTasksDocument: Boolean,
    ) {
        val storage = SpecStorage.getInstance(project)
        val current = storage.loadWorkflow(workflowId).getOrThrow()
        storage.saveWorkflow(
            current.copy(
                currentPhase = phaseForStage(currentStage),
                currentStage = currentStage,
                verifyEnabled = verifyEnabled,
                stageStates = buildStageStates(current.stageStates, currentStage, verifyEnabled),
                documents = buildDocuments(workflowId, includeTasksDocument),
                status = WorkflowStatus.IN_PROGRESS,
                updatedAt = System.currentTimeMillis(),
            ),
        ).getOrThrow()
    }

    private fun buildDocuments(workflowId: String, includeTasksDocument: Boolean): Map<SpecPhase, SpecDocument> {
        if (!includeTasksDocument) {
            return emptyMap()
        }
        val tasksContent = SpecArtifactService(project).readArtifact(workflowId, StageId.TASKS)
            ?: return emptyMap()
        return mapOf(
            SpecPhase.IMPLEMENT to SpecDocument(
                id = "$workflowId-tasks",
                phase = SpecPhase.IMPLEMENT,
                content = tasksContent,
                metadata = SpecMetadata(
                    title = "tasks.md",
                    description = "Structured tasks for $workflowId",
                ),
            ),
        )
    }

    private fun buildStageStates(
        existing: Map<StageId, StageState>,
        currentStage: StageId,
        verifyEnabled: Boolean,
    ): Map<StageId, StageState> {
        val marker = "2026-03-18T00:00:00Z"
        return StageId.entries.associateWith { stageId ->
            val active = when (stageId) {
                StageId.VERIFY -> verifyEnabled
                else -> existing[stageId]?.active ?: true
            }
            when {
                !active -> StageState(active = false, status = StageProgress.NOT_STARTED)
                stageId.ordinal < currentStage.ordinal -> StageState(
                    active = true,
                    status = StageProgress.DONE,
                    enteredAt = marker,
                    completedAt = marker,
                )

                stageId == currentStage -> StageState(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                    enteredAt = marker,
                )

                else -> StageState(active = true, status = StageProgress.NOT_STARTED)
            }
        }
    }

    private fun phaseForStage(stageId: StageId): SpecPhase {
        return when (stageId) {
            StageId.REQUIREMENTS -> SpecPhase.SPECIFY
            StageId.DESIGN -> SpecPhase.DESIGN
            StageId.TASKS,
            StageId.IMPLEMENT,
            StageId.VERIFY,
            StageId.ARCHIVE -> SpecPhase.IMPLEMENT
        }
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            if (condition()) {
                return
            }
            Thread.sleep(50)
        }
        fail("Condition was not met within ${timeoutMs}ms")
    }
}
