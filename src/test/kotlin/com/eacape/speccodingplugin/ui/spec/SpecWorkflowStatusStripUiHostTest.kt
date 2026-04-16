package com.eacape.speccodingplugin.ui.spec

import java.awt.Color
import javax.swing.JButton
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowStatusStripUiHostTest {

    @Test
    fun `apply should render status text and troubleshooting actions`() {
        val recorder = RecordingEnvironment()
        val host = host(recorder)

        host.apply(
            SpecWorkflowStatusPresentation(
                text = "Generation failed",
                actions = listOf(
                    SpecWorkflowTroubleshootingAction.OpenSettings(label = "Open settings"),
                    SpecWorkflowTroubleshootingAction.OpenBundledDemo(label = "Open demo"),
                ),
            ),
        )

        assertEquals("Generation failed", host.currentStatusTextForTest())
        assertEquals(listOf("Open settings", "Open demo"), host.currentStatusActionLabelsForTest())
        assertTrue(host.statusChipPanel.isVisible)
        assertTrue(host.statusActionPanel.isVisible)
        assertEquals(listOf("Open settings", "Open demo"), recorder.styledButtons)
    }

    @Test
    fun `apply should clear troubleshooting actions and keep chip visible for plain status`() {
        val host = host(RecordingEnvironment())
        host.apply(
            SpecWorkflowStatusPresentation(
                text = "Verify failed",
                actions = listOf(
                    SpecWorkflowTroubleshootingAction.OpenSettings(label = "Open settings"),
                ),
            ),
        )

        host.apply(
            SpecWorkflowStatusPresentation(
                text = "Verify pending",
                actions = emptyList(),
            ),
        )

        assertEquals("Verify pending", host.currentStatusTextForTest())
        assertTrue(host.statusChipPanel.isVisible)
        assertFalse(host.statusActionPanel.isVisible)
        assertTrue(host.currentStatusActionLabelsForTest().isEmpty())
    }

    @Test
    fun `apply should hide chip and clear actions when status text is blank`() {
        val host = host(RecordingEnvironment())
        host.apply(
            SpecWorkflowStatusPresentation(
                text = "Task failed",
                actions = listOf(
                    SpecWorkflowTroubleshootingAction.OpenSettings(label = "Open settings"),
                ),
            ),
        )

        host.apply(
            SpecWorkflowStatusPresentation(
                text = "",
                actions = emptyList(),
            ),
        )

        assertEquals("", host.currentStatusTextForTest())
        assertFalse(host.statusChipPanel.isVisible)
        assertFalse(host.statusActionPanel.isVisible)
        assertTrue(host.currentStatusActionLabelsForTest().isEmpty())
    }

    @Test
    fun `action button click should delegate to troubleshooting dispatcher`() {
        val recorder = RecordingEnvironment()
        val host = host(recorder)
        val action = SpecWorkflowTroubleshootingAction.OpenSettings(label = "Open settings")
        host.apply(
            SpecWorkflowStatusPresentation(
                text = "Runtime failed",
                actions = listOf(action),
            ),
        )

        (host.statusActionPanel.components.single() as JButton).doClick()

        assertEquals(listOf(action), recorder.performedActions)
    }

    private fun host(recorder: RecordingEnvironment): SpecWorkflowStatusStripUiHost {
        return SpecWorkflowStatusStripUiHost(
            statusChipBackground = Color(1, 2, 3),
            statusChipBorder = Color(4, 5, 6),
            statusTextColor = Color(7, 8, 9),
            styleActionButton = { button ->
                recorder.styledButtons += button.text.orEmpty()
            },
            performAction = { action ->
                recorder.performedActions += action
            },
        )
    }

    private class RecordingEnvironment {
        val styledButtons = mutableListOf<String>()
        val performedActions = mutableListOf<SpecWorkflowTroubleshootingAction>()
    }
}
