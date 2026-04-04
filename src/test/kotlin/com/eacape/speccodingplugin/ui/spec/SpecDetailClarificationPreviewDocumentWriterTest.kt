package com.eacape.speccodingplugin.ui.spec

import java.awt.Color
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.StyleConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailClarificationPreviewDocumentWriterTest {

    private val palette = SpecDetailClarificationPreviewDocumentPalette(
        bodyForeground = Color(20, 30, 40),
        titleForeground = Color(30, 40, 50),
        mutedForeground = Color(40, 50, 60),
        questionCodeBackground = Color(50, 60, 70),
        questionCodeForeground = Color(60, 70, 80),
        detailChipBackground = Color(70, 80, 90),
        detailChipForeground = Color(80, 90, 100),
        baseFontFamily = "Dialog",
        baseFontSize = 13,
    )

    @Test
    fun `write should replace document content and apply preview styles`() {
        val doc = DefaultStyledDocument().apply {
            insertString(0, "stale content", null)
        }
        val plan = SpecDetailClarificationPreviewRenderPlan(
            operations = listOf(
                SpecDetailClarificationPreviewRenderOperation.Text(
                    text = "Confirmed Clarification Points",
                    style = SpecDetailClarificationPreviewRenderTextStyle.TITLE,
                ),
                SpecDetailClarificationPreviewRenderOperation.Newline,
                SpecDetailClarificationPreviewRenderOperation.Text(
                    text = "\u2022 ",
                    style = SpecDetailClarificationPreviewRenderTextStyle.BODY,
                ),
                SpecDetailClarificationPreviewRenderOperation.Text(
                    text = "Need ",
                    style = SpecDetailClarificationPreviewRenderTextStyle.BODY,
                ),
                SpecDetailClarificationPreviewRenderOperation.Text(
                    text = "SPEC",
                    style = SpecDetailClarificationPreviewRenderTextStyle.QUESTION_BOLD,
                ),
                SpecDetailClarificationPreviewRenderOperation.Text(
                    text = " with ",
                    style = SpecDetailClarificationPreviewRenderTextStyle.BODY,
                ),
                SpecDetailClarificationPreviewRenderOperation.Text(
                    text = "gradle test",
                    style = SpecDetailClarificationPreviewRenderTextStyle.QUESTION_CODE,
                ),
                SpecDetailClarificationPreviewRenderOperation.Text(
                    text = "  ",
                    style = SpecDetailClarificationPreviewRenderTextStyle.BODY,
                ),
                SpecDetailClarificationPreviewRenderOperation.Text(
                    text = " detail: Keep local cache ",
                    style = SpecDetailClarificationPreviewRenderTextStyle.DETAIL_CHIP,
                ),
                SpecDetailClarificationPreviewRenderOperation.Newline,
                SpecDetailClarificationPreviewRenderOperation.Text(
                    text = "(empty)",
                    style = SpecDetailClarificationPreviewRenderTextStyle.MUTED,
                ),
            ),
            fallbackMarkdown = "fallback",
        )

        SpecDetailClarificationPreviewDocumentWriter.write(doc, plan, palette)

        val content = doc.getText(0, doc.length)
        assertEquals(
            "Confirmed Clarification Points\n" +
                "\u2022 Need SPEC with gradle test   detail: Keep local cache \n" +
                "(empty)",
            content,
        )
        assertSegmentStyle(
            doc = doc,
            content = content,
            needle = "Confirmed Clarification Points",
            foreground = palette.titleForeground,
            bold = true,
            fontFamily = palette.baseFontFamily,
        )
        assertSegmentStyle(
            doc = doc,
            content = content,
            needle = "SPEC",
            foreground = palette.bodyForeground,
            bold = true,
            fontFamily = palette.baseFontFamily,
        )
        assertSegmentStyle(
            doc = doc,
            content = content,
            needle = "gradle test",
            foreground = palette.questionCodeForeground,
            background = palette.questionCodeBackground,
            fontFamily = palette.codeFontFamily,
        )
        assertSegmentStyle(
            doc = doc,
            content = content,
            needle = " detail: Keep local cache ",
            foreground = palette.detailChipForeground,
            background = palette.detailChipBackground,
            bold = true,
            fontFamily = palette.baseFontFamily,
        )
        assertSegmentStyle(
            doc = doc,
            content = content,
            needle = "(empty)",
            foreground = palette.mutedForeground,
            fontFamily = palette.baseFontFamily,
        )
    }

    @Test
    fun `write should skip empty text operations without dropping newline sequencing`() {
        val doc = DefaultStyledDocument()
        val plan = SpecDetailClarificationPreviewRenderPlan(
            operations = listOf(
                SpecDetailClarificationPreviewRenderOperation.Text(
                    text = "",
                    style = SpecDetailClarificationPreviewRenderTextStyle.BODY,
                ),
                SpecDetailClarificationPreviewRenderOperation.Newline,
                SpecDetailClarificationPreviewRenderOperation.Text(
                    text = "Muted",
                    style = SpecDetailClarificationPreviewRenderTextStyle.MUTED,
                ),
            ),
            fallbackMarkdown = "fallback",
        )

        SpecDetailClarificationPreviewDocumentWriter.write(doc, plan, palette)

        val content = doc.getText(0, doc.length)
        assertEquals("\nMuted", content)
        assertSegmentStyle(
            doc = doc,
            content = content,
            needle = "Muted",
            foreground = palette.mutedForeground,
            fontFamily = palette.baseFontFamily,
        )
    }

    private fun assertSegmentStyle(
        doc: DefaultStyledDocument,
        content: String,
        needle: String,
        foreground: Color,
        background: Color? = null,
        bold: Boolean = false,
        fontFamily: String,
    ) {
        val offset = content.indexOf(needle)
        assertTrue(offset >= 0, "expected to find <$needle> in <$content>")
        val attrs = doc.getCharacterElement(offset).attributes
        assertEquals(foreground, StyleConstants.getForeground(attrs))
        assertEquals(fontFamily, StyleConstants.getFontFamily(attrs))
        assertEquals(bold, StyleConstants.isBold(attrs))
        background?.let { assertEquals(it, StyleConstants.getBackground(attrs)) }
    }
}
