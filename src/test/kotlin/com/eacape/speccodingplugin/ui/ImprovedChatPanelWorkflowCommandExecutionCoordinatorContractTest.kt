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
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("private val workflowCommandExecutionCoordinator = ImprovedChatPanelWorkflowCommandExecutionCoordinator("))
        assertTrue(source.contains("workflowCommandExecutionCoordinator.executeInBackground("))
        assertFalse(source.contains("workflowCommandRunner.execute("))
        assertFalse(source.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planExecutionOutcome("))
        assertFalse(source.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildBackgroundRunningStatus("))
        assertFalse(source.contains("persistWorkflowCommandChangeset(command, execution, beforeSnapshot)"))
    }
}
