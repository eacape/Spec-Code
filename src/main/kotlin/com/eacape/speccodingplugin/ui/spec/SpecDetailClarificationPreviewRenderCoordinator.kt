package com.eacape.speccodingplugin.ui.spec

internal enum class SpecDetailClarificationPreviewRenderTextStyle {
    BODY,
    TITLE,
    QUESTION_BOLD,
    QUESTION_CODE,
    DETAIL_CHIP,
    MUTED,
}

internal sealed interface SpecDetailClarificationPreviewRenderOperation {
    data class Text(
        val text: String,
        val style: SpecDetailClarificationPreviewRenderTextStyle,
    ) : SpecDetailClarificationPreviewRenderOperation

    data object Newline : SpecDetailClarificationPreviewRenderOperation
}

internal data class SpecDetailClarificationPreviewRenderPlan(
    val operations: List<SpecDetailClarificationPreviewRenderOperation>,
    val fallbackMarkdown: String,
)

internal object SpecDetailClarificationPreviewRenderCoordinator {

    fun buildPlan(plan: SpecDetailClarificationPreviewPlan.Checklist): SpecDetailClarificationPreviewRenderPlan {
        return SpecDetailClarificationPreviewRenderPlan(
            operations = if (plan.sections.isEmpty()) {
                listOf(
                    SpecDetailClarificationPreviewRenderOperation.Text(
                        text = plan.emptyText,
                        style = SpecDetailClarificationPreviewRenderTextStyle.MUTED,
                    ),
                )
            } else {
                buildList {
                    plan.sections.forEachIndexed { sectionIndex, section ->
                        if (sectionIndex > 0) {
                            add(SpecDetailClarificationPreviewRenderOperation.Newline)
                            add(SpecDetailClarificationPreviewRenderOperation.Newline)
                        }
                        add(
                            SpecDetailClarificationPreviewRenderOperation.Text(
                                text = section.title,
                                style = SpecDetailClarificationPreviewRenderTextStyle.TITLE,
                            ),
                        )
                        add(SpecDetailClarificationPreviewRenderOperation.Newline)
                        section.entries.forEachIndexed { entryIndex, entry ->
                            if (entryIndex > 0) {
                                add(SpecDetailClarificationPreviewRenderOperation.Newline)
                            }
                            add(
                                SpecDetailClarificationPreviewRenderOperation.Text(
                                    text = "\u2022 ",
                                    style = SpecDetailClarificationPreviewRenderTextStyle.BODY,
                                ),
                            )
                            entry.segments.forEach { segment ->
                                segment.text.takeIf(String::isNotEmpty)?.let { text ->
                                    add(
                                        SpecDetailClarificationPreviewRenderOperation.Text(
                                            text = text,
                                            style = segment.toRenderTextStyle(),
                                        ),
                                    )
                                }
                            }
                            entry.detailChipText
                                ?.takeIf(String::isNotBlank)
                                ?.let { detailChipText ->
                                    add(
                                        SpecDetailClarificationPreviewRenderOperation.Text(
                                            text = "  ",
                                            style = SpecDetailClarificationPreviewRenderTextStyle.BODY,
                                        ),
                                    )
                                    add(
                                        SpecDetailClarificationPreviewRenderOperation.Text(
                                            text = " $detailChipText ",
                                            style = SpecDetailClarificationPreviewRenderTextStyle.DETAIL_CHIP,
                                        ),
                                    )
                                }
                        }
                    }
                }
            },
            fallbackMarkdown = plan.fallbackMarkdown,
        )
    }

    private fun SpecDetailClarificationInlineSegment.toRenderTextStyle(): SpecDetailClarificationPreviewRenderTextStyle {
        return when {
            inlineCode -> SpecDetailClarificationPreviewRenderTextStyle.QUESTION_CODE
            bold -> SpecDetailClarificationPreviewRenderTextStyle.QUESTION_BOLD
            else -> SpecDetailClarificationPreviewRenderTextStyle.BODY
        }
    }
}
