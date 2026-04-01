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

    data class PermissionDenied(
        val errorMessage: String,
    ) : ImprovedChatPanelShellCommandExecutionPlan

    data class LaunchInBackground(
        val dispatchRequest: ImprovedChatPanelShellCommandDispatchRequest,
        val preExecutionSystemMessage: String? = null,
    ) : ImprovedChatPanelShellCommandExecutionPlan

    data class ApplyImmediateResult(
        val feedback: ImprovedChatPanelWorkflowCommandFeedback,
        val persistAsync: Boolean,
        val operationRequest: OperationRequest,
        val preExecutionSystemMessage: String? = null,
        val restorePlan: ImprovedChatPanelComposerRestorePlan? = null,
        val launchError: Throwable? = null,
    ) : ImprovedChatPanelShellCommandExecutionPlan
}

internal class ImprovedChatPanelShellCommandExecutionCoordinator(
    private val authorizeCommandExecution: (OperationRequest, String) -> ImprovedChatPanelWorkflowCommandPermissionOutcome,
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

        return when (
            val permissionOutcome = authorizeCommandExecution(
                dispatchRequest.operationRequest,
                dispatchRequest.normalizedCommand,
            )
        ) {
            is ImprovedChatPanelWorkflowCommandPermissionOutcome.Allowed -> {
                when (val target = request.target) {
                    ImprovedChatPanelShellCommandExecutionTarget.Background ->
                        buildBackgroundPlan(dispatchRequest, permissionOutcome.acceptedSystemMessage)

                    is ImprovedChatPanelShellCommandExecutionTarget.IdeTerminal ->
                        executeInIdeTerminal(
                            dispatchRequest,
                            target,
                            permissionOutcome.acceptedSystemMessage,
                        )
                }
            }

            is ImprovedChatPanelWorkflowCommandPermissionOutcome.Denied ->
                ImprovedChatPanelShellCommandExecutionPlan.PermissionDenied(permissionOutcome.errorMessage)

            ImprovedChatPanelWorkflowCommandPermissionOutcome.Cancelled ->
                ImprovedChatPanelShellCommandExecutionPlan.NoOp
        }
    }

    private fun buildBackgroundPlan(
        dispatchRequest: ImprovedChatPanelShellCommandDispatchRequest,
        preExecutionSystemMessage: String?,
    ): ImprovedChatPanelShellCommandExecutionPlan {
        return when (
            val dispatchPlan = ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planDispatch(
                dispatchRequest = dispatchRequest,
                alreadyRunning = isWorkflowCommandRunning(dispatchRequest.normalizedCommand),
            )
        ) {
            is ImprovedChatPanelWorkflowCommandDispatchPlan.LaunchInBackground ->
                ImprovedChatPanelShellCommandExecutionPlan.LaunchInBackground(
                    dispatchRequest = dispatchPlan.dispatchRequest,
                    preExecutionSystemMessage = preExecutionSystemMessage,
                )

            is ImprovedChatPanelWorkflowCommandDispatchPlan.RenderFeedback ->
                ImprovedChatPanelShellCommandExecutionPlan.ApplyImmediateResult(
                    feedback = dispatchPlan.feedback,
                    persistAsync = dispatchPlan.persistAsync,
                    operationRequest = dispatchRequest.operationRequest,
                    preExecutionSystemMessage = preExecutionSystemMessage,
                )
        }
    }

    private fun executeInIdeTerminal(
        dispatchRequest: ImprovedChatPanelShellCommandDispatchRequest,
        target: ImprovedChatPanelShellCommandExecutionTarget.IdeTerminal,
        preExecutionSystemMessage: String?,
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
            preExecutionSystemMessage = preExecutionSystemMessage,
            restorePlan = executionResult.restorePlan,
            launchError = executionResult.launchError,
        )
    }
}
