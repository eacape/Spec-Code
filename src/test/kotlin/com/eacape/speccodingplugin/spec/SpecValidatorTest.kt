package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecValidatorTest {

    @Test
    fun `specify validation accepts keyword style sections without strict headings`() {
        val content = """
            我已经为你创建了完整的结构化需求文档。

            核心内容：
            - 5个功能需求：数据爬取、数据存储、多维度展示、数据查询筛选、数据对比
            - 5个非功能需求：性能、可靠性、可维护性、兼容性、数据准确性
            - 5个用户故事：涵盖管理员和用户视角，包含定时爬取和可视化查看

            验收标准：
            - 每个用户故事都有可验证检查项
        """.trimIndent()

        val document = SpecDocument(
            id = "specify-keyword-style",
            phase = SpecPhase.SPECIFY,
            content = content,
            metadata = SpecMetadata(
                title = "specify",
                description = "keyword style",
            ),
        )

        val result = SpecValidator.validate(document)
        assertTrue(result.valid, "Keyword-style sections should be accepted for SPECIFY phase")
    }

    @Test
    fun `specify validation fails when required non-functional section is missing`() {
        val content = """
            ## 功能需求
            - 用户可以创建档案

            ## 用户故事
            As a user, I want to browse archives, so that I can find data quickly.
        """.trimIndent()

        val document = SpecDocument(
            id = "specify-missing-nfr",
            phase = SpecPhase.SPECIFY,
            content = content,
            metadata = SpecMetadata(
                title = "specify",
                description = "missing section",
            ),
        )

        val result = SpecValidator.validate(document)
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("非功能需求") })
    }
    @Test
    fun `specify validation fails when requirements still use scaffold placeholders`() {
        val content = """
            # Requirements Document

            ## Functional Requirements
            - [ ] TODO: Describe required behavior.

            ## Non-Functional Requirements
            - [ ] TODO: Describe performance, security, and reliability constraints.

            ## User Stories
            As a <role>, I want <capability>, so that <benefit>.

            ## Acceptance Criteria
            - [ ] TODO: Add measurable acceptance criteria.
        """.trimIndent()

        val document = SpecDocument(
            id = "specify-scaffold",
            phase = SpecPhase.SPECIFY,
            content = content,
            metadata = SpecMetadata(
                title = "specify",
                description = "scaffold placeholder",
            ),
        )

        val result = SpecValidator.validate(document)
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("TODO placeholders") })
        assertTrue(result.errors.any { it.contains("<role>/<capability>/<benefit>") })
    }
}
