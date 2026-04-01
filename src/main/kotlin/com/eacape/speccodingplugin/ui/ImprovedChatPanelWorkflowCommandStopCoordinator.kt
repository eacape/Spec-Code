package com.eacape.speccodingplugin.ui

internal data class ImprovedChatPanelWorkflowCommandStopPlan(
    val normalizedCommand: String,
    val immediateFeedback: ImprovedChatPanelWorkflowCommandFeedback,
    val persistAsync: Boolean,
    val shouldAttemptStop: Boolean,
)

internal class ImprovedChatPanelWorkflowCommandStopCoordinator(
    private val isWorkflowCommandRunning: (String) -> Boolean,
    private val stopWorkflowCommand: (String) -> ImprovedChatPanelWorkflowCommandStopOutcome,
) {

    fun prepareStop(command: String): ImprovedChatPanelWorkflowCommandStopPlan? {
        val normalizedCommand = command.trim()
        if (normalizedCommand.isBlank()) {
            return null
        }

        val stopPlan = ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStop(
            command = normalizedCommand,
            isRunning = isWorkflowCommandRunning(normalizedCommand),
        )
        return ImprovedChatPanelWorkflowCommandStopPlan(
            normalizedCommand = normalizedCommand,
            immediateFeedback = stopPlan.immediateFeedback,
            persistAsync = stopPlan.persistAsync,
            shouldAttemptStop = stopPlan.shouldAttemptStop,
        )
    }

    fun performStop(
        stopPlan: ImprovedChatPanelWorkflowCommandStopPlan,
    ): ImprovedChatPanelWorkflowCommandExecutionOutcomePlan? {
        return ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStopOutcome(
            command = stopPlan.normalizedCommand,
            stopOutcome = stopWorkflowCommand(stopPlan.normalizedCommand),
        )
    }
}
