package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import javax.swing.JTextPane
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SpecDetailPreviewMarkdownRendererAdapterTest {

    @Test
    fun `render should sync interaction invoke markdown renderer and reset caret`() {
        val pane = JTextPane().apply {
            text = "stale"
            caretPosition = text.length
        }
        var updatedInteraction: SpecDetailPreviewChecklistInteractionPlan? = null
        var cursorRefreshes = 0
        var renderedContent: String? = null
        val expectedInteraction = SpecDetailPreviewChecklistInteractionPlan(
            phase = SpecPhase.IMPLEMENT,
            content = "- [ ] Ship fix",
        )
        val adapter = SpecDetailPreviewMarkdownRendererAdapter(
            pane = pane,
            updateInteraction = { updatedInteraction = it },
            refreshCursor = { cursorRefreshes += 1 },
            renderMarkdown = { target, content ->
                renderedContent = content
                target.text = "rendered:$content"
                target.caretPosition = target.document.length
            },
        )
        val plan = SpecDetailPreviewMarkdownPlan(
            displayContent = "# Tasks",
            checklistInteraction = expectedInteraction,
        )

        val renderedText = adapter.render(plan)

        assertEquals("# Tasks", renderedText)
        assertSame(expectedInteraction, updatedInteraction)
        assertEquals("# Tasks", renderedContent)
        assertEquals("rendered:# Tasks", pane.text)
        assertEquals(0, pane.caretPosition)
        assertEquals(2, cursorRefreshes)
    }

    @Test
    fun `render should fall back to plain text when markdown renderer fails`() {
        val pane = JTextPane().apply {
            text = "stale"
            caretPosition = text.length
        }
        var updatedInteraction: SpecDetailPreviewChecklistInteractionPlan? = null
        var cursorRefreshes = 0
        val adapter = SpecDetailPreviewMarkdownRendererAdapter(
            pane = pane,
            updateInteraction = { updatedInteraction = it },
            refreshCursor = { cursorRefreshes += 1 },
            renderMarkdown = { _, _ -> error("boom") },
        )
        val plan = SpecDetailPreviewMarkdownPlan(
            displayContent = "plain fallback",
            checklistInteraction = null,
        )

        val renderedText = adapter.render(plan)

        assertEquals("plain fallback", renderedText)
        assertNull(updatedInteraction)
        assertEquals("plain fallback", pane.text)
        assertEquals(0, pane.caretPosition)
        assertEquals(2, cursorRefreshes)
    }
}
