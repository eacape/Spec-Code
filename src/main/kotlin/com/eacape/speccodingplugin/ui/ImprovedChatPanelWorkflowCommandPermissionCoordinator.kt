package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.core.OperationRequest
import com.eacape.speccodingplugin.core.OperationResult

internal sealed interface ImprovedChatPanelWorkflowCommandPermissionOutcome {
    data class Allowed(
        val acceptedSystemMessage: String? = null,
    ) : ImprovedChatPanelWorkflowCommandPermissionOutcome

    data class Denied(
        val errorMessage: String,
    ) : ImprovedChatPanelWorkflowCommandPermissionOutcome

    data object Cancelled : ImprovedChatPanelWorkflowCommandPermissionOutcome
}

internal class ImprovedChatPanelWorkflowCommandPermissionCoordinator(
    private val checkOperation: (OperationRequest) -> OperationResult,
    private val currentModeDisplayName: () -> String,
    private val requestConfirmation: (title: String, message: String) -> Boolean,
) {

    fun authorize(
        request: OperationRequest,
        command: String,
    ): ImprovedChatPanelWorkflowCommandPermissionOutcome {
        return when (
            val decision = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.resolvePermission(
                result = checkOperation(request),
                currentModeDisplayName = currentModeDisplayName(),
                request = request,
                command = command,
            )
        ) {
            ImprovedChatPanelWorkflowCommandPermissionDecision.Allowed ->
                ImprovedChatPanelWorkflowCommandPermissionOutcome.Allowed()

            is ImprovedChatPanelWorkflowCommandPermissionDecision.Denied ->
                ImprovedChatPanelWorkflowCommandPermissionOutcome.Denied(decision.errorMessage)

            is ImprovedChatPanelWorkflowCommandPermissionDecision.RequiresConfirmation ->
                if (requestConfirmation(decision.title, decision.message)) {
                    ImprovedChatPanelWorkflowCommandPermissionOutcome.Allowed(
                        acceptedSystemMessage = decision.acceptedSystemMessage,
                    )
                } else {
                    ImprovedChatPanelWorkflowCommandPermissionOutcome.Cancelled
                }
        }
    }
}
