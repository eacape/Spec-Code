package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.llm.LlmChunk
import com.eacape.speccodingplugin.llm.LlmResponse
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coVerify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

class SpecGeneratorClarificationTest {

    private val generator = SpecGenerator(mockk<LlmRouter>(relaxed = true))

    @Test
    fun `extractClarificationQuestions should parse markdown question section`() {
        val raw = """
            ## 需要确认的问题
            1. 目标用户是否只包含管理员？
            2. 排行榜是否允许手动修正？
            - 是否需要审计日志落库？
            
            ## 已有信息摘要
            - 当前系统已有 Redis。
        """.trimIndent()

        val questions = generator.extractClarificationQuestions(raw, maxQuestions = 5)

        assertEquals(
            listOf(
                "目标用户是否只包含管理员？",
                "排行榜是否允许手动修正？",
                "是否需要审计日志落库？",
            ),
            questions,
        )
    }

    @Test
    fun `extractClarificationQuestions should fallback to free-form question lines`() {
        val raw = """
            请先确认以下事项：
            是否需要支持离线模式？
            是否有固定 SLA？
            这个阶段先不涉及多租户。
        """.trimIndent()

        val questions = generator.extractClarificationQuestions(raw, maxQuestions = 5)

        assertEquals(
            listOf(
                "是否需要支持离线模式？",
                "是否有固定 SLA？",
            ),
            questions,
        )
    }

    @Test
    fun `extractClarificationQuestions should respect maxQuestions budget`() {
        val raw = """
            ## Clarification Questions
            1. Q1?
            2. Q2?
            3. Q3?
            4. Q4?
        """.trimIndent()

        val questions = generator.extractClarificationQuestions(raw, maxQuestions = 2)

        assertEquals(listOf("Q1?", "Q2?"), questions)
    }

    @Test
    fun `extractClarificationQuestions should parse claude style numbering and bullets`() {
        val raw = """
            ### Clarification Questions
            1）What user roles are in the first release
            2）Should we support SSO login
            • What is the data retention period
            
            ## Existing Context Summary
            - Deployed on Kubernetes.
        """.trimIndent()

        val questions = generator.extractClarificationQuestions(raw, maxQuestions = 5)

        assertEquals(
            listOf(
                "What user roles are in the first release",
                "Should we support SSO login",
                "What is the data retention period",
            ),
            questions,
        )
    }

    @Test
    fun `extractClarificationQuestions should parse loose section headers and question like lines`() {
        val raw = """
            需要确认的问题：
            （1）是否需要支持离线同步
            （2）日志保留周期需要多久
            是否要求多租户隔离
            
            已有信息摘要：
            - 当前数据库是 PostgreSQL。
        """.trimIndent()

        val questions = generator.extractClarificationQuestions(raw, maxQuestions = 5)

        assertEquals(
            listOf(
                "是否需要支持离线同步",
                "日志保留周期需要多久",
                "是否要求多租户隔离",
            ),
            questions,
        )
    }

    @Test
    fun `draftClarification should fallback to stream when non-stream response is blank`() = runBlocking {
        val llmRouter = mockk<LlmRouter>()
        val generator = SpecGenerator(llmRouter)
        val request = SpecGenerationRequest(
            phase = SpecPhase.DESIGN,
            input = "design a todo app",
            options = GenerationOptions(
                providerId = "claude-cli",
                model = "claude-haiku-4-5",
            ),
        )
        val raw = """
            ## 需要确认的问题
            1. 是否需要支持多租户？
        """.trimIndent()

        coEvery {
            llmRouter.generate(providerId = any(), request = any())
        } returns LlmResponse(
            content = "",
            model = "claude-haiku-4-5",
        )
        coEvery {
            llmRouter.stream(providerId = any(), request = any(), onChunk = any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val onChunk = invocation.args[2] as suspend (LlmChunk) -> Unit
            onChunk(LlmChunk(delta = raw))
            LlmResponse(content = "", model = "claude-haiku-4-5")
        }

        val draft = generator.draftClarification(request).getOrThrow()

        assertEquals(listOf("是否需要支持多租户？"), draft.questions)
        coVerify(exactly = 1) { llmRouter.generate(providerId = any(), request = any()) }
        coVerify(exactly = 1) { llmRouter.stream(providerId = any(), request = any(), onChunk = any()) }
    }

    @Test
    fun `draftClarification should fallback to stream when non-stream request fails`() = runBlocking {
        val llmRouter = mockk<LlmRouter>()
        val generator = SpecGenerator(llmRouter)
        val request = SpecGenerationRequest(
            phase = SpecPhase.SPECIFY,
            input = "build a ranking system",
            options = GenerationOptions(
                providerId = "claude-cli",
                model = "claude-haiku-4-5",
            ),
        )
        val raw = """
            ## Clarification Questions
            1. What are the ranking tie-break rules?
        """.trimIndent()

        coEvery {
            llmRouter.generate(providerId = any(), request = any())
        } throws IllegalStateException("non-stream failed")
        coEvery {
            llmRouter.stream(providerId = any(), request = any(), onChunk = any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val onChunk = invocation.args[2] as suspend (LlmChunk) -> Unit
            onChunk(LlmChunk(delta = raw))
            LlmResponse(content = "", model = "claude-haiku-4-5")
        }

        val draft = generator.draftClarification(request).getOrThrow()

        assertEquals(listOf("What are the ranking tie-break rules?"), draft.questions)
        coVerify(exactly = 1) { llmRouter.generate(providerId = any(), request = any()) }
        coVerify(exactly = 1) { llmRouter.stream(providerId = any(), request = any(), onChunk = any()) }
    }
}
