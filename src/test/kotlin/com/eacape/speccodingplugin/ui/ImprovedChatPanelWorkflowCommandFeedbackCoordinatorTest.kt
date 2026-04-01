package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.Operation
import com.eacape.speccodingplugin.core.OperationRequest
import com.eacape.speccodingplugin.core.OperationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImprovedChatPanelWorkflowCommandFeedbackCoordinatorTest {

    @Test
    fun `resolve permission should build denied feedback with current mode`() {
        val decision = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.resolvePermission(
            result = OperationResult.Denied("dangerous command"),
            currentModeDisplayName = "Agent",
            request = workflowCommandRequest("rm -rf"),
            command = "rm -rf",
        )

        assertTrue(decision is ImprovedChatPanelWorkflowCommandPermissionDecision.Denied)
        assertEquals(
            SpecCodingBundle.message("toolwindow.mode.operation.denied", "Agent", "dangerous command"),
            (decision as ImprovedChatPanelWorkflowCommandPermissionDecision.Denied).errorMessage,
        )
    }

    @Test
    fun `resolve permission should build confirmation prompt and accepted system message`() {
        val request = workflowCommandRequest("gradle verify")
        val decision = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.resolvePermission(
            result = OperationResult.RequiresConfirmation(request),
            currentModeDisplayName = "Default",
            request = request,
            command = "gradle verify",
        )

        assertTrue(decision is ImprovedChatPanelWorkflowCommandPermissionDecision.RequiresConfirmation)
        decision as ImprovedChatPanelWorkflowCommandPermissionDecision.RequiresConfirmation
        assertEquals(
            SpecCodingBundle.message("chat.workflow.action.runCommand.confirm.title"),
            decision.title,
        )
        assertEquals(
            SpecCodingBundle.message("chat.workflow.action.runCommand.confirm.message", "gradle verify"),
            decision.message,
        )
        assertEquals(
            SpecCodingBundle.message("toolwindow.mode.confirmation.accepted", Operation.EXECUTE_COMMAND.name),
            decision.acceptedSystemMessage,
        )
    }

    @Test
    fun `build terminal started feedback should only show status and record success`() {
        val feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildTerminalStartedFeedback("gradle verify")

        assertEquals(
            SpecCodingBundle.message("chat.workflow.action.runCommand.startedTerminal", "gradle verify"),
            feedback.statusMessage,
        )
        assertEquals(true, feedback.operationRecordedSuccess)
        assertEquals(null, feedback.conversationMessage)
        assertEquals(false, feedback.shouldPersistChangeset)
    }

    @Test
    fun `build completed feedback should mark success and append truncation notice`() {
        val feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildCompletedFeedback(
            command = "gradle verify",
            execution = ImprovedChatPanelWorkflowCommandExecutionResult(
                success = true,
                exitCode = 0,
                output = "verification passed",
                outputTruncated = true,
            ),
            timeoutSeconds = 1800,
            outputLimitChars = 120,
            displayOutput = "verification passed",
            shouldHideStatus = true,
        )

        assertEquals(ImprovedChatPanelWorkflowCommandFeedbackMessageKind.SYSTEM, feedback.conversationMessageKind)
        assertEquals(true, feedback.operationRecordedSuccess)
        assertEquals(true, feedback.shouldHideStatus)
        assertEquals(true, feedback.shouldPersistChangeset)
        assertTrue(feedback.conversationMessage!!.contains("✅"))
        assertTrue(feedback.conversationMessage!!.contains("verification passed"))
        assertTrue(
            feedback.conversationMessage!!.contains(
                SpecCodingBundle.message("chat.workflow.action.runCommand.outputTruncated", 120),
            ),
        )
    }

    @Test
    fun `build completed feedback should mark timeout as error and fall back to no output`() {
        val feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildCompletedFeedback(
            command = "gradle verify",
            execution = ImprovedChatPanelWorkflowCommandExecutionResult(
                success = false,
                timedOut = true,
                output = "",
            ),
            timeoutSeconds = 45,
            outputLimitChars = 120,
            displayOutput = "",
            shouldHideStatus = false,
        )

        assertEquals(ImprovedChatPanelWorkflowCommandFeedbackMessageKind.ERROR, feedback.conversationMessageKind)
        assertEquals(false, feedback.operationRecordedSuccess)
        assertFalse(feedback.shouldHideStatus)
        assertTrue(feedback.conversationMessage!!.contains("⏱"))
        assertTrue(
            feedback.conversationMessage!!.contains(
                SpecCodingBundle.message("chat.workflow.action.runCommand.timeout", 45, "gradle verify"),
            ),
        )
        assertTrue(
            feedback.conversationMessage!!.contains(
                SpecCodingBundle.message("chat.workflow.action.runCommand.noOutput"),
            ),
        )
    }

    @Test
    fun `build failed to start feedback should return error summary`() {
        val feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildFailedToStartFeedback(
            command = "gradle verify",
            startupErrorMessage = "shell unavailable",
        )

        assertEquals(ImprovedChatPanelWorkflowCommandFeedbackMessageKind.ERROR, feedback.conversationMessageKind)
        assertEquals(false, feedback.operationRecordedSuccess)
        assertTrue(feedback.conversationMessage!!.contains("⚠"))
        assertTrue(feedback.conversationMessage!!.contains("shell unavailable"))
        assertFalse(feedback.shouldPersistChangeset)
    }

    @Test
    fun `build stop failed feedback should fall back to unknown error`() {
        val feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildStopFailedFeedback(
            command = "gradle verify",
            errorDetail = null,
        )

        assertEquals(ImprovedChatPanelWorkflowCommandFeedbackMessageKind.ERROR, feedback.conversationMessageKind)
        assertTrue(
            feedback.conversationMessage!!.contains(
                SpecCodingBundle.message("chat.workflow.action.runCommand.error", "gradle verify"),
            ),
        )
        assertTrue(feedback.conversationMessage!!.contains(SpecCodingBundle.message("common.unknown")))
    }

    private fun workflowCommandRequest(command: String): OperationRequest {
        return OperationRequest(
            operation = Operation.EXECUTE_COMMAND,
            description = "Workflow quick action command: $command",
            details = mapOf("command" to command),
        )
    }
}
