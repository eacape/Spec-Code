package com.eacape.speccodingplugin.ui.spec

import com.intellij.util.messages.Topic

data class SpecWorkflowChangedEvent(
    val workflowId: String?,
    val reason: String,
)

interface SpecWorkflowChangedListener {
    fun onWorkflowChanged(event: SpecWorkflowChangedEvent) {}

    companion object {
        const val REASON_WORKFLOW_SELECTED = "workflow_selected"

        val TOPIC: Topic<SpecWorkflowChangedListener> = Topic.create(
            "SpecCoding.SpecWorkflowChanged",
            SpecWorkflowChangedListener::class.java,
        )
    }
}
