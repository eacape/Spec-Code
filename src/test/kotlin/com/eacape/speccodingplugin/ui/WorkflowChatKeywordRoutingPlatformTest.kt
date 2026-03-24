package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.session.ConversationRole
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.session.WorkflowChatActionIntent
import com.eacape.speccodingplugin.session.WorkflowChatBinding
import com.eacape.speccodingplugin.session.WorkflowChatEntrySource
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecTaskExecutionService
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.ui.history.HistorySessionOpenListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class WorkflowChatKeywordRoutingPlatformTest : BasePlatformTestCase() {

    fun testWorkflowChatShouldTreatExecuteKeywordTextAsDiscussionInput() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Workflow Chat Keywords",
            description = "task 136",
        ).getOrThrow()
        val task = SpecTasksService(project).addTask(
            workflowId = workflow.id,
            title = "Execute from task row instead",
            priority = TaskPriority.P0,
        )
        val sessionManager = SessionManager.getInstance(project)
        val executionService = SpecTaskExecutionService(project)
        val session = sessionManager.createSession(
            title = "Workflow discussion",
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
            val snapshot = chatPanel.workflowBindingSnapshotForTest()
            snapshot.getValue("sessionId") == session.id &&
                snapshot.getValue("workflowId") == workflow.id &&
                snapshot.getValue("sendActionKind") == "CHAT_SEND"
        }

        ApplicationManager.getApplication().invokeAndWait {
            chatPanel.setComposerInputForTest("执行 ${task.id}")
            chatPanel.clickSendForTest()
        }
        UIUtil.dispatchAllInvocationEvents()

        waitUntil {
            sessionManager.listMessages(session.id).any { message ->
                message.role == ConversationRole.USER && message.content == "执行 ${task.id}"
            }
        }

        val loadedSession = sessionManager.getSession(session.id)
        assertEquals(WorkflowChatActionIntent.DISCUSS, loadedSession?.workflowChatBinding?.actionIntent)
        assertTrue(executionService.listRuns(workflow.id, task.id).isEmpty())
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
