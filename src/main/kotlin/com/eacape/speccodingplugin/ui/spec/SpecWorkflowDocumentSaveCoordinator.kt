package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow

internal data class SpecWorkflowDocumentSaveBackgroundRequest<T>(
    val task: () -> T,
    val onSuccess: (T) -> Unit,
    val onFailure: (Throwable) -> Unit = {},
)

internal interface SpecWorkflowDocumentSaveBackgroundRunner {
    fun <T> run(request: SpecWorkflowDocumentSaveBackgroundRequest<T>)
}

internal class SpecWorkflowDocumentSaveCoordinator(
    private val backgroundRunner: SpecWorkflowDocumentSaveBackgroundRunner,
    private val selectedWorkflowId: () -> String?,
    private val saveDocumentContent: (workflowId: String, phase: SpecPhase, content: String) -> Result<SpecWorkflow>,
    private val applySavedWorkflowState: (SpecWorkflow) -> Unit,
    private val setStatusText: (String?) -> Unit,
    private val renderFailureMessage: (Throwable) -> String,
) {

    fun save(
        phase: SpecPhase,
        content: String,
        onDone: (Result<SpecWorkflow>) -> Unit,
    ) {
        val workflowId = selectedWorkflowId()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: run {
                onDone(Result.failure(IllegalStateException(SpecCodingBundle.message("spec.detail.noWorkflow"))))
                return
            }

        backgroundRunner.run(
            SpecWorkflowDocumentSaveBackgroundRequest(
                task = {
                    saveDocumentContent(workflowId, phase, content).getOrThrow()
                },
                onSuccess = { updated ->
                    applySavedWorkflowState(updated)
                    setStatusText(null)
                    onDone(Result.success(updated))
                },
                onFailure = { error ->
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.workflow.error",
                            renderFailureMessage(error),
                        ),
                    )
                    onDone(Result.failure(error))
                },
            ),
        )
    }
}
