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

class PullTeamSkillsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(
                project,
                SpecCodingBundle.message("team.skillSync.task.pull"),
                false,
            ) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = SpecCodingBundle.message("team.skillSync.task.pull")
                    val result = TeamSkillSyncService.getInstance(project).pullFromTeamRepo()
                    result
                        .onSuccess { payload ->
                            notify(
                                project = project,
                                content = SpecCodingBundle.message(
                                    "team.skillSync.pull.success",
                                    payload.syncedFiles,
                                    payload.branch,
                                ),
                                type = NotificationType.INFORMATION,
                            )
                        }
                        .onFailure { error ->
                            notify(
                                project = project,
                                content = SpecCodingBundle.message(
                                    "team.skillSync.pull.failed",
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
        e.presentation.text = SpecCodingBundle.message("action.team.skills.pull.text")
        e.presentation.description = SpecCodingBundle.message("action.team.skills.pull.description")
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
