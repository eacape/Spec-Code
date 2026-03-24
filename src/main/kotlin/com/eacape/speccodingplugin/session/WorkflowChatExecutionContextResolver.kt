package com.eacape.speccodingplugin.session

import com.eacape.speccodingplugin.spec.SpecStorage
import com.eacape.speccodingplugin.spec.TaskExecutionRun
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

data class WorkflowChatExecutionContext(
    val workflowId: String,
    val runId: String,
    val taskId: String,
    val status: TaskExecutionRunStatus,
    val enteredAt: String,
)

@Service(Service.Level.PROJECT)
class WorkflowChatExecutionContextResolver(private val project: Project) {
    private var _storageOverride: SpecStorage? = null

    private val storage: SpecStorage by lazy { _storageOverride ?: SpecStorage.getInstance(project) }

    internal constructor(project: Project, storage: SpecStorage) : this(project) {
        _storageOverride = storage
    }

    fun resolve(binding: WorkflowChatBinding?): WorkflowChatExecutionContext? {
        val normalizedBinding = binding?.normalizedOrNull() ?: return null
        return resolve(normalizedBinding.workflowId)
    }

    fun resolve(workflowId: String): WorkflowChatExecutionContext? {
        val normalizedWorkflowId = workflowId.trim().ifBlank { return null }
        val workflow = storage.loadWorkflow(normalizedWorkflowId).getOrNull() ?: return null
        return resolve(normalizedWorkflowId, workflow.taskExecutionRuns)
    }

    internal fun resolve(
        workflowId: String,
        runs: List<TaskExecutionRun>,
    ): WorkflowChatExecutionContext? {
        val currentRun = selectContextRun(runs) ?: return null
        return WorkflowChatExecutionContext(
            workflowId = workflowId,
            runId = currentRun.runId,
            taskId = currentRun.taskId,
            status = currentRun.status,
            enteredAt = currentRun.finishedAt ?: currentRun.startedAt,
        )
    }

    internal fun selectContextRun(runs: List<TaskExecutionRun>): TaskExecutionRun? {
        return runs.asSequence()
            .filter(::isContextEligible)
            // Until the workflow spec defines multi-run focus policy, prefer the latest waiting/active run.
            .sortedWith(
                compareBy<TaskExecutionRun> { contextPriority(it.status) }
                    .thenByDescending { it.startedAt }
                    .thenByDescending { it.runId },
            )
            .firstOrNull()
    }

    private fun isContextEligible(run: TaskExecutionRun): Boolean {
        return when (run.status) {
            TaskExecutionRunStatus.QUEUED,
            TaskExecutionRunStatus.RUNNING,
            TaskExecutionRunStatus.WAITING_CONFIRMATION,
            -> true

            TaskExecutionRunStatus.FAILED,
            TaskExecutionRunStatus.CANCELLED,
            TaskExecutionRunStatus.SUCCEEDED,
            -> false
        }
    }

    private fun contextPriority(status: TaskExecutionRunStatus): Int {
        return when (status) {
            TaskExecutionRunStatus.WAITING_CONFIRMATION -> 0
            TaskExecutionRunStatus.RUNNING -> 1
            TaskExecutionRunStatus.QUEUED -> 2
            TaskExecutionRunStatus.FAILED,
            TaskExecutionRunStatus.CANCELLED,
            TaskExecutionRunStatus.SUCCEEDED,
            -> 3
        }
    }

    companion object {
        fun getInstance(project: Project): WorkflowChatExecutionContextResolver = project.service()
    }
}
