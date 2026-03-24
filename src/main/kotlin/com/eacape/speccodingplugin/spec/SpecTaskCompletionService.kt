package com.eacape.speccodingplugin.spec

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class SpecTaskCompletionService(private val project: Project) {
    data class TaskCompletionPlan(
        val planId: String,
        val workflowId: String,
        val taskId: String,
        val generatedAt: String,
        val suggestedRelatedFiles: List<String>,
        val existingVerificationResult: TaskVerificationResult?,
        val awaitingExecutionConfirmation: Boolean,
    )

    private var _storageOverride: SpecStorage? = null
    private var _tasksServiceOverride: SpecTasksService? = null
    private var _relatedFilesResolverOverride: ((String, List<String>) -> List<String>)? = null
    private var _taskExecutionServiceOverride: SpecTaskExecutionService? = null

    private val storage: SpecStorage by lazy { _storageOverride ?: SpecStorage.getInstance(project) }
    private val tasksService: SpecTasksService by lazy {
        _tasksServiceOverride ?: SpecTasksService.getInstance(project)
    }
    private val relatedFilesResolver: (String, List<String>) -> List<String> by lazy {
        _relatedFilesResolverOverride ?: { taskId, existingRelatedFiles ->
            SpecRelatedFilesService.getInstance(project).suggestRelatedFiles(taskId, existingRelatedFiles)
        }
    }
    private val taskExecutionService: SpecTaskExecutionService by lazy {
        _taskExecutionServiceOverride ?: SpecTaskExecutionService.getInstance(project)
    }
    private val pendingPlans = ConcurrentHashMap<String, TaskCompletionPlan>()

    internal constructor(
        project: Project,
        storage: SpecStorage,
        tasksService: SpecTasksService,
        relatedFilesResolver: (String, List<String>) -> List<String>,
        taskExecutionService: SpecTaskExecutionService,
    ) : this(project) {
        _storageOverride = storage
        _tasksServiceOverride = tasksService
        _relatedFilesResolverOverride = relatedFilesResolver
        _taskExecutionServiceOverride = taskExecutionService
    }

    fun previewCompletion(workflowId: String, taskId: String): TaskCompletionPlan {
        val task = resolveTask(workflowId, taskId)
        val plan = TaskCompletionPlan(
            planId = UUID.randomUUID().toString(),
            workflowId = workflowId.trim(),
            taskId = task.id,
            generatedAt = Instant.now().toString(),
            suggestedRelatedFiles = relatedFilesResolver(task.id, task.relatedFiles),
            existingVerificationResult = task.verificationResult,
            awaitingExecutionConfirmation = task.awaitingCompletionConfirmation,
        )
        pendingPlans[plan.planId] = plan
        return plan
    }

    fun completeTask(
        planId: String,
        relatedFiles: List<String>,
        verificationResult: TaskVerificationResult?,
        auditContext: Map<String, String> = emptyMap(),
        completionRunSummary: String? = null,
    ): StructuredTask {
        val plan = pendingPlans[planId]
            ?: throw IllegalArgumentException("Task completion plan not found: $planId")
        val completedTask = completeTask(
            workflowId = plan.workflowId,
            taskId = plan.taskId,
            relatedFiles = relatedFiles,
            verificationResult = verificationResult,
            auditContext = auditContext,
            completionRunSummary = completionRunSummary,
        )
        pendingPlans.remove(planId)
        return completedTask
    }

    fun completeTask(
        workflowId: String,
        taskId: String,
        relatedFiles: List<String>,
        verificationResult: TaskVerificationResult?,
        auditContext: Map<String, String> = emptyMap(),
        completionRunSummary: String? = null,
    ): StructuredTask {
        val normalizedWorkflowId = workflowId.trim()
        val normalizedTask = resolveTask(normalizedWorkflowId, taskId)
        return runCatching {
            completeTaskOnce(
                workflowId = normalizedWorkflowId,
                task = normalizedTask,
                relatedFiles = relatedFiles,
                verificationResult = verificationResult,
                auditContext = auditContext,
                completionRunSummary = completionRunSummary,
            )
        }.recoverCatching { error ->
            if (error !is InvalidTasksArtifactEditError) {
                throw error
            }
            val repairResult = SpecTasksQuickFixService(
                project = project,
                storage = storage,
                artifactService = SpecArtifactService(project),
                tasksService = tasksService,
            ).repairTasksArtifact(
                workflowId = normalizedWorkflowId,
                trigger = TASK_COMPLETION_AUTO_REPAIR_TRIGGER,
            )
            if (repairResult.issuesAfter.isNotEmpty()) {
                throw error
            }
            completeTaskOnce(
                workflowId = normalizedWorkflowId,
                task = resolveTask(normalizedWorkflowId, normalizedTask.id),
                relatedFiles = relatedFiles,
                verificationResult = verificationResult,
                auditContext = auditContext,
                completionRunSummary = completionRunSummary,
            )
        }.getOrThrow()
    }

    private fun completeTaskOnce(
        workflowId: String,
        task: StructuredTask,
        relatedFiles: List<String>,
        verificationResult: TaskVerificationResult?,
        auditContext: Map<String, String>,
        completionRunSummary: String?,
    ): StructuredTask {
        tasksService.updateRelatedFiles(
            workflowId = workflowId,
            taskId = task.id,
            files = relatedFiles,
            auditContext = auditContext,
        )
        when {
            verificationResult != null -> tasksService.updateVerificationResult(
                workflowId = workflowId,
                taskId = task.id,
                verificationResult = verificationResult,
                auditContext = auditContext,
            )

            task.verificationResult != null -> tasksService.clearVerificationResult(
                workflowId = workflowId,
                taskId = task.id,
                auditContext = auditContext,
            )
        }
        if (task.awaitingCompletionConfirmation) {
            taskExecutionService.resolveWaitingConfirmationRun(
                workflowId = workflowId,
                taskId = task.id,
                summary = completionRunSummary ?: "Completed from shared task completion flow.",
            )
        }
        tasksService.transitionStatus(
            workflowId = workflowId,
            taskId = task.id,
            to = TaskStatus.COMPLETED,
            auditContext = auditContext,
        )
        return resolveTask(workflowId, task.id)
    }

    private fun resolveTask(workflowId: String, taskId: String): StructuredTask {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val normalizedTaskId = taskId.trim().uppercase()
        return tasksService.parse(workflowId)
            .attachActiveExecutionRuns(workflow.taskExecutionRuns)
            .firstOrNull { task -> task.id == normalizedTaskId }
            ?: throw MissingStructuredTaskError(normalizedTaskId)
    }

    companion object {
        private const val TASK_COMPLETION_AUTO_REPAIR_TRIGGER = "task-completion-auto-repair"

        fun getInstance(project: Project): SpecTaskCompletionService = project.service()
    }
}