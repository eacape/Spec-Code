package com.eacape.speccodingplugin.ui.spec

internal data class SpecDetailClarificationConfirmDetailRequest(
    val question: String,
    val initialDetail: String,
)

internal data class SpecDetailClarificationChecklistMutationResult(
    val state: SpecDetailClarificationFormState,
    val activeDetailIndex: Int?,
    val confirmedContext: String,
    val questionListChanged: Boolean,
)

internal sealed interface SpecDetailClarificationChecklistRowClickPlan {
    data class Apply(
        val result: SpecDetailClarificationChecklistMutationResult,
    ) : SpecDetailClarificationChecklistRowClickPlan

    data class RequestConfirmDetail(
        val request: SpecDetailClarificationConfirmDetailRequest,
    ) : SpecDetailClarificationChecklistRowClickPlan
}

internal object SpecDetailClarificationChecklistCoordinator {

    fun planRowClick(
        state: SpecDetailClarificationFormState,
        activeDetailIndex: Int?,
        index: Int,
        fallbackDecision: SpecDetailClarificationQuestionDecision,
        text: SpecDetailClarificationText,
    ): SpecDetailClarificationChecklistRowClickPlan? {
        if (state.structuredQuestions.isEmpty() || index !in state.structuredQuestions.indices) {
            return null
        }
        val currentDecision = state.questionDecisions[index] ?: fallbackDecision
        val nextDecision = when (currentDecision) {
            SpecDetailClarificationQuestionDecision.UNDECIDED -> SpecDetailClarificationQuestionDecision.CONFIRMED
            SpecDetailClarificationQuestionDecision.CONFIRMED -> SpecDetailClarificationQuestionDecision.UNDECIDED
            SpecDetailClarificationQuestionDecision.NOT_APPLICABLE -> SpecDetailClarificationQuestionDecision.CONFIRMED
        }
        return when (nextDecision) {
            SpecDetailClarificationQuestionDecision.CONFIRMED -> {
                prepareConfirmDetail(state, index)?.let(SpecDetailClarificationChecklistRowClickPlan::RequestConfirmDetail)
            }

            SpecDetailClarificationQuestionDecision.UNDECIDED,
            SpecDetailClarificationQuestionDecision.NOT_APPLICABLE,
            -> applyDecision(
                state = state,
                activeDetailIndex = activeDetailIndex,
                index = index,
                decision = nextDecision,
                text = text,
            )?.let(SpecDetailClarificationChecklistRowClickPlan::Apply)
        }
    }

    fun prepareConfirmDetail(
        state: SpecDetailClarificationFormState,
        index: Int,
    ): SpecDetailClarificationConfirmDetailRequest? {
        if (state.structuredQuestions.isEmpty() || index !in state.structuredQuestions.indices) {
            return null
        }
        return SpecDetailClarificationConfirmDetailRequest(
            question = state.structuredQuestions[index],
            initialDetail = state.questionDetails[index].orEmpty(),
        )
    }

    fun applyDecision(
        state: SpecDetailClarificationFormState,
        activeDetailIndex: Int?,
        index: Int,
        decision: SpecDetailClarificationQuestionDecision,
        text: SpecDetailClarificationText,
    ): SpecDetailClarificationChecklistMutationResult? {
        val mutation = state.withDecision(index, decision, activeDetailIndex) ?: return null
        return buildResult(
            mutation = mutation,
            text = text,
            questionListChanged = true,
        )
    }

    fun applyConfirmedDetail(
        state: SpecDetailClarificationFormState,
        activeDetailIndex: Int?,
        index: Int,
        detail: String,
        text: SpecDetailClarificationText,
    ): SpecDetailClarificationChecklistMutationResult? {
        if (state.structuredQuestions.isEmpty() || index !in state.structuredQuestions.indices) {
            return null
        }

        val requiresConfirmation = state.questionDecisions[index] != SpecDetailClarificationQuestionDecision.CONFIRMED
        val confirmedMutation = if (requiresConfirmation) {
            state.withDecision(
                index = index,
                decision = SpecDetailClarificationQuestionDecision.CONFIRMED,
                activeDetailIndex = activeDetailIndex,
            ) ?: return null
        } else {
            SpecDetailClarificationMutation(
                state = state,
                activeDetailIndex = activeDetailIndex,
            )
        }

        val detailMutation = confirmedMutation.state.withConfirmedDetail(
            index = index,
            detail = detail,
            activeDetailIndex = confirmedMutation.activeDetailIndex,
        ) ?: return null

        return buildResult(
            mutation = detailMutation,
            text = text,
            questionListChanged = requiresConfirmation,
        )
    }

    private fun buildResult(
        mutation: SpecDetailClarificationMutation,
        text: SpecDetailClarificationText,
        questionListChanged: Boolean,
    ): SpecDetailClarificationChecklistMutationResult {
        return SpecDetailClarificationChecklistMutationResult(
            state = mutation.state,
            activeDetailIndex = mutation.activeDetailIndex,
            confirmedContext = SpecDetailClarificationContextCoordinator.resolveConfirmedContext(
                state = mutation.state,
                clarificationInput = "",
                clarificationText = text,
            ),
            questionListChanged = questionListChanged,
        )
    }
}
