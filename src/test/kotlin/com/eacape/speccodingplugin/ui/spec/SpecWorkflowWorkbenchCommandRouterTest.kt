package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowWorkbenchCommandRouterTest {

    @Test
    fun `dispatch should route targeted jump when workflow context is present`() {
        val callbacks = RecordingCallbacks()
        val router = router(callbacks)

        router.dispatch(
            action = SpecWorkflowWorkbenchAction(
                kind = SpecWorkflowWorkbenchActionKind.JUMP,
                label = "Jump",
                enabled = true,
                targetStage = StageId.VERIFY,
            ),
            workflowId = "wf-1",
        )

        assertEquals(listOf("jump:wf-1:VERIFY"), callbacks.events)
    }

    @Test
    fun `dispatch should fallback when jump lacks workflow context`() {
        val callbacks = RecordingCallbacks()
        val router = router(callbacks)

        router.dispatch(
            action = SpecWorkflowWorkbenchAction(
                kind = SpecWorkflowWorkbenchActionKind.JUMP,
                label = "Jump",
                enabled = true,
                targetStage = StageId.VERIFY,
            ),
            workflowId = null,
        )

        assertEquals(listOf("jumpFallback"), callbacks.events)
    }

    @Test
    fun `dispatch should surface task execution failure through status callback`() {
        val callbacks = RecordingCallbacks(requestTaskExecutionResult = false)
        val router = router(callbacks)

        router.dispatch(
            action = SpecWorkflowWorkbenchAction(
                kind = SpecWorkflowWorkbenchActionKind.START_TASK,
                label = "Start",
                enabled = true,
                taskId = "task-9",
            ),
            workflowId = "wf-1",
        )

        assertEquals(listOf("selectTask:task-9", "requestTaskExecution:task-9", "status:execute failed task-9"), callbacks.events)
    }

    @Test
    fun `dispatch should no-op workflow scoped actions without workflow id`() {
        val callbacks = RecordingCallbacks()
        val router = router(callbacks)

        router.dispatch(
            action = SpecWorkflowWorkbenchAction(
                kind = SpecWorkflowWorkbenchActionKind.RUN_VERIFY,
                label = "Verify",
                enabled = true,
            ),
            workflowId = null,
        )

        assertTrue(callbacks.events.isEmpty())
    }

    private fun router(callbacks: RecordingCallbacks): SpecWorkflowWorkbenchCommandRouter {
        return SpecWorkflowWorkbenchCommandRouter(
            callbacks = callbacks,
            taskExecutionFailedMessage = { taskId -> "execute failed $taskId" },
            taskCompletionFailedMessage = { taskId -> "complete failed $taskId" },
        )
    }

    private class RecordingCallbacks(
        private val requestTaskExecutionResult: Boolean = true,
        private val requestTaskCompletionResult: Boolean = true,
    ) : SpecWorkflowWorkbenchCommandCallbacks {

        val events = mutableListOf<String>()

        override fun advance() {
            events += "advance"
        }

        override fun jump(workflowId: String, targetStage: StageId) {
            events += "jump:$workflowId:${targetStage.name}"
        }

        override fun jumpFallback() {
            events += "jumpFallback"
        }

        override fun rollback(workflowId: String, targetStage: StageId) {
            events += "rollback:$workflowId:${targetStage.name}"
        }

        override fun rollbackFallback() {
            events += "rollbackFallback"
        }

        override fun selectTask(taskId: String) {
            events += "selectTask:$taskId"
        }

        override fun requestTaskExecution(taskId: String): Boolean {
            events += "requestTaskExecution:$taskId"
            return requestTaskExecutionResult
        }

        override fun requestTaskCompletion(taskId: String): Boolean {
            events += "requestTaskCompletion:$taskId"
            return requestTaskCompletionResult
        }

        override fun cancelTaskExecution(taskId: String) {
            events += "cancelTaskExecution:$taskId"
        }

        override fun openTaskChat(workflowId: String, taskId: String) {
            events += "openTaskChat:$workflowId:$taskId"
        }

        override fun runVerify(workflowId: String) {
            events += "runVerify:$workflowId"
        }

        override fun previewVerifyPlan(workflowId: String) {
            events += "previewVerifyPlan:$workflowId"
        }

        override fun openVerification(workflowId: String) {
            events += "openVerification:$workflowId"
        }

        override fun showDelta() {
            events += "showDelta"
        }

        override fun completeWorkflow() {
            events += "completeWorkflow"
        }

        override fun archiveWorkflow() {
            events += "archiveWorkflow"
        }

        override fun showStatus(text: String) {
            events += "status:$text"
        }
    }
}
