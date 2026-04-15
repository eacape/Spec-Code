package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus

internal class SpecWorkflowLifecycleUiHost(
    private val selectedWorkflowId: () -> String?,
    private val currentWorkflow: () -> SpecWorkflow?,
    private val completeWorkflow: (String?) -> Unit,
    private val togglePauseResume: (String?, Boolean) -> Unit,
    private val archiveWorkflow: (String?, WorkflowStatus?) -> Unit,
) {

    fun requestComplete() {
        completeWorkflow(selectedWorkflowId())
    }

    fun requestPauseResume() {
        togglePauseResume(
            selectedWorkflowId(),
            currentWorkflow()?.status == WorkflowStatus.PAUSED,
        )
    }

    fun requestArchive() {
        val workflow = currentWorkflow()
        archiveWorkflow(workflow?.id, workflow?.status)
    }
}
