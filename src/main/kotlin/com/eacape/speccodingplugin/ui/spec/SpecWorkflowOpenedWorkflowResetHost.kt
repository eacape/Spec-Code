package com.eacape.speccodingplugin.ui.spec

internal interface SpecWorkflowOpenedWorkflowResetUi {
    fun clearCurrentWorkflow()

    fun clearCurrentWorkflowSources()

    fun resetWorkflowViewsToEmpty()

    fun applyToolbarActionAvailability(availability: SpecWorkflowToolbarActionAvailability)

    fun clearWorkflowListHighlight()

    fun showWorkspaceEmptyState()
}

internal class SpecWorkflowOpenedWorkflowResetHost(
    private val workflowPanelState: SpecWorkflowPanelState,
    private val ui: SpecWorkflowOpenedWorkflowResetUi,
) {
    fun clear(resetHighlight: Boolean = false) {
        workflowPanelState.clearOpenedWorkflow(resetHighlight = resetHighlight)
        ui.clearCurrentWorkflow()
        ui.clearCurrentWorkflowSources()
        ui.resetWorkflowViewsToEmpty()
        ui.applyToolbarActionAvailability(SpecWorkflowToolbarActionAvailabilityBuilder.empty())
        if (resetHighlight) {
            ui.clearWorkflowListHighlight()
        }
        ui.showWorkspaceEmptyState()
    }
}
