package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowSwitcherUiHostTest {

    @Test
    fun `show should cancel previous popup before replacing it`() {
        val recorder = RecordingEnvironment()
        val host = SpecWorkflowSwitcherUiHost(showPopupUi = recorder::showPopup)

        host.show(recorder.popupRequest())
        host.show(recorder.popupRequest(initialSelectionWorkflowId = "wf-b"))

        assertEquals(2, recorder.shownRequests.size)
        assertEquals(listOf("cancel:0"), recorder.events)
        assertTrue(host.isVisibleForTest())
        assertEquals(listOf("wf-a", "wf-b"), host.visibleWorkflowIdsForTest())
        assertEquals("wf-b", host.selectedWorkflowIdForTest())
    }

    @Test
    fun `popup callbacks should clear active popup reference before delegating`() {
        val recorder = RecordingEnvironment()
        val host = SpecWorkflowSwitcherUiHost(showPopupUi = recorder::showPopup)

        host.show(recorder.popupRequest())
        recorder.lastRequest!!.onOpenWorkflow("wf-a")

        assertFalse(host.isVisibleForTest())
        assertEquals(listOf("open:wf-a"), recorder.events)

        host.show(recorder.popupRequest())
        recorder.lastRequest!!.onEditWorkflow("wf-b")

        assertFalse(host.isVisibleForTest())
        assertEquals(listOf("open:wf-a", "edit:wf-b"), recorder.events)

        host.show(recorder.popupRequest())
        recorder.lastRequest!!.onDeleteWorkflow("wf-b")

        assertFalse(host.isVisibleForTest())
        assertEquals(listOf("open:wf-a", "edit:wf-b", "delete:wf-b"), recorder.events)
    }

    private class RecordingEnvironment {
        val events = mutableListOf<String>()
        val shownRequests = mutableListOf<SpecWorkflowSwitcherPopupRequest>()
        var lastRequest: SpecWorkflowSwitcherPopupRequest? = null

        fun showPopup(request: SpecWorkflowSwitcherPopupRequest): SpecWorkflowSwitcherPopupController {
            shownRequests += request
            lastRequest = request
            return FakePopupController(
                visibleWorkflowIds = request.items.map { item -> item.workflowId },
                selectedWorkflowId = request.initialSelectionWorkflowId,
                onCancel = {
                    events += "cancel:${shownRequests.size - 1}"
                },
            )
        }

        fun popupRequest(initialSelectionWorkflowId: String? = "wf-a"): SpecWorkflowSwitcherPopupRequest {
            return SpecWorkflowSwitcherPopupRequest(
                items = listOf(item("wf-a"), item("wf-b")),
                initialSelectionWorkflowId = initialSelectionWorkflowId,
                onOpenWorkflow = { workflowId -> events += "open:$workflowId" },
                onEditWorkflow = { workflowId -> events += "edit:$workflowId" },
                onDeleteWorkflow = { workflowId -> events += "delete:$workflowId" },
            )
        }

        private fun item(workflowId: String): SpecWorkflowListPanel.WorkflowListItem {
            return SpecWorkflowListPanel.WorkflowListItem(
                workflowId = workflowId,
                title = "Workflow $workflowId",
                description = "switcher ui host",
                currentPhase = SpecPhase.SPECIFY,
                status = WorkflowStatus.IN_PROGRESS,
                updatedAt = 1L,
            )
        }
    }

    private class FakePopupController(
        private val visibleWorkflowIds: List<String>,
        private val selectedWorkflowId: String?,
        private val onCancel: () -> Unit,
    ) : SpecWorkflowSwitcherPopupController {
        override fun cancel() {
            onCancel()
        }

        override fun isVisibleForTest(): Boolean = true

        override fun visibleWorkflowIdsForTest(): List<String> = visibleWorkflowIds

        override fun selectedWorkflowIdForTest(): String? = selectedWorkflowId
    }
}
