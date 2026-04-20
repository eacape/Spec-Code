package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.session.WorkflowChatActionIntent
import com.eacape.speccodingplugin.session.WorkflowChatBinding
import com.eacape.speccodingplugin.session.WorkflowChatEntrySource
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.ui.WorkflowChatOpenRequest

internal class SpecWorkflowTaskChatCoordinator(
    private val activateChatToolWindow: () -> Boolean,
    private val invokeLater: ((() -> Unit) -> Unit),
    private val isDisposed: () -> Boolean,
    private val openHistorySession: (String) -> Unit,
    private val publishWorkflowChatRefresh: (workflowId: String, taskId: String?, reason: String) -> Unit,
    private val openWorkflowChat: (WorkflowChatOpenRequest) -> Unit,
) {

    fun openExecutionSession(sessionId: String, workflowId: String) {
        val normalizedSessionId = sessionId.trim().ifBlank { return }
        val normalizedWorkflowId = workflowId.trim().ifBlank { return }
        invokeLater {
            if (isDisposed()) {
                return@invokeLater
            }
            if (!activateChatToolWindow()) {
                return@invokeLater
            }
            openHistorySession(normalizedSessionId)
            publishWorkflowChatRefresh(
                normalizedWorkflowId,
                null,
                "spec_task_execution_session_opened",
            )
        }
    }

    fun openTaskWorkflowChat(workflowId: String, taskId: String) {
        taskId.trim().ifBlank { return }
        val request = buildTaskWorkflowChatRequest(workflowId) ?: return
        if (!activateChatToolWindow()) {
            return
        }
        invokeLater {
            if (isDisposed()) {
                return@invokeLater
            }
            openWorkflowChat(request)
        }
    }

    private fun buildTaskWorkflowChatRequest(workflowId: String): WorkflowChatOpenRequest? {
        val normalizedWorkflowId = workflowId.trim().ifBlank { return null }
        return WorkflowChatOpenRequest(
            binding = WorkflowChatBinding(
                workflowId = normalizedWorkflowId,
                focusedStage = StageId.IMPLEMENT,
                source = WorkflowChatEntrySource.TASK_PANEL,
                actionIntent = WorkflowChatActionIntent.DISCUSS,
            ),
        )
    }
}
