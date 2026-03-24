package com.eacape.speccodingplugin.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

internal object IdeTerminalCommandExecutor {
    private const val tabTitlePrefix = "Spec Code: "
    private const val maxTabTitleLength = 48

    fun execute(project: Project, command: String, workingDirectory: String) {
        val application = ApplicationManager.getApplication()
        val action = {
            check(!project.isDisposed) { "Project is disposed" }

            val manager = TerminalToolWindowManager.getInstance(project)
            val widget = manager.createLocalShellWidget(workingDirectory, buildTabTitle(command))
                ?: error("Failed to create IDE terminal widget")

            manager.toolWindow.activate(null)
            widget.executeCommand(command)
        }

        if (application.isDispatchThread) {
            action()
        } else {
            application.invokeAndWait(action)
        }
    }

    private fun buildTabTitle(command: String): String {
        val normalized = command.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) {
            return "Spec Code"
        }

        val availableLength = (maxTabTitleLength - tabTitlePrefix.length).coerceAtLeast(8)
        val body = if (normalized.length <= availableLength) {
            normalized
        } else {
            normalized.take(availableLength - 3) + "..."
        }
        return tabTitlePrefix + body
    }
}
