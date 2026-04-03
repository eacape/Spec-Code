package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.GenerationOptions
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.RequirementsSectionSupport
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowSourceUsage
import java.nio.file.Path

internal data class SpecWorkflowGateRequirementsClarifyPlan(
    val normalizedSections: List<RequirementsSectionId>,
    val input: String,
    val suggestedDetails: String,
    val clarificationRound: Int,
    val reusedPreviousRetry: Boolean,
)

internal data class SpecWorkflowGateRequirementsResumePlan(
    val input: String,
    val suggestedDetails: String,
    val clarificationRound: Int,
    val resumeWithConfirmedContext: Boolean,
    val shouldClearProcessTimeline: Boolean,
)

internal sealed interface SpecWorkflowGateRequirementsClarificationLaunch {
    data class RequestDraft(
        val workflowId: String,
        val phase: SpecPhase,
        val options: GenerationOptions,
        val input: String,
        val suggestedDetails: String,
        val seedQuestionsMarkdown: String?,
        val seedStructuredQuestions: List<String>,
        val clarificationRound: Int,
    ) : SpecWorkflowGateRequirementsClarificationLaunch

    data class ManualFallback(
        val workflowId: String,
        val phase: SpecPhase,
        val input: String,
        val suggestedDetails: String,
        val questionsMarkdown: String,
        val clarificationRound: Int,
        val reason: String,
        val statusMessage: String,
    ) : SpecWorkflowGateRequirementsClarificationLaunch
}

internal data class SpecWorkflowGateRequirementsRepairAfterClarificationRequest(
    val workflowId: String,
    val pendingRetry: ClarificationRetryPayload,
    val confirmedContext: String?,
)

internal sealed interface SpecWorkflowGateRequirementsRepairContinuation {
    data class Noop(
        val statusMessage: String,
    ) : SpecWorkflowGateRequirementsRepairContinuation

    data class ManualFallback(
        val requirementsDocumentPath: Path?,
        val statusMessage: String,
        val infoTitle: String,
        val infoMessage: String,
    ) : SpecWorkflowGateRequirementsRepairContinuation

    data class PreviewAndApply(
        val missingSections: List<RequirementsSectionId>,
        val confirmedContextOverride: String?,
    ) : SpecWorkflowGateRequirementsRepairContinuation
}

internal class SpecWorkflowGateRequirementsRepairCoordinator(
    private val aiUnavailableReason: (providerHint: String?) -> String?,
    private val locateRequirementsArtifact: (workflowId: String) -> Path,
    private val renderClarificationFailureMarkdown: (Throwable) -> String,
) {

    fun buildClarifyThenFillPlan(
        missingSections: List<RequirementsSectionId>,
        previousRetry: ClarificationRetryPayload?,
    ): SpecWorkflowGateRequirementsClarifyPlan? {
        val normalizedSections = missingSections.distinct()
        if (normalizedSections.isEmpty()) {
            return null
        }
        val reusableRetry = previousRetry?.takeIf { retry ->
            retry.followUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR &&
                retry.requirementsRepairSections == normalizedSections
        }
        return SpecWorkflowGateRequirementsClarifyPlan(
            normalizedSections = normalizedSections,
            input = reusableRetry?.input?.takeIf(String::isNotBlank)
                ?: buildRequirementsRepairClarificationInput(normalizedSections),
            suggestedDetails = reusableRetry?.confirmedContext?.takeIf(String::isNotBlank)
                ?: buildRequirementsRepairSuggestedDetails(normalizedSections),
            clarificationRound = (reusableRetry?.clarificationRound ?: 0) + 1,
            reusedPreviousRetry = reusableRetry != null,
        )
    }

    fun buildResumePlan(
        input: String,
        pendingRetry: ClarificationRetryPayload,
    ): SpecWorkflowGateRequirementsResumePlan {
        val effectiveInput = input.ifBlank { pendingRetry.input }
        val suggestedDetails = when {
            input.isNotBlank() -> input
            pendingRetry.confirmedContext.isNotBlank() -> pendingRetry.confirmedContext
            else -> effectiveInput
        }
        return SpecWorkflowGateRequirementsResumePlan(
            input = effectiveInput,
            suggestedDetails = suggestedDetails,
            clarificationRound = pendingRetry.clarificationRound + 1,
            resumeWithConfirmedContext = pendingRetry.confirmed &&
                input.isBlank() &&
                pendingRetry.confirmedContext.isNotBlank(),
            shouldClearProcessTimeline = pendingRetry.questionsMarkdown.isBlank(),
        )
    }

    fun prepareClarificationLaunch(
        workflow: SpecWorkflow,
        providerId: String?,
        modelId: String?,
        workflowSourceUsage: WorkflowSourceUsage,
        pendingRetry: ClarificationRetryPayload?,
        input: String,
        suggestedDetails: String,
        clarificationRound: Int,
    ): SpecWorkflowGateRequirementsClarificationLaunch {
        val normalizedProvider = providerId?.trim()?.takeIf(String::isNotBlank)
        val normalizedModel = modelId?.trim()?.takeIf(String::isNotBlank)
        val unavailableReason = when {
            workflow.currentPhase != SpecPhase.SPECIFY ->
                SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.manualFallback.phase")

            normalizedProvider == null || normalizedModel == null ->
                aiUnavailableReason(null)
                    ?: SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.manualFallback.model")

            else -> null
        }
        if (unavailableReason != null) {
            return SpecWorkflowGateRequirementsClarificationLaunch.ManualFallback(
                workflowId = workflow.id,
                phase = workflow.currentPhase,
                input = input,
                suggestedDetails = suggestedDetails,
                questionsMarkdown = renderClarificationFailureMarkdown(IllegalStateException(unavailableReason)),
                clarificationRound = clarificationRound,
                reason = unavailableReason,
                statusMessage = SpecCodingBundle.message(
                    "spec.toolwindow.gate.quickFix.clarify.manualFallback.status",
                    unavailableReason,
                ),
            )
        }
        return SpecWorkflowGateRequirementsClarificationLaunch.RequestDraft(
            workflowId = workflow.id,
            phase = workflow.currentPhase,
            options = GenerationOptions(
                providerId = normalizedProvider,
                model = normalizedModel,
                confirmedContext = pendingRetry?.confirmedContext,
                workflowSourceUsage = workflowSourceUsage,
                composeActionMode = workflow.resolveComposeActionMode(workflow.currentPhase),
            ),
            input = input,
            suggestedDetails = suggestedDetails,
            seedQuestionsMarkdown = pendingRetry?.questionsMarkdown,
            seedStructuredQuestions = pendingRetry?.structuredQuestions.orEmpty(),
            clarificationRound = clarificationRound,
        )
    }

    fun continueAfterClarification(
        request: SpecWorkflowGateRequirementsRepairAfterClarificationRequest,
    ): SpecWorkflowGateRequirementsRepairContinuation {
        val requestedSections = request.pendingRetry.requirementsRepairSections.distinct()
        if (requestedSections.isEmpty()) {
            return SpecWorkflowGateRequirementsRepairContinuation.Noop(
                statusMessage = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.noop.message"),
            )
        }
        val unavailableReason = aiUnavailableReason(null)
        if (unavailableReason != null) {
            return SpecWorkflowGateRequirementsRepairContinuation.ManualFallback(
                requirementsDocumentPath = runCatching {
                    locateRequirementsArtifact(request.workflowId)
                }.getOrNull(),
                statusMessage = SpecCodingBundle.message(
                    "spec.toolwindow.gate.quickFix.clarify.manualFallback.status",
                    unavailableReason,
                ),
                infoTitle = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.manualContinue.title"),
                infoMessage = SpecCodingBundle.message(
                    "spec.toolwindow.gate.quickFix.clarify.manualContinue.message",
                    RequirementsSectionSupport.describeSections(requestedSections),
                    unavailableReason,
                ),
            )
        }
        return SpecWorkflowGateRequirementsRepairContinuation.PreviewAndApply(
            missingSections = requestedSections,
            confirmedContextOverride = request.confirmedContext,
        )
    }

    private fun buildRequirementsRepairClarificationInput(
        missingSections: List<RequirementsSectionId>,
    ): String {
        return buildString {
            append("Repair requirements.md by filling the missing top-level sections: ")
            append(RequirementsSectionSupport.describeSections(missingSections))
            append(".")
        }
    }

    private fun buildRequirementsRepairSuggestedDetails(
        missingSections: List<RequirementsSectionId>,
    ): String {
        return buildString {
            appendLine("## ${SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.context.title")}")
            appendLine(
                SpecCodingBundle.message(
                    "spec.toolwindow.gate.quickFix.missingSections",
                    RequirementsSectionSupport.describeSections(missingSections),
                ),
            )
            appendLine()
            appendLine(SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.context.hint"))
        }.trim()
    }
}
