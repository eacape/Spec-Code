package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowMeta

internal data class SpecWorkflowListRefreshLoadedState(
    val items: List<SpecWorkflowListPanel.WorkflowListItem>,
)

internal data class SpecWorkflowListRefreshApplyRequest(
    val loadedState: SpecWorkflowListRefreshLoadedState,
    val selectWorkflowId: String? = null,
    val selectedWorkflowId: String? = null,
    val highlightedWorkflowId: String? = null,
    val preserveListMode: Boolean = false,
)

internal interface SpecWorkflowListRefreshCallbacks {
    fun cancelWorkflowSwitcherPopup()

    fun updateWorkflowItems(items: List<SpecWorkflowListPanel.WorkflowListItem>)

    fun setStatusText(text: String?)

    fun setSwitchWorkflowEnabled(enabled: Boolean)

    fun dropPendingOpenRequestIfInvalid(validWorkflowIds: Set<String>)

    fun highlightWorkflow(workflowId: String?)

    fun loadWorkflow(workflowId: String)

    fun clearOpenedWorkflowUi(resetHighlight: Boolean)
}

internal class SpecWorkflowListRefreshCoordinator(
    private val listWorkflowMetadata: () -> List<WorkflowMeta>,
    private val stageLabel: (StageId) -> String,
    private val selectionCoordinator: SpecWorkflowSelectionCoordinator,
) {

    fun load(): SpecWorkflowListRefreshLoadedState {
        return SpecWorkflowListRefreshLoadedState(
            items = listWorkflowMetadata().map(::toWorkflowListItem),
        )
    }

    fun apply(
        request: SpecWorkflowListRefreshApplyRequest,
        callbacks: SpecWorkflowListRefreshCallbacks,
    ) {
        callbacks.cancelWorkflowSwitcherPopup()
        callbacks.updateWorkflowItems(request.loadedState.items)
        callbacks.setStatusText(null)

        val refreshDecision = selectionCoordinator.resolveRefresh(
            SpecWorkflowSelectionRefreshRequest(
                items = request.loadedState.items,
                selectWorkflowId = request.selectWorkflowId,
                selectedWorkflowId = request.selectedWorkflowId,
                highlightedWorkflowId = request.highlightedWorkflowId,
                preserveListMode = request.preserveListMode,
            ),
        )

        callbacks.setSwitchWorkflowEnabled(refreshDecision.switchWorkflowEnabled)
        callbacks.dropPendingOpenRequestIfInvalid(refreshDecision.validWorkflowIds)
        callbacks.highlightWorkflow(refreshDecision.targetHighlightedWorkflowId)

        refreshDecision.targetOpenedWorkflowId?.let(callbacks::loadWorkflow)
            ?: callbacks.clearOpenedWorkflowUi(resetHighlight = refreshDecision.resetHighlightOnClear)
    }

    private fun toWorkflowListItem(meta: WorkflowMeta): SpecWorkflowListPanel.WorkflowListItem {
        return SpecWorkflowListPanel.WorkflowListItem(
            workflowId = meta.workflowId,
            title = meta.title?.ifBlank { meta.workflowId } ?: meta.workflowId,
            description = meta.description.orEmpty(),
            currentPhase = meta.currentPhase,
            currentStageLabel = stageLabel(meta.currentStage),
            status = meta.status,
            updatedAt = meta.updatedAt,
            changeIntent = meta.changeIntent,
            baselineWorkflowId = meta.baselineWorkflowId,
        )
    }
}
