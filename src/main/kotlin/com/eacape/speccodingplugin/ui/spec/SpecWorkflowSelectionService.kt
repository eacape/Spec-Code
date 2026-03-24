package com.eacape.speccodingplugin.ui.spec

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

class SpecWorkflowSelectionService(
    project: Project,
) : Disposable {

    @Volatile
    private var selectedWorkflowId: String? = null

    init {
        project.messageBus.connect(this).subscribe(
            SpecWorkflowChangedListener.TOPIC,
            object : SpecWorkflowChangedListener {
                override fun onWorkflowChanged(event: SpecWorkflowChangedEvent) {
                    selectedWorkflowId = event.workflowId
                        ?.trim()
                        ?.ifBlank { null }
                }
            },
        )
    }

    fun currentWorkflowId(): String? = selectedWorkflowId

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): SpecWorkflowSelectionService = project.service()
    }
}
