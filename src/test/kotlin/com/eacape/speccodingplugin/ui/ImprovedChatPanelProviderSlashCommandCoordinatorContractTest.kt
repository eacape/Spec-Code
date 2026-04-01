package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelProviderSlashCommandCoordinatorContractTest {

    @Test
    fun `improved chat panel should delegate provider slash shell planning to coordinator`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ImprovedChatPanelProviderSlashCommandCoordinator.buildExecutionPlan("))
        assertFalse(source.contains("private fun buildProviderSlashShellCommand("))
        assertFalse(source.contains("private fun quoteShellTokenIfNeeded("))
    }
}
