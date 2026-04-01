package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class ImprovedChatPanelWorkflowCommandStopCoordinatorTest {

    @Test
    fun `prepare stop should ignore blank command before checking running state`() {
        val runningChecked = AtomicBoolean(false)
        val coordinator = ImprovedChatPanelWorkflowCommandStopCoordinator(
            isWorkflowCommandRunning = {
                runningChecked.set(true)
                false
            },
            stopWorkflowCommand = { error("Blank command should not attempt stop") },
        )

        val stopPlan = coordinator.prepareStop("   ")

        assertNull(stopPlan)
        assertFalse(runningChecked.get())
    }

    @Test
    fun `prepare stop should return not running feedback without stop attempt`() {
        val coordinator = ImprovedChatPanelWorkflowCommandStopCoordinator(
            isWorkflowCommandRunning = { command ->
                assertEquals("gradle verify", command)
                false
            },
            stopWorkflowCommand = { error("Idle command should not attempt stop") },
        )

        val stopPlan = coordinator.prepareStop("  gradle verify  ")

        assertNotNull(stopPlan)
        assertEquals("gradle verify", stopPlan?.normalizedCommand)
        assertFalse(stopPlan?.shouldAttemptStop ?: true)
        assertEquals(true, stopPlan?.persistAsync)
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.SYSTEM,
            stopPlan?.immediateFeedback?.conversationMessageKind,
        )
    }

    @Test
    fun `prepare stop should request stop when command is active`() {
        val coordinator = ImprovedChatPanelWorkflowCommandStopCoordinator(
            isWorkflowCommandRunning = { command ->
                assertEquals("gradle verify", command)
                true
            },
            stopWorkflowCommand = { error("prepareStop should not execute stop") },
        )

        val stopPlan = coordinator.prepareStop("gradle verify")

        assertNotNull(stopPlan)
        assertTrue(stopPlan?.shouldAttemptStop == true)
        assertEquals(true, stopPlan?.persistAsync)
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.SYSTEM,
            stopPlan?.immediateFeedback?.conversationMessageKind,
        )
    }

    @Test
    fun `perform stop should ignore successful stop completion feedback`() {
        val coordinator = ImprovedChatPanelWorkflowCommandStopCoordinator(
            isWorkflowCommandRunning = { true },
            stopWorkflowCommand = { ImprovedChatPanelWorkflowCommandStopOutcome.Stopping },
        )

        val stopOutcomePlan = coordinator.performStop(
            coordinator.prepareStop("gradle verify") ?: error("Expected stop plan"),
        )

        assertNull(stopOutcomePlan)
    }

    @Test
    fun `perform stop should map failed stop to error feedback`() {
        var stoppedCommand: String? = null
        val coordinator = ImprovedChatPanelWorkflowCommandStopCoordinator(
            isWorkflowCommandRunning = { true },
            stopWorkflowCommand = { command ->
                stoppedCommand = command
                ImprovedChatPanelWorkflowCommandStopOutcome.Failed(
                    IllegalStateException("permission denied"),
                )
            },
        )

        val stopOutcomePlan = coordinator.performStop(
            coordinator.prepareStop("gradle verify") ?: error("Expected stop plan"),
        )

        assertEquals("gradle verify", stoppedCommand)
        assertNotNull(stopOutcomePlan)
        assertEquals(false, stopOutcomePlan?.persistAsync)
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.ERROR,
            stopOutcomePlan?.feedback?.conversationMessageKind,
        )
    }
}
