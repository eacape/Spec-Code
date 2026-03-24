package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecArtifactService
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.SpecVerificationQuickFixService
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.ui.editor.SpecWorkflowEditorContextResolver
import com.eacape.speccodingplugin.ui.spec.SpecVerificationScopeSelectDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import java.nio.file.Files

class GenerateVerificationSkeletonAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val context = SpecWorkflowEditorContextResolver.resolve(e.getData(CommonDataKeys.VIRTUAL_FILE)) ?: return

        val workflowId = context.workflowId
        val artifactService = SpecArtifactService(project)
        val verificationPath = artifactService.locateArtifact(workflowId, StageId.VERIFY)
        if (Files.exists(verificationPath)) {
            return
        }

        val tasksService = SpecTasksService(project)
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.editor.fixVerification.prepare"),
            task = {
                runCatching { tasksService.parse(workflowId).sortedBy(StructuredTask::id) }.getOrDefault(emptyList())
            },
            onSuccess = { tasks ->
                val dialog = SpecVerificationScopeSelectDialog(tasks)
                if (!dialog.showAndGet()) {
                    return@runBackground
                }

                val quickFixService = SpecVerificationQuickFixService(project)
                SpecWorkflowActionSupport.runBackground(
                    project = project,
                    title = SpecCodingBundle.message("spec.action.editor.fixVerification.progress"),
                    task = {
                        quickFixService.scaffoldVerificationArtifact(
                            workflowId = workflowId,
                            scopeTaskIds = dialog.selectedTaskIds,
                            trigger = SpecVerificationQuickFixService.TRIGGER_EDITOR_POPUP,
                        )
                    },
                    onSuccess = { result ->
                        SpecWorkflowActionSupport.rememberWorkflow(project, result.workflowId)
                        if (result.created) {
                            val fileName = result.verificationDocumentPath.fileName.toString()
                            SpecWorkflowActionSupport.notifySuccess(
                                project = project,
                                message = SpecCodingBundle.message(
                                    "spec.action.editor.fixVerification.success.message",
                                    fileName,
                                    result.scopeTaskIds.size,
                                ),
                            )
                        }
                        SpecWorkflowActionSupport.openFile(project, result.verificationDocumentPath)
                    },
                )
            },
        )
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val context = SpecWorkflowEditorContextResolver.resolve(e.getData(CommonDataKeys.VIRTUAL_FILE))
        if (project == null || context == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val artifactService = SpecArtifactService(project)
        val verificationPath = runCatching { artifactService.locateArtifact(context.workflowId, StageId.VERIFY) }.getOrNull()
        e.presentation.isEnabledAndVisible = verificationPath != null && !Files.exists(verificationPath)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

