package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.ui.chat.MarkdownRenderer
import javax.swing.JTextPane

internal class SpecDetailPreviewMarkdownRendererAdapter(
    private val pane: JTextPane,
    private val updateInteraction: (SpecDetailPreviewChecklistInteractionPlan?) -> Unit,
    private val refreshCursor: () -> Unit,
    private val renderMarkdown: (JTextPane, String) -> Unit = MarkdownRenderer::render,
) {

    fun render(plan: SpecDetailPreviewMarkdownPlan): String {
        updateInteraction(plan.checklistInteraction)
        refreshCursor()
        runCatching {
            renderMarkdown(pane, plan.displayContent)
            pane.caretPosition = 0
        }.onFailure {
            pane.text = plan.displayContent
            pane.caretPosition = 0
        }
        refreshCursor()
        return plan.displayContent
    }
}
