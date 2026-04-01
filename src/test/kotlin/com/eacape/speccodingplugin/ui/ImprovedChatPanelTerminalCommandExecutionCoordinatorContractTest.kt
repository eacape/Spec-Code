package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelTerminalCommandExecutionCoordinatorContractTest {

    @Test
    fun `improved chat panel should delegate IDE terminal execution to coordinator`() {
        val panelSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )
        val runtimeFacadeSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanelShellCommandRuntimeFacade.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(panelSource.contains("private val shellCommandRuntimeFacade = ImprovedChatPanelShellCommandRuntimeFacade.create("))
        assertTrue(runtimeFacadeSource.contains("ImprovedChatPanelTerminalCommandExecutionCoordinator("))
        assertTrue(runtimeFacadeSource.contains("executeInIdeTerminal = executeInIdeTerminal"))
        assertTrue(panelSource.contains("plan.restorePlan?.let(::applyComposerRestorePlan)"))
        assertFalse(panelSource.contains("private val terminalCommandExecutionCoordinator = ImprovedChatPanelTerminalCommandExecutionCoordinator("))
        assertFalse(panelSource.contains("terminalCommandExecutionCoordinator.execute("))
        assertFalse(panelSource.contains("private fun restoreComposerIfTerminalCommandEchoed("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildTerminalStartedFeedback("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildTerminalUnavailableFeedback("))
    }
}
