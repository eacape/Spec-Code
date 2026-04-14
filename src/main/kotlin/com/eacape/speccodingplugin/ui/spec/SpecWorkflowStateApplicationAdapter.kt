package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset

internal interface SpecWorkflowStateApplicationUi {
    fun cancelWorkflowSwitcherPopup()

    fun updateWorkflowItems(items: List<SpecWorkflowListPanel.WorkflowListItem>)

    fun setStatusText(text: String?)

    fun setSwitchWorkflowEnabled(enabled: Boolean)

    fun dropPendingOpenRequestIfInvalid(validWorkflowIds: Set<String>)

    fun highlightWorkflow(workflowId: String?)

    fun loadWorkflow(workflowId: String)

    fun clearOpenedWorkflowUi(resetHighlight: Boolean)

    fun applyWorkflowCore(state: SpecWorkflowLoadedCoreUiState)

    fun applyWorkflowSources(
        workflow: SpecWorkflow,
        assets: List<WorkflowSourceAsset>,
        preserveSelection: Boolean,
    )

    fun applyWorkflowTasks(state: SpecWorkflowLoadedTaskUiState)

    fun restorePendingClarificationState(workflowId: String)

    fun applyPendingOpenWorkflowRequest(workflowId: String)

    fun updateWorkflowActionAvailability(workflow: SpecWorkflow)

    fun showWorkflowLoadInProgress()
}

internal class SpecWorkflowStateApplicationAdapter(
    private val ui: SpecWorkflowStateApplicationUi,
) {

    val listRefreshCallbacks = object : SpecWorkflowListRefreshCallbacks {
        override fun cancelWorkflowSwitcherPopup() {
            ui.cancelWorkflowSwitcherPopup()
        }

        override fun updateWorkflowItems(items: List<SpecWorkflowListPanel.WorkflowListItem>) {
            ui.updateWorkflowItems(items)
        }

        override fun setStatusText(text: String?) {
            ui.setStatusText(text)
        }

        override fun setSwitchWorkflowEnabled(enabled: Boolean) {
            ui.setSwitchWorkflowEnabled(enabled)
        }

        override fun dropPendingOpenRequestIfInvalid(validWorkflowIds: Set<String>) {
            ui.dropPendingOpenRequestIfInvalid(validWorkflowIds)
        }

        override fun highlightWorkflow(workflowId: String?) {
            ui.highlightWorkflow(workflowId)
        }

        override fun loadWorkflow(workflowId: String) {
            ui.loadWorkflow(workflowId)
        }

        override fun clearOpenedWorkflowUi(resetHighlight: Boolean) {
            ui.clearOpenedWorkflowUi(resetHighlight)
        }
    }

    val loadedStateCallbacks = object : SpecWorkflowLoadedStateCallbacks {
        override fun clearOpenedWorkflowUi(resetHighlight: Boolean) {
            ui.clearOpenedWorkflowUi(resetHighlight)
        }

        override fun applyWorkflowCore(state: SpecWorkflowLoadedCoreUiState) {
            ui.applyWorkflowCore(state)
        }

        override fun applyWorkflowSources(
            workflow: SpecWorkflow,
            assets: List<WorkflowSourceAsset>,
            preserveSelection: Boolean,
        ) {
            ui.applyWorkflowSources(workflow, assets, preserveSelection)
        }

        override fun applyWorkflowTasks(state: SpecWorkflowLoadedTaskUiState) {
            ui.applyWorkflowTasks(state)
        }

        override fun restorePendingClarificationState(workflowId: String) {
            ui.restorePendingClarificationState(workflowId)
        }

        override fun applyPendingOpenWorkflowRequest(workflowId: String) {
            ui.applyPendingOpenWorkflowRequest(workflowId)
        }

        override fun updateWorkflowActionAvailability(workflow: SpecWorkflow) {
            ui.updateWorkflowActionAvailability(workflow)
        }

        override fun setStatusText(text: String) {
            ui.setStatusText(text)
        }
    }

    fun showWorkflowLoadInProgress() {
        ui.showWorkflowLoadInProgress()
    }
}
