package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowRuntimeTroubleshootingStatusCoordinatorTest {

    @Test
    fun `runtime should normalize workflow id and attach troubleshooting actions`() {
        val calls = mutableListOf<Pair<String, SpecWorkflowRuntimeTroubleshootingTrigger>>()
        val coordinator = SpecWorkflowRuntimeTroubleshootingStatusCoordinator(
            buildActions = { workflowId, trigger ->
                calls += workflowId to trigger
                listOf(SpecWorkflowTroubleshootingAction.OpenSettings(label = "Open settings"))
            },
        )

        val presentation = coordinator.runtime(
            SpecWorkflowRuntimeTroubleshootingStatusRequest(
                workflowId = " wf-1 ",
                text = " generation failed ",
                trigger = SpecWorkflowRuntimeTroubleshootingTrigger.GENERATION_FAILURE,
            ),
        )

        assertEquals(listOf("wf-1" to SpecWorkflowRuntimeTroubleshootingTrigger.GENERATION_FAILURE), calls)
        assertEquals("generation failed", presentation.text)
        assertEquals(
            listOf(SpecWorkflowTroubleshootingAction.OpenSettings(label = "Open settings")),
            presentation.actions,
        )
    }

    @Test
    fun `runtime should fall back to plain status when workflow id is blank`() {
        val coordinator = SpecWorkflowRuntimeTroubleshootingStatusCoordinator(
            buildActions = { _, _ ->
                error("buildActions should not be called")
            },
        )

        val presentation = coordinator.runtime(
            SpecWorkflowRuntimeTroubleshootingStatusRequest(
                workflowId = " ",
                text = " verify failed ",
                trigger = SpecWorkflowRuntimeTroubleshootingTrigger.VERIFY_FAILURE,
            ),
        )

        assertEquals("verify failed", presentation.text)
        assertTrue(presentation.actions.isEmpty())
    }

    @Test
    fun `runtime should clear actions when text is blank`() {
        val coordinator = SpecWorkflowRuntimeTroubleshootingStatusCoordinator(
            buildActions = { _, _ ->
                error("buildActions should not be called")
            },
        )

        val presentation = coordinator.runtime(
            SpecWorkflowRuntimeTroubleshootingStatusRequest(
                workflowId = "wf-3",
                text = "   ",
                trigger = SpecWorkflowRuntimeTroubleshootingTrigger.TASK_EXECUTION_FAILURE,
            ),
        )

        assertEquals("", presentation.text)
        assertTrue(presentation.actions.isEmpty())
    }
}
