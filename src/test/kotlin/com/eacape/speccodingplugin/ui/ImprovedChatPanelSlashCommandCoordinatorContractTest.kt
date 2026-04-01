package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelSlashCommandCoordinatorContractTest {

    @Test
    fun `improved chat panel should delegate slash command routing to coordinator`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ImprovedChatPanelSlashCommandCoordinator.resolve("))
        assertTrue(source.contains("ImprovedChatPanelSlashCommandCoordinator.extractSlashCommandToken("))
        assertFalse(source.contains("private fun resolveProviderSlashCommand("))
        assertFalse(source.contains("private fun extractSlashCommandToken("))
        assertFalse(source.contains("private fun isRegisteredSkillSlashCommand("))
        assertFalse(source.contains("private fun isInteractiveOnlyProviderSlashCommand("))
        assertFalse(source.contains("private fun isInteractiveOnlySessionSlashCommand("))
    }
}
