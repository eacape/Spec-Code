package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset
import java.nio.file.Path

internal class SpecWorkflowComposerSourceUiHost(
    private val currentWorkflow: () -> SpecWorkflow?,
    private val currentWorkflowSources: () -> List<WorkflowSourceAsset>,
    private val selectedSourceIds: (String) -> Set<String>?,
    private val chooseSourcePaths: () -> List<Path>,
    private val applyWorkflowSourcesPresentation: (String, SpecWorkflowComposerSourcePresentation) -> Unit,
    private val isWorkflowStillSelected: (String) -> Boolean,
    private val showValidationDialogUi: (SpecWorkflowComposerSourceValidationDialog) -> Unit,
    private val setStatusText: (String) -> Unit,
    private val composerSourceCoordinator: SpecWorkflowComposerSourceCoordinator,
) {

    fun requestAdd() {
        val workflow = currentWorkflow() ?: return
        if (!composerSourceCoordinator.isEditable(workflow.currentStage)) {
            return
        }
        composerSourceCoordinator.importSources(
            request = SpecWorkflowComposerSourceImportRequest(
                workflowId = workflow.id,
                currentStage = workflow.currentStage,
                selectedPaths = chooseSourcePaths(),
                currentAssets = currentWorkflowSources(),
                existingSelection = selectedSourceIds(workflow.id),
            ),
            isWorkflowStillSelected = isWorkflowStillSelected,
            onRejected = { rejected ->
                showValidationDialogUi(rejected.validationDialog)
                setStatusText(rejected.statusText)
            },
            onImported = { imported ->
                applyWorkflowSourcesPresentation(workflow.id, imported.presentation)
                imported.validationDialog?.let(showValidationDialogUi)
                setStatusText(imported.statusText)
            },
            onFailure = setStatusText,
        )
    }

    fun requestRemove(sourceId: String) {
        val workflow = currentWorkflow() ?: return
        val presentation = composerSourceCoordinator.removeSource(
            currentStage = workflow.currentStage,
            sourceId = sourceId,
            currentAssets = currentWorkflowSources(),
            existingSelection = selectedSourceIds(workflow.id),
        ) ?: return
        applyWorkflowSourcesPresentation(workflow.id, presentation)
        setStatusText(
            SpecCodingBundle.message(
                "spec.detail.sources.status.removed",
                sourceId,
            ),
        )
    }

    fun requestRestore() {
        val workflow = currentWorkflow() ?: return
        val assets = currentWorkflowSources()
        val presentation = composerSourceCoordinator.buildPresentation(
            currentStage = workflow.currentStage,
            assets = assets,
            preserveSelection = false,
            existingSelection = null,
        )
        applyWorkflowSourcesPresentation(workflow.id, presentation)
        setStatusText(
            SpecCodingBundle.message(
                "spec.detail.sources.status.restored",
                assets.size,
            ),
        )
    }
}
