package com.eacape.speccodingplugin.ui.spec

internal data class SpecDetailClarificationPreviewEntry(
    val segments: List<SpecDetailClarificationInlineSegment>,
    val detailChipText: String? = null,
)

internal data class SpecDetailClarificationPreviewSection(
    val title: String,
    val entries: List<SpecDetailClarificationPreviewEntry>,
)

internal sealed interface SpecDetailClarificationPreviewPlan {
    data class Markdown(
        val content: String,
    ) : SpecDetailClarificationPreviewPlan

    data class Checklist(
        val sections: List<SpecDetailClarificationPreviewSection>,
        val emptyText: String,
        val fallbackMarkdown: String,
    ) : SpecDetailClarificationPreviewPlan
}

internal object SpecDetailClarificationPreviewCoordinator {

    fun buildPlan(
        state: SpecDetailClarificationFormState,
        input: String,
        text: SpecDetailClarificationText,
        emptyText: String,
    ): SpecDetailClarificationPreviewPlan {
        if (!state.checklistMode) {
            return SpecDetailClarificationPreviewPlan.Markdown(
                content = input.trim().ifBlank { emptyText },
            )
        }

        val sections = buildList {
            val confirmedEntries = state.confirmedEntries()
            if (confirmedEntries.isNotEmpty()) {
                add(
                    SpecDetailClarificationPreviewSection(
                        title = text.confirmedTitle,
                        entries = confirmedEntries.map { entry ->
                            SpecDetailClarificationPreviewEntry(
                                segments = SpecDetailClarificationInlineMarkdownParser.parse(
                                    text = entry.question,
                                    collapseWhitespace = true,
                                ),
                                detailChipText = entry.detail
                                    .takeIf(String::isNotBlank)
                                    ?.let { "${text.detailPrefix}: $it" },
                            )
                        },
                    ),
                )
            }

            val notApplicableQuestions = state.notApplicableQuestions()
            if (notApplicableQuestions.isNotEmpty()) {
                add(
                    SpecDetailClarificationPreviewSection(
                        title = text.notApplicableTitle,
                        entries = notApplicableQuestions.map { question ->
                            SpecDetailClarificationPreviewEntry(
                                segments = SpecDetailClarificationInlineMarkdownParser.parse(
                                    text = question,
                                    collapseWhitespace = true,
                                ),
                            )
                        },
                    ),
                )
            }
        }

        return SpecDetailClarificationPreviewPlan.Checklist(
            sections = sections,
            emptyText = emptyText,
            fallbackMarkdown = state.previewMarkdown(text).ifBlank { emptyText },
        )
    }
}
