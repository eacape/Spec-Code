package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelWorkflowCommandStopCoordinatorContractTest {

    @Test
    fun `improved chat panel should delegate workflow shell stop handling to coordinator`() {
        val panelSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(panelSource.contains("private val workflowCommandStopCoordinator = ImprovedChatPanelWorkflowCommandStopCoordinator("))
        assertTrue(panelSource.contains("workflowCommandStopCoordinator.prepareStop("))
        assertTrue(panelSource.contains("workflowCommandStopCoordinator.performStop(stopPlan)"))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStop("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStopOutcome("))
        assertFalse(panelSource.contains("workflowCommandRunner.stop(normalizedCommand)"))
    }
}
