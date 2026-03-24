package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SpecMarkdownAstParserTest {

    @Test
    fun `parse should extract fenced code blocks with stable line ranges`() {
        val markdown = listOf(
            "# Tasks",
            "",
            "### T-001: Build parser",
            "```spec-task",
            "status: PENDING",
            "priority: P0",
            "```",
            "",
            "```kotlin",
            "println(\"ok\")",
            "```",
        ).joinToString("\n")

        val parsed = SpecMarkdownAstParser.parse(markdown)

        assertEquals(2, parsed.codeFences.size)

        val specTaskFence = parsed.codeFences[0]
        assertEquals("spec-task", specTaskFence.language)
        assertEquals("status: PENDING\npriority: P0", specTaskFence.content.trim())
        assertEquals(4, specTaskFence.location.startLine)
        assertEquals(7, specTaskFence.location.endLine)
        assertNotNull(specTaskFence.contentLocation)
        assertEquals(5, specTaskFence.contentLocation?.startLine)
        assertEquals(6, specTaskFence.contentLocation?.endLine)

        val kotlinFence = parsed.codeFences[1]
        assertEquals("kotlin", kotlinFence.language)
        assertEquals("println(\"ok\")", kotlinFence.content.trim())
        assertEquals(9, kotlinFence.location.startLine)
        assertEquals(11, kotlinFence.location.endLine)
        assertEquals(10, kotlinFence.contentLocation?.startLine)
        assertEquals(10, kotlinFence.contentLocation?.endLine)
    }

    @Test
    fun `parse should keep line locations consistent between LF and CRLF`() {
        val lfMarkdown = listOf(
            "## Phase",
            "```spec-task",
            "status: IN_PROGRESS",
            "```",
            "tail",
        ).joinToString("\n")
        val crlfMarkdown = lfMarkdown.replace("\n", "\r\n")

        val lfParsed = SpecMarkdownAstParser.parse(lfMarkdown)
        val crlfParsed = SpecMarkdownAstParser.parse(crlfMarkdown)

        assertEquals(1, lfParsed.codeFences.size)
        assertEquals(1, crlfParsed.codeFences.size)

        val lfFence = lfParsed.codeFences.single()
        val crlfFence = crlfParsed.codeFences.single()

        assertEquals(lfFence.language, crlfFence.language)
        assertEquals(lfFence.content, crlfFence.content)
        assertEquals(lfFence.location.startLine, crlfFence.location.startLine)
        assertEquals(lfFence.location.endLine, crlfFence.location.endLine)
        assertEquals(lfFence.contentLocation?.startLine, crlfFence.contentLocation?.startLine)
        assertEquals(lfFence.contentLocation?.endLine, crlfFence.contentLocation?.endLine)
    }

    @Test
    fun `lineOfOffset should return deterministic one based line numbers`() {
        val markdown = "alpha\nbeta\ngamma"
        val parsed = SpecMarkdownAstParser.parse(markdown)

        assertEquals(1, parsed.lineOfOffset(0))
        assertEquals(2, parsed.lineOfOffset(markdown.indexOf("beta")))
        assertEquals(3, parsed.lineOfOffset(markdown.indexOf("gamma")))
        assertEquals(3, parsed.lineOfOffset(markdown.length))
    }

    @Test
    fun `parse should handle empty fenced content`() {
        val markdown = listOf(
            "```spec-task",
            "```",
        ).joinToString("\r\n")

        val parsed = SpecMarkdownAstParser.parse(markdown)
        val fence = parsed.codeFences.single()

        assertEquals("spec-task", fence.language)
        assertEquals("", fence.content)
        assertNull(fence.contentLocation)
        assertEquals(1, fence.location.startLine)
        assertEquals(2, fence.location.endLine)
    }

    @Test
    fun `parse should reuse cached parsed document for identical normalized markdown`() {
        val markdown = "## Tasks\r\n\r\n```spec-task\r\nstatus: PENDING\r\n```"

        val first = SpecMarkdownAstParser.parse(markdown)
        val second = SpecMarkdownAstParser.parse(markdown.replace("\r\n", "\n"))

        assertSame(first, second)
    }
}
