package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecWorkflowProcessTimelineUiHostTest {

    @Test
    fun `appendEntries should map timeline states to detail panel states`() {
        val harness = Harness()

        harness.host.appendEntries(
            listOf(
                timelineEntry("Request clarification", SpecWorkflowTimelineEntryState.ACTIVE),
                timelineEntry("Clarification saved", SpecWorkflowTimelineEntryState.DONE),
                timelineEntry("Generation failed", SpecWorkflowTimelineEntryState.FAILED),
                timelineEntry("Reusing retry context", SpecWorkflowTimelineEntryState.INFO),
            ),
        )

        assertEquals(
            listOf(
                TimelineUiCall("Request clarification", SpecDetailPanel.ProcessTimelineState.ACTIVE),
                TimelineUiCall("Clarification saved", SpecDetailPanel.ProcessTimelineState.DONE),
                TimelineUiCall("Generation failed", SpecDetailPanel.ProcessTimelineState.FAILED),
                TimelineUiCall("Reusing retry context", SpecDetailPanel.ProcessTimelineState.INFO),
            ),
            harness.timelineCalls,
        )
    }

    @Test
    fun `clear should delegate to detail timeline reset`() {
        val harness = Harness()

        harness.host.clear()

        assertEquals(1, harness.clearCalls)
    }

    private class Harness {
        val timelineCalls = mutableListOf<TimelineUiCall>()
        var clearCalls = 0

        val host = SpecWorkflowProcessTimelineUiHost(
            appendTimelineEntryUi = { text, state ->
                timelineCalls += TimelineUiCall(text, state)
            },
            clearTimelineUi = {
                clearCalls += 1
            },
        )
    }

    private data class TimelineUiCall(
        val text: String,
        val state: SpecDetailPanel.ProcessTimelineState,
    )

    private companion object {
        fun timelineEntry(
            text: String,
            state: SpecWorkflowTimelineEntryState,
        ): SpecWorkflowTimelineEntry {
            return SpecWorkflowTimelineEntry(text = text, state = state)
        }
    }
}
