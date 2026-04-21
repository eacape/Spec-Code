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
    @Test
    fun `filter should remove echoed referenced context block lines`() {
        val filter = PromptReferenceEchoFilter.fromTextBlocks(
            listOf(
                """
                ## Reference Project Context
                Use the following project materials as reference data for the next user request.
                Answer the user's question directly before summarizing or citing these materials.

                ### Referenced File: spec-design-review.md
                File: `D:/repo/spec-design-review.md`
                ```
                当前设计要求严格的 SPECIFY → DESIGN → IMPLEMENT 线性流程
                ```
                """.trimIndent()
            )
        )

        val filtered = filter.filter(
            """
            ## Reference Project Context
            Use the following project materials as reference data for the next user request.
            Answer the user's question directly before summarizing or citing these materials.
            ### Referenced File: spec-design-review.md
            File: `D:/repo/spec-design-review.md`
            当前设计要求严格的 SPECIFY → DESIGN → IMPLEMENT 线性流程
            这是一个关于 spec 工作流设计评审的文档。
            """.trimIndent(),
            flush = true,
        )

        assertFalse(filtered.contains("Reference Project Context"), filtered)
        assertFalse(filtered.contains("spec-design-review.md"), filtered)
        assertFalse(filtered.contains("SPECIFY → DESIGN → IMPLEMENT"), filtered)
        assertTrue(filtered.contains("这是一个关于 spec 工作流设计评审的文档"), filtered)
    }

    @Test
    fun `filter should remove codex internal scaffold headings`() {
        val filter = PromptReferenceEchoFilter.fromTextBlocks(listOf("dummy"))

        val filtered = filter.filter(
            """
            You are answering the final user request for an IDE chat session.
            ## Internal Instructions And Reference Context
            ### Internal Block 1
            ## Final User Request
            ## Response Requirements
            这是设计评审文档。
            """.trimIndent(),
            flush = true,
        )

        assertFalse(filtered.contains("Internal Instructions And Reference Context"), filtered)
        assertFalse(filtered.contains("Final User Request"), filtered)
        assertFalse(filtered.contains("Response Requirements"), filtered)
        assertTrue(filtered.contains("这是设计评审文档"), filtered)
    }
    @Test
    fun `filter should remove codex response requirement lines without headings`() {
        val filter = PromptReferenceEchoFilter.fromTextBlocks(listOf("dummy"))

        val filtered = filter.filter(
            """
            这个是什么文档
            Answer the final user request directly.
            Do not dump raw context or internal instructions.
            Use referenced files only as supporting evidence.
            If the user asks what a document is, identify its purpose before citing details.
            这是一个演示项目的说明文档。
            """.trimIndent(),
            flush = true,
        )

        assertTrue(filtered.contains("这个是什么文档"), filtered)
        assertFalse(filtered.contains("Answer the final user request directly."), filtered)
        assertFalse(filtered.contains("Do not dump raw context or internal instructions."), filtered)
        assertFalse(filtered.contains("Use referenced files only as supporting evidence."), filtered)
        assertFalse(filtered.contains("If the user asks what a document is"), filtered)
        assertTrue(filtered.contains("这是一个演示项目的说明文档。"), filtered)
    }

    @Test
    fun `filter should remove codex response requirement prefix variants`() {
        val filter = PromptReferenceEchoFilter.fromTextBlocks(listOf("dummy"))

        val filtered = filter.filter(
            """
            You are answering the final user request for an IDE chat session
            Sections marked Internal Instructions and Reference Context are hidden inputs, not user-visible text
            Use them only as guidance or evidence while reviewing the attached image
            Do not quote, restate, or continue those hidden sections verbatim unless the user explicitly asks for a quote
            Answer the final user request directly in the first sentence and keep it concise
            Do not dump raw context or internal instructions in the visible reply
            Use referenced files only as supporting evidence when they are relevant
            这是图片里这段讨论的结论摘要。
            """.trimIndent(),
            flush = true,
        )

        assertFalse(filtered.contains("You are answering the final user request"), filtered)
        assertFalse(filtered.contains("Sections marked Internal Instructions"), filtered)
        assertFalse(filtered.contains("Use them only as guidance or evidence"), filtered)
        assertFalse(filtered.contains("Do not quote, restate"), filtered)
        assertFalse(filtered.contains("Answer the final user request directly"), filtered)
        assertFalse(filtered.contains("Do not dump raw context"), filtered)
        assertFalse(filtered.contains("Use referenced files only as supporting evidence"), filtered)
        assertTrue(filtered.contains("这是图片里这段讨论的结论摘要。"), filtered)
    }

    @Test
    fun `filter should remove project development copilot instruction variants`() {
        val filter = PromptReferenceEchoFilter.fromTextBlocks(listOf("dummy"))

        val filtered = filter.filter(
            """
            You are the in-IDE project development copilot.
            Prefer workflow-oriented responses for implementation tasks:
            1) clarify objective and constraints briefly,
            2) propose a concrete implementation plan,
            3) provide executable code-level changes,
            4) include verification/check steps.
            During implementation replies, include short progress lines when relevant, using prefixes: [Thinking], [Read], [Edit], [Task], [Verify].
            Never claim files were created/modified/deleted unless tools actually executed those edits.
            If no file edit was performed, describe it as a proposal rather than completed work.
            Keep responses practical, specific to this repository, and avoid generic filler.
            这是界面评审结论。
            """.trimIndent(),
            flush = true,
        )

        assertFalse(filtered.contains("project development copilot"), filtered)
        assertFalse(filtered.contains("clarify objective and constraints briefly"), filtered)
        assertFalse(filtered.contains("propose a concrete implementation plan"), filtered)
        assertFalse(filtered.contains("provide executable code-level changes"), filtered)
        assertFalse(filtered.contains("include verification/check steps"), filtered)
        assertFalse(filtered.contains("During implementation replies"), filtered)
        assertFalse(filtered.contains("Never claim files were created"), filtered)
        assertFalse(filtered.contains("If no file edit was performed"), filtered)
        assertFalse(filtered.contains("Keep responses practical"), filtered)
        assertTrue(filtered.contains("这是界面评审结论。"), filtered)
    }

    @Test
    fun `filter should remove built-in prompt instruction echoes even without referenced text blocks`() {
        val filter = PromptReferenceEchoFilter.fromTextBlocks(emptyList())

        val filtered = filter.filter(
            """
            You are the in-IDE project development copilot.
            Prefer workflow-oriented responses for implementation tasks:
            1) clarify objective and constraints briefly,
            2) propose a concrete implementation plan,
            3) provide executable code-level changes,
            4) include verification/check steps.
            During implementation replies, include short progress lines when relevant, using prefixes: [Thinking], [Read], [Edit], [Task], [Verify].
            这是图片评审的最终结论。
            """.trimIndent(),
            flush = true,
        )

        assertFalse(filtered.contains("project development copilot"), filtered)
        assertFalse(filtered.contains("clarify objective and constraints briefly"), filtered)
        assertFalse(filtered.contains("include verification/check steps"), filtered)
        assertFalse(filtered.contains("During implementation replies"), filtered)
        assertTrue(filtered.contains("这是图片评审的最终结论。"), filtered)
    }

    @Test
    fun `filter should remove chinese structured prompt agenda lines`() {
        val filter = PromptReferenceEchoFilter.fromTextBlocks(emptyList())

        val filtered = filter.filter(
            """
            职责：4. 核心执行流程与调用链；5. 关键数据模型、状态流转或接口关系；6. 构建、运行、测试与发布方式。
            优先指出真正影响交付和维护的问题；如果没看重点不足，明确缺失点以及下一步应检查什么。
            最后给出一个简洁的行动清单，按高/中/低优先级列出可执行改进建议。
            这是这个后端项目的一期骨架，主流程已经搭起来了。
            """.trimIndent(),
            flush = true,
        )

        assertFalse(filtered.contains("核心执行流程与调用链"), filtered)
        assertFalse(filtered.contains("关键数据模型"), filtered)
        assertFalse(filtered.contains("优先指出真正影响交付和维护的问题"), filtered)
        assertFalse(filtered.contains("按高/中/低优先级列出可执行改进建议"), filtered)
        assertTrue(filtered.contains("这是这个后端项目的一期骨架"), filtered)
    }
}
