package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecDetailClarificationPreviewRenderCoordinatorTest {

    @Test
    fun `buildPlan should emit muted empty text when checklist preview has no sections`() {
        val plan = SpecDetailClarificationPreviewRenderCoordinator.buildPlan(
            SpecDetailClarificationPreviewPlan.Checklist(
                sections = emptyList(),
                emptyText = "(empty)",
                fallbackMarkdown = "fallback",
            ),
        )

        assertEquals(
            listOf(
                SpecDetailClarificationPreviewRenderOperation.Text(
                    text = "(empty)",
                    style = SpecDetailClarificationPreviewRenderTextStyle.MUTED,
                ),
            ),
            plan.operations,
        )
        assertEquals("fallback", plan.fallbackMarkdown)
    }

    @Test
    fun `buildPlan should expand checklist sections into ordered render operations`() {
        val plan = SpecDetailClarificationPreviewRenderCoordinator.buildPlan(
            SpecDetailClarificationPreviewPlan.Checklist(
                sections = listOf(
                    SpecDetailClarificationPreviewSection(
                        title = "Confirmed Clarification Points",
                        entries = listOf(
                            SpecDetailClarificationPreviewEntry(
                                segments = listOf(
                                    SpecDetailClarificationInlineSegment("Need "),
                                    SpecDetailClarificationInlineSegment("SPEC", bold = true),
                                    SpecDetailClarificationInlineSegment(" with "),
                                    SpecDetailClarificationInlineSegment("gradle test", inlineCode = true),
                                ),
                                detailChipText = "detail: Keep local cache",
                            ),
                        ),
                    ),
                    SpecDetailClarificationPreviewSection(
                        title = "Not Applicable Clarification Points",
                        entries = listOf(
                            SpecDetailClarificationPreviewEntry(
                                segments = listOf(
                                    SpecDetailClarificationInlineSegment("Skip cloud-only dependencies"),
                                ),
                            ),
                        ),
                    ),
                ),
                emptyText = "(empty)",
                fallbackMarkdown = "fallback",
            ),
        )

        assertEquals(
            listOf(
                SpecDetailClarificationPreviewRenderOperation.Text(
                    "Confirmed Clarification Points",
                    SpecDetailClarificationPreviewRenderTextStyle.TITLE,
                ),
                SpecDetailClarificationPreviewRenderOperation.Newline,
                SpecDetailClarificationPreviewRenderOperation.Text(
                    "\u2022 ",
                    SpecDetailClarificationPreviewRenderTextStyle.BODY,
                ),
                SpecDetailClarificationPreviewRenderOperation.Text(
                    "Need ",
                    SpecDetailClarificationPreviewRenderTextStyle.BODY,
                ),
                SpecDetailClarificationPreviewRenderOperation.Text(
                    "SPEC",
                    SpecDetailClarificationPreviewRenderTextStyle.QUESTION_BOLD,
                ),
                SpecDetailClarificationPreviewRenderOperation.Text(
                    " with ",
                    SpecDetailClarificationPreviewRenderTextStyle.BODY,
                ),
                SpecDetailClarificationPreviewRenderOperation.Text(
                    "gradle test",
                    SpecDetailClarificationPreviewRenderTextStyle.QUESTION_CODE,
                ),
                SpecDetailClarificationPreviewRenderOperation.Text(
                    "  ",
                    SpecDetailClarificationPreviewRenderTextStyle.BODY,
                ),
                SpecDetailClarificationPreviewRenderOperation.Text(
                    " detail: Keep local cache ",
                    SpecDetailClarificationPreviewRenderTextStyle.DETAIL_CHIP,
                ),
                SpecDetailClarificationPreviewRenderOperation.Newline,
                SpecDetailClarificationPreviewRenderOperation.Newline,
                SpecDetailClarificationPreviewRenderOperation.Text(
                    "Not Applicable Clarification Points",
                    SpecDetailClarificationPreviewRenderTextStyle.TITLE,
                ),
                SpecDetailClarificationPreviewRenderOperation.Newline,
                SpecDetailClarificationPreviewRenderOperation.Text(
                    "\u2022 ",
                    SpecDetailClarificationPreviewRenderTextStyle.BODY,
                ),
                SpecDetailClarificationPreviewRenderOperation.Text(
                    "Skip cloud-only dependencies",
                    SpecDetailClarificationPreviewRenderTextStyle.BODY,
                ),
            ),
            plan.operations,
        )
    }

    @Test
    fun `buildPlan should skip blank detail chip text and preserve checklist sections for fallback`() {
        val sourcePlan = SpecDetailClarificationPreviewPlan.Checklist(
            sections = listOf(
                SpecDetailClarificationPreviewSection(
                    title = "Confirmed",
                    entries = listOf(
                        SpecDetailClarificationPreviewEntry(
                            segments = listOf(
                                SpecDetailClarificationInlineSegment("Question A"),
                            ),
                            detailChipText = "   ",
                        ),
                    ),
                ),
            ),
            emptyText = "(empty)",
            fallbackMarkdown = "fallback markdown",
        )

        val plan = SpecDetailClarificationPreviewRenderCoordinator.buildPlan(sourcePlan)

        assertEquals("fallback markdown", plan.fallbackMarkdown)
        assertEquals(
            listOf(
                SpecDetailClarificationPreviewRenderOperation.Text(
                    "Confirmed",
                    SpecDetailClarificationPreviewRenderTextStyle.TITLE,
                ),
                SpecDetailClarificationPreviewRenderOperation.Newline,
                SpecDetailClarificationPreviewRenderOperation.Text(
                    "\u2022 ",
                    SpecDetailClarificationPreviewRenderTextStyle.BODY,
                ),
                SpecDetailClarificationPreviewRenderOperation.Text(
                    "Question A",
                    SpecDetailClarificationPreviewRenderTextStyle.BODY,
                ),
            ),
            plan.operations,
        )
    }
}
