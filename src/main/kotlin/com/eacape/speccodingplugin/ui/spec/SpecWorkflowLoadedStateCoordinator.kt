package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.CodeContextPack
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset
import com.intellij.openapi.diagnostic.thisLogger

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
    private val logger = thisLogger()

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
        applyUiCallback(
            workflowId = workflow.id,
            operation = "apply workflow core state",
            callbacks = callbacks,
        ) {
            callbacks.applyWorkflowCore(
                SpecWorkflowLoadedCoreUiState(
                    workflow = workflow,
                    snapshot = snapshot,
                    followCurrentPhase = request.followCurrentPhase,
                    codeContextResult = request.loadedState.codeContextResult,
                ),
            )
        }

        request.loadedState.sourcesResult
            ?.onSuccess { sources ->
                applyUiCallback(
                    workflowId = workflow.id,
                    operation = "apply workflow sources",
                    callbacks = callbacks,
                ) {
                    callbacks.applyWorkflowSources(
                        workflow = workflow,
                        assets = sources,
                        preserveSelection = request.previousSelectedWorkflowId == workflow.id,
                    )
                }
            }
            ?.onFailure { error ->
                logger.warn("Failed to load workflow sources for workflow ${workflow.id}", error)
                applyUiCallback(
                    workflowId = workflow.id,
                    operation = "clear workflow sources after load failure",
                    callbacks = callbacks,
                ) {
                    callbacks.applyWorkflowSources(
                        workflow = workflow,
                        assets = emptyList(),
                        preserveSelection = false,
                    )
                }
                reportFailure(callbacks, error)
            }

        val refreshedAtMillis = currentTimeMillis()
        request.loadedState.tasksResult
            .onSuccess { tasks ->
                applyUiCallback(
                    workflowId = workflow.id,
                    operation = "apply workflow tasks",
                    callbacks = callbacks,
                ) {
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
            }
            .onFailure { error ->
                logger.warn("Failed to load workflow tasks for workflow ${workflow.id}", error)
                applyUiCallback(
                    workflowId = workflow.id,
                    operation = "clear workflow tasks after load failure",
                    callbacks = callbacks,
                ) {
                    callbacks.applyWorkflowTasks(
                        SpecWorkflowLoadedTaskUiState(
                            workflow = workflow,
                            snapshot = snapshot,
                            tasks = emptyList(),
                            liveProgressByTaskId = emptyMap(),
                            refreshedAtMillis = refreshedAtMillis,
                        ),
                    )
                }
                reportFailure(callbacks, error)
            }

        if (request.previousSelectedWorkflowId != null && request.previousSelectedWorkflowId != workflow.id) {
            applyUiCallback(
                workflowId = workflow.id,
                operation = "restore pending clarification state",
                callbacks = callbacks,
            ) {
                callbacks.restorePendingClarificationState(workflow.id)
            }
        }
        applyUiCallback(
            workflowId = workflow.id,
            operation = "apply pending open workflow request",
            callbacks = callbacks,
        ) {
            callbacks.applyPendingOpenWorkflowRequest(workflow.id)
        }
        applyUiCallback(
            workflowId = workflow.id,
            operation = "update workflow action availability",
            callbacks = callbacks,
        ) {
            callbacks.updateWorkflowActionAvailability(workflow)
        }
        onUpdated?.let { callback ->
            applyUiCallback(
                workflowId = workflow.id,
                operation = "invoke workflow updated callback",
                callbacks = callbacks,
            ) {
                callback(workflow)
            }
        }
    }

    private fun buildFailureStatusText(error: Throwable): String {
        return SpecCodingBundle.message(
            "spec.workflow.error",
            renderFailureMessage(error),
        )
    }

    private fun applyUiCallback(
        workflowId: String,
        operation: String,
        callbacks: SpecWorkflowLoadedStateCallbacks,
        action: () -> Unit,
    ) {
        runCatching(action).onFailure { error ->
            logger.warn("Failed to $operation for workflow $workflowId", error)
            reportFailure(callbacks, error)
        }
    }

    private fun reportFailure(
        callbacks: SpecWorkflowLoadedStateCallbacks,
        error: Throwable,
    ) {
        runCatching {
            callbacks.setStatusText(buildFailureStatusText(error))
        }.onFailure { statusError ->
            logger.warn("Failed to publish workflow failure status", statusError)
        }
    }
}
