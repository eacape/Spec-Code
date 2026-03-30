package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class NewFullSpecWorkflowAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SpecWorkflowActionSupport.showCreateWorkflow(
            project = project,
            preferredTemplate = WorkflowTemplate.FULL_SPEC,
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
