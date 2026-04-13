package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowSelectionCoordinatorTest {

    private val coordinator = SpecWorkflowSelectionCoordinator()

    @Test
    fun `resolveRefresh should prioritize requested workflow and keep it highlighted`() {
        val decision = coordinator.resolveRefresh(
            SpecWorkflowSelectionRefreshRequest(
                items = listOf(item("wf-a"), item("wf-b")),
                selectWorkflowId = " wf-b ",
                selectedWorkflowId = "wf-a",
                highlightedWorkflowId = "wf-a",
            ),
        )

        assertEquals(linkedSetOf("wf-a", "wf-b"), decision.validWorkflowIds)
        assertEquals("wf-b", decision.targetOpenedWorkflowId)
        assertEquals("wf-b", decision.targetHighlightedWorkflowId)
        assertTrue(decision.switchWorkflowEnabled)
        assertFalse(decision.shouldClearOpenedWorkflow)
    }

    @Test
    fun `resolveRefresh should preserve list mode without reopening workflow`() {
        val decision = coordinator.resolveRefresh(
            SpecWorkflowSelectionRefreshRequest(
                items = listOf(item("wf-a"), item("wf-b")),
                selectedWorkflowId = "wf-missing",
                highlightedWorkflowId = "wf-b",
                preserveListMode = true,
            ),
        )

        assertEquals(null, decision.targetOpenedWorkflowId)
        assertEquals("wf-b", decision.targetHighlightedWorkflowId)
        assertTrue(decision.shouldClearOpenedWorkflow)
        assertFalse(decision.resetHighlightOnClear)
    }

    @Test
    fun `resolveRefresh should fallback to first workflow when nothing valid is selected`() {
        val decision = coordinator.resolveRefresh(
            SpecWorkflowSelectionRefreshRequest(
                items = listOf(item("wf-a"), item("wf-b")),
                selectedWorkflowId = "wf-missing",
                highlightedWorkflowId = "wf-missing",
            ),
        )

        assertEquals("wf-a", decision.targetOpenedWorkflowId)
        assertEquals("wf-a", decision.targetHighlightedWorkflowId)
    }

    @Test
    fun `resolveDeleteRefreshTarget should reopen preserved selection when deleting another workflow`() {
        val target = coordinator.resolveDeleteRefreshTarget(
            workflowId = "wf-delete",
            currentItems = listOf(item("wf-keep"), item("wf-delete"), item("wf-other")),
            selectedWorkflowId = "wf-keep",
        )

        assertEquals(
            SpecWorkflowSelectionRefreshTarget(
                selectWorkflowId = "wf-keep",
                preserveListMode = false,
            ),
            target,
        )
    }

    @Test
    fun `resolveDeleteRefreshTarget should keep list mode when deleting an unfocused workflow`() {
        val target = coordinator.resolveDeleteRefreshTarget(
            workflowId = "wf-delete",
            currentItems = listOf(item("wf-a"), item("wf-delete")),
            selectedWorkflowId = null,
        )

        assertEquals(
            SpecWorkflowSelectionRefreshTarget(
                selectWorkflowId = null,
                preserveListMode = true,
            ),
            target,
        )
    }

    @Test
    fun `focus should clear opened workflow only when user focuses another workflow`() {
        val callbacks = RecordingCallbacks()

        coordinator.focus(
            workflowId = " wf-b ",
            selectedWorkflowId = "wf-a",
            callbacks = callbacks,
        )

        assertEquals(listOf("highlight:wf-b", "clear:false"), callbacks.events)
    }

    @Test
    fun `open should highlight load and publish normalized workflow id`() {
        val callbacks = RecordingCallbacks()

        coordinator.open(" wf-open ", callbacks)

        assertEquals(
            listOf(
                "highlight:wf-open",
                "load:wf-open",
                "publish:wf-open",
            ),
            callbacks.events,
        )
    }

    @Test
    fun `backToList should clear opened workflow without resetting highlight`() {
        val callbacks = RecordingCallbacks()

        coordinator.backToList(callbacks)

        assertEquals(listOf("clear:false"), callbacks.events)
    }

    private fun item(workflowId: String): SpecWorkflowListPanel.WorkflowListItem {
        return SpecWorkflowListPanel.WorkflowListItem(
            workflowId = workflowId,
            title = "Workflow $workflowId",
            description = "selection test",
            currentPhase = SpecPhase.DESIGN,
            status = WorkflowStatus.IN_PROGRESS,
            updatedAt = 1L,
        )
    }

    private class RecordingCallbacks : SpecWorkflowSelectionCallbacks {
        val events = mutableListOf<String>()

        override fun highlightWorkflow(workflowId: String?) {
            events += "highlight:$workflowId"
        }

        override fun clearOpenedWorkflowUi(resetHighlight: Boolean) {
            events += "clear:$resetHighlight"
        }

        override fun loadWorkflow(workflowId: String) {
            events += "load:$workflowId"
        }

        override fun publishWorkflowSelection(workflowId: String) {
            events += "publish:$workflowId"
        }
    }
}
