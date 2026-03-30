package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailProcessTimelineRenderModelTest {

    @Test
    fun `replace should normalize entries and keep latest items within limit`() {
        val model = SpecDetailProcessTimelineRenderModel(maxEntries = 2).replace(
            listOf(
                SpecDetailPanel.ProcessTimelineEntry("  "),
                SpecDetailPanel.ProcessTimelineEntry(" first  ", SpecDetailPanel.ProcessTimelineState.INFO),
                SpecDetailPanel.ProcessTimelineEntry("second", SpecDetailPanel.ProcessTimelineState.DONE),
                SpecDetailPanel.ProcessTimelineEntry(" third ", SpecDetailPanel.ProcessTimelineState.FAILED),
            ),
        )

        assertEquals(
            listOf(
                SpecDetailPanel.ProcessTimelineEntry("second", SpecDetailPanel.ProcessTimelineState.DONE),
                SpecDetailPanel.ProcessTimelineEntry("third", SpecDetailPanel.ProcessTimelineState.FAILED),
            ),
            model.entries,
        )
        assertTrue(model.visible)
    }

    @Test
    fun `append should ignore blank text and skip identical consecutive entry`() {
        val model = SpecDetailProcessTimelineRenderModel(maxEntries = 3)
            .append("  Prepare context  ", SpecDetailPanel.ProcessTimelineState.INFO)
            .append("Prepare context", SpecDetailPanel.ProcessTimelineState.INFO)
            .append("", SpecDetailPanel.ProcessTimelineState.ACTIVE)
            .append("Calling model", SpecDetailPanel.ProcessTimelineState.ACTIVE)

        assertEquals(
            listOf(
                SpecDetailPanel.ProcessTimelineEntry("Prepare context", SpecDetailPanel.ProcessTimelineState.INFO),
                SpecDetailPanel.ProcessTimelineEntry("Calling model", SpecDetailPanel.ProcessTimelineState.ACTIVE),
            ),
            model.entries,
        )
    }

    @Test
    fun `render should provide markdown and plain text snapshots and clear state`() {
        val populated = SpecDetailProcessTimelineRenderModel().replace(
            listOf(
                SpecDetailPanel.ProcessTimelineEntry("Prepare clarification context", SpecDetailPanel.ProcessTimelineState.DONE),
                SpecDetailPanel.ProcessTimelineEntry("Calling model to generate content", SpecDetailPanel.ProcessTimelineState.ACTIVE),
            ),
        )

        assertEquals(
            """
            - ✓ Prepare clarification context
            - → Calling model to generate content
            """.trimIndent(),
            populated.markdown,
        )
        assertEquals(
            """
            ✓ Prepare clarification context
            → Calling model to generate content
            """.trimIndent(),
            populated.plainText,
        )

        val cleared = populated.clear()
        assertFalse(cleared.visible)
        assertEquals("", cleared.markdown)
        assertEquals("", cleared.plainText)
    }
}
