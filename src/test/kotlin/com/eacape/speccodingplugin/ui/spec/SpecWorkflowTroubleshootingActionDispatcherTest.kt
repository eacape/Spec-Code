package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecWorkflowTroubleshootingActionDispatcherTest {

    @Test
    fun `perform should open settings without triggering entry refresh`() {
        val callbacks = RecordingCallbacks()
        val dispatcher = SpecWorkflowTroubleshootingActionDispatcher(callbacks)

        dispatcher.perform(
            SpecWorkflowTroubleshootingAction.OpenSettings(label = "Open settings"),
        )

        assertEquals(listOf("settings"), callbacks.events)
    }

    @Test
    fun `perform should open bundled demo without changing selected entry`() {
        val callbacks = RecordingCallbacks()
        val dispatcher = SpecWorkflowTroubleshootingActionDispatcher(callbacks)

        dispatcher.perform(
            SpecWorkflowTroubleshootingAction.OpenBundledDemo(label = "Open bundled demo"),
        )

        assertEquals(listOf("demo"), callbacks.events)
    }

    @Test
    fun `perform should switch entry before refreshing form state`() {
        val callbacks = RecordingCallbacks()
        val dispatcher = SpecWorkflowTroubleshootingActionDispatcher(callbacks)

        dispatcher.perform(
            SpecWorkflowTroubleshootingAction.SelectEntry(
                entry = SpecWorkflowPrimaryEntry.FULL_SPEC,
                label = "Switch to full spec",
            ),
        )

        assertEquals(
            listOf("entry:FULL_SPEC", "refresh"),
            callbacks.events,
        )
    }

    private class RecordingCallbacks : SpecWorkflowTroubleshootingActionDispatcher.Callbacks {
        val events = mutableListOf<String>()

        override fun openSettings() {
            events += "settings"
        }

        override fun openBundledDemo() {
            events += "demo"
        }

        override fun selectEntry(entry: SpecWorkflowPrimaryEntry) {
            events += "entry:$entry"
        }

        override fun refreshAfterEntrySelection() {
            events += "refresh"
        }
    }
}
