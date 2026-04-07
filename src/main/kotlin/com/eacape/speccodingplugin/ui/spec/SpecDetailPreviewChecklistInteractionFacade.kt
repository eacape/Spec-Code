package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.ui.chat.MarkdownRenderer
import java.awt.event.MouseEvent
import javax.swing.JTextPane
import javax.swing.text.AttributeSet

internal data class SpecDetailPreviewChecklistHitTest(
    val documentLength: Int,
    val documentPosition: Int? = null,
    val paragraphAttributes: AttributeSet? = null,
)

internal object SpecDetailPreviewChecklistInteractionFacade {

    fun cursorKind(
        pane: JTextPane,
        event: MouseEvent?,
        interaction: SpecDetailPreviewChecklistInteractionPlan?,
        isEditing: Boolean,
        hasClarificationState: Boolean,
        isSaving: Boolean,
    ): SpecDetailPreviewChecklistCursorKind {
        return cursorKind(
            hitTest = buildHitTest(pane, event),
            interaction = interaction,
            isEditing = isEditing,
            hasClarificationState = hasClarificationState,
            isSaving = isSaving,
        )
    }

    fun cursorKind(
        hitTest: SpecDetailPreviewChecklistHitTest,
        interaction: SpecDetailPreviewChecklistInteractionPlan?,
        isEditing: Boolean,
        hasClarificationState: Boolean,
        isSaving: Boolean,
    ): SpecDetailPreviewChecklistCursorKind {
        return SpecDetailPreviewChecklistInteractionCoordinator.cursorKind(
            interaction = interaction,
            hoveredLineIndex = resolveLineIndex(hitTest),
            isEditing = isEditing,
            hasClarificationState = hasClarificationState,
            isSaving = isSaving,
        )
    }

    fun buildTogglePlan(
        pane: JTextPane,
        event: MouseEvent,
        interaction: SpecDetailPreviewChecklistInteractionPlan?,
        isEditing: Boolean,
        hasClarificationState: Boolean,
        isSaving: Boolean,
    ): SpecDetailPreviewChecklistTogglePlan {
        return buildTogglePlan(
            hitTest = buildHitTest(pane, event),
            interaction = interaction,
            isEditing = isEditing,
            hasClarificationState = hasClarificationState,
            isSaving = isSaving,
        )
    }

    fun buildTogglePlan(
        hitTest: SpecDetailPreviewChecklistHitTest,
        interaction: SpecDetailPreviewChecklistInteractionPlan?,
        isEditing: Boolean,
        hasClarificationState: Boolean,
        isSaving: Boolean,
    ): SpecDetailPreviewChecklistTogglePlan {
        return SpecDetailPreviewChecklistInteractionCoordinator.buildTogglePlan(
            interaction = interaction,
            lineIndex = resolveLineIndex(hitTest),
            isEditing = isEditing,
            hasClarificationState = hasClarificationState,
            isSaving = isSaving,
        )
    }

    fun resolveLineIndex(hitTest: SpecDetailPreviewChecklistHitTest): Int? {
        val position = hitTest.documentPosition ?: return null
        if (hitTest.documentLength <= 0 || position !in 0 until hitTest.documentLength) {
            return null
        }
        return MarkdownRenderer.extractChecklistLineIndex(hitTest.paragraphAttributes)
    }

    private fun buildHitTest(
        pane: JTextPane,
        event: MouseEvent?,
    ): SpecDetailPreviewChecklistHitTest {
        val documentLength = pane.document.length
        if (documentLength <= 0 || event == null) {
            return SpecDetailPreviewChecklistHitTest(documentLength = documentLength)
        }
        val position = pane.viewToModel2D(event.point)
        if (position < 0) {
            return SpecDetailPreviewChecklistHitTest(documentLength = documentLength)
        }
        val safePosition = position.coerceIn(0, documentLength - 1)
        val paragraphAttributes = pane.styledDocument.getParagraphElement(safePosition).attributes
        return SpecDetailPreviewChecklistHitTest(
            documentLength = documentLength,
            documentPosition = safePosition,
            paragraphAttributes = paragraphAttributes,
        )
    }
}
