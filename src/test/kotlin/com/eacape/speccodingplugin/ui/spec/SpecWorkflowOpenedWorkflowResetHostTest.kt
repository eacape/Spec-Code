package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpecWorkflowOpenedWorkflowResetHostTest {

    @Test
    fun `clear should reset opened workflow state without clearing highlight by default`() {
        val state = populatedState()
        val ui = Recorder()

        SpecWorkflowOpenedWorkflowResetHost(state, ui).clear()

        assertNull(state.selectedWorkflowId)
        assertEquals("wf-1", state.highlightedWorkflowId)
        assertNull(state.focusedStage)
        assertEquals(DocumentWorkspaceView.DOCUMENT, state.selectedDocumentWorkspaceView)
        assertNull(state.selectedStructuredTaskId)
        assertEquals(
            listOf(
                "clearCurrentWorkflow",
                "clearSources",
                "resetViews",
                "toolbar:false:false:false:false",
                "workspaceEmpty",
            ),
            ui.calls,
        )
    }

    @Test
    fun `clear should reset highlighted workflow when requested`() {
        val state = populatedState()
        val ui = Recorder()

        SpecWorkflowOpenedWorkflowResetHost(state, ui).clear(resetHighlight = true)

        assertNull(state.selectedWorkflowId)
        assertNull(state.highlightedWorkflowId)
        assertNull(state.focusedStage)
        assertEquals(DocumentWorkspaceView.DOCUMENT, state.selectedDocumentWorkspaceView)
        assertNull(state.selectedStructuredTaskId)
        assertEquals(
            listOf(
                "clearCurrentWorkflow",
                "clearSources",
                "resetViews",
                "toolbar:false:false:false:false",
                "clearHighlight",
                "workspaceEmpty",
            ),
            ui.calls,
        )
    }

    private fun populatedState(): SpecWorkflowPanelState {
        return SpecWorkflowPanelState().apply {
            selectedWorkflowId = "wf-1"
            highlightedWorkflowId = "wf-1"
            focusedStage = StageId.TASKS
            selectedDocumentWorkspaceView = DocumentWorkspaceView.STRUCTURED_TASKS
            selectedStructuredTaskId = "task-1"
        }
    }

    private class Recorder : SpecWorkflowOpenedWorkflowResetUi {
        val calls = mutableListOf<String>()

        override fun clearCurrentWorkflow() {
            calls += "clearCurrentWorkflow"
        }

        override fun clearCurrentWorkflowSources() {
            calls += "clearSources"
        }

        override fun resetWorkflowViewsToEmpty() {
            calls += "resetViews"
        }

        override fun applyToolbarActionAvailability(availability: SpecWorkflowToolbarActionAvailability) {
            calls += "toolbar:${availability.createWorktreeEnabled}:${availability.mergeWorktreeEnabled}:${availability.deltaEnabled}:${availability.archiveEnabled}"
        }

        override fun clearWorkflowListHighlight() {
            calls += "clearHighlight"
        }

        override fun showWorkspaceEmptyState() {
            calls += "workspaceEmpty"
        }
    }
}
