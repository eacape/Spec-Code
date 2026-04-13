package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.StageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpecWorkflowExternalEventCoordinatorTest {

    private val coordinator = SpecWorkflowExternalEventCoordinator(
        specDocumentFileNames = setOf(
            "requirements.md",
            "design.md",
            "tasks.md",
            "implement.md",
            "verify.md",
        ),
    )

    @Test
    fun `resolveSelectWorkflow should convert selection events into normalized open requests`() {
        assertEquals(
            SpecWorkflowExternalEventAction.OpenWorkflow(
                request = SpecToolWindowOpenRequest(workflowId = "wf-target"),
            ),
            coordinator.resolveSelectWorkflow(" wf-target "),
        )
    }

    @Test
    fun `resolveOpenWorkflow should normalize workflow and task ids while preserving focus details`() {
        val request = SpecToolWindowOpenRequest(
            workflowId = " wf-open ",
            taskId = " task-7 ",
            focusedStage = StageId.IMPLEMENT,
            requirementsRepairClarification = RequirementsRepairClarificationRequest(
                missingSections = listOf(RequirementsSectionId.USER_STORIES),
            ),
        )

        assertEquals(
            SpecWorkflowExternalEventAction.OpenWorkflow(
                request = SpecToolWindowOpenRequest(
                    workflowId = "wf-open",
                    taskId = "task-7",
                    focusedStage = StageId.IMPLEMENT,
                    requirementsRepairClarification = RequirementsRepairClarificationRequest(
                        missingSections = listOf(RequirementsSectionId.USER_STORIES),
                    ),
                ),
            ),
            coordinator.resolveOpenWorkflow(request),
        )
    }

    @Test
    fun `resolveOpenWorkflow should ignore blank workflow ids`() {
        assertNull(
            coordinator.resolveOpenWorkflow(
                SpecToolWindowOpenRequest(
                    workflowId = "   ",
                    focusedStage = StageId.DESIGN,
                ),
            ),
        )
    }

    @Test
    fun `resolveWorkflowChanged should ignore workflow selected echo events`() {
        assertNull(
            coordinator.resolveWorkflowChanged(
                SpecWorkflowChangedEvent(
                    workflowId = "wf-selected",
                    reason = SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED,
                ),
            ),
        )
    }

    @Test
    fun `resolveWorkflowChanged should refresh changed workflow for non selection events`() {
        assertEquals(
            SpecWorkflowExternalEventAction.RefreshWorkflows(selectWorkflowId = "wf-refresh"),
            coordinator.resolveWorkflowChanged(
                SpecWorkflowChangedEvent(
                    workflowId = " wf-refresh ",
                    reason = "workflow_updated",
                ),
            ),
        )
    }

    @Test
    fun `resolveDocumentReload should match spec document changes within the selected workflow`() {
        assertEquals(
            SpecWorkflowExternalEventAction.ScheduleDocumentReload(workflowId = "WF-42"),
            coordinator.resolveDocumentReload(
                eventPaths = listOf(
                    "D:\\repo\\.spec-coding\\specs\\WF-42\\notes.txt",
                    "D:\\repo\\.spec-coding\\specs\\WF-42\\TASKS.md",
                ),
                basePath = " D:\\repo\\ ",
                selectedWorkflowId = " WF-42 ",
            ),
        )
    }

    @Test
    fun `resolveDocumentReload should ignore unrelated workflow files and invalid context`() {
        assertNull(
            coordinator.resolveDocumentReload(
                eventPaths = listOf("D:/repo/.spec-coding/specs/wf-24/notes.txt"),
                basePath = "D:/repo",
                selectedWorkflowId = "wf-42",
            ),
        )
        assertNull(
            coordinator.resolveDocumentReload(
                eventPaths = listOf("D:/repo/.spec-coding/specs/wf-42/tasks.md"),
                basePath = "   ",
                selectedWorkflowId = "wf-42",
            ),
        )
        assertNull(
            coordinator.resolveDocumentReload(
                eventPaths = listOf("D:/repo/.spec-coding/specs/wf-42/tasks.md"),
                basePath = "D:/repo",
                selectedWorkflowId = "   ",
            ),
        )
    }
}
