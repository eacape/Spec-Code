package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpecDetailClarificationChecklistRenderCoordinatorTest {

    @Test
    fun `buildPlan should derive progress and row state from checklist decisions`() {
        val state = checklistState(
            decisions = mapOf(
                0 to SpecDetailClarificationQuestionDecision.CONFIRMED,
                1 to SpecDetailClarificationQuestionDecision.NOT_APPLICABLE,
            ),
        )
        val structuredQuestions = listOf(
            "Need **SPEC** with `gradle test` support",
            "Use cloud-only dependencies",
        )
        val plan = SpecDetailClarificationChecklistRenderCoordinator.buildPlan(
            state = state.copy(structuredQuestions = structuredQuestions),
            structuredQuestions = structuredQuestions,
            questionDecisions = state.questionDecisions,
            activeDetailIndex = 0,
        )

        assertEquals(0, plan.activeDetailIndex)
        assertEquals(
            SpecDetailClarificationChecklistProgress(
                confirmedCount = 1,
                notApplicableCount = 1,
                totalCount = 2,
            ),
            plan.progress,
        )
        assertEquals("\u2713", plan.rowPlans[0].indicatorSymbol)
        assertEquals(SpecDetailClarificationChecklistRowTone.CONFIRMED, plan.rowPlans[0].tone)
        assertEquals(true, plan.rowPlans[0].confirmSelected)
        assertEquals(false, plan.rowPlans[0].notApplicableSelected)
        assertEquals(
            listOf(
                SpecDetailClarificationInlineSegment("Need "),
                SpecDetailClarificationInlineSegment("SPEC", bold = true),
                SpecDetailClarificationInlineSegment(" with "),
                SpecDetailClarificationInlineSegment("gradle test", inlineCode = true),
                SpecDetailClarificationInlineSegment(" support"),
            ),
            plan.rowPlans[0].questionSegments,
        )
        assertEquals("\u2205", plan.rowPlans[1].indicatorSymbol)
        assertEquals(SpecDetailClarificationChecklistRowTone.NOT_APPLICABLE, plan.rowPlans[1].tone)
        assertEquals(SpecDetailClarificationQuestionDecision.UNDECIDED, plan.rowPlans[1].notApplicableToggleDecision)
    }

    @Test
    fun `buildPlan should fall back to first confirmed detail when active selection is stale`() {
        val plan = SpecDetailClarificationChecklistRenderCoordinator.buildPlan(
            state = checklistState(
                decisions = mapOf(
                    1 to SpecDetailClarificationQuestionDecision.CONFIRMED,
                ),
            ),
            structuredQuestions = listOf(
                "Question A",
                "Question B",
            ),
            questionDecisions = mapOf(
                1 to SpecDetailClarificationQuestionDecision.CONFIRMED,
            ),
            activeDetailIndex = 0,
        )

        assertEquals(1, plan.activeDetailIndex)
    }

    @Test
    fun `buildPlan should derive fallback progress and active selection without form state`() {
        val plan = SpecDetailClarificationChecklistRenderCoordinator.buildPlan(
            state = null,
            structuredQuestions = listOf(
                "Question A",
                "Question B",
            ),
            questionDecisions = mapOf(
                1 to SpecDetailClarificationQuestionDecision.CONFIRMED,
            ),
            activeDetailIndex = null,
        )

        assertEquals(1, plan.activeDetailIndex)
        assertEquals(
            SpecDetailClarificationChecklistProgress(
                confirmedCount = 1,
                notApplicableCount = 0,
                totalCount = 2,
            ),
            plan.progress,
        )
        assertEquals("\u2022", plan.rowPlans[0].indicatorSymbol)
        assertEquals(SpecDetailClarificationChecklistRowTone.DEFAULT, plan.rowPlans[0].tone)
        assertEquals(SpecDetailClarificationQuestionDecision.NOT_APPLICABLE, plan.rowPlans[0].notApplicableToggleDecision)
    }

    @Test
    fun `buildPlan should clear active selection when no confirmed questions remain`() {
        val plan = SpecDetailClarificationChecklistRenderCoordinator.buildPlan(
            state = checklistState(),
            structuredQuestions = listOf(
                "Question A",
                "Question B",
            ),
            questionDecisions = emptyMap(),
            activeDetailIndex = 1,
        )

        assertNull(plan.activeDetailIndex)
    }

    private fun checklistState(
        decisions: Map<Int, SpecDetailClarificationQuestionDecision> = emptyMap(),
    ): SpecDetailClarificationFormState {
        return SpecDetailClarificationFormState(
            phase = SpecPhase.SPECIFY,
            input = "clarify storage",
            questionsMarkdown = "checklist",
            structuredQuestions = listOf(
                "Question A",
                "Question B",
            ),
            questionDecisions = decisions,
        )
    }
}
