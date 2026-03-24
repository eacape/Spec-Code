package com.eacape.speccodingplugin.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ClaudeCodeEngineTest {

    @Test
    fun `buildCommandArgs should not append legacy max tokens flag`() {
        val engine = ClaudeCodeEngine("claude")
        val request = EngineRequest(
            prompt = "repair requirements",
            options = mapOf("max_tokens" to "1400"),
        )

        val args = invokeBuildCommandArgs(engine, request)

        assertFalse(args.contains("--max-tokens"), "Unexpected legacy flag in args: $args")
    }

    @Test
    fun `environmentOverrides should map max tokens to claude env var`() {
        val engine = ClaudeCodeEngine("claude")
        val request = EngineRequest(
            prompt = "repair requirements",
            options = mapOf("max_tokens" to "1400"),
        )

        val overrides = invokeEnvironmentOverrides(engine, request)

        assertEquals(mapOf("CLAUDE_CODE_MAX_OUTPUT_TOKENS" to "1400"), overrides)
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildCommandArgs(engine: ClaudeCodeEngine, request: EngineRequest): List<String> {
        val method = engine::class.java.getDeclaredMethod("buildCommandArgs", EngineRequest::class.java)
        method.isAccessible = true
        return method.invoke(engine, request) as List<String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeEnvironmentOverrides(engine: ClaudeCodeEngine, request: EngineRequest): Map<String, String> {
        val method = engine::class.java.getDeclaredMethod("environmentOverrides", EngineRequest::class.java)
        method.isAccessible = true
        return method.invoke(engine, request) as Map<String, String>
    }
}
