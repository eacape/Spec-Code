package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.TaskVerificationResult

internal data class SpecWorkflowTaskMutationBackgroundRequest(
    val title: String,
    val task: () -> Unit,
    val onSuccess: () -> Unit,
)

internal class SpecWorkflowTaskMutationCoordinator(
    private val runBackground: (SpecWorkflowTaskMutationBackgroundRequest) -> Unit,
    private val applyStatusTransition: (
        workflowId: String,
        taskId: String,
        to: TaskStatus,
        auditContext: Map<String, String>,
    ) -> Unit,
    private val persistDependsOn: (
        workflowId: String,
        taskId: String,
        dependsOn: List<String>,
    ) -> Unit,
    private val applyTaskCompletion: (
        workflowId: String,
        taskId: String,
        relatedFiles: List<String>,
        verificationResult: TaskVerificationResult?,
        auditContext: Map<String, String>,
    ) -> Unit,
    private val storeVerificationResult: (
        workflowId: String,
        taskId: String,
        verificationResult: TaskVerificationResult,
        auditContext: Map<String, String>,
    ) -> Unit,
    private val removeVerificationResult: (
        workflowId: String,
        taskId: String,
        auditContext: Map<String, String>,
    ) -> Unit,
    private val setStatusText: (String) -> Unit,
    private val publishWorkflowChatRefresh: (workflowId: String, taskId: String, reason: String) -> Unit,
    private val reloadCurrentWorkflow: () -> Unit,
) {

    fun transitionStatus(
        workflowId: String,
        taskId: String,
        to: TaskStatus,
        auditContext: Map<String, String>,
    ) {
        runMutation(
            title = SpecCodingBundle.message("spec.toolwindow.tasks.status.progress"),
            task = {
                applyStatusTransition(
                    workflowId,
                    taskId,
                    to,
                    auditContext,
                )
            },
            onSuccess = {
                setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.status.updated", taskId, to.name))
                publishWorkflowChatRefresh(workflowId, taskId, "spec_task_status_transition")
                reloadCurrentWorkflow()
            },
        )
    }

    fun updateDependsOn(
        workflowId: String,
        taskId: String,
        dependsOn: List<String>,
    ) {
        runMutation(
            title = SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.progress"),
            task = {
                persistDependsOn(
                    workflowId,
                    taskId,
                    dependsOn,
                )
            },
            onSuccess = {
                setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.updated", taskId))
                reloadCurrentWorkflow()
            },
        )
    }

    fun completeTask(
        workflowId: String,
        taskId: String,
        files: List<String>,
        verificationResult: TaskVerificationResult?,
        auditContext: Map<String, String>,
    ) {
        runMutation(
            title = SpecCodingBundle.message("spec.toolwindow.tasks.complete.progress"),
            task = {
                applyTaskCompletion(
                    workflowId,
                    taskId,
                    files,
                    verificationResult,
                    auditContext,
                )
            },
            onSuccess = {
                setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.complete.updated", taskId))
                publishWorkflowChatRefresh(workflowId, taskId, "spec_task_completed")
                reloadCurrentWorkflow()
            },
        )
    }

    fun updateVerificationResult(
        workflowId: String,
        taskId: String,
        verificationResult: TaskVerificationResult?,
        existingVerificationResult: TaskVerificationResult?,
        auditContext: Map<String, String>,
    ) {
        runMutation(
            title = SpecCodingBundle.message("spec.toolwindow.tasks.verification.progress"),
            task = {
                when {
                    verificationResult != null -> storeVerificationResult(
                        workflowId,
                        taskId,
                        verificationResult,
                        auditContext,
                    )

                    existingVerificationResult != null -> removeVerificationResult(
                        workflowId,
                        taskId,
                        auditContext,
                    )
                }
            },
            onSuccess = {
                val statusKey = if (verificationResult == null) {
                    "spec.toolwindow.tasks.verification.cleared"
                } else {
                    "spec.toolwindow.tasks.verification.updated"
                }
                setStatusText(SpecCodingBundle.message(statusKey, taskId))
                publishWorkflowChatRefresh(workflowId, taskId, "spec_task_verification_updated")
                reloadCurrentWorkflow()
            },
        )
    }

    private fun runMutation(
        title: String,
        task: () -> Unit,
        onSuccess: () -> Unit,
    ) {
        runBackground(
            SpecWorkflowTaskMutationBackgroundRequest(
                title = title,
                task = task,
                onSuccess = onSuccess,
            ),
        )
    }
}
