package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.engine.CliSlashCommandInfo
import com.eacape.speccodingplugin.engine.CliSlashInvocationKind
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider

internal data class ImprovedChatPanelProviderSlashCommandExecutionPlan(
    val shellCommand: String,
    val requestDescription: String,
)

internal object ImprovedChatPanelProviderSlashCommandCoordinator {

    fun buildExecutionPlan(
        slashCommand: String,
        providerId: String?,
        commandInfo: CliSlashCommandInfo,
        claudeExecutablePath: String,
        codexExecutablePath: String,
    ): ImprovedChatPanelProviderSlashCommandExecutionPlan? {
        val normalizedProvider = providerId?.trim().orEmpty()
        if (normalizedProvider.isBlank()) {
            return null
        }

        val slashToken = ImprovedChatPanelSlashCommandCoordinator.extractSlashCommandToken(slashCommand) ?: return null
        val cliExecutable = resolveCliExecutable(
            providerId = normalizedProvider,
            claudeExecutablePath = claudeExecutablePath,
            codexExecutablePath = codexExecutablePath,
        ) ?: return null
        val args = slashCommand.removePrefix("/")
            .trim()
            .substringAfter(" ", "")
            .trim()
        val invocationToken = if (
            normalizedProvider.equals(ClaudeCliLlmProvider.ID, ignoreCase = true) &&
            commandInfo.invocationKind == CliSlashInvocationKind.OPTION
        ) {
            "--$slashToken"
        } else {
            slashToken
        }

        val shellCommand = buildString {
            append(quoteShellTokenIfNeeded(cliExecutable))
            append(' ')
            append(invocationToken)
            if (args.isNotBlank()) {
                append(' ')
                append(args)
            }
        }.trim()

        return ImprovedChatPanelProviderSlashCommandExecutionPlan(
            shellCommand = shellCommand,
            requestDescription = "Provider slash command: ${slashCommand.trim()}",
        )
    }

    private fun resolveCliExecutable(
        providerId: String,
        claudeExecutablePath: String,
        codexExecutablePath: String,
    ): String? {
        return when {
            providerId.equals(ClaudeCliLlmProvider.ID, ignoreCase = true) -> claudeExecutablePath.trim().ifBlank { "claude" }
            providerId.equals(CodexCliLlmProvider.ID, ignoreCase = true) -> codexExecutablePath.trim().ifBlank { "codex" }
            else -> null
        }
    }

    private fun quoteShellTokenIfNeeded(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return trimmed
        }
        if (trimmed.startsWith('"') && trimmed.endsWith('"')) {
            return trimmed
        }
        return if (trimmed.any { it.isWhitespace() }) {
            "\"$trimmed\""
        } else {
            trimmed
        }
    }
}
