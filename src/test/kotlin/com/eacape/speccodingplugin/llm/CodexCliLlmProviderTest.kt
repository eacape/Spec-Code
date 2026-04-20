package com.eacape.speccodingplugin.llm

import com.eacape.speccodingplugin.engine.CliDiscoveryService
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexCliLlmProviderTest {

    @Test
    fun `render prompt should prioritize final user request over internal blocks`() {
        val provider = CodexCliLlmProvider(mockk<CliDiscoveryService>(relaxed = true))

        val prompt = provider.renderPrompt(
            listOf(
                LlmMessage(
                    LlmRole.SYSTEM,
                    "## Reference Project Context\n当前设计要求严格的 SPECIFY → DESIGN → IMPLEMENT 线性流程",
                ),
                LlmMessage(
                    LlmRole.USER,
                    "这个是什么文档\n\n引用上下文项：\n- spec-design-review.md",
                ),
            )
        )

        assertTrue(prompt.contains("## Internal Instructions And Reference Context"), prompt)
        assertTrue(prompt.contains("## Final User Request"), prompt)
        assertTrue(prompt.contains("这个是什么文档"), prompt)
        assertTrue(
            prompt.contains("If the user asks what a document is, identify its purpose before citing details."),
            prompt,
        )
        assertFalse(prompt.contains("[System]"), prompt)
    }
}
