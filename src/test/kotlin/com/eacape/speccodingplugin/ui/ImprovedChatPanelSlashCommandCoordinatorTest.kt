package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.engine.CliSlashCommandInfo
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ImprovedChatPanelSlashCommandCoordinatorTest {

    @Test
    fun `resolve should route built in slash entrypoints before provider or skill checks`() {
        assertEquals(
            ImprovedChatPanelSlashCommandKind.SHOW_AVAILABLE_SKILLS,
            ImprovedChatPanelSlashCommandCoordinator.resolve(
                command = "/skills",
                providerId = ClaudeCliLlmProvider.ID,
                availableProviderCommands = listOf(
                    CliSlashCommandInfo(
                        providerId = ClaudeCliLlmProvider.ID,
                        command = "skills",
                    ),
                ),
                isRegisteredSkillSlashCommand = { true },
            ).kind,
        )
        assertEquals(
            ImprovedChatPanelSlashCommandKind.PIPELINE_COMMAND,
            ImprovedChatPanelSlashCommandCoordinator.resolve(
                command = "/pipeline verify",
                providerId = ClaudeCliLlmProvider.ID,
                availableProviderCommands = emptyList(),
                isRegisteredSkillSlashCommand = { false },
            ).kind,
        )
        assertEquals(
            ImprovedChatPanelSlashCommandKind.MODE_COMMAND,
            ImprovedChatPanelSlashCommandCoordinator.resolve(
                command = "/mode read-only",
                providerId = ClaudeCliLlmProvider.ID,
                availableProviderCommands = emptyList(),
                isRegisteredSkillSlashCommand = { false },
            ).kind,
        )
    }

    @Test
    fun `resolve should canonicalize workflow slash commands before provider fallback`() {
        val route = ImprovedChatPanelSlashCommandCoordinator.resolve(
            command = "/spec next",
            providerId = CodexCliLlmProvider.ID,
            availableProviderCommands = listOf(
                CliSlashCommandInfo(
                    providerId = CodexCliLlmProvider.ID,
                    command = "spec",
                ),
            ),
            isRegisteredSkillSlashCommand = { false },
        )

        assertEquals(ImprovedChatPanelSlashCommandKind.WORKFLOW_COMMAND, route.kind)
        assertEquals("/workflow next", route.workflowCommand)
        assertNull(route.providerCommandInfo)
    }

    @Test
    fun `resolve should route provider slash command when provider supports token`() {
        val route = ImprovedChatPanelSlashCommandCoordinator.resolve(
            command = "/review latest changes",
            providerId = ClaudeCliLlmProvider.ID,
            availableProviderCommands = listOf(
                CliSlashCommandInfo(
                    providerId = ClaudeCliLlmProvider.ID,
                    command = "review",
                ),
            ),
            isRegisteredSkillSlashCommand = { false },
        )

        assertEquals(ImprovedChatPanelSlashCommandKind.PROVIDER_COMMAND, route.kind)
        assertEquals("review", route.providerCommandInfo?.command)
    }

    @Test
    fun `resolve should block provider interactive only slash command before execution`() {
        val route = ImprovedChatPanelSlashCommandCoordinator.resolve(
            command = "/mcp list",
            providerId = ClaudeCliLlmProvider.ID,
            availableProviderCommands = listOf(
                CliSlashCommandInfo(
                    providerId = ClaudeCliLlmProvider.ID,
                    command = "mcp",
                ),
            ),
            isRegisteredSkillSlashCommand = { false },
        )

        assertEquals(ImprovedChatPanelSlashCommandKind.INTERACTIVE_ONLY_COMMAND, route.kind)
        assertEquals("/mcp", route.displayCommandToken)
    }

    @Test
    fun `resolve should block session only slash token when provider command is unavailable`() {
        val route = ImprovedChatPanelSlashCommandCoordinator.resolve(
            command = "/compact latest response",
            providerId = CodexCliLlmProvider.ID,
            availableProviderCommands = emptyList(),
            isRegisteredSkillSlashCommand = { false },
        )

        assertEquals(ImprovedChatPanelSlashCommandKind.INTERACTIVE_ONLY_COMMAND, route.kind)
        assertEquals("/compact", route.displayCommandToken)
    }

    @Test
    fun `resolve should fallback to local skill slash command when registered`() {
        val route = ImprovedChatPanelSlashCommandCoordinator.resolve(
            command = "/refactor clean up panel",
            providerId = ClaudeCliLlmProvider.ID,
            availableProviderCommands = emptyList(),
            isRegisteredSkillSlashCommand = { token -> token == "refactor" },
        )

        assertEquals(ImprovedChatPanelSlashCommandKind.LOCAL_SKILL_COMMAND, route.kind)
        assertEquals("/refactor", route.displayCommandToken)
    }

    @Test
    fun `resolve should mark unknown slash command as unsupported`() {
        val route = ImprovedChatPanelSlashCommandCoordinator.resolve(
            command = "/unknown command",
            providerId = ClaudeCliLlmProvider.ID,
            availableProviderCommands = emptyList(),
            isRegisteredSkillSlashCommand = { false },
        )

        assertEquals(ImprovedChatPanelSlashCommandKind.UNSUPPORTED_COMMAND, route.kind)
        assertEquals("/unknown", route.displayCommandToken)
    }
}
