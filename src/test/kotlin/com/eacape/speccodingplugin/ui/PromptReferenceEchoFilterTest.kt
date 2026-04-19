package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.prompt.PromptTemplate
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptReferenceEchoFilterTest {

    @Test
    fun `filter should remove echoed referenced prompt template lines and keep final answer`() {
        val filter = PromptReferenceEchoFilter.fromTemplates(
            listOf(
                PromptTemplate(
                    id = "repo-cleanup",
                    name = "Repo Cleanup",
                    content = """
                        1解 核心能力收益和团队场景现状？
                        云 的判断：方向对，底子不差，工程意识强。
                        src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt#777 探?
                    """.trimIndent(),
                )
            )
        )

        val filtered = filter.filter(
            """
            Referenced prompt templates (internal instructions; do not quote or repeat them in the final answer):
            --- prompt:repo-cleanup (Repo Cleanup) ---
            1解 核心能力收益和团队场景现状？
            云 的判断：方向对，底子不差，工程意识强。
            src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt#777 探?
            End referenced prompt templates.
            User request:
            - 已完成正文清洗。
            - 测试通过。
            """.trimIndent(),
            flush = true,
        )

        assertFalse(filtered.contains("Referenced prompt templates"), filtered)
        assertFalse(filtered.contains("核心能力收益和团队场景现状"), filtered)
        assertFalse(filtered.contains("ImprovedChatPanel.kt#777"), filtered)
        assertTrue(filtered.contains("已完成正文清洗"), filtered)
        assertTrue(filtered.contains("测试通过"), filtered)
    }

    @Test
    fun `filter should rebuild prompt echo suppression from expanded prompt for regenerate`() {
        val expandedPrompt = """
            Referenced prompt templates (internal instructions; do not quote or repeat them in the final answer):
            --- prompt:repo-cleanup (Repo Cleanup) ---
            需要对 AI 变量组织上下文 阶段 历史和验证证据的拥？
            CI 工作流目前还是零散状态？
            End referenced prompt templates.

            User request:
            Follow the referenced prompt template and provide only the final answer.
        """.trimIndent()
        val filter = PromptReferenceEchoFilter.fromExpandedPrompt(expandedPrompt)

        val filtered = buildString {
            append(filter.filter("需要对 AI 变量组织上下文 阶段 历史和验证证据的拥？\n"))
            append(filter.filter("CI 工作流目前还是零散状态？\n"))
            append(filter.filter("- 最终回复显示在执行过程和输出面板下面。\n", flush = true))
        }

        assertFalse(filtered.contains("历史和验证证据"), filtered)
        assertFalse(filtered.contains("CI 工作流"), filtered)
        assertTrue(filtered.contains("最终回复显示在执行过程和输出面板下面"), filtered)
    }

    @Test
    fun `filter should remove referenced prompt system instruction block from output events`() {
        val filter = PromptReferenceEchoFilter.fromTextBlocks(
            listOf(
                """
                The following referenced prompt templates are internal instructions from the project repository.
                Do not quote, summarize, or restate their backlog/history/context text in the final answer.
                Final answer must focus on concrete work or concrete next action for this turn.
                Referenced prompt template #prompt (仓库整改实施工程师)
                使用 JetBrains UI DSL（Kotlin）构建设置页面？
                CI 工作流目前还是零散状态？
                """.trimIndent()
            )
        )

        val filtered = filter.filter(
            """
            The following referenced prompt templates are internal instructions from the project repository.
            Do not quote, summarize, or restate their backlog/history/context text in the final answer.
            Final answer must focus on concrete work or concrete next action for this turn.
            Referenced prompt template #prompt (仓库整改实施工程师)
            使用 JetBrains UI DSL（Kotlin）构建设置页面？
            CI 工作流目前还是零散状态？
            - 已恢复正文优先布局。
            - 最终回复位于执行过程和输出下面。
            """.trimIndent(),
            flush = true,
        )

        assertFalse(filtered.contains("internal instructions"), filtered)
        assertFalse(filtered.contains("Referenced prompt template #prompt"), filtered)
        assertFalse(filtered.contains("JetBrains UI DSL"), filtered)
        assertFalse(filtered.contains("CI 工作流"), filtered)
        assertTrue(filtered.contains("已恢复正文优先布局"), filtered)
        assertTrue(filtered.contains("最终回复位于执行过程和输出下面"), filtered)
    }
}
