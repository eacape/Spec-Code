package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.Operation
import com.eacape.speccodingplugin.core.OperationRequest
import com.eacape.speccodingplugin.core.OperationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class ImprovedChatPanelWorkflowCommandPermissionCoordinatorTest {

    @Test
    fun `authorize should allow command without confirmation`() {
        val confirmationRequested = AtomicBoolean(false)
        val coordinator = ImprovedChatPanelWorkflowCommandPermissionCoordinator(
            checkOperation = { OperationResult.Allowed() },
            currentModeDisplayName = { "Default" },
            requestConfirmation = { _, _ ->
                confirmationRequested.set(true)
                true
            },
        )

        val outcome = coordinator.authorize(
            request = workflowCommandRequest("gradle verify"),
            command = "gradle verify",
        )

        assertTrue(outcome is ImprovedChatPanelWorkflowCommandPermissionOutcome.Allowed)
        assertEquals(
            null,
            (outcome as ImprovedChatPanelWorkflowCommandPermissionOutcome.Allowed).acceptedSystemMessage,
        )
        assertTrue(!confirmationRequested.get())
    }

    @Test
    fun `authorize should build denied outcome with current mode`() {
        val coordinator = ImprovedChatPanelWorkflowCommandPermissionCoordinator(
            checkOperation = { OperationResult.Denied("dangerous command") },
            currentModeDisplayName = { "Agent" },
            requestConfirmation = { _, _ -> error("Denied command should not ask for confirmation") },
        )

        val outcome = coordinator.authorize(
            request = workflowCommandRequest("rm -rf"),
            command = "rm -rf",
        )

        assertTrue(outcome is ImprovedChatPanelWorkflowCommandPermissionOutcome.Denied)
        assertEquals(
            SpecCodingBundle.message("toolwindow.mode.operation.denied", "Agent", "dangerous command"),
            (outcome as ImprovedChatPanelWorkflowCommandPermissionOutcome.Denied).errorMessage,
        )
    }

    @Test
    fun `authorize should return accepted system message after confirmation`() {
        val request = workflowCommandRequest("gradle verify")
        val confirmationPrompt = mutableListOf<Pair<String, String>>()
        val coordinator = ImprovedChatPanelWorkflowCommandPermissionCoordinator(
            checkOperation = { OperationResult.RequiresConfirmation(request) },
            currentModeDisplayName = { "Default" },
            requestConfirmation = { title, message ->
                confirmationPrompt += title to message
                true
            },
        )

        val outcome = coordinator.authorize(
            request = request,
            command = "gradle verify",
        )

        assertEquals(
            listOf(
                SpecCodingBundle.message("chat.workflow.action.runCommand.confirm.title") to
                    SpecCodingBundle.message("chat.workflow.action.runCommand.confirm.message", "gradle verify"),
            ),
            confirmationPrompt,
        )
        assertTrue(outcome is ImprovedChatPanelWorkflowCommandPermissionOutcome.Allowed)
        assertEquals(
            SpecCodingBundle.message("toolwindow.mode.confirmation.accepted", Operation.EXECUTE_COMMAND.name),
            (outcome as ImprovedChatPanelWorkflowCommandPermissionOutcome.Allowed).acceptedSystemMessage,
        )
    }

    @Test
    fun `authorize should return cancelled outcome when confirmation rejected`() {
        val request = workflowCommandRequest("gradle verify")
        val coordinator = ImprovedChatPanelWorkflowCommandPermissionCoordinator(
            checkOperation = { OperationResult.RequiresConfirmation(request) },
            currentModeDisplayName = { "Default" },
            requestConfirmation = { _, _ -> false },
        )

        val outcome = coordinator.authorize(
            request = request,
            command = "gradle verify",
        )

        assertEquals(ImprovedChatPanelWorkflowCommandPermissionOutcome.Cancelled, outcome)
    }

    private fun workflowCommandRequest(command: String): OperationRequest {
        return OperationRequest(
            operation = Operation.EXECUTE_COMMAND,
            description = "Workflow quick action command: $command",
            details = mapOf("command" to command),
        )
    }
}
