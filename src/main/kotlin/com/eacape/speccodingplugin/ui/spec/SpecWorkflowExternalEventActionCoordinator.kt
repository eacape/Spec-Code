package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.WorkflowTemplate

internal class SpecWorkflowExternalEventActionCoordinator(
    private val documentReloadCoordinator: SpecWorkflowDocumentReloadCoordinator,
    private val isDisposed: () -> Boolean,
    private val selectedWorkflowId: () -> String?,
    private val createWorkflow: (WorkflowTemplate?) -> Unit,
    private val openWorkflow: (SpecToolWindowOpenRequest) -> Unit,
    private val refreshWorkflows: (String?) -> Unit,
    private val reloadCurrentWorkflow: () -> Unit,
) {
    fun handle(action: SpecWorkflowExternalEventAction) {
        when (action) {
            is SpecWorkflowExternalEventAction.CreateWorkflow -> createWorkflow(action.preferredTemplate)
            is SpecWorkflowExternalEventAction.OpenWorkflow -> openWorkflow(action.request)
            is SpecWorkflowExternalEventAction.RefreshWorkflows -> refreshWorkflows(action.selectWorkflowId)
            is SpecWorkflowExternalEventAction.ScheduleDocumentReload -> scheduleDocumentReload(action.workflowId)
        }
    }

    fun cancelPendingDocumentReload() {
        documentReloadCoordinator.cancelPending()
    }

    private fun scheduleDocumentReload(workflowId: String) {
        documentReloadCoordinator.schedule(
            workflowId = workflowId,
            shouldReload = { candidateWorkflowId ->
                !isDisposed() && selectedWorkflowId() == candidateWorkflowId
            },
            reload = reloadCurrentWorkflow,
        )
    }
}
