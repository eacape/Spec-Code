package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.StageTransitionType
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowSelectionService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class AdvanceSpecWorkflowStageAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val specEngine = SpecEngine.getInstance(project)
        val selectedWorkflowId = SpecWorkflowSelectionService.getInstance(project).currentWorkflowId()

        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.advance.loading"),
            task = { specEngine.listWorkflowMetadata().sortedByDescending { it.updatedAt } },
            onSuccess = { workflows ->
                if (workflows.isEmpty()) {
                    SpecWorkflowActionSupport.showInfo(
                        project,
                        SpecCodingBundle.message("spec.action.advance.empty.title"),
                        SpecCodingBundle.message("spec.action.advance.empty.message"),
                    )
                    return@runBackground
                }
                val selectedWorkflow = selectedWorkflowId?.let { workflowId ->
                    workflows.firstOrNull { it.workflowId == workflowId }
                } ?: workflows.singleOrNull()
                if (selectedWorkflow != null) {
                    previewAndAdvance(project, specEngine, selectedWorkflow.workflowId)
                } else {
                    SpecWorkflowActionSupport.chooseWorkflow(
                        project = project,
                        workflows = workflows,
                        title = SpecCodingBundle.message("spec.action.advance.workflow.popup.title"),
                        onChosen = { workflow -> previewAndAdvance(project, specEngine, workflow.workflowId) },
                    )
                }
            },
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun previewAndAdvance(
        project: com.intellij.openapi.project.Project,
        specEngine: SpecEngine,
        workflowId: String,
    ) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.advance.preview"),
            task = {
                specEngine.previewStageTransition(
                    workflowId = workflowId,
                    transitionType = StageTransitionType.ADVANCE,
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
                        executeAdvance(project, specEngine, workflowId)
                    }

                    GateStatus.PASS -> executeAdvance(project, specEngine, workflowId)
                }
            },
        )
    }

    private fun executeAdvance(
        project: com.intellij.openapi.project.Project,
        specEngine: SpecEngine,
        workflowId: String,
    ) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.advance.executing"),
            task = { specEngine.advanceWorkflow(workflowId) { true }.getOrThrow() },
            onSuccess = { result ->
                SpecWorkflowActionSupport.notifySuccess(
                    project,
                    SpecCodingBundle.message(
                        "spec.action.advance.success",
                        SpecWorkflowActionSupport.stageLabel(result.targetStage),
                    ),
                )
                SpecWorkflowActionSupport.showWorkflow(project, workflowId)
            },
        )
    }
}
