package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecTasksQuickFixService
import com.eacape.speccodingplugin.ui.editor.SpecWorkflowEditorContextResolver
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction

class RepairSpecTasksSyntaxAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val context = SpecWorkflowEditorContextResolver.resolve(e.getData(CommonDataKeys.VIRTUAL_FILE)) ?: return
        if (!context.fileName.equals("tasks.md", ignoreCase = true)) {
            return
        }

        val quickFixService = SpecTasksQuickFixService(project)
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.editor.fixTasks.progress"),
            task = {
                quickFixService.repairTasksArtifact(
                    workflowId = context.workflowId,
                    trigger = SpecTasksQuickFixService.TRIGGER_EDITOR_POPUP,
                )
            },
            onSuccess = { result ->
                SpecWorkflowActionSupport.rememberWorkflow(project, result.workflowId)
                if (!result.changed) {
                    SpecWorkflowActionSupport.showInfo(
                        project = project,
                        title = SpecCodingBundle.message("spec.action.editor.fixTasks.none.title"),
                        message = SpecCodingBundle.message("spec.action.editor.fixTasks.none.message"),
                    )
                    return@runBackground
                }

                if (result.issuesAfter.isNotEmpty()) {
                    val firstIssue = result.issuesAfter.first()
                    SpecWorkflowActionSupport.showInfo(
                        project = project,
                        title = SpecCodingBundle.message("spec.action.editor.fixTasks.partial.title"),
                        message = SpecCodingBundle.message(
                            "spec.action.editor.fixTasks.partial.message",
                            result.issuesAfter.size,
                            firstIssue.line,
                            firstIssue.message,
                        ),
                    )
                    SpecWorkflowActionSupport.openFile(project, result.tasksDocumentPath, firstIssue.line)
                    return@runBackground
                }

                SpecWorkflowActionSupport.notifySuccess(
                    project = project,
                    message = SpecCodingBundle.message(
                        "spec.action.editor.fixTasks.success.message",
                        result.issuesBefore.size,
                    ),
                )
                SpecWorkflowActionSupport.openFile(project, result.tasksDocumentPath)
            },
        )
    }

    override fun update(e: AnActionEvent) {
        val context = SpecWorkflowEditorContextResolver.resolve(e.getData(CommonDataKeys.VIRTUAL_FILE))
        e.presentation.isEnabledAndVisible = e.project != null && context?.fileName?.equals("tasks.md", ignoreCase = true) == true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

