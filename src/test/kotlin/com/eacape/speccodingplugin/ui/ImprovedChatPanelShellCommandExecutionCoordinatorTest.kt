package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class ImprovedChatPanelShellCommandExecutionCoordinatorTest {

    @Test
    fun `execute should ignore blank command before permission check`() {
        val permissionChecked = AtomicBoolean(false)
        val coordinator = ImprovedChatPanelShellCommandExecutionCoordinator(
            authorizeCommandExecution = { _, _ ->
                permissionChecked.set(true)
                true
            },
            isWorkflowCommandRunning = { error("Should not query running state for blank command") },
            executeTerminalCommand = { _, _ -> error("Should not launch terminal for blank command") },
        )

        val result = coordinator.execute(
            ImprovedChatPanelShellCommandExecutionRequest(
                command = "   ",
                requestDescription = "ignored",
                target = ImprovedChatPanelShellCommandExecutionTarget.Background,
            ),
        )

        assertSame(ImprovedChatPanelShellCommandExecutionPlan.NoOp, result)
        assertTrue(!permissionChecked.get())
    }

    @Test
    fun `execute should stop when permission is denied`() {
        val runningChecked = AtomicBoolean(false)
        val coordinator = ImprovedChatPanelShellCommandExecutionCoordinator(
            authorizeCommandExecution = { _, _ -> false },
            isWorkflowCommandRunning = {
                runningChecked.set(true)
                false
            },
            executeTerminalCommand = { _, _ -> error("Should not launch terminal when permission is denied") },
        )

        val result = coordinator.execute(
            ImprovedChatPanelShellCommandExecutionRequest(
                command = "npm test",
                requestDescription = "Workflow quick action command: npm test",
                target = ImprovedChatPanelShellCommandExecutionTarget.Background,
            ),
        )

        assertSame(ImprovedChatPanelShellCommandExecutionPlan.NoOp, result)
        assertTrue(!runningChecked.get())
    }

    @Test
    fun `execute should launch background workflow command when allowed and not running`() {
        val coordinator = ImprovedChatPanelShellCommandExecutionCoordinator(
            authorizeCommandExecution = { _, _ -> true },
            isWorkflowCommandRunning = { command ->
                assertEquals("npm test", command)
                false
            },
            executeTerminalCommand = { _, _ -> error("Background command should not launch IDE terminal") },
        )

        val result = coordinator.execute(
            ImprovedChatPanelShellCommandExecutionRequest(
                command = "  npm test  ",
                requestDescription = "Workflow quick action command: npm test",
                target = ImprovedChatPanelShellCommandExecutionTarget.Background,
            ),
        )

        assertTrue(result is ImprovedChatPanelShellCommandExecutionPlan.LaunchInBackground)
        val launchPlan = result as ImprovedChatPanelShellCommandExecutionPlan.LaunchInBackground
        assertEquals("npm test", launchPlan.dispatchRequest.normalizedCommand)
        assertEquals(
            "Workflow quick action command: npm test",
            launchPlan.dispatchRequest.requestDescription,
        )
    }

    @Test
    fun `execute should convert already running background command to immediate feedback`() {
        val coordinator = ImprovedChatPanelShellCommandExecutionCoordinator(
            authorizeCommandExecution = { _, _ -> true },
            isWorkflowCommandRunning = { true },
            executeTerminalCommand = { _, _ -> error("Already running background command should not launch terminal") },
        )

        val result = coordinator.execute(
            ImprovedChatPanelShellCommandExecutionRequest(
                command = "npm test",
                requestDescription = "Workflow quick action command: npm test",
                target = ImprovedChatPanelShellCommandExecutionTarget.Background,
            ),
        )

        assertTrue(result is ImprovedChatPanelShellCommandExecutionPlan.ApplyImmediateResult)
        val feedbackPlan = result as ImprovedChatPanelShellCommandExecutionPlan.ApplyImmediateResult
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.SYSTEM,
            feedbackPlan.feedback.conversationMessageKind,
        )
        assertTrue(feedbackPlan.feedback.conversationMessage.orEmpty().contains("npm test"))
        assertNull(feedbackPlan.restorePlan)
    }

    @Test
    fun `execute should delegate IDE terminal requests and preserve restore plan`() {
        var executedRequest: ImprovedChatPanelTerminalCommandExecutionRequest? = null
        var observedComposerText: String? = null
        val dispatchRequest = ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest(
            command = "npm test",
            requestDescription = "Workflow quick action command: npm test",
        )!!
        val terminalResult = ImprovedChatPanelTerminalCommandExecutionResult(
            feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildTerminalStartedFeedback("npm test"),
            persistAsync = false,
            operationRequest = dispatchRequest.operationRequest,
            restorePlan = ImprovedChatPanelComposerRestorePlan(text = "before", caret = 3),
        )
        val coordinator = ImprovedChatPanelShellCommandExecutionCoordinator(
            authorizeCommandExecution = { _, _ -> true },
            isWorkflowCommandRunning = { error("IDE terminal path should not query background running state") },
            executeTerminalCommand = { request, currentComposerText ->
                executedRequest = request
                observedComposerText = currentComposerText()
                terminalResult
            },
        )

        val result = coordinator.execute(
            ImprovedChatPanelShellCommandExecutionRequest(
                command = "npm test",
                requestDescription = "Workflow quick action command: npm test",
                target = ImprovedChatPanelShellCommandExecutionTarget.IdeTerminal(
                    projectBasePath = "D:/repo",
                    userHome = "C:/Users/spec",
                    composerText = "before",
                    composerCaret = 3,
                    currentComposerText = { "before npm test" },
                ),
            ),
        )

        assertTrue(result is ImprovedChatPanelShellCommandExecutionPlan.ApplyImmediateResult)
        val feedbackPlan = result as ImprovedChatPanelShellCommandExecutionPlan.ApplyImmediateResult
        assertEquals("npm test", executedRequest?.dispatchRequest?.normalizedCommand)
        assertEquals("D:/repo", executedRequest?.projectBasePath)
        assertEquals("C:/Users/spec", executedRequest?.userHome)
        assertEquals("before", executedRequest?.composerText)
        assertEquals(3, executedRequest?.composerCaret)
        assertEquals("before npm test", observedComposerText)
        assertEquals("before", feedbackPlan.restorePlan?.text)
        assertEquals(3, feedbackPlan.restorePlan?.caret)
    }
}
