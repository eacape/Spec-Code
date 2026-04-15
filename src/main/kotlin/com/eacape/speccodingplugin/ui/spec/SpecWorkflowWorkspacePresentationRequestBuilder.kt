package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress

internal data class SpecWorkflowWorkspacePresentationRequest(
    val workflow: SpecWorkflow,
    val overviewState: SpecWorkflowOverviewState,
    val tasks: List<StructuredTask>,
    val liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
    val verifyDeltaState: SpecWorkflowVerifyDeltaState,
    val gateResult: GateResult?,
)

internal class SpecWorkflowWorkspacePresentationRequestBuilder(
    private val buildOverviewState: (SpecWorkflow) -> SpecWorkflowOverviewState,
    private val buildVerifyDeltaState: (SpecWorkflow, Long) -> SpecWorkflowVerifyDeltaState,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {

    fun buildRefreshRequest(
        workflow: SpecWorkflow?,
        appliedState: SpecWorkflowWorkspaceAppliedState?,
    ): SpecWorkflowWorkspacePresentationRequest? {
        val currentWorkflow = workflow ?: return null
        val currentState = appliedState ?: return null
        return buildRequest(
            workflow = currentWorkflow,
            overviewState = currentState.overviewState,
            tasks = currentState.tasks,
            liveProgressByTaskId = currentState.liveProgressByTaskId,
            verifyDeltaState = currentState.verifyDeltaState,
            gateResult = currentState.gateResult,
        )
    }

    fun buildFocusStageRequest(
        workflow: SpecWorkflow?,
        appliedState: SpecWorkflowWorkspaceAppliedState?,
    ): SpecWorkflowWorkspacePresentationRequest? {
        val currentWorkflow = workflow ?: return null
        return buildRequest(
            workflow = currentWorkflow,
            overviewState = appliedState?.overviewState ?: buildOverviewState(currentWorkflow),
            tasks = appliedState?.tasks.orEmpty(),
            liveProgressByTaskId = appliedState?.liveProgressByTaskId.orEmpty(),
            verifyDeltaState = appliedState?.verifyDeltaState ?: buildVerifyDeltaState(
                currentWorkflow,
                currentTimeMillis(),
            ),
            gateResult = appliedState?.gateResult,
        )
    }

    private fun buildRequest(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        tasks: List<StructuredTask>,
        liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
        verifyDeltaState: SpecWorkflowVerifyDeltaState,
        gateResult: GateResult?,
    ): SpecWorkflowWorkspacePresentationRequest {
        return SpecWorkflowWorkspacePresentationRequest(
            workflow = workflow,
            overviewState = overviewState,
            tasks = tasks,
            liveProgressByTaskId = liveProgressByTaskId,
            verifyDeltaState = verifyDeltaState,
            gateResult = gateResult,
        )
    }
}
