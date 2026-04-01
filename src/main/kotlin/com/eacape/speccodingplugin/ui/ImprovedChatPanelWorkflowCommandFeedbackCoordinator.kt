package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.OperationRequest
import com.eacape.speccodingplugin.core.OperationResult

internal enum class ImprovedChatPanelWorkflowCommandFeedbackMessageKind {
    SYSTEM,
    ERROR,
}

internal data class ImprovedChatPanelWorkflowCommandFeedback(
    val statusMessage: String? = null,
    val conversationMessage: String? = null,
    val conversationMessageKind: ImprovedChatPanelWorkflowCommandFeedbackMessageKind? = null,
    val operationRecordedSuccess: Boolean? = null,
    val shouldHideStatus: Boolean = false,
    val shouldPersistChangeset: Boolean = false,
)

internal sealed interface ImprovedChatPanelWorkflowCommandPermissionDecision {
    data object Allowed : ImprovedChatPanelWorkflowCommandPermissionDecision

    data class Denied(
        val errorMessage: String,
    ) : ImprovedChatPanelWorkflowCommandPermissionDecision

    data class RequiresConfirmation(
        val title: String,
        val message: String,
        val acceptedSystemMessage: String,
    ) : ImprovedChatPanelWorkflowCommandPermissionDecision
}

internal object ImprovedChatPanelWorkflowCommandFeedbackCoordinator {

    fun resolvePermission(
        result: OperationResult,
        currentModeDisplayName: String,
        request: OperationRequest,
        command: String,
    ): ImprovedChatPanelWorkflowCommandPermissionDecision {
        return when (result) {
            is OperationResult.Allowed -> ImprovedChatPanelWorkflowCommandPermissionDecision.Allowed
            is OperationResult.Denied -> {
                ImprovedChatPanelWorkflowCommandPermissionDecision.Denied(
                    errorMessage = SpecCodingBundle.message(
                        "toolwindow.mode.operation.denied",
                        currentModeDisplayName,
                        result.reason,
                    ),
                )
            }

            is OperationResult.RequiresConfirmation -> {
                ImprovedChatPanelWorkflowCommandPermissionDecision.RequiresConfirmation(
                    title = SpecCodingBundle.message("chat.workflow.action.runCommand.confirm.title"),
                    message = SpecCodingBundle.message(
                        "chat.workflow.action.runCommand.confirm.message",
                        command,
                    ),
                    acceptedSystemMessage = SpecCodingBundle.message(
                        "toolwindow.mode.confirmation.accepted",
                        request.operation.name,
                    ),
                )
            }
        }
    }

    fun buildBackgroundRunningStatus(command: String): String {
        return SpecCodingBundle.message("chat.workflow.action.runCommand.running", command)
    }

    fun buildAlreadyRunningFeedback(command: String): ImprovedChatPanelWorkflowCommandFeedback {
        return systemMessage(
            SpecCodingBundle.message("chat.workflow.action.runCommand.alreadyRunning", command),
        )
    }

    fun buildTerminalStartedFeedback(command: String): ImprovedChatPanelWorkflowCommandFeedback {
        return ImprovedChatPanelWorkflowCommandFeedback(
            statusMessage = SpecCodingBundle.message("chat.workflow.action.runCommand.startedTerminal", command),
            operationRecordedSuccess = true,
        )
    }

    fun buildTerminalUnavailableFeedback(command: String): ImprovedChatPanelWorkflowCommandFeedback {
        return errorMessage(
            SpecCodingBundle.message("chat.workflow.action.runCommand.terminalUnavailable", command),
        )
    }

    fun buildStopNotRunningFeedback(command: String): ImprovedChatPanelWorkflowCommandFeedback {
        return systemMessage(
            SpecCodingBundle.message("chat.workflow.action.stopCommand.notRunning", command),
        )
    }

    fun buildStopStoppingFeedback(command: String): ImprovedChatPanelWorkflowCommandFeedback {
        return systemMessage(
            SpecCodingBundle.message("chat.workflow.action.stopCommand.stopping", command),
        )
    }

    fun buildStopFailedFeedback(
        command: String,
        errorDetail: String?,
    ): ImprovedChatPanelWorkflowCommandFeedback {
        return errorMessage(
            buildString {
                append(SpecCodingBundle.message("chat.workflow.action.runCommand.error", command))
                append('\n')
                append(errorDetail ?: SpecCodingBundle.message("common.unknown"))
            },
        )
    }

    fun buildFailedToStartFeedback(
        command: String,
        startupErrorMessage: String,
    ): ImprovedChatPanelWorkflowCommandFeedback {
        val execution = ImprovedChatPanelWorkflowCommandExecutionResult(
            success = false,
            error = startupErrorMessage,
            output = startupErrorMessage,
        )
        return errorMessage(
            conversationMessage = formatExecutionSummary(
                command = command,
                execution = execution,
                timeoutSeconds = 0,
                outputLimitChars = 0,
                displayOutput = startupErrorMessage,
            ),
            operationRecordedSuccess = false,
        )
    }

    fun buildCompletedFeedback(
        command: String,
        execution: ImprovedChatPanelWorkflowCommandExecutionResult,
        timeoutSeconds: Long,
        outputLimitChars: Int,
        displayOutput: String,
        shouldHideStatus: Boolean,
    ): ImprovedChatPanelWorkflowCommandFeedback {
        val successful = execution.success || execution.stoppedByUser
        return ImprovedChatPanelWorkflowCommandFeedback(
            conversationMessage = formatExecutionSummary(
                command = command,
                execution = execution,
                timeoutSeconds = timeoutSeconds,
                outputLimitChars = outputLimitChars,
                displayOutput = displayOutput,
            ),
            conversationMessageKind = if (successful) {
                ImprovedChatPanelWorkflowCommandFeedbackMessageKind.SYSTEM
            } else {
                ImprovedChatPanelWorkflowCommandFeedbackMessageKind.ERROR
            },
            operationRecordedSuccess = successful,
            shouldHideStatus = shouldHideStatus,
            shouldPersistChangeset = true,
        )
    }

    private fun formatExecutionSummary(
        command: String,
        execution: ImprovedChatPanelWorkflowCommandExecutionResult,
        timeoutSeconds: Long,
        outputLimitChars: Int,
        displayOutput: String,
    ): String {
        val icon = when {
            execution.stoppedByUser -> "\u23F9"
            execution.timedOut -> "\u23F1"
            execution.error != null -> "\u26A0"
            execution.success -> "\u2705"
            else -> "\u274C"
        }
        val statusText = when {
            execution.stoppedByUser -> SpecCodingBundle.message("chat.workflow.action.stopCommand.stopped", command)
            execution.timedOut -> SpecCodingBundle.message(
                "chat.workflow.action.runCommand.timeout",
                timeoutSeconds,
                command,
            )
            execution.error != null -> SpecCodingBundle.message("chat.workflow.action.runCommand.error", command)
            execution.success -> SpecCodingBundle.message(
                "chat.workflow.action.runCommand.success",
                execution.exitCode ?: 0,
                command,
            )
            else -> SpecCodingBundle.message(
                "chat.workflow.action.runCommand.failed",
                execution.exitCode ?: -1,
                command,
            )
        }

        var output = displayOutput.ifBlank {
            SpecCodingBundle.message("chat.workflow.action.runCommand.noOutput")
        }
        if (execution.outputTruncated) {
            output += "\n" + SpecCodingBundle.message(
                "chat.workflow.action.runCommand.outputTruncated",
                outputLimitChars,
            )
        }

        return buildString {
            appendLine("$icon $statusText")
            appendLine("${SpecCodingBundle.message("chat.workflow.action.runCommand.outputLabel")}：")
            append(output)
        }
    }

    private fun systemMessage(
        conversationMessage: String,
    ): ImprovedChatPanelWorkflowCommandFeedback {
        return ImprovedChatPanelWorkflowCommandFeedback(
            conversationMessage = conversationMessage,
            conversationMessageKind = ImprovedChatPanelWorkflowCommandFeedbackMessageKind.SYSTEM,
        )
    }

    private fun errorMessage(
        conversationMessage: String,
        operationRecordedSuccess: Boolean? = null,
    ): ImprovedChatPanelWorkflowCommandFeedback {
        return ImprovedChatPanelWorkflowCommandFeedback(
            conversationMessage = conversationMessage,
            conversationMessageKind = ImprovedChatPanelWorkflowCommandFeedbackMessageKind.ERROR,
            operationRecordedSuccess = operationRecordedSuccess,
        )
    }
}
