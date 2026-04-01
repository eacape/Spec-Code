package com.eacape.speccodingplugin.ui

internal data class ImprovedChatPanelWorkflowCommandStopExecutionPlan(
    val normalizedCommand: String,
    val immediateFeedback: ImprovedChatPanelWorkflowCommandFeedback,
    val persistAsync: Boolean,
    val shouldAttemptStop: Boolean,
)

internal class ImprovedChatPanelWorkflowCommandStopCoordinator(
    private val isWorkflowCommandRunning: (String) -> Boolean,
    private val stopWorkflowCommand: (String) -> ImprovedChatPanelWorkflowCommandStopOutcome,
) {

    fun prepareStop(command: String): ImprovedChatPanelWorkflowCommandStopExecutionPlan? {
        val normalizedCommand = command.trim()
        if (normalizedCommand.isBlank()) {
            return null
        }

        val stopPlan = ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStop(
            command = normalizedCommand,
            isRunning = isWorkflowCommandRunning(normalizedCommand),
        )
        return ImprovedChatPanelWorkflowCommandStopExecutionPlan(
            normalizedCommand = normalizedCommand,
            immediateFeedback = stopPlan.immediateFeedback,
            persistAsync = stopPlan.persistAsync,
            shouldAttemptStop = stopPlan.shouldAttemptStop,
        )
    }

    fun performStop(
        stopPlan: ImprovedChatPanelWorkflowCommandStopExecutionPlan,
    ): ImprovedChatPanelWorkflowCommandExecutionOutcomePlan? {
        return ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStopOutcome(
            command = stopPlan.normalizedCommand,
            stopOutcome = stopWorkflowCommand(stopPlan.normalizedCommand),
        )
    }
}
