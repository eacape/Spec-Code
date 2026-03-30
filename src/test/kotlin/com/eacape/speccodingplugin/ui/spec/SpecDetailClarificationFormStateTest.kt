package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailClarificationFormStateTest {

    @Test
    fun `draft should infer checklist decisions and details from confirmed context`() {
        val draft = SpecDetailClarificationFormState.draft(
            phase = SpecPhase.SPECIFY,
            input = "clarify storage",
            questionsMarkdown = "1. offline?\n2. cloud only?",
            suggestedDetails = """
                **Confirmed Clarification Points**
                - Should support offline sync
                  - detail: Keep a local queue

                **Not Applicable Clarification Points**
                - Use cloud-only dependencies
            """.trimIndent(),
            structuredQuestions = listOf(
                "Should support offline sync",
                "Use cloud-only dependencies",
            ),
            text = clarificationText(),
        )

        assertEquals(
            SpecDetailClarificationQuestionDecision.CONFIRMED,
            draft.state.questionDecisions[0],
        )
        assertEquals("Keep a local queue", draft.state.questionDetails[0])
        assertEquals(
            SpecDetailClarificationQuestionDecision.NOT_APPLICABLE,
            draft.state.questionDecisions[1],
        )
        assertEquals(0, draft.activeDetailIndex)
    }

    @Test
    fun `withDecision should clear detail and move active index when confirmed item changes`() {
        val state = checklistState(
            decisions = mapOf(
                0 to SpecDetailClarificationQuestionDecision.CONFIRMED,
                1 to SpecDetailClarificationQuestionDecision.CONFIRMED,
            ),
            details = mapOf(
                0 to "Keep queue",
                1 to "Retry for 5 minutes",
            ),
        )

        val mutation = state.withDecision(
            index = 0,
            decision = SpecDetailClarificationQuestionDecision.NOT_APPLICABLE,
            activeDetailIndex = 0,
        )!!

        assertEquals(
            SpecDetailClarificationQuestionDecision.NOT_APPLICABLE,
            mutation.state.questionDecisions[0],
        )
        assertNull(mutation.state.questionDetails[0])
        assertEquals(1, mutation.activeDetailIndex)
    }

    @Test
    fun `withConfirmedDetail should normalize detail and serialize confirmed context`() {
        val state = checklistState(
            decisions = mapOf(
                0 to SpecDetailClarificationQuestionDecision.CONFIRMED,
                1 to SpecDetailClarificationQuestionDecision.NOT_APPLICABLE,
            ),
        )

        val mutation = state.withConfirmedDetail(
            index = 0,
            detail = "  Keep queue\n  and retry locally  ",
            activeDetailIndex = null,
        )!!

        assertEquals("Keep queue and retry locally", mutation.state.questionDetails[0])
        assertEquals(0, mutation.activeDetailIndex)

        val context = mutation.state.confirmedContext(clarificationText())
        assertTrue(context.contains("**Confirmed Clarification Points**"))
        assertTrue(context.contains("- Should support offline sync"))
        assertTrue(context.contains("- detail: Keep queue and retry locally"))
        assertTrue(context.contains("**Not Applicable Clarification Points**"))
        assertTrue(context.contains("- Use cloud-only dependencies"))
    }

    @Test
    fun `preview markdown should escape details and expose first missing confirmed question`() {
        val state = checklistState(
            decisions = mapOf(
                0 to SpecDetailClarificationQuestionDecision.CONFIRMED,
                1 to SpecDetailClarificationQuestionDecision.CONFIRMED,
            ),
            details = mapOf(
                0 to "use `sqlite` cache",
            ),
        )

        assertEquals("Use cloud-only dependencies", state.firstMissingConfirmedQuestion())

        val markdown = state.previewMarkdown(clarificationText())
        assertTrue(markdown.contains("`detail: use 'sqlite' cache`"))
        assertTrue(markdown.contains("- Use cloud-only dependencies"))
    }

    private fun checklistState(
        decisions: Map<Int, SpecDetailClarificationQuestionDecision>,
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
