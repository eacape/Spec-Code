package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress

internal data class SpecWorkflowWorkspaceAppliedState(
    val overviewState: SpecWorkflowOverviewState,
    val workbenchState: SpecWorkflowStageWorkbenchState,
    val verifyDeltaState: SpecWorkflowVerifyDeltaState,
    val gateResult: GateResult?,
    val tasks: List<StructuredTask>,
    val liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
)

internal interface SpecWorkflowWorkspaceStateApplicationUi {
    fun showWorkspaceContent()

    fun updateOverview(
        overviewState: SpecWorkflowOverviewState,
        workbenchState: SpecWorkflowStageWorkbenchState,
    )

    fun applySummary(presentation: SpecWorkflowWorkspaceSummaryPresentation)

    fun updateWorkbenchState(
        state: SpecWorkflowStageWorkbenchState,
        syncSelection: Boolean,
    )

    fun syncStructuredTaskSelection(taskId: String)

    fun updateDocumentWorkspaceViewPresentation(workbenchState: SpecWorkflowStageWorkbenchState)

    fun applyWorkspaceSectionPresentation(
        summaries: SpecWorkflowWorkspaceSectionSummaries,
        visibleSectionIds: Set<SpecWorkflowWorkspaceSectionId>,
        expandedStates: Map<SpecWorkflowWorkspaceSectionId, Boolean>,
    )
}

internal class SpecWorkflowWorkspaceStateApplicationHost(
    private val workspaceUi: SpecWorkflowWorkspaceStateApplicationUi,
    private val workspacePresentationTelemetry: SpecWorkflowWorkspacePresentationTelemetryTracker,
    private val resolveWorkbenchState: (
        workflow: SpecWorkflow,
        state: SpecWorkflowStageWorkbenchState,
    ) -> SpecWorkflowStageWorkbenchState,
    private val supportsStructuredTasksDocumentWorkspaceView: (SpecWorkflowStageWorkbenchState) -> Boolean,
) {
    private var currentState: SpecWorkflowWorkspaceAppliedState? = null
    private var workspaceSectionPresetToken: String? = null
    private val workspaceSectionOverrides = mutableMapOf<SpecWorkflowWorkspaceSectionId, Boolean>()

    fun currentState(): SpecWorkflowWorkspaceAppliedState? = currentState

    fun rememberSectionOverride(
        sectionId: SpecWorkflowWorkspaceSectionId,
        expanded: Boolean,
    ) {
        workspaceSectionOverrides[sectionId] = expanded
    }

    fun clear() {
        currentState = null
        workspaceSectionPresetToken = null
        workspaceSectionOverrides.clear()
    }

    fun apply(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        tasks: List<StructuredTask>,
        liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
        verifyDeltaState: SpecWorkflowVerifyDeltaState,
        gateResult: GateResult?,
        focusedStage: StageId?,
    ): SpecWorkflowWorkspaceAppliedState {
        val workspacePresentationStartedAt = workspacePresentationTelemetry.markStart()
        val previousWorkbenchState = currentState?.workbenchState
        val workbenchState = resolveWorkbenchState(
            workflow,
            SpecWorkflowStageWorkbenchBuilder.build(
                workflow = workflow,
                overviewState = overviewState,
                tasks = tasks,
                liveProgressByTaskId = liveProgressByTaskId,
                verifyDeltaState = verifyDeltaState,
                gateResult = gateResult,
                focusedStage = focusedStage,
            ),
        )
        val appliedState = SpecWorkflowWorkspaceAppliedState(
            overviewState = overviewState,
            workbenchState = workbenchState,
            verifyDeltaState = verifyDeltaState,
            gateResult = gateResult,
            tasks = tasks,
            liveProgressByTaskId = liveProgressByTaskId,
        )
        currentState = appliedState

        workspaceUi.showWorkspaceContent()
        workspaceUi.updateOverview(
            overviewState = overviewState,
            workbenchState = workbenchState,
        )

        val guidance = SpecWorkflowStageGuidanceBuilder.build(
            state = overviewState,
            workbenchState = workbenchState,
        )
        val summaryPresentation = SpecWorkflowWorkspaceSummaryPresenter.build(
            workflow = workflow,
            overviewState = overviewState,
            workbenchState = workbenchState,
            guidance = guidance,
            tasks = tasks,
            verifyDeltaState = verifyDeltaState,
            gateResult = gateResult,
        )
        workspaceUi.applySummary(summaryPresentation)

        val shouldSyncWorkbenchSelection = previousWorkbenchState?.focusedStage != workbenchState.focusedStage ||
            previousWorkbenchState?.currentStage != workbenchState.currentStage
        val telemetryObservation = SpecWorkflowWorkspacePresentationObservation(
            workflowId = workflow.id,
            currentStage = workflow.currentStage,
            focusedStage = workbenchState.focusedStage,
            taskCount = tasks.size,
            liveTaskCount = liveProgressByTaskId.size,
            visibleSectionCount = workbenchState.visibleSections.size,
            syncSelection = shouldSyncWorkbenchSelection,
        )
        try {
            workspaceUi.updateWorkbenchState(
                state = workbenchState,
                syncSelection = shouldSyncWorkbenchSelection,
            )
            syncWorkbenchTaskSelection(
                previousWorkbenchState = previousWorkbenchState,
                workbenchState = workbenchState,
            )
            workspaceUi.updateDocumentWorkspaceViewPresentation(workbenchState)
            workspaceUi.applyWorkspaceSectionPresentation(
                summaries = summaryPresentation.sectionSummaries,
                visibleSectionIds = resolveVisibleSectionIds(workbenchState),
                expandedStates = resolveWorkspaceSectionExpandedStates(workflow, workbenchState),
            )
        } finally {
            workspacePresentationTelemetry.record(
                startedAtNanos = workspacePresentationStartedAt,
                observation = telemetryObservation,
            )
        }
        return appliedState
    }

    private fun syncWorkbenchTaskSelection(
        previousWorkbenchState: SpecWorkflowStageWorkbenchState?,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ) {
        if (workbenchState.focusedStage != StageId.IMPLEMENT) {
            return
        }
        val taskId = workbenchState.implementationFocus?.taskId ?: return
        val shouldSyncSelection = previousWorkbenchState?.focusedStage != workbenchState.focusedStage ||
            previousWorkbenchState?.implementationFocus?.taskId != taskId
        if (shouldSyncSelection) {
            workspaceUi.syncStructuredTaskSelection(taskId)
        }
    }

    private fun resolveVisibleSectionIds(
        workbenchState: SpecWorkflowStageWorkbenchState,
    ): Set<SpecWorkflowWorkspaceSectionId> {
        return LinkedHashSet(workbenchState.visibleSections).apply {
            if (supportsStructuredTasksDocumentWorkspaceView(workbenchState)) {
                remove(SpecWorkflowWorkspaceSectionId.TASKS)
            }
        }
    }

    private fun resolveWorkspaceSectionExpandedStates(
        workflow: SpecWorkflow,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ): Map<SpecWorkflowWorkspaceSectionId, Boolean> {
        val token = "${workflow.id}:${workflow.currentStage.name}:${workbenchState.focusedStage.name}:${workflow.status.name}"
        val defaultExpanded = SpecWorkflowWorkspaceLayout.defaultExpandedSections(
            currentStage = workbenchState.focusedStage,
            status = workflow.status,
        )
        if (workspaceSectionPresetToken != token) {
            workspaceSectionPresetToken = token
            workspaceSectionOverrides.clear()
        }
        return SpecWorkflowWorkspaceSectionId.entries.associateWith { sectionId ->
            workspaceSectionOverrides[sectionId] ?: (sectionId in defaultExpanded)
        }
    }
}
