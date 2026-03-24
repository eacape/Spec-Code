package com.eacape.speccodingplugin.ui.chat

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JTextPane
import javax.swing.SwingUtilities

class MarkdownRendererHtmlBudgetTest {

    @Test
    fun `html table rendering should stay enabled for compact table content`() {
        val markdown = """
            | Name | Value |
            | --- | --- |
            | mode | auto |
            | retry | false |
        """.trimIndent()

        assertTrue(MarkdownRenderer.shouldUseHtmlTableRendering(markdown))
    }

    @Test
    fun `render should fall back to plain mode for oversized table content`() {
        val pane = JTextPane()
        val markdown = buildOversizedTableMarkdown(rows = 24)

        assertFalse(MarkdownRenderer.shouldUseHtmlTableRendering(markdown))

        runOnEdt {
            MarkdownRenderer.render(pane, markdown)
        }

        assertTrue(pane.contentType.contains("plain"))
        assertTrue(pane.text.contains("row-24"))
    }

    private fun buildOversizedTableMarkdown(rows: Int): String {
        val header = "| Column | Details |"
        val separator = "| --- | --- |"
        val body = (1..rows).joinToString("\n") { index ->
            val detail = (1..18).joinToString(" ") { part -> "detail-$index-$part" }
            "| row-$index | $detail |"
        }
        return listOf(
            "Large comparison table for layout safety regression coverage.",
            header,
            separator,
            body,
        ).joinToString("\n")
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }
}
