package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecWorkflow

internal class SpecWorkflowClarificationRetryRestoreUiHost(
    private val retryStore: SpecWorkflowClarificationRetryStore,
    private val appendTimelineEntriesUi: (List<SpecWorkflowTimelineEntry>) -> Unit,
) {

    fun syncFromWorkflow(workflow: SpecWorkflow) {
        retryStore.syncFromWorkflow(workflow)
    }

    fun restorePendingState(workflowId: String) {
        val payload = retryStore.current(workflowId) ?: return
        appendTimelineEntriesUi(buildRestoredTimelineEntries(payload))
    }

    private fun buildRestoredTimelineEntries(payload: ClarificationRetryPayload): List<SpecWorkflowTimelineEntry> {
        return buildList {
            add(
                SpecWorkflowTimelineEntry(
                    text = SpecCodingBundle.message(
                        "spec.workflow.process.retryRestored",
                        payload.clarificationRound,
                    ),
                    state = SpecWorkflowTimelineEntryState.INFO,
                ),
            )
            payload.lastError
                ?.takeIf { it.isNotBlank() }
                ?.let { error ->
                    add(
                        SpecWorkflowTimelineEntry(
                            text = SpecCodingBundle.message("spec.workflow.process.retryLastError", error),
                            state = SpecWorkflowTimelineEntryState.FAILED,
                        ),
                    )
                }
        }
    }
}
