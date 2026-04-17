package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ClarificationRetryState
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowClarificationRetryRestoreUiHostTest {

    @Test
    fun `syncFromWorkflow and restorePendingState should append restored timeline entries`() {
        val harness = Harness()
        val workflow = workflow(
            workflowId = "wf-restore",
            clarificationRetryState = ClarificationRetryState(
                input = "Clarify requirements",
                confirmedContext = "Reuse previous notes",
                questionsMarkdown = "## Questions",
                clarificationRound = 3,
                lastError = "provider timeout",
            ),
        )

        harness.host.syncFromWorkflow(workflow)
        harness.host.restorePendingState(workflow.id)

        assertEquals(
            listOf(
                SpecWorkflowTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.retryRestored", 3),
                    state = SpecWorkflowTimelineEntryState.INFO,
                ),
                SpecWorkflowTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.retryLastError", "provider timeout"),
                    state = SpecWorkflowTimelineEntryState.FAILED,
                ),
            ),
            harness.timelineEntries,
        )
    }

    @Test
    fun `restorePendingState should omit blank last error entries`() {
        val harness = Harness()
        val workflow = workflow(
            workflowId = "wf-blank-error",
            clarificationRetryState = ClarificationRetryState(
                input = "Clarify requirements",
                confirmedContext = "Reuse previous notes",
                questionsMarkdown = "## Questions",
                clarificationRound = 2,
                lastError = "  ",
            ),
        )

        harness.host.syncFromWorkflow(workflow)
        harness.host.restorePendingState(workflow.id)

        assertEquals(
            listOf(
                SpecWorkflowTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.retryRestored", 2),
                    state = SpecWorkflowTimelineEntryState.INFO,
                ),
            ),
            harness.timelineEntries,
        )
    }

    @Test
    fun `restorePendingState should ignore workflows without retry payload`() {
        val harness = Harness()

        harness.host.restorePendingState("wf-missing")

        assertTrue(harness.timelineEntries.isEmpty())
    }

    private class Harness {
        val timelineEntries = mutableListOf<SpecWorkflowTimelineEntry>()
        val retryStore = SpecWorkflowClarificationRetryStore { _, _ -> }
        val host = SpecWorkflowClarificationRetryRestoreUiHost(
            retryStore = retryStore,
            appendTimelineEntriesUi = { entries ->
                timelineEntries += entries
            },
        )
    }

    private companion object {
        fun workflow(
            workflowId: String,
            clarificationRetryState: ClarificationRetryState?,
        ): SpecWorkflow {
            return SpecWorkflow(
                id = workflowId,
                currentPhase = SpecPhase.SPECIFY,
                documents = mapOf(
                    SpecPhase.SPECIFY to SpecDocument(
                        id = "$workflowId-specify",
                        phase = SpecPhase.SPECIFY,
                        content = "",
                        metadata = SpecMetadata(
                            title = "requirements.md",
                            description = "",
                        ),
                    ),
                ),
                status = WorkflowStatus.IN_PROGRESS,
                clarificationRetryState = clarificationRetryState,
            )
        }
    }
}
