package com.eacape.speccodingplugin.ui.spec

internal interface SpecWorkflowWorkbenchCommandCallbacks {
    fun advance()
    fun jump(workflowId: String, targetStage: com.eacape.speccodingplugin.spec.StageId)
    fun jumpFallback()
    fun rollback(workflowId: String, targetStage: com.eacape.speccodingplugin.spec.StageId)
    fun rollbackFallback()
    fun selectTask(taskId: String)
    fun requestTaskExecution(taskId: String): Boolean
    fun requestTaskCompletion(taskId: String): Boolean
    fun cancelTaskExecution(taskId: String)
    fun openTaskChat(workflowId: String, taskId: String)
    fun runVerify(workflowId: String)
    fun previewVerifyPlan(workflowId: String)
    fun openVerification(workflowId: String)
    fun showDelta()
    fun completeWorkflow()
    fun archiveWorkflow()
    fun showStatus(text: String)
}

internal class SpecWorkflowWorkbenchCommandRouter(
    private val callbacks: SpecWorkflowWorkbenchCommandCallbacks,
    private val taskExecutionFailedMessage: (String) -> String,
    private val taskCompletionFailedMessage: (String) -> String,
) {

    fun dispatch(action: SpecWorkflowWorkbenchAction, workflowId: String?) {
        when (action.kind) {
            SpecWorkflowWorkbenchActionKind.ADVANCE -> callbacks.advance()
            SpecWorkflowWorkbenchActionKind.JUMP -> {
                val targetStage = action.targetStage
                if (workflowId != null && targetStage != null) {
                    callbacks.jump(workflowId, targetStage)
                } else {
                    callbacks.jumpFallback()
                }
            }

            SpecWorkflowWorkbenchActionKind.ROLLBACK -> {
                val targetStage = action.targetStage
                if (workflowId != null && targetStage != null) {
                    callbacks.rollback(workflowId, targetStage)
                } else {
                    callbacks.rollbackFallback()
                }
            }

            SpecWorkflowWorkbenchActionKind.START_TASK,
            SpecWorkflowWorkbenchActionKind.RESUME_TASK,
            -> {
                val taskId = action.taskId ?: return
                callbacks.selectTask(taskId)
                if (!callbacks.requestTaskExecution(taskId)) {
                    callbacks.showStatus(taskExecutionFailedMessage(taskId))
                }
            }

            SpecWorkflowWorkbenchActionKind.COMPLETE_TASK -> {
                val taskId = action.taskId ?: return
                callbacks.selectTask(taskId)
                if (!callbacks.requestTaskCompletion(taskId)) {
                    callbacks.showStatus(taskCompletionFailedMessage(taskId))
                }
            }

            SpecWorkflowWorkbenchActionKind.STOP_TASK_EXECUTION -> {
                val taskId = action.taskId ?: return
                callbacks.cancelTaskExecution(taskId)
            }

            SpecWorkflowWorkbenchActionKind.OPEN_TASK_CHAT -> {
                val taskId = action.taskId ?: return
                val activeWorkflowId = workflowId ?: return
                callbacks.openTaskChat(activeWorkflowId, taskId)
            }

            SpecWorkflowWorkbenchActionKind.RUN_VERIFY -> {
                val activeWorkflowId = workflowId ?: return
                callbacks.runVerify(activeWorkflowId)
            }

            SpecWorkflowWorkbenchActionKind.PREVIEW_VERIFY_PLAN -> {
                val activeWorkflowId = workflowId ?: return
                callbacks.previewVerifyPlan(activeWorkflowId)
            }

            SpecWorkflowWorkbenchActionKind.OPEN_VERIFICATION -> {
                val activeWorkflowId = workflowId ?: return
                callbacks.openVerification(activeWorkflowId)
            }

            SpecWorkflowWorkbenchActionKind.SHOW_DELTA -> callbacks.showDelta()
            SpecWorkflowWorkbenchActionKind.COMPLETE_WORKFLOW -> callbacks.completeWorkflow()
            SpecWorkflowWorkbenchActionKind.ARCHIVE_WORKFLOW -> callbacks.archiveWorkflow()
        }
    }
}
