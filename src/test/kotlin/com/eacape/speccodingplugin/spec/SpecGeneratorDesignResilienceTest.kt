package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.llm.LlmResponse
import com.eacape.speccodingplugin.llm.LlmRouter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecGeneratorDesignResilienceTest {

    @Test
    fun `generate design should keep markdown outside mermaid code fence`() = runBlocking {
        val llmRouter = mockk<LlmRouter>()
        val generator = SpecGenerator(llmRouter)
        val responseContent = """
            # Design Document
            
            ## 架构设计
            
            ```mermaid
            erDiagram
                USER ||--o{ COMMUNITY_REPORT : reports
            ```
            
            ## 技术选型
            
            - Kotlin + Spring Boot
            
            ## 数据模型
            
            - User(id, role)
        """.trimIndent()

        coEvery {
            llmRouter.generate(providerId = any(), request = any())
        } returns LlmResponse(
            content = responseContent,
            model = "mock-model",
        )

        val result = generator.generate(
            SpecGenerationRequest(
                phase = SpecPhase.DESIGN,
                input = "design a moderation platform",
                options = GenerationOptions(
                    providerId = "claude-cli",
                    model = "mock-model",
                ),
            ),
        )

        assertTrue(result is SpecGenerationResult.Success)
        val document = (result as SpecGenerationResult.Success).document
        assertTrue(document.content.contains("## 架构设计"))
        assertTrue(document.content.contains("## 技术选型"))
        assertTrue(document.content.contains("## 数据模型"))
        assertTrue(document.content.contains("Kotlin + Spring Boot"))
    }

    @Test
    fun `generate design should auto-fill required sections when headings are missing`() = runBlocking {
        val llmRouter = mockk<LlmRouter>()
        val generator = SpecGenerator(llmRouter)
        val responseContent = "采用事件驱动架构，核心服务通过消息队列进行解耦。"

        coEvery {
            llmRouter.generate(providerId = any(), request = any())
        } returns LlmResponse(
            content = responseContent,
            model = "mock-model",
        )

        val result = generator.generate(
            SpecGenerationRequest(
                phase = SpecPhase.DESIGN,
                input = "design a moderation platform",
                options = GenerationOptions(
                    providerId = "claude-cli",
                    model = "mock-model",
                ),
            ),
        )

        assertTrue(result is SpecGenerationResult.Success)
        val document = (result as SpecGenerationResult.Success).document
        assertTrue(document.content.contains("## 架构设计"))
        assertTrue(document.content.contains("## 技术选型"))
        assertTrue(document.content.contains("## 数据模型"))
    }

    @Test
    fun `generate design should keep full content when multiple code fences exist without heading markers`() = runBlocking {
        val llmRouter = mockk<LlmRouter>()
        val generator = SpecGenerator(llmRouter)
        val responseContent = """
            ```mermaid
            erDiagram
                USER ||--o{ COMMUNITY_REPORT : reports
            ```
            
            ```text
            关键约束：审计日志至少保留 180 天
            ```
        """.trimIndent()

        coEvery {
            llmRouter.generate(providerId = any(), request = any())
        } returns LlmResponse(
            content = responseContent,
            model = "mock-model",
        )

        val result = generator.generate(
            SpecGenerationRequest(
                phase = SpecPhase.DESIGN,
                input = "design an audit-ready moderation platform",
                options = GenerationOptions(
                    providerId = "claude-cli",
                    model = "mock-model",
                ),
            ),
        )

        assertTrue(result is SpecGenerationResult.Success)
        val document = (result as SpecGenerationResult.Success).document
        assertTrue(document.content.contains("180 天"))
        assertTrue(document.content.contains("## 架构设计"))
        assertTrue(document.content.contains("## 技术选型"))
        assertTrue(document.content.contains("## 数据模型"))
    }
}
