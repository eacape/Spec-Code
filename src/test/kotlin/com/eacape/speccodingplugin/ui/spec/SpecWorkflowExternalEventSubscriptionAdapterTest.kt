package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowExternalEventSubscriptionAdapterTest {

    private val externalEventCoordinator = SpecWorkflowExternalEventCoordinator(
        specDocumentFileNames = setOf(
            "requirements.md",
            "design.md",
            "tasks.md",
            "implement.md",
            "verify.md",
        ),
    )

    @Test
    fun `register should dispatch tool window callbacks through invokeLater`() {
        val subscriber = FakeSubscriber()
        val queuedUiActions = mutableListOf<() -> Unit>()
        val actions = mutableListOf<SpecWorkflowExternalEventAction>()
        val adapter = createAdapter(
            subscriber = subscriber,
            invokeLater = { action -> queuedUiActions += action },
            onAction = actions::add,
        )

        adapter.register()
        subscriber.toolWindowListener?.onOpenWorkflowRequested(
            SpecToolWindowOpenRequest(
                workflowId = " wf-target ",
                taskId = " task-7 ",
                focusedStage = StageId.IMPLEMENT,
            ),
        )

        assertTrue(actions.isEmpty())
        queuedUiActions.single().invoke()

        assertEquals(
            listOf(
                SpecWorkflowExternalEventAction.OpenWorkflow(
                    request = SpecToolWindowOpenRequest(
                        workflowId = "wf-target",
                        taskId = "task-7",
                        focusedStage = StageId.IMPLEMENT,
                    ),
                ),
            ),
            actions,
        )
    }

    @Test
    fun `register should ignore queued tool window callbacks after disposal`() {
        val subscriber = FakeSubscriber()
        val queuedUiActions = mutableListOf<() -> Unit>()
        val actions = mutableListOf<SpecWorkflowExternalEventAction>()
        var disposed = false
        val adapter = createAdapter(
            subscriber = subscriber,
            invokeLater = { action -> queuedUiActions += action },
            isDisposed = { disposed },
            onAction = actions::add,
        )

        adapter.register()
        subscriber.toolWindowListener?.onCreateWorkflowRequested(WorkflowTemplate.QUICK_TASK)
        disposed = true
        queuedUiActions.single().invoke()

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `register should dispatch workflow changed events directly`() {
        val subscriber = FakeSubscriber()
        val actions = mutableListOf<SpecWorkflowExternalEventAction>()
        val adapter = createAdapter(
            subscriber = subscriber,
            onAction = actions::add,
        )

        adapter.register()
        subscriber.workflowChangedListener?.onWorkflowChanged(
            SpecWorkflowChangedEvent(
                workflowId = " wf-refresh ",
                reason = "workflow_updated",
            ),
        )

        assertEquals(
            listOf(
                SpecWorkflowExternalEventAction.RefreshWorkflows(selectWorkflowId = "wf-refresh"),
            ),
            actions,
        )
    }

    @Test
    fun `register should dispatch document file changes for the selected workflow`() {
        val subscriber = FakeSubscriber()
        val actions = mutableListOf<SpecWorkflowExternalEventAction>()
        val adapter = createAdapter(
            subscriber = subscriber,
            selectedWorkflowId = { "WF-42" },
            projectBasePath = { " D:/repo/ " },
            onAction = actions::add,
        )

        adapter.register()
        subscriber.documentFileChangeListener?.invoke(
            listOf(
                "D:/repo/.spec-coding/specs/WF-42/notes.txt",
                "D:/repo/.spec-coding/specs/WF-42/tasks.md",
            ),
        )

        assertEquals(
            listOf(
                SpecWorkflowExternalEventAction.ScheduleDocumentReload(workflowId = "WF-42"),
            ),
            actions,
        )
    }

    @Test
    fun `register should ignore blank select workflow requests`() {
        val subscriber = FakeSubscriber()
        val queuedUiActions = mutableListOf<() -> Unit>()
        val actions = mutableListOf<SpecWorkflowExternalEventAction>()
        val adapter = createAdapter(
            subscriber = subscriber,
            invokeLater = { action -> queuedUiActions += action },
            onAction = actions::add,
        )

        adapter.register()
        subscriber.toolWindowListener?.onSelectWorkflowRequested("   ")
        queuedUiActions.single().invoke()

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `register should only subscribe once`() {
        val subscriber = FakeSubscriber()
        val adapter = createAdapter(subscriber = subscriber)

        adapter.register()
        adapter.register()

        assertEquals(1, subscriber.toolWindowSubscriptions)
        assertEquals(1, subscriber.workflowChangedSubscriptions)
        assertEquals(1, subscriber.documentFileSubscriptions)
    }

    private fun createAdapter(
        subscriber: FakeSubscriber,
        selectedWorkflowId: () -> String? = { null },
        projectBasePath: () -> String? = { null },
        invokeLater: ((() -> Unit) -> Unit) = { action -> action() },
        isDisposed: () -> Boolean = { false },
        onAction: (SpecWorkflowExternalEventAction) -> Unit = {},
    ): SpecWorkflowExternalEventSubscriptionAdapter {
        return SpecWorkflowExternalEventSubscriptionAdapter(
            subscriber = subscriber,
            externalEventCoordinator = externalEventCoordinator,
            selectedWorkflowId = selectedWorkflowId,
            projectBasePath = projectBasePath,
            invokeLater = invokeLater,
            isDisposed = isDisposed,
            onAction = onAction,
        )
    }

    private class FakeSubscriber : SpecWorkflowExternalEventSubscriber {
        var toolWindowListener: SpecToolWindowControlListener? = null
            private set
        var workflowChangedListener: SpecWorkflowChangedListener? = null
            private set
        var documentFileChangeListener: ((List<String>) -> Unit)? = null
            private set
        var toolWindowSubscriptions: Int = 0
            private set
        var workflowChangedSubscriptions: Int = 0
            private set
        var documentFileSubscriptions: Int = 0
            private set

        override fun subscribeToolWindowControl(listener: SpecToolWindowControlListener) {
            toolWindowSubscriptions += 1
            toolWindowListener = listener
        }

        override fun subscribeWorkflowChanged(listener: SpecWorkflowChangedListener) {
            workflowChangedSubscriptions += 1
            workflowChangedListener = listener
        }

        override fun subscribeDocumentFileChanges(after: (List<String>) -> Unit) {
            documentFileSubscriptions += 1
            documentFileChangeListener = after
        }
    }
}
