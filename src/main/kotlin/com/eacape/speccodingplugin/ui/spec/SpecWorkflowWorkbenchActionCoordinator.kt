package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StageId

internal class SpecWorkflowWorkbenchActionCoordinator(
    private val onAdvance: () -> Unit,
    private val onJump: (workflowId: String, targetStage: StageId) -> Unit,
    private val onJumpFallback: () -> Unit,
    private val onRollback: (workflowId: String, targetStage: StageId) -> Unit,
    private val onRollbackFallback: () -> Unit,
    private val onSelectTask: (taskId: String) -> Unit,
    private val requestTaskExecutionAction: (taskId: String) -> Boolean,
    private val requestTaskCompletionAction: (taskId: String) -> Boolean,
    private val onCancelTaskExecution: (taskId: String) -> Unit,
    private val onOpenTaskChat: (workflowId: String, taskId: String) -> Unit,
    private val onRunVerify: (workflowId: String) -> Unit,
    private val buildVerifyPlanPreviewSummary: (workflowId: String) -> String,
    private val runPreviewInBackground: (
        title: String,
        task: () -> String,
        onSuccess: (String) -> Unit,
    ) -> Unit,
    private val showPreviewSummary: (title: String, message: String) -> Unit,
    private val onOpenVerification: (workflowId: String) -> Unit,
    private val onShowDelta: () -> Unit,
    private val onCompleteWorkflow: () -> Unit,
    private val onArchiveWorkflow: () -> Unit,
    private val onShowStatus: (String) -> Unit,
) : SpecWorkflowWorkbenchCommandCallbacks {

    override fun advance() = onAdvance()

    override fun jump(workflowId: String, targetStage: StageId) = onJump(workflowId, targetStage)

    override fun jumpFallback() = onJumpFallback()

    override fun rollback(workflowId: String, targetStage: StageId) = onRollback(workflowId, targetStage)

    override fun rollbackFallback() = onRollbackFallback()

    override fun selectTask(taskId: String) = onSelectTask(taskId)

    override fun requestTaskExecution(taskId: String): Boolean = requestTaskExecutionAction(taskId)

    override fun requestTaskCompletion(taskId: String): Boolean = requestTaskCompletionAction(taskId)

    override fun cancelTaskExecution(taskId: String) = onCancelTaskExecution(taskId)

    override fun openTaskChat(workflowId: String, taskId: String) = onOpenTaskChat(workflowId, taskId)

    override fun runVerify(workflowId: String) = onRunVerify(workflowId)

    override fun previewVerifyPlan(workflowId: String) {
        val normalizedWorkflowId = workflowId.trim().takeIf { it.isNotEmpty() } ?: return
        runPreviewInBackground(
            SpecCodingBundle.message("spec.action.verify.preview"),
            {
                buildVerifyPlanPreviewSummary(normalizedWorkflowId)
            },
            { summary ->
                showPreviewSummary(
                    SpecCodingBundle.message("spec.action.verify.confirm.title"),
                    summary,
                )
            },
        )
    }

    override fun openVerification(workflowId: String) = onOpenVerification(workflowId)

    override fun showDelta() = onShowDelta()

    override fun completeWorkflow() = onCompleteWorkflow()

    override fun archiveWorkflow() = onArchiveWorkflow()

    override fun showStatus(text: String) = onShowStatus(text)
}
