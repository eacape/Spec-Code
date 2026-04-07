package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailClarificationContextCoordinatorTest {

    @Test
    fun `resolveConfirmedContext should normalize manual clarification input`() {
        val confirmedContext = SpecDetailClarificationContextCoordinator.resolveConfirmedContext(
            state = manualState(),
            clarificationInput = "  line one\r\nline two \r\n",
            clarificationText = clarificationText(),
        )

        assertEquals("line one\nline two", confirmedContext)
    }

    @Test
    fun `resolveConfirmedContext should serialize checklist selections`() {
        val confirmedContext = SpecDetailClarificationContextCoordinator.resolveConfirmedContext(
            state = checklistState(
                decisions = mapOf(
                    0 to SpecDetailClarificationQuestionDecision.CONFIRMED,
                    1 to SpecDetailClarificationQuestionDecision.NOT_APPLICABLE,
                ),
                details = mapOf(0 to "Keep queue locally"),
            ),
            clarificationInput = "",
            clarificationText = clarificationText(),
        )

        assertTrue(confirmedContext.contains("**Confirmed Clarification Points**"))
        assertTrue(confirmedContext.contains("Should support offline sync"))
        assertTrue(confirmedContext.contains("detail: Keep queue locally"))
        assertTrue(confirmedContext.contains("**Not Applicable Clarification Points**"))
        assertTrue(confirmedContext.contains("Allow cloud-only mode"))
    }

    @Test
    fun `resolveInputSyncPlan should mirror checklist confirmed context into input`() {
        val plan = SpecDetailClarificationContextCoordinator.resolveInputSyncPlan(
            state = checklistState(
                decisions = mapOf(0 to SpecDetailClarificationQuestionDecision.CONFIRMED),
                details = mapOf(0 to "Keep queue locally"),
            ),
            clarificationText = clarificationText(),
        )

        requireNotNull(plan)
        assertEquals(0, plan.caretPosition)
        assertTrue(plan.inputText.contains("Should support offline sync"))
        assertTrue(plan.inputText.contains("detail: Keep queue locally"))
    }

    @Test
    fun `resolveInputSyncPlan should ignore non checklist clarification state`() {
        val plan = SpecDetailClarificationContextCoordinator.resolveInputSyncPlan(
            state = manualState(),
            clarificationText = clarificationText(),
        )

        assertNull(plan)
    }

    @Test
    fun `resolveDraftAutosavePlan should preserve retry payload fields`() {
        val plan = SpecDetailClarificationContextCoordinator.resolveDraftAutosavePlan(
            state = checklistState(
                decisions = mapOf(0 to SpecDetailClarificationQuestionDecision.CONFIRMED),
                details = mapOf(0 to "Keep queue locally"),
            ),
            clarificationInput = "",
            clarificationText = clarificationText(),
        )

        assertEquals("clarify storage", plan.input)
        assertEquals("checklist", plan.questionsMarkdown)
        assertEquals(listOf("Should support offline sync", "Allow cloud-only mode"), plan.structuredQuestions)
        assertTrue(plan.confirmedContext.contains("Keep queue locally"))
    }

    private fun manualState(): SpecDetailClarificationFormState {
        return SpecDetailClarificationFormState(
            phase = SpecPhase.SPECIFY,
            input = "clarify storage",
            questionsMarkdown = "manual clarification",
        )
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
                "Allow cloud-only mode",
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
