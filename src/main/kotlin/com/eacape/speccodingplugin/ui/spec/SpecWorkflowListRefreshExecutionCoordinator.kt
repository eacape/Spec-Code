package com.eacape.speccodingplugin.ui.spec

internal data class SpecWorkflowListRefreshExecutionRequest(
    val selectWorkflowId: String? = null,
    val showRefreshFeedback: Boolean = false,
    val preserveListMode: Boolean = false,
)

internal class SpecWorkflowListRefreshExecutionCoordinator(
    private val loadRefreshState: () -> SpecWorkflowListRefreshLoadedState,
    private val selectedWorkflowId: () -> String?,
    private val highlightedWorkflowId: () -> String?,
    private val launchRefreshLoad: (
        loadAction: () -> SpecWorkflowListRefreshLoadedState,
        onLoaded: (SpecWorkflowListRefreshLoadedState) -> Unit,
    ) -> Unit,
    private val applyLoadedState: (SpecWorkflowListRefreshApplyRequest) -> Unit,
    private val showRefreshFeedback: () -> Unit,
) {

    fun refreshWorkflows(request: SpecWorkflowListRefreshExecutionRequest = SpecWorkflowListRefreshExecutionRequest()) {
        launchRefreshLoad(loadRefreshState) { loadedState ->
            applyLoadedState(
                SpecWorkflowListRefreshApplyRequest(
                    loadedState = loadedState,
                    selectWorkflowId = request.selectWorkflowId,
                    selectedWorkflowId = selectedWorkflowId(),
                    highlightedWorkflowId = highlightedWorkflowId(),
                    preserveListMode = request.preserveListMode,
                ),
            )
            if (request.showRefreshFeedback) {
                showRefreshFeedback()
            }
        }
    }
}
