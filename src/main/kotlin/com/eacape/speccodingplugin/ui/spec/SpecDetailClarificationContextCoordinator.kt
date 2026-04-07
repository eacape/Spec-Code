package com.eacape.speccodingplugin.ui.spec

internal data class SpecDetailClarificationInputSyncPlan(
    val inputText: String,
    val caretPosition: Int = 0,
)

internal data class SpecDetailClarificationDraftAutosavePlan(
    val input: String,
    val confirmedContext: String,
    val questionsMarkdown: String,
    val structuredQuestions: List<String>,
)

internal object SpecDetailClarificationContextCoordinator {

    fun resolveConfirmedContext(
        state: SpecDetailClarificationFormState,
        clarificationInput: String,
        clarificationText: SpecDetailClarificationText,
    ): String {
        return if (state.checklistMode) {
            state.confirmedContext(clarificationText)
        } else {
            normalizeContent(clarificationInput)
        }
    }

    fun resolveInputSyncPlan(
        state: SpecDetailClarificationFormState?,
        clarificationText: SpecDetailClarificationText,
    ): SpecDetailClarificationInputSyncPlan? {
        val currentState = state ?: return null
        if (!currentState.checklistMode) {
            return null
        }
        return SpecDetailClarificationInputSyncPlan(
            inputText = resolveConfirmedContext(
                state = currentState,
                clarificationInput = "",
                clarificationText = clarificationText,
            ),
        )
    }

    fun resolveDraftAutosavePlan(
        state: SpecDetailClarificationFormState,
        clarificationInput: String,
        clarificationText: SpecDetailClarificationText,
    ): SpecDetailClarificationDraftAutosavePlan {
        return SpecDetailClarificationDraftAutosavePlan(
            input = state.input,
            confirmedContext = resolveConfirmedContext(
                state = state,
                clarificationInput = clarificationInput,
                clarificationText = clarificationText,
            ),
            questionsMarkdown = state.questionsMarkdown,
            structuredQuestions = state.structuredQuestions,
        )
    }

    private fun normalizeContent(content: String): String {
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }
}
