package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailClarificationLifecycleCoordinatorTest {

    @Test
    fun `showGenerating should start clarification generating lifecycle`() {
        val plan = SpecDetailClarificationLifecycleCoordinator.showGenerating(
            phase = SpecPhase.SPECIFY,
            input = "clarify cache policy",
            suggestedDetails = "cache details",
            mode = ArtifactComposeActionMode.GENERATE,
        )

        assertTrue(plan.lifecycleState.isGeneratingActive)
        assertTrue(plan.lifecycleState.isClarificationGenerating)
        assertFalse(plan.lifecycleState.checklistReadOnly)
        assertEquals("clarify cache policy", plan.lifecycleState.clarificationState?.input)
        assertEquals("cache details", plan.suggestedDetails)
        assertEquals(SpecDetailPreviewSurfaceCard.CLARIFY, plan.previewSurfacePlan.card)
        assertFalse(plan.previewSurfacePlan.clarificationPreviewVisible)
    }

    @Test
    fun `showDraft should stop generating and reuse checklist context sync`() {
        val plan = SpecDetailClarificationLifecycleCoordinator.showDraft(
            phase = SpecPhase.SPECIFY,
            input = "clarify offline support",
            questionsMarkdown = "1. Should support offline mode?",
            suggestedDetails = """
                **Confirmed Clarification Points**
                - Should support offline mode?
                  - detail: Keep queue locally
            """.trimIndent(),
            structuredQuestions = listOf("Should support offline mode?"),
            clarificationText = clarificationText(),
            mode = ArtifactComposeActionMode.GENERATE,
        )

        val state = plan.lifecycleState
        assertFalse(state.isGeneratingActive)
        assertFalse(state.isClarificationGenerating)
        assertEquals(0, state.activeDetailIndex)
        assertFalse(state.checklistReadOnly)
        assertNotNull(plan.inputSyncPlan)
        assertTrue(plan.inputSyncPlan!!.inputText.contains("Should support offline mode?"))
        assertTrue(plan.inputSyncPlan!!.inputText.contains("detail: Keep queue locally"))
        assertEquals(SpecDetailPreviewSurfaceCard.CLARIFY, plan.previewSurfacePlan.card)
        assertTrue(plan.previewSurfacePlan.clarificationPreviewVisible)
        assertTrue(plan.statusPlan.text.contains(SpecCodingBundle.message("spec.workflow.clarify.hint")))
    }

    @Test
    fun `exit should clear clarification lifecycle and choose preview card`() {
        val plan = SpecDetailClarificationLifecycleCoordinator.exit(
            isEditing = false,
            clearInput = true,
        )

        assertNull(plan.lifecycleState.clarificationState)
        assertFalse(plan.lifecycleState.isGeneratingActive)
        assertFalse(plan.lifecycleState.isClarificationGenerating)
        assertFalse(plan.lifecycleState.checklistReadOnly)
        assertTrue(plan.clearInput)
        assertEquals(SpecDetailPreviewSurfaceCard.PREVIEW, plan.previewSurfacePlan.card)
    }

    @Test
    fun `restoreAfterFailure should unlock checklist and stay in clarification when draft exists`() {
        val restorePlan = SpecDetailClarificationLifecycleCoordinator.restoreAfterFailure(
            state = SpecDetailClarificationLifecycleState(
                clarificationState = SpecDetailClarificationFormState(
                    phase = SpecPhase.SPECIFY,
                    input = "clarify cache policy",
                    questionsMarkdown = "1. clarify",
                    structuredQuestions = listOf("Should support offline mode?"),
                ),
                activeDetailIndex = 0,
                checklistReadOnly = true,
                isGeneratingActive = true,
                isClarificationGenerating = false,
            ),
            workflow = workflow("wf-clarify-failed"),
            selectedPhase = SpecPhase.SPECIFY,
        )

        assertFalse(restorePlan.lifecycleState.checklistReadOnly)
        assertFalse(restorePlan.lifecycleState.isGeneratingActive)
        assertFalse(restorePlan.lifecycleState.isClarificationGenerating)
        assertTrue(restorePlan.restoreClarificationPreview)
        assertNull(restorePlan.restoreDocumentPhase)
        assertEquals(SpecDetailPreviewSurfaceCard.CLARIFY, restorePlan.previewSurfacePlan?.card)
    }

    @Test
    fun `restoreAfterFailure should fall back to no workflow status when workflow is missing`() {
        val restorePlan = SpecDetailClarificationLifecycleCoordinator.restoreAfterFailure(
            state = SpecDetailClarificationLifecycleState(
                clarificationState = SpecDetailClarificationFormState(
                    phase = SpecPhase.SPECIFY,
                    input = "clarify retry",
                    questionsMarkdown = "1. clarify",
                ),
                isGeneratingActive = true,
                isClarificationGenerating = true,
            ),
            workflow = null,
            selectedPhase = null,
        )

        assertNull(restorePlan.previewSurfacePlan)
        assertFalse(restorePlan.restoreClarificationPreview)
        assertNull(restorePlan.restoreDocumentPhase)
        assertEquals(SpecCodingBundle.message("spec.detail.noWorkflow"), restorePlan.statusPlan?.text)
    }

    private fun clarificationText(): SpecDetailClarificationText {
        return SpecDetailClarificationText(
            confirmedTitle = "Confirmed Clarification Points",
            notApplicableTitle = "Not Applicable Clarification Points",
            detailPrefix = "detail",
            confirmedSectionMarkers = listOf("Confirmed Clarification Points"),
            notApplicableSectionMarkers = listOf("Not Applicable Clarification Points"),
        )
    }

    private fun workflow(id: String): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.SPECIFY,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Clarification Lifecycle",
            description = "clarification lifecycle test",
            createdAt = 1L,
            updatedAt = 2L,
        )
    }
}
