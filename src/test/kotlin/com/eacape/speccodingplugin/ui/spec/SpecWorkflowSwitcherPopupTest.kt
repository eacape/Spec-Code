package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecWorkflowSwitcherPopupTest {

    @Test
    fun `visibleItems should sort by updatedAt descending and workflowId for ties`() {
        val visibleItems = SpecWorkflowSwitcherSupport.visibleItems(
            items = listOf(
                item(workflowId = "wf-b", title = "Beta", updatedAt = 100L),
                item(workflowId = "wf-a", title = "Alpha", updatedAt = 100L),
                item(workflowId = "wf-c", title = "Current", updatedAt = 300L),
            ),
            query = "",
        )

        assertEquals(listOf("wf-c", "wf-a", "wf-b"), visibleItems.map { it.workflowId })
    }

    @Test
    fun `switcher popup should filter items and route selected workflow action`() {
        val openedWorkflowIds = mutableListOf<String>()
        val popup = SpecWorkflowSwitcherPopup(
            items = listOf(
                item(
                    workflowId = "wf-legacy",
                    title = "Legacy cleanup",
                    description = "old config migration",
                    updatedAt = 100L,
                ),
                item(
                    workflowId = "wf-live",
                    title = "Live progress",
                    description = "task telemetry improvements",
                    updatedAt = 200L,
                ),
            ),
            initialSelectionWorkflowId = "wf-live",
            onOpenWorkflow = { workflowId -> openedWorkflowIds += workflowId },
            onEditWorkflow = {},
            onDeleteWorkflow = {},
        )

        popup.applySearchForTest("legacy")

        assertEquals(listOf("wf-legacy"), popup.visibleWorkflowIdsForTest())
        assertEquals("wf-legacy", popup.selectedWorkflowIdForTest())

        popup.confirmSelectionForTest()

        assertEquals(listOf("wf-legacy"), openedWorkflowIds)
    }

    private fun item(
        workflowId: String,
        title: String,
        description: String = "",
        updatedAt: Long,
    ): SpecWorkflowListPanel.WorkflowListItem {
        return SpecWorkflowListPanel.WorkflowListItem(
            workflowId = workflowId,
            title = title,
            description = description,
            currentPhase = SpecPhase.SPECIFY,
            currentStageLabel = "Requirements",
            status = WorkflowStatus.IN_PROGRESS,
            updatedAt = updatedAt,
        )
    }
}
