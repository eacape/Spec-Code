package com.eacape.speccodingplugin.ui.spec

internal sealed interface SpecDetailClarificationActionPlan {
    data object Ignore : SpecDetailClarificationActionPlan

    data class Validation(
        val banner: SpecDetailPreviewValidationPlan,
    ) : SpecDetailClarificationActionPlan

    data class Confirm(
        val input: String,
        val confirmedContext: String,
        val setChecklistReadOnly: Boolean,
    ) : SpecDetailClarificationActionPlan

    data class Regenerate(
        val input: String,
        val confirmedContext: String,
    ) : SpecDetailClarificationActionPlan

    data class Skip(
        val input: String,
    ) : SpecDetailClarificationActionPlan

    data object Cancel : SpecDetailClarificationActionPlan
}

internal object SpecDetailClarificationActionCoordinator {

    fun confirm(
        state: SpecDetailClarificationFormState?,
        clarificationInput: String,
        clarificationText: SpecDetailClarificationText,
    ): SpecDetailClarificationActionPlan {
        val currentState = state ?: return SpecDetailClarificationActionPlan.Ignore
        return when (
            val plan = SpecDetailClarificationConfirmCoordinator.resolve(
                state = currentState,
                clarificationInput = clarificationInput,
                clarificationText = clarificationText,
            )
        ) {
            is SpecDetailClarificationConfirmPlan.Validation -> {
                SpecDetailClarificationActionPlan.Validation(plan.banner)
            }

            is SpecDetailClarificationConfirmPlan.Submit -> {
                SpecDetailClarificationActionPlan.Confirm(
                    input = plan.input,
                    confirmedContext = plan.confirmedContext,
                    setChecklistReadOnly = plan.setChecklistReadOnly,
                )
            }
        }
    }

    fun regenerate(
        state: SpecDetailClarificationFormState?,
        clarificationInput: String,
        clarificationText: SpecDetailClarificationText,
    ): SpecDetailClarificationActionPlan {
        val currentState = state ?: return SpecDetailClarificationActionPlan.Ignore
        return SpecDetailClarificationActionPlan.Regenerate(
            input = currentState.input,
            confirmedContext = SpecDetailClarificationContextCoordinator.resolveConfirmedContext(
                state = currentState,
                clarificationInput = clarificationInput,
                clarificationText = clarificationText,
            ),
        )
    }

    fun skip(state: SpecDetailClarificationFormState?): SpecDetailClarificationActionPlan {
        val currentState = state ?: return SpecDetailClarificationActionPlan.Ignore
        return SpecDetailClarificationActionPlan.Skip(input = currentState.input)
    }

    fun cancel(state: SpecDetailClarificationFormState?): SpecDetailClarificationActionPlan {
        return if (state == null) {
            SpecDetailClarificationActionPlan.Ignore
        } else {
            SpecDetailClarificationActionPlan.Cancel
        }
    }
}
