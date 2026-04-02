package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.CodeContextPack
import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset

internal data class SpecWorkflowUiSnapshot(
    val overviewState: SpecWorkflowOverviewState,
    val verifyDeltaState: SpecWorkflowVerifyDeltaState,
    val gateResult: GateResult?,
    val refreshedAtMillis: Long,
)

internal data class SpecWorkflowPanelLoadRequest(
    val workflowId: String,
    val includeSources: Boolean,
)

internal data class SpecWorkflowPanelLoadedState(
    val workflow: SpecWorkflow?,
    val uiSnapshot: SpecWorkflowUiSnapshot?,
    val tasksResult: Result<List<StructuredTask>>,
    val codeContextResult: Result<CodeContextPack>?,
    val sourcesResult: Result<List<WorkflowSourceAsset>>?,
    val liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
)

internal class SpecWorkflowPanelLoadCoordinator(
    private val reloadWorkflow: (String) -> Result<SpecWorkflow>,
    private val parseTasks: (String) -> List<StructuredTask>,
    private val buildCodeContext: (SpecWorkflow) -> CodeContextPack,
    private val listWorkflowSources: (String) -> Result<List<WorkflowSourceAsset>>,
    private val buildUiSnapshot: (SpecWorkflow) -> SpecWorkflowUiSnapshot,
    private val buildTaskLiveProgressByTaskId: (String) -> Map<String, TaskExecutionLiveProgress>,
) {

    fun load(request: SpecWorkflowPanelLoadRequest): SpecWorkflowPanelLoadedState {
        val workflow = reloadWorkflow(request.workflowId).getOrNull()

        return SpecWorkflowPanelLoadedState(
            workflow = workflow,
            uiSnapshot = workflow?.let(buildUiSnapshot),
            tasksResult = runCatching { parseTasks(request.workflowId) },
            codeContextResult = workflow?.let { loadedWorkflow ->
                runCatching { buildCodeContext(loadedWorkflow) }
            },
            sourcesResult = workflow
                ?.takeIf { request.includeSources }
                ?.let { loadedWorkflow -> listWorkflowSources(loadedWorkflow.id) },
            liveProgressByTaskId = workflow
                ?.let { loadedWorkflow -> buildTaskLiveProgressByTaskId(loadedWorkflow.id) }
                .orEmpty(),
        )
    }
}
