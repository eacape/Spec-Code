package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.spec.SpecTaskExecutionService
import com.eacape.speccodingplugin.spec.TaskExecutionRun
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class SpecWorkflowTaskExecutionRequest(
    val workflowId: String,
    val taskId: String,
    val providerId: String,
    val modelId: String,
    val operationMode: OperationMode,
    val sessionId: String?,
    val retry: Boolean,
    val auditContext: Map<String, String>,
)

internal data class SpecWorkflowTaskExecutionCancelRequest(
    val workflowId: String,
    val taskId: String,
)

internal data class SpecWorkflowTaskExecutionBackgroundRequest<T>(
    val title: String,
    val task: () -> T,
    val onSuccess: (T) -> Unit,
    val onCancelRequested: (() -> Unit)? = null,
    val onCancelled: (() -> Unit)? = null,
    val onFailure: ((Throwable) -> Unit)? = null,
)

internal interface SpecWorkflowTaskExecutionBackgroundRunner {
    fun <T> run(request: SpecWorkflowTaskExecutionBackgroundRequest<T>)
}

internal class SpecWorkflowTaskExecutionCoordinator(
    private val backgroundRunner: SpecWorkflowTaskExecutionBackgroundRunner,
    private val listRuns: (workflowId: String, taskId: String) -> List<TaskExecutionRun>,
    private val startExecution: (
        request: SpecWorkflowTaskExecutionRequest,
        onRequestRegistered: (SpecTaskExecutionService.TaskExecutionCancellationHandle) -> Unit,
    ) -> SpecTaskExecutionService.TaskAiExecutionResult,
    private val retryExecution: (
        request: SpecWorkflowTaskExecutionRequest,
        previousRunId: String?,
        onRequestRegistered: (SpecTaskExecutionService.TaskExecutionCancellationHandle) -> Unit,
    ) -> SpecTaskExecutionService.TaskAiExecutionResult,
    private val cancelExecutionRun: (workflowId: String, runId: String) -> Unit,
    private val cancelExecution: (workflowId: String, taskId: String) -> Unit,
    private val openWorkflowChatExecutionSession: (sessionId: String, workflowId: String) -> Unit,
    private val setStatusText: (String) -> Unit,
    private val setCancelRequestedStatusText: (String) -> Unit,
    private val publishWorkflowChatRefresh: (workflowId: String, taskId: String?, reason: String) -> Unit,
    private val reloadCurrentWorkflow: () -> Unit,
    private val renderFailureMessage: (Throwable, String) -> String,
    private val showExecutionFailureDialog: (title: String, message: String) -> Unit,
) {

    fun execute(request: SpecWorkflowTaskExecutionRequest) {
        val progressKey = if (request.retry) {
            "spec.toolwindow.tasks.retry.progress"
        } else {
            "spec.toolwindow.tasks.execute.progress"
        }
        val cancellationHandleRef =
            AtomicReference<SpecTaskExecutionService.TaskExecutionCancellationHandle?>()
        val cancelRequested = AtomicBoolean(false)
        val chatSessionOpened = AtomicBoolean(false)

        backgroundRunner.run(
            SpecWorkflowTaskExecutionBackgroundRequest(
                title = SpecCodingBundle.message(progressKey, request.taskId),
                task = {
                    val onRequestRegistered: (SpecTaskExecutionService.TaskExecutionCancellationHandle) -> Unit = { handle ->
                        cancellationHandleRef.set(handle)
                        if (chatSessionOpened.compareAndSet(false, true)) {
                            openWorkflowChatExecutionSession(
                                handle.sessionId,
                                handle.workflowId,
                            )
                        }
                        if (cancelRequested.get()) {
                            cancelExecutionRun(
                                handle.workflowId,
                                handle.runId,
                            )
                        }
                    }
                    val previousRunId = if (request.retry) {
                        listRuns(request.workflowId, request.taskId)
                            .firstOrNull { run -> run.status.isTerminal() }
                            ?.runId
                    } else {
                        null
                    }
                    if (request.retry) {
                        retryExecution(
                            request,
                            previousRunId,
                            onRequestRegistered,
                        )
                    } else {
                        startExecution(request, onRequestRegistered)
                    }
                },
                onSuccess = { result ->
                    val statusKey = if (request.retry) {
                        "spec.toolwindow.tasks.retry.updated"
                    } else {
                        "spec.toolwindow.tasks.execute.updated"
                    }
                    setStatusText(SpecCodingBundle.message(statusKey, request.taskId, result.sessionTitle))
                    publishWorkflowChatRefresh(request.workflowId, request.taskId, "spec_task_execution_updated")
                    reloadCurrentWorkflow()
                },
                onCancelRequested = {
                    if (cancelRequested.compareAndSet(false, true)) {
                        cancellationHandleRef.get()?.let { handle ->
                            cancelExecutionRun(
                                handle.workflowId,
                                handle.runId,
                            )
                        }
                        setCancelRequestedStatusText(
                            SpecCodingBundle.message(
                                "spec.toolwindow.tasks.execution.cancel.requested",
                                request.taskId,
                            ),
                        )
                    }
                },
                onCancelled = {
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.toolwindow.tasks.execution.cancelled",
                            request.taskId,
                        ),
                    )
                    publishWorkflowChatRefresh(
                        request.workflowId,
                        request.taskId,
                        "spec_task_execution_cancelled",
                    )
                    reloadCurrentWorkflow()
                },
                onFailure = { error ->
                    val message = renderFailureMessage(
                        error,
                        SpecCodingBundle.message("common.unknown"),
                    )
                    setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                    publishWorkflowChatRefresh(
                        request.workflowId,
                        request.taskId,
                        "spec_task_execution_failed",
                    )
                    reloadCurrentWorkflow()
                    showExecutionFailureDialog(
                        SpecCodingBundle.message(progressKey, request.taskId),
                        message,
                    )
                },
            ),
        )
    }

    fun cancel(request: SpecWorkflowTaskExecutionCancelRequest) {
        backgroundRunner.run(
            SpecWorkflowTaskExecutionBackgroundRequest(
                title = SpecCodingBundle.message(
                    "spec.toolwindow.tasks.execution.cancel.progress",
                    request.taskId,
                ),
                task = {
                    cancelExecution(
                        request.workflowId,
                        request.taskId,
                    )
                },
                onSuccess = {
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.toolwindow.tasks.execution.cancelled",
                            request.taskId,
                        ),
                    )
                    publishWorkflowChatRefresh(
                        request.workflowId,
                        request.taskId,
                        "spec_task_execution_cancelled",
                    )
                    reloadCurrentWorkflow()
                },
            ),
        )
    }
}
