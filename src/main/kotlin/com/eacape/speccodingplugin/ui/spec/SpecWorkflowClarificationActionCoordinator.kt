package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.GenerationOptions
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow

internal data class SpecWorkflowClarificationDraftLaunchRequest(
    val context: SpecWorkflowGenerationContext,
    val input: String,
    val options: GenerationOptions = context.options,
    val suggestedDetails: String = input,
    val seedQuestionsMarkdown: String? = null,
    val seedStructuredQuestions: List<String> = emptyList(),
    val clarificationRound: Int = 1,
)

internal data class SpecWorkflowClarificationRunGenerationRequest(
    val workflowId: String,
    val input: String,
    val options: GenerationOptions,
)

internal data class SpecWorkflowRequirementsRepairClarificationLaunchRequest(
    val workflow: SpecWorkflow,
    val input: String,
    val suggestedDetails: String,
    val pendingRetry: ClarificationRetryPayload?,
    val clarificationRound: Int,
)

internal data class SpecWorkflowContinueRequirementsRepairRequest(
    val workflowId: String,
    val pendingRetry: ClarificationRetryPayload,
    val input: String,
    val confirmedContext: String?,
)

internal class SpecWorkflowClarificationActionCoordinator(
    private val retryStore: SpecWorkflowClarificationRetryStore,
    private val resolveSelectedWorkflow: () -> SpecWorkflow?,
    private val resolveGenerationContext: () -> SpecWorkflowGenerationContext?,
    private val selectedWorkflowId: () -> String?,
    private val currentWorkflow: () -> SpecWorkflow?,
    private val appendTimelineEntry: (SpecWorkflowTimelineEntry) -> Unit,
    private val setStatusText: (String) -> Unit,
    private val unlockClarificationChecklistInteractions: () -> Unit,
    private val cancelActiveGenerationRequest: (String) -> Unit,
    private val requestClarificationDraft: (SpecWorkflowClarificationDraftLaunchRequest) -> Unit,
    private val runGeneration: (SpecWorkflowClarificationRunGenerationRequest) -> Unit,
    private val launchRequirementsRepairClarification: (SpecWorkflowRequirementsRepairClarificationLaunchRequest) -> Unit,
    private val continueRequirementsRepairAfterClarification: (SpecWorkflowContinueRequirementsRepairRequest) -> Unit,
) {

    fun confirm(input: String, confirmedContext: String) {
        val workflow = resolveSelectedWorkflow()
        if (workflow == null) {
            unlockClarificationChecklistInteractions()
            return
        }

        val pendingRetry = retryStore.current(workflow.id)
        appendTimelineEntry(
            SpecWorkflowTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.clarify.confirmed"),
                state = SpecWorkflowTimelineEntryState.DONE,
            ),
        )
        val refreshedRetry = retryStore.remember(
            SpecWorkflowClarificationRetryRememberRequest(
                workflowId = workflow.id,
                input = input,
                confirmedContext = confirmedContext,
                clarificationRound = pendingRetry?.clarificationRound,
                confirmed = true,
                followUp = pendingRetry?.followUp,
                requirementsRepairSections = pendingRetry?.requirementsRepairSections.orEmpty(),
            ),
        )
        if (refreshedRetry?.followUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR) {
            continueRequirementsRepairAfterClarification(
                SpecWorkflowContinueRequirementsRepairRequest(
                    workflowId = workflow.id,
                    pendingRetry = refreshedRetry,
                    input = input,
                    confirmedContext = confirmedContext,
                ),
            )
            return
        }

        val context = resolveGenerationContext()
        if (context == null) {
            unlockClarificationChecklistInteractions()
            return
        }
        runGeneration(
            SpecWorkflowClarificationRunGenerationRequest(
                workflowId = context.workflowId,
                input = input,
                options = context.options.copy(
                    confirmedContext = confirmedContext,
                    clarificationWriteback = refreshedRetry.toWritebackPayload(
                        confirmedContext = confirmedContext,
                    ),
                ),
            ),
        )
    }

    fun regenerate(input: String, currentDraft: String) {
        val workflow = resolveSelectedWorkflow() ?: return
        val pendingRetry = retryStore.current(workflow.id)
        val clarificationRound = (pendingRetry?.clarificationRound ?: 0) + 1
        appendTimelineEntry(
            SpecWorkflowTimelineEntry(
                text = SpecCodingBundle.message(
                    "spec.workflow.process.clarify.regenerate",
                    clarificationRound,
                ),
                state = SpecWorkflowTimelineEntryState.ACTIVE,
            ),
        )
        if (pendingRetry?.followUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR) {
            launchRequirementsRepairClarification(
                SpecWorkflowRequirementsRepairClarificationLaunchRequest(
                    workflow = workflow,
                    input = input,
                    suggestedDetails = currentDraft,
                    pendingRetry = pendingRetry,
                    clarificationRound = clarificationRound,
                ),
            )
            return
        }

        val context = resolveGenerationContext() ?: return
        requestClarificationDraft(
            SpecWorkflowClarificationDraftLaunchRequest(
                context = context,
                input = input,
                options = context.options.copy(confirmedContext = currentDraft),
                suggestedDetails = currentDraft,
                seedQuestionsMarkdown = pendingRetry?.questionsMarkdown,
                seedStructuredQuestions = pendingRetry?.structuredQuestions.orEmpty(),
                clarificationRound = clarificationRound,
            ),
        )
    }

    fun skip(input: String) {
        val workflow = resolveSelectedWorkflow() ?: return
        val pendingRetry = retryStore.current(workflow.id)
        if (pendingRetry?.followUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR) {
            val refreshedRetry = retryStore.remember(
                SpecWorkflowClarificationRetryRememberRequest(
                    workflowId = workflow.id,
                    input = input,
                    confirmedContext = "",
                    clarificationRound = pendingRetry.clarificationRound,
                    confirmed = false,
                    followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                    requirementsRepairSections = pendingRetry.requirementsRepairSections,
                ),
            ) ?: pendingRetry
            appendSkippedTimelineAndStatus(workflow.resolveComposeActionMode(workflow.currentPhase))
            continueRequirementsRepairAfterClarification(
                SpecWorkflowContinueRequirementsRepairRequest(
                    workflowId = workflow.id,
                    pendingRetry = refreshedRetry,
                    input = input,
                    confirmedContext = "",
                ),
            )
            return
        }

        val context = resolveGenerationContext() ?: return
        retryStore.clear(context.workflowId)
        appendSkippedTimelineAndStatus(
            context.options.composeActionMode ?: ArtifactComposeActionMode.GENERATE,
        )
        runGeneration(
            SpecWorkflowClarificationRunGenerationRequest(
                workflowId = context.workflowId,
                input = input,
                options = context.options,
            ),
        )
    }

    fun cancel() {
        cancelActiveGenerationRequest("Clarification cancelled by user")
        selectedWorkflowId()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(retryStore::clear)
        appendTimelineEntry(
            SpecWorkflowTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.clarify.cancelled"),
                state = SpecWorkflowTimelineEntryState.INFO,
            ),
        )
        val workflow = currentWorkflow()
        val composeMode = workflow?.resolveComposeActionMode(workflow.currentPhase)
            ?: ArtifactComposeActionMode.GENERATE
        setStatusText(
            ArtifactComposeActionUiText.clarificationCancelled(composeMode),
        )
    }

    fun autosave(
        input: String,
        confirmedContext: String,
        questionsMarkdown: String,
        structuredQuestions: List<String>,
    ) {
        val workflowId = selectedWorkflowId()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return
        val activeWorkflowId = currentWorkflow()?.id?.trim()
        if (activeWorkflowId != null && activeWorkflowId != workflowId) {
            return
        }
        val pendingRetry = retryStore.current(workflowId)
        retryStore.remember(
            SpecWorkflowClarificationRetryRememberRequest(
                workflowId = workflowId,
                input = input,
                confirmedContext = confirmedContext,
                questionsMarkdown = questionsMarkdown,
                structuredQuestions = structuredQuestions,
                clarificationRound = pendingRetry?.clarificationRound,
                followUp = pendingRetry?.followUp,
                requirementsRepairSections = pendingRetry?.requirementsRepairSections.orEmpty(),
                persist = false,
            ),
        )
    }

    private fun appendSkippedTimelineAndStatus(composeMode: ArtifactComposeActionMode) {
        appendTimelineEntry(
            SpecWorkflowTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.clarify.skipped"),
                state = SpecWorkflowTimelineEntryState.INFO,
            ),
        )
        setStatusText(
            ArtifactComposeActionUiText.clarificationSkippedProceed(composeMode),
        )
    }
}
