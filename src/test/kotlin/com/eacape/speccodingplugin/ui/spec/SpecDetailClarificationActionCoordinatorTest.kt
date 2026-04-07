package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailClarificationActionCoordinatorTest {

    @Test
    fun `confirm should ignore missing clarification state`() {
        val plan = SpecDetailClarificationActionCoordinator.confirm(
            state = null,
            clarificationInput = "context",
            clarificationText = clarificationText(),
        )

        assertEquals(SpecDetailClarificationActionPlan.Ignore, plan)
    }

    @Test
    fun `confirm should map validation plan from confirm coordinator`() {
        val plan = SpecDetailClarificationActionCoordinator.confirm(
            state = SpecDetailClarificationFormState(
                phase = SpecPhase.SPECIFY,
                input = "clarify cache strategy",
                questionsMarkdown = "1. checklist",
            ),
            clarificationInput = " \r\n ",
            clarificationText = clarificationText(),
        )

        val validation = plan as SpecDetailClarificationActionPlan.Validation
        assertEquals(
            SpecCodingBundle.message("spec.detail.clarify.detailsRequired"),
            validation.banner.text,
        )
        assertEquals(SpecDetailPreviewValidationTone.ERROR, validation.banner.tone)
    }

    @Test
    fun `confirm should map submit plan and lock checklist`() {
        val plan = SpecDetailClarificationActionCoordinator.confirm(
            state = SpecDetailClarificationFormState(
                phase = SpecPhase.DESIGN,
                input = "clarify design boundary",
                questionsMarkdown = "1. clarify",
            ),
            clarificationInput = "  line one\r\nline two \r\n",
            clarificationText = clarificationText(),
        )

        val submit = plan as SpecDetailClarificationActionPlan.Confirm
        assertEquals("clarify design boundary", submit.input)
        assertEquals("line one\nline two", submit.confirmedContext)
        assertTrue(submit.setChecklistReadOnly)
    }

    @Test
    fun `regenerate should reuse confirmed context builder`() {
        val plan = SpecDetailClarificationActionCoordinator.regenerate(
            state = SpecDetailClarificationFormState(
                phase = SpecPhase.SPECIFY,
                input = "clarify storage",
                questionsMarkdown = "checklist",
                structuredQuestions = listOf("Should support offline sync"),
                questionDecisions = mapOf(0 to SpecDetailClarificationQuestionDecision.CONFIRMED),
                questionDetails = mapOf(0 to "Keep queue locally"),
            ),
            clarificationInput = "",
            clarificationText = clarificationText(),
        )

        val regenerate = plan as SpecDetailClarificationActionPlan.Regenerate
        assertEquals("clarify storage", regenerate.input)
        assertTrue(regenerate.confirmedContext.contains("Should support offline sync"))
        assertTrue(regenerate.confirmedContext.contains("detail: Keep queue locally"))
    }

    @Test
    fun `skip and cancel should map action intent from clarification state`() {
        val state = SpecDetailClarificationFormState(
            phase = SpecPhase.SPECIFY,
            input = "clarify retry policy",
            questionsMarkdown = "1. clarify",
        )

        val skip = SpecDetailClarificationActionCoordinator.skip(state)
        val cancel = SpecDetailClarificationActionCoordinator.cancel(state)

        assertEquals(
            SpecDetailClarificationActionPlan.Skip(input = "clarify retry policy"),
            skip,
        )
        assertEquals(SpecDetailClarificationActionPlan.Cancel, cancel)
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
