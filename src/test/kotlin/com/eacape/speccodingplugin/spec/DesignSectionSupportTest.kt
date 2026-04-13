package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesignSectionSupportTest {

    @Test
    fun `missing sections should keep canonical order`() {
        val markdown = """
            # Design Document

            ## Architecture Design
            - Keep stage transitions auditable.

            ## Technology Stack
            - Kotlin UI plugin.
        """.trimIndent()

        assertEquals(
            listOf(
                DesignSectionId.DATA_MODEL,
                DesignSectionId.API_DESIGN,
                DesignSectionId.NON_FUNCTIONAL,
            ),
            DesignSectionSupport.missingSections(markdown),
        )
    }

    @Test
    fun `required sections should accept legacy and localized aliases`() {
        val markdown = """
            # 设计文档

            ## 架构设计
            - 保持工作流状态和界面状态同步。

            ## Technology Stack
            - Keep the legacy title accepted while rules are aligned.

            ## 数据模型
            - Stage state and document binding stay consistent.

            ## 接口设计
            - Stage advance refreshes summary and document preview together.

            ## 非功能设计
            - 离线可用，避免阻塞 EDT。
        """.trimIndent()

        assertTrue(DesignSectionSupport.hasRequiredSections(markdown))
        assertEquals(emptyList<DesignSectionId>(), DesignSectionSupport.missingSections(markdown))
    }

    @Test
    fun `heading summaries should stay aligned with canonical order`() {
        assertEquals(
            listOf("## 架构设计", "## 技术选型", "## 数据模型", "## 接口设计", "## 非功能设计"),
            DesignSectionSupport.canonicalMarkdownHeadings(),
        )
        assertEquals(
            "Architecture Design, Technology Choices, Data Model, API Design, and Non-Functional Design",
            DesignSectionSupport.englishHeadingSummary(),
        )
    }
}
