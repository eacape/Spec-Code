package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.SpecWorkflow

internal class SpecWorkflowGenerationLaunchCoordinator(
    private val retryStore: SpecWorkflowClarificationRetryStore,
    private val resolveSelectedWorkflow: () -> SpecWorkflow?,
    private val resolveGenerationContext: () -> SpecWorkflowGenerationContext?,
    private val clearProcessTimeline: () -> Unit,
    private val appendTimelineEntry: (SpecWorkflowTimelineEntry) -> Unit,
    private val generationCoordinator: SpecWorkflowGenerationCoordinator,
    private val gateRequirementsRepairCoordinator: SpecWorkflowGateRequirementsRepairCoordinator,
    private val runGeneration: (SpecWorkflowGenerationExecutionRequest) -> Unit,
    private val requestClarificationDraft: (SpecWorkflowClarificationDraftLaunchRequest) -> Unit,
    private val launchRequirementsRepairClarification: (SpecWorkflowRequirementsRepairClarificationLaunchRequest) -> Unit,
    private val continueRequirementsRepairAfterClarification: (SpecWorkflowContinueRequirementsRepairRequest) -> Unit,
) {

    fun generate(input: String) {
        val workflow = resolveSelectedWorkflow() ?: return
        val pendingRetry = retryStore.current(workflow.id)
        if (pendingRetry?.followUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR) {
            handleRequirementsRepairGenerate(
                workflow = workflow,
                input = input,
                pendingRetry = pendingRetry,
            )
            return
        }

        val context = resolveGenerationContext() ?: return
        when (
            val launchPlan = generationCoordinator.buildLaunchPlan(
                input = input,
                pendingRetry = pendingRetry,
                context = context,
            )
        ) {
            is SpecWorkflowGenerationLaunchPlan.ResumeGeneration -> {
                if (launchPlan.shouldShowRetryContextReuse) {
                    appendRetryContextReuseEntry()
                }
                runGeneration(
                    SpecWorkflowGenerationExecutionRequest(
                        workflowId = launchPlan.workflowId,
                        phase = context.phase,
                        input = launchPlan.input,
                        options = launchPlan.options,
                    ),
                )
            }

            is SpecWorkflowGenerationLaunchPlan.RequestClarification -> {
                if (launchPlan.shouldClearProcessTimeline) {
                    clearProcessTimeline()
                }
                appendClarificationRoundEntry(launchPlan.clarificationRound)
                if (launchPlan.shouldShowRetryContextReuse) {
                    appendRetryContextReuseEntry()
                }
                retryStore.remember(
                    SpecWorkflowClarificationRetryRememberRequest(
                        workflowId = launchPlan.context.workflowId,
                        input = launchPlan.input,
                        confirmedContext = launchPlan.suggestedDetails,
                        clarificationRound = launchPlan.clarificationRound,
                        lastError = launchPlan.retryLastError,
                        confirmed = false,
                        followUp = ClarificationFollowUp.GENERATION,
                        requirementsRepairSections = emptyList(),
                    ),
                )
                requestClarificationDraft(
                    SpecWorkflowClarificationDraftLaunchRequest(
                        context = launchPlan.context,
                        input = launchPlan.input,
                        options = launchPlan.options,
                        suggestedDetails = launchPlan.suggestedDetails,
                        seedQuestionsMarkdown = launchPlan.seedQuestionsMarkdown,
                        seedStructuredQuestions = launchPlan.seedStructuredQuestions,
                        clarificationRound = launchPlan.clarificationRound,
                    ),
                )
            }
        }
    }

    private fun handleRequirementsRepairGenerate(
        workflow: SpecWorkflow,
        input: String,
        pendingRetry: ClarificationRetryPayload,
    ) {
        val resumePlan = gateRequirementsRepairCoordinator.buildResumePlan(
            input = input,
            pendingRetry = pendingRetry,
        )
        if (resumePlan.resumeWithConfirmedContext) {
            appendRetryContextReuseEntry()
            continueRequirementsRepairAfterClarification(
                SpecWorkflowContinueRequirementsRepairRequest(
                    workflowId = workflow.id,
                    pendingRetry = pendingRetry,
                    input = resumePlan.input,
                    confirmedContext = pendingRetry.confirmedContext,
                ),
            )
            return
        }
        if (resumePlan.shouldClearProcessTimeline) {
            clearProcessTimeline()
        }
        appendClarificationRoundEntry(resumePlan.clarificationRound)
        appendRetryContextReuseEntry()
        retryStore.remember(
            SpecWorkflowClarificationRetryRememberRequest(
                workflowId = workflow.id,
                input = resumePlan.input,
                confirmedContext = resumePlan.suggestedDetails,
                clarificationRound = resumePlan.clarificationRound,
                lastError = pendingRetry.lastError,
                confirmed = false,
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = pendingRetry.requirementsRepairSections,
            ),
        )
        launchRequirementsRepairClarification(
            SpecWorkflowRequirementsRepairClarificationLaunchRequest(
                workflow = workflow,
                input = resumePlan.input,
                suggestedDetails = resumePlan.suggestedDetails,
                pendingRetry = retryStore.current(workflow.id),
                clarificationRound = resumePlan.clarificationRound,
            ),
        )
    }

    private fun appendClarificationRoundEntry(round: Int) {
        appendTimelineEntry(
            SpecWorkflowTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.clarify.round", round),
                state = SpecWorkflowTimelineEntryState.ACTIVE,
            ),
        )
    }

    private fun appendRetryContextReuseEntry() {
        appendTimelineEntry(
            SpecWorkflowTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                state = SpecWorkflowTimelineEntryState.INFO,
            ),
        )
    }
}
