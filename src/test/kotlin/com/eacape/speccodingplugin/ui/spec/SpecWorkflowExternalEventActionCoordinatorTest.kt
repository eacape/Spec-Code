package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowExternalEventActionCoordinatorTest {

    @Test
    fun `handle should dispatch create workflow actions`() {
        var createdTemplate: WorkflowTemplate? = null
        val coordinator = createCoordinator(
            createWorkflow = { template -> createdTemplate = template },
        )

        coordinator.handle(
            SpecWorkflowExternalEventAction.CreateWorkflow(WorkflowTemplate.FULL_SPEC),
        )

        assertEquals(WorkflowTemplate.FULL_SPEC, createdTemplate)
    }

    @Test
    fun `handle should dispatch open workflow actions`() {
        var openedRequest: SpecToolWindowOpenRequest? = null
        val coordinator = createCoordinator(
            openWorkflow = { request -> openedRequest = request },
        )

        coordinator.handle(
            SpecWorkflowExternalEventAction.OpenWorkflow(
                request = SpecToolWindowOpenRequest(
                    workflowId = "wf-target",
                    taskId = "task-7",
                    focusedStage = StageId.IMPLEMENT,
                ),
            ),
        )

        assertEquals(
            SpecToolWindowOpenRequest(
                workflowId = "wf-target",
                taskId = "task-7",
                focusedStage = StageId.IMPLEMENT,
            ),
            openedRequest,
        )
    }

    @Test
    fun `handle should dispatch refresh workflow actions`() {
        var refreshedWorkflowId: String? = null
        val coordinator = createCoordinator(
            refreshWorkflows = { workflowId -> refreshedWorkflowId = workflowId },
        )

        coordinator.handle(
            SpecWorkflowExternalEventAction.RefreshWorkflows(selectWorkflowId = "wf-refresh"),
        )

        assertEquals("wf-refresh", refreshedWorkflowId)
    }

    @Test
    fun `handle should reload documents for the currently selected workflow only`() {
        val scheduled = mutableListOf<FakeScheduledReload>()
        var selectedWorkflowId = "wf-target"
        var reloads = 0
        val coordinator = createCoordinator(
            scheduled = scheduled,
            selectedWorkflowId = { selectedWorkflowId },
            reloadCurrentWorkflow = { reloads += 1 },
        )

        coordinator.handle(
            SpecWorkflowExternalEventAction.ScheduleDocumentReload("wf-target"),
        )
        selectedWorkflowId = "wf-other"
        scheduled.single().run()

        assertEquals(0, reloads)
    }

    @Test
    fun `handle should skip document reload when panel is disposed before debounce fires`() {
        val scheduled = mutableListOf<FakeScheduledReload>()
        var disposed = false
        var reloads = 0
        val coordinator = createCoordinator(
            scheduled = scheduled,
            isDisposed = { disposed },
            selectedWorkflowId = { "wf-target" },
            reloadCurrentWorkflow = { reloads += 1 },
        )

        coordinator.handle(
            SpecWorkflowExternalEventAction.ScheduleDocumentReload("wf-target"),
        )
        disposed = true
        scheduled.single().run()

        assertEquals(0, reloads)
    }

    @Test
    fun `cancelPendingDocumentReload should cancel scheduled reload callbacks`() {
        val scheduled = mutableListOf<FakeScheduledReload>()
        var reloads = 0
        val coordinator = createCoordinator(
            scheduled = scheduled,
            selectedWorkflowId = { "wf-target" },
            reloadCurrentWorkflow = { reloads += 1 },
        )

        coordinator.handle(
            SpecWorkflowExternalEventAction.ScheduleDocumentReload("wf-target"),
        )
        coordinator.cancelPendingDocumentReload()
        scheduled.single().run()

        assertTrue(scheduled.single().cancelled)
        assertEquals(0, reloads)
    }

    private fun createCoordinator(
        scheduled: MutableList<FakeScheduledReload> = mutableListOf(),
        isDisposed: () -> Boolean = { false },
        selectedWorkflowId: () -> String? = { null },
        createWorkflow: (WorkflowTemplate?) -> Unit = {},
        openWorkflow: (SpecToolWindowOpenRequest) -> Unit = {},
        refreshWorkflows: (String?) -> Unit = {},
        reloadCurrentWorkflow: () -> Unit = {},
    ): SpecWorkflowExternalEventActionCoordinator {
        return SpecWorkflowExternalEventActionCoordinator(
            documentReloadCoordinator = SpecWorkflowDocumentReloadCoordinator(
                debounceMillis = 300L,
                scheduleDebounced = { delayMillis, action ->
                    FakeScheduledReload(delayMillis, action).also(scheduled::add)
                },
            ),
            isDisposed = isDisposed,
            selectedWorkflowId = selectedWorkflowId,
            createWorkflow = createWorkflow,
            openWorkflow = openWorkflow,
            refreshWorkflows = refreshWorkflows,
            reloadCurrentWorkflow = reloadCurrentWorkflow,
        )
    }

    private class FakeScheduledReload(
        val delayMillis: Long,
        private val action: () -> Unit,
    ) : SpecWorkflowDocumentReloadHandle {
        var cancelled: Boolean = false
            private set

        override fun cancel() {
            cancelled = true
        }

        fun run() {
            action()
        }
    }
}
