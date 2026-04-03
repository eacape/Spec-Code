package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailClarificationPreviewCoordinatorTest {

    @Test
    fun `buildPlan should trim freeform clarification markdown input`() {
        val plan = SpecDetailClarificationPreviewCoordinator.buildPlan(
            state = freeformState(),
            input = "  Need offline sync support  ",
            text = clarificationText(),
            emptyText = "(empty)",
        )

        assertEquals(
            SpecDetailClarificationPreviewPlan.Markdown("Need offline sync support"),
            plan,
        )
    }

    @Test
    fun `buildPlan should return empty message for blank freeform clarification`() {
        val plan = SpecDetailClarificationPreviewCoordinator.buildPlan(
            state = freeformState(),
            input = "   ",
            text = clarificationText(),
            emptyText = "(empty)",
        )

        assertEquals(
            SpecDetailClarificationPreviewPlan.Markdown("(empty)"),
            plan,
        )
    }

    @Test
    fun `buildPlan should build checklist sections with parsed inline markdown and detail chip`() {
        val plan = SpecDetailClarificationPreviewCoordinator.buildPlan(
            state = checklistState(
                decisions = mapOf(
                    0 to SpecDetailClarificationQuestionDecision.CONFIRMED,
                    1 to SpecDetailClarificationQuestionDecision.NOT_APPLICABLE,
                ),
                details = mapOf(
                    0 to "Keep local cache",
                ),
            ),
            input = "ignored",
            text = clarificationText(),
            emptyText = "(empty)",
        )

        val checklist = plan as SpecDetailClarificationPreviewPlan.Checklist
        assertEquals(
            listOf(
                "Confirmed Clarification Points",
                "Not Applicable Clarification Points",
            ),
            checklist.sections.map { it.title },
        )
        assertEquals(
            listOf(
                SpecDetailClarificationInlineSegment("Need "),
                SpecDetailClarificationInlineSegment("SPEC", bold = true),
                SpecDetailClarificationInlineSegment(" with "),
                SpecDetailClarificationInlineSegment("gradle test", inlineCode = true),
                SpecDetailClarificationInlineSegment(" support"),
            ),
            checklist.sections.first().entries.single().segments,
        )
        assertEquals(
            "detail: Keep local cache",
            checklist.sections.first().entries.single().detailChipText,
        )
        assertTrue(checklist.fallbackMarkdown.contains("**Confirmed Clarification Points**"))
        assertTrue(checklist.fallbackMarkdown.contains("`detail: Keep local cache`"))
    }

    @Test
    fun `buildPlan should keep checklist fallback when nothing confirmed yet`() {
        val plan = SpecDetailClarificationPreviewCoordinator.buildPlan(
            state = checklistState(),
            input = "ignored",
            text = clarificationText(),
            emptyText = "(empty)",
        )

        val checklist = plan as SpecDetailClarificationPreviewPlan.Checklist
        assertTrue(checklist.sections.isEmpty())
        assertEquals("(empty)", checklist.fallbackMarkdown)
        assertEquals("(empty)", checklist.emptyText)
    }

    private fun freeformState(): SpecDetailClarificationFormState {
        return SpecDetailClarificationFormState(
            phase = SpecPhase.SPECIFY,
            input = "clarify storage",
            questionsMarkdown = "questions",
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
                "Need **SPEC** with `gradle test` support",
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
