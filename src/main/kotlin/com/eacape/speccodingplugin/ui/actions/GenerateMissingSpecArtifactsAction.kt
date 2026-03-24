package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecArtifactQuickFixService
import com.eacape.speccodingplugin.ui.editor.SpecWorkflowEditorContextResolver
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction

class GenerateMissingSpecArtifactsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val context = SpecWorkflowEditorContextResolver.resolve(e.getData(CommonDataKeys.VIRTUAL_FILE)) ?: return
        val quickFixService = SpecArtifactQuickFixService(project)

        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.editor.fixArtifacts.progress"),
            task = {
                quickFixService.scaffoldMissingArtifacts(
                    workflowId = context.workflowId,
                    trigger = SpecArtifactQuickFixService.TRIGGER_EDITOR_POPUP,
                )
            },
            onSuccess = { result ->
                SpecWorkflowActionSupport.rememberWorkflow(project, result.workflowId)
                if (result.createdArtifacts.isEmpty()) {
                    SpecWorkflowActionSupport.showInfo(
                        project = project,
                        title = SpecCodingBundle.message("spec.action.editor.fixArtifacts.none.title"),
                        message = SpecCodingBundle.message("spec.action.editor.fixArtifacts.none.message"),
                    )
                    return@runBackground
                }

                val createdFiles = result.createdArtifacts.joinToString(", ") { it.fileName.toString() }
                SpecWorkflowActionSupport.notifySuccess(
                    project = project,
                    message = SpecCodingBundle.message(
                        "spec.action.editor.fixArtifacts.success.message",
                        result.createdArtifacts.size,
                        createdFiles,
                    ),
                )
                result.createdArtifacts.firstOrNull()?.let { createdPath ->
                    SpecWorkflowActionSupport.openFile(project, createdPath)
                }
            },
        )
    }

    override fun update(e: AnActionEvent) {
        val context = SpecWorkflowEditorContextResolver.resolve(e.getData(CommonDataKeys.VIRTUAL_FILE))
        e.presentation.isEnabledAndVisible = e.project != null && context != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
