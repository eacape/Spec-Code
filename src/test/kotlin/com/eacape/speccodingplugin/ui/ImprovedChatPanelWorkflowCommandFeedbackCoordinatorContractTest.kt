package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelWorkflowCommandFeedbackCoordinatorContractTest {

    @Test
    fun `improved chat panel should delegate workflow command feedback and permission decisions`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.resolvePermission("))
        assertTrue(source.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildTerminalStartedFeedback("))
        assertTrue(source.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildTerminalUnavailableFeedback("))
        assertFalse(source.contains("private fun formatWorkflowCommandExecutionSummary("))
        assertFalse(source.contains("chat.workflow.action.runCommand.success"))
        assertFalse(source.contains("chat.workflow.action.runCommand.failed"))
        assertFalse(source.contains("chat.workflow.action.runCommand.timeout"))
        assertFalse(source.contains("chat.workflow.action.stopCommand.stopped"))
    }
}
