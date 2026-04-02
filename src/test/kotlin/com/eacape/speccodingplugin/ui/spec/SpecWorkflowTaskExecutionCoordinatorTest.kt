package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.SpecTaskExecutionService
import com.eacape.speccodingplugin.spec.TaskExecutionRun
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowTaskExecutionCoordinatorTest {

    @Test
    fun `execute should start task execution and open workflow chat session`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder, successRunner(recorder))
        val request = executionRequest(taskId = "T-1")

        coordinator.execute(request)

        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.execute.progress", "T-1"),
            recorder.lastBackgroundTitle,
        )
        assertEquals(request, recorder.startRequest)
        assertNull(recorder.retryCall)
        assertTrue(recorder.listRunsCalls.isEmpty())
        assertEquals(
            listOf(OpenChatSessionCall(sessionId = "chat-T-1", workflowId = "wf-1")),
            recorder.openChatCalls,
        )
        assertEquals(
            listOf(SpecCodingBundle.message("spec.toolwindow.tasks.execute.updated", "T-1", "Session T-1")),
            recorder.statusTexts,
        )
        assertEquals(
            listOf(RefreshEvent("wf-1", "T-1", "spec_task_execution_updated")),
            recorder.refreshEvents,
        )
        assertEquals(1, recorder.reloadCalls)
        assertTrue(recorder.cancelRunCalls.isEmpty())
        assertTrue(recorder.cancelTaskCalls.isEmpty())
        assertTrue(recorder.dialogs.isEmpty())
    }

    @Test
    fun `execute should retry with latest terminal run id when retry is requested`() {
        val recorder = RecordingEnvironment().apply {
            listRunsResult = listOf(
                executionRun(runId = "run-live", taskId = "T-2", status = TaskExecutionRunStatus.RUNNING),
                executionRun(runId = "run-failed", taskId = "T-2", status = TaskExecutionRunStatus.FAILED),
                executionRun(runId = "run-succeeded", taskId = "T-2", status = TaskExecutionRunStatus.SUCCEEDED),
            )
        }
        val coordinator = coordinator(recorder, successRunner(recorder))
        val request = executionRequest(taskId = "T-2", retry = true)

        coordinator.execute(request)

        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.retry.progress", "T-2"),
            recorder.lastBackgroundTitle,
        )
        assertEquals(listOf(ListRunsCall("wf-1", "T-2")), recorder.listRunsCalls)
        assertEquals(RetryCall(request = request, previousRunId = "run-failed"), recorder.retryCall)
        assertNull(recorder.startRequest)
        assertEquals(
            listOf(SpecCodingBundle.message("spec.toolwindow.tasks.retry.updated", "T-2", "Session T-2")),
            recorder.statusTexts,
        )
        assertEquals(
            listOf(RefreshEvent("wf-1", "T-2", "spec_task_execution_updated")),
            recorder.refreshEvents,
        )
        assertEquals(1, recorder.reloadCalls)
    }

    @Test
    fun `execute should cancel registered run when cancellation is requested before handle registration`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder, cancelBeforeCompletionRunner(recorder))
        val request = executionRequest(taskId = "T-3")

        coordinator.execute(request)

        assertEquals(
            listOf(CancelRunCall(workflowId = "wf-1", runId = "run-T-3")),
            recorder.cancelRunCalls,
        )
        assertEquals(
            listOf(OpenChatSessionCall(sessionId = "chat-T-3", workflowId = "wf-1")),
            recorder.openChatCalls,
        )
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.toolwindow.tasks.execution.cancel.requested", "T-3"),
                SpecCodingBundle.message("spec.toolwindow.tasks.execution.cancelled", "T-3"),
            ),
            recorder.statusTexts,
        )
        assertEquals(
            listOf(RefreshEvent("wf-1", "T-3", "spec_task_execution_cancelled")),
            recorder.refreshEvents,
        )
        assertEquals(1, recorder.reloadCalls)
        assertTrue(recorder.dialogs.isEmpty())
    }

    @Test
    fun `execute should surface failure status dialog and refresh when execution fails`() {
        val recorder = RecordingEnvironment().apply {
            onStartExecution = { _, _ ->
                throw IllegalStateException("boom")
            }
        }
        val coordinator = coordinator(recorder, failureRunner(recorder))
        val request = executionRequest(taskId = "T-4")

        coordinator.execute(request)

        assertEquals(
            listOf(SpecCodingBundle.message("spec.workflow.error", "boom")),
            recorder.statusTexts,
        )
        assertEquals(
            listOf(RefreshEvent("wf-1", "T-4", "spec_task_execution_failed")),
            recorder.refreshEvents,
        )
        assertEquals(
            listOf(
                DialogCall(
                    title = SpecCodingBundle.message("spec.toolwindow.tasks.execute.progress", "T-4"),
                    message = "boom",
                ),
            ),
            recorder.dialogs,
        )
        assertEquals(1, recorder.reloadCalls)
        assertTrue(recorder.openChatCalls.isEmpty())
    }

    @Test
    fun `cancel should invoke task cancellation service and publish cancelled feedback`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder, successRunner(recorder))

        coordinator.cancel(
            SpecWorkflowTaskExecutionCancelRequest(
                workflowId = "wf-2",
                taskId = "T-5",
            ),
        )

        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.execution.cancel.progress", "T-5"),
            recorder.lastBackgroundTitle,
        )
        assertEquals(
            listOf(CancelTaskCall(workflowId = "wf-2", taskId = "T-5")),
            recorder.cancelTaskCalls,
        )
        assertEquals(
            listOf(SpecCodingBundle.message("spec.toolwindow.tasks.execution.cancelled", "T-5")),
            recorder.statusTexts,
        )
        assertEquals(
            listOf(RefreshEvent("wf-2", "T-5", "spec_task_execution_cancelled")),
            recorder.refreshEvents,
        )
        assertEquals(1, recorder.reloadCalls)
        assertTrue(recorder.openChatCalls.isEmpty())
        assertTrue(recorder.cancelRunCalls.isEmpty())
    }

    private fun coordinator(
        recorder: RecordingEnvironment,
        backgroundRunner: SpecWorkflowTaskExecutionBackgroundRunner,
    ): SpecWorkflowTaskExecutionCoordinator {
        return SpecWorkflowTaskExecutionCoordinator(
            backgroundRunner = backgroundRunner,
            listRuns = { workflowId, taskId ->
                recorder.listRunsCalls += ListRunsCall(workflowId, taskId)
                recorder.listRunsResult
            },
            startExecution = { request, onRequestRegistered ->
                recorder.startRequest = request
                recorder.onStartExecution(request, onRequestRegistered)
            },
            retryExecution = { request, previousRunId, onRequestRegistered ->
                recorder.retryCall = RetryCall(request, previousRunId)
                recorder.onRetryExecution(request, previousRunId, onRequestRegistered)
            },
            cancelExecutionRun = { workflowId, runId ->
                recorder.cancelRunCalls += CancelRunCall(workflowId, runId)
            },
            cancelExecution = { workflowId, taskId ->
                recorder.cancelTaskCalls += CancelTaskCall(workflowId, taskId)
            },
            openWorkflowChatExecutionSession = { sessionId, workflowId ->
                recorder.openChatCalls += OpenChatSessionCall(sessionId, workflowId)
            },
            setStatusText = { text ->
                recorder.statusTexts += text
            },
            setCancelRequestedStatusText = { text ->
                recorder.statusTexts += text
            },
            publishWorkflowChatRefresh = { workflowId, taskId, reason ->
                recorder.refreshEvents += RefreshEvent(workflowId, taskId, reason)
            },
            reloadCurrentWorkflow = {
                recorder.reloadCalls += 1
            },
            renderFailureMessage = { error, fallback ->
                error.message ?: fallback
            },
            showExecutionFailureDialog = { title, message ->
                recorder.dialogs += DialogCall(title, message)
            },
        )
    }

    private fun successRunner(recorder: RecordingEnvironment): SpecWorkflowTaskExecutionBackgroundRunner {
        return object : SpecWorkflowTaskExecutionBackgroundRunner {
            override fun <T> run(request: SpecWorkflowTaskExecutionBackgroundRequest<T>) {
                recorder.lastBackgroundTitle = request.title
                val result = request.task()
                request.onSuccess(result)
            }
        }
    }

    private fun cancelBeforeCompletionRunner(recorder: RecordingEnvironment): SpecWorkflowTaskExecutionBackgroundRunner {
        return object : SpecWorkflowTaskExecutionBackgroundRunner {
            override fun <T> run(request: SpecWorkflowTaskExecutionBackgroundRequest<T>) {
                recorder.lastBackgroundTitle = request.title
                request.onCancelRequested?.invoke()
                request.task()
                request.onCancelled?.invoke()
            }
        }
    }

    private fun failureRunner(recorder: RecordingEnvironment): SpecWorkflowTaskExecutionBackgroundRunner {
        return object : SpecWorkflowTaskExecutionBackgroundRunner {
            override fun <T> run(request: SpecWorkflowTaskExecutionBackgroundRequest<T>) {
                recorder.lastBackgroundTitle = request.title
                try {
                    request.task()
                } catch (error: Throwable) {
                    request.onFailure?.invoke(error) ?: throw error
                }
            }
        }
    }

    private fun executionRequest(
        taskId: String,
        retry: Boolean = false,
    ): SpecWorkflowTaskExecutionRequest {
        return SpecWorkflowTaskExecutionRequest(
            workflowId = "wf-1",
            taskId = taskId,
            providerId = "provider-1",
            modelId = "model-1",
            operationMode = OperationMode.AUTO,
            sessionId = "reusable-session",
            retry = retry,
            auditContext = linkedMapOf("source" to "panel", "taskId" to taskId),
        )
    }

    private fun defaultResult(taskId: String): SpecTaskExecutionService.TaskAiExecutionResult {
        return SpecTaskExecutionService.TaskAiExecutionResult(
            run = executionRun(
                runId = "run-$taskId",
                taskId = taskId,
                status = TaskExecutionRunStatus.SUCCEEDED,
            ),
            sessionId = "chat-$taskId",
            sessionTitle = "Session $taskId",
            requestId = "request-$taskId",
            prompt = "prompt",
            assistantReply = "reply",
        )
    }

    private fun executionRun(
        runId: String,
        taskId: String,
        status: TaskExecutionRunStatus,
    ): TaskExecutionRun {
        return TaskExecutionRun(
            runId = runId,
            taskId = taskId,
            status = status,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-04-02T00:00:00Z",
            finishedAt = if (status.isTerminal()) "2026-04-02T00:01:00Z" else null,
            summary = status.name,
        )
    }

    private inner class RecordingEnvironment {
        var lastBackgroundTitle: String? = null
        var startRequest: SpecWorkflowTaskExecutionRequest? = null
        var retryCall: RetryCall? = null
        var listRunsResult: List<TaskExecutionRun> = emptyList()
        val listRunsCalls = mutableListOf<ListRunsCall>()
        val cancelRunCalls = mutableListOf<CancelRunCall>()
        val cancelTaskCalls = mutableListOf<CancelTaskCall>()
        val openChatCalls = mutableListOf<OpenChatSessionCall>()
        val statusTexts = mutableListOf<String>()
        val refreshEvents = mutableListOf<RefreshEvent>()
        val dialogs = mutableListOf<DialogCall>()
        var reloadCalls: Int = 0

        var onStartExecution: (
            SpecWorkflowTaskExecutionRequest,
            (SpecTaskExecutionService.TaskExecutionCancellationHandle) -> Unit,
        ) -> SpecTaskExecutionService.TaskAiExecutionResult = { request, onRequestRegistered ->
            val handle = SpecTaskExecutionService.TaskExecutionCancellationHandle(
                workflowId = request.workflowId,
                taskId = request.taskId,
                runId = "run-${request.taskId}",
                sessionId = "chat-${request.taskId}",
                providerId = request.providerId,
                requestId = "request-${request.taskId}",
            )
            onRequestRegistered(handle)
            defaultResult(request.taskId)
        }

        var onRetryExecution: (
            SpecWorkflowTaskExecutionRequest,
            String?,
            (SpecTaskExecutionService.TaskExecutionCancellationHandle) -> Unit,
        ) -> SpecTaskExecutionService.TaskAiExecutionResult = { request, _, onRequestRegistered ->
            val handle = SpecTaskExecutionService.TaskExecutionCancellationHandle(
                workflowId = request.workflowId,
                taskId = request.taskId,
                runId = "run-${request.taskId}",
                sessionId = "chat-${request.taskId}",
                providerId = request.providerId,
                requestId = "request-${request.taskId}",
            )
            onRequestRegistered(handle)
            defaultResult(request.taskId)
        }
    }

    private data class RetryCall(
        val request: SpecWorkflowTaskExecutionRequest,
        val previousRunId: String?,
    )

    private data class ListRunsCall(
        val workflowId: String,
        val taskId: String,
    )

    private data class CancelRunCall(
        val workflowId: String,
        val runId: String,
    )

    private data class CancelTaskCall(
        val workflowId: String,
        val taskId: String,
    )

    private data class OpenChatSessionCall(
        val sessionId: String,
        val workflowId: String,
    )

    private data class RefreshEvent(
        val workflowId: String,
        val taskId: String?,
        val reason: String,
    )

    private data class DialogCall(
        val title: String,
        val message: String,
    )
}
