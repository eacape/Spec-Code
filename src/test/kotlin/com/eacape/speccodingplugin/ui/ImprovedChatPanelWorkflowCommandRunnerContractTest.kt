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
        val panelSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )
        val runtimeFacadeSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanelShellCommandRuntimeFacade.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(panelSource.contains("private val shellCommandRuntimeFacade = ImprovedChatPanelShellCommandRuntimeFacade.create("))
        assertTrue(panelSource.contains("shellCommandRuntimeFacade.dispose()"))
        assertTrue(runtimeFacadeSource.contains("val workflowCommandRunner = ImprovedChatPanelWorkflowCommandRunner("))
        assertTrue(runtimeFacadeSource.contains("disposeRuntime = workflowCommandRunner::dispose"))
        assertFalse(panelSource.contains("private val workflowCommandRunner = ImprovedChatPanelWorkflowCommandRunner("))
        assertFalse(panelSource.contains("workflowCommandRunner.execute("))
        assertFalse(panelSource.contains("workflowCommandRunner.stop(normalizedCommand)"))
        assertFalse(panelSource.contains("workflowCommandRunner.dispose()"))
        assertFalse(panelSource.contains("private val runningWorkflowCommands ="))
        assertFalse(panelSource.contains("private fun startWorkflowShellCommand("))
        assertFalse(panelSource.contains("private fun buildShellCommand("))
        assertFalse(panelSource.contains("private data class RunningWorkflowCommand("))
        assertFalse(panelSource.contains("ProcessBuilder(buildShellCommand(command))"))
    }
}
