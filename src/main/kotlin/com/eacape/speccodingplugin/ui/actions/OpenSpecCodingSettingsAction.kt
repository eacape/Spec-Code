package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.ui.settings.SettingsPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener

/**
 * 打开 Spec Code 设置的 Action
 * 点击齿轮图标后动态添加 Settings 内容面板并切换到它，
 * 用户切换到其他 Tab 时自动移除 Settings 面板，保持顶部导航栏整洁。
 */
class OpenSpecCodingSettingsAction : AnAction() {

    companion object {
        private val SETTINGS_CONTENT_KEY = Key.create<Boolean>("SpecCoding.SettingsContent")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Spec Code") ?: return
        toolWindow.show {
            val contentManager = toolWindow.contentManager
            val settingsTitle = SpecCodingBundle.message("settings.tab.title")

            // 检查是否已存在 settings content
            val existing = contentManager.contents.firstOrNull {
                it.getUserData(SETTINGS_CONTENT_KEY) == true || it.displayName == settingsTitle
            }

            if (existing != null) {
                contentManager.setSelectedContent(existing)
                return@show
            }

            // 动态创建 Settings content
            val settingsPanel = SettingsPanel(project)
            val settingsContent = ContentFactory.getInstance().createContent(
                settingsPanel, settingsTitle, false
            )
            settingsContent.putUserData(SETTINGS_CONTENT_KEY, true)
            Disposer.register(settingsContent, settingsPanel)

            contentManager.addContent(settingsContent)
            contentManager.setSelectedContent(settingsContent)

            // 用户切换到其他 Tab 时自动移除 Settings content
            contentManager.addContentManagerListener(object : ContentManagerListener {
                override fun selectionChanged(event: ContentManagerEvent) {
                    if (contentManager.selectedContent != settingsContent && contentManager.contents.contains(settingsContent)) {
                        contentManager.removeContentManagerListener(this)
                        contentManager.removeContent(settingsContent, true)
                    }
                }
            })
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.text = SpecCodingBundle.message("toolbar.openSettings")
        e.presentation.icon = AllIcons.General.GearPlain
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
