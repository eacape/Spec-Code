package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowMeta
import com.eacape.speccodingplugin.ui.actions.SpecWorkflowActionSupport

internal data class SpecWorkflowWorkbenchStageSelectionRequest(
    val workflowMeta: WorkflowMeta,
    val stages: List<StageId>,
    val title: String,
    val onChosen: (StageId) -> Unit,
)

internal class SpecWorkflowWorkbenchStageSelectionCoordinator(
    private val workflowMetaProvider: () -> WorkflowMeta?,
    private val jumpTargets: (WorkflowMeta) -> List<StageId> = SpecWorkflowActionSupport::jumpTargets,
    private val rollbackTargets: (WorkflowMeta) -> List<StageId> = SpecWorkflowActionSupport::rollbackTargets,
    private val showInfo: (title: String, message: String) -> Unit,
    private val chooseStage: (SpecWorkflowWorkbenchStageSelectionRequest) -> Unit,
    private val onJumpSelected: (workflowId: String, targetStage: StageId) -> Unit,
    private val onRollbackSelected: (workflowId: String, targetStage: StageId) -> Unit,
) {

    fun requestJumpSelection() {
        requestSelection(
            targetsProvider = jumpTargets,
            emptyTitle = SpecCodingBundle.message("spec.action.jump.none.title"),
            emptyMessage = SpecCodingBundle.message("spec.action.jump.none.message"),
            chooserTitle = SpecCodingBundle.message("spec.action.jump.stage.popup.title"),
            onChosen = onJumpSelected,
        )
    }

    fun requestRollbackSelection() {
        requestSelection(
            targetsProvider = rollbackTargets,
            emptyTitle = SpecCodingBundle.message("spec.action.rollback.none.title"),
            emptyMessage = SpecCodingBundle.message("spec.action.rollback.none.message"),
            chooserTitle = SpecCodingBundle.message("spec.action.rollback.stage.popup.title"),
            onChosen = onRollbackSelected,
        )
    }

    private fun requestSelection(
        targetsProvider: (WorkflowMeta) -> List<StageId>,
        emptyTitle: String,
        emptyMessage: String,
        chooserTitle: String,
        onChosen: (workflowId: String, targetStage: StageId) -> Unit,
    ) {
        val workflowMeta = workflowMetaProvider() ?: return
        val targets = targetsProvider(workflowMeta)
        if (targets.isEmpty()) {
            showInfo(emptyTitle, emptyMessage)
            return
        }
        chooseStage(
            SpecWorkflowWorkbenchStageSelectionRequest(
                workflowMeta = workflowMeta,
                stages = targets,
                title = chooserTitle,
                onChosen = { targetStage ->
                    onChosen(workflowMeta.workflowId, targetStage)
                },
            ),
        )
    }
}
