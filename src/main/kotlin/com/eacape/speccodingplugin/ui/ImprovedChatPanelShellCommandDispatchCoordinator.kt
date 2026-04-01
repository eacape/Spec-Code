package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.core.Operation
import com.eacape.speccodingplugin.core.OperationRequest

internal data class ImprovedChatPanelShellCommandDispatchRequest(
    val normalizedCommand: String,
    val requestDescription: String,
    val operationRequest: OperationRequest,
)

internal data class ImprovedChatPanelTerminalCommandLaunchPlan(
    val dispatchRequest: ImprovedChatPanelShellCommandDispatchRequest,
    val workingDirectory: String,
    val originalComposerText: String,
    val originalComposerCaret: Int,
)

internal object ImprovedChatPanelShellCommandDispatchCoordinator {

    fun buildDispatchRequest(
        command: String,
        requestDescription: String,
    ): ImprovedChatPanelShellCommandDispatchRequest? {
        val normalizedCommand = command.trim()
        if (normalizedCommand.isBlank()) {
            return null
        }
        return ImprovedChatPanelShellCommandDispatchRequest(
            normalizedCommand = normalizedCommand,
            requestDescription = requestDescription,
            operationRequest = OperationRequest(
                operation = Operation.EXECUTE_COMMAND,
                description = requestDescription,
                details = mapOf("command" to normalizedCommand),
            ),
        )
    }

    fun buildTerminalLaunchPlan(
        dispatchRequest: ImprovedChatPanelShellCommandDispatchRequest,
        projectBasePath: String?,
        userHome: String?,
        composerText: String,
        composerCaret: Int,
    ): ImprovedChatPanelTerminalCommandLaunchPlan? {
        val workingDirectory = projectBasePath?.trim()?.takeIf(String::isNotEmpty)
            ?: userHome?.trim()?.takeIf(String::isNotEmpty)
            ?: return null
        return ImprovedChatPanelTerminalCommandLaunchPlan(
            dispatchRequest = dispatchRequest,
            workingDirectory = workingDirectory,
            originalComposerText = composerText,
            originalComposerCaret = composerCaret.coerceIn(0, composerText.length),
        )
    }

    fun shouldRestoreComposerAfterTerminalEcho(
        terminalPlan: ImprovedChatPanelTerminalCommandLaunchPlan,
        currentText: String,
    ): Boolean {
        val originalText = terminalPlan.originalComposerText
        val command = terminalPlan.dispatchRequest.normalizedCommand
        if (command.isBlank() || currentText == originalText) {
            return false
        }
        if (originalText.isBlank() && currentText == command) {
            return true
        }

        val safeCaret = terminalPlan.originalComposerCaret.coerceIn(0, currentText.length)
        val insertedEnd = safeCaret + command.length
        if (insertedEnd > currentText.length) {
            return false
        }

        return currentText.substring(safeCaret, insertedEnd) == command &&
            currentText.removeRange(safeCaret, insertedEnd) == originalText
    }
}
