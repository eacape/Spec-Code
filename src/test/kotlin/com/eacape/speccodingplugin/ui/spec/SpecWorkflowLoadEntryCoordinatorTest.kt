package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowLoadEntryCoordinatorTest {

    @Test
    fun `selectWorkflow should update state and request a normalized selection load`() {
        val state = SpecWorkflowPanelState().apply {
            selectedWorkflowId = "wf-current"
            focusedStage = StageId.DESIGN
        }
        val loads = mutableListOf<RecordedLoad>()
        val coordinator = coordinator(state = state, loads = loads)

        coordinator.selectWorkflow(" wf-next ")

        assertEquals("wf-next", state.selectedWorkflowId)
        assertNull(state.focusedStage)
        assertEquals(
            SpecWorkflowLoadTrigger(
                workflowId = "wf-next",
                includeSources = true,
                previousSelectedWorkflowId = "wf-current",
            ),
            loads.single().trigger,
        )
        assertNull(loads.single().onUpdated)
    }

    @Test
    fun `reloadCurrentWorkflow should clear focused stage when following current phase`() {
        val state = SpecWorkflowPanelState().apply {
            selectedWorkflowId = " wf-reload "
            focusedStage = StageId.IMPLEMENT
        }
        val loads = mutableListOf<RecordedLoad>()
        val onUpdated: (SpecWorkflow) -> Unit = {}
        val coordinator = coordinator(state = state, loads = loads)

        coordinator.reloadCurrentWorkflow(
            followCurrentPhase = true,
            onUpdated = onUpdated,
        )

        assertNull(state.focusedStage)
        assertEquals(
            SpecWorkflowLoadTrigger(
                workflowId = "wf-reload",
                includeSources = false,
                followCurrentPhase = true,
            ),
            loads.single().trigger,
        )
        assertSame(onUpdated, loads.single().onUpdated)
    }

    @Test
    fun `reloadCurrentWorkflow should ignore missing selection`() {
        val loads = mutableListOf<RecordedLoad>()
        val coordinator = coordinator(loads = loads)

        coordinator.reloadCurrentWorkflow()

        assertTrue(loads.isEmpty())
    }

    @Test
    fun `openWorkflowFromRequest should remember pending request and refresh when workflow is not loaded`() {
        val state = SpecWorkflowPanelState().apply {
            selectedWorkflowId = "wf-current"
        }
        val refreshes = mutableListOf<String?>()
        val appliedRequests = mutableListOf<SpecToolWindowOpenRequest>()
        val coordinator = coordinator(
            state = state,
            refreshes = refreshes,
            appliedRequests = appliedRequests,
        )

        coordinator.openWorkflowFromRequest(
            request = SpecToolWindowOpenRequest(
                workflowId = " wf-target ",
                taskId = " task-7 ",
                focusedStage = StageId.TASKS,
            ),
            currentWorkflowId = "wf-current",
        )

        val normalizedRequest = SpecToolWindowOpenRequest(
            workflowId = "wf-target",
            taskId = "task-7",
            focusedStage = StageId.TASKS,
        )
        assertEquals(normalizedRequest, state.pendingOpenWorkflowRequest)
        assertEquals(listOf("wf-target"), refreshes)
        assertTrue(appliedRequests.isEmpty())
    }

    @Test
    fun `openWorkflowFromRequest should apply directly when target workflow is already loaded`() {
        val state = SpecWorkflowPanelState().apply {
            selectedWorkflowId = "wf-current"
        }
        val refreshes = mutableListOf<String?>()
        val appliedRequests = mutableListOf<SpecToolWindowOpenRequest>()
        val coordinator = coordinator(
            state = state,
            refreshes = refreshes,
            appliedRequests = appliedRequests,
        )

        coordinator.openWorkflowFromRequest(
            request = SpecToolWindowOpenRequest(
                workflowId = " wf-current ",
                taskId = " task-1 ",
                focusedStage = StageId.IMPLEMENT,
            ),
            currentWorkflowId = " wf-current ",
        )

        assertEquals(
            listOf(
                SpecToolWindowOpenRequest(
                    workflowId = "wf-current",
                    taskId = "task-1",
                    focusedStage = StageId.IMPLEMENT,
                ),
            ),
            appliedRequests,
        )
        assertNull(state.pendingOpenWorkflowRequest)
        assertTrue(refreshes.isEmpty())
    }

    @Test
    fun `openWorkflowFromRequest should clear stale pending request when workflow id is blank`() {
        val state = SpecWorkflowPanelState().apply {
            rememberPendingOpenRequest(SpecToolWindowOpenRequest(workflowId = "wf-old"))
        }
        val refreshes = mutableListOf<String?>()
        val appliedRequests = mutableListOf<SpecToolWindowOpenRequest>()
        val coordinator = coordinator(
            state = state,
            refreshes = refreshes,
            appliedRequests = appliedRequests,
        )

        coordinator.openWorkflowFromRequest(
            request = SpecToolWindowOpenRequest(workflowId = "   "),
            currentWorkflowId = "wf-current",
        )

        assertNull(state.pendingOpenWorkflowRequest)
        assertTrue(refreshes.isEmpty())
        assertTrue(appliedRequests.isEmpty())
    }

    @Test
    fun `applyPendingOpenWorkflowRequestIfNeeded should only consume matching requests`() {
        val pendingRequest = SpecToolWindowOpenRequest(
            workflowId = " wf-target ",
            taskId = " task-2 ",
            focusedStage = StageId.DESIGN,
        )
        val state = SpecWorkflowPanelState().apply {
            rememberPendingOpenRequest(pendingRequest)
        }
        val appliedRequests = mutableListOf<SpecToolWindowOpenRequest>()
        val coordinator = coordinator(
            state = state,
            appliedRequests = appliedRequests,
        )

        coordinator.applyPendingOpenWorkflowRequestIfNeeded("wf-other")

        assertTrue(appliedRequests.isEmpty())
        assertEquals(
            SpecToolWindowOpenRequest(
                workflowId = "wf-target",
                taskId = "task-2",
                focusedStage = StageId.DESIGN,
            ),
            state.pendingOpenWorkflowRequest,
        )

        coordinator.applyPendingOpenWorkflowRequestIfNeeded(" wf-target ")

        assertEquals(
            listOf(
                SpecToolWindowOpenRequest(
                    workflowId = "wf-target",
                    taskId = "task-2",
                    focusedStage = StageId.DESIGN,
                ),
            ),
            appliedRequests,
        )
        assertNull(state.pendingOpenWorkflowRequest)
    }

    private fun coordinator(
        state: SpecWorkflowPanelState = SpecWorkflowPanelState(),
        loads: MutableList<RecordedLoad> = mutableListOf(),
        refreshes: MutableList<String?> = mutableListOf(),
        appliedRequests: MutableList<SpecToolWindowOpenRequest> = mutableListOf(),
    ): SpecWorkflowLoadEntryCoordinator {
        return SpecWorkflowLoadEntryCoordinator(
            panelState = state,
            navigationCoordinator = SpecWorkflowNavigationCoordinator(),
            requestWorkflowLoad = { trigger, onUpdated ->
                loads += RecordedLoad(trigger, onUpdated)
            },
            refreshWorkflows = { workflowId -> refreshes += workflowId },
            applyOpenRequestToCurrentWorkflow = { request -> appliedRequests += request },
        )
    }

    private data class RecordedLoad(
        val trigger: SpecWorkflowLoadTrigger,
        val onUpdated: ((SpecWorkflow) -> Unit)?,
    )
}
