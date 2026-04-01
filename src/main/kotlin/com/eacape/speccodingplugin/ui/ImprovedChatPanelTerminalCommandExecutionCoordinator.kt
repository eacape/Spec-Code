package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.core.OperationRequest

internal data class ImprovedChatPanelTerminalCommandExecutionRequest(
    val dispatchRequest: ImprovedChatPanelShellCommandDispatchRequest,
    val projectBasePath: String?,
    val userHome: String?,
    val composerText: String,
    val composerCaret: Int,
)

internal data class ImprovedChatPanelComposerRestorePlan(
    val text: String,
    val caret: Int,
)

internal data class ImprovedChatPanelTerminalCommandExecutionResult(
    val feedback: ImprovedChatPanelWorkflowCommandFeedback,
    val persistAsync: Boolean,
    val operationRequest: OperationRequest,
    val restorePlan: ImprovedChatPanelComposerRestorePlan? = null,
    val launchError: Throwable? = null,
)

internal class ImprovedChatPanelTerminalCommandExecutionCoordinator(
    private val executeInIdeTerminal: (String, String) -> Unit,
) {

    fun execute(
        request: ImprovedChatPanelTerminalCommandExecutionRequest,
        currentComposerText: () -> String,
    ): ImprovedChatPanelTerminalCommandExecutionResult {
        val terminalPlan = ImprovedChatPanelShellCommandDispatchCoordinator.buildTerminalLaunchPlan(
            dispatchRequest = request.dispatchRequest,
            projectBasePath = request.projectBasePath,
            userHome = request.userHome,
            composerText = request.composerText,
            composerCaret = request.composerCaret,
        )
            ?: return ImprovedChatPanelTerminalCommandExecutionResult(
                feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildTerminalUnavailableFeedback(
                    request.dispatchRequest.normalizedCommand,
                ),
                persistAsync = true,
                operationRequest = request.dispatchRequest.operationRequest,
            )

        return runCatching {
            executeInIdeTerminal(
                terminalPlan.dispatchRequest.normalizedCommand,
                terminalPlan.workingDirectory,
            )
        }.fold(
            onSuccess = {
                ImprovedChatPanelTerminalCommandExecutionResult(
                    feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildTerminalStartedFeedback(
                        terminalPlan.dispatchRequest.normalizedCommand,
                    ),
                    persistAsync = false,
                    operationRequest = terminalPlan.dispatchRequest.operationRequest,
                    restorePlan = buildRestorePlan(terminalPlan, currentComposerText()),
                )
            },
            onFailure = { error ->
                ImprovedChatPanelTerminalCommandExecutionResult(
                    feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildTerminalUnavailableFeedback(
                        terminalPlan.dispatchRequest.normalizedCommand,
                    ),
                    persistAsync = true,
                    operationRequest = terminalPlan.dispatchRequest.operationRequest,
                    restorePlan = buildRestorePlan(terminalPlan, currentComposerText()),
                    launchError = error,
                )
            },
        )
    }

    private fun buildRestorePlan(
        terminalPlan: ImprovedChatPanelTerminalCommandLaunchPlan,
        currentComposerText: String,
    ): ImprovedChatPanelComposerRestorePlan? {
        if (
            !ImprovedChatPanelShellCommandDispatchCoordinator.shouldRestoreComposerAfterTerminalEcho(
                terminalPlan,
                currentComposerText,
            )
        ) {
            return null
        }
        return ImprovedChatPanelComposerRestorePlan(
            text = terminalPlan.originalComposerText,
            caret = terminalPlan.originalComposerCaret,
        )
    }
}
