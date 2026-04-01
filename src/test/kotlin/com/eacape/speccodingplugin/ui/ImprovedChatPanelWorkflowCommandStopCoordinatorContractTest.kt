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
        val runtimeFacadeSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanelShellCommandRuntimeFacade.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(panelSource.contains("private val shellCommandRuntimeFacade = ImprovedChatPanelShellCommandRuntimeFacade.create("))
        assertTrue(panelSource.contains("shellCommandRuntimeFacade.prepareStop(command)"))
        assertTrue(panelSource.contains("shellCommandRuntimeFacade.performStop(stopPlan)"))
        assertTrue(runtimeFacadeSource.contains("ImprovedChatPanelWorkflowCommandStopCoordinator("))
        assertTrue(runtimeFacadeSource.contains("prepareStopDelegate = workflowCommandStopCoordinator::prepareStop"))
        assertTrue(runtimeFacadeSource.contains("performStopDelegate = workflowCommandStopCoordinator::performStop"))
        assertFalse(panelSource.contains("private val workflowCommandStopCoordinator = ImprovedChatPanelWorkflowCommandStopCoordinator("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStop("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStopOutcome("))
        assertFalse(panelSource.contains("workflowCommandRunner.stop(normalizedCommand)"))
    }
}
