package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.core.OperationRequest

internal sealed interface ImprovedChatPanelShellCommandExecutionTarget {
    data object Background : ImprovedChatPanelShellCommandExecutionTarget

    data class IdeTerminal(
        val projectBasePath: String?,
        val userHome: String?,
        val composerText: String,
        val composerCaret: Int,
        val currentComposerText: () -> String,
    ) : ImprovedChatPanelShellCommandExecutionTarget
}

internal data class ImprovedChatPanelShellCommandExecutionRequest(
    val command: String,
    val requestDescription: String,
    val target: ImprovedChatPanelShellCommandExecutionTarget,
)

internal sealed interface ImprovedChatPanelShellCommandExecutionPlan {
    data object NoOp : ImprovedChatPanelShellCommandExecutionPlan

    data class LaunchInBackground(
        val dispatchRequest: ImprovedChatPanelShellCommandDispatchRequest,
    ) : ImprovedChatPanelShellCommandExecutionPlan

    data class ApplyImmediateResult(
        val feedback: ImprovedChatPanelWorkflowCommandFeedback,
        val persistAsync: Boolean,
        val operationRequest: OperationRequest,
        val restorePlan: ImprovedChatPanelComposerRestorePlan? = null,
        val launchError: Throwable? = null,
    ) : ImprovedChatPanelShellCommandExecutionPlan
}

internal class ImprovedChatPanelShellCommandExecutionCoordinator(
    private val authorizeCommandExecution: (OperationRequest, String) -> Boolean,
    private val isWorkflowCommandRunning: (String) -> Boolean,
    private val executeTerminalCommand: (
        ImprovedChatPanelTerminalCommandExecutionRequest,
        () -> String,
    ) -> ImprovedChatPanelTerminalCommandExecutionResult,
) {

    fun execute(
        request: ImprovedChatPanelShellCommandExecutionRequest,
    ): ImprovedChatPanelShellCommandExecutionPlan {
        val dispatchRequest = ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest(
            command = request.command,
            requestDescription = request.requestDescription,
        ) ?: return ImprovedChatPanelShellCommandExecutionPlan.NoOp
        if (!authorizeCommandExecution(dispatchRequest.operationRequest, dispatchRequest.normalizedCommand)) {
            return ImprovedChatPanelShellCommandExecutionPlan.NoOp
        }

        return when (val target = request.target) {
            ImprovedChatPanelShellCommandExecutionTarget.Background ->
                buildBackgroundPlan(dispatchRequest)

            is ImprovedChatPanelShellCommandExecutionTarget.IdeTerminal ->
                executeInIdeTerminal(dispatchRequest, target)
        }
    }

    private fun buildBackgroundPlan(
        dispatchRequest: ImprovedChatPanelShellCommandDispatchRequest,
    ): ImprovedChatPanelShellCommandExecutionPlan {
        return when (
            val dispatchPlan = ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planDispatch(
                dispatchRequest = dispatchRequest,
                alreadyRunning = isWorkflowCommandRunning(dispatchRequest.normalizedCommand),
            )
        ) {
            is ImprovedChatPanelWorkflowCommandDispatchPlan.LaunchInBackground ->
                ImprovedChatPanelShellCommandExecutionPlan.LaunchInBackground(dispatchPlan.dispatchRequest)

            is ImprovedChatPanelWorkflowCommandDispatchPlan.RenderFeedback ->
                ImprovedChatPanelShellCommandExecutionPlan.ApplyImmediateResult(
                    feedback = dispatchPlan.feedback,
                    persistAsync = dispatchPlan.persistAsync,
                    operationRequest = dispatchRequest.operationRequest,
                )
        }
    }

    private fun executeInIdeTerminal(
        dispatchRequest: ImprovedChatPanelShellCommandDispatchRequest,
        target: ImprovedChatPanelShellCommandExecutionTarget.IdeTerminal,
    ): ImprovedChatPanelShellCommandExecutionPlan.ApplyImmediateResult {
        val executionResult = executeTerminalCommand(
            ImprovedChatPanelTerminalCommandExecutionRequest(
                dispatchRequest = dispatchRequest,
                projectBasePath = target.projectBasePath,
                userHome = target.userHome,
                composerText = target.composerText,
                composerCaret = target.composerCaret,
            ),
            target.currentComposerText,
        )
        return ImprovedChatPanelShellCommandExecutionPlan.ApplyImmediateResult(
            feedback = executionResult.feedback,
            persistAsync = executionResult.persistAsync,
            operationRequest = executionResult.operationRequest,
            restorePlan = executionResult.restorePlan,
            launchError = executionResult.launchError,
        )
    }
}
