package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.engine.CliSlashCommandInfo
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.session.canonicalizeWorkflowChatCommand
import java.util.Locale

internal enum class ImprovedChatPanelSlashCommandKind {
    SHOW_AVAILABLE_SKILLS,
    PIPELINE_COMMAND,
    MODE_COMMAND,
    WORKFLOW_COMMAND,
    PROVIDER_COMMAND,
    LOCAL_SKILL_COMMAND,
    INTERACTIVE_ONLY_COMMAND,
    UNSUPPORTED_COMMAND,
}

internal data class ImprovedChatPanelSlashCommandRoute(
    val kind: ImprovedChatPanelSlashCommandKind,
    val command: String,
    val workflowCommand: String? = null,
    val providerCommandInfo: CliSlashCommandInfo? = null,
    val displayCommandToken: String? = null,
)

internal object ImprovedChatPanelSlashCommandCoordinator {

    fun resolve(
        command: String,
        providerId: String?,
        availableProviderCommands: List<CliSlashCommandInfo>,
        isRegisteredSkillSlashCommand: (String) -> Boolean,
    ): ImprovedChatPanelSlashCommandRoute {
        val trimmedCommand = command.trim()
        if (trimmedCommand == "/skills") {
            return ImprovedChatPanelSlashCommandRoute(
                kind = ImprovedChatPanelSlashCommandKind.SHOW_AVAILABLE_SKILLS,
                command = trimmedCommand,
            )
        }
        if (trimmedCommand.startsWith("/pipeline")) {
            return ImprovedChatPanelSlashCommandRoute(
                kind = ImprovedChatPanelSlashCommandKind.PIPELINE_COMMAND,
                command = trimmedCommand,
            )
        }
        if (trimmedCommand.startsWith("/mode")) {
            return ImprovedChatPanelSlashCommandRoute(
                kind = ImprovedChatPanelSlashCommandKind.MODE_COMMAND,
                command = trimmedCommand,
            )
        }

        canonicalizeWorkflowChatCommand(trimmedCommand)?.let { workflowCommand ->
            return ImprovedChatPanelSlashCommandRoute(
                kind = ImprovedChatPanelSlashCommandKind.WORKFLOW_COMMAND,
                command = trimmedCommand,
                workflowCommand = workflowCommand,
            )
        }

        val slashToken = extractSlashCommandToken(trimmedCommand)
        val providerCommand = resolveProviderSlashCommand(
            command = trimmedCommand,
            providerId = providerId,
            availableProviderCommands = availableProviderCommands,
        )
        if (providerCommand != null) {
            return if (isInteractiveOnlyProviderSlashCommand(providerId, providerCommand)) {
                ImprovedChatPanelSlashCommandRoute(
                    kind = ImprovedChatPanelSlashCommandKind.INTERACTIVE_ONLY_COMMAND,
                    command = trimmedCommand,
                    providerCommandInfo = providerCommand,
                    displayCommandToken = "/${providerCommand.command}",
                )
            } else {
                ImprovedChatPanelSlashCommandRoute(
                    kind = ImprovedChatPanelSlashCommandKind.PROVIDER_COMMAND,
                    command = trimmedCommand,
                    providerCommandInfo = providerCommand,
                )
            }
        }

        if (slashToken != null && isInteractiveOnlySessionSlashCommand(providerId, slashToken)) {
            return ImprovedChatPanelSlashCommandRoute(
                kind = ImprovedChatPanelSlashCommandKind.INTERACTIVE_ONLY_COMMAND,
                command = trimmedCommand,
                displayCommandToken = "/$slashToken",
            )
        }

        if (slashToken != null && isRegisteredSkillSlashCommand(slashToken)) {
            return ImprovedChatPanelSlashCommandRoute(
                kind = ImprovedChatPanelSlashCommandKind.LOCAL_SKILL_COMMAND,
                command = trimmedCommand,
                displayCommandToken = "/$slashToken",
            )
        }

        return ImprovedChatPanelSlashCommandRoute(
            kind = ImprovedChatPanelSlashCommandKind.UNSUPPORTED_COMMAND,
            command = trimmedCommand,
            displayCommandToken = slashToken?.let { "/$it" },
        )
    }

    internal fun extractSlashCommandToken(command: String): String? {
        val trimmed = command.trim()
        if (!trimmed.startsWith("/")) {
            return null
        }
        return trimmed
            .removePrefix("/")
            .substringBefore(" ")
            .trim()
            .lowercase(Locale.ROOT)
            .ifBlank { null }
    }

    private fun resolveProviderSlashCommand(
        command: String,
        providerId: String?,
        availableProviderCommands: List<CliSlashCommandInfo>,
    ): CliSlashCommandInfo? {
        val normalizedProvider = providerId?.trim().orEmpty()
        if (normalizedProvider.isBlank()) {
            return null
        }
        val slashToken = extractSlashCommandToken(command) ?: return null
        return availableProviderCommands.firstOrNull { item ->
            item.providerId.equals(normalizedProvider, ignoreCase = true) &&
                item.command.equals(slashToken, ignoreCase = true)
        }
    }

    private fun isInteractiveOnlyProviderSlashCommand(
        providerId: String?,
        commandInfo: CliSlashCommandInfo,
    ): Boolean {
        val provider = providerId?.trim().orEmpty()
        val command = commandInfo.command.trim().lowercase(Locale.ROOT)
        if (command.isBlank()) {
            return false
        }
        return when {
            provider.equals(ClaudeCliLlmProvider.ID, ignoreCase = true) -> {
                command in CLAUDE_INTERACTIVE_ONLY_CLI_COMMANDS
            }

            provider.equals(CodexCliLlmProvider.ID, ignoreCase = true) -> {
                command in CODEX_INTERACTIVE_ONLY_CLI_COMMANDS
            }

            else -> false
        }
    }

    private fun isInteractiveOnlySessionSlashCommand(providerId: String?, slashToken: String): Boolean {
        val normalizedToken = slashToken.trim().lowercase(Locale.ROOT)
        if (normalizedToken.isBlank()) {
            return false
        }
        val provider = providerId?.trim().orEmpty()
        return when {
            provider.equals(ClaudeCliLlmProvider.ID, ignoreCase = true) -> {
                normalizedToken in CLAUDE_SESSION_ONLY_SLASH_COMMANDS
            }

            provider.equals(CodexCliLlmProvider.ID, ignoreCase = true) -> {
                normalizedToken in CODEX_SESSION_ONLY_SLASH_COMMANDS
            }

            else -> false
        }
    }

    private val CLAUDE_INTERACTIVE_ONLY_CLI_COMMANDS = setOf(
        "agents",
        "auth",
        "doctor",
        "install",
        "mcp",
        "plugin",
        "setup-token",
        "update",
        "upgrade",
    )

    private val CODEX_INTERACTIVE_ONLY_CLI_COMMANDS = setOf(
        "app-server",
        "cloud",
        "completion",
        "debug",
        "fork",
        "login",
        "logout",
        "mcp",
        "mcp-server",
        "resume",
        "sandbox",
    )

    private val CLAUDE_SESSION_ONLY_SLASH_COMMANDS = setOf(
        "compact",
    )

    private val CODEX_SESSION_ONLY_SLASH_COMMANDS = setOf(
        "compact",
    )
}
