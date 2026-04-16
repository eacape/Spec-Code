package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GenerationOptions
import com.eacape.speccodingplugin.spec.SpecClarificationDraft
import com.eacape.speccodingplugin.spec.SpecGenerationProgress
import com.eacape.speccodingplugin.spec.SpecPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

internal fun interface SpecWorkflowGenerationExecutionHandle {
    fun cancel(reason: String)
}

internal interface SpecWorkflowGenerationExecutionBackgroundRunner {
    fun launch(task: suspend () -> Unit): SpecWorkflowGenerationExecutionHandle
}

internal data class SpecWorkflowGenerationExecutionRequest(
    val workflowId: String,
    val phase: SpecPhase,
    val input: String,
    val options: GenerationOptions,
)

internal interface SpecWorkflowGenerationExecutionUi {
    fun invokeLater(action: () -> Unit)
    fun isWorkflowSelected(workflowId: String): Boolean
    fun showClarificationGenerating(prepared: SpecWorkflowPreparedClarificationDraftRequest)
    fun applyClarificationDraftResult(
        prepared: SpecWorkflowPreparedClarificationDraftRequest,
        result: SpecWorkflowClarificationDraftResult,
    )

    fun applyGenerationProgress(
        workflowId: String,
        input: String,
        update: SpecWorkflowGenerationProgressUpdate,
    )

    fun handleClarificationInterrupted(
        workflowId: String,
        input: String,
        options: GenerationOptions,
    )
}

internal class SpecWorkflowGenerationExecutionCoordinator(
    private val backgroundRunner: SpecWorkflowGenerationExecutionBackgroundRunner,
    private val ui: SpecWorkflowGenerationExecutionUi,
    private val generationCoordinator: SpecWorkflowGenerationCoordinator,
    private val draftClarification: suspend (String, String, GenerationOptions) -> Result<SpecClarificationDraft>,
    private val generateCurrentPhase: suspend (String, String, GenerationOptions) -> Flow<SpecGenerationProgress>,
    private val cancelRequestAcrossProviders: (providerId: String?, requestId: String) -> Unit,
    private val logClarificationDraftFailure: (workflowId: String, error: Throwable?) -> Unit,
) {

    private var activeHandle: SpecWorkflowGenerationExecutionHandle? = null
    private var activeRequest: SpecWorkflowActiveGenerationRequest? = null

    fun requestClarificationDraft(request: SpecWorkflowClarificationDraftLaunchRequest) {
        cancelActiveRequest("Superseded by new clarification request")
        val prepared = generationCoordinator.prepareClarificationDraft(
            context = request.context,
            input = request.input,
            options = request.options,
            suggestedDetails = request.suggestedDetails,
            seedQuestionsMarkdown = request.seedQuestionsMarkdown,
            seedStructuredQuestions = request.seedStructuredQuestions,
            clarificationRound = request.clarificationRound,
        )
        activeRequest = prepared.activeRequest
        activeHandle = backgroundRunner.launch {
            try {
                ui.invokeLater {
                    if (ui.isWorkflowSelected(prepared.context.workflowId)) {
                        ui.showClarificationGenerating(prepared)
                    }
                }
                val draftResult = try {
                    Result.success(
                        draftClarification(
                            prepared.context.workflowId,
                            prepared.input,
                            prepared.requestOptions,
                        ).getOrThrow(),
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    Result.failure(error)
                }

                val draft = draftResult.getOrNull()
                val draftError = draftResult.exceptionOrNull()
                if (draft == null) {
                    logClarificationDraftFailure(prepared.context.workflowId, draftError)
                }
                val result = generationCoordinator.buildClarificationDraftResult(
                    prepared = prepared,
                    draft = draft,
                    error = draftError,
                )
                ui.invokeLater {
                    if (ui.isWorkflowSelected(prepared.context.workflowId)) {
                        ui.applyClarificationDraftResult(prepared, result)
                    }
                }
            } catch (cancel: CancellationException) {
                if (isActiveRequest(prepared.activeRequest)) {
                    ui.invokeLater {
                        ui.handleClarificationInterrupted(
                            workflowId = prepared.context.workflowId,
                            input = request.input,
                            options = prepared.requestOptions,
                        )
                    }
                }
                throw cancel
            } finally {
                clearActiveRequest(prepared.activeRequest)
            }
        }
    }

    fun runGeneration(request: SpecWorkflowGenerationExecutionRequest) {
        cancelActiveRequest("Superseded by new generation request")
        val prepared = generationCoordinator.prepareGeneration(
            workflowId = request.workflowId,
            phase = request.phase,
            input = request.input,
            options = request.options,
        )
        activeRequest = prepared.activeRequest
        activeHandle = backgroundRunner.launch {
            try {
                var tracker = SpecWorkflowGenerationProgressTracker()
                generateCurrentPhase(
                    request.workflowId,
                    request.input,
                    prepared.requestOptions,
                ).collect { progress ->
                    val update = generationCoordinator.advanceGenerationProgress(
                        prepared = prepared,
                        tracker = tracker,
                        progress = progress,
                    )
                    tracker = update.tracker
                    ui.invokeLater {
                        ui.applyGenerationProgress(
                            workflowId = request.workflowId,
                            input = request.input,
                            update = update,
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                if (isActiveRequest(prepared.activeRequest)) {
                    val interruptedUpdate = generationCoordinator.buildInterruptedProgressUpdate(
                        prepared = prepared,
                        interruptedMessage = SpecCodingBundle.message("spec.workflow.generation.interrupted"),
                    )
                    ui.invokeLater {
                        ui.applyGenerationProgress(
                            workflowId = request.workflowId,
                            input = request.input,
                            update = interruptedUpdate,
                        )
                    }
                }
                throw cancel
            } finally {
                clearActiveRequest(prepared.activeRequest)
            }
        }
    }

    fun cancelActiveRequest(reason: String) {
        val request = activeRequest
        if (request != null && request.requestId.isNotBlank()) {
            cancelRequestAcrossProviders(request.providerId, request.requestId)
        }
        activeHandle?.cancel(reason)
        activeHandle = null
        activeRequest = null
    }

    private fun isActiveRequest(request: SpecWorkflowActiveGenerationRequest): Boolean {
        val active = activeRequest ?: return false
        return active.workflowId == request.workflowId && active.requestId == request.requestId
    }

    private fun clearActiveRequest(request: SpecWorkflowActiveGenerationRequest) {
        if (!isActiveRequest(request)) {
            return
        }
        activeHandle = null
        activeRequest = null
    }
}
