package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelComposerSubmissionContractTest {

    @Test
    fun `improved chat panel should delegate composer submission routing to coordinator`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ImprovedChatPanelComposerSubmissionCoordinator.resolve("))
        assertTrue(source.contains("ImprovedChatPanelComposerSubmissionCoordinator.appendImagePathsToPrompt("))
        assertTrue(source.contains("ImprovedChatPanelComposerSubmissionCoordinator.buildVisibleInput("))
        assertFalse(source.contains("private fun appendImagePathsToPrompt("))
        assertFalse(source.contains("private fun buildVisibleInput("))
        assertFalse(source.contains("internal fun shouldRouteToWorkflowCommand("))
    }
}
