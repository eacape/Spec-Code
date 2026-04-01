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
        val panelSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )
        val permissionCoordinatorSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanelWorkflowCommandPermissionCoordinator.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(permissionCoordinatorSource.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.resolvePermission("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.resolvePermission("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildTerminalStartedFeedback("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildTerminalUnavailableFeedback("))
        assertFalse(panelSource.contains("private fun formatWorkflowCommandExecutionSummary("))
        assertFalse(panelSource.contains("chat.workflow.action.runCommand.success"))
        assertFalse(panelSource.contains("chat.workflow.action.runCommand.failed"))
        assertFalse(panelSource.contains("chat.workflow.action.runCommand.timeout"))
        assertFalse(panelSource.contains("chat.workflow.action.stopCommand.stopped"))
    }
}
