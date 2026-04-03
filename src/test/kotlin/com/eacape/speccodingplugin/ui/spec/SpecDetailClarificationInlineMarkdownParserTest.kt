package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecDetailClarificationInlineMarkdownParserTest {

    @Test
    fun `parse should extract bold segments from checklist question text`() {
        val segments = SpecDetailClarificationInlineMarkdownParser.parse(
            text = "**VIBE 你好** 和 **SPEC** 需要确认",
            collapseWhitespace = true,
        )

        assertEquals(
            listOf(
                SpecDetailClarificationInlineSegment("VIBE 你好", bold = true),
                SpecDetailClarificationInlineSegment(" 和 "),
                SpecDetailClarificationInlineSegment("SPEC", bold = true),
                SpecDetailClarificationInlineSegment(" 需要确认"),
            ),
            segments,
        )
    }

    @Test
    fun `parse should support inline code delimited by single or repeated backticks`() {
        val segments = SpecDetailClarificationInlineMarkdownParser.parse(
            text = "执行 `gradle test` 或 ``./gradlew.bat buildPlugin``",
            collapseWhitespace = true,
        )

        assertEquals(
            listOf(
                SpecDetailClarificationInlineSegment("执行 "),
                SpecDetailClarificationInlineSegment("gradle test", inlineCode = true),
                SpecDetailClarificationInlineSegment(" 或 "),
                SpecDetailClarificationInlineSegment("./gradlew.bat buildPlugin", inlineCode = true),
            ),
            segments,
        )
    }

    @Test
    fun `parse should normalize multiline whitespace before tokenizing checklist questions`() {
        val segments = SpecDetailClarificationInlineMarkdownParser.parse(
            text = "  第一行\n   **重点**   \n  ``foo`bar``   ",
            collapseWhitespace = true,
        )

        assertEquals(
            listOf(
                SpecDetailClarificationInlineSegment("第一行 "),
                SpecDetailClarificationInlineSegment("重点", bold = true),
                SpecDetailClarificationInlineSegment(" "),
                SpecDetailClarificationInlineSegment("foo`bar", inlineCode = true),
            ),
            segments,
        )
    }

    @Test
    fun `parse should merge adjacent plain segments after unmatched markdown markers`() {
        val segments = SpecDetailClarificationInlineMarkdownParser.parse(
            text = "Need *literal marker and trailing text",
            collapseWhitespace = true,
        )

        assertEquals(
            listOf(
                SpecDetailClarificationInlineSegment("Need *literal marker and trailing text"),
            ),
            segments,
        )
    }
}
