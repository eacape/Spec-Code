package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImprovedChatPanelWorkflowCommandRuntimeCoordinatorTest {

    @Test
    fun `plan dispatch should launch in background when command is idle`() {
        val dispatchRequest = shellCommandRequest("gradle verify")

        val plan = ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planDispatch(
            dispatchRequest = dispatchRequest,
            alreadyRunning = false,
        )

        assertTrue(plan is ImprovedChatPanelWorkflowCommandDispatchPlan.LaunchInBackground)
        assertEquals(
            dispatchRequest,
            (plan as ImprovedChatPanelWorkflowCommandDispatchPlan.LaunchInBackground).dispatchRequest,
        )
    }

    @Test
    fun `plan dispatch should render already running feedback when command is active`() {
        val dispatchRequest = shellCommandRequest("gradle verify")

        val plan = ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planDispatch(
            dispatchRequest = dispatchRequest,
            alreadyRunning = true,
        )

        assertTrue(plan is ImprovedChatPanelWorkflowCommandDispatchPlan.RenderFeedback)
        plan as ImprovedChatPanelWorkflowCommandDispatchPlan.RenderFeedback
        assertEquals(true, plan.persistAsync)
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.SYSTEM,
            plan.feedback.conversationMessageKind,
        )
    }

    @Test
    fun `plan stop should return not running feedback without stop`() {
        val plan = ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStop(
            command = "gradle verify",
            isRunning = false,
        )

        assertFalse(plan.shouldAttemptStop)
        assertEquals(true, plan.persistAsync)
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.SYSTEM,
            plan.immediateFeedback.conversationMessageKind,
        )
    }

    @Test
    fun `plan stop should request stop and emit stopping feedback when active`() {
        val plan = ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStop(
            command = "gradle verify",
            isRunning = true,
        )

        assertTrue(plan.shouldAttemptStop)
        assertEquals(true, plan.persistAsync)
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.SYSTEM,
            plan.immediateFeedback.conversationMessageKind,
        )
    }

    @Test
    fun `plan stop outcome should only emit feedback for failed stop`() {
        assertNull(
            ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStopOutcome(
                command = "gradle verify",
                stopOutcome = ImprovedChatPanelWorkflowCommandStopOutcome.Stopping,
            ),
        )

        val failedPlan = ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStopOutcome(
            command = "gradle verify",
            stopOutcome = ImprovedChatPanelWorkflowCommandStopOutcome.Failed(
                IllegalStateException("permission denied"),
            ),
        )

        assertNotNull(failedPlan)
        assertEquals(false, failedPlan?.persistAsync)
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.ERROR,
            failedPlan?.feedback?.conversationMessageKind,
        )
    }

    @Test
    fun `plan execution outcome should map startup failure to error feedback`() {
        val plan = ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planExecutionOutcome(
            command = "gradle verify",
            outcome = ImprovedChatPanelWorkflowCommandRunOutcome.FailedToStart("shell unavailable"),
            timeoutSeconds = 1800,
            outputLimitChars = 12_000,
            displayOutput = "",
            shouldHideStatus = false,
        )

        assertEquals(false, plan.persistAsync)
        assertNull(plan.execution)
        assertFalse(plan.shouldPersistChangeset)
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.ERROR,
            plan.feedback.conversationMessageKind,
        )
        assertEquals(false, plan.feedback.operationRecordedSuccess)
    }

    @Test
    fun `plan execution outcome should keep completed execution for changeset persistence`() {
        val execution = ImprovedChatPanelWorkflowCommandExecutionResult(
            success = true,
            exitCode = 0,
            output = "verification passed",
        )

        val plan = ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planExecutionOutcome(
            command = "gradle verify",
            outcome = ImprovedChatPanelWorkflowCommandRunOutcome.Completed(execution),
            timeoutSeconds = 1800,
            outputLimitChars = 12_000,
            displayOutput = "verification passed",
            shouldHideStatus = true,
        )

        assertEquals(false, plan.persistAsync)
        assertEquals(execution, plan.execution)
        assertTrue(plan.shouldPersistChangeset)
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.SYSTEM,
            plan.feedback.conversationMessageKind,
        )
        assertEquals(true, plan.feedback.operationRecordedSuccess)
        assertTrue(plan.feedback.shouldHideStatus)
    }

    @Test
    fun `plan execution outcome should map duplicate background run to system feedback`() {
        val plan = ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planExecutionOutcome(
            command = "gradle verify",
            outcome = ImprovedChatPanelWorkflowCommandRunOutcome.AlreadyRunning,
            timeoutSeconds = 1800,
            outputLimitChars = 12_000,
            displayOutput = "",
            shouldHideStatus = false,
        )

        assertEquals(false, plan.persistAsync)
        assertNull(plan.execution)
        assertFalse(plan.shouldPersistChangeset)
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.SYSTEM,
            plan.feedback.conversationMessageKind,
        )
    }

    private fun shellCommandRequest(command: String): ImprovedChatPanelShellCommandDispatchRequest {
        return ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest(
            command = command,
            requestDescription = "Workflow quick action command: $command",
        ) ?: error("Expected dispatch request")
    }
}
