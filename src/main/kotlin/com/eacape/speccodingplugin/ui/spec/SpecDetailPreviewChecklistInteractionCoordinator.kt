package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow

internal enum class SpecDetailPreviewChecklistCursorKind {
    DEFAULT,
    HAND,
    WAIT,
}

internal sealed interface SpecDetailPreviewChecklistTogglePlan {
    data object Ignore : SpecDetailPreviewChecklistTogglePlan

    data class Save(
        val phase: SpecPhase,
        val updatedContent: String,
    ) : SpecDetailPreviewChecklistTogglePlan
}

internal data class SpecDetailPreviewChecklistSaveCompletionPlan(
    val updatedWorkflow: SpecWorkflow? = null,
    val refreshButtonStates: Boolean = false,
)

internal object SpecDetailPreviewChecklistInteractionCoordinator {

    fun cursorKind(
        interaction: SpecDetailPreviewChecklistInteractionPlan?,
        hoveredLineIndex: Int?,
        isEditing: Boolean,
        hasClarificationState: Boolean,
        isSaving: Boolean,
    ): SpecDetailPreviewChecklistCursorKind {
        if (isSaving) {
            return SpecDetailPreviewChecklistCursorKind.WAIT
        }
        return if (
            interaction != null &&
            !isEditing &&
            !hasClarificationState &&
            hoveredLineIndex != null
        ) {
            SpecDetailPreviewChecklistCursorKind.HAND
        } else {
            SpecDetailPreviewChecklistCursorKind.DEFAULT
        }
    }

    fun buildTogglePlan(
        interaction: SpecDetailPreviewChecklistInteractionPlan?,
        lineIndex: Int?,
        isEditing: Boolean,
        hasClarificationState: Boolean,
        isSaving: Boolean,
    ): SpecDetailPreviewChecklistTogglePlan {
        if (
            interaction == null ||
            lineIndex == null ||
            isEditing ||
            hasClarificationState ||
            isSaving
        ) {
            return SpecDetailPreviewChecklistTogglePlan.Ignore
        }
        val updatedContent = SpecDetailPreviewMarkdownCoordinator.toggleChecklistLine(
            content = interaction.content,
            lineIndex = lineIndex,
        ) ?: return SpecDetailPreviewChecklistTogglePlan.Ignore
        if (updatedContent == interaction.content) {
            return SpecDetailPreviewChecklistTogglePlan.Ignore
        }
        return SpecDetailPreviewChecklistTogglePlan.Save(
            phase = interaction.phase,
            updatedContent = updatedContent,
        )
    }

    fun buildSaveCompletionPlan(
        result: Result<SpecWorkflow>,
        hasCurrentWorkflow: Boolean,
    ): SpecDetailPreviewChecklistSaveCompletionPlan {
        return result.fold(
            onSuccess = { updated ->
                SpecDetailPreviewChecklistSaveCompletionPlan(updatedWorkflow = updated)
            },
            onFailure = {
                SpecDetailPreviewChecklistSaveCompletionPlan(
                    refreshButtonStates = hasCurrentWorkflow,
                )
            },
        )
    }
}
