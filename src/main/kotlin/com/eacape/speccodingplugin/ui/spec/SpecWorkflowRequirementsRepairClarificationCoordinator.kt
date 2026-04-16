package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.WorkflowSourceUsage
import java.nio.file.Path

internal data class SpecWorkflowRequirementsRepairPreviewAndApplyRequest(
    val workflowId: String,
    val missingSections: List<RequirementsSectionId>,
    val confirmedContextOverride: String?,
    val onPreviewCancelled: () -> Unit,
    val onNoop: () -> Unit,
    val onApplied: () -> Unit,
    val onFailure: (Throwable) -> Unit,
)

internal class SpecWorkflowRequirementsRepairClarificationCoordinator(
    private val retryStore: SpecWorkflowClarificationRetryStore,
    private val gateRequirementsRepairCoordinator: SpecWorkflowGateRequirementsRepairCoordinator,
    private val resolveProviderId: () -> String?,
    private val resolveModelId: () -> String?,
    private val resolveWorkflowSourceUsage: (String) -> WorkflowSourceUsage,
    private val requestClarificationDraft: (SpecWorkflowClarificationDraftLaunchRequest) -> Unit,
    private val previewAndApply: (SpecWorkflowRequirementsRepairPreviewAndApplyRequest) -> Unit,
    private val appendTimelineEntry: (SpecWorkflowTimelineEntry) -> Unit,
    private val showClarificationManualFallback: (SpecWorkflowGateRequirementsClarificationLaunch.ManualFallback) -> Unit,
    private val setStatusText: (String) -> Unit,
    private val unlockClarificationChecklistInteractions: () -> Unit,
    private val exitClarificationMode: (Boolean) -> Unit,
    private val reloadRequirementsWorkflow: (Boolean) -> Unit,
    private val openRequirementsDocument: (Path) -> Unit,
    private val showInfo: (String, String) -> Unit,
    private val renderFailureMessage: (Throwable) -> String,
) {

    fun launchClarification(request: SpecWorkflowRequirementsRepairClarificationLaunchRequest) {
        when (
            val launch = gateRequirementsRepairCoordinator.prepareClarificationLaunch(
                workflow = request.workflow,
                providerId = resolveProviderId(),
                modelId = resolveModelId(),
                workflowSourceUsage = resolveWorkflowSourceUsage(request.workflow.id),
                pendingRetry = request.pendingRetry,
                input = request.input,
                suggestedDetails = request.suggestedDetails,
                clarificationRound = request.clarificationRound,
            )
        ) {
            is SpecWorkflowGateRequirementsClarificationLaunch.RequestDraft -> {
                requestClarificationDraft(
                    SpecWorkflowClarificationDraftLaunchRequest(
                        context = SpecWorkflowGenerationContext(
                            workflowId = launch.workflowId,
                            phase = launch.phase,
                            options = launch.options,
                        ),
                        input = launch.input,
                        options = launch.options,
                        suggestedDetails = launch.suggestedDetails,
                        seedQuestionsMarkdown = launch.seedQuestionsMarkdown,
                        seedStructuredQuestions = launch.seedStructuredQuestions,
                        clarificationRound = launch.clarificationRound,
                    ),
                )
            }

            is SpecWorkflowGateRequirementsClarificationLaunch.ManualFallback -> {
                retryStore.remember(
                    SpecWorkflowClarificationRetryRememberRequest(
                        workflowId = launch.workflowId,
                        input = launch.input,
                        confirmedContext = launch.suggestedDetails,
                        questionsMarkdown = launch.questionsMarkdown,
                        structuredQuestions = emptyList(),
                        clarificationRound = launch.clarificationRound,
                        lastError = launch.reason,
                        confirmed = false,
                        followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                        requirementsRepairSections = request.pendingRetry?.requirementsRepairSections.orEmpty(),
                    ),
                )
                showClarificationManualFallback(launch)
                appendTimelineEntry(
                    SpecWorkflowTimelineEntry(
                        text = SpecCodingBundle.message("spec.workflow.process.clarify.prepare"),
                        state = SpecWorkflowTimelineEntryState.DONE,
                    ),
                )
                appendTimelineEntry(
                    SpecWorkflowTimelineEntry(
                        text = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.manualFallback.timeline"),
                        state = SpecWorkflowTimelineEntryState.INFO,
                    ),
                )
                setStatusText(launch.statusMessage)
            }
        }
    }

    fun continueAfterClarification(request: SpecWorkflowContinueRequirementsRepairRequest) {
        when (
            val continuation = gateRequirementsRepairCoordinator.continueAfterClarification(
                SpecWorkflowGateRequirementsRepairAfterClarificationRequest(
                    workflowId = request.workflowId,
                    pendingRetry = request.pendingRetry,
                    confirmedContext = request.confirmedContext,
                ),
            )
        ) {
            is SpecWorkflowGateRequirementsRepairContinuation.Noop -> {
                unlockClarificationChecklistInteractions()
                setStatusText(continuation.statusMessage)
            }

            is SpecWorkflowGateRequirementsRepairContinuation.ManualFallback -> {
                unlockClarificationChecklistInteractions()
                exitClarificationMode(false)
                setStatusText(continuation.statusMessage)
                continuation.requirementsDocumentPath?.let(openRequirementsDocument)
                showInfo(continuation.infoTitle, continuation.infoMessage)
            }

            is SpecWorkflowGateRequirementsRepairContinuation.PreviewAndApply -> {
                previewAndApply(
                    SpecWorkflowRequirementsRepairPreviewAndApplyRequest(
                        workflowId = request.workflowId,
                        missingSections = continuation.missingSections,
                        confirmedContextOverride = continuation.confirmedContextOverride,
                        onPreviewCancelled = {
                            unlockClarificationChecklistInteractions()
                            setStatusText(
                                SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.previewCancelled"),
                            )
                        },
                        onNoop = {
                            retryStore.clear(request.workflowId)
                            exitClarificationMode(true)
                            unlockClarificationChecklistInteractions()
                            reloadRequirementsWorkflow(false)
                        },
                        onApplied = {
                            retryStore.clear(request.workflowId)
                            exitClarificationMode(true)
                            unlockClarificationChecklistInteractions()
                            reloadRequirementsWorkflow(true)
                        },
                        onFailure = { error ->
                            unlockClarificationChecklistInteractions()
                            val failureMessage = renderFailureMessage(error)
                            retryStore.remember(
                                SpecWorkflowClarificationRetryRememberRequest(
                                    workflowId = request.workflowId,
                                    input = request.input,
                                    confirmedContext = request.confirmedContext
                                        ?: request.pendingRetry.confirmedContext,
                                    clarificationRound = request.pendingRetry.clarificationRound,
                                    lastError = failureMessage,
                                    confirmed = request.pendingRetry.confirmed,
                                    followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                                    requirementsRepairSections = continuation.missingSections,
                                ),
                            )
                            setStatusText(
                                SpecCodingBundle.message("spec.workflow.error", failureMessage),
                            )
                        },
                    ),
                )
            }
        }
    }
}
