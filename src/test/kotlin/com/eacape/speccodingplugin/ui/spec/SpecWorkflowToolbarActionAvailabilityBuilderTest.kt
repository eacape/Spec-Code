package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecWorkflowToolbarActionAvailabilityBuilderTest {

    @Test
    fun `build should enable worktree and delta actions for opened workflow`() {
        assertEquals(
            SpecWorkflowToolbarActionAvailability(
                createWorktreeEnabled = true,
                mergeWorktreeEnabled = true,
                deltaEnabled = true,
                archiveEnabled = false,
            ),
            SpecWorkflowToolbarActionAvailabilityBuilder.build(
                workflow(status = WorkflowStatus.IN_PROGRESS),
            ),
        )
    }

    @Test
    fun `build should enable archive only for completed workflow`() {
        assertEquals(
            SpecWorkflowToolbarActionAvailability(
                createWorktreeEnabled = true,
                mergeWorktreeEnabled = true,
                deltaEnabled = true,
                archiveEnabled = true,
            ),
            SpecWorkflowToolbarActionAvailabilityBuilder.build(
                workflow(status = WorkflowStatus.COMPLETED),
            ),
        )
    }

    @Test
    fun `empty should disable all toolbar actions`() {
        assertEquals(
            SpecWorkflowToolbarActionAvailability(),
            SpecWorkflowToolbarActionAvailabilityBuilder.empty(),
        )
        assertEquals(
            SpecWorkflowToolbarActionAvailability(),
            SpecWorkflowToolbarActionAvailabilityBuilder.build(null),
        )
    }

    private fun workflow(status: WorkflowStatus): SpecWorkflow {
        return SpecWorkflow(
            id = "wf-toolbar",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = status,
            title = "Toolbar Workflow",
            description = "toolbar availability",
            template = WorkflowTemplate.QUICK_TASK,
            currentStage = StageId.IMPLEMENT,
        )
    }
}
