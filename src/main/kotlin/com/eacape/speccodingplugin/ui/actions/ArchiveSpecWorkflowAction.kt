package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowSelectionService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class ArchiveSpecWorkflowAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val specEngine = SpecEngine.getInstance(project)
        val selectedWorkflowId = SpecWorkflowSelectionService.getInstance(project).currentWorkflowId()

        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.archive.loading"),
            task = {
                specEngine.listWorkflowMetadata()
                    .filter { workflow -> workflow.status == WorkflowStatus.COMPLETED }
                    .sortedByDescending { workflow -> workflow.updatedAt }
            },
            onSuccess = { workflows ->
                if (workflows.isEmpty()) {
                    SpecWorkflowActionSupport.showInfo(
                        project = project,
                        title = SpecCodingBundle.message("spec.action.archive.empty.title"),
                        message = SpecCodingBundle.message("spec.action.archive.empty.message"),
                    )
                    return@runBackground
                }
                val selectedWorkflow = selectedWorkflowId
                    ?.let { workflowId -> workflows.firstOrNull { workflow -> workflow.workflowId == workflowId } }
                    ?: workflows.singleOrNull()
                if (selectedWorkflow != null) {
                    confirmAndArchive(project, specEngine, selectedWorkflow.workflowId)
                } else {
                    SpecWorkflowActionSupport.chooseWorkflow(
                        project = project,
                        workflows = workflows,
                        title = SpecCodingBundle.message("spec.action.archive.workflow.popup.title"),
                        onChosen = { workflow -> confirmAndArchive(project, specEngine, workflow.workflowId) },
                    )
                }
            },
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun confirmAndArchive(
        project: com.intellij.openapi.project.Project,
        specEngine: SpecEngine,
        workflowId: String,
    ) {
        if (!SpecWorkflowActionSupport.confirmArchive(project, workflowId)) {
            return
        }
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.archive.executing"),
            task = { specEngine.archiveWorkflow(workflowId).getOrThrow() },
            onSuccess = { result ->
                SpecWorkflowActionSupport.notifySuccess(
                    project = project,
                    message = SpecCodingBundle.message("spec.action.archive.success", result.workflowId),
                )
            },
        )
    }
}
