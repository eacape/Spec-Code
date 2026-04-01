package com.eacape.speccodingplugin.ui

internal sealed interface ImprovedChatPanelWorkflowCommandDispatchPlan {
    data class LaunchInBackground(
        val dispatchRequest: ImprovedChatPanelShellCommandDispatchRequest,
    ) : ImprovedChatPanelWorkflowCommandDispatchPlan

    data class RenderFeedback(
        val feedback: ImprovedChatPanelWorkflowCommandFeedback,
        val persistAsync: Boolean,
    ) : ImprovedChatPanelWorkflowCommandDispatchPlan
}

internal data class ImprovedChatPanelWorkflowCommandStopPlan(
    val immediateFeedback: ImprovedChatPanelWorkflowCommandFeedback,
    val persistAsync: Boolean,
    val shouldAttemptStop: Boolean,
)

internal data class ImprovedChatPanelWorkflowCommandExecutionOutcomePlan(
    val feedback: ImprovedChatPanelWorkflowCommandFeedback,
    val persistAsync: Boolean,
    val execution: ImprovedChatPanelWorkflowCommandExecutionResult? = null,
    val shouldPersistChangeset: Boolean = false,
)

internal object ImprovedChatPanelWorkflowCommandRuntimeCoordinator {

    fun planDispatch(
        dispatchRequest: ImprovedChatPanelShellCommandDispatchRequest,
        alreadyRunning: Boolean,
    ): ImprovedChatPanelWorkflowCommandDispatchPlan {
        if (alreadyRunning) {
            return ImprovedChatPanelWorkflowCommandDispatchPlan.RenderFeedback(
                feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildAlreadyRunningFeedback(
                    dispatchRequest.normalizedCommand,
                ),
                persistAsync = true,
            )
        }
        return ImprovedChatPanelWorkflowCommandDispatchPlan.LaunchInBackground(dispatchRequest)
    }

    fun planStop(
        command: String,
        isRunning: Boolean,
    ): ImprovedChatPanelWorkflowCommandStopPlan {
        return if (isRunning) {
            ImprovedChatPanelWorkflowCommandStopPlan(
                immediateFeedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildStopStoppingFeedback(command),
                persistAsync = true,
                shouldAttemptStop = true,
            )
        } else {
            ImprovedChatPanelWorkflowCommandStopPlan(
                immediateFeedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildStopNotRunningFeedback(command),
                persistAsync = true,
                shouldAttemptStop = false,
            )
        }
    }

    fun planStopOutcome(
        command: String,
        stopOutcome: ImprovedChatPanelWorkflowCommandStopOutcome,
    ): ImprovedChatPanelWorkflowCommandExecutionOutcomePlan? {
        return when (stopOutcome) {
            ImprovedChatPanelWorkflowCommandStopOutcome.AlreadyStopping,
            ImprovedChatPanelWorkflowCommandStopOutcome.NotRunning,
            ImprovedChatPanelWorkflowCommandStopOutcome.Stopping -> null

            is ImprovedChatPanelWorkflowCommandStopOutcome.Failed ->
                ImprovedChatPanelWorkflowCommandExecutionOutcomePlan(
                    feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildStopFailedFeedback(
                        command = command,
                        errorDetail = stopOutcome.error.message,
                    ),
                    persistAsync = false,
                )
        }
    }

    fun planExecutionOutcome(
        command: String,
        outcome: ImprovedChatPanelWorkflowCommandRunOutcome,
        timeoutSeconds: Long,
        outputLimitChars: Int,
        displayOutput: String,
        shouldHideStatus: Boolean,
    ): ImprovedChatPanelWorkflowCommandExecutionOutcomePlan {
        return when (outcome) {
            ImprovedChatPanelWorkflowCommandRunOutcome.AlreadyRunning ->
                ImprovedChatPanelWorkflowCommandExecutionOutcomePlan(
                    feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildAlreadyRunningFeedback(command),
                    persistAsync = false,
                )

            is ImprovedChatPanelWorkflowCommandRunOutcome.FailedToStart ->
                ImprovedChatPanelWorkflowCommandExecutionOutcomePlan(
                    feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildFailedToStartFeedback(
                        command = command,
                        startupErrorMessage = outcome.errorMessage,
                    ),
                    persistAsync = false,
                )

            is ImprovedChatPanelWorkflowCommandRunOutcome.Completed -> {
                val feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildCompletedFeedback(
                    command = command,
                    execution = outcome.execution,
                    timeoutSeconds = timeoutSeconds,
                    outputLimitChars = outputLimitChars,
                    displayOutput = displayOutput,
                    shouldHideStatus = shouldHideStatus,
                )
                ImprovedChatPanelWorkflowCommandExecutionOutcomePlan(
                    feedback = feedback,
                    persistAsync = false,
                    execution = outcome.execution,
                    shouldPersistChangeset = feedback.shouldPersistChangeset,
                )
            }
        }
    }
}
