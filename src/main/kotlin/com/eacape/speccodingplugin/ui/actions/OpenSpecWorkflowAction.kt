package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecEngine
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class OpenSpecWorkflowAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.open.loading"),
            task = { SpecEngine.getInstance(project).listWorkflowMetadata().sortedByDescending { it.updatedAt } },
            onSuccess = { workflows ->
                if (workflows.isEmpty()) {
                    SpecWorkflowActionSupport.showInfo(
                        project = project,
                        title = SpecCodingBundle.message("spec.action.open.empty.title"),
                        message = SpecCodingBundle.message("spec.action.open.empty.message"),
                    )
                    return@runBackground
                }
                SpecWorkflowActionSupport.chooseWorkflow(
                    project = project,
                    workflows = workflows,
                    title = SpecCodingBundle.message("spec.action.open.popup.title"),
                    onChosen = { workflow -> SpecWorkflowActionSupport.showWorkflow(project, workflow.workflowId) },
                )
            },
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
