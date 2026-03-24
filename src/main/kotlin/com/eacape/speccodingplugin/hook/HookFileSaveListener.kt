package com.eacape.speccodingplugin.hook

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import java.nio.file.Paths

class HookFileSaveListener(
    private val hookManagerProvider: (Project) -> HookManager = { HookManager.getInstance(it) },
) : FileDocumentManagerListener {

    override fun beforeDocumentSaving(document: com.intellij.openapi.editor.Document) {
        val application = ApplicationManager.getApplication()
        if (application.isDisposed || application.isDisposeInProgress) {
            return
        }
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!file.isValid || file.isDirectory) {
            return
        }

        ProjectManager.getInstance().openProjects.forEach { project ->
            if (project.isDisposed) {
                return@forEach
            }
            val basePath = project.basePath ?: return@forEach
            val filePath = file.path
            val projectRelativePath = filePath.takeIf { isPathUnderBase(it, basePath) }
                ?.let { toRelativePath(it, basePath) }
                ?: return@forEach

            val triggerContext = HookTriggerContext(
                filePath = projectRelativePath,
                metadata = mapOf(
                    "absolutePath" to filePath,
                    "projectName" to project.name,
                ),
            )
            application.executeOnPooledThread {
                if (project.isDisposed) {
                    return@executeOnPooledThread
                }
                runCatching {
                    hookManagerProvider(project).trigger(
                        event = HookEvent.FILE_SAVED,
                        triggerContext = triggerContext,
                    )
                }
            }
        }
    }

    private fun isPathUnderBase(path: String, basePath: String): Boolean {
        return runCatching {
            val normalizedPath = Paths.get(path).normalize()
            val normalizedBase = Paths.get(basePath).normalize()
            normalizedPath.startsWith(normalizedBase)
        }.getOrDefault(false)
    }

    private fun toRelativePath(path: String, basePath: String): String? {
        return runCatching {
            val normalizedPath = Paths.get(path).normalize()
            val normalizedBase = Paths.get(basePath).normalize()
            normalizedBase.relativize(normalizedPath)
                .toString()
                .replace('\\', '/')
        }.getOrNull()
    }
}

class HookProjectManagerListener(
    private val hookManagerProvider: (Project) -> HookManager = { HookManager.getInstance(it) },
    private val gitCommitWatcherProvider: (Project) -> HookGitCommitWatcher = { HookGitCommitWatcher.getInstance(it) },
) : ProjectManagerListener {
    @Suppress("OVERRIDE_DEPRECATION")
    override fun projectOpened(project: Project) {
        hookManagerProvider(project).listHooks()
        gitCommitWatcherProvider(project).start()
    }
}
