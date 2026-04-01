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
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("private val terminalCommandExecutionCoordinator = ImprovedChatPanelTerminalCommandExecutionCoordinator("))
        assertTrue(source.contains("terminalCommandExecutionCoordinator.execute("))
        assertTrue(source.contains("executionResult.restorePlan?.let(::applyComposerRestorePlan)"))
        assertFalse(source.contains("private fun restoreComposerIfTerminalCommandEchoed("))
        assertFalse(source.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildTerminalStartedFeedback("))
        assertFalse(source.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildTerminalUnavailableFeedback("))
    }
}
