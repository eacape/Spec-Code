package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelWorkflowCommandRuntimeCoordinatorContractTest {

    @Test
    fun `improved chat panel should delegate workflow command runtime branching to coordinator`() {
        val panelSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )
        val executionCoordinatorSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanelWorkflowCommandExecutionCoordinator.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(panelSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planDispatch("))
        assertTrue(panelSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStop("))
        assertTrue(panelSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStopOutcome("))
        assertTrue(executionCoordinatorSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planExecutionOutcome("))
        assertTrue(panelSource.contains("applyWorkflowCommandFeedback("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planExecutionOutcome("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandRunOutcome.AlreadyRunning ->"))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandStopOutcome.Failed ->"))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildFailedToStartFeedback("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildCompletedFeedback("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildStopFailedFeedback("))
    }
}
