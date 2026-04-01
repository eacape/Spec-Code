package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImprovedChatPanelTerminalCommandExecutionCoordinatorTest {

    @Test
    fun `execute should launch IDE terminal and restore composer after command echo`() {
        var launchedCommand: String? = null
        var launchedDirectory: String? = null
        val coordinator = ImprovedChatPanelTerminalCommandExecutionCoordinator { command, workingDirectory ->
            launchedCommand = command
            launchedDirectory = workingDirectory
        }
        val dispatchRequest = dispatchRequest("npm test")

        val result = coordinator.execute(
            request = ImprovedChatPanelTerminalCommandExecutionRequest(
                dispatchRequest = dispatchRequest,
                projectBasePath = "D:/repo",
                userHome = "C:/Users/spec",
                composerText = "",
                composerCaret = 0,
            ),
            currentComposerText = { "npm test" },
        )

        assertEquals("npm test", launchedCommand)
        assertEquals("D:/repo", launchedDirectory)
        assertFalse(result.persistAsync)
        assertNull(result.launchError)
        assertEquals(dispatchRequest.operationRequest, result.operationRequest)
        assertEquals(true, result.feedback.operationRecordedSuccess)
        assertTrue(result.feedback.statusMessage.orEmpty().contains("npm test"))
        assertNull(result.feedback.conversationMessage)
        assertNotNull(result.restorePlan)
        assertEquals("", result.restorePlan?.text)
        assertEquals(0, result.restorePlan?.caret)
    }

    @Test
    fun `execute should fallback to user home when project base path is blank`() {
        var launchedDirectory: String? = null
        val coordinator = ImprovedChatPanelTerminalCommandExecutionCoordinator { _, workingDirectory ->
            launchedDirectory = workingDirectory
        }

        val result = coordinator.execute(
            request = ImprovedChatPanelTerminalCommandExecutionRequest(
                dispatchRequest = dispatchRequest("npm test"),
                projectBasePath = "   ",
                userHome = "C:/Users/spec",
                composerText = "before after",
                composerCaret = 7,
            ),
            currentComposerText = { "before changed after" },
        )

        assertEquals("C:/Users/spec", launchedDirectory)
        assertNull(result.restorePlan)
        assertNull(result.launchError)
    }

    @Test
    fun `execute should render terminal unavailable feedback when no working directory exists`() {
        var launched = false
        val coordinator = ImprovedChatPanelTerminalCommandExecutionCoordinator { _, _ ->
            launched = true
        }

        val result = coordinator.execute(
            request = ImprovedChatPanelTerminalCommandExecutionRequest(
                dispatchRequest = dispatchRequest("npm test"),
                projectBasePath = " ",
                userHome = "",
                composerText = "",
                composerCaret = 0,
            ),
            currentComposerText = { error("should not query composer text when launch plan is unavailable") },
        )

        assertFalse(launched)
        assertTrue(result.persistAsync)
        assertNull(result.launchError)
        assertNull(result.restorePlan)
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.ERROR,
            result.feedback.conversationMessageKind,
        )
        assertTrue(result.feedback.conversationMessage.orEmpty().contains("npm test"))
    }

    @Test
    fun `execute should keep restore plan and surface launch error on terminal failure`() {
        val launchError = IllegalStateException("terminal unavailable")
        val coordinator = ImprovedChatPanelTerminalCommandExecutionCoordinator { _, _ ->
            throw launchError
        }

        val result = coordinator.execute(
            request = ImprovedChatPanelTerminalCommandExecutionRequest(
                dispatchRequest = dispatchRequest("npm test"),
                projectBasePath = "D:/repo",
                userHome = "C:/Users/spec",
                composerText = "before after",
                composerCaret = 7,
            ),
            currentComposerText = { "before npm testafter" },
        )

        assertTrue(result.persistAsync)
        assertSame(launchError, result.launchError)
        assertNotNull(result.restorePlan)
        assertEquals("before after", result.restorePlan?.text)
        assertEquals(7, result.restorePlan?.caret)
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.ERROR,
            result.feedback.conversationMessageKind,
        )
        assertTrue(result.feedback.conversationMessage.orEmpty().contains("npm test"))
    }

    private fun dispatchRequest(command: String): ImprovedChatPanelShellCommandDispatchRequest {
        return ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest(
            command = command,
            requestDescription = "Workflow quick action command: $command",
        )!!
    }
}
