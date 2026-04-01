package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelShellCommandExecutionCoordinatorContractTest {

    @Test
    fun `improved chat panel should delegate shell execution preparation to unified coordinator`() {
        val panelSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )
        val runtimeFacadeSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanelShellCommandRuntimeFacade.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(panelSource.contains("private val shellCommandRuntimeFacade = ImprovedChatPanelShellCommandRuntimeFacade.create("))
        assertTrue(panelSource.contains("private val workflowCommandPermissionCoordinator = ImprovedChatPanelWorkflowCommandPermissionCoordinator("))
        assertTrue(runtimeFacadeSource.contains("ImprovedChatPanelShellCommandExecutionCoordinator("))
        assertTrue(runtimeFacadeSource.contains("authorizeCommandExecution = authorizeCommandExecution"))
        assertTrue(panelSource.contains("shellCommandRuntimeFacade.prepareExecution("))
        assertTrue(panelSource.contains("private fun applyShellCommandExecutionPlan("))
        assertFalse(panelSource.contains("private val shellCommandExecutionCoordinator = ImprovedChatPanelShellCommandExecutionCoordinator("))
        assertFalse(panelSource.contains("private fun checkWorkflowCommandPermission("))
        assertFalse(panelSource.contains("private fun executeShellCommandInIdeTerminal("))
    }
}
