package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowWorkbenchActionCoordinatorTest {

    @Test
    fun `stage and task actions should delegate to injected callbacks`() {
        val recorder = RecordingCallbacks(
            requestTaskExecutionResult = false,
            requestTaskCompletionResult = true,
        )
        val coordinator = coordinator(recorder)

        coordinator.advance()
        coordinator.jump("wf-1", StageId.VERIFY)
        coordinator.jumpFallback()
        coordinator.rollback("wf-2", StageId.DESIGN)
        coordinator.rollbackFallback()
        coordinator.selectTask("task-1")
        assertFalse(coordinator.requestTaskExecution("task-2"))
        assertTrue(coordinator.requestTaskCompletion("task-3"))
        coordinator.cancelTaskExecution("task-4")
        coordinator.openTaskChat("wf-3", "task-5")

        assertEquals(
            listOf(
                "advance",
                "jump:wf-1:VERIFY",
                "jumpFallback",
                "rollback:wf-2:DESIGN",
                "rollbackFallback",
                "selectTask:task-1",
                "requestTaskExecution:task-2",
                "requestTaskCompletion:task-3",
                "cancelTaskExecution:task-4",
                "openTaskChat:wf-3:task-5",
            ),
            recorder.events,
        )
    }

    @Test
    fun `workflow actions and status should delegate to injected callbacks`() {
        val recorder = RecordingCallbacks()
        val coordinator = coordinator(recorder)

        coordinator.runVerify("wf-1")
        coordinator.openVerification("wf-2")
        coordinator.showDelta()
        coordinator.completeWorkflow()
        coordinator.archiveWorkflow()
        coordinator.showStatus("done")

        assertEquals(
            listOf(
                "runVerify:wf-1",
                "openVerification:wf-2",
                "showDelta",
                "completeWorkflow",
                "archiveWorkflow",
                "showStatus:done",
            ),
            recorder.events,
        )
    }

    @Test
    fun `previewVerifyPlan should run background summary and show info`() {
        val recorder = RecordingCallbacks()
        val coordinator = coordinator(recorder)

        coordinator.previewVerifyPlan(" wf-preview ")

        assertEquals(
            listOf(
                "runPreviewInBackground:${SpecCodingBundle.message("spec.action.verify.preview")}",
                "buildSummary:wf-preview",
                "showPreviewSummary:${SpecCodingBundle.message("spec.action.verify.confirm.title")}:summary:wf-preview",
            ),
            recorder.events,
        )
    }

    @Test
    fun `previewVerifyPlan should ignore blank workflow id`() {
        val recorder = RecordingCallbacks()
        val coordinator = coordinator(recorder)

        coordinator.previewVerifyPlan("   ")

        assertTrue(recorder.events.isEmpty())
    }

    private fun coordinator(recorder: RecordingCallbacks): SpecWorkflowWorkbenchActionCoordinator {
        return SpecWorkflowWorkbenchActionCoordinator(
            onAdvance = recorder::advance,
            onJump = recorder::jump,
            onJumpFallback = recorder::jumpFallback,
            onRollback = recorder::rollback,
            onRollbackFallback = recorder::rollbackFallback,
            onSelectTask = recorder::selectTask,
            requestTaskExecutionAction = recorder::requestTaskExecution,
            requestTaskCompletionAction = recorder::requestTaskCompletion,
            onCancelTaskExecution = recorder::cancelTaskExecution,
            onOpenTaskChat = recorder::openTaskChat,
            onRunVerify = recorder::runVerify,
            buildVerifyPlanPreviewSummary = recorder::buildSummary,
            runPreviewInBackground = recorder::runPreviewInBackground,
            showPreviewSummary = recorder::showPreviewSummary,
            onOpenVerification = recorder::openVerification,
            onShowDelta = recorder::showDelta,
            onCompleteWorkflow = recorder::completeWorkflow,
            onArchiveWorkflow = recorder::archiveWorkflow,
            onShowStatus = recorder::showStatus,
        )
    }

    private class RecordingCallbacks(
        private val requestTaskExecutionResult: Boolean = true,
        private val requestTaskCompletionResult: Boolean = true,
    ) {
        val events = mutableListOf<String>()

        fun advance() {
            events += "advance"
        }

        fun jump(workflowId: String, targetStage: StageId) {
            events += "jump:$workflowId:${targetStage.name}"
        }

        fun jumpFallback() {
            events += "jumpFallback"
        }

        fun rollback(workflowId: String, targetStage: StageId) {
            events += "rollback:$workflowId:${targetStage.name}"
        }

        fun rollbackFallback() {
            events += "rollbackFallback"
        }

        fun selectTask(taskId: String) {
            events += "selectTask:$taskId"
        }

        fun requestTaskExecution(taskId: String): Boolean {
            events += "requestTaskExecution:$taskId"
            return requestTaskExecutionResult
        }

        fun requestTaskCompletion(taskId: String): Boolean {
            events += "requestTaskCompletion:$taskId"
            return requestTaskCompletionResult
        }

        fun cancelTaskExecution(taskId: String) {
            events += "cancelTaskExecution:$taskId"
        }

        fun openTaskChat(workflowId: String, taskId: String) {
            events += "openTaskChat:$workflowId:$taskId"
        }

        fun runVerify(workflowId: String) {
            events += "runVerify:$workflowId"
        }

        fun buildSummary(workflowId: String): String {
            events += "buildSummary:$workflowId"
            return "summary:$workflowId"
        }

        fun runPreviewInBackground(
            title: String,
            task: () -> String,
            onSuccess: (String) -> Unit,
        ) {
            events += "runPreviewInBackground:$title"
            onSuccess(task())
        }

        fun showPreviewSummary(title: String, message: String) {
            events += "showPreviewSummary:$title:$message"
        }

        fun openVerification(workflowId: String) {
            events += "openVerification:$workflowId"
        }

        fun showDelta() {
            events += "showDelta"
        }

        fun completeWorkflow() {
            events += "completeWorkflow"
        }

        fun archiveWorkflow() {
            events += "archiveWorkflow"
        }

        fun showStatus(text: String) {
            events += "showStatus:$text"
        }
    }
}
