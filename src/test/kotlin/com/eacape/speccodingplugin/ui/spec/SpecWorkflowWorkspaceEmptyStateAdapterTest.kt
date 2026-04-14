package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecWorkflowWorkspaceEmptyStateAdapterTest {

    @Test
    fun `showEmptyState should reset workspace ui and document workspace view`() {
        val ui = Recorder()
        val adapter = SpecWorkflowWorkspaceEmptyStateAdapter(ui)

        adapter.showEmptyState()

        assertEquals(
            listOf(
                "listMode",
                "back:false",
                "emptyCard",
                "clearState",
                "stopLiveProgress",
                "clearFocus",
                "clearSummary",
                "resetSections",
                "showAllSections",
                "resetDocumentView",
            ),
            ui.calls,
        )
    }

    private class Recorder : SpecWorkflowWorkspaceEmptyStateUi {
        val calls = mutableListOf<String>()

        override fun showWorkflowListOnlyMode() {
            calls += "listMode"
        }

        override fun setBackToListEnabled(enabled: Boolean) {
            calls += "back:$enabled"
        }

        override fun showWorkspaceEmptyCard() {
            calls += "emptyCard"
        }

        override fun clearWorkspaceState() {
            calls += "clearState"
        }

        override fun stopLiveProgressRefresh() {
            calls += "stopLiveProgress"
        }

        override fun clearFocusedStage() {
            calls += "clearFocus"
        }

        override fun clearWorkspaceSummary() {
            calls += "clearSummary"
        }

        override fun resetWorkspaceSections() {
            calls += "resetSections"
        }

        override fun showAllWorkspaceSections() {
            calls += "showAllSections"
        }

        override fun resetDocumentWorkspaceView() {
            calls += "resetDocumentView"
        }
    }
}
