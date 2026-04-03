package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.GenerationOptions
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowSourceUsage
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SpecWorkflowGateRequirementsRepairCoordinatorTest {

    @Test
    fun `buildClarifyThenFillPlan should seed default input and details when there is no reusable retry`() {
        val coordinator = coordinator()

        val plan = coordinator.buildClarifyThenFillPlan(
            missingSections = listOf(
                RequirementsSectionId.NON_FUNCTIONAL,
                RequirementsSectionId.NON_FUNCTIONAL,
                RequirementsSectionId.ACCEPTANCE_CRITERIA,
            ),
            previousRetry = null,
        )

        requireNotNull(plan)
        assertEquals(
            listOf(RequirementsSectionId.NON_FUNCTIONAL, RequirementsSectionId.ACCEPTANCE_CRITERIA),
            plan.normalizedSections,
        )
        assertTrue(plan.input.contains("requirements.md"))
        assertTrue(plan.suggestedDetails.contains(SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.context.title")))
        assertTrue(plan.suggestedDetails.contains(SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.context.hint")))
        assertEquals(1, plan.clarificationRound)
        assertFalse(plan.reusedPreviousRetry)
    }

    @Test
    fun `buildClarifyThenFillPlan should reuse matching retry state`() {
        val coordinator = coordinator()
        val previousRetry = retryPayload(
            input = "existing input",
            confirmedContext = "existing context",
            clarificationRound = 2,
            requirementsRepairSections = listOf(RequirementsSectionId.USER_STORIES),
        )

        val plan = coordinator.buildClarifyThenFillPlan(
            missingSections = listOf(RequirementsSectionId.USER_STORIES),
            previousRetry = previousRetry,
        )

        requireNotNull(plan)
        assertEquals("existing input", plan.input)
        assertEquals("existing context", plan.suggestedDetails)
        assertEquals(3, plan.clarificationRound)
        assertTrue(plan.reusedPreviousRetry)
    }

    @Test
    fun `buildResumePlan should reuse confirmed context when user input is blank`() {
        val coordinator = coordinator()

        val plan = coordinator.buildResumePlan(
            input = "",
            pendingRetry = retryPayload(
                input = "seed input",
                confirmedContext = "confirmed context",
                clarificationRound = 4,
                confirmed = true,
                questionsMarkdown = "",
            ),
        )

        assertEquals("seed input", plan.input)
        assertEquals("confirmed context", plan.suggestedDetails)
        assertEquals(5, plan.clarificationRound)
        assertTrue(plan.resumeWithConfirmedContext)
        assertTrue(plan.shouldClearProcessTimeline)
    }

    @Test
    fun `prepareClarificationLaunch should return request draft when requirements ai is available`() {
        val coordinator = coordinator(aiUnavailableReason = { null })

        val launch = coordinator.prepareClarificationLaunch(
            workflow = workflow(id = "wf-draft", phase = SpecPhase.SPECIFY),
            providerId = " provider-1 ",
            modelId = " model-1 ",
            workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf("source-1")),
            pendingRetry = retryPayload(confirmedContext = "confirmed details"),
            input = "repair requirements",
            suggestedDetails = "details",
            clarificationRound = 2,
        )

        val request = launch as SpecWorkflowGateRequirementsClarificationLaunch.RequestDraft
        assertEquals("wf-draft", request.workflowId)
        assertEquals(SpecPhase.SPECIFY, request.phase)
        assertEquals(
            GenerationOptions(
                providerId = "provider-1",
                model = "model-1",
                confirmedContext = "confirmed details",
                workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf("source-1")),
                composeActionMode = workflow(id = "wf-draft", phase = SpecPhase.SPECIFY)
                    .resolveComposeActionMode(SpecPhase.SPECIFY),
            ),
            request.options,
        )
        assertEquals("repair requirements", request.input)
        assertEquals("details", request.suggestedDetails)
        assertEquals(2, request.clarificationRound)
    }

    @Test
    fun `prepareClarificationLaunch should fall back when workflow is not in specify phase`() {
        val coordinator = coordinator()

        val launch = coordinator.prepareClarificationLaunch(
            workflow = workflow(id = "wf-design", phase = SpecPhase.DESIGN),
            providerId = "provider-1",
            modelId = "model-1",
            workflowSourceUsage = WorkflowSourceUsage(),
            pendingRetry = null,
            input = "repair requirements",
            suggestedDetails = "details",
            clarificationRound = 1,
        )

        val fallback = launch as SpecWorkflowGateRequirementsClarificationLaunch.ManualFallback
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.manualFallback.phase"),
            fallback.reason,
        )
        assertTrue(fallback.questionsMarkdown.contains("markdown:"))
    }

    @Test
    fun `continueAfterClarification should report noop when repair sections are missing`() {
        val coordinator = coordinator()

        val continuation = coordinator.continueAfterClarification(
            SpecWorkflowGateRequirementsRepairAfterClarificationRequest(
                workflowId = "wf-noop",
                pendingRetry = retryPayload(requirementsRepairSections = emptyList()),
                confirmedContext = "context",
            ),
        )

        val noop = continuation as SpecWorkflowGateRequirementsRepairContinuation.Noop
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.noop.message"),
            noop.statusMessage,
        )
    }

    @Test
    fun `continueAfterClarification should require manual continuation when ai is unavailable`() {
        val coordinator = coordinator(aiUnavailableReason = { "AI offline" })

        val continuation = coordinator.continueAfterClarification(
            SpecWorkflowGateRequirementsRepairAfterClarificationRequest(
                workflowId = "wf-manual",
                pendingRetry = retryPayload(
                    requirementsRepairSections = listOf(RequirementsSectionId.NON_FUNCTIONAL),
                ),
                confirmedContext = "confirmed",
            ),
        )

        val fallback = continuation as SpecWorkflowGateRequirementsRepairContinuation.ManualFallback
        assertEquals(Path.of("/tmp/wf-manual-requirements.md"), fallback.requirementsDocumentPath)
        assertTrue(fallback.statusMessage.contains("AI offline"))
        assertTrue(fallback.infoMessage.contains("AI offline"))
    }

    @Test
    fun `continueAfterClarification should return preview apply request when ai is available`() {
        val coordinator = coordinator(aiUnavailableReason = { null })

        val continuation = coordinator.continueAfterClarification(
            SpecWorkflowGateRequirementsRepairAfterClarificationRequest(
                workflowId = "wf-apply",
                pendingRetry = retryPayload(
                    requirementsRepairSections = listOf(
                        RequirementsSectionId.USER_STORIES,
                        RequirementsSectionId.USER_STORIES,
                    ),
                ),
                confirmedContext = "confirmed",
            ),
        )

        val preview = continuation as SpecWorkflowGateRequirementsRepairContinuation.PreviewAndApply
        assertEquals(listOf(RequirementsSectionId.USER_STORIES), preview.missingSections)
        assertEquals("confirmed", preview.confirmedContextOverride)
    }

    private fun coordinator(
        aiUnavailableReason: (String?) -> String? = { null },
    ): SpecWorkflowGateRequirementsRepairCoordinator {
        return SpecWorkflowGateRequirementsRepairCoordinator(
            aiUnavailableReason = aiUnavailableReason,
            locateRequirementsArtifact = { workflowId ->
                Path.of("/tmp/${workflowId}-requirements.md")
            },
            renderClarificationFailureMarkdown = { error ->
                "markdown:${error.message}"
            },
        )
    }

    private fun retryPayload(
        input: String = "repair input",
        confirmedContext: String = "repair context",
        clarificationRound: Int = 1,
        questionsMarkdown: String = "questions",
        confirmed: Boolean = false,
        requirementsRepairSections: List<RequirementsSectionId> = listOf(RequirementsSectionId.NON_FUNCTIONAL),
    ): ClarificationRetryPayload {
        return ClarificationRetryPayload(
            input = input,
            confirmedContext = confirmedContext,
            questionsMarkdown = questionsMarkdown,
            structuredQuestions = listOf("Q1"),
            clarificationRound = clarificationRound,
            lastError = null,
            confirmed = confirmed,
            followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
            requirementsRepairSections = requirementsRepairSections,
        )
    }

    private fun workflow(
        id: String,
        phase: SpecPhase,
    ): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = phase,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Workflow $id",
        )
    }
}
