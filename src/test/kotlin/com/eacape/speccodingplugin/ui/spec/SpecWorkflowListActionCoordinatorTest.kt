package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowListActionCoordinatorTest {

    @Test
    fun `requestSwitch should ignore empty workflow list`() {
        val recorder = RecordingEnvironment()

        recorder.coordinator().requestSwitch()

        assertTrue(recorder.events.isEmpty())
        assertEquals(null, recorder.switchRequest)
    }

    @Test
    fun `requestSwitch should show popup with selected workflow and route actions`() {
        val recorder = RecordingEnvironment().apply {
            items += item("wf-a")
            items += item("wf-b")
            selectedWorkflowId = " wf-b "
            highlightedWorkflowId = "wf-a"
        }

        recorder.coordinator().requestSwitch()

        assertEquals(
            listOf("cancelSwitcher", "showSwitcher:wf-b:wf-a,wf-b"),
            recorder.events,
        )
        recorder.switchRequest!!.onOpenWorkflow("wf-a")
        recorder.switchRequest!!.onEditWorkflow("wf-b")

        assertEquals(
            listOf(
                "cancelSwitcher",
                "showSwitcher:wf-b:wf-a,wf-b",
                "open:wf-a",
                "edit:wf-b",
            ),
            recorder.events,
        )
    }

    @Test
    fun `requestDelete should resolve refresh target and refresh after successful delete`() {
        val recorder = RecordingEnvironment().apply {
            items += item("wf-a")
            items += item("wf-b")
            items += item("wf-c")
            selectedWorkflowId = "wf-b"
            deleteResults["wf-b"] = Result.success(Unit)
        }

        recorder.coordinator().requestDelete(" wf-b ")

        assertEquals(
            listOf(
                "cancelSwitcher",
                "launchDelete",
                "delete:wf-b",
                "invokeLater",
                "deleteSuccess:wf-b:wf-a:false",
            ),
            recorder.events,
        )
    }

    @Test
    fun `requestDelete should surface failure after background delete error`() {
        val recorder = RecordingEnvironment().apply {
            items += item("wf-delete")
            deleteResults["wf-delete"] = Result.failure(IllegalStateException("delete failed"))
        }

        recorder.coordinator().requestDelete("wf-delete")

        assertEquals(
            listOf(
                "cancelSwitcher",
                "launchDelete",
                "delete:wf-delete",
                "invokeLater",
                "deleteFailure:delete failed",
            ),
            recorder.events,
        )
    }

    @Test
    fun `requestDelete should ignore blank workflow id`() {
        val recorder = RecordingEnvironment().apply {
            items += item("wf-a")
        }

        recorder.coordinator().requestDelete("   ")

        assertTrue(recorder.events.isEmpty())
    }

    private class RecordingEnvironment {
        val items = mutableListOf<SpecWorkflowListPanel.WorkflowListItem>()
        val events = mutableListOf<String>()
        val deleteResults = mutableMapOf<String, Result<Unit>>()
        var selectedWorkflowId: String? = null
        var highlightedWorkflowId: String? = null
        var switchRequest: SpecWorkflowSwitcherPopupRequest? = null

        fun coordinator(): SpecWorkflowListActionCoordinator {
            return SpecWorkflowListActionCoordinator(
                currentItems = { items.toList() },
                selectedWorkflowId = { selectedWorkflowId },
                highlightedWorkflowId = { highlightedWorkflowId },
                showWorkflowSwitcher = { request ->
                    switchRequest = request
                    events += "showSwitcher:${request.initialSelectionWorkflowId}:${request.items.joinToString(",") { item -> item.workflowId }}"
                },
                cancelWorkflowSwitcher = {
                    events += "cancelSwitcher"
                },
                selectionCoordinator = SpecWorkflowSelectionCoordinator(),
                openWorkflow = { workflowId ->
                    events += "open:$workflowId"
                },
                editWorkflow = { workflowId ->
                    events += "edit:$workflowId"
                },
                deleteWorkflow = { workflowId ->
                    events += "delete:$workflowId"
                    deleteResults.getValue(workflowId)
                },
                launchDeleteInBackground = { task ->
                    events += "launchDelete"
                    task()
                },
                invokeLater = { action ->
                    events += "invokeLater"
                    action()
                },
                onDeleteSuccess = { workflowId, refreshTarget ->
                    events += "deleteSuccess:$workflowId:${refreshTarget.selectWorkflowId}:${refreshTarget.preserveListMode}"
                },
                onDeleteFailure = { error ->
                    events += "deleteFailure:${error.message}"
                },
            )
        }
    }

    private fun item(workflowId: String): SpecWorkflowListPanel.WorkflowListItem {
        return SpecWorkflowListPanel.WorkflowListItem(
            workflowId = workflowId,
            title = "Workflow $workflowId",
            description = "list action coordinator",
            currentPhase = SpecPhase.DESIGN,
            status = WorkflowStatus.IN_PROGRESS,
            updatedAt = 1L,
        )
    }
}
