package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecPhase

internal sealed interface SpecDetailClarificationConfirmPlan {
    data class Submit(
        val input: String,
        val confirmedContext: String,
        val setChecklistReadOnly: Boolean,
    ) : SpecDetailClarificationConfirmPlan

    data class Validation(
        val banner: SpecDetailPreviewValidationPlan,
    ) : SpecDetailClarificationConfirmPlan
}

internal object SpecDetailClarificationConfirmCoordinator {

    fun resolve(
        state: SpecDetailClarificationFormState,
        clarificationInput: String,
        clarificationText: SpecDetailClarificationText,
    ): SpecDetailClarificationConfirmPlan {
        val firstMissingDetailQuestion = state
            .takeIf { it.checklistMode }
            ?.firstMissingConfirmedQuestion()
        if (firstMissingDetailQuestion != null) {
            return validation(
                SpecCodingBundle.message(
                    "spec.detail.clarify.checklist.detail.required",
                    firstMissingDetailQuestion,
                ),
            )
        }

        val confirmedContext = SpecDetailClarificationContextCoordinator.resolveConfirmedContext(
            state = state,
            clarificationInput = clarificationInput,
            clarificationText = clarificationText,
        )
        if (confirmedContext.isBlank() && !allowsBlankConfirmation(state.phase)) {
            return validation(SpecCodingBundle.message("spec.detail.clarify.detailsRequired"))
        }

        return SpecDetailClarificationConfirmPlan.Submit(
            input = state.input,
            confirmedContext = confirmedContext,
            setChecklistReadOnly = true,
        )
    }

    private fun validation(message: String): SpecDetailClarificationConfirmPlan.Validation {
        return SpecDetailClarificationConfirmPlan.Validation(
            banner = SpecDetailPreviewValidationPlan(
                text = message,
                tone = SpecDetailPreviewValidationTone.ERROR,
            ),
        )
    }

    private fun allowsBlankConfirmation(phase: SpecPhase): Boolean {
        return phase == SpecPhase.DESIGN || phase == SpecPhase.IMPLEMENT
    }
}
