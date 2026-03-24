package com.eacape.speccodingplugin.mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class McpSecurityValidatorTest {

    @Test
    fun `should pass validation for trusted server with safe command`() {
        val config = McpServerConfig(
            id = "test", name = "Test",
            command = "npx", args = listOf("-y", "server"),
            trusted = true
        )
        val result = McpSecurityValidator.validateBeforeStart(config)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `should fail validation for untrusted server`() {
        val config = McpServerConfig(
            id = "test", name = "Test",
            command = "npx", trusted = false
        )
        val result = McpSecurityValidator.validateBeforeStart(config)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun `should fail validation for empty command`() {
        val result = McpSecurityValidator.validateCommandPath("")
        assertTrue(result.isFailure)
    }

    @Test
    fun `should fail validation for command with shell metacharacters`() {
        val result = McpSecurityValidator.validateCommandPath("cmd\r\nmalicious")
        assertTrue(result.isFailure)
    }

    @Test
    fun `should pass validation for known safe commands`() {
        val safeCommands = listOf(
            "npx", "node", "python", "python3",
            "uvx", "uv", "docker", "deno", "bun", "cargo"
        )
        for (cmd in safeCommands) {
            val result = McpSecurityValidator.validateCommandPath(cmd)
            assertTrue(result.isSuccess, "Expected $cmd to pass")
        }
    }

    @Test
    fun `should fail for relative non-safe command`() {
        val result = McpSecurityValidator.validateCommandPath(
            "my-custom-server"
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `should report trusted correctly`() {
        val trusted = McpServerConfig(
            id = "t", name = "T",
            command = "npx", trusted = true
        )
        val untrusted = McpServerConfig(
            id = "u", name = "U",
            command = "npx", trusted = false
        )
        assertTrue(McpSecurityValidator.isTrusted(trusted))
        assertFalse(McpSecurityValidator.isTrusted(untrusted))
    }

    @Test
    fun `should fail for args with shell metacharacters`() {
        val config = McpServerConfig(
            id = "test", name = "Test",
            command = "npx",
            args = listOf("--flag", "value\r\nnext"),
            trusted = true
        )
        val result = McpSecurityValidator.validateBeforeStart(config)
        assertTrue(result.isFailure)
    }

    @Test
    fun `should allow safe args with urls and json fragments`() {
        val config = McpServerConfig(
            id = "test",
            name = "Test",
            command = "npx",
            args = listOf(
                "--endpoint",
                "https://example.com/sse?token=a&mode=dev",
                """{"path":"C:/Program Files (x86)/demo"}""",
            ),
            trusted = true,
        )
        val result = McpSecurityValidator.validateBeforeStart(config)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `should fail validation for unsupported built in transport`() {
        val config = McpServerConfig(
            id = "test",
            name = "Test",
            command = "https://example.com/sse",
            transport = TransportType.SSE,
            trusted = true,
        )
        val result = McpSecurityValidator.validateBeforeStart(config)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }
}
