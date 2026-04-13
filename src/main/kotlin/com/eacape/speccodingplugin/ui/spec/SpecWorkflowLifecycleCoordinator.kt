package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecArchiveResult
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus

internal data class SpecWorkflowLifecycleBackgroundRequest<T>(
    val task: () -> T,
    val onSuccess: (T) -> Unit,
    val onFailure: (Throwable) -> Unit = {},
)

internal interface SpecWorkflowLifecycleBackgroundRunner {
    fun <T> run(request: SpecWorkflowLifecycleBackgroundRequest<T>)
}

internal class SpecWorkflowLifecycleCoordinator(
    private val backgroundRunner: SpecWorkflowLifecycleBackgroundRunner,
    private val completeWorkflow: (workflowId: String) -> Result<SpecWorkflow>,
    private val pauseWorkflow: (workflowId: String) -> Result<SpecWorkflow>,
    private val resumeWorkflow: (workflowId: String) -> Result<SpecWorkflow>,
    private val archiveWorkflow: (workflowId: String) -> Result<SpecArchiveResult>,
    private val confirmArchive: (workflowId: String) -> Boolean,
    private val reloadCurrentWorkflow: () -> Unit,
    private val refreshWorkflows: () -> Unit,
    private val clearOpenedWorkflowIfSelected: (workflowId: String) -> Unit,
    private val setStatusText: (String) -> Unit,
    private val renderFailureMessage: (Throwable) -> String,
) {

    fun complete(workflowId: String?) {
        val normalizedWorkflowId = workflowId.normalizeWorkflowId() ?: return
        backgroundRunner.run(
            SpecWorkflowLifecycleBackgroundRequest(
                task = {
                    completeWorkflow(normalizedWorkflowId).getOrThrow()
                },
                onSuccess = { workflow ->
                    reloadCurrentWorkflow()
                    refreshWorkflows()
                    setStatusText(
                        SpecCodingBundle.message("toolwindow.spec.command.completed", workflow.id),
                    )
                },
                onFailure = { error ->
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.workflow.error",
                            renderFailureMessage(error),
                        ),
                    )
                },
            ),
        )
    }

    fun togglePauseResume(workflowId: String?, isPaused: Boolean) {
        val normalizedWorkflowId = workflowId.normalizeWorkflowId() ?: return
        backgroundRunner.run(
            SpecWorkflowLifecycleBackgroundRequest(
                task = {
                    if (isPaused) {
                        resumeWorkflow(normalizedWorkflowId).getOrThrow()
                    } else {
                        pauseWorkflow(normalizedWorkflowId).getOrThrow()
                    }
                },
                onSuccess = {
                    reloadCurrentWorkflow()
                    refreshWorkflows()
                },
                onFailure = { error ->
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.workflow.error",
                            renderFailureMessage(error),
                        ),
                    )
                },
            ),
        )
    }

    fun archive(workflowId: String?, status: WorkflowStatus?) {
        val normalizedWorkflowId = workflowId.normalizeWorkflowId()
        if (normalizedWorkflowId == null) {
            setStatusText(SpecCodingBundle.message("spec.workflow.archive.selectFirst"))
            return
        }
        if (status != WorkflowStatus.COMPLETED) {
            setStatusText(SpecCodingBundle.message("spec.workflow.archive.onlyCompleted"))
            return
        }
        if (!confirmArchive(normalizedWorkflowId)) {
            return
        }
        backgroundRunner.run(
            SpecWorkflowLifecycleBackgroundRequest(
                task = {
                    archiveWorkflow(normalizedWorkflowId).getOrThrow()
                },
                onSuccess = {
                    clearOpenedWorkflowIfSelected(normalizedWorkflowId)
                    refreshWorkflows()
                    setStatusText(
                        SpecCodingBundle.message("spec.workflow.archive.done", normalizedWorkflowId),
                    )
                },
                onFailure = { error ->
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.workflow.archive.failed",
                            renderFailureMessage(error),
                        ),
                    )
                },
            ),
        )
    }

    private fun String?.normalizeWorkflowId(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
