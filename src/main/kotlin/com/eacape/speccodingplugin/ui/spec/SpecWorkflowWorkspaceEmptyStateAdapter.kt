package com.eacape.speccodingplugin.ui.spec

internal interface SpecWorkflowWorkspaceEmptyStateUi {
    fun showWorkflowListOnlyMode()

    fun setBackToListEnabled(enabled: Boolean)

    fun showWorkspaceEmptyCard()

    fun clearWorkspaceState()

    fun stopLiveProgressRefresh()

    fun clearFocusedStage()

    fun clearWorkspaceSummary()

    fun resetWorkspaceSections()

    fun showAllWorkspaceSections()

    fun resetDocumentWorkspaceView()
}

internal class SpecWorkflowWorkspaceEmptyStateAdapter(
    private val ui: SpecWorkflowWorkspaceEmptyStateUi,
) {
    fun showEmptyState() {
        ui.showWorkflowListOnlyMode()
        ui.setBackToListEnabled(false)
        ui.showWorkspaceEmptyCard()
        ui.clearWorkspaceState()
        ui.stopLiveProgressRefresh()
        ui.clearFocusedStage()
        ui.clearWorkspaceSummary()
        ui.resetWorkspaceSections()
        ui.showAllWorkspaceSections()
        ui.resetDocumentWorkspaceView()
    }
}
