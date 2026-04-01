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
        val shellExecutionCoordinatorSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanelShellCommandExecutionCoordinator.kt"),
            StandardCharsets.UTF_8,
        )
        val stopCoordinatorSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanelWorkflowCommandStopCoordinator.kt"),
            StandardCharsets.UTF_8,
        )
        val executionCoordinatorSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanelWorkflowCommandExecutionCoordinator.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(shellExecutionCoordinatorSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planDispatch("))
        assertTrue(stopCoordinatorSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStop("))
        assertTrue(stopCoordinatorSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStopOutcome("))
        assertTrue(executionCoordinatorSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planExecutionOutcome("))
        assertTrue(panelSource.contains("applyWorkflowCommandFeedback("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planDispatch("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStop("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStopOutcome("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planExecutionOutcome("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandRunOutcome.AlreadyRunning ->"))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandStopOutcome.Failed ->"))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildFailedToStartFeedback("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildCompletedFeedback("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildStopFailedFeedback("))
    }
}
