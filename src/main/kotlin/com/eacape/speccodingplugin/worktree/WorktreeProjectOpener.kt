package com.eacape.speccodingplugin.worktree

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import java.nio.file.Files
import java.nio.file.Paths

interface WorktreeProjectOpener {
    fun openProject(worktreePath: String): Result<Unit>
}

class IdeWorktreeProjectOpener(
    private val project: Project,
) : WorktreeProjectOpener {
    override fun openProject(worktreePath: String): Result<Unit> {
        return runCatching {
            val normalizedPath = worktreePath.trim()
            require(normalizedPath.isNotBlank()) { "Worktree path cannot be blank" }

            val path = Paths.get(normalizedPath)
            require(Files.exists(path)) { "Worktree path does not exist: $normalizedPath" }
            require(Files.isDirectory(path)) { "Worktree path is not a directory: $normalizedPath" }
            require(!project.isDisposed) { "Project is already disposed" }

            val application = ApplicationManager.getApplication()
            var openedProject: Project? = null
            val openAction = {
                val task = OpenProjectTask.build().withForceOpenInNewFrame(true)
                openedProject = ProjectUtil.openOrImport(path, task)
            }

            if (application.isDispatchThread) {
                openAction()
            } else {
                application.invokeAndWait { openAction() }
            }

            openedProject ?: throw IllegalStateException("Failed to open worktree directory: $normalizedPath")
        }
    }
}
