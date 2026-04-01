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

        assertTrue(panelSource.contains("private val shellCommandExecutionCoordinator = ImprovedChatPanelShellCommandExecutionCoordinator("))
        assertTrue(panelSource.contains("shellCommandExecutionCoordinator.execute("))
        assertTrue(panelSource.contains("private fun applyShellCommandExecutionPlan("))
        assertFalse(panelSource.contains("private fun executeShellCommandInIdeTerminal("))
    }
}
