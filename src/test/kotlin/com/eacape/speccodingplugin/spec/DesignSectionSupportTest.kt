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

            ## API Design
            - Stage advance refreshes summary and document preview together.

            ## 非功能需求
            - 离线可用，避免阻塞 EDT。
        """.trimIndent()

        assertTrue(DesignSectionSupport.hasRequiredSections(markdown))
        assertEquals(emptyList<DesignSectionId>(), DesignSectionSupport.missingSections(markdown))
    }
}
