package com.eacape.speccodingplugin.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkflowSectionParserTest {

    @Test
    fun `parse should extract english sections and keep remaining text`() {
        val content = """
            Intro line.
            ## Plan
            - step one
            ## Execute
            - edit files
            ## Verify
            - run tests
            Tail note.
        """.trimIndent()

        val result = WorkflowSectionParser.parse(content)

        assertEquals(3, result.sections.size)
        assertEquals(WorkflowSectionParser.SectionKind.PLAN, result.sections[0].kind)
        assertTrue(result.sections[0].content.contains("step one"))
        assertEquals(WorkflowSectionParser.SectionKind.EXECUTE, result.sections[1].kind)
        assertEquals(WorkflowSectionParser.SectionKind.VERIFY, result.sections[2].kind)
        assertTrue(result.sections[2].content.contains("Tail note."))
        assertTrue(result.remainingText.contains("Intro line."))
    }

    @Test
    fun `parse should extract chinese section headings`() {
        val content = """
            ## 计划
            - 明确边界
            ## 执行
            - 修改代码
            ## 验证
            - 运行测试
        """.trimIndent()

        val result = WorkflowSectionParser.parse(content)

        assertEquals(3, result.sections.size)
        assertEquals(WorkflowSectionParser.SectionKind.PLAN, result.sections[0].kind)
        assertEquals(WorkflowSectionParser.SectionKind.EXECUTE, result.sections[1].kind)
        assertEquals(WorkflowSectionParser.SectionKind.VERIFY, result.sections[2].kind)
        assertEquals("", result.remainingText)
    }

    @Test
    fun `parse should extract chinese section headings without hash spacing`() {
        val content = """
            ##计划
            - 明确边界
            ##执行
            - 修改代码
            ##验证
            - 运行测试
        """.trimIndent()

        val result = WorkflowSectionParser.parse(content)

        assertEquals(3, result.sections.size)
        assertEquals(WorkflowSectionParser.SectionKind.PLAN, result.sections[0].kind)
        assertEquals(WorkflowSectionParser.SectionKind.EXECUTE, result.sections[1].kind)
        assertEquals(WorkflowSectionParser.SectionKind.VERIFY, result.sections[2].kind)
    }

    @Test
    fun `parse should return empty sections for plain text`() {
        val content = "Just a normal response without section headings."
        val result = WorkflowSectionParser.parse(content)

        assertTrue(result.sections.isEmpty())
        assertEquals(content, result.remainingText)
    }

    @Test
    fun `parse should extract plain plan execute verify headings without markdown prefixes`() {
        val content = """
            Plan
            - clarify requirements
            Execute
            - implement changes
            Verify
            - run tests
        """.trimIndent()

        val result = WorkflowSectionParser.parse(content)

        assertEquals(3, result.sections.size)
        assertEquals(WorkflowSectionParser.SectionKind.PLAN, result.sections[0].kind)
        assertTrue(result.sections[0].content.contains("clarify requirements"))
        assertEquals(WorkflowSectionParser.SectionKind.EXECUTE, result.sections[1].kind)
        assertEquals(WorkflowSectionParser.SectionKind.VERIFY, result.sections[2].kind)
    }
}
