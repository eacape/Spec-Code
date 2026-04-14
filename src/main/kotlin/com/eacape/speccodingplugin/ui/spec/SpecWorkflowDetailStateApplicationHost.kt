package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.CodeContextCollectionStrategy
import com.eacape.speccodingplugin.spec.CodeContextPack
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset
import com.eacape.speccodingplugin.spec.WorkflowSourceUsage

internal interface SpecWorkflowDetailStateApplicationUi {
    fun updateAutoCodeContext(
        workflowId: String?,
        codeContextPack: CodeContextPack?,
    )

    fun updateWorkflowSources(
        workflowId: String?,
        assets: List<WorkflowSourceAsset>,
        selectedSourceIds: Set<String>,
        editable: Boolean,
    )
}

internal class SpecWorkflowDetailStateApplicationHost(
    private val detailUi: SpecWorkflowDetailStateApplicationUi,
    private val composerSourceCoordinator: SpecWorkflowComposerSourceCoordinator,
    private val renderFailureMessage: (Throwable) -> String,
) {
    private var currentWorkflowSources: List<WorkflowSourceAsset> = emptyList()
    private val composerSelectedSourceIdsByWorkflowId = mutableMapOf<String, LinkedHashSet<String>>()

    fun clearCurrentWorkflowSources() {
        currentWorkflowSources = emptyList()
    }

    fun currentWorkflowSources(): List<WorkflowSourceAsset> = currentWorkflowSources

    fun selectedSourceIds(workflowId: String): Set<String>? = composerSelectedSourceIdsByWorkflowId[workflowId]

    fun applyAutoCodeContext(
        workflow: SpecWorkflow,
        codeContextResult: Result<CodeContextPack>?,
    ) {
        val pack = codeContextResult?.getOrElse { error ->
            CodeContextPack(
                phase = workflow.currentPhase,
                strategy = CodeContextCollectionStrategy.forPhase(workflow.currentPhase),
                degradationReasons = listOf(
                    "Automatic code context collection failed: ${renderFailureMessage(error)}",
                ),
            )
        } ?: CodeContextPack(
            phase = workflow.currentPhase,
            strategy = CodeContextCollectionStrategy.forPhase(workflow.currentPhase),
            degradationReasons = listOf(
                "Automatic code context collection was skipped for this workflow refresh.",
            ),
        )
        detailUi.updateAutoCodeContext(
            workflowId = workflow.id,
            codeContextPack = pack,
        )
    }

    fun applyWorkflowSources(
        workflow: SpecWorkflow,
        assets: List<WorkflowSourceAsset>,
        preserveSelection: Boolean,
        preferredSourceIds: Set<String> = emptySet(),
    ) {
        val presentation = composerSourceCoordinator.buildPresentation(
            currentStage = workflow.currentStage,
            assets = assets,
            preserveSelection = preserveSelection,
            existingSelection = selectedSourceIds(workflow.id),
            preferredSourceIds = preferredSourceIds,
        )
        applyWorkflowSourcesPresentation(workflow.id, presentation)
    }

    fun applyWorkflowSourcesPresentation(
        workflowId: String?,
        presentation: SpecWorkflowComposerSourcePresentation,
    ) {
        if (workflowId == null) {
            currentWorkflowSources = emptyList()
            detailUi.updateWorkflowSources(
                workflowId = null,
                assets = emptyList(),
                selectedSourceIds = emptySet(),
                editable = false,
            )
            return
        }
        currentWorkflowSources = presentation.assets
        composerSelectedSourceIdsByWorkflowId[workflowId] = presentation.selectedSourceIds
        detailUi.updateWorkflowSources(
            workflowId = workflowId,
            assets = presentation.assets,
            selectedSourceIds = presentation.selectedSourceIds,
            editable = presentation.editable,
        )
    }

    fun resolveSourceUsage(workflowId: String): WorkflowSourceUsage {
        return composerSourceCoordinator.resolveSourceUsage(
            currentAssets = currentWorkflowSources,
            existingSelection = selectedSourceIds(workflowId),
        )
    }
}
