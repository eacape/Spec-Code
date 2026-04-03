package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailClarificationChecklistCoordinatorTest {

    @Test
    fun `planRowClick should request confirm detail when undecided question is clicked`() {
        val plan = SpecDetailClarificationChecklistCoordinator.planRowClick(
            state = checklistState(),
            activeDetailIndex = null,
            index = 0,
            fallbackDecision = SpecDetailClarificationQuestionDecision.UNDECIDED,
            text = clarificationText(),
        )

        assertEquals(
            SpecDetailClarificationChecklistRowClickPlan.RequestConfirmDetail(
                SpecDetailClarificationConfirmDetailRequest(
                    question = "Should support offline sync",
                    initialDetail = "",
                ),
            ),
            plan,
        )
    }

    @Test
    fun `planRowClick should clear confirmed question without requesting detail`() {
        val plan = SpecDetailClarificationChecklistCoordinator.planRowClick(
            state = checklistState(
                decisions = mapOf(
                    0 to SpecDetailClarificationQuestionDecision.CONFIRMED,
                ),
                details = mapOf(
                    0 to "Keep queue",
                ),
            ),
            activeDetailIndex = 0,
            index = 0,
            fallbackDecision = SpecDetailClarificationQuestionDecision.CONFIRMED,
            text = clarificationText(),
        )

        val apply = assertApply(plan)
        assertTrue(apply.result.questionListChanged)
        assertTrue(apply.result.state.questionDecisions.isEmpty())
        assertEquals("", apply.result.confirmedContext)
        assertEquals(null, apply.result.activeDetailIndex)
    }

    @Test
    fun `prepareConfirmDetail should reuse existing detail`() {
        val request = SpecDetailClarificationChecklistCoordinator.prepareConfirmDetail(
            state = checklistState(
                decisions = mapOf(
                    0 to SpecDetailClarificationQuestionDecision.CONFIRMED,
                ),
                details = mapOf(
                    0 to "Keep queue",
                ),
            ),
            index = 0,
        )

        assertEquals(
            SpecDetailClarificationConfirmDetailRequest(
                question = "Should support offline sync",
                initialDetail = "Keep queue",
            ),
            request,
        )
    }

    @Test
    fun `applyConfirmedDetail should confirm undecided question before storing detail`() {
        val result = SpecDetailClarificationChecklistCoordinator.applyConfirmedDetail(
            state = checklistState(),
            activeDetailIndex = null,
            index = 0,
            detail = "Keep queue locally",
            text = clarificationText(),
        )

        val resolved = requireNotNull(result)
        assertTrue(resolved.questionListChanged)
        assertEquals(
            SpecDetailClarificationQuestionDecision.CONFIRMED,
            resolved.state.questionDecisions[0],
        )
        assertEquals("Keep queue locally", resolved.state.questionDetails[0])
        assertEquals(0, resolved.activeDetailIndex)
        assertTrue(resolved.confirmedContext.contains("Should support offline sync"))
        assertTrue(resolved.confirmedContext.contains("Keep queue locally"))
    }

    @Test
    fun `applyConfirmedDetail should update existing detail without forcing question rerender`() {
        val result = SpecDetailClarificationChecklistCoordinator.applyConfirmedDetail(
            state = checklistState(
                decisions = mapOf(
                    0 to SpecDetailClarificationQuestionDecision.CONFIRMED,
                ),
                details = mapOf(
                    0 to "Keep queue",
                ),
            ),
            activeDetailIndex = 0,
            index = 0,
            detail = "Retry locally",
            text = clarificationText(),
        )

        val resolved = requireNotNull(result)
        assertFalse(resolved.questionListChanged)
        assertEquals("Retry locally", resolved.state.questionDetails[0])
        assertEquals(0, resolved.activeDetailIndex)
    }

    private fun assertApply(
        plan: SpecDetailClarificationChecklistRowClickPlan?,
    ): SpecDetailClarificationChecklistRowClickPlan.Apply {
        assertNotNull(plan)
        assertTrue(plan is SpecDetailClarificationChecklistRowClickPlan.Apply)
        return plan as SpecDetailClarificationChecklistRowClickPlan.Apply
    }

    private fun checklistState(
        decisions: Map<Int, SpecDetailClarificationQuestionDecision> = emptyMap(),
        details: Map<Int, String> = emptyMap(),
    ): SpecDetailClarificationFormState {
        return SpecDetailClarificationFormState(
            phase = SpecPhase.SPECIFY,
            input = "clarify storage",
            questionsMarkdown = "checklist",
            structuredQuestions = listOf(
                "Should support offline sync",
                "Use cloud-only dependencies",
            ),
            questionDecisions = decisions,
            questionDetails = details,
        )
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
}
