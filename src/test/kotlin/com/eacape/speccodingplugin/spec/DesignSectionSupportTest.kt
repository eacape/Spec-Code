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

    @Test
    fun `shared metadata should drive design guidance and template body`() {
        assertEquals(
            listOf(
                "在“架构设计”中说明核心模块、职责与关键数据流。",
                "在“技术选型”中给出技术方案与取舍理由。",
                "在“数据模型”中描述核心实体、字段关系与约束。",
                "在“接口设计”中说明关键接口、输入输出以及与现有工作流/服务的调用边界。",
                "在“非功能设计”中覆盖性能、安全、可靠性、可观测性与回滚约束。",
            ),
            DesignSectionSupport.generationRequirementLines(),
        )
        assertEquals(
            "architecture decisions, technology choices, data model changes, API contracts, and non-functional constraints",
            DesignSectionSupport.reviseFocusSummary(),
        )
        assertEquals(
            listOf(
                "TODO: Describe the architecture and module boundaries.",
                "TODO: List selected technologies and rationale.",
                "TODO: Describe key entities and relationships.",
                "TODO: Describe interfaces and contract changes.",
                "TODO: Capture performance, security, and operability choices.",
            ),
            DesignSectionSupport.draftPlaceholderLines(),
        )

        val template = DesignSectionSupport.canonicalTemplateMarkdown()
        val draft = DesignSectionSupport.artifactDraftMarkdown()
        assertTrue(template.contains("采用三层架构："))
        assertTrue(template.contains("### POST /api/auth/login"))
        assertTrue(template.contains("- 安全：敏感配置和执行上下文需要脱敏、审计并限制权限边界"))
        assertTrue(draft.contains("## Architecture Design"))
        assertTrue(draft.contains("## Non-Functional Design"))
        assertTrue(draft.contains("TODO: Describe interfaces and contract changes."))
    }
}
