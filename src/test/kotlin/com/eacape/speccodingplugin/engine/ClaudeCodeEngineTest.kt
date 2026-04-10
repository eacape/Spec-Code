package com.eacape.speccodingplugin.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `parseProgressLine should ignore structured init metadata from stderr`() {
        val engine = ClaudeCodeEngine("claude")

        val chunk = invokeParseProgressLine(
            engine,
            """{"type":"system","subtype":"init","cwd":"/tmp/project","tools":["mcp__demo__tool"]}""",
        )

        assertNull(chunk)
    }

    @Test
    fun `sanitizeProcessErrorLine should extract structured claude error detail`() {
        val engine = ClaudeCodeEngine("claude")

        val sanitized = invokeSanitizeProcessErrorLine(
            engine,
            """{"type":"result","subtype":"error","error":"connection failed"}""",
        )

        assertEquals("connection failed", sanitized)
    }

    @Test
    fun `sanitizeProcessErrorLine should drop structured init metadata`() {
        val engine = ClaudeCodeEngine("claude")

        val sanitized = invokeSanitizeProcessErrorLine(
            engine,
            """{"type":"system","subtype":"init","cwd":"/tmp/project","tools":["mcp__demo__tool"]}""",
        )

        assertNull(sanitized)
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

    private fun invokeParseProgressLine(engine: ClaudeCodeEngine, line: String): EngineChunk? {
        val method = engine::class.java.getDeclaredMethod("parseProgressLine", String::class.java)
        method.isAccessible = true
        return method.invoke(engine, line) as EngineChunk?
    }

    private fun invokeSanitizeProcessErrorLine(engine: ClaudeCodeEngine, line: String): String? {
        val method = engine::class.java.getDeclaredMethod("sanitizeProcessErrorLine", String::class.java)
        method.isAccessible = true
        return method.invoke(engine, line) as String?
    }
}
