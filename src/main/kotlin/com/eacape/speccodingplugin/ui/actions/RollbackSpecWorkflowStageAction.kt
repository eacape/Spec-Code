package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowMeta
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowSelectionService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class RollbackSpecWorkflowStageAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val specEngine = SpecEngine.getInstance(project)
        val selectedWorkflowId = SpecWorkflowSelectionService.getInstance(project).currentWorkflowId()

        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.rollback.loading"),
            task = { specEngine.listWorkflowMetadata().sortedByDescending { it.updatedAt } },
            onSuccess = { workflows ->
                if (workflows.isEmpty()) {
                    SpecWorkflowActionSupport.showInfo(
                        project,
                        SpecCodingBundle.message("spec.action.rollback.empty.title"),
                        SpecCodingBundle.message("spec.action.rollback.empty.message"),
                    )
                    return@runBackground
                }
                val selectedWorkflow = selectedWorkflowId?.let { workflowId ->
                    workflows.firstOrNull { it.workflowId == workflowId }
                } ?: workflows.singleOrNull()
                if (selectedWorkflow != null) {
                    chooseTargetStage(project, specEngine, selectedWorkflow)
                } else {
                    SpecWorkflowActionSupport.chooseWorkflow(
                        project = project,
                        workflows = workflows,
                        title = SpecCodingBundle.message("spec.action.rollback.workflow.popup.title"),
                        onChosen = { workflow -> chooseTargetStage(project, specEngine, workflow) },
                    )
                }
            },
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = false
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun chooseTargetStage(
        project: com.intellij.openapi.project.Project,
        specEngine: SpecEngine,
        workflowMeta: WorkflowMeta,
    ) {
        val targets = SpecWorkflowActionSupport.rollbackTargets(workflowMeta)
        if (targets.isEmpty()) {
            SpecWorkflowActionSupport.showInfo(
                project,
                SpecCodingBundle.message("spec.action.rollback.none.title"),
                SpecCodingBundle.message("spec.action.rollback.none.message"),
            )
            return
        }
        SpecWorkflowActionSupport.chooseStage(
            project = project,
            stages = targets,
            title = SpecCodingBundle.message("spec.action.rollback.stage.popup.title"),
            workflowMeta = workflowMeta,
            onChosen = { targetStage -> executeRollback(project, specEngine, workflowMeta.workflowId, targetStage) },
        )
    }

    private fun executeRollback(
        project: com.intellij.openapi.project.Project,
        specEngine: SpecEngine,
        workflowId: String,
        targetStage: StageId,
    ) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.rollback.executing"),
            task = { specEngine.rollbackToStage(workflowId, targetStage).getOrThrow() },
            onSuccess = { meta ->
                SpecWorkflowActionSupport.notifySuccess(
                    project,
                    SpecCodingBundle.message(
                        "spec.action.rollback.success",
                        SpecWorkflowActionSupport.stageLabel(meta.currentStage),
                    ),
                )
                SpecWorkflowActionSupport.showWorkflow(project, workflowId)
            },
        )
    }
}
