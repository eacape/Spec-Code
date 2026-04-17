package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset
import javax.swing.JButton

internal data class SpecWorkflowStateApplicationUiPanels(
    val listPanel: SpecWorkflowListPanel,
    val phaseIndicator: SpecPhaseIndicatorPanel,
    val overviewPanel: SpecWorkflowOverviewPanel,
    val verifyDeltaPanel: SpecWorkflowVerifyDeltaPanel,
    val tasksPanel: SpecWorkflowTasksPanel,
    val detailTasksPanel: SpecWorkflowTasksPanel,
    val gateDetailsPanel: SpecWorkflowGateDetailsPanel,
    val detailPanel: SpecDetailPanel,
)

internal data class SpecWorkflowStateApplicationUiButtons(
    val switchWorkflowButton: JButton,
    val createWorktreeButton: JButton,
    val mergeWorktreeButton: JButton,
    val deltaButton: JButton,
    val archiveButton: JButton,
)

internal class SpecWorkflowPanelStateApplicationUiFacade(
    private val workflowPanelState: SpecWorkflowPanelState,
    private val panels: SpecWorkflowStateApplicationUiPanels,
    private val buttons: SpecWorkflowStateApplicationUiButtons,
    private val onCancelWorkflowSwitcherPopup: () -> Unit,
    private val updateStatusText: (String?) -> Unit,
    private val onLoadWorkflow: (String) -> Unit,
    private val onClearOpenedWorkflowUi: (Boolean) -> Unit,
    private val setCurrentWorkflow: (SpecWorkflow?) -> Unit,
    private val clarificationRetryUiHost: SpecWorkflowClarificationRetryRestoreUiHost,
    private val detailStateHost: SpecWorkflowDetailStateApplicationHost,
    private val updateWorkspacePresentation: (
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        tasks: List<StructuredTask>,
        liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
        verifyDeltaState: SpecWorkflowVerifyDeltaState,
        gateResult: GateResult?,
    ) -> Unit,
    private val onApplyPendingOpenWorkflowRequest: (String) -> Unit,
    private val showWorkspaceContent: () -> Unit,
) : SpecWorkflowStateApplicationUi {

    override fun cancelWorkflowSwitcherPopup() {
        onCancelWorkflowSwitcherPopup()
    }

    override fun updateWorkflowItems(items: List<SpecWorkflowListPanel.WorkflowListItem>) {
        panels.listPanel.updateWorkflows(items)
    }

    override fun setStatusText(text: String?) {
        updateStatusText(text)
    }

    override fun setSwitchWorkflowEnabled(enabled: Boolean) {
        buttons.switchWorkflowButton.isEnabled = enabled
    }

    override fun dropPendingOpenRequestIfInvalid(validWorkflowIds: Set<String>) {
        workflowPanelState.dropPendingOpenRequestIfInvalid(validWorkflowIds)
    }

    override fun highlightWorkflow(workflowId: String?) {
        workflowPanelState.highlightWorkflow(workflowId)
        panels.listPanel.setSelectedWorkflow(workflowId)
    }

    override fun loadWorkflow(workflowId: String) {
        onLoadWorkflow(workflowId)
    }

    override fun clearOpenedWorkflowUi(resetHighlight: Boolean) {
        onClearOpenedWorkflowUi(resetHighlight)
    }

    override fun applyWorkflowCore(state: SpecWorkflowLoadedCoreUiState) {
        setCurrentWorkflow(state.workflow)
        clarificationRetryUiHost.syncFromWorkflow(state.workflow)
        panels.phaseIndicator.updatePhase(state.workflow)
        panels.overviewPanel.updateOverview(state.snapshot.overviewState)
        panels.verifyDeltaPanel.updateState(state.snapshot.verifyDeltaState)
        panels.gateDetailsPanel.updateGateResult(
            workflowId = state.workflow.id,
            gateResult = state.snapshot.gateResult,
            refreshedAtMillis = state.snapshot.refreshedAtMillis,
        )
        panels.detailPanel.updateWorkflow(
            state.workflow,
            followCurrentPhase = state.followCurrentPhase,
        )
        detailStateHost.applyAutoCodeContext(state.workflow, state.codeContextResult)
    }

    override fun applyWorkflowSources(
        workflow: SpecWorkflow,
        assets: List<WorkflowSourceAsset>,
        preserveSelection: Boolean,
    ) {
        detailStateHost.applyWorkflowSources(workflow, assets, preserveSelection)
    }

    override fun applyWorkflowTasks(state: SpecWorkflowLoadedTaskUiState) {
        panels.tasksPanel.updateTasks(
            workflowId = state.workflow.id,
            tasks = state.tasks,
            liveProgressByTaskId = state.liveProgressByTaskId,
            refreshedAtMillis = state.refreshedAtMillis,
        )
        panels.detailTasksPanel.updateTasks(
            workflowId = state.workflow.id,
            tasks = state.tasks,
            liveProgressByTaskId = state.liveProgressByTaskId,
            refreshedAtMillis = state.refreshedAtMillis,
        )
        updateWorkspacePresentation(
            state.workflow,
            state.snapshot.overviewState,
            state.tasks,
            state.liveProgressByTaskId,
            state.snapshot.verifyDeltaState,
            state.snapshot.gateResult,
        )
    }

    override fun restorePendingClarificationState(workflowId: String) {
        clarificationRetryUiHost.restorePendingState(workflowId)
    }

    override fun applyPendingOpenWorkflowRequest(workflowId: String) {
        onApplyPendingOpenWorkflowRequest(workflowId)
    }

    override fun updateWorkflowActionAvailability(workflow: SpecWorkflow) {
        val availability = SpecWorkflowToolbarActionAvailabilityBuilder.build(workflow)
        buttons.createWorktreeButton.isEnabled = availability.createWorktreeEnabled
        buttons.mergeWorktreeButton.isEnabled = availability.mergeWorktreeEnabled
        buttons.deltaButton.isEnabled = availability.deltaEnabled
        buttons.archiveButton.isEnabled = availability.archiveEnabled
    }

    override fun showWorkflowLoadInProgress() {
        panels.overviewPanel.showLoading()
        panels.verifyDeltaPanel.showLoading()
        panels.tasksPanel.showLoading()
        panels.detailTasksPanel.showLoading()
        panels.gateDetailsPanel.showLoading()
        showWorkspaceContent()
    }
}
