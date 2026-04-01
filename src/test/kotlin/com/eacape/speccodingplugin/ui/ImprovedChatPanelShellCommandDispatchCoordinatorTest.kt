package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.core.Operation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImprovedChatPanelShellCommandDispatchCoordinatorTest {

    @Test
    fun `build dispatch request should trim command and create execute operation`() {
        val request = ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest(
            command = "  npm test  ",
            requestDescription = "Workflow quick action command: npm test",
        )

        assertNotNull(request)
        assertEquals("npm test", request?.normalizedCommand)
        assertEquals("Workflow quick action command: npm test", request?.requestDescription)
        assertEquals(Operation.EXECUTE_COMMAND, request?.operationRequest?.operation)
        assertEquals("npm test", request?.operationRequest?.details?.get("command"))
    }

    @Test
    fun `build dispatch request should ignore blank command`() {
        assertNull(
            ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest(
                command = "   ",
                requestDescription = "ignored",
            ),
        )
    }

    @Test
    fun `build terminal launch plan should prefer project base path and clamp caret`() {
        val dispatchRequest = ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest(
            command = "npm test",
            requestDescription = "Workflow quick action command: npm test",
        )!!

        val plan = ImprovedChatPanelShellCommandDispatchCoordinator.buildTerminalLaunchPlan(
            dispatchRequest = dispatchRequest,
            projectBasePath = "D:/repo",
            userHome = "C:/Users/spec",
            composerText = "echo npm test",
            composerCaret = 99,
        )

        assertNotNull(plan)
        assertEquals("D:/repo", plan?.workingDirectory)
        assertEquals("echo npm test", plan?.originalComposerText)
        assertEquals("echo npm test".length, plan?.originalComposerCaret)
    }

    @Test
    fun `build terminal launch plan should fallback to user home when project path is blank`() {
        val dispatchRequest = ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest(
            command = "npm test",
            requestDescription = "Workflow quick action command: npm test",
        )!!

        val plan = ImprovedChatPanelShellCommandDispatchCoordinator.buildTerminalLaunchPlan(
            dispatchRequest = dispatchRequest,
            projectBasePath = "   ",
            userHome = "C:/Users/spec",
            composerText = "",
            composerCaret = 0,
        )

        assertNotNull(plan)
        assertEquals("C:/Users/spec", plan?.workingDirectory)
    }

    @Test
    fun `build terminal launch plan should fail when no working directory is available`() {
        val dispatchRequest = ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest(
            command = "npm test",
            requestDescription = "Workflow quick action command: npm test",
        )!!

        val plan = ImprovedChatPanelShellCommandDispatchCoordinator.buildTerminalLaunchPlan(
            dispatchRequest = dispatchRequest,
            projectBasePath = " ",
            userHome = "",
            composerText = "",
            composerCaret = 0,
        )

        assertNull(plan)
    }

    @Test
    fun `should restore composer after blank composer terminal echo`() {
        val dispatchRequest = ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest(
            command = "npm test",
            requestDescription = "Workflow quick action command: npm test",
        )!!
        val plan = ImprovedChatPanelTerminalCommandLaunchPlan(
            dispatchRequest = dispatchRequest,
            workingDirectory = "D:/repo",
            originalComposerText = "",
            originalComposerCaret = 0,
        )

        assertTrue(
            ImprovedChatPanelShellCommandDispatchCoordinator.shouldRestoreComposerAfterTerminalEcho(
                terminalPlan = plan,
                currentText = "npm test",
            ),
        )
    }

    @Test
    fun `should restore composer after command is inserted at original caret`() {
        val dispatchRequest = ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest(
            command = "npm test",
            requestDescription = "Workflow quick action command: npm test",
        )!!
        val plan = ImprovedChatPanelTerminalCommandLaunchPlan(
            dispatchRequest = dispatchRequest,
            workingDirectory = "D:/repo",
            originalComposerText = "before after",
            originalComposerCaret = 7,
        )

        assertTrue(
            ImprovedChatPanelShellCommandDispatchCoordinator.shouldRestoreComposerAfterTerminalEcho(
                terminalPlan = plan,
                currentText = "before npm testafter",
            ),
        )
    }

    @Test
    fun `should not restore composer when current text differs from terminal echo pattern`() {
        val dispatchRequest = ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest(
            command = "npm test",
            requestDescription = "Workflow quick action command: npm test",
        )!!
        val plan = ImprovedChatPanelTerminalCommandLaunchPlan(
            dispatchRequest = dispatchRequest,
            workingDirectory = "D:/repo",
            originalComposerText = "before after",
            originalComposerCaret = 7,
        )

        assertFalse(
            ImprovedChatPanelShellCommandDispatchCoordinator.shouldRestoreComposerAfterTerminalEcho(
                terminalPlan = plan,
                currentText = "before changed after",
            ),
        )
    }
}
