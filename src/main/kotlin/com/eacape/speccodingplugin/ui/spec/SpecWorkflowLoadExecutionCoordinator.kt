package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecWorkflow

internal class SpecWorkflowLoadExecutionCoordinator(
    private val loadCoordinator: SpecWorkflowPanelLoadCoordinator,
    private val showLoadInProgress: () -> Unit,
    private val launchLoad: (() -> Unit) -> Unit,
    private val applyLoadedWorkflow: (
        workflowId: String,
        loadedState: SpecWorkflowPanelLoadedState,
        followCurrentPhase: Boolean,
        previousSelectedWorkflowId: String?,
        onUpdated: ((SpecWorkflow) -> Unit)?,
    ) -> Unit,
) {

    fun requestWorkflowLoad(
        loadTrigger: SpecWorkflowLoadTrigger,
        onUpdated: ((SpecWorkflow) -> Unit)? = null,
    ) {
        showLoadInProgress()
        launchLoad {
            val loadedState = loadCoordinator.load(
                SpecWorkflowPanelLoadRequest(
                    workflowId = loadTrigger.workflowId,
                    includeSources = loadTrigger.includeSources,
                ),
            )
            applyLoadedWorkflow(
                loadTrigger.workflowId,
                loadedState,
                loadTrigger.followCurrentPhase,
                loadTrigger.previousSelectedWorkflowId,
                onUpdated,
            )
        }
    }
}
