package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.CodeContextPack
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset

internal data class SpecWorkflowLoadedStateApplyRequest(
    val workflowId: String,
    val selectedWorkflowId: String?,
    val loadedState: SpecWorkflowPanelLoadedState,
    val followCurrentPhase: Boolean = false,
    val previousSelectedWorkflowId: String? = null,
)

internal data class SpecWorkflowLoadedCoreUiState(
    val workflow: SpecWorkflow,
    val snapshot: SpecWorkflowUiSnapshot,
    val followCurrentPhase: Boolean,
    val codeContextResult: Result<CodeContextPack>?,
)

internal data class SpecWorkflowLoadedTaskUiState(
    val workflow: SpecWorkflow,
    val snapshot: SpecWorkflowUiSnapshot,
    val tasks: List<StructuredTask>,
    val liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
    val refreshedAtMillis: Long,
)

internal interface SpecWorkflowLoadedStateCallbacks {
    fun clearOpenedWorkflowUi(resetHighlight: Boolean)

    fun applyWorkflowCore(state: SpecWorkflowLoadedCoreUiState)

    fun applyWorkflowSources(
        workflow: SpecWorkflow,
        assets: List<WorkflowSourceAsset>,
        preserveSelection: Boolean,
    )

    fun applyWorkflowTasks(state: SpecWorkflowLoadedTaskUiState)

    fun restorePendingClarificationState(workflowId: String)

    fun applyPendingOpenWorkflowRequest(workflowId: String)

    fun updateWorkflowActionAvailability(workflow: SpecWorkflow)

    fun setStatusText(text: String)
}

internal class SpecWorkflowLoadedStateCoordinator(
    private val buildUiSnapshot: (SpecWorkflow) -> SpecWorkflowUiSnapshot,
    private val decorateTasksWithExecutionState: (
        workflow: SpecWorkflow,
        tasks: List<StructuredTask>,
        liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
    ) -> List<StructuredTask>,
    private val renderFailureMessage: (Throwable) -> String,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {

    fun apply(
        request: SpecWorkflowLoadedStateApplyRequest,
        callbacks: SpecWorkflowLoadedStateCallbacks,
        onUpdated: ((SpecWorkflow) -> Unit)? = null,
    ) {
        if (request.selectedWorkflowId != request.workflowId) {
            return
        }

        val workflow = request.loadedState.workflow
        if (workflow == null) {
            callbacks.clearOpenedWorkflowUi(resetHighlight = false)
            return
        }

        val snapshot = request.loadedState.uiSnapshot ?: buildUiSnapshot(workflow)
        callbacks.applyWorkflowCore(
            SpecWorkflowLoadedCoreUiState(
                workflow = workflow,
                snapshot = snapshot,
                followCurrentPhase = request.followCurrentPhase,
                codeContextResult = request.loadedState.codeContextResult,
            ),
        )

        request.loadedState.sourcesResult
            ?.onSuccess { sources ->
                callbacks.applyWorkflowSources(
                    workflow = workflow,
                    assets = sources,
                    preserveSelection = request.previousSelectedWorkflowId == workflow.id,
                )
            }
            ?.onFailure { error ->
                callbacks.applyWorkflowSources(
                    workflow = workflow,
                    assets = emptyList(),
                    preserveSelection = false,
                )
                callbacks.setStatusText(buildFailureStatusText(error))
            }

        val refreshedAtMillis = currentTimeMillis()
        request.loadedState.tasksResult
            .onSuccess { tasks ->
                callbacks.applyWorkflowTasks(
                    SpecWorkflowLoadedTaskUiState(
                        workflow = workflow,
                        snapshot = snapshot,
                        tasks = decorateTasksWithExecutionState(
                            workflow,
                            tasks,
                            request.loadedState.liveProgressByTaskId,
                        ),
                        liveProgressByTaskId = request.loadedState.liveProgressByTaskId,
                        refreshedAtMillis = refreshedAtMillis,
                    ),
                )
            }
            .onFailure { error ->
                callbacks.applyWorkflowTasks(
                    SpecWorkflowLoadedTaskUiState(
                        workflow = workflow,
                        snapshot = snapshot,
                        tasks = emptyList(),
                        liveProgressByTaskId = emptyMap(),
                        refreshedAtMillis = refreshedAtMillis,
                    ),
                )
                callbacks.setStatusText(buildFailureStatusText(error))
            }

        if (request.previousSelectedWorkflowId != null && request.previousSelectedWorkflowId != workflow.id) {
            callbacks.restorePendingClarificationState(workflow.id)
        }
        callbacks.applyPendingOpenWorkflowRequest(workflow.id)
        callbacks.updateWorkflowActionAvailability(workflow)
        onUpdated?.invoke(workflow)
    }

    private fun buildFailureStatusText(error: Throwable): String {
        return SpecCodingBundle.message(
            "spec.workflow.error",
            renderFailureMessage(error),
        )
    }
}
