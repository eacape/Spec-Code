package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.session.WorkflowChatActionIntent
import com.eacape.speccodingplugin.session.WorkflowChatBinding
import com.eacape.speccodingplugin.session.WorkflowChatEntrySource
import com.eacape.speccodingplugin.spec.ExecutionLivePhase
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecTaskExecutionService
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import com.eacape.speccodingplugin.ui.WorkflowChatRefreshEvent
import com.eacape.speccodingplugin.ui.WorkflowChatRefreshListener
import com.eacape.speccodingplugin.ui.history.HistorySessionOpenListener
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowContentUiType
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.time.Instant

class ChatToolWindowFactoryPlatformTest : BasePlatformTestCase() {

    fun `test create tool window content should expose chat and spec header tabs`() {
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val primaryContents = toolWindow.contentManager.contents.filter(ChatToolWindowFactory::isPrimaryContent)
        assertEquals(2, primaryContents.size)
        assertTrue(primaryContents.any { it.displayName == SpecCodingBundle.message("toolwindow.tab.chat") })
        assertTrue(primaryContents.any { it.displayName == SpecCodingBundle.message("spec.tab.title") })
        assertTrue(primaryContents.any { it.component is ImprovedChatPanel })
        assertTrue(primaryContents.any { it.component is SpecWorkflowPanel })
        assertEquals(ToolWindowContentUiType.TABBED, toolWindow.contentUiType)
    }

    fun `test select spec content should activate spec content tab`() {
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(ChatToolWindowFactory.selectSpecContent(toolWindow, project))
        val selected = toolWindow.contentManager.selectedContent
        assertNotNull(selected)
        assertTrue(ChatToolWindowFactory.isSpecContent(selected))
        assertEquals(SpecCodingBundle.message("spec.tab.title"), selected!!.displayName)
    }

    fun `test removing spec content should restore primary tabs`() {
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        ApplicationManager.getApplication().invokeAndWait {
            val specContent = toolWindow.contentManager.contents.firstOrNull(ChatToolWindowFactory::isSpecContent)
            assertNotNull(specContent)
            toolWindow.contentManager.removeContent(specContent!!, true)
        }
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(toolWindow.contentManager.contents.any(ChatToolWindowFactory::isChatContent))
        assertTrue(toolWindow.contentManager.contents.any(ChatToolWindowFactory::isSpecContent))
    }

    fun `test chat composer should embed image preview inside composer surface`() {
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val chatPanel = currentChatPanel(toolWindow)
        assertTrue(chatPanel.isImageAttachmentPreviewEmbeddedInComposerForTest())
    }

    fun `test task panel should open chat with workflow and task binding chips`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Chat Binding",
            description = "task 79",
        ).getOrThrow()
        val task = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Discuss task scope",
            priority = TaskPriority.P1,
        )
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val specPanel = currentSpecPanel(toolWindow)
        val chatPanel = currentChatPanel(toolWindow)

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.openWorkflowForTest(workflow.id)
        }
        waitUntil {
            specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(task.id)
        }

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(specPanel.selectTaskForTest(task.id))
            specPanel.clickOpenWorkflowChatForSelectedTaskForTest()
        }
        UIUtil.dispatchAllInvocationEvents()

        waitUntil {
            ChatToolWindowFactory.isChatContent(toolWindow.contentManager.selectedContent) &&
                chatPanel.workflowBindingSnapshotForTest().getValue("workflowId") == workflow.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == task.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskChipVisible") == "true"
        }

        val snapshot = chatPanel.workflowBindingSnapshotForTest()
        assertEquals("SPEC", snapshot.getValue("mode"))
        assertEquals("false", snapshot.getValue("workflowChipVisible"))
        assertEquals("true", snapshot.getValue("taskChipVisible"))
        assertEquals(
            SpecCodingBundle.message(
                "toolwindow.workflow.binding.task.summary",
                task.id,
                SpecCodingBundle.message("toolwindow.workflow.binding.task.status.pending"),
            ),
            snapshot.getValue("taskChipText"),
        )
        assertEquals(
            SpecCodingBundle.message(
                "toolwindow.workflow.binding.task.tooltip.state",
                task.id,
                SpecCodingBundle.message("toolwindow.workflow.binding.task.status.pending"),
            ),
            snapshot.getValue("taskChipTooltip"),
        )
        assertEquals("", snapshot.getValue("taskChipIconId"))
        assertEquals("false", snapshot.getValue("taskChipBorderPainted"))
        assertEquals("false", snapshot.getValue("taskChipContentFilled"))
        assertEquals("false", snapshot.getValue("taskChipOpaque"))
        assertEquals("false", snapshot.getValue("retryTaskVisible"))
        assertEquals("false", snapshot.getValue("retryTaskEnabled"))
        assertEquals("false", snapshot.getValue("executeTaskVisible"))
        assertEquals("false", snapshot.getValue("executeTaskEnabled"))
        assertEquals("false", snapshot.getValue("completeTaskVisible"))
        assertEquals("false", snapshot.getValue("completeTaskEnabled"))
        assertEquals(
            "CHAT_SEND",
            snapshot.getValue("sendActionKind"),
        )
        assertEquals(
            SpecCodingBundle.message("toolwindow.send"),
            snapshot.getValue("sendTooltip"),
        )
        assertEquals("true", snapshot.getValue("sendEnabled"))
        assertEquals("false", snapshot.getValue("taskMoreVisible"))
        assertEquals("false", snapshot.getValue("taskClearVisible"))
        assertEquals("true", snapshot.getValue("composerAccessoryVisible"))
        assertFalse(snapshot.getValue("sessionId").isBlank())
    }

    fun `test bound task should disable send until dependencies are completed`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Dependency Guard",
            description = "task 99",
        ).getOrThrow()
        val tasksService = SpecTasksService(project)
        val dependencyTask = tasksService.addTask(
            workflowId = workflow.id,
            title = "Dependency task",
            priority = TaskPriority.P0,
        )
        val blockedTask = tasksService.addTask(
            workflowId = workflow.id,
            title = "Blocked until dependency completes",
            priority = TaskPriority.P1,
            dependsOn = listOf(dependencyTask.id),
        )
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val specPanel = currentSpecPanel(toolWindow)
        val chatPanel = currentChatPanel(toolWindow)

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.openWorkflowForTest(workflow.id)
        }
        waitUntil {
            specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(blockedTask.id)
        }

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(specPanel.selectTaskForTest(blockedTask.id))
            specPanel.clickOpenWorkflowChatForSelectedTaskForTest()
        }
        waitUntil {
            chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == blockedTask.id
        }

        var snapshot = chatPanel.workflowBindingSnapshotForTest()
        assertEquals("CHAT_SEND", snapshot.getValue("sendActionKind"))
        assertEquals("true", snapshot.getValue("sendEnabled"))
        assertEquals(SpecCodingBundle.message("toolwindow.send"), snapshot.getValue("sendTooltip"))

        tasksService.transitionStatus(
            workflowId = workflow.id,
            taskId = dependencyTask.id,
            to = TaskStatus.COMPLETED,
        )
        project.messageBus.syncPublisher(WorkflowChatRefreshListener.TOPIC)
            .onWorkflowChatRefreshRequested(
                WorkflowChatRefreshEvent(
                    workflowId = workflow.id,
                    taskId = blockedTask.id,
                    focusedStage = StageId.IMPLEMENT,
                    reason = "dependency_completed",
                ),
            )

        waitUntil {
            chatPanel.workflowBindingSnapshotForTest().getValue("sendEnabled") == "true"
        }

        snapshot = chatPanel.workflowBindingSnapshotForTest()
        assertEquals("CHAT_SEND", snapshot.getValue("sendActionKind"))
        assertEquals("true", snapshot.getValue("sendEnabled"))
        assertEquals(SpecCodingBundle.message("toolwindow.send"), snapshot.getValue("sendTooltip"))
    }

    fun `test history open should normalize legacy spec session into workflow binding UI`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Legacy Spec Session Upgrade",
            description = "task 83 legacy session",
        ).getOrThrow()
        val session = SessionManager.getInstance(project).createSession(
            title = "/spec status",
            specTaskId = workflow.id,
        ).getOrThrow()
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val chatPanel = currentChatPanel(toolWindow)

        openSessionFromHistory(session.id)

        waitUntil {
            val snapshot = chatPanel.workflowBindingSnapshotForTest()
            snapshot.getValue("sessionId") == session.id &&
                snapshot.getValue("mode") == "SPEC" &&
                snapshot.getValue("workflowId") == workflow.id
        }

        val snapshot = chatPanel.workflowBindingSnapshotForTest()
        assertEquals("SPEC", snapshot.getValue("mode"))
        assertEquals(workflow.id, snapshot.getValue("workflowId"))
        assertEquals("", snapshot.getValue("taskId"))
        assertEquals("false", snapshot.getValue("workflowChipVisible"))
        assertEquals("false", snapshot.getValue("taskChipVisible"))
    }

    fun `test task binding chip should deep link back to workflow task in specs tab`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Task Deep Link",
            description = "task 82",
        ).getOrThrow()
        val tasksService = SpecTasksService(project)
        val primaryTask = tasksService.addTask(
            workflowId = workflow.id,
            title = "Primary linked task",
            priority = TaskPriority.P0,
        )
        val secondaryTask = tasksService.addTask(
            workflowId = workflow.id,
            title = "Secondary task",
            priority = TaskPriority.P1,
        )
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val specPanel = currentSpecPanel(toolWindow)
        val chatPanel = currentChatPanel(toolWindow)

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.openWorkflowForTest(workflow.id)
        }
        waitUntil {
            specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(primaryTask.id) &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(secondaryTask.id)
        }

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(specPanel.selectTaskForTest(primaryTask.id))
            specPanel.clickOpenWorkflowChatForSelectedTaskForTest()
        }
        waitUntil {
            chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == primaryTask.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory.selectSpecContent(toolWindow, project)
            assertTrue(specPanel.selectTaskForTest(secondaryTask.id))
            specPanel.focusStageForTest(StageId.TASKS)
        }
        UIUtil.dispatchAllInvocationEvents()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory.selectChatContent(toolWindow, project)
            chatPanel.clickTaskBindingChipForTest()
        }
        UIUtil.dispatchAllInvocationEvents()

        waitUntil {
            ChatToolWindowFactory.isSpecContent(toolWindow.contentManager.selectedContent) &&
                specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.focusedStageForTest() == StageId.IMPLEMENT &&
                specPanel.tasksSnapshotForTest().getValue("selectedTaskId") == primaryTask.id
        }
    }

    fun `test history open should restore persisted task binding and keep spec selection consistent`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Persisted Task Binding Restore",
            description = "task 83 persisted binding",
        ).getOrThrow()
        val task = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Restore bound task selection",
            priority = TaskPriority.P0,
        )
        val session = SessionManager.getInstance(project).createSession(
            title = "Workflow task restore",
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
        val specPanel = currentSpecPanel(toolWindow)

        openSessionFromHistory(session.id)

        waitUntil {
            val snapshot = chatPanel.workflowBindingSnapshotForTest()
            snapshot.getValue("sessionId") == session.id &&
                snapshot.getValue("taskId") == task.id &&
                snapshot.getValue("taskChipVisible") == "true"
        }

        ApplicationManager.getApplication().invokeAndWait {
            chatPanel.clickTaskBindingChipForTest()
        }
        UIUtil.dispatchAllInvocationEvents()

        waitUntil {
            ChatToolWindowFactory.isSpecContent(toolWindow.contentManager.selectedContent) &&
                specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.focusedStageForTest() == StageId.IMPLEMENT &&
                specPanel.tasksSnapshotForTest().getValue("selectedTaskId") == task.id
        }
    }

    fun `test workflow chat refresh event should update bound task run state presentation`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Chat Refresh",
            description = "task 82 refresh",
        ).getOrThrow()
        val task = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Refresh bound task",
            priority = TaskPriority.P0,
        )
        val executionService = SpecTaskExecutionService(project)
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val specPanel = currentSpecPanel(toolWindow)
        val chatPanel = currentChatPanel(toolWindow)

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.openWorkflowForTest(workflow.id)
        }
        waitUntil {
            specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(task.id)
        }

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(specPanel.selectTaskForTest(task.id))
            specPanel.clickOpenWorkflowChatForSelectedTaskForTest()
        }
        waitUntil {
            chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == task.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("sendActionKind") == "CHAT_SEND"
        }

        val run = executionService.createRun(
            workflowId = workflow.id,
            taskId = task.id,
            status = TaskExecutionRunStatus.RUNNING,
            trigger = ExecutionTrigger.USER_EXECUTE,
        )
        project.messageBus.syncPublisher(WorkflowChatRefreshListener.TOPIC)
            .onWorkflowChatRefreshRequested(
                WorkflowChatRefreshEvent(
                    workflowId = workflow.id,
                    taskId = task.id,
                    focusedStage = StageId.IMPLEMENT,
                    reason = "test_running_refresh",
                ),
            )

        waitUntil {
            chatPanel.workflowBindingSnapshotForTest().getValue("taskChipText").contains(
                SpecCodingBundle.message("toolwindow.workflow.binding.task.status.inProgress"),
            )
        }

        val runningSnapshot = chatPanel.workflowBindingSnapshotForTest()
        assertEquals("CHAT_SEND", runningSnapshot.getValue("sendActionKind"))
        assertEquals(SpecCodingBundle.message("toolwindow.send"), runningSnapshot.getValue("sendTooltip"))
        assertEquals("false", runningSnapshot.getValue("executeTaskVisible"))
        assertEquals("false", runningSnapshot.getValue("retryTaskVisible"))
        assertEquals("false", runningSnapshot.getValue("completeTaskVisible"))

        executionService.updateRunStatus(
            workflowId = workflow.id,
            runId = run.runId,
            status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
        )
        project.messageBus.syncPublisher(WorkflowChatRefreshListener.TOPIC)
            .onWorkflowChatRefreshRequested(
                WorkflowChatRefreshEvent(
                    workflowId = workflow.id,
                    taskId = task.id,
                    focusedStage = StageId.IMPLEMENT,
                    reason = "test_waiting_confirmation",
                ),
            )

        waitUntil {
            chatPanel.workflowBindingSnapshotForTest().getValue("taskChipText").contains(
                SpecCodingBundle.message("toolwindow.workflow.binding.task.status.waitingConfirmation"),
            )
        }

        val snapshot = chatPanel.workflowBindingSnapshotForTest()
        assertEquals(
            SpecCodingBundle.message(
                "toolwindow.workflow.binding.task.tooltip.run",
                task.id,
                SpecCodingBundle.message("toolwindow.workflow.binding.task.status.waitingConfirmation"),
                run.runId,
                ExecutionLivePhase.WAITING_CONFIRMATION.name,
            ),
            snapshot.getValue("taskChipTooltip"),
        )
        assertEquals("CHAT_SEND", snapshot.getValue("sendActionKind"))
        assertEquals(SpecCodingBundle.message("toolwindow.send"), snapshot.getValue("sendTooltip"))
        assertEquals("false", snapshot.getValue("executeTaskEnabled"))
        assertEquals("false", snapshot.getValue("retryTaskEnabled"))
        assertEquals("false", snapshot.getValue("completeTaskEnabled"))
        assertEquals("false", snapshot.getValue("executeTaskVisible"))
        assertEquals("false", snapshot.getValue("retryTaskVisible"))
        assertEquals("false", snapshot.getValue("completeTaskVisible"))
    }

    fun `test workflow chat live progress should update bound task state and render execution trace`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Chat Live Progress",
            description = "task 98",
        ).getOrThrow()
        val task = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Observe execution trace",
            priority = TaskPriority.P0,
        )
        val toolWindow = registerSpecCodeToolWindow()

        ApplicationManager.getApplication().invokeAndWait {
            ChatToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
        UIUtil.dispatchAllInvocationEvents()

        val specPanel = currentSpecPanel(toolWindow)
        val chatPanel = currentChatPanel(toolWindow)

        ApplicationManager.getApplication().invokeAndWait {
            specPanel.openWorkflowForTest(workflow.id)
        }
        waitUntil {
            specPanel.selectedWorkflowIdForTest() == workflow.id &&
                specPanel.tasksSnapshotForTest().getValue("tasks").contains(task.id)
        }

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(specPanel.selectTaskForTest(task.id))
            specPanel.clickOpenWorkflowChatForSelectedTaskForTest()
        }
        waitUntil {
            chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == task.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("sendActionKind") == "CHAT_SEND"
        }

        ApplicationManager.getApplication().invokeAndWait {
            chatPanel.applyTaskLiveProgressForTest(
                TaskExecutionLiveProgress(
                    workflowId = workflow.id,
                    runId = "run-live-001",
                    taskId = task.id,
                    phase = ExecutionLivePhase.STREAMING,
                    startedAt = Instant.parse("2026-03-15T11:00:00Z"),
                    lastUpdatedAt = Instant.parse("2026-03-15T11:00:01Z"),
                    lastDetail = "Applying workflow change",
                    recentEvents = listOf(
                        ChatStreamEvent(
                            kind = ChatTraceKind.READ,
                            detail = "SpecWorkflowPanel.kt",
                            status = ChatTraceStatus.RUNNING,
                        ),
                        ChatStreamEvent(
                            kind = ChatTraceKind.OUTPUT,
                            detail = "Applying workflow change",
                            status = ChatTraceStatus.RUNNING,
                        ),
                    ),
                    providerId = "mock",
                    modelId = "mock-model-v1",
                ),
            )
        }
        UIUtil.dispatchAllInvocationEvents()

        waitUntil {
            chatPanel.workflowBindingSnapshotForTest().getValue("taskChipText").contains(
                SpecCodingBundle.message("toolwindow.workflow.binding.task.status.inProgress"),
            ) &&
                chatPanel.liveTaskExecutionSnapshotForTest().getValue("visible") == "true"
        }

        val snapshot = chatPanel.workflowBindingSnapshotForTest()
        val liveSnapshot = chatPanel.liveTaskExecutionSnapshotForTest()
        assertTrue(
            snapshot.getValue("taskChipText").contains(
                SpecCodingBundle.message("toolwindow.workflow.binding.task.status.inProgress"),
            ),
        )
        assertEquals(
            SpecCodingBundle.message(
                "toolwindow.workflow.binding.task.tooltip.run",
                task.id,
                SpecCodingBundle.message("toolwindow.workflow.binding.task.status.inProgress"),
                "run-live-001",
                ExecutionLivePhase.STREAMING.name,
            ),
            snapshot.getValue("taskChipTooltip"),
        )
        assertEquals("CHAT_SEND", snapshot.getValue("sendActionKind"))
        assertEquals(SpecCodingBundle.message("toolwindow.send"), snapshot.getValue("sendTooltip"))
        assertEquals("true", liveSnapshot.getValue("visible"))
        assertEquals("run-live-001", liveSnapshot.getValue("runId"))
        assertEquals(task.id, liveSnapshot.getValue("taskId"))
        assertTrue(liveSnapshot.getValue("labels").contains(SpecCodingBundle.message("chat.timeline.summary.label")))
        assertTrue(liveSnapshot.getValue("labels").contains(SpecCodingBundle.message("chat.timeline.kind.read")))
        assertTrue(liveSnapshot.getValue("labels").contains(SpecCodingBundle.message("chat.timeline.kind.output")))
    }

    fun `test workflow attachment boundary should surface explicit status in bound chat`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Attachment Boundary",
            description = "task 83 attachments",
        ).getOrThrow()
        val task = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Discuss attachment boundary",
            priority = TaskPriority.P1,
        )
        val session = SessionManager.getInstance(project).createSession(
            title = "Workflow attachment boundary",
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

        waitUntil {
            chatPanel.workflowBindingSnapshotForTest().getValue("sessionId") == session.id &&
                chatPanel.workflowBindingSnapshotForTest().getValue("taskId") == task.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            chatPanel.addImageAttachmentsForTest(listOf("C:/tmp/context-image.png"))
            chatPanel.showWorkflowAttachmentBoundaryForTest()
        }
        UIUtil.dispatchAllInvocationEvents()

        val statusSnapshot = chatPanel.statusSnapshotForTest()
        assertEquals(
            SpecCodingBundle.message("toolwindow.workflow.attachment.boundary"),
            statusSnapshot.getValue("text"),
        )
        assertEquals("true", statusSnapshot.getValue("visible"))
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
