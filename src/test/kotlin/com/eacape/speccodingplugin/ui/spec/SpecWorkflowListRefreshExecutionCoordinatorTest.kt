package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SpecWorkflowListRefreshExecutionCoordinatorTest {

    @Test
    fun `refreshWorkflows should load in background then apply using latest selection state and show feedback`() {
        val loadedState = SpecWorkflowListRefreshLoadedState(emptyList())
        val events = mutableListOf<String>()
        val applyRequests = mutableListOf<SpecWorkflowListRefreshApplyRequest>()
        var selectedWorkflowId: String? = "wf-initial"
        var highlightedWorkflowId: String? = "wf-highlight-initial"
        var scheduledLoadAction: (() -> SpecWorkflowListRefreshLoadedState)? = null
        var scheduledOnLoaded: ((SpecWorkflowListRefreshLoadedState) -> Unit)? = null

        val coordinator = SpecWorkflowListRefreshExecutionCoordinator(
            loadRefreshState = {
                events += "load"
                loadedState
            },
            selectedWorkflowId = { selectedWorkflowId },
            highlightedWorkflowId = { highlightedWorkflowId },
            launchRefreshLoad = { loadAction, onLoaded ->
                events += "schedule"
                scheduledLoadAction = loadAction
                scheduledOnLoaded = onLoaded
            },
            applyLoadedState = { request ->
                events += "apply"
                applyRequests += request
            },
            showRefreshFeedback = {
                events += "feedback"
            },
        )

        coordinator.refreshWorkflows(
            SpecWorkflowListRefreshExecutionRequest(
                selectWorkflowId = "wf-target",
                showRefreshFeedback = true,
                preserveListMode = true,
            ),
        )

        assertEquals(listOf("schedule"), events)
        assertNotNull(scheduledLoadAction)
        assertNotNull(scheduledOnLoaded)

        selectedWorkflowId = "wf-updated"
        highlightedWorkflowId = "wf-highlight-updated"

        val resolvedLoadedState = scheduledLoadAction!!.invoke()
        assertSame(loadedState, resolvedLoadedState)
        assertEquals(listOf("schedule", "load"), events)

        scheduledOnLoaded!!.invoke(resolvedLoadedState)

        assertEquals(listOf("schedule", "load", "apply", "feedback"), events)
        val applyRequest = applyRequests.single()
        assertSame(loadedState, applyRequest.loadedState)
        assertEquals("wf-target", applyRequest.selectWorkflowId)
        assertEquals("wf-updated", applyRequest.selectedWorkflowId)
        assertEquals("wf-highlight-updated", applyRequest.highlightedWorkflowId)
        assertEquals(true, applyRequest.preserveListMode)
    }

    @Test
    fun `refreshWorkflows should skip feedback when not requested`() {
        val loadedState = SpecWorkflowListRefreshLoadedState(emptyList())
        var scheduledLoadAction: (() -> SpecWorkflowListRefreshLoadedState)? = null
        var scheduledOnLoaded: ((SpecWorkflowListRefreshLoadedState) -> Unit)? = null
        var appliedRequest: SpecWorkflowListRefreshApplyRequest? = null
        var feedbackCalls = 0

        val coordinator = SpecWorkflowListRefreshExecutionCoordinator(
            loadRefreshState = { loadedState },
            selectedWorkflowId = { null },
            highlightedWorkflowId = { null },
            launchRefreshLoad = { loadAction, onLoaded ->
                scheduledLoadAction = loadAction
                scheduledOnLoaded = onLoaded
            },
            applyLoadedState = { request ->
                appliedRequest = request
            },
            showRefreshFeedback = {
                feedbackCalls += 1
            },
        )

        coordinator.refreshWorkflows(
            SpecWorkflowListRefreshExecutionRequest(
                selectWorkflowId = "wf-no-feedback",
                preserveListMode = false,
            ),
        )

        val resolvedLoadedState = scheduledLoadAction!!.invoke()
        scheduledOnLoaded!!.invoke(resolvedLoadedState)

        assertEquals(0, feedbackCalls)
        assertEquals("wf-no-feedback", appliedRequest?.selectWorkflowId)
        assertNull(appliedRequest?.selectedWorkflowId)
        assertNull(appliedRequest?.highlightedWorkflowId)
        assertEquals(false, appliedRequest?.preserveListMode)
    }
}
