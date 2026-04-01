package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.engine.CliSlashCommandInfo
import com.eacape.speccodingplugin.engine.CliSlashInvocationKind
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ImprovedChatPanelProviderSlashCommandCoordinatorTest {

    @Test
    fun `buildExecutionPlan should render claude option slash command with quoted executable path`() {
        val plan = ImprovedChatPanelProviderSlashCommandCoordinator.buildExecutionPlan(
            slashCommand = "/review latest changes",
            providerId = ClaudeCliLlmProvider.ID,
            commandInfo = CliSlashCommandInfo(
                providerId = ClaudeCliLlmProvider.ID,
                command = "review",
                invocationKind = CliSlashInvocationKind.OPTION,
            ),
            claudeExecutablePath = "C:/Program Files/Claude/claude.exe",
            codexExecutablePath = "codex",
        )

        assertEquals(
            "\"C:/Program Files/Claude/claude.exe\" --review latest changes",
            plan?.shellCommand,
        )
        assertEquals("Provider slash command: /review latest changes", plan?.requestDescription)
    }

    @Test
    fun `buildExecutionPlan should render codex slash command as subcommand`() {
        val plan = ImprovedChatPanelProviderSlashCommandCoordinator.buildExecutionPlan(
            slashCommand = "/review latest changes",
            providerId = CodexCliLlmProvider.ID,
            commandInfo = CliSlashCommandInfo(
                providerId = CodexCliLlmProvider.ID,
                command = "review",
                invocationKind = CliSlashInvocationKind.COMMAND,
            ),
            claudeExecutablePath = "claude",
            codexExecutablePath = "codex",
        )

        assertEquals("codex review latest changes", plan?.shellCommand)
        assertEquals("Provider slash command: /review latest changes", plan?.requestDescription)
    }

    @Test
    fun `buildExecutionPlan should preserve already quoted executable path`() {
        val plan = ImprovedChatPanelProviderSlashCommandCoordinator.buildExecutionPlan(
            slashCommand = "/review",
            providerId = CodexCliLlmProvider.ID,
            commandInfo = CliSlashCommandInfo(
                providerId = CodexCliLlmProvider.ID,
                command = "review",
            ),
            claudeExecutablePath = "claude",
            codexExecutablePath = "\"C:/Program Files/Codex/codex.exe\"",
        )

        assertEquals("\"C:/Program Files/Codex/codex.exe\" review", plan?.shellCommand)
    }

    @Test
    fun `buildExecutionPlan should reject malformed or unsupported provider input`() {
        assertNull(
            ImprovedChatPanelProviderSlashCommandCoordinator.buildExecutionPlan(
                slashCommand = "review latest changes",
                providerId = ClaudeCliLlmProvider.ID,
                commandInfo = CliSlashCommandInfo(
                    providerId = ClaudeCliLlmProvider.ID,
                    command = "review",
                ),
                claudeExecutablePath = "claude",
                codexExecutablePath = "codex",
            ),
        )
        assertNull(
            ImprovedChatPanelProviderSlashCommandCoordinator.buildExecutionPlan(
                slashCommand = "/review latest changes",
                providerId = "mock",
                commandInfo = CliSlashCommandInfo(
                    providerId = "mock",
                    command = "review",
                ),
                claudeExecutablePath = "claude",
                codexExecutablePath = "codex",
            ),
        )
    }
}
