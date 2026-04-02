package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.session.WorkflowChatActionIntent
import com.eacape.speccodingplugin.session.WorkflowChatBinding
import com.eacape.speccodingplugin.session.WorkflowChatEntrySource
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.ui.WorkflowChatOpenRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowTaskChatCoordinatorTest {

    @Test
    fun `openExecutionSession should activate chat toolwindow open session and publish refresh`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)

        coordinator.openExecutionSession(" session-1 ", " wf-1 ")

        assertEquals(1, recorder.activationCalls)
        assertEquals(listOf("session-1"), recorder.openedSessionIds)
        assertEquals(
            listOf(RefreshEvent("wf-1", null, "spec_task_execution_session_opened")),
            recorder.refreshEvents,
        )
        assertTrue(recorder.workflowChatRequests.isEmpty())
        assertTrue(recorder.deferredActions.isEmpty())
    }

    @Test
    fun `openExecutionSession should ignore blank ids and unavailable toolwindow`() {
        val recorder = RecordingEnvironment().apply {
            activationResult = false
        }
        val coordinator = coordinator(recorder)

        coordinator.openExecutionSession(" ", "wf-1")
        coordinator.openExecutionSession("session-2", " ")
        coordinator.openExecutionSession("session-3", "wf-3")

        assertEquals(1, recorder.activationCalls)
        assertTrue(recorder.openedSessionIds.isEmpty())
        assertTrue(recorder.refreshEvents.isEmpty())
    }

    @Test
    fun `openTaskWorkflowChat should schedule workflow chat request after toolwindow activation`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)

        coordinator.openTaskWorkflowChat(" wf-2 ", " T-2 ")

        assertEquals(1, recorder.activationCalls)
        assertTrue(recorder.workflowChatRequests.isEmpty())

        recorder.flushDeferredActions()

        assertEquals(
            listOf(
                WorkflowChatOpenRequest(
                    binding = WorkflowChatBinding(
                        workflowId = "wf-2",
                        focusedStage = StageId.IMPLEMENT,
                        source = WorkflowChatEntrySource.TASK_PANEL,
                        actionIntent = WorkflowChatActionIntent.DISCUSS,
                    ),
                ),
            ),
            recorder.workflowChatRequests,
        )
        assertTrue(recorder.openedSessionIds.isEmpty())
        assertTrue(recorder.refreshEvents.isEmpty())
    }

    @Test
    fun `openTaskWorkflowChat should skip publish when disposed before deferred open`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)

        coordinator.openTaskWorkflowChat("wf-3", "T-3")
        recorder.disposed = true

        recorder.flushDeferredActions()

        assertEquals(1, recorder.activationCalls)
        assertTrue(recorder.workflowChatRequests.isEmpty())
    }

    @Test
    fun `openTaskWorkflowChat should ignore blank task or unavailable toolwindow`() {
        val recorder = RecordingEnvironment().apply {
            activationResult = false
        }
        val coordinator = coordinator(recorder)

        coordinator.openTaskWorkflowChat("wf-4", " ")
        coordinator.openTaskWorkflowChat("wf-4", "T-4")

        assertEquals(1, recorder.activationCalls)
        assertTrue(recorder.workflowChatRequests.isEmpty())
        assertTrue(recorder.deferredActions.isEmpty())
    }

    private fun coordinator(recorder: RecordingEnvironment): SpecWorkflowTaskChatCoordinator {
        return SpecWorkflowTaskChatCoordinator(
            activateChatToolWindow = {
                recorder.activationCalls += 1
                recorder.activationResult
            },
            invokeLater = { action ->
                recorder.deferredActions += action
            },
            isDisposed = {
                recorder.disposed
            },
            openHistorySession = { sessionId ->
                recorder.openedSessionIds += sessionId
            },
            publishWorkflowChatRefresh = { workflowId, taskId, reason ->
                recorder.refreshEvents += RefreshEvent(workflowId, taskId, reason)
            },
            openWorkflowChat = { request ->
                recorder.workflowChatRequests += request
            },
        )
    }

    private class RecordingEnvironment {
        var activationCalls: Int = 0
        var activationResult: Boolean = true
        var disposed: Boolean = false
        val openedSessionIds = mutableListOf<String>()
        val refreshEvents = mutableListOf<RefreshEvent>()
        val workflowChatRequests = mutableListOf<WorkflowChatOpenRequest>()
        val deferredActions = mutableListOf<() -> Unit>()

        fun flushDeferredActions() {
            val actions = deferredActions.toList()
            deferredActions.clear()
            actions.forEach { action -> action() }
        }
    }

    private data class RefreshEvent(
        val workflowId: String,
        val taskId: String?,
        val reason: String,
    )
}
