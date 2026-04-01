package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelWorkflowCommandRuntimeCoordinatorContractTest {

    @Test
    fun `improved chat panel should delegate workflow command runtime branching to coordinator`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planDispatch("))
        assertTrue(source.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStop("))
        assertTrue(source.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planStopOutcome("))
        assertTrue(source.contains("ImprovedChatPanelWorkflowCommandRuntimeCoordinator.planExecutionOutcome("))
        assertTrue(source.contains("applyWorkflowCommandFeedback("))
        assertFalse(source.contains("ImprovedChatPanelWorkflowCommandRunOutcome.AlreadyRunning ->"))
        assertFalse(source.contains("ImprovedChatPanelWorkflowCommandStopOutcome.Failed ->"))
        assertFalse(source.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildFailedToStartFeedback("))
        assertFalse(source.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildCompletedFeedback("))
        assertFalse(source.contains("ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildStopFailedFeedback("))
    }
}
