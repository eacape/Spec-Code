package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GenerationOptions
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.ValidationResult

internal class SpecWorkflowGenerationExecutionUiHost(
    private val invokeLaterOnUi: ((() -> Unit) -> Unit),
    private val isWorkflowSelectedNow: (String) -> Boolean,
    private val showClarificationGeneratingUi: (SpecPhase, String, String) -> Unit,
    private val showClarificationDraftUi: (SpecPhase, String, String, String, List<String>) -> Unit,
    private val appendTimelineEntriesUi: (List<SpecWorkflowTimelineEntry>) -> Unit,
    private val showGeneratingUi: (Double) -> Unit,
    private val showValidationFailureUi: (SpecPhase, ValidationResult) -> Unit,
    private val showGenerationFailedUi: () -> Unit,
    private val exitClarificationModeUi: (Boolean) -> Unit,
    private val rememberClarificationRetry: (SpecWorkflowClarificationRetryRememberRequest) -> Unit,
    private val clearClarificationRetry: (String) -> Unit,
    private val resolveClarificationRound: (String) -> Int?,
    private val setStatusText: (String?) -> Unit,
    private val setRuntimeTroubleshootingStatus: (
        workflowId: String?,
        text: String?,
        trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
    ) -> Unit,
    private val reloadCurrentWorkflow: (onUpdated: ((SpecWorkflow) -> Unit)?) -> Unit,
) : SpecWorkflowGenerationExecutionUi {

    override fun invokeLater(action: () -> Unit) {
        invokeLaterOnUi(action)
    }

    override fun isWorkflowSelected(workflowId: String): Boolean {
        return isWorkflowSelectedNow(workflowId)
    }

    override fun showClarificationGenerating(prepared: SpecWorkflowPreparedClarificationDraftRequest) {
        showClarificationGeneratingUi(
            prepared.context.phase,
            prepared.input,
            prepared.safeSuggestedDetails,
        )
        appendTimelineEntriesUi(prepared.initialTimelineEntries)
        setStatusText(prepared.loadingStatusText)
    }

    override fun applyClarificationDraftResult(
        prepared: SpecWorkflowPreparedClarificationDraftRequest,
        result: SpecWorkflowClarificationDraftResult,
    ) {
        showClarificationDraftUi(
            result.phase,
            prepared.input,
            result.questionsMarkdown,
            prepared.safeSuggestedDetails,
            result.structuredQuestions,
        )
        rememberClarificationRetry(
            SpecWorkflowClarificationRetryRememberRequest(
                workflowId = prepared.context.workflowId,
                input = prepared.input,
                confirmedContext = prepared.safeSuggestedDetails,
                questionsMarkdown = result.questionsMarkdown,
                structuredQuestions = result.structuredQuestions,
                clarificationRound = prepared.clarificationRound,
                lastError = result.errorText,
            ),
        )
        appendTimelineEntriesUi(listOf(result.timelineEntry))
        if (result.troubleshootingTrigger != null) {
            setRuntimeTroubleshootingStatus(
                prepared.context.workflowId,
                result.statusText,
                result.troubleshootingTrigger,
            )
        } else {
            setStatusText(result.statusText)
        }
    }

    override fun applyGenerationProgress(
        workflowId: String,
        input: String,
        update: SpecWorkflowGenerationProgressUpdate,
    ) {
        if (!isWorkflowSelected(workflowId)) {
            return
        }
        appendTimelineEntriesUi(update.timelineEntries)
        update.progressFraction?.let(showGeneratingUi)
        if (update.shouldClearRetry) {
            clearClarificationRetry(workflowId)
        }
        if (update.retryLastError != null) {
            rememberClarificationRetry(
                SpecWorkflowClarificationRetryRememberRequest(
                    workflowId = workflowId,
                    input = input,
                    confirmedContext = update.retryConfirmedContext,
                    clarificationRound = resolveClarificationRound(workflowId),
                    lastError = update.retryLastError,
                ),
            )
        }
        if (update.validationFailure != null) {
            setStatusText(update.statusText)
            if (update.shouldReloadWorkflow) {
                reloadCurrentWorkflow { updated ->
                    showValidationFailureUi(updated.currentPhase, update.validationFailure)
                }
            }
            return
        }
        if (update.shouldShowGenerationFailed) {
            showGenerationFailedUi()
        }
        if (update.shouldExitClarificationMode) {
            exitClarificationModeUi(update.clearInputOnExit)
        }
        if (update.statusText != null) {
            if (update.troubleshootingTrigger != null) {
                setRuntimeTroubleshootingStatus(
                    workflowId,
                    update.statusText,
                    update.troubleshootingTrigger,
                )
            } else {
                setStatusText(update.statusText)
            }
        }
        if (update.shouldReloadWorkflow) {
            reloadCurrentWorkflow(null)
        }
    }

    override fun handleClarificationInterrupted(
        workflowId: String,
        input: String,
        options: GenerationOptions,
    ) {
        if (!isWorkflowSelected(workflowId)) {
            return
        }
        val interruptedMessage = SpecCodingBundle.message("spec.workflow.generation.interrupted")
        appendTimelineEntriesUi(
            listOf(
                SpecWorkflowTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.clarify.failed", interruptedMessage),
                    state = SpecWorkflowTimelineEntryState.FAILED,
                ),
            ),
        )
        rememberClarificationRetry(
            SpecWorkflowClarificationRetryRememberRequest(
                workflowId = workflowId,
                input = input,
                confirmedContext = options.confirmedContext,
                clarificationRound = resolveClarificationRound(workflowId),
                lastError = interruptedMessage,
            ),
        )
        showGenerationFailedUi()
        setStatusText(SpecCodingBundle.message("spec.workflow.error", interruptedMessage))
    }
}
