package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelShellCommandDispatchCoordinatorContractTest {

    @Test
    fun `improved chat panel should delegate shell command dispatch planning to coordinator`() {
        val panelSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )
        val executionCoordinatorSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanelShellCommandExecutionCoordinator.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(executionCoordinatorSource.contains("ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest("))
        assertFalse(panelSource.contains("ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest("))
        assertFalse(panelSource.contains("ImprovedChatPanelShellCommandDispatchCoordinator.buildTerminalLaunchPlan("))
        assertFalse(panelSource.contains("ImprovedChatPanelShellCommandDispatchCoordinator.shouldRestoreComposerAfterTerminalEcho("))
        assertFalse(panelSource.contains("private fun looksLikeTerminalCommandEcho("))
        assertFalse(panelSource.contains("details = mapOf(\"command\" to normalizedCommand)"))
        assertFalse(panelSource.contains("?: System.getProperty(\"user.home\")"))
    }
}
