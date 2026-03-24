package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.context.ContextSnapshot
import com.eacape.speccodingplugin.context.ContextType
import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.LlmChunk
import com.eacape.speccodingplugin.llm.LlmResponse
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.session.ConversationMessage
import com.eacape.speccodingplugin.session.ConversationRole
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.session.WorkflowChatActionIntent
import com.eacape.speccodingplugin.session.WorkflowChatBinding
import com.eacape.speccodingplugin.session.WorkflowChatEntrySource
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID

@Service(Service.Level.PROJECT)
class SpecTaskExecutionService(private val project: Project) {
    data class TaskExecutionChatRequest(
        val providerId: String?,
        val userInput: String,
        val modelId: String?,
        val contextSnapshot: ContextSnapshot?,
        val operationMode: OperationMode,
        val requestId: String,
        val onChunk: suspend (LlmChunk) -> Unit,
    )

    data class TaskExecutionCancellationHandle(
        val workflowId: String,
        val taskId: String,
        val runId: String,
        val sessionId: String,
        val providerId: String?,
        val requestId: String,
    )

    data class TaskAiExecutionResult(
        val run: TaskExecutionRun,
        val sessionId: String,
        val sessionTitle: String,
        val requestId: String,
        val prompt: String,
        val assistantReply: String,
        val previousRunId: String? = null,
    )

    private data class ExecutionLaunchArtifacts(
        val prompt: String,
        val timelineMessage: String,
        val presentation: WorkflowChatExecutionLaunchPresentation,
        val debugPayload: WorkflowChatExecutionLaunchDebugPayload,
    )

    data class LegacyTaskExecutionMigrationResult(
        val workflow: SpecWorkflow,
        val migratedRuns: List<TaskExecutionRun>,
    ) {
        val migrated: Boolean
            get() = migratedRuns.isNotEmpty()
    }

    private var _storageOverride: SpecStorage? = null
    private var _tasksServiceOverride: SpecTasksService? = null
    private var _relatedFilesResolverOverride: ((String, List<String>) -> List<String>)? = null
    private var _projectServiceOverride: SpecCodingProjectService? = null
    private var _sessionManagerOverride: SessionManager? = null
    private var _chatExecutorOverride: (suspend (TaskExecutionChatRequest) -> LlmResponse)? = null
    private var _requestCancellerOverride: ((String?, String) -> Unit)? = null

    private val storage: SpecStorage by lazy { _storageOverride ?: SpecStorage.getInstance(project) }
    private val tasksService: SpecTasksService by lazy { _tasksServiceOverride ?: SpecTasksService(project) }
    private val relatedFilesResolver: (String, List<String>) -> List<String> by lazy {
        _relatedFilesResolverOverride ?: { taskId, existingRelatedFiles ->
            SpecRelatedFilesService.getInstance(project).suggestRelatedFiles(taskId, existingRelatedFiles)
        }
    }
    private val projectService: SpecCodingProjectService by lazy { _projectServiceOverride ?: SpecCodingProjectService(project) }
    private val sessionManager: SessionManager by lazy { _sessionManagerOverride ?: SessionManager(project) }
    private val chatExecutor: suspend (TaskExecutionChatRequest) -> LlmResponse by lazy {
        _chatExecutorOverride ?: { request ->
            projectService.chat(
                providerId = request.providerId,
                userInput = request.userInput,
                modelId = request.modelId,
                contextSnapshot = request.contextSnapshot,
                operationMode = request.operationMode,
                requestId = request.requestId,
                onChunk = request.onChunk,
            )
        }
    }
    private val requestCanceller: (String?, String) -> Unit by lazy {
        _requestCancellerOverride ?: ::cancelRequestAcrossProviders
    }
    private val activeRequestHandles = ConcurrentHashMap<String, TaskExecutionCancellationHandle>()
    private val liveProgressByRunId = ConcurrentHashMap<String, TaskExecutionLiveProgress>()
    private val liveProgressListeners = CopyOnWriteArrayList<TaskExecutionLiveProgressListener>()

    internal constructor(
        project: Project,
        storage: SpecStorage,
        tasksService: SpecTasksService,
        relatedFilesResolver: (String, List<String>) -> List<String>,
        projectService: SpecCodingProjectService,
        sessionManager: SessionManager,
        chatExecutor: suspend (TaskExecutionChatRequest) -> LlmResponse,
        requestCanceller: (String?, String) -> Unit = { _, _ -> },
    ) : this(project) {
        _storageOverride = storage
        _tasksServiceOverride = tasksService
        _relatedFilesResolverOverride = relatedFilesResolver
        _projectServiceOverride = projectService
        _sessionManagerOverride = sessionManager
        _chatExecutorOverride = chatExecutor
        _requestCancellerOverride = requestCanceller
    }

    fun listRuns(workflowId: String, taskId: String? = null): List<TaskExecutionRun> {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val normalizedTaskId = taskId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::normalizeTaskId)
        return workflow.taskExecutionRuns
            .asSequence()
            .filter { run -> normalizedTaskId == null || run.taskId == normalizedTaskId }
            .sortedWith(compareByDescending<TaskExecutionRun> { it.startedAt }.thenByDescending { it.runId })
            .toList()
    }

    fun listLiveProgress(workflowId: String, taskId: String? = null): List<TaskExecutionLiveProgress> {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val normalizedTaskId = taskId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::normalizeTaskId)
        val persistedRuns = workflow.taskExecutionRuns
            .asSequence()
            .filter { run -> normalizedTaskId == null || run.taskId == normalizedTaskId }
            .toList()

        val progressByRunId = linkedMapOf<String, TaskExecutionLiveProgress>()
        persistedRuns.forEach { run ->
            val liveProgress = liveProgressByRunId[run.runId] ?: synthesizeLiveProgress(workflowId, run)
            if (liveProgress.phase != ExecutionLivePhase.TERMINAL) {
                progressByRunId[run.runId] = liveProgress
            }
        }
        liveProgressByRunId.values
            .asSequence()
            .filter { progress ->
                progress.workflowId == workflowId &&
                    progress.phase != ExecutionLivePhase.TERMINAL &&
                    (normalizedTaskId == null || progress.taskId == normalizedTaskId)
            }
            .forEach { progress ->
                progressByRunId.putIfAbsent(progress.runId, progress)
            }

        return progressByRunId.values
            .sortedWith(compareByDescending<TaskExecutionLiveProgress> { it.lastUpdatedAt }.thenByDescending { it.runId })
    }

    fun getLiveProgress(workflowId: String, runId: String): TaskExecutionLiveProgress? {
        val normalizedRunId = runId.trim().takeIf(String::isNotBlank) ?: return null
        val existing = liveProgressByRunId[normalizedRunId]
        if (existing != null && existing.workflowId == workflowId) {
            return existing
        }
        return loadRun(workflowId, normalizedRunId)?.let { run ->
            liveProgressByRunId[normalizedRunId] ?: synthesizeLiveProgress(workflowId, run)
        }
    }

    fun getTaskLiveProgress(workflowId: String, taskId: String): TaskExecutionLiveProgress? {
        val normalizedTaskId = normalizeTaskId(taskId)
        return listLiveProgress(workflowId, normalizedTaskId).firstOrNull()
    }

    fun addLiveProgressListener(listener: TaskExecutionLiveProgressListener) {
        liveProgressListeners += listener
    }

    fun removeLiveProgressListener(listener: TaskExecutionLiveProgressListener) {
        liveProgressListeners -= listener
    }

    fun startAiExecution(
        workflowId: String,
        taskId: String,
        providerId: String?,
        modelId: String?,
        operationMode: OperationMode,
        supplementalInstruction: String? = null,
        sessionId: String? = null,
        sessionSource: WorkflowChatEntrySource = WorkflowChatEntrySource.TASK_PANEL,
        auditContext: Map<String, String> = emptyMap(),
        onRequestRegistered: (TaskExecutionCancellationHandle) -> Unit = {},
    ): TaskAiExecutionResult {
        return executeWithAi(
            workflowId = workflowId,
            taskId = taskId,
            providerId = providerId,
            modelId = modelId,
            operationMode = operationMode,
            supplementalInstruction = supplementalInstruction,
            trigger = ExecutionTrigger.USER_EXECUTE,
            previousRunId = null,
            sessionId = sessionId,
            sessionSource = sessionSource,
            auditContext = auditContext,
            onRequestRegistered = onRequestRegistered,
        )
    }

    fun retryAiExecution(
        workflowId: String,
        taskId: String,
        providerId: String?,
        modelId: String?,
        operationMode: OperationMode,
        supplementalInstruction: String? = null,
        previousRunId: String? = null,
        sessionId: String? = null,
        sessionSource: WorkflowChatEntrySource = WorkflowChatEntrySource.TASK_PANEL,
        auditContext: Map<String, String> = emptyMap(),
        onRequestRegistered: (TaskExecutionCancellationHandle) -> Unit = {},
    ): TaskAiExecutionResult {
        return executeWithAi(
            workflowId = workflowId,
            taskId = taskId,
            providerId = providerId,
            modelId = modelId,
            operationMode = operationMode,
            supplementalInstruction = supplementalInstruction,
            trigger = ExecutionTrigger.USER_RETRY,
            previousRunId = previousRunId,
            sessionId = sessionId,
            sessionSource = sessionSource,
            auditContext = auditContext,
            onRequestRegistered = onRequestRegistered,
        )
    }

    fun createRun(
        workflowId: String,
        taskId: String,
        status: TaskExecutionRunStatus,
        trigger: ExecutionTrigger,
        startedAt: String = Instant.now().toString(),
        finishedAt: String? = null,
        summary: String? = null,
    ): TaskExecutionRun {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        return createRun(
            workflow = workflow,
            taskId = taskId,
            status = status,
            trigger = trigger,
            startedAt = startedAt,
            finishedAt = finishedAt,
            summary = summary,
        ).second
    }

    fun updateRunStatus(
        workflowId: String,
        runId: String,
        status: TaskExecutionRunStatus,
        finishedAt: String? = null,
        summary: String? = null,
    ): TaskExecutionRun {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val normalizedRunId = runId.trim().takeIf(String::isNotBlank)
            ?: throw MissingTaskExecutionRunError(runId)
        val existingRun = workflow.taskExecutionRuns.firstOrNull { run -> run.runId == normalizedRunId }
            ?: throw MissingTaskExecutionRunError(normalizedRunId)
        if (!existingRun.status.canTransitionTo(status)) {
            throw InvalidTaskExecutionRunTransitionError(existingRun.runId, existingRun.status, status)
        }
        val resolvedFinishedAt = when {
            finishedAt?.isNotBlank() == true -> finishedAt.trim()
            status.isTerminal() -> Instant.now().toString()
            else -> null
        }
        val normalizedSummary = summary?.trim()?.takeIf(String::isNotBlank) ?: existingRun.summary
        val updatedRun = existingRun.copy(
            status = status,
            finishedAt = resolvedFinishedAt,
            summary = normalizedSummary,
        )
        if (updatedRun.status.isTerminal()) {
            activeRequestHandles.remove(updatedRun.runId)
        }
        val updatedWorkflow = workflow.copy(
            taskExecutionRuns = replaceRun(workflow.taskExecutionRuns, updatedRun),
            updatedAt = System.currentTimeMillis(),
        )
        storage.saveWorkflowTransition(
            workflow = updatedWorkflow,
            eventType = SpecAuditEventType.TASK_EXECUTION_RUN_STATUS_CHANGED,
            details = linkedMapOf(
                "runId" to updatedRun.runId,
                "taskId" to updatedRun.taskId,
                "fromStatus" to existingRun.status.name,
                "toStatus" to updatedRun.status.name,
                "trigger" to updatedRun.trigger.name,
                "startedAt" to updatedRun.startedAt,
                "finishedAt" to (updatedRun.finishedAt ?: ""),
                "summary" to (updatedRun.summary ?: ""),
            ),
        ).getOrThrow()
        return updatedRun
    }

    fun cancelExecution(
        workflowId: String,
        taskId: String,
        summary: String = USER_CANCELLED_RUN_SUMMARY,
    ): TaskExecutionRun? {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val normalizedTaskId = normalizeTaskId(taskId)
        val activeRun = workflow.taskExecutionRuns
            .asSequence()
            .filter { run -> run.taskId == normalizedTaskId && !run.status.isTerminal() }
            .maxWithOrNull(compareBy<TaskExecutionRun> { it.startedAt }.thenBy { it.runId })
            ?: return null
        return cancelExecutionRun(
            workflowId = workflowId,
            runId = activeRun.runId,
            summary = summary,
        )
    }

    fun cancelExecutionRun(
        workflowId: String,
        runId: String,
        summary: String = USER_CANCELLED_RUN_SUMMARY,
    ): TaskExecutionRun? {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val normalizedRunId = runId.trim().takeIf(String::isNotBlank) ?: return null
        val currentRun = workflow.taskExecutionRuns.firstOrNull { run -> run.runId == normalizedRunId } ?: return null
        if (currentRun.status == TaskExecutionRunStatus.CANCELLED) {
            return currentRun
        }
        if (currentRun.status.isTerminal() || !currentRun.status.canTransitionTo(TaskExecutionRunStatus.CANCELLED)) {
            return currentRun
        }
        val cancellationHandle = activeRequestHandles[currentRun.runId]
        updateLiveProgress(
            workflowId = workflowId,
            runId = currentRun.runId,
            taskId = currentRun.taskId,
            phase = ExecutionLivePhase.CANCELLING,
            startedAt = parseRunInstant(currentRun.startedAt),
            detail = LIVE_PROGRESS_CANCELLING_DETAIL,
            event = milestoneEvent(
                detail = LIVE_PROGRESS_CANCELLING_DETAIL,
                status = ChatTraceStatus.RUNNING,
                id = TASK_EXECUTION_LIFECYCLE_EVENT_ID,
            ),
        )
        val cancelledRun = updateRunStatus(
            workflowId = workflowId,
            runId = currentRun.runId,
            status = TaskExecutionRunStatus.CANCELLED,
            summary = summary,
        )
        activeRequestHandles.remove(currentRun.runId)
        cancellationHandle?.let { handle -> requestCanceller(handle.providerId, handle.requestId) }
        if (cancellationHandle == null) {
            finishLiveProgress(
                workflowId = workflowId,
                run = cancelledRun,
                detail = cancelledRun.summary ?: summary,
            )
        }
        return cancelledRun
    }

    fun resolveWaitingConfirmationRun(
        workflowId: String,
        taskId: String,
        summary: String? = null,
    ): TaskExecutionRun? {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val normalizedTaskId = normalizeTaskId(taskId)
        val run = workflow.taskExecutionRuns
            .asSequence()
            .filter { candidate ->
                candidate.taskId == normalizedTaskId &&
                    candidate.status == TaskExecutionRunStatus.WAITING_CONFIRMATION
            }
            .sortedBy(TaskExecutionRun::startedAt)
            .lastOrNull()
            ?: return null
        return updateRunStatus(
            workflowId = workflowId,
            runId = run.runId,
            status = TaskExecutionRunStatus.SUCCEEDED,
            summary = summary,
        ).also { resolvedRun ->
            finishLiveProgress(
                workflowId = workflowId,
                run = resolvedRun,
                detail = resolvedRun.summary ?: LIVE_PROGRESS_COMPLETED_DETAIL,
            )
        }
    }

    fun migrateLegacyInProgressTasks(workflow: SpecWorkflow): LegacyTaskExecutionMigrationResult {
        val tasks = tasksService.parse(workflow.id)
        if (tasks.isEmpty()) {
            return LegacyTaskExecutionMigrationResult(workflow = workflow, migratedRuns = emptyList())
        }

        var currentWorkflow = workflow
        val migratedRuns = mutableListOf<TaskExecutionRun>()
        tasks.filter { task -> task.status == TaskStatus.IN_PROGRESS }
            .sortedBy(StructuredTask::id)
            .forEach { task ->
                val existingActiveRun = currentWorkflow.taskExecutionRuns.any { run ->
                    run.taskId == task.id && !run.status.isTerminal()
                }
                if (!existingActiveRun) {
                    val (updatedWorkflow, run) = createRun(
                        workflow = currentWorkflow,
                        taskId = task.id,
                        status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
                        trigger = ExecutionTrigger.SYSTEM_RECOVERY,
                        summary = "Recovered from legacy task status IN_PROGRESS.",
                    )
                    currentWorkflow = updatedWorkflow
                    migratedRuns += run
                }
                normalizeLegacyInProgressTask(
                    workflowId = workflow.id,
                    taskId = task.id,
                )
            }
        return LegacyTaskExecutionMigrationResult(
            workflow = currentWorkflow,
            migratedRuns = migratedRuns,
        )
    }

    private fun executeWithAi(
        workflowId: String,
        taskId: String,
        providerId: String?,
        modelId: String?,
        operationMode: OperationMode,
        supplementalInstruction: String?,
        trigger: ExecutionTrigger,
        previousRunId: String?,
        sessionId: String?,
        sessionSource: WorkflowChatEntrySource,
        auditContext: Map<String, String>,
        onRequestRegistered: (TaskExecutionCancellationHandle) -> Unit,
    ): TaskAiExecutionResult {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val task = resolveTask(workflowId, taskId)
        val executionConstraint = SpecTaskDependencyRules.executionConstraint(
            task = task,
            tasks = tasksService.parse(workflowId),
        )
        if (!executionConstraint.executable) {
            throw TaskExecutionBlockedByDependenciesError(
                task.id,
                executionConstraint.unmetDependencyIds,
            )
        }
        val previousRun = resolvePreviousRun(workflow, task.id, previousRunId, trigger)
        val normalizedProviderId = normalizeProgressMetadataValue(providerId)
        val normalizedModelId = normalizeProgressMetadataValue(modelId)
        val queuedRun = createRun(
            workflow = workflow,
            taskId = task.id,
            status = TaskExecutionRunStatus.QUEUED,
            trigger = trigger,
            summary = buildQueuedSummary(trigger, previousRun),
        ).second
        updateLiveProgress(
            workflowId = workflowId,
            runId = queuedRun.runId,
            taskId = task.id,
            phase = ExecutionLivePhase.QUEUED,
            startedAt = parseRunInstant(queuedRun.startedAt),
            detail = queuedRun.summary ?: LIVE_PROGRESS_QUEUED_DETAIL,
            providerId = normalizedProviderId,
            modelId = normalizedModelId,
            event = milestoneEvent(
                detail = queuedRun.summary ?: LIVE_PROGRESS_QUEUED_DETAIL,
                status = ChatTraceStatus.INFO,
                id = TASK_EXECUTION_LIFECYCLE_EVENT_ID,
            ),
        )
        updateLiveProgress(
            workflowId = workflowId,
            runId = queuedRun.runId,
            taskId = task.id,
            phase = ExecutionLivePhase.PREPARING_CONTEXT,
            startedAt = parseRunInstant(queuedRun.startedAt),
            detail = LIVE_PROGRESS_PREPARING_DETAIL,
            providerId = normalizedProviderId,
            modelId = normalizedModelId,
            event = milestoneEvent(
                detail = LIVE_PROGRESS_PREPARING_DETAIL,
                status = ChatTraceStatus.RUNNING,
                id = TASK_EXECUTION_LIFECYCLE_EVENT_ID,
            ),
        )
        val suggestedRelatedFiles = relatedFilesResolver(task.id, task.relatedFiles)
        val session = resolveExecutionSession(
            workflow = workflow,
            task = task,
            providerId = providerId,
            trigger = trigger,
            sessionId = sessionId,
            sessionSource = sessionSource,
        )
        val requestId = UUID.randomUUID().toString()
        val cancellationHandle = TaskExecutionCancellationHandle(
            workflowId = workflowId,
            taskId = task.id,
            runId = queuedRun.runId,
            sessionId = session.id,
            providerId = providerId,
            requestId = requestId,
        )
        activeRequestHandles[queuedRun.runId] = cancellationHandle
        onRequestRegistered(cancellationHandle)
        val executionLaunch = buildExecutionLaunchArtifacts(
            workflow = workflow,
            task = task,
            run = queuedRun,
            trigger = trigger,
            previousRun = previousRun,
            suggestedRelatedFiles = suggestedRelatedFiles,
            supplementalInstruction = supplementalInstruction,
            sessionSource = sessionSource,
        )
        val prompt = executionLaunch.prompt
        val contextSnapshot = buildRelatedFilesContextSnapshot(suggestedRelatedFiles)
        var sawStreamingSignal = false
        val streamedTraceEvents = mutableListOf<ChatStreamEvent>()
        val executionStartedAtMillis = parseRunInstant(queuedRun.startedAt).toEpochMilli()
        val onChunk: suspend (LlmChunk) -> Unit = { chunk ->
            if (chunk.event != null) {
                normalizeLiveEvent(chunk.event)?.let(streamedTraceEvents::add)
                updateLiveProgress(
                    workflowId = workflowId,
                    runId = queuedRun.runId,
                    taskId = task.id,
                    phase = ExecutionLivePhase.STREAMING,
                    startedAt = parseRunInstant(queuedRun.startedAt),
                    providerId = normalizedProviderId,
                    modelId = normalizedModelId,
                    event = chunk.event,
                    detail = chunk.event.detail,
                )
                sawStreamingSignal = true
            } else if (chunk.delta.isNotBlank()) {
                val fallbackEvent = if (sawStreamingSignal) {
                    null
                } else {
                    milestoneEvent(
                        kind = ChatTraceKind.OUTPUT,
                        detail = LIVE_PROGRESS_STREAMING_DETAIL,
                        status = ChatTraceStatus.RUNNING,
                    )
                }
                fallbackEvent?.let(streamedTraceEvents::add)
                updateLiveProgress(
                    workflowId = workflowId,
                    runId = queuedRun.runId,
                    taskId = task.id,
                    phase = ExecutionLivePhase.STREAMING,
                    startedAt = parseRunInstant(queuedRun.startedAt),
                    providerId = normalizedProviderId,
                    modelId = normalizedModelId,
                    event = fallbackEvent,
                    detail = LIVE_PROGRESS_STREAMING_DETAIL,
                )
                sawStreamingSignal = true
            }
        }
        val executionAuditContext = buildExecutionAuditContext(
            auditContext = auditContext,
            task = task,
            run = queuedRun,
            trigger = trigger,
            sessionId = session.id,
            providerId = providerId,
            modelId = modelId,
            requestId = requestId,
            previousRunId = previousRun?.runId,
        )

        val persistedLaunchMessage = persistExecutionMessage(
            sessionId = session.id,
            role = ConversationRole.USER,
            content = executionLaunch.timelineMessage,
            run = queuedRun,
            workflowId = workflowId,
            requestId = requestId,
            providerId = providerId,
            modelId = modelId,
            previousRunId = previousRun?.runId,
            launchPresentation = executionLaunch.presentation,
            launchDebugPayload = executionLaunch.debugPayload,
        )
        recordExecutionLaunchAudit(
            workflowId = workflowId,
            task = task,
            run = queuedRun,
            sessionId = session.id,
            messageId = persistedLaunchMessage.id,
            requestId = requestId,
            providerId = providerId,
            modelId = modelId,
            previousRunId = previousRun?.runId,
            rawPrompt = prompt,
            visibleSummary = executionLaunch.timelineMessage,
            presentation = executionLaunch.presentation,
        )

        normalizeTaskLifecycleForExecution(workflowId, task, executionAuditContext)

        return try {
            ensureRunNotCancelled(workflowId, queuedRun.runId)
            updateRunStatus(
                workflowId = workflowId,
                runId = queuedRun.runId,
                status = TaskExecutionRunStatus.RUNNING,
            )
            updateLiveProgress(
                workflowId = workflowId,
                runId = queuedRun.runId,
                taskId = task.id,
                phase = ExecutionLivePhase.REQUEST_DISPATCHED,
                startedAt = parseRunInstant(queuedRun.startedAt),
                detail = LIVE_PROGRESS_REQUEST_DISPATCHED_DETAIL,
                providerId = normalizedProviderId,
                modelId = normalizedModelId,
                event = milestoneEvent(
                    detail = LIVE_PROGRESS_REQUEST_DISPATCHED_DETAIL,
                    status = ChatTraceStatus.INFO,
                    id = TASK_EXECUTION_LIFECYCLE_EVENT_ID,
                ),
            )
            ensureRunNotCancelled(workflowId, queuedRun.runId)
            val response = runBlocking {
                chatExecutor(
                    TaskExecutionChatRequest(
                        providerId = providerId,
                        userInput = prompt,
                        modelId = modelId,
                        contextSnapshot = contextSnapshot,
                        operationMode = operationMode,
                        requestId = requestId,
                        onChunk = onChunk,
                    ),
                )
            }
            ensureRunNotCancelled(workflowId, queuedRun.runId)
            val assistantReply = response.content.trim()
            val completedRun = updateRunStatus(
                workflowId = workflowId,
                runId = queuedRun.runId,
                status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
                summary = summarizeAssistantReply(assistantReply),
            )
            updateLiveProgress(
                workflowId = workflowId,
                runId = queuedRun.runId,
                taskId = task.id,
                phase = ExecutionLivePhase.WAITING_CONFIRMATION,
                startedAt = parseRunInstant(queuedRun.startedAt),
                detail = completedRun.summary ?: LIVE_PROGRESS_WAITING_CONFIRMATION_DETAIL,
                providerId = normalizedProviderId,
                modelId = normalizedModelId,
                event = milestoneEvent(
                    detail = LIVE_PROGRESS_WAITING_CONFIRMATION_DETAIL,
                    status = ChatTraceStatus.INFO,
                    id = TASK_EXECUTION_LIFECYCLE_EVENT_ID,
                ),
            )
            persistExecutionMessage(
                sessionId = session.id,
                role = ConversationRole.ASSISTANT,
                content = assistantReply.ifBlank { summarizeAssistantReply(response.content) },
                run = completedRun,
                workflowId = workflowId,
                requestId = requestId,
                providerId = providerId,
                modelId = modelId,
                previousRunId = previousRun?.runId,
                traceEvents = streamedTraceEvents,
                startedAtMillis = executionStartedAtMillis,
                finishedAtMillis = System.currentTimeMillis(),
            )
            TaskAiExecutionResult(
                run = completedRun,
                sessionId = session.id,
                sessionTitle = session.title,
                requestId = requestId,
                prompt = prompt,
                assistantReply = assistantReply,
                previousRunId = previousRun?.runId,
            )
        } catch (cancel: CancellationException) {
            val cancelSummary = summarizeCancellation(cancel)
            persistExecutionMessage(
                sessionId = session.id,
                role = ConversationRole.SYSTEM,
                content = cancelSummary,
                run = loadRun(workflowId, queuedRun.runId) ?: queuedRun,
                workflowId = workflowId,
                requestId = requestId,
                providerId = providerId,
                modelId = modelId,
                previousRunId = previousRun?.runId,
            )
            markRunCancelled(workflowId, queuedRun.runId, cancelSummary)
            finishLiveProgress(
                workflowId = workflowId,
                run = loadRun(workflowId, queuedRun.runId) ?: queuedRun.copy(
                    status = TaskExecutionRunStatus.CANCELLED,
                    summary = cancelSummary,
                    finishedAt = Instant.now().toString(),
                ),
                detail = cancelSummary,
                providerId = normalizedProviderId,
                modelId = normalizedModelId,
            )
            throw cancel
        } catch (error: Throwable) {
            val cancelledRun = loadRun(workflowId, queuedRun.runId)
                ?.takeIf { run -> run.status == TaskExecutionRunStatus.CANCELLED }
            if (cancelledRun != null) {
                val cancelSummary = cancelledRun.summary ?: USER_CANCELLED_RUN_SUMMARY
                persistExecutionMessage(
                    sessionId = session.id,
                    role = ConversationRole.SYSTEM,
                    content = cancelSummary,
                    run = cancelledRun,
                    workflowId = workflowId,
                    requestId = requestId,
                    providerId = providerId,
                    modelId = modelId,
                    previousRunId = previousRun?.runId,
                )
                finishLiveProgress(
                    workflowId = workflowId,
                    run = cancelledRun,
                    detail = cancelSummary,
                    providerId = normalizedProviderId,
                    modelId = normalizedModelId,
                )
                throw CancellationException(cancelSummary)
            }
            val failureSummary = summarizeFailure(error)
            persistExecutionMessage(
                sessionId = session.id,
                role = ConversationRole.SYSTEM,
                content = failureSummary,
                run = queuedRun,
                workflowId = workflowId,
                requestId = requestId,
                providerId = providerId,
                modelId = modelId,
                previousRunId = previousRun?.runId,
            )
            markRunFailed(workflowId, queuedRun.runId, failureSummary)
            finishLiveProgress(
                workflowId = workflowId,
                run = loadRun(workflowId, queuedRun.runId) ?: queuedRun.copy(
                    status = TaskExecutionRunStatus.FAILED,
                    summary = failureSummary,
                    finishedAt = Instant.now().toString(),
                ),
                detail = failureSummary,
                providerId = normalizedProviderId,
                modelId = normalizedModelId,
            )
            transitionTaskToBlocked(
                workflowId = workflowId,
                taskId = task.id,
                reason = failureSummary,
                auditContext = executionAuditContext,
            )
            throw error
        } finally {
            activeRequestHandles.remove(queuedRun.runId)
        }
    }

    private fun createRun(
        workflow: SpecWorkflow,
        taskId: String,
        status: TaskExecutionRunStatus,
        trigger: ExecutionTrigger,
        startedAt: String = Instant.now().toString(),
        finishedAt: String? = null,
        summary: String? = null,
    ): Pair<SpecWorkflow, TaskExecutionRun> {
        val normalizedTaskId = normalizeTaskId(taskId)
        ensureTaskExists(workflow.id, normalizedTaskId)
        if (workflow.taskExecutionRuns.any { run ->
                run.taskId == normalizedTaskId && !run.status.isTerminal()
            }
        ) {
            throw ActiveTaskExecutionRunExistsError(normalizedTaskId)
        }

        val normalizedStartedAt = startedAt.trim().takeIf(String::isNotBlank) ?: Instant.now().toString()
        val normalizedFinishedAt = when {
            finishedAt?.isNotBlank() == true -> finishedAt.trim()
            status.isTerminal() -> normalizedStartedAt
            else -> null
        }
        val normalizedSummary = summary?.trim()?.takeIf(String::isNotBlank)
        val run = TaskExecutionRun(
            runId = buildRunId(normalizedTaskId),
            taskId = normalizedTaskId,
            status = status,
            trigger = trigger,
            startedAt = normalizedStartedAt,
            finishedAt = normalizedFinishedAt,
            summary = normalizedSummary,
        )
        val updatedWorkflow = workflow.copy(
            taskExecutionRuns = appendRun(workflow.taskExecutionRuns, run),
            updatedAt = System.currentTimeMillis(),
        )
        storage.saveWorkflowTransition(
            workflow = updatedWorkflow,
            eventType = SpecAuditEventType.TASK_EXECUTION_RUN_CREATED,
            details = linkedMapOf(
                "runId" to run.runId,
                "taskId" to run.taskId,
                "status" to run.status.name,
                "trigger" to run.trigger.name,
                "startedAt" to run.startedAt,
                "finishedAt" to (run.finishedAt ?: ""),
                "summary" to (run.summary ?: ""),
                "migratedFromStatus" to if (trigger == ExecutionTrigger.SYSTEM_RECOVERY) TaskStatus.IN_PROGRESS.name else "",
            ),
        ).getOrThrow()
        return updatedWorkflow to run
    }

    private fun synthesizeLiveProgress(
        workflowId: String,
        run: TaskExecutionRun,
    ): TaskExecutionLiveProgress {
        val phase = when (run.status) {
            TaskExecutionRunStatus.QUEUED -> ExecutionLivePhase.QUEUED
            TaskExecutionRunStatus.RUNNING -> ExecutionLivePhase.REQUEST_DISPATCHED
            TaskExecutionRunStatus.WAITING_CONFIRMATION -> ExecutionLivePhase.WAITING_CONFIRMATION
            TaskExecutionRunStatus.FAILED,
            TaskExecutionRunStatus.CANCELLED,
            TaskExecutionRunStatus.SUCCEEDED,
            -> ExecutionLivePhase.TERMINAL
        }
        val detail = when (run.status) {
            TaskExecutionRunStatus.QUEUED -> run.summary ?: LIVE_PROGRESS_QUEUED_DETAIL
            TaskExecutionRunStatus.RUNNING -> run.summary ?: LIVE_PROGRESS_REQUEST_DISPATCHED_DETAIL
            TaskExecutionRunStatus.WAITING_CONFIRMATION -> run.summary ?: LIVE_PROGRESS_WAITING_CONFIRMATION_DETAIL
            TaskExecutionRunStatus.CANCELLED -> run.summary ?: USER_CANCELLED_RUN_SUMMARY
            TaskExecutionRunStatus.FAILED -> run.summary ?: LIVE_PROGRESS_FAILED_DETAIL
            TaskExecutionRunStatus.SUCCEEDED -> run.summary ?: LIVE_PROGRESS_COMPLETED_DETAIL
        }
        return TaskExecutionLiveProgress(
            workflowId = workflowId,
            runId = run.runId,
            taskId = run.taskId,
            phase = phase,
            startedAt = parseRunInstant(run.startedAt),
            lastUpdatedAt = parseRunInstant(run.finishedAt ?: run.startedAt),
            lastDetail = detail,
            recentEvents = listOfNotNull(
                milestoneEvent(
                    detail = detail,
                    status = when (run.status) {
                        TaskExecutionRunStatus.FAILED -> ChatTraceStatus.ERROR
                        TaskExecutionRunStatus.CANCELLED -> ChatTraceStatus.DONE
                        TaskExecutionRunStatus.SUCCEEDED -> ChatTraceStatus.DONE
                        else -> ChatTraceStatus.INFO
                    },
                    id = TASK_EXECUTION_LIFECYCLE_EVENT_ID,
                ),
            ),
        )
    }

    private fun updateLiveProgress(
        workflowId: String,
        runId: String,
        taskId: String,
        phase: ExecutionLivePhase,
        startedAt: Instant,
        detail: String? = null,
        providerId: String? = null,
        modelId: String? = null,
        event: ChatStreamEvent? = null,
    ) {
        val existing = liveProgressByRunId[runId]
        val normalizedEvent = normalizeLiveEvent(event)
        val normalizedDetail = detail?.trim()?.takeIf(String::isNotBlank)?.take(LIVE_PROGRESS_DETAIL_CHAR_LIMIT)
        val updated = TaskExecutionLiveProgress(
            workflowId = workflowId,
            runId = runId,
            taskId = taskId,
            phase = phase,
            startedAt = existing?.startedAt ?: startedAt,
            lastUpdatedAt = Instant.now(),
            lastDetail = normalizedDetail ?: normalizedEvent?.detail ?: existing?.lastDetail,
            recentEvents = appendRecentEvent(existing?.recentEvents.orEmpty(), normalizedEvent),
            providerId = normalizeProgressMetadataValue(providerId) ?: existing?.providerId,
            modelId = normalizeProgressMetadataValue(modelId) ?: existing?.modelId,
        )
        liveProgressByRunId[runId] = updated
        notifyLiveProgressUpdated(updated)
    }

    private fun finishLiveProgress(
        workflowId: String,
        run: TaskExecutionRun,
        detail: String? = null,
        providerId: String? = null,
        modelId: String? = null,
    ) {
        val resolvedDetail = detail?.trim()?.takeIf(String::isNotBlank) ?: when (run.status) {
            TaskExecutionRunStatus.CANCELLED -> run.summary ?: USER_CANCELLED_RUN_SUMMARY
            TaskExecutionRunStatus.FAILED -> run.summary ?: LIVE_PROGRESS_FAILED_DETAIL
            TaskExecutionRunStatus.SUCCEEDED -> run.summary ?: LIVE_PROGRESS_COMPLETED_DETAIL
            TaskExecutionRunStatus.WAITING_CONFIRMATION -> run.summary ?: LIVE_PROGRESS_WAITING_CONFIRMATION_DETAIL
            TaskExecutionRunStatus.RUNNING -> run.summary ?: LIVE_PROGRESS_STREAMING_DETAIL
            TaskExecutionRunStatus.QUEUED -> run.summary ?: LIVE_PROGRESS_QUEUED_DETAIL
        }
        updateLiveProgress(
            workflowId = workflowId,
            runId = run.runId,
            taskId = run.taskId,
            phase = ExecutionLivePhase.TERMINAL,
            startedAt = parseRunInstant(run.startedAt),
            detail = resolvedDetail,
            providerId = providerId,
            modelId = modelId,
            event = milestoneEvent(
                detail = resolvedDetail,
                status = when (run.status) {
                    TaskExecutionRunStatus.FAILED -> ChatTraceStatus.ERROR
                    TaskExecutionRunStatus.CANCELLED -> ChatTraceStatus.DONE
                    TaskExecutionRunStatus.SUCCEEDED -> ChatTraceStatus.DONE
                    else -> ChatTraceStatus.INFO
                },
                id = TASK_EXECUTION_LIFECYCLE_EVENT_ID,
            ),
        )
    }

    private fun appendRecentEvent(
        existing: List<ChatStreamEvent>,
        event: ChatStreamEvent?,
    ): List<ChatStreamEvent> {
        val normalizedEvent = normalizeLiveEvent(event) ?: return existing
        val deduplicated = if (existing.lastOrNull()?.let { last ->
                last.kind == normalizedEvent.kind &&
                    last.status == normalizedEvent.status &&
                    last.detail == normalizedEvent.detail
            } == true
        ) {
            existing
        } else {
            existing + normalizedEvent
        }
        return deduplicated.takeLast(MAX_RECENT_LIVE_EVENTS)
    }

    private fun notifyLiveProgressUpdated(progress: TaskExecutionLiveProgress) {
        liveProgressListeners.forEach { listener ->
            listener.onLiveProgressUpdated(progress)
        }
    }

    private fun normalizeLiveEvent(event: ChatStreamEvent?): ChatStreamEvent? {
        event ?: return null
        val normalizedDetail = event.detail.trim().takeIf(String::isNotBlank)?.take(LIVE_PROGRESS_DETAIL_CHAR_LIMIT) ?: return null
        return event.copy(detail = normalizedDetail)
    }

    private fun milestoneEvent(
        detail: String,
        kind: ChatTraceKind = ChatTraceKind.TASK,
        status: ChatTraceStatus = ChatTraceStatus.INFO,
        id: String? = null,
    ): ChatStreamEvent {
        return ChatStreamEvent(
            kind = kind,
            detail = detail,
            status = status,
            id = id,
        )
    }

    private fun parseRunInstant(value: String?): Instant {
        val normalized = value?.trim().orEmpty()
        return runCatching { Instant.parse(normalized) }.getOrElse { Instant.now() }
    }

    private fun normalizeProgressMetadataValue(value: String?): String? {
        return value?.trim()?.takeIf(String::isNotBlank)
    }

    private fun ensureTaskExists(workflowId: String, taskId: String) {
        val exists = tasksService.parse(workflowId).any { task -> task.id == taskId }
        require(exists) { "Task $taskId does not exist in workflow $workflowId" }
    }

    private fun appendRun(
        existingRuns: List<TaskExecutionRun>,
        run: TaskExecutionRun,
    ): List<TaskExecutionRun> {
        return (existingRuns + run)
            .sortedWith(compareBy<TaskExecutionRun> { it.startedAt }.thenBy { it.runId })
    }

    private fun replaceRun(
        existingRuns: List<TaskExecutionRun>,
        updatedRun: TaskExecutionRun,
    ): List<TaskExecutionRun> {
        return existingRuns
            .map { run -> if (run.runId == updatedRun.runId) updatedRun else run }
            .sortedWith(compareBy<TaskExecutionRun> { it.startedAt }.thenBy { it.runId })
    }

    private fun buildRunId(taskId: String): String {
        return "run-${taskId.lowercase()}-${UUID.randomUUID()}"
    }

    private fun resolveExecutionSession(
        workflow: SpecWorkflow,
        task: StructuredTask,
        providerId: String?,
        trigger: ExecutionTrigger,
        sessionId: String?,
        sessionSource: WorkflowChatEntrySource,
    ) = sessionId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { existingSessionId ->
            sessionManager.updateWorkflowChatBinding(
                sessionId = existingSessionId,
                binding = WorkflowChatBinding(
                    workflowId = workflow.id,
                    focusedStage = StageId.IMPLEMENT,
                    source = sessionSource,
                    actionIntent = when (trigger) {
                        ExecutionTrigger.USER_EXECUTE -> WorkflowChatActionIntent.EXECUTE_TASK
                        ExecutionTrigger.USER_RETRY -> WorkflowChatActionIntent.RETRY_TASK
                        ExecutionTrigger.SYSTEM_RECOVERY -> WorkflowChatActionIntent.DISCUSS
                    },
                ),
            ).getOrThrow()
        }
        ?: sessionManager.createSession(
            title = buildSessionTitle(workflow, task, trigger),
            specTaskId = workflow.id,
            modelProvider = providerId,
            workflowChatBinding = WorkflowChatBinding(
                workflowId = workflow.id,
                focusedStage = StageId.IMPLEMENT,
                source = sessionSource,
                actionIntent = when (trigger) {
                    ExecutionTrigger.USER_EXECUTE -> WorkflowChatActionIntent.EXECUTE_TASK
                    ExecutionTrigger.USER_RETRY -> WorkflowChatActionIntent.RETRY_TASK
                    ExecutionTrigger.SYSTEM_RECOVERY -> WorkflowChatActionIntent.DISCUSS
                },
            ),
        ).getOrThrow()

    private fun buildSessionTitle(
        workflow: SpecWorkflow,
        task: StructuredTask,
        trigger: ExecutionTrigger,
    ): String {
        val workflowLabel = workflow.title.trim().ifBlank { workflow.id }
        val triggerLabel = when (trigger) {
            ExecutionTrigger.USER_EXECUTE -> "Execute"
            ExecutionTrigger.USER_RETRY -> "Retry"
            ExecutionTrigger.SYSTEM_RECOVERY -> "Recovery"
        }
        return "$workflowLabel · ${task.id} · $triggerLabel".take(MAX_SESSION_TITLE_LENGTH)
    }

    private fun buildExecutionLaunchArtifacts(
        workflow: SpecWorkflow,
        task: StructuredTask,
        run: TaskExecutionRun,
        trigger: ExecutionTrigger,
        previousRun: TaskExecutionRun?,
        suggestedRelatedFiles: List<String>,
        supplementalInstruction: String?,
        sessionSource: WorkflowChatEntrySource,
    ): ExecutionLaunchArtifacts {
        val dependencySummary = buildDependencySummary(workflow.id, task)
        val documentSummaries = buildDocumentSummaries(workflow)
        val clarificationConclusions = extractClarificationConclusions(workflow)
        val normalizedSupplementalInstruction = supplementalInstruction?.trim()?.takeIf(String::isNotBlank)
        val presentation = WorkflowChatExecutionLaunchPresentation(
            workflowId = workflow.id,
            taskId = task.id,
            taskTitle = task.title,
            runId = run.runId,
            focusedStage = workflow.currentStage,
            trigger = trigger,
            launchSurface = sessionSource.toExecutionLaunchSurface(),
            taskStatusBeforeExecution = task.status,
            taskPriority = task.priority,
            sections = listOf(
                buildExecutionPresentationSection(
                    kind = WorkflowChatExecutionPresentationSectionKind.DEPENDENCIES,
                    items = dependencySummary,
                    emptyStateReason = "No dependencies recorded.",
                    previewLimit = EXECUTION_DEPENDENCY_PREVIEW_LIMIT,
                ),
                buildExecutionPresentationSection(
                    kind = WorkflowChatExecutionPresentationSectionKind.ARTIFACT_SUMMARIES,
                    items = documentSummaries,
                    emptyStateReason = "No artifact summaries available.",
                    previewLimit = EXECUTION_ARTIFACT_SUMMARY_PREVIEW_LIMIT,
                ),
                buildExecutionPresentationSection(
                    kind = WorkflowChatExecutionPresentationSectionKind.CLARIFICATION_CONCLUSIONS,
                    items = clarificationConclusions,
                    emptyStateReason = "No confirmed clarifications recorded.",
                    previewLimit = EXECUTION_CLARIFICATION_PREVIEW_LIMIT,
                ),
                buildExecutionPresentationSection(
                    kind = WorkflowChatExecutionPresentationSectionKind.CODE_CONTEXT,
                    items = suggestedRelatedFiles,
                    emptyStateReason = "No candidate related files suggested.",
                    previewLimit = EXECUTION_CODE_CONTEXT_PREVIEW_LIMIT,
                ),
            ),
            supplementalInstruction = normalizedSupplementalInstruction,
            rawPromptDebugAvailable = true,
        )
        val prompt = buildExecutionPrompt(
            workflow = workflow,
            task = task,
            run = run,
            trigger = trigger,
            previousRun = previousRun,
            suggestedRelatedFiles = suggestedRelatedFiles,
            supplementalInstruction = normalizedSupplementalInstruction,
            dependencySummary = dependencySummary,
            documentSummaries = documentSummaries,
            clarificationConclusions = clarificationConclusions,
        )
        return ExecutionLaunchArtifacts(
            prompt = prompt,
            timelineMessage = buildExecutionLaunchTimelineMessage(workflow, presentation),
            presentation = presentation,
            debugPayload = WorkflowChatExecutionLaunchDebugPayload(rawPrompt = prompt),
        )
    }

    private fun buildExecutionPresentationSection(
        kind: WorkflowChatExecutionPresentationSectionKind,
        items: List<String>,
        emptyStateReason: String,
        previewLimit: Int,
    ): WorkflowChatExecutionPresentationSection {
        val previewItems = items
            .take(previewLimit.coerceAtLeast(1))
            .map { item -> clipExecutionPreview(item, EXECUTION_SECTION_ITEM_CHAR_LIMIT) }
        return WorkflowChatExecutionPresentationSection(
            kind = kind,
            itemCount = items.size,
            previewItems = previewItems,
            emptyStateReason = emptyStateReason.takeIf { items.isEmpty() },
            truncated = items.size > previewItems.size,
        )
    }

    private fun buildExecutionPrompt(
        workflow: SpecWorkflow,
        task: StructuredTask,
        run: TaskExecutionRun,
        trigger: ExecutionTrigger,
        previousRun: TaskExecutionRun?,
        suggestedRelatedFiles: List<String>,
        supplementalInstruction: String?,
        dependencySummary: List<String>,
        documentSummaries: List<String>,
        clarificationConclusions: List<String>,
    ): String {
        val taskSummary = listOf(
            "Task ID: ${task.id}",
            "Task Title: ${task.title}",
            "Task Status: ${task.status.name}",
            "Priority: ${task.priority.name}",
        )
        return buildString {
            appendLine("Interaction mode: workflow")
            appendLine("Workflow=${workflow.id} (docs: .spec-coding/specs/${workflow.id}/{requirements,design,tasks}.md)")
            appendLine(SpecCodingBundle.message("toolwindow.chat.mode.spec.instruction"))
            appendLine("Execution action: ${trigger.toExecutionActionName()}")
            appendLine("Run ID: ${run.runId}")
            appendLine()
            appendLine("## Task")
            taskSummary.forEach(::appendLine)
            appendLine()
            appendLine("## Stage Context")
            appendLine("Current phase: ${workflow.currentPhase.name}")
            appendLine("Current stage: ${workflow.currentStage.name}")
            appendLine()
            appendLine("## Dependencies")
            if (dependencySummary.isEmpty()) {
                appendLine("- None")
            } else {
                dependencySummary.forEach { line -> appendLine("- $line") }
            }
            appendLine()
            appendLine("## Artifact Summaries")
            if (documentSummaries.isEmpty()) {
                appendLine("- No artifact summaries available.")
            } else {
                documentSummaries.forEach { line -> appendLine("- $line") }
            }
            appendLine()
            appendLine("## Confirmed Clarification Conclusions")
            if (clarificationConclusions.isEmpty()) {
                appendLine("- None recorded.")
            } else {
                clarificationConclusions.forEach { line -> appendLine("- $line") }
            }
            appendLine()
            appendLine("## Candidate Related Files")
            if (suggestedRelatedFiles.isEmpty()) {
                appendLine("- None suggested.")
            } else {
                suggestedRelatedFiles.forEach { line -> appendLine("- $line") }
            }
            if (previousRun != null) {
                appendLine()
                appendLine("## Previous Execution")
                appendLine("Previous run ID: ${previousRun.runId}")
                appendLine("Previous status: ${previousRun.status.name}")
                previousRun.summary?.takeIf(String::isNotBlank)?.let { summary ->
                    appendLine("Previous summary: ${summary.trim()}")
                }
            }
            supplementalInstruction?.trim()?.takeIf(String::isNotBlank)?.let { instruction ->
                appendLine()
                appendLine("## Supplemental Instruction")
                appendLine(instruction)
            }
            appendLine()
            appendLine("## Execution Request")
            appendLine("Use the task-scoped context above to execute this structured task.")
            appendLine("Keep the response grounded in this repository and this task only.")
            appendLine("Format the response as clean Markdown with section headings on their own lines.")
            appendLine("Use only `## Plan`, `## Execute`, and `## Verify` as top-level headings when sections are needed.")
            appendLine("Inside sections, use bullets or numbered lists only; do not use `###` sub-headings.")
            appendLine("Do not mark the task completed automatically.")
            appendLine("End with:")
            appendLine("1. a concise implementation summary,")
            appendLine("2. suggested relatedFiles entries,")
            appendLine("3. an optional verificationResult draft if verification evidence exists.")
        }.trimEnd()
    }

    private fun buildExecutionLaunchTimelineMessage(
        workflow: SpecWorkflow,
        presentation: WorkflowChatExecutionLaunchPresentation,
    ): String {
        return buildString {
            appendLine("## Execution Request")
            appendLine("- Task: ${presentation.taskId} · ${presentation.taskTitle}")
            appendLine("- Workflow: ${presentation.workflowId}")
            appendLine("- Run: ${presentation.runId}")
            appendLine("- Stage: ${workflow.currentPhase.name} / ${presentation.focusedStage.name}")
            appendLine("- Trigger: ${presentation.trigger.toTimelineLabel()}")
            presentation.taskStatusBeforeExecution?.let { appendLine("- Status before execution: ${it.name}") }
            presentation.taskPriority?.let { appendLine("- Priority: ${it.name}") }
            appendLine()
            appendLine("## System Context")
            presentation.sections.forEach { section ->
                appendLine("- ${section.kind.toTimelineLabel()}: ${section.toTimelinePreview()}")
            }
            presentation.supplementalInstruction?.let { instruction ->
                appendLine()
                appendLine("## User Note")
                appendLine(
                    "- " +
                        clipExecutionPreview(instruction, EXECUTION_SUPPLEMENTAL_INSTRUCTION_CHAR_LIMIT),
                )
            }
            presentation.degradationReasons.forEach { reason ->
                appendLine("- Note: ${clipExecutionPreview(reason, EXECUTION_SECTION_ITEM_CHAR_LIMIT)}")
            }
            appendLine("- Internal execution prompt hidden from default chat view and retained for explicit debug inspection.")
        }.trimEnd()
    }

    private fun buildDependencySummary(
        workflowId: String,
        task: StructuredTask,
    ): List<String> {
        if (task.dependsOn.isEmpty()) {
            return emptyList()
        }
        val tasksById = tasksService.parse(workflowId).associateBy(StructuredTask::id)
        return task.dependsOn.map { dependencyId ->
            val dependency = tasksById[dependencyId]
            if (dependency == null) {
                "$dependencyId · missing"
            } else {
                "${dependency.id} · ${dependency.status.name} · ${dependency.title}"
            }
        }
    }

    private fun buildDocumentSummaries(workflow: SpecWorkflow): List<String> {
        return listOf(SpecPhase.SPECIFY, SpecPhase.DESIGN, SpecPhase.IMPLEMENT)
            .mapNotNull { phase ->
                val document = workflow.documents[phase] ?: return@mapNotNull null
                val fileName = phase.outputFileName
                val summary = summarizeDocument(document.content)
                "$fileName: $summary"
            }
    }

    private fun summarizeDocument(content: String): String {
        return content.lineSequence()
            .map(String::trim)
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("#") &&
                    !line.startsWith("```")
            }
            .take(6)
            .joinToString(" ")
            .replace(WHITESPACE_REGEX, " ")
            .take(DOCUMENT_SUMMARY_CHAR_LIMIT)
            .ifBlank { "No summary available." }
    }

    private fun extractClarificationConclusions(workflow: SpecWorkflow): List<String> {
        val fromRetryState = workflow.clarificationRetryState
            ?.takeIf { retry -> retry.confirmed }
            ?.confirmedContext
            .orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter { line -> line.startsWith("- ") || line.startsWith("* ") || line.matches(ORDERED_LIST_REGEX) }
            .map { line -> line.removePrefix("- ").removePrefix("* ").replace(ORDERED_LIST_REGEX, "").trim() }

        val fromDocuments = workflow.documents.values
            .asSequence()
            .flatMap { document -> extractClarificationSections(document.content).asSequence() }

        return (fromRetryState + fromDocuments)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(MAX_CLARIFICATION_LINES)
            .toList()
    }

    private fun extractClarificationSections(content: String): List<String> {
        val collected = mutableListOf<String>()
        var inClarificationSection = false
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("## ") || line.startsWith("### ") -> {
                    val heading = line.removePrefix("## ").removePrefix("### ").trim()
                    inClarificationSection = heading.equals("Clarifications", ignoreCase = true) ||
                        heading.equals("Decisions", ignoreCase = true) ||
                        heading.equals("Decisions from Clarification", ignoreCase = true)
                }

                inClarificationSection && (line.startsWith("- ") || line.startsWith("* ")) -> {
                    collected += line.removePrefix("- ").removePrefix("* ").trim()
                }

                inClarificationSection && line.matches(ORDERED_LIST_REGEX) -> {
                    collected += line.replace(ORDERED_LIST_REGEX, "").trim()
                }
            }
        }
        return collected
    }

    private fun buildRelatedFilesContextSnapshot(relatedFiles: List<String>): ContextSnapshot? {
        val projectRoot = project.basePath?.trim()?.takeIf(String::isNotBlank)?.let(Path::of) ?: return null
        val items = relatedFiles
            .take(MAX_CONTEXT_FILES)
            .mapNotNull { relativePath ->
                val absolutePath = projectRoot.resolve(relativePath).normalize()
                if (!absolutePath.startsWith(projectRoot) || !Files.isRegularFile(absolutePath)) {
                    return@mapNotNull null
                }
                val content = runCatching {
                    Files.readString(absolutePath)
                }.getOrNull()?.take(MAX_CONTEXT_FILE_CHARS)?.trim()
                    ?: return@mapNotNull null
                if (content.isBlank()) {
                    return@mapNotNull null
                }
                ContextItem(
                    type = ContextType.REFERENCED_FILE,
                    label = relativePath,
                    content = content,
                    filePath = absolutePath.toString(),
                )
            }
        if (items.isEmpty()) {
            return null
        }
        return ContextSnapshot(
            items = items,
            tokenBudget = items.sumOf(ContextItem::tokenEstimate).coerceAtLeast(1),
        )
    }

    private fun persistExecutionMessage(
        sessionId: String,
        role: ConversationRole,
        content: String,
        run: TaskExecutionRun,
        workflowId: String,
        requestId: String,
        providerId: String?,
        modelId: String?,
        previousRunId: String?,
        launchPresentation: WorkflowChatExecutionLaunchPresentation? = null,
        launchDebugPayload: WorkflowChatExecutionLaunchDebugPayload? = null,
        traceEvents: List<ChatStreamEvent> = emptyList(),
        startedAtMillis: Long? = null,
        finishedAtMillis: Long? = null,
    ): ConversationMessage {
        return sessionManager.addMessage(
            sessionId = sessionId,
            role = role,
            content = content,
            metadataJson = TaskExecutionSessionMetadataCodec.encode(
                run = run,
                workflowId = workflowId,
                requestId = requestId,
                providerId = providerId,
                modelId = modelId,
                previousRunId = previousRunId,
                launchPresentation = launchPresentation,
                launchDebugPayload = launchDebugPayload,
                traceEvents = traceEvents,
                startedAtMillis = startedAtMillis,
                finishedAtMillis = finishedAtMillis,
            ),
        ).getOrThrow()
    }

    private fun normalizeTaskLifecycleForExecution(
        workflowId: String,
        task: StructuredTask,
        auditContext: Map<String, String>,
    ) {
        if (task.status == TaskStatus.BLOCKED || task.status == TaskStatus.IN_PROGRESS) {
            tasksService.transitionStatus(
                workflowId = workflowId,
                taskId = task.id,
                to = TaskStatus.PENDING,
                auditContext = auditContext,
            )
        }
    }

    private fun transitionTaskToBlocked(
        workflowId: String,
        taskId: String,
        reason: String,
        auditContext: Map<String, String>,
    ) {
        val currentTask = resolveTask(workflowId, taskId)
        if (currentTask.status == TaskStatus.PENDING || currentTask.status == TaskStatus.IN_PROGRESS) {
            tasksService.transitionStatus(
                workflowId = workflowId,
                taskId = taskId,
                to = TaskStatus.BLOCKED,
                reason = reason,
                auditContext = auditContext,
            )
        }
    }

    private fun buildExecutionAuditContext(
        auditContext: Map<String, String>,
        task: StructuredTask,
        run: TaskExecutionRun,
        trigger: ExecutionTrigger,
        sessionId: String,
        providerId: String?,
        modelId: String?,
        requestId: String,
        previousRunId: String?,
    ): Map<String, String> {
        return linkedMapOf<String, String>().apply {
            putAll(auditContext)
            put("taskExecutionAction", trigger.toExecutionActionName())
            put("taskExecutionRunId", run.runId)
            put("taskExecutionTrigger", trigger.name)
            put("taskSessionId", sessionId)
            put("taskRequestId", requestId)
            put("taskStatusBeforeExecution", task.status.name)
            providerId?.takeIf(String::isNotBlank)?.let { put("providerId", it.trim()) }
            modelId?.takeIf(String::isNotBlank)?.let { put("modelId", it.trim()) }
            previousRunId?.takeIf(String::isNotBlank)?.let { put("previousRunId", it.trim()) }
        }
    }

    private fun normalizeLegacyInProgressTask(
        workflowId: String,
        taskId: String,
    ) {
        val currentTask = resolveTask(workflowId, taskId)
        if (currentTask.status != TaskStatus.IN_PROGRESS) {
            return
        }
        tasksService.transitionStatus(
            workflowId = workflowId,
            taskId = taskId,
            to = TaskStatus.PENDING,
            reason = "Recovered legacy IN_PROGRESS status into execution run state.",
            auditContext = mapOf(
                "taskExecutionAction" to ExecutionTrigger.SYSTEM_RECOVERY.toExecutionActionName(),
            ),
        )
    }

    private fun resolvePreviousRun(
        workflow: SpecWorkflow,
        taskId: String,
        previousRunId: String?,
        trigger: ExecutionTrigger,
    ): TaskExecutionRun? {
        if (trigger != ExecutionTrigger.USER_RETRY) {
            return null
        }
        val explicitRunId = previousRunId?.trim()?.takeIf(String::isNotBlank)
        val previousRun = when {
            explicitRunId != null -> workflow.taskExecutionRuns.firstOrNull { run -> run.runId == explicitRunId }
            else -> workflow.taskExecutionRuns
                .filter { run -> run.taskId == taskId && run.status.isTerminal() }
                .maxWithOrNull(compareBy<TaskExecutionRun> { it.startedAt }.thenBy { it.runId })
        }
        if (explicitRunId != null && previousRun == null) {
            throw MissingTaskExecutionRunError(explicitRunId)
        }
        return previousRun
    }

    private fun resolveTask(workflowId: String, taskId: String): StructuredTask {
        val normalizedTaskId = normalizeTaskId(taskId)
        return tasksService.parse(workflowId)
            .firstOrNull { task -> task.id == normalizedTaskId }
            ?: throw MissingStructuredTaskError(normalizedTaskId)
    }

    private fun loadRun(workflowId: String, runId: String): TaskExecutionRun? {
        val normalizedRunId = runId.trim().takeIf(String::isNotBlank) ?: return null
        return storage.loadWorkflow(workflowId).getOrThrow()
            .taskExecutionRuns
            .firstOrNull { run -> run.runId == normalizedRunId }
    }

    private fun ensureRunNotCancelled(workflowId: String, runId: String) {
        val currentRun = loadRun(workflowId, runId) ?: return
        if (currentRun.status == TaskExecutionRunStatus.CANCELLED) {
            throw CancellationException(currentRun.summary ?: USER_CANCELLED_RUN_SUMMARY)
        }
    }

    private fun markRunCancelled(
        workflowId: String,
        runId: String,
        cancelSummary: String,
    ) {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val currentRun = workflow.taskExecutionRuns.firstOrNull { run -> run.runId == runId } ?: return
        if (currentRun.status == TaskExecutionRunStatus.CANCELLED) {
            return
        }
        if (currentRun.status.isTerminal() || !currentRun.status.canTransitionTo(TaskExecutionRunStatus.CANCELLED)) {
            return
        }
        updateRunStatus(
            workflowId = workflowId,
            runId = runId,
            status = TaskExecutionRunStatus.CANCELLED,
            summary = cancelSummary,
        )
    }

    private fun markRunFailed(
        workflowId: String,
        runId: String,
        failureSummary: String,
    ) {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val currentRun = workflow.taskExecutionRuns.firstOrNull { run -> run.runId == runId } ?: return
        if (currentRun.status.isTerminal() || !currentRun.status.canTransitionTo(TaskExecutionRunStatus.FAILED)) {
            return
        }
        updateRunStatus(
            workflowId = workflowId,
            runId = runId,
            status = TaskExecutionRunStatus.FAILED,
            summary = failureSummary,
        )
    }

    private fun summarizeAssistantReply(content: String): String {
        return content
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" ")
            .replace(WHITESPACE_REGEX, " ")
            .take(RUN_SUMMARY_CHAR_LIMIT)
            .ifBlank { "AI execution finished. Review the task session for details." }
    }

    private fun summarizeFailure(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isBlank()) {
            "AI execution failed."
        } else {
            "AI execution failed: ${message.take(RUN_SUMMARY_CHAR_LIMIT)}"
        }
    }

    private fun summarizeCancellation(error: CancellationException): String {
        val message = error.message?.trim().orEmpty()
        return message.takeIf(String::isNotBlank)?.take(RUN_SUMMARY_CHAR_LIMIT) ?: USER_CANCELLED_RUN_SUMMARY
    }

    private fun recordExecutionLaunchAudit(
        workflowId: String,
        task: StructuredTask,
        run: TaskExecutionRun,
        sessionId: String,
        messageId: String,
        requestId: String,
        providerId: String?,
        modelId: String?,
        previousRunId: String?,
        rawPrompt: String,
        visibleSummary: String,
        presentation: WorkflowChatExecutionLaunchPresentation,
    ) {
        val sectionKinds = presentation.sections.joinToString(",") { section -> section.kind.name }
        val sectionCounts = presentation.sections.joinToString(",") { section ->
            "${section.kind.name}=${section.itemCount}"
        }
        storage.appendAuditEvent(
            workflowId = workflowId,
            eventType = SpecAuditEventType.TASK_EXECUTION_REQUEST_RECORDED,
            details = linkedMapOf(
                "runId" to run.runId,
                "taskId" to task.id,
                "trigger" to run.trigger.name,
                "sessionId" to sessionId,
                "messageId" to messageId,
                "requestId" to requestId,
                "launchSurface" to presentation.launchSurface.name,
                "focusedStage" to presentation.focusedStage.name,
                "taskStatusBeforeExecution" to presentation.taskStatusBeforeExecution?.name.orEmpty(),
                "taskPriority" to presentation.taskPriority?.name.orEmpty(),
                "providerId" to providerId.orEmpty(),
                "modelId" to modelId.orEmpty(),
                "previousRunId" to previousRunId.orEmpty(),
                "rawPromptStorage" to "session_message_metadata",
                "rawPromptChars" to rawPrompt.length.toString(),
                "rawPromptDigest" to sha256Hex(rawPrompt),
                "visibleSummaryPreview" to clipExecutionPreview(visibleSummary, EXECUTION_AUDIT_SUMMARY_CHAR_LIMIT),
                "sectionKinds" to sectionKinds,
                "sectionCounts" to sectionCounts,
                "supplementalInstructionPresent" to presentation.supplementalInstruction.isNullOrBlank().not().toString(),
                "rawPromptDebugAvailable" to presentation.rawPromptDebugAvailable.toString(),
            ),
        ).getOrThrow()
    }

    private fun clipExecutionPreview(content: String, maxChars: Int): String {
        return content.trim().replace(WHITESPACE_REGEX, " ").let { normalized ->
            if (normalized.length <= maxChars) {
                normalized
            } else {
                normalized.take(maxChars - 3).trimEnd() + "..."
            }
        }
    }

    private fun sha256Hex(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun buildQueuedSummary(
        trigger: ExecutionTrigger,
        previousRun: TaskExecutionRun?,
    ): String {
        val action = when (trigger) {
            ExecutionTrigger.USER_EXECUTE -> "Queued spec page AI execution."
            ExecutionTrigger.USER_RETRY -> "Queued spec page AI retry."
            ExecutionTrigger.SYSTEM_RECOVERY -> "Recovered execution run."
        }
        val previousSummary = previousRun?.runId?.let { runId -> " Previous run: $runId." }.orEmpty()
        return action + previousSummary
    }

    private fun normalizeTaskId(taskId: String): String {
        val normalizedTaskId = taskId.trim().uppercase()
        require(TASK_ID_REGEX.matches(normalizedTaskId)) {
            "taskId must use the format T-001."
        }
        return normalizedTaskId
    }

    private fun cancelRequestAcrossProviders(providerId: String?, requestId: String) {
        val router = LlmRouter.getInstance()
        router.cancel(providerId = providerId, requestId = requestId)
        router.cancel(providerId = ClaudeCliLlmProvider.ID, requestId = requestId)
        router.cancel(providerId = CodexCliLlmProvider.ID, requestId = requestId)
    }

    companion object {
        private val TASK_ID_REGEX = Regex("""^T-\d{3}$""")
        private val ORDERED_LIST_REGEX = Regex("""^\d+\.\s+""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private const val MAX_SESSION_TITLE_LENGTH = 80
        private const val MAX_CONTEXT_FILES = 6
        private const val MAX_CONTEXT_FILE_CHARS = 12_000
        private const val EXECUTION_DEPENDENCY_PREVIEW_LIMIT = 2
        private const val EXECUTION_ARTIFACT_SUMMARY_PREVIEW_LIMIT = 2
        private const val EXECUTION_CLARIFICATION_PREVIEW_LIMIT = 2
        private const val EXECUTION_CODE_CONTEXT_PREVIEW_LIMIT = 3
        private const val EXECUTION_SECTION_ITEM_CHAR_LIMIT = 180
        private const val EXECUTION_SUPPLEMENTAL_INSTRUCTION_CHAR_LIMIT = 220
        private const val EXECUTION_AUDIT_SUMMARY_CHAR_LIMIT = 240
        private const val RUN_SUMMARY_CHAR_LIMIT = 260
        private const val DOCUMENT_SUMMARY_CHAR_LIMIT = 240
        private const val MAX_CLARIFICATION_LINES = 12
        private const val MAX_RECENT_LIVE_EVENTS = 5
        private const val LIVE_PROGRESS_DETAIL_CHAR_LIMIT = 220
        private const val USER_CANCELLED_RUN_SUMMARY = "AI execution cancelled by user."
        private const val LIVE_PROGRESS_QUEUED_DETAIL = "Execution queued."
        private const val LIVE_PROGRESS_PREPARING_DETAIL = "Preparing task context."
        private const val LIVE_PROGRESS_REQUEST_DISPATCHED_DETAIL = "Request dispatched to provider."
        private const val LIVE_PROGRESS_STREAMING_DETAIL = "AI is returning content."
        private const val LIVE_PROGRESS_WAITING_CONFIRMATION_DETAIL = "Waiting for confirmation."
        private const val LIVE_PROGRESS_CANCELLING_DETAIL = "Cancelling execution."
        private const val LIVE_PROGRESS_COMPLETED_DETAIL = "AI execution completed."
        private const val LIVE_PROGRESS_FAILED_DETAIL = "AI execution failed."
        private const val TASK_EXECUTION_LIFECYCLE_EVENT_ID = "task_execution_lifecycle"

        fun getInstance(project: Project): SpecTaskExecutionService = project.service()
    }
}

fun interface TaskExecutionLiveProgressListener {
    fun onLiveProgressUpdated(progress: TaskExecutionLiveProgress)
}

private fun ExecutionTrigger.toExecutionActionName(): String {
    return when (this) {
        ExecutionTrigger.USER_EXECUTE -> "EXECUTE_WITH_AI"
        ExecutionTrigger.USER_RETRY -> "RETRY_EXECUTION"
        ExecutionTrigger.SYSTEM_RECOVERY -> "SYSTEM_RECOVERY"
    }
}

internal object TaskExecutionSessionMetadataCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    internal data class DecodedMetadata(
        val runId: String?,
        val taskId: String?,
        val workflowId: String?,
        val trigger: ExecutionTrigger?,
        val requestId: String?,
        val providerId: String?,
        val modelId: String?,
        val previousRunId: String?,
        val launchPresentation: WorkflowChatExecutionLaunchPresentation? = null,
        val launchDebugPayload: WorkflowChatExecutionLaunchDebugPayload? = null,
    )

    fun encode(
        run: TaskExecutionRun,
        workflowId: String,
        requestId: String,
        providerId: String?,
        modelId: String?,
        previousRunId: String?,
        launchPresentation: WorkflowChatExecutionLaunchPresentation? = null,
        launchDebugPayload: WorkflowChatExecutionLaunchDebugPayload? = null,
        traceEvents: List<ChatStreamEvent> = emptyList(),
        startedAtMillis: Long? = null,
        finishedAtMillis: Long? = null,
    ): String {
        val payload = linkedMapOf<String, JsonElement>(
            "format" to JsonPrimitive("task_execution_session_v1"),
            "runId" to JsonPrimitive(run.runId),
            "taskId" to JsonPrimitive(run.taskId),
            "workflowId" to JsonPrimitive(workflowId),
            "trigger" to JsonPrimitive(run.trigger.name),
            "requestId" to JsonPrimitive(requestId),
        ).apply {
            providerId?.takeIf(String::isNotBlank)?.let { put("providerId", JsonPrimitive(it.trim())) }
            modelId?.takeIf(String::isNotBlank)?.let { put("modelId", JsonPrimitive(it.trim())) }
            previousRunId?.takeIf(String::isNotBlank)?.let { put("previousRunId", JsonPrimitive(it.trim())) }
            launchPresentation?.let {
                put("execution_launch_presentation", WorkflowChatExecutionLaunchPresentationCodec.encodeToJson(it))
            }
            launchDebugPayload?.let {
                put("execution_launch_debug", WorkflowChatExecutionLaunchDebugPayloadCodec.encodeToJson(it))
            }
            startedAtMillis?.takeIf { it >= 0L }?.let { put("started_at_ms", JsonPrimitive(it)) }
            finishedAtMillis?.takeIf { it >= 0L }?.let { put("finished_at_ms", JsonPrimitive(it)) }
            val normalizedTraceEvents = traceEvents
                .filter { it.detail.isNotBlank() }
                .takeLast(MAX_TRACE_EVENTS)
            if (normalizedTraceEvents.isNotEmpty()) {
                put(
                    "trace_events",
                    JsonArray(normalizedTraceEvents.map { event -> event.toTraceMetadataJson() }),
                )
            }
        }
        return JsonObject(payload).toString()
    }

    private fun ChatStreamEvent.toTraceMetadataJson(): JsonObject {
        return JsonObject(
            linkedMapOf<String, JsonElement>(
                "kind" to JsonPrimitive(kind.name),
                "detail" to JsonPrimitive(detail),
                "status" to JsonPrimitive(status.name),
            ).apply {
                id?.let { put("id", JsonPrimitive(it)) }
                sequence?.let { put("sequence", JsonPrimitive(it)) }
                if (metadata.isNotEmpty()) {
                    put(
                        "metadata",
                        JsonObject(
                            metadata.entries.associate { (key, value) ->
                                key to JsonPrimitive(value)
                            },
                        ),
                    )
                }
            },
        )
    }

    fun decode(metadataJson: String?): DecodedMetadata {
        if (metadataJson.isNullOrBlank()) {
            return DecodedMetadata(null, null, null, null, null, null, null, null, null, null)
        }
        val root = runCatching { json.parseToJsonElement(metadataJson) as? JsonObject }
            .getOrNull()
            ?: return DecodedMetadata(null, null, null, null, null, null, null, null, null, null)
        val trigger = root["trigger"]
            ?.toString()
            ?.trim('"')
            ?.let { raw -> ExecutionTrigger.entries.firstOrNull { entry -> entry.name == raw } }
        return DecodedMetadata(
            runId = root["runId"]?.toString()?.trim('"'),
            taskId = root["taskId"]?.toString()?.trim('"'),
            workflowId = root["workflowId"]?.toString()?.trim('"'),
            trigger = trigger,
            requestId = root["requestId"]?.toString()?.trim('"'),
            providerId = root["providerId"]?.toString()?.trim('"'),
            modelId = root["modelId"]?.toString()?.trim('"'),
            previousRunId = root["previousRunId"]?.toString()?.trim('"'),
            launchPresentation = WorkflowChatExecutionLaunchPresentationCodec.decodeFromJson(
                root["execution_launch_presentation"],
            ),
            launchDebugPayload = WorkflowChatExecutionLaunchDebugPayloadCodec.decodeFromJson(
                root["execution_launch_debug"],
            ),
        )
    }

    private const val MAX_TRACE_EVENTS = 600
}

private fun WorkflowChatEntrySource.toExecutionLaunchSurface(): WorkflowChatExecutionLaunchSurface {
    return when (this) {
        WorkflowChatEntrySource.TASK_PANEL -> WorkflowChatExecutionLaunchSurface.TASK_ROW
        WorkflowChatEntrySource.SPEC_PAGE,
        WorkflowChatEntrySource.SIDEBAR,
        WorkflowChatEntrySource.MODE_SWITCH,
        WorkflowChatEntrySource.SESSION_RESTORE,
        -> WorkflowChatExecutionLaunchSurface.WORKFLOW_CHAT
    }
}

private fun WorkflowChatExecutionPresentationSectionKind.toTimelineLabel(): String {
    return when (this) {
        WorkflowChatExecutionPresentationSectionKind.DEPENDENCIES -> "Dependencies"
        WorkflowChatExecutionPresentationSectionKind.ARTIFACT_SUMMARIES -> "Artifact summaries"
        WorkflowChatExecutionPresentationSectionKind.CLARIFICATION_CONCLUSIONS -> "Clarifications"
        WorkflowChatExecutionPresentationSectionKind.CODE_CONTEXT -> "Candidate related files"
    }
}

private fun WorkflowChatExecutionPresentationSection.toTimelinePreview(): String {
    if (itemCount <= 0) {
        return emptyStateReason ?: "None"
    }
    val primaryPreview = previewItems.firstOrNull().orEmpty().ifBlank {
        "$itemCount item" + if (itemCount == 1) "" else "s"
    }
    val hiddenCount = (itemCount - 1).coerceAtLeast(0)
    return if (hiddenCount > 0) {
        "$primaryPreview (+$hiddenCount more)"
    } else {
        primaryPreview
    }
}

private fun ExecutionTrigger.toTimelineLabel(): String {
    return when (this) {
        ExecutionTrigger.USER_EXECUTE -> "Execute with AI"
        ExecutionTrigger.USER_RETRY -> "Retry execution"
        ExecutionTrigger.SYSTEM_RECOVERY -> "Recovered execution"
    }
}
