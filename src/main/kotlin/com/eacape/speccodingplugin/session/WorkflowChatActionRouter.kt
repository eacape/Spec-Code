package com.eacape.speccodingplugin.session

import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.spec.MissingStructuredTaskError
import com.eacape.speccodingplugin.spec.SpecStorage
import com.eacape.speccodingplugin.spec.SpecTaskCompletionService
import com.eacape.speccodingplugin.spec.SpecTaskExecutionService
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskVerificationResult
import com.eacape.speccodingplugin.spec.attachActiveExecutionRuns
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class WorkflowChatActionRouter(private val project: Project) {
    private var _sessionManagerOverride: SessionManager? = null
    private var _storageOverride: SpecStorage? = null
    private var _tasksServiceOverride: SpecTasksService? = null
    private var _executionServiceOverride: SpecTaskExecutionService? = null
    private var _completionServiceOverride: SpecTaskCompletionService? = null
    private var _executionContextResolverOverride: WorkflowChatExecutionContextResolver? = null

    private val sessionManager: SessionManager by lazy {
        _sessionManagerOverride ?: SessionManager.getInstance(project)
    }
    private val storage: SpecStorage by lazy { _storageOverride ?: SpecStorage.getInstance(project) }
    private val tasksService: SpecTasksService by lazy {
        _tasksServiceOverride ?: SpecTasksService.getInstance(project)
    }
    private val executionService: SpecTaskExecutionService by lazy {
        _executionServiceOverride ?: SpecTaskExecutionService.getInstance(project)
    }
    private val completionService: SpecTaskCompletionService by lazy {
        _completionServiceOverride ?: SpecTaskCompletionService.getInstance(project)
    }
    private val executionContextResolver: WorkflowChatExecutionContextResolver by lazy {
        _executionContextResolverOverride ?: WorkflowChatExecutionContextResolver.getInstance(project)
    }

    internal constructor(
        project: Project,
        sessionManager: SessionManager,
        storage: SpecStorage,
        tasksService: SpecTasksService,
        executionService: SpecTaskExecutionService,
        completionService: SpecTaskCompletionService,
    ) : this(project) {
        _sessionManagerOverride = sessionManager
        _storageOverride = storage
        _tasksServiceOverride = tasksService
        _executionServiceOverride = executionService
        _completionServiceOverride = completionService
        _executionContextResolverOverride = WorkflowChatExecutionContextResolver(project, storage)
    }

    fun executeBoundTask(
        sessionId: String,
        providerId: String?,
        modelId: String?,
        operationMode: OperationMode,
        supplementalInstruction: String? = null,
        onRequestRegistered: (SpecTaskExecutionService.TaskExecutionCancellationHandle) -> Unit = {},
    ): SpecTaskExecutionService.TaskAiExecutionResult {
        return executeTaskAction(
            sessionId = sessionId,
            explicitTaskId = null,
            providerId = providerId,
            modelId = modelId,
            operationMode = operationMode,
            actionIntent = WorkflowChatActionIntent.EXECUTE_TASK,
            supplementalInstruction = supplementalInstruction,
            previousRunId = null,
            onRequestRegistered = onRequestRegistered,
        )
    }

    fun executeTask(
        sessionId: String,
        taskId: String,
        providerId: String?,
        modelId: String?,
        operationMode: OperationMode,
        supplementalInstruction: String? = null,
        onRequestRegistered: (SpecTaskExecutionService.TaskExecutionCancellationHandle) -> Unit = {},
    ): SpecTaskExecutionService.TaskAiExecutionResult {
        return executeTaskAction(
            sessionId = sessionId,
            explicitTaskId = taskId,
            providerId = providerId,
            modelId = modelId,
            operationMode = operationMode,
            actionIntent = WorkflowChatActionIntent.EXECUTE_TASK,
            supplementalInstruction = supplementalInstruction,
            previousRunId = null,
            onRequestRegistered = onRequestRegistered,
        )
    }

    fun retryBoundTask(
        sessionId: String,
        providerId: String?,
        modelId: String?,
        operationMode: OperationMode,
        supplementalInstruction: String? = null,
        previousRunId: String? = null,
        onRequestRegistered: (SpecTaskExecutionService.TaskExecutionCancellationHandle) -> Unit = {},
    ): SpecTaskExecutionService.TaskAiExecutionResult {
        return executeTaskAction(
            sessionId = sessionId,
            explicitTaskId = null,
            providerId = providerId,
            modelId = modelId,
            operationMode = operationMode,
            actionIntent = WorkflowChatActionIntent.RETRY_TASK,
            supplementalInstruction = supplementalInstruction,
            previousRunId = previousRunId,
            onRequestRegistered = onRequestRegistered,
        )
    }

    fun retryTask(
        sessionId: String,
        taskId: String,
        providerId: String?,
        modelId: String?,
        operationMode: OperationMode,
        supplementalInstruction: String? = null,
        previousRunId: String? = null,
        onRequestRegistered: (SpecTaskExecutionService.TaskExecutionCancellationHandle) -> Unit = {},
    ): SpecTaskExecutionService.TaskAiExecutionResult {
        return executeTaskAction(
            sessionId = sessionId,
            explicitTaskId = taskId,
            providerId = providerId,
            modelId = modelId,
            operationMode = operationMode,
            actionIntent = WorkflowChatActionIntent.RETRY_TASK,
            supplementalInstruction = supplementalInstruction,
            previousRunId = previousRunId,
            onRequestRegistered = onRequestRegistered,
        )
    }

    fun previewCompleteBoundTask(sessionId: String): SpecTaskCompletionService.TaskCompletionPlan {
        val context = resolveTaskContext(sessionId)
        return completionService.previewCompletion(
            workflowId = context.binding.workflowId,
            taskId = context.task.id,
        )
    }

    fun previewCompleteTask(sessionId: String, taskId: String): SpecTaskCompletionService.TaskCompletionPlan {
        val context = resolveTaskContext(sessionId, taskId)
        return completionService.previewCompletion(
            workflowId = context.binding.workflowId,
            taskId = context.task.id,
        )
    }

    fun completeBoundTask(
        sessionId: String,
        planId: String,
        relatedFiles: List<String>,
        verificationResult: TaskVerificationResult?,
    ): StructuredTask {
        return completeTask(
            sessionId = sessionId,
            taskId = null,
            planId = planId,
            relatedFiles = relatedFiles,
            verificationResult = verificationResult,
        )
    }

    fun completeTask(
        sessionId: String,
        taskId: String?,
        planId: String,
        relatedFiles: List<String>,
        verificationResult: TaskVerificationResult?,
    ): StructuredTask {
        val context = resolveTaskContext(sessionId, taskId)
        val completedTask = completionService.completeTask(
            planId = planId,
            relatedFiles = relatedFiles,
            verificationResult = verificationResult,
            auditContext = buildAuditContext(
                context = context,
                action = WorkflowChatActionIntent.COMPLETE_TASK,
            ),
            completionRunSummary = "Completed from workflow chat task action.",
        )
        sessionManager.updateWorkflowChatBinding(
            context.session.id,
            context.binding.copy(actionIntent = WorkflowChatActionIntent.COMPLETE_TASK),
        ).getOrThrow()
        return completedTask
    }

    private fun executeTaskAction(
        sessionId: String,
        explicitTaskId: String?,
        providerId: String?,
        modelId: String?,
        operationMode: OperationMode,
        actionIntent: WorkflowChatActionIntent,
        supplementalInstruction: String?,
        previousRunId: String?,
        onRequestRegistered: (SpecTaskExecutionService.TaskExecutionCancellationHandle) -> Unit,
    ): SpecTaskExecutionService.TaskAiExecutionResult {
        val context = resolveTaskContext(sessionId, explicitTaskId)
        return when (actionIntent) {
            WorkflowChatActionIntent.EXECUTE_TASK -> executionService.startAiExecution(
                workflowId = context.binding.workflowId,
                taskId = context.task.id,
                providerId = providerId,
                modelId = modelId,
                operationMode = operationMode,
                supplementalInstruction = supplementalInstruction,
                sessionId = context.session.id,
                sessionSource = context.binding.source,
                auditContext = buildAuditContext(context, actionIntent),
                onRequestRegistered = onRequestRegistered,
            )

            WorkflowChatActionIntent.RETRY_TASK -> executionService.retryAiExecution(
                workflowId = context.binding.workflowId,
                taskId = context.task.id,
                providerId = providerId,
                modelId = modelId,
                operationMode = operationMode,
                supplementalInstruction = supplementalInstruction,
                previousRunId = previousRunId,
                sessionId = context.session.id,
                sessionSource = context.binding.source,
                auditContext = buildAuditContext(context, actionIntent),
                onRequestRegistered = onRequestRegistered,
            )

            WorkflowChatActionIntent.COMPLETE_TASK,
            WorkflowChatActionIntent.DISCUSS,
            -> error("Unsupported workflow chat task action: $actionIntent")
        }
    }

    private fun resolveTaskContext(
        sessionId: String,
        explicitTaskId: String? = null,
    ): BoundTaskContext {
        val normalizedSessionId = sessionId.trim()
        require(normalizedSessionId.isNotBlank()) { "sessionId cannot be blank" }
        val session = sessionManager.getSession(normalizedSessionId)
            ?: throw IllegalArgumentException("Session not found: $normalizedSessionId")
        val binding = session.resolvedWorkflowChatBinding()
            ?: throw IllegalStateException("Workflow chat binding is not available for session $normalizedSessionId")
        val taskId = explicitTaskId?.trim()?.ifBlank { null }
            ?: executionContextResolver.resolve(binding)?.taskId
            ?: throw IllegalStateException(
                "Workflow chat session $normalizedSessionId has no active task execution context",
            )
        val workflow = storage.loadWorkflow(binding.workflowId).getOrThrow()
        val task = tasksService.parse(binding.workflowId)
            .attachActiveExecutionRuns(workflow.taskExecutionRuns)
            .firstOrNull { candidate -> candidate.id == taskId }
            ?: throw MissingStructuredTaskError(taskId)
        return BoundTaskContext(
            session = session,
            binding = binding,
            task = task,
        )
    }

    private fun buildAuditContext(
        context: BoundTaskContext,
        action: WorkflowChatActionIntent,
    ): Map<String, String> {
        return linkedMapOf<String, String>().apply {
            put("triggerSource", "WORKFLOW_CHAT")
            put("workflowChatSessionId", context.session.id)
            put("workflowChatSource", context.binding.source.name)
            put("workflowChatActionIntent", action.name)
            put("workflowChatFocusedStage", context.binding.focusedStage?.name.orEmpty())
            put("taskAction", action.name)
            put("taskLifecycleStatus", context.task.status.name)
            put("taskDisplayStatus", context.task.displayStatus.name)
            put("taskExecutionRunId", context.task.activeExecutionRun?.runId.orEmpty())
            put("taskExecutionRunStatus", context.task.activeExecutionRun?.status?.name.orEmpty())
        }.filterValues { value -> value.isNotBlank() }
    }

    private data class BoundTaskContext(
        val session: ConversationSession,
        val binding: WorkflowChatBinding,
        val task: StructuredTask,
    )

    companion object {
        fun getInstance(project: Project): WorkflowChatActionRouter = project.service()
    }
}
