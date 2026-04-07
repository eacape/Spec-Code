package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailClarificationConfirmCoordinatorTest {

    @Test
    fun `resolve should require detail for confirmed checklist question`() {
        val plan = SpecDetailClarificationConfirmCoordinator.resolve(
            state = SpecDetailClarificationFormState(
                phase = SpecPhase.SPECIFY,
                input = "clarify cache strategy",
                questionsMarkdown = "1. 是否需要多机房容灾？",
                structuredQuestions = listOf("是否需要多机房容灾？"),
                questionDecisions = mapOf(0 to SpecDetailClarificationQuestionDecision.CONFIRMED),
            ),
            clarificationInput = "",
            clarificationText = clarificationText(),
        )

        val validation = plan as SpecDetailClarificationConfirmPlan.Validation
        assertEquals(
            SpecCodingBundle.message("spec.detail.clarify.checklist.detail.required", "是否需要多机房容灾？"),
            validation.banner.text,
        )
        assertEquals(SpecDetailPreviewValidationTone.ERROR, validation.banner.tone)
    }

    @Test
    fun `resolve should require confirmed context for non blank restricted phases`() {
        val plan = SpecDetailClarificationConfirmCoordinator.resolve(
            state = SpecDetailClarificationFormState(
                phase = SpecPhase.SPECIFY,
                input = "clarify api constraints",
                questionsMarkdown = "1. clarify",
            ),
            clarificationInput = "  \r\n  ",
            clarificationText = clarificationText(),
        )

        val validation = plan as SpecDetailClarificationConfirmPlan.Validation
        assertEquals(
            SpecCodingBundle.message("spec.detail.clarify.detailsRequired"),
            validation.banner.text,
        )
    }

    @Test
    fun `resolve should allow blank confirmed context in implement phase`() {
        val plan = SpecDetailClarificationConfirmCoordinator.resolve(
            state = SpecDetailClarificationFormState(
                phase = SpecPhase.IMPLEMENT,
                input = "clarify task sequencing",
                questionsMarkdown = "1. clarify",
            ),
            clarificationInput = "",
            clarificationText = clarificationText(),
        )

        val submit = plan as SpecDetailClarificationConfirmPlan.Submit
        assertEquals("clarify task sequencing", submit.input)
        assertEquals("", submit.confirmedContext)
        assertTrue(submit.setChecklistReadOnly)
    }

    @Test
    fun `resolve should normalize manual clarification input before submit`() {
        val plan = SpecDetailClarificationConfirmCoordinator.resolve(
            state = SpecDetailClarificationFormState(
                phase = SpecPhase.DESIGN,
                input = "clarify design boundary",
                questionsMarkdown = "1. clarify",
            ),
            clarificationInput = "  line one\r\nline two \r\n",
            clarificationText = clarificationText(),
        )

        val submit = plan as SpecDetailClarificationConfirmPlan.Submit
        assertEquals("line one\nline two", submit.confirmedContext)
    }

    @Test
    fun `resolve should submit checklist confirmed context when details are complete`() {
        val plan = SpecDetailClarificationConfirmCoordinator.resolve(
            state = SpecDetailClarificationFormState(
                phase = SpecPhase.SPECIFY,
                input = "clarify cache strategy",
                questionsMarkdown = "1. 是否需要多机房容灾？",
                structuredQuestions = listOf("是否需要多机房容灾？"),
                questionDecisions = mapOf(0 to SpecDetailClarificationQuestionDecision.CONFIRMED),
                questionDetails = mapOf(0 to "至少双活，RTO < 30s"),
            ),
            clarificationInput = "",
            clarificationText = clarificationText(),
        )

        val submit = plan as SpecDetailClarificationConfirmPlan.Submit
        assertTrue(
            submit.confirmedContext.contains("**${SpecCodingBundle.message("spec.detail.clarify.confirmed.title")}**"),
        )
        assertTrue(submit.confirmedContext.contains("是否需要多机房容灾？"))
        assertTrue(
            submit.confirmedContext.contains(
                "${SpecCodingBundle.message("spec.detail.clarify.checklist.detail.exportPrefix")}: 至少双活，RTO < 30s",
            ),
        )
    }

    private fun clarificationText(): SpecDetailClarificationText {
        return SpecDetailClarificationText(
            confirmedTitle = SpecCodingBundle.message("spec.detail.clarify.confirmed.title"),
            notApplicableTitle = SpecCodingBundle.message("spec.detail.clarify.notApplicable.title"),
            detailPrefix = SpecCodingBundle.message("spec.detail.clarify.checklist.detail.exportPrefix"),
            confirmedSectionMarkers = listOf(SpecCodingBundle.message("spec.detail.clarify.confirmed.title")),
            notApplicableSectionMarkers = listOf(SpecCodingBundle.message("spec.detail.clarify.notApplicable.title")),
        )
    }
}
