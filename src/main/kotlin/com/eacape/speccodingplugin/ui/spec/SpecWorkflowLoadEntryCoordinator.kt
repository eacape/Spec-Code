package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecWorkflow

internal class SpecWorkflowLoadEntryCoordinator(
    private val panelState: SpecWorkflowPanelState,
    private val navigationCoordinator: SpecWorkflowNavigationCoordinator,
    private val requestWorkflowLoad: (SpecWorkflowLoadTrigger, ((SpecWorkflow) -> Unit)?) -> Unit,
    private val refreshWorkflows: (String?) -> Unit,
    private val applyOpenRequestToCurrentWorkflow: (SpecToolWindowOpenRequest) -> Unit,
) {

    fun selectWorkflow(workflowId: String) {
        val loadTrigger = navigationCoordinator.buildSelectionLoadTrigger(
            workflowId = workflowId,
            selectedWorkflowId = panelState.selectedWorkflowId,
        ) ?: return
        panelState.selectWorkflow(loadTrigger.workflowId)
        requestWorkflowLoad(loadTrigger, null)
    }

    fun reloadCurrentWorkflow(
        followCurrentPhase: Boolean = false,
        onUpdated: ((SpecWorkflow) -> Unit)? = null,
    ) {
        val loadTrigger = navigationCoordinator.buildReloadLoadTrigger(
            selectedWorkflowId = panelState.selectedWorkflowId,
            followCurrentPhase = followCurrentPhase,
        ) ?: return
        if (loadTrigger.followCurrentPhase) {
            panelState.focusedStage = null
        }
        requestWorkflowLoad(loadTrigger, onUpdated)
    }

    fun openWorkflowFromRequest(
        request: SpecToolWindowOpenRequest,
        currentWorkflowId: String?,
    ) {
        val openRequestDecision = navigationCoordinator.resolveOpenRequest(
            request = request,
            selectedWorkflowId = panelState.selectedWorkflowId,
            currentWorkflowId = currentWorkflowId,
        )
        val normalizedRequest = openRequestDecision.normalizedRequest ?: run {
            panelState.clearPendingOpenRequest()
            return
        }
        panelState.rememberPendingOpenRequest(normalizedRequest)
        if (openRequestDecision.shouldApplyToCurrentWorkflow) {
            applyPendingOpenWorkflowRequestIfNeeded(normalizedRequest.workflowId)
            return
        }
        refreshWorkflows(openRequestDecision.refreshWorkflowId)
    }

    fun applyPendingOpenWorkflowRequestIfNeeded(workflowId: String) {
        consumePendingOpenWorkflowRequest(workflowId)
            ?.let(applyOpenRequestToCurrentWorkflow)
    }

    private fun consumePendingOpenWorkflowRequest(workflowId: String): SpecToolWindowOpenRequest? {
        val normalizedWorkflowId = normalize(workflowId) ?: return null
        val request = panelState.pendingOpenWorkflowRequest ?: return null
        if (request.workflowId != normalizedWorkflowId) {
            return null
        }
        panelState.clearPendingOpenRequest()
        return request
    }

    private fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
