package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.session.WorkflowChatBinding
import com.intellij.util.messages.Topic

data class WorkflowChatOpenRequest(
    val binding: WorkflowChatBinding,
    val preferNewSession: Boolean = true,
)

interface ChatToolWindowControlListener {
    fun onNewSessionRequested()

    fun onOpenHistoryRequested()

    fun onOpenWorkflowChatRequested(request: WorkflowChatOpenRequest) {}

    companion object {
        val TOPIC: Topic<ChatToolWindowControlListener> = Topic.create(
            "SpecCoding.ChatToolWindowControl",
            ChatToolWindowControlListener::class.java,
        )
    }
}
