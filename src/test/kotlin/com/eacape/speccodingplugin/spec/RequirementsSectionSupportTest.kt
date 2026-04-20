package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequirementsSectionSupportTest {

    @Test
    fun `missing sections should keep canonical order`() {
        val markdown = """
            # Requirements Document

            ## Functional Requirements
            - Keep workflow state durable.

            ## Acceptance Criteria
            - [ ] Missing middle sections are detected.
        """.trimIndent()

        assertEquals(
            listOf(
                RequirementsSectionId.NON_FUNCTIONAL,
                RequirementsSectionId.USER_STORIES,
            ),
            RequirementsSectionSupport.missingSections(markdown),
        )
    }

    @Test
    fun `missing sections should recognize localized headings`() {
        val markdown = """
            # 需求文档

            ## 功能需求
            - 支持中文标题。

            ## 非功能需求
            - 离线可用。

            ## 用户故事
            - 作为用户，我希望快速修复缺失章节。

            ## 验收标准
            - [ ] 识别中文章节。
        """.trimIndent()

        assertTrue(RequirementsSectionSupport.hasRequiredSections(markdown))
        assertEquals(emptyList<RequirementsSectionId>(), RequirementsSectionSupport.missingSections(markdown))
    }

    @Test
    fun `numbered headings and acceptance aliases should be recognized`() {
        val markdown = """
            # 需求文档

            ## 3. 功能需求
            - 覆盖编号标题。

            ## 4. 非功能需求
            - 保持识别稳定。

            ## 5. 首版总体验收标准
            - [ ] 兼容验收标准别名。

            ## 6. 用户故事
            - 作为作者，我希望现有章节不会被误判缺失。
        """.trimIndent()

        assertEquals(RequirementsSectionId.FUNCTIONAL, RequirementsSectionId.fromHeadingTitle("3. 功能需求"))
        assertEquals(RequirementsSectionId.NON_FUNCTIONAL, RequirementsSectionId.fromHeadingTitle("（4）非功能需求"))
        assertEquals(RequirementsSectionId.ACCEPTANCE_CRITERIA, RequirementsSectionId.fromHeadingTitle("5. 首版总体验收标准"))
        assertEquals(RequirementsSectionId.USER_STORIES, RequirementsSectionId.fromHeadingTitle("六、用户故事"))
        assertEquals(RequirementsHeadingStyle.CHINESE, RequirementsSectionSupport.detectHeadingStyle(markdown))
        assertTrue(RequirementsSectionSupport.hasRequiredSections(markdown))
        assertEquals(emptyList<RequirementsSectionId>(), RequirementsSectionSupport.missingSections(markdown))
    }
}
