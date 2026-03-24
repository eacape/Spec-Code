package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.skill.TeamSkillSyncService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task

class PushTeamSkillsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(
                project,
                SpecCodingBundle.message("team.skillSync.task.push"),
                false,
            ) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = SpecCodingBundle.message("team.skillSync.task.push")
                    val result = TeamSkillSyncService.getInstance(project).pushToTeamRepo()
                    result
                        .onSuccess { payload ->
                            if (payload.noChanges) {
                                notify(
                                    project = project,
                                    content = SpecCodingBundle.message("team.skillSync.push.noChanges", payload.branch),
                                    type = NotificationType.INFORMATION,
                                )
                            } else {
                                notify(
                                    project = project,
                                    content = SpecCodingBundle.message(
                                        "team.skillSync.push.success",
                                        payload.syncedFiles,
                                        payload.branch,
                                        payload.commitId ?: SpecCodingBundle.message("common.unknown"),
                                    ),
                                    type = NotificationType.INFORMATION,
                                )
                            }
                        }
                        .onFailure { error ->
                            notify(
                                project = project,
                                content = SpecCodingBundle.message(
                                    "team.skillSync.push.failed",
                                    error.message ?: SpecCodingBundle.message("team.skillSync.error.generic"),
                                ),
                                type = NotificationType.ERROR,
                            )
                        }
                }
            },
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
        e.presentation.text = SpecCodingBundle.message("action.team.skills.push.text")
        e.presentation.description = SpecCodingBundle.message("action.team.skills.push.description")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun notify(
        project: com.intellij.openapi.project.Project,
        content: String,
        type: NotificationType,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SpecCoding.Notifications")
            .createNotification(content, type)
            .notify(project)
    }
}
