package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecWorkflow

internal data class SpecWorkflowPhaseNavigationBackgroundRequest<T>(
    val task: () -> T,
    val onSuccess: (T) -> Unit,
    val onFailure: (Throwable) -> Unit = {},
)

internal interface SpecWorkflowPhaseNavigationBackgroundRunner {
    fun <T> run(request: SpecWorkflowPhaseNavigationBackgroundRequest<T>)
}

internal class SpecWorkflowPhaseNavigationCoordinator(
    private val backgroundRunner: SpecWorkflowPhaseNavigationBackgroundRunner,
    private val selectedWorkflowId: () -> String?,
    private val proceedToNextPhase: (String) -> Result<SpecWorkflow>,
    private val goBackToPreviousPhase: (String) -> Result<SpecWorkflow>,
    private val clearInput: () -> Unit,
    private val reloadCurrentWorkflow: () -> Unit,
    private val setStatusText: (String) -> Unit,
    private val renderFailureMessage: (Throwable) -> String,
) {

    fun next() {
        runPhaseMutation(proceedToNextPhase)
    }

    fun goBack() {
        runPhaseMutation(goBackToPreviousPhase)
    }

    private fun runPhaseMutation(
        transition: (String) -> Result<SpecWorkflow>,
    ) {
        val workflowId = selectedWorkflowId()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return

        backgroundRunner.run(
            SpecWorkflowPhaseNavigationBackgroundRequest(
                task = {
                    transition(workflowId).getOrThrow()
                },
                onSuccess = {
                    clearInput()
                    reloadCurrentWorkflow()
                },
                onFailure = { error ->
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.workflow.error",
                            renderFailureMessage(error),
                        ),
                    )
                },
            ),
        )
    }
}
