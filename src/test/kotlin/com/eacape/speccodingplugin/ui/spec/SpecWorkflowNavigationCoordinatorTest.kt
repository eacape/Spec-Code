package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowNavigationCoordinatorTest {

    private val coordinator = SpecWorkflowNavigationCoordinator()

    @Test
    fun `buildSelectionLoadTrigger should normalize requested workflow and preserve previous selection`() {
        val trigger = coordinator.buildSelectionLoadTrigger(
            workflowId = " wf-next ",
            selectedWorkflowId = " wf-current ",
        )

        assertEquals(
            SpecWorkflowLoadTrigger(
                workflowId = "wf-next",
                includeSources = true,
                previousSelectedWorkflowId = "wf-current",
            ),
            trigger,
        )
    }

    @Test
    fun `buildReloadLoadTrigger should reuse selected workflow without reloading sources`() {
        val trigger = coordinator.buildReloadLoadTrigger(
            selectedWorkflowId = " wf-reload ",
            followCurrentPhase = true,
        )

        assertEquals(
            SpecWorkflowLoadTrigger(
                workflowId = "wf-reload",
                includeSources = false,
                followCurrentPhase = true,
            ),
            trigger,
        )
    }

    @Test
    fun `buildReloadLoadTrigger should return null without a selected workflow`() {
        assertNull(
            coordinator.buildReloadLoadTrigger(
                selectedWorkflowId = "   ",
                followCurrentPhase = false,
            ),
        )
    }

    @Test
    fun `resolveOpenRequest should normalize request and refresh when workflow is not already loaded`() {
        val decision = coordinator.resolveOpenRequest(
            request = SpecToolWindowOpenRequest(
                workflowId = " wf-target ",
                taskId = " task-7 ",
                focusedStage = StageId.IMPLEMENT,
            ),
            selectedWorkflowId = "wf-other",
            currentWorkflowId = "wf-other",
        )

        assertEquals(
            SpecToolWindowOpenRequest(
                workflowId = "wf-target",
                taskId = "task-7",
                focusedStage = StageId.IMPLEMENT,
            ),
            decision.normalizedRequest,
        )
        assertFalse(decision.shouldApplyToCurrentWorkflow)
        assertEquals("wf-target", decision.refreshWorkflowId)
    }

    @Test
    fun `resolveOpenRequest should apply directly when target workflow is already selected and loaded`() {
        val decision = coordinator.resolveOpenRequest(
            request = SpecToolWindowOpenRequest(
                workflowId = " wf-current ",
                focusedStage = StageId.DESIGN,
            ),
            selectedWorkflowId = "wf-current",
            currentWorkflowId = " wf-current ",
        )

        assertTrue(decision.shouldApplyToCurrentWorkflow)
        assertEquals("wf-current", decision.refreshWorkflowId)
    }

    @Test
    fun `resolveOpenRequest should reject blank workflow ids`() {
        val decision = coordinator.resolveOpenRequest(
            request = SpecToolWindowOpenRequest(
                workflowId = "   ",
                taskId = " task-1 ",
                focusedStage = StageId.TASKS,
            ),
            selectedWorkflowId = "wf-current",
            currentWorkflowId = "wf-current",
        )

        assertNull(decision.normalizedRequest)
        assertFalse(decision.shouldApplyToCurrentWorkflow)
        assertNull(decision.refreshWorkflowId)
    }
}
