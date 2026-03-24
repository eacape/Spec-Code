package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.ui.ChatToolWindowFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

class OpenSpecCodingToolWindowAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // ToolWindow lookup must use stable id from plugin.xml, not localized title.
        val toolWindowId = TOOL_WINDOW_ID
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(toolWindowId)
        toolWindow?.show {
            ChatToolWindowFactory.ensurePrimaryContents(project, toolWindow)
            toolWindow.activate(null)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
        e.presentation.text = SpecCodingBundle.message("action.open.toolwindow.text")
        e.presentation.description = SpecCodingBundle.message("action.open.toolwindow.description")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        private const val TOOL_WINDOW_ID = ChatToolWindowFactory.TOOL_WINDOW_ID
    }
}
