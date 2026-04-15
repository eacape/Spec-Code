package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageTransitionGatePreview
import com.eacape.speccodingplugin.spec.WorkflowStatus

internal class SpecWorkflowUiSnapshotBuilder(
    private val previewAdvanceGate: (workflowId: String) -> Result<StageTransitionGatePreview>,
    private val buildOverviewState: (
        workflow: SpecWorkflow,
        gatePreview: StageTransitionGatePreview?,
        refreshedAtMillis: Long,
    ) -> SpecWorkflowOverviewState,
    private val buildVerifyDeltaState: (workflow: SpecWorkflow, refreshedAtMillis: Long) -> SpecWorkflowVerifyDeltaState,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val logGatePreviewFailure: (message: String, error: Throwable) -> Unit = { _, _ -> },
) {

    fun build(workflow: SpecWorkflow): SpecWorkflowUiSnapshot {
        val refreshedAtMillis = currentTimeMillis()
        val gatePreview = previewAdvanceGateIfNeeded(workflow)
        return SpecWorkflowUiSnapshot(
            overviewState = createOverviewState(workflow, gatePreview, refreshedAtMillis),
            verifyDeltaState = buildVerifyDeltaState(workflow, refreshedAtMillis),
            gateResult = gatePreview?.gateResult,
            refreshedAtMillis = refreshedAtMillis,
        )
    }

    fun buildOverview(workflow: SpecWorkflow): SpecWorkflowOverviewState {
        val refreshedAtMillis = currentTimeMillis()
        return createOverviewState(
            workflow = workflow,
            gatePreview = previewAdvanceGateIfNeeded(workflow),
            refreshedAtMillis = refreshedAtMillis,
        )
    }

    private fun createOverviewState(
        workflow: SpecWorkflow,
        gatePreview: StageTransitionGatePreview?,
        refreshedAtMillis: Long,
    ): SpecWorkflowOverviewState {
        return buildOverviewState(workflow, gatePreview, refreshedAtMillis)
    }

    private fun previewAdvanceGateIfNeeded(workflow: SpecWorkflow): StageTransitionGatePreview? {
        if (workflow.status == WorkflowStatus.COMPLETED || workflow.currentStage == StageId.ARCHIVE) {
            return null
        }
        return previewAdvanceGate(workflow.id).getOrElse { error ->
            logGatePreviewFailure("Unable to preview advance gate for workflow ${workflow.id}", error)
            null
        }
    }
}
