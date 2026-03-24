package com.eacape.speccodingplugin.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliDiscoveryServiceSlashCommandParseTest {

    @Test
    fun `parseClaudeSlashCommandsFromHelp should extract commands section only`() {
        val help = """
            Usage: claude [options] [command] [prompt]

            Options:
              --add-dir <directories...>                        Additional directories to allow tool access to
              --allowedTools, --allowed-tools <tools...>        Comma or space-separated list of tool names to allow
              -h, --help                                        Display help for command

            Commands:
              agents [options]                                  List configured agents
              update|upgrade                                    Check for updates and install if available
        """.trimIndent()

        val commands = CliDiscoveryService.parseClaudeSlashCommandsFromHelp(help)
        val names = commands.map { it.command }.toSet()

        assertEquals(setOf("agents", "update"), names)
        assertTrue(commands.all { it.providerId == "claude-cli" })
        assertTrue(commands.all { it.invocationKind == CliSlashInvocationKind.COMMAND })
    }

    @Test
    fun `parseCodexSlashCommandsFromHelp should extract only commands section`() {
        val help = """
            Codex CLI

            Commands:
              exec        Run Codex non-interactively [aliases: e]
              review      Run a code review non-interactively
              completion  Generate shell completion scripts

            Arguments:
              [PROMPT]    Optional user prompt to start the session
        """.trimIndent()

        val commands = CliDiscoveryService.parseCodexSlashCommandsFromHelp(help)
        val names = commands.map { it.command }.toSet()

        assertEquals(setOf("exec", "review", "completion"), names)
        assertTrue(commands.all { it.providerId == "codex-cli" })
    }
}
