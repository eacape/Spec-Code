package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import java.awt.Cursor
import java.awt.event.MouseEvent
import javax.swing.JTextPane

internal class SpecDetailPreviewChecklistInteractionController(
    private val pane: JTextPane,
    private val isEditing: () -> Boolean,
    private val hasClarificationState: () -> Boolean,
    private val currentWorkflow: () -> SpecWorkflow?,
    private val onSaveDocument: (SpecPhase, String, (Result<SpecWorkflow>) -> Unit) -> Unit,
    private val onWorkflowUpdated: (SpecWorkflow) -> Unit,
    private val onRefreshButtonStates: (SpecWorkflow) -> Unit,
) {
    private var interaction: SpecDetailPreviewChecklistInteractionPlan? = null
    private var isSaving: Boolean = false

    fun updateInteraction(interaction: SpecDetailPreviewChecklistInteractionPlan?) {
        this.interaction = interaction
    }

    fun reset() {
        interaction = null
        isSaving = false
        refreshCursor(null)
    }

    fun handleToggleRequested(event: MouseEvent) {
        applyTogglePlan(
            SpecDetailPreviewChecklistInteractionFacade.buildTogglePlan(
                pane = pane,
                event = event,
                interaction = interaction,
                isEditing = isEditing(),
                hasClarificationState = hasClarificationState(),
                isSaving = isSaving,
            ),
        )
    }

    fun toggleLine(lineIndex: Int?) {
        applyTogglePlan(
            SpecDetailPreviewChecklistInteractionCoordinator.buildTogglePlan(
                interaction = interaction,
                lineIndex = lineIndex,
                isEditing = isEditing(),
                hasClarificationState = hasClarificationState(),
                isSaving = isSaving,
            ),
        )
    }

    fun refreshCursor(event: MouseEvent?) {
        val cursor = when (
            SpecDetailPreviewChecklistInteractionFacade.cursorKind(
                pane = pane,
                event = event,
                interaction = interaction,
                isEditing = isEditing(),
                hasClarificationState = hasClarificationState(),
                isSaving = isSaving,
            )
        ) {
            SpecDetailPreviewChecklistCursorKind.WAIT -> Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
            SpecDetailPreviewChecklistCursorKind.HAND -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            SpecDetailPreviewChecklistCursorKind.DEFAULT -> Cursor.getDefaultCursor()
        }
        if (pane.cursor != cursor) {
            pane.cursor = cursor
        }
    }

    private fun applyTogglePlan(plan: SpecDetailPreviewChecklistTogglePlan) {
        if (plan !is SpecDetailPreviewChecklistTogglePlan.Save) return

        isSaving = true
        refreshCursor(null)
        onSaveDocument(plan.phase, plan.updatedContent) { result ->
            isSaving = false
            applySaveCompletion(
                SpecDetailPreviewChecklistInteractionCoordinator.buildSaveCompletionPlan(
                    result = result,
                    hasCurrentWorkflow = currentWorkflow() != null,
                ),
            )
            refreshCursor(null)
        }
    }

    private fun applySaveCompletion(plan: SpecDetailPreviewChecklistSaveCompletionPlan) {
        plan.updatedWorkflow?.let { updated ->
            onWorkflowUpdated(updated)
            return
        }
        if (plan.refreshButtonStates) {
            currentWorkflow()?.let(onRefreshButtonStates)
        }
    }
}
