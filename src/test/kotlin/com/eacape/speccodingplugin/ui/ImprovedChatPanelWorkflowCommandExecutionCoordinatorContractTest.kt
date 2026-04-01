package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelWorkflowCommandExecutionCoordinatorContractTest {

    @Test
    fun `improved chat panel should delegate workflow shell background execution to coordinator`() {
        val panelSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )
        val runtimeFacadeSource = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanelShellCommandRuntimeFacade.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(panelSource.contains("private val shellCommandRuntimeFacade = ImprovedChatPanelShellCommandRuntimeFacade.create("))
        assertTrue(panelSource.contains("shellCommandRuntimeFacade.executeInBackground("))
        assertTrue(runtimeFacadeSource.contains("ImprovedChatPanelWorkflowCommandExecutionCoordinator("))
        assertTrue(runtimeFacadeSource.contains("executeInBackgroundDelegate = workflowCommandExecutionCoordinator::executeInBackground"))
        assertFalse(panelSource.contains("private val workflowCommandExecutionCoordinator = ImprovedChatPanelWorkflowCommandExecutionCoordinator("))
        assertFalse(panelSource.contains("workflowCommandRunner.execute("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planExecutionOutcome("))
        assertFalse(panelSource.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildBackgroundRunningStatus("))
        assertFalse(panelSource.contains("persistWorkflowCommandChangeset(command, execution, beforeSnapshot)"))
    }
}
