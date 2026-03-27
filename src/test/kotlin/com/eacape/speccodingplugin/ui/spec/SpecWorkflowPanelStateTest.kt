package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowPanelStateTest {

    @Test
    fun `selectWorkflow should preserve focus when selection stays on same workflow`() {
        val state = SpecWorkflowPanelState().apply {
            selectedWorkflowId = "wf-1"
            focusedStage = StageId.DESIGN
            selectedDocumentWorkspaceView = DocumentWorkspaceView.STRUCTURED_TASKS
            selectedStructuredTaskId = "task-1"
        }

        val changed = state.selectWorkflow(" wf-1 ")

        assertFalse(changed)
        assertEquals("wf-1", state.selectedWorkflowId)
        assertEquals(StageId.DESIGN, state.focusedStage)
        assertEquals(DocumentWorkspaceView.STRUCTURED_TASKS, state.selectedDocumentWorkspaceView)
        assertEquals("task-1", state.selectedStructuredTaskId)
    }

    @Test
    fun `selectWorkflow should reset stage scoped state when workflow changes`() {
        val state = SpecWorkflowPanelState().apply {
            selectedWorkflowId = "wf-1"
            focusedStage = StageId.IMPLEMENT
            selectedDocumentWorkspaceView = DocumentWorkspaceView.STRUCTURED_TASKS
            selectedStructuredTaskId = "task-1"
        }

        val changed = state.selectWorkflow("wf-2")

        assertTrue(changed)
        assertEquals("wf-2", state.selectedWorkflowId)
        assertNull(state.focusedStage)
        assertEquals(DocumentWorkspaceView.DOCUMENT, state.selectedDocumentWorkspaceView)
        assertNull(state.selectedStructuredTaskId)
    }

    @Test
    fun `clearOpenedWorkflow should reset selection and optionally highlight`() {
        val state = SpecWorkflowPanelState().apply {
            selectedWorkflowId = "wf-1"
            highlightedWorkflowId = "wf-1"
            focusedStage = StageId.TASKS
            selectedDocumentWorkspaceView = DocumentWorkspaceView.STRUCTURED_TASKS
            selectedStructuredTaskId = "task-1"
        }

        state.clearOpenedWorkflow(resetHighlight = false)

        assertNull(state.selectedWorkflowId)
        assertEquals("wf-1", state.highlightedWorkflowId)
        assertNull(state.focusedStage)
        assertEquals(DocumentWorkspaceView.DOCUMENT, state.selectedDocumentWorkspaceView)
        assertNull(state.selectedStructuredTaskId)

        state.clearOpenedWorkflow(resetHighlight = true)

        assertNull(state.highlightedWorkflowId)
    }

    @Test
    fun `rememberPendingOpenRequest should normalize and drop invalid pending workflow ids`() {
        val state = SpecWorkflowPanelState()

        val request = state.rememberPendingOpenRequest(
            SpecToolWindowOpenRequest(
                workflowId = " wf-42 ",
                taskId = " task-7 ",
                focusedStage = StageId.IMPLEMENT,
            ),
        )

        assertEquals("wf-42", request.workflowId)
        assertEquals("task-7", request.taskId)
        assertEquals(request, state.pendingOpenWorkflowRequest)

        state.dropPendingOpenRequestIfInvalid(setOf("wf-99"))

        assertNull(state.pendingOpenWorkflowRequest)
    }
}
