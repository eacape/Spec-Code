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
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest("))
        assertFalse(source.contains("ImprovedChatPanelShellCommandDispatchCoordinator.buildTerminalLaunchPlan("))
        assertFalse(source.contains("ImprovedChatPanelShellCommandDispatchCoordinator.shouldRestoreComposerAfterTerminalEcho("))
        assertFalse(source.contains("private fun looksLikeTerminalCommandEcho("))
        assertFalse(source.contains("details = mapOf(\"command\" to normalizedCommand)"))
        assertFalse(source.contains("?: System.getProperty(\"user.home\")"))
    }
}
