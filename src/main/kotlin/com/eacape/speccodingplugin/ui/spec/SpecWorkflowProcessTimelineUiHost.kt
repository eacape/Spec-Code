package com.eacape.speccodingplugin.ui.spec

internal class SpecWorkflowProcessTimelineUiHost(
    private val appendTimelineEntryUi: (String, SpecDetailPanel.ProcessTimelineState) -> Unit,
    private val clearTimelineUi: () -> Unit,
) {

    fun clear() {
        clearTimelineUi()
    }

    fun appendEntry(entry: SpecWorkflowTimelineEntry) {
        appendTimelineEntryUi(
            entry.text,
            entry.state.toProcessTimelineState(),
        )
    }

    fun appendEntries(entries: Iterable<SpecWorkflowTimelineEntry>) {
        entries.forEach(::appendEntry)
    }

    private fun SpecWorkflowTimelineEntryState.toProcessTimelineState(): SpecDetailPanel.ProcessTimelineState {
        return when (this) {
            SpecWorkflowTimelineEntryState.ACTIVE -> SpecDetailPanel.ProcessTimelineState.ACTIVE
            SpecWorkflowTimelineEntryState.DONE -> SpecDetailPanel.ProcessTimelineState.DONE
            SpecWorkflowTimelineEntryState.FAILED -> SpecDetailPanel.ProcessTimelineState.FAILED
            SpecWorkflowTimelineEntryState.INFO -> SpecDetailPanel.ProcessTimelineState.INFO
        }
    }
}
