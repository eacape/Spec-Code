package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.spec.StageId
import com.intellij.util.messages.Topic

data class WorkflowChatRefreshEvent(
    val workflowId: String,
    val taskId: String? = null,
    val focusedStage: StageId? = null,
    val reason: String,
)

interface WorkflowChatRefreshListener {
    fun onWorkflowChatRefreshRequested(event: WorkflowChatRefreshEvent) {}

    companion object {
        val TOPIC: Topic<WorkflowChatRefreshListener> = Topic.create(
            "SpecCoding.WorkflowChatRefresh",
            WorkflowChatRefreshListener::class.java,
        )
    }
}
