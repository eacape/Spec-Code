package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelWorkflowCommandRunnerContractTest {

    @Test
    fun `improved chat panel should delegate workflow command runtime to shared runner`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("private val workflowCommandRunner = ImprovedChatPanelWorkflowCommandRunner("))
        assertTrue(source.contains("workflowCommandRunner.execute("))
        assertTrue(source.contains("workflowCommandRunner.stop(normalizedCommand)"))
        assertTrue(source.contains("workflowCommandRunner.dispose()"))
        assertFalse(source.contains("private val runningWorkflowCommands ="))
        assertFalse(source.contains("private fun startWorkflowShellCommand("))
        assertFalse(source.contains("private fun buildShellCommand("))
        assertFalse(source.contains("private data class RunningWorkflowCommand("))
        assertFalse(source.contains("ProcessBuilder(buildShellCommand(command))"))
    }
}
