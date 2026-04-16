package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId

internal class SpecWorkflowRequirementsRepairEntryCoordinator(
    private val retryStore: SpecWorkflowClarificationRetryStore,
    private val resolveWorkflow: (String) -> SpecWorkflow?,
    private val gateRequirementsRepairCoordinator: SpecWorkflowGateRequirementsRepairCoordinator,
    private val showWorkspaceContent: () -> Unit,
    private val focusStage: (StageId) -> Unit,
    private val clearProcessTimeline: () -> Unit,
    private val appendTimelineEntry: (SpecWorkflowTimelineEntry) -> Unit,
    private val launchClarification: (SpecWorkflowRequirementsRepairClarificationLaunchRequest) -> Unit,
) {

    fun startClarifyThenFill(
        workflowId: String,
        missingSections: List<RequirementsSectionId>,
    ): Boolean {
        val workflow = resolveWorkflow(workflowId) ?: return false
        val previousRetry = retryStore.current(workflow.id)
        val plan = gateRequirementsRepairCoordinator.buildClarifyThenFillPlan(
            missingSections = missingSections,
            previousRetry = previousRetry,
        ) ?: return false

        showWorkspaceContent()
        focusStage(StageId.REQUIREMENTS)
        if (!plan.reusedPreviousRetry) {
            clearProcessTimeline()
        }
        appendClarificationRoundEntry(plan.clarificationRound)
        if (plan.reusedPreviousRetry) {
            appendRetryContextReuseEntry()
        }

        val refreshedRetry = retryStore.remember(
            SpecWorkflowClarificationRetryRememberRequest(
                workflowId = workflow.id,
                input = plan.input,
                confirmedContext = plan.suggestedDetails,
                clarificationRound = plan.clarificationRound,
                confirmed = false,
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = plan.normalizedSections,
            ),
        )
        launchClarification(
            SpecWorkflowRequirementsRepairClarificationLaunchRequest(
                workflow = workflow,
                input = plan.input,
                suggestedDetails = plan.suggestedDetails,
                pendingRetry = refreshedRetry,
                clarificationRound = plan.clarificationRound,
            ),
        )
        return true
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
