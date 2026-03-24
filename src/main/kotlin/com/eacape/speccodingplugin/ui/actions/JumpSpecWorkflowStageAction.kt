package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageTransitionType
import com.eacape.speccodingplugin.spec.WorkflowMeta
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowSelectionService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class JumpSpecWorkflowStageAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val specEngine = SpecEngine.getInstance(project)
        val selectedWorkflowId = SpecWorkflowSelectionService.getInstance(project).currentWorkflowId()

        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.jump.loading"),
            task = { specEngine.listWorkflowMetadata().sortedByDescending { it.updatedAt } },
            onSuccess = { workflows ->
                if (workflows.isEmpty()) {
                    SpecWorkflowActionSupport.showInfo(
                        project,
                        SpecCodingBundle.message("spec.action.jump.empty.title"),
                        SpecCodingBundle.message("spec.action.jump.empty.message"),
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
                        title = SpecCodingBundle.message("spec.action.jump.workflow.popup.title"),
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
        val targets = SpecWorkflowActionSupport.jumpTargets(workflowMeta)
        if (targets.isEmpty()) {
            SpecWorkflowActionSupport.showInfo(
                project,
                SpecCodingBundle.message("spec.action.jump.none.title"),
                SpecCodingBundle.message("spec.action.jump.none.message"),
            )
            return
        }
        SpecWorkflowActionSupport.chooseStage(
            project = project,
            stages = targets,
            title = SpecCodingBundle.message("spec.action.jump.stage.popup.title"),
            workflowMeta = workflowMeta,
            onChosen = { targetStage -> previewAndJump(project, specEngine, workflowMeta.workflowId, targetStage) },
        )
    }

    private fun previewAndJump(
        project: com.intellij.openapi.project.Project,
        specEngine: SpecEngine,
        workflowId: String,
        targetStage: StageId,
    ) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.jump.preview"),
            task = {
                specEngine.previewStageTransition(
                    workflowId = workflowId,
                    transitionType = StageTransitionType.JUMP,
                    targetStage = targetStage,
                ).getOrThrow()
            },
            onSuccess = { preview ->
                when (preview.gateResult.status) {
                    GateStatus.ERROR -> {
                        SpecWorkflowActionSupport.showGateBlocked(project, workflowId, preview.gateResult)
                        SpecWorkflowActionSupport.showWorkflow(project, workflowId)
                    }

                    GateStatus.WARNING -> {
                        if (!SpecWorkflowActionSupport.confirmWarnings(project, workflowId, preview.gateResult)) {
                            return@runBackground
                        }
                        executeJump(project, specEngine, workflowId, targetStage)
                    }

                    GateStatus.PASS -> executeJump(project, specEngine, workflowId, targetStage)
                }
            },
        )
    }

    private fun executeJump(
        project: com.intellij.openapi.project.Project,
        specEngine: SpecEngine,
        workflowId: String,
        targetStage: StageId,
    ) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.jump.executing"),
            task = { specEngine.jumpToStage(workflowId, targetStage) { true }.getOrThrow() },
            onSuccess = { result ->
                SpecWorkflowActionSupport.notifySuccess(
                    project,
                    SpecCodingBundle.message(
                        "spec.action.jump.success",
                        SpecWorkflowActionSupport.stageLabel(result.targetStage),
                    ),
                )
                SpecWorkflowActionSupport.showWorkflow(project, workflowId)
            },
        )
    }
}
