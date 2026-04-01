package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.core.OperationRequest
import com.eacape.speccodingplugin.rollback.WorkspaceChangesetCollector

internal data class ImprovedChatPanelWorkflowCommandBackgroundRequest(
    val dispatchRequest: ImprovedChatPanelShellCommandDispatchRequest,
    val shouldHideStatus: Boolean,
)

internal data class ImprovedChatPanelWorkflowCommandChangesetPlan(
    val command: String,
    val execution: ImprovedChatPanelWorkflowCommandExecutionResult,
    val beforeSnapshot: WorkspaceChangesetCollector.Snapshot?,
)

internal data class ImprovedChatPanelWorkflowCommandBackgroundResult(
    val feedback: ImprovedChatPanelWorkflowCommandFeedback,
    val persistAsync: Boolean,
    val operationRequest: OperationRequest,
    val changesetPlan: ImprovedChatPanelWorkflowCommandChangesetPlan? = null,
)

internal class ImprovedChatPanelWorkflowCommandExecutionCoordinator(
    private val timeoutSeconds: Long,
    private val outputLimitChars: Int,
    private val captureBeforeSnapshot: () -> WorkspaceChangesetCollector.Snapshot?,
    private val executeCommand: (String, (() -> Unit)?) -> ImprovedChatPanelWorkflowCommandRunOutcome,
    private val sanitizeDisplayOutput: (String) -> String,
    private val showRunningStatus: (String) -> Unit,
) {

    fun executeInBackground(
        request: ImprovedChatPanelWorkflowCommandBackgroundRequest,
    ): ImprovedChatPanelWorkflowCommandBackgroundResult {
        val command = request.dispatchRequest.normalizedCommand
        val beforeSnapshot = captureBeforeSnapshot()
        val outcome = executeCommand(command) {
            showRunningStatus(
                ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildBackgroundRunningStatus(command),
            )
        }

        val displayOutput = when (outcome) {
            is ImprovedChatPanelWorkflowCommandRunOutcome.Completed ->
                sanitizeDisplayOutput(outcome.execution.output)

            ImprovedChatPanelWorkflowCommandRunOutcome.AlreadyRunning,
            is ImprovedChatPanelWorkflowCommandRunOutcome.FailedToStart -> ""
        }
        val outcomePlan = ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planExecutionOutcome(
            command = command,
            outcome = outcome,
            timeoutSeconds = timeoutSeconds,
            outputLimitChars = outputLimitChars,
            displayOutput = displayOutput,
            shouldHideStatus = request.shouldHideStatus,
        )
        val changesetPlan = outcomePlan.execution
            ?.takeIf { outcomePlan.shouldPersistChangeset }
            ?.let { execution ->
                ImprovedChatPanelWorkflowCommandChangesetPlan(
                    command = command,
                    execution = execution,
                    beforeSnapshot = beforeSnapshot,
                )
            }

        return ImprovedChatPanelWorkflowCommandBackgroundResult(
            feedback = outcomePlan.feedback,
            persistAsync = outcomePlan.persistAsync,
            operationRequest = request.dispatchRequest.operationRequest,
            changesetPlan = changesetPlan,
        )
    }
}
