package com.eacape.speccodingplugin.ui.spec

internal enum class SpecDetailClarificationChecklistRowTone {
    DEFAULT,
    CONFIRMED,
    NOT_APPLICABLE,
}

internal data class SpecDetailClarificationChecklistRowPlan(
    val index: Int,
    val decision: SpecDetailClarificationQuestionDecision,
    val active: Boolean,
    val detailPresent: Boolean,
    val indicatorSymbol: String,
    val tone: SpecDetailClarificationChecklistRowTone,
    val normalizedQuestion: String,
    val questionSegments: List<SpecDetailClarificationInlineSegment>,
    val confirmSelected: Boolean,
    val notApplicableSelected: Boolean,
    val notApplicableToggleDecision: SpecDetailClarificationQuestionDecision,
)

internal data class SpecDetailClarificationChecklistRenderPlan(
    val activeDetailIndex: Int?,
    val progress: SpecDetailClarificationChecklistProgress,
    val rowPlans: List<SpecDetailClarificationChecklistRowPlan>,
)

internal object SpecDetailClarificationChecklistRenderCoordinator {

    fun buildPlan(
        state: SpecDetailClarificationFormState?,
        structuredQuestions: List<String>,
        questionDecisions: Map<Int, SpecDetailClarificationQuestionDecision>,
        questionDetails: Map<Int, String>,
        activeDetailIndex: Int?,
    ): SpecDetailClarificationChecklistRenderPlan {
        val resolvedActiveDetailIndex = state?.resolvedActiveDetailIndex(activeDetailIndex)
            ?: fallbackActiveDetailIndex(
                structuredQuestions = structuredQuestions,
                questionDecisions = questionDecisions,
                activeDetailIndex = activeDetailIndex,
            )
        val progress = state?.progress()
            ?: SpecDetailClarificationChecklistProgress(
                confirmedCount = questionDecisions.values.count { it == SpecDetailClarificationQuestionDecision.CONFIRMED },
                notApplicableCount = questionDecisions.values.count { it == SpecDetailClarificationQuestionDecision.NOT_APPLICABLE },
                totalCount = structuredQuestions.size,
            )
        return SpecDetailClarificationChecklistRenderPlan(
            activeDetailIndex = resolvedActiveDetailIndex,
            progress = progress,
            rowPlans = structuredQuestions.mapIndexed { index, question ->
                val decision = questionDecisions[index] ?: SpecDetailClarificationQuestionDecision.UNDECIDED
                SpecDetailClarificationChecklistRowPlan(
                    index = index,
                    decision = decision,
                    active = resolvedActiveDetailIndex == index,
                    detailPresent = questionDetails[index]?.isNotBlank() == true,
                    indicatorSymbol = decision.toIndicatorSymbol(),
                    tone = decision.toRowTone(),
                    normalizedQuestion = SpecDetailClarificationInlineMarkdownParser.normalizeWhitespace(question),
                    questionSegments = SpecDetailClarificationInlineMarkdownParser.parse(
                        text = question,
                        collapseWhitespace = true,
                    ),
                    confirmSelected = decision == SpecDetailClarificationQuestionDecision.CONFIRMED,
                    notApplicableSelected = decision == SpecDetailClarificationQuestionDecision.NOT_APPLICABLE,
                    notApplicableToggleDecision = if (decision == SpecDetailClarificationQuestionDecision.NOT_APPLICABLE) {
                        SpecDetailClarificationQuestionDecision.UNDECIDED
                    } else {
                        SpecDetailClarificationQuestionDecision.NOT_APPLICABLE
                    },
                )
            },
        )
    }

    private fun fallbackActiveDetailIndex(
        structuredQuestions: List<String>,
        questionDecisions: Map<Int, SpecDetailClarificationQuestionDecision>,
        activeDetailIndex: Int?,
    ): Int? {
        val confirmedIndexes = structuredQuestions.indices
            .filter { index -> questionDecisions[index] == SpecDetailClarificationQuestionDecision.CONFIRMED }
        return when {
            confirmedIndexes.isEmpty() -> null
            activeDetailIndex in confirmedIndexes -> activeDetailIndex
            else -> confirmedIndexes.first()
        }
    }

    private fun SpecDetailClarificationQuestionDecision.toIndicatorSymbol(): String {
        return when (this) {
            SpecDetailClarificationQuestionDecision.CONFIRMED -> "\u2713"
            SpecDetailClarificationQuestionDecision.NOT_APPLICABLE -> "\u2205"
            SpecDetailClarificationQuestionDecision.UNDECIDED -> "\u2022"
        }
    }

    private fun SpecDetailClarificationQuestionDecision.toRowTone(): SpecDetailClarificationChecklistRowTone {
        return when (this) {
            SpecDetailClarificationQuestionDecision.CONFIRMED -> SpecDetailClarificationChecklistRowTone.CONFIRMED
            SpecDetailClarificationQuestionDecision.NOT_APPLICABLE -> SpecDetailClarificationChecklistRowTone.NOT_APPLICABLE
            SpecDetailClarificationQuestionDecision.UNDECIDED -> SpecDetailClarificationChecklistRowTone.DEFAULT
        }
    }
}
