package com.eacape.speccodingplugin.rollback

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * 回滚管理器（Project-level Service）
 * 负责执行变更集的回滚操作
 */
@Service(Service.Level.PROJECT)
class RollbackManager(private val project: Project) {
    private val logger = thisLogger()
    private val store = ChangesetStore.getInstance(project)

    /**
     * 回滚指定的变更集
     */
    fun rollback(changesetId: String, options: RollbackOptions = RollbackOptions()): RollbackResult {
        val changeset = store.getById(changesetId)
            ?: return RollbackResult.Failure("Changeset not found: $changesetId")

        logger.info("Rolling back changeset: $changesetId - ${changeset.description}")

        return rollbackChangeset(changeset, options)
    }

    /**
     * 回滚最近的变更集
     */
    fun rollbackLast(options: RollbackOptions = RollbackOptions()): RollbackResult {
        val recent = store.getRecent(1)
        if (recent.isEmpty()) {
            return RollbackResult.Failure("No changesets to rollback")
        }

        return rollbackChangeset(recent.first(), options)
    }

    /**
     * 回滚变更集
     */
    private fun rollbackChangeset(changeset: Changeset, options: RollbackOptions): RollbackResult {
        val changesToRollback = if (options.selectedFiles != null) {
            changeset.changes.filter { it.filePath in options.selectedFiles }
        } else {
            changeset.changes
        }

        if (changesToRollback.isEmpty()) {
            return RollbackResult.Failure("No changes to rollback")
        }

        val rolledBack = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val errors = mutableMapOf<String, String>()

        // 创建备份（如果需要）
        if (options.createBackup) {
            createBackup(changeset)
        }

        // 执行回滚
        for (change in changesToRollback) {
            try {
                rollbackFileChange(change)
                rolledBack.add(change.filePath)
                logger.debug("Rolled back: ${change.filePath}")
            } catch (e: Exception) {
                failed.add(change.filePath)
                errors[change.filePath] = e.message ?: "Unknown error"
                logger.warn("Failed to rollback ${change.filePath}", e)
            }
        }

        // 刷新文件系统
        LocalFileSystem.getInstance().refresh(false)

        return when {
            failed.isEmpty() -> {
                RollbackResult.Success(
                    rolledBackFiles = rolledBack,
                    message = "Successfully rolled back ${rolledBack.size} file(s)"
                )
            }
            rolledBack.isEmpty() -> {
                RollbackResult.Failure(
                    error = "Failed to rollback all files: ${errors.values.firstOrNull() ?: "Unknown error"}"
                )
            }
            else -> {
                RollbackResult.PartialSuccess(
                    rolledBackFiles = rolledBack,
                    failedFiles = failed,
                    errors = errors,
                    message = "Rolled back ${rolledBack.size} file(s), ${failed.size} failed"
                )
            }
        }
    }

    /**
     * 回滚单个文件变更
     */
    private fun rollbackFileChange(change: FileChange) {
        when (change.changeType) {
            FileChange.ChangeType.CREATED -> {
                // 删除创建的文件
                deleteFileIfExists(change.filePath)
            }
            FileChange.ChangeType.MODIFIED -> {
                // 恢复修改前的内容
                val beforeContent = change.beforeContent
                    ?: throw IllegalStateException("No before content for modified file: ${change.filePath}")
                restoreOrRecreateFileContent(change.filePath, beforeContent)
            }
            FileChange.ChangeType.DELETED -> {
                // 恢复删除的文件
                val beforeContent = change.beforeContent
                    ?: throw IllegalStateException("No before content for deleted file: ${change.filePath}")
                restoreOrRecreateFileContent(change.filePath, beforeContent)
            }
        }
    }

    /**
     * 删除文件（若不存在则视为已回滚）
     */
    private fun deleteFileIfExists(filePath: String) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(toVfsPath(filePath))
            ?: return

        WriteAction.runAndWait<Throwable> {
            if (virtualFile.exists()) {
                virtualFile.delete(this)
            }
        }
    }

    /**
     * 恢复文件内容
     */
    private fun restoreFileContent(filePath: String, content: String) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(toVfsPath(filePath))
            ?: throw IllegalStateException("File not found: $filePath")

        WriteAction.runAndWait<Throwable> {
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            if (document != null) {
                document.setText(content)
                FileDocumentManager.getInstance().saveDocument(document)
            } else {
                virtualFile.setBinaryContent(content.toByteArray(virtualFile.charset))
            }
        }
    }

    /**
     * 优先恢复已有文件；若文件已不存在则重建为 before 内容。
     */
    private fun restoreOrRecreateFileContent(filePath: String, content: String) {
        val existing = LocalFileSystem.getInstance().refreshAndFindFileByPath(toVfsPath(filePath))
        if (existing != null && existing.exists()) {
            restoreFileContent(filePath, content)
        } else {
            recreateFile(filePath, content)
        }
    }

    /**
     * 重新创建文件
     */
    private fun recreateFile(filePath: String, content: String) {
        val path = Paths.get(filePath)
        val parentPath = path.parent

        WriteAction.runAndWait<Throwable> {
            // 确保父目录存在
            if (parentPath != null && !Files.exists(parentPath)) {
                Files.createDirectories(parentPath)
            }

            // 创建文件
            Files.writeString(path, content)

            // 刷新 VFS
            LocalFileSystem.getInstance().refreshAndFindFileByPath(toVfsPath(filePath))
        }
    }

    /**
     * 创建备份
     */
    private fun createBackup(changeset: Changeset) {
        try {
            val basePath = project.basePath ?: return
            val backupDir = Paths.get(basePath)
                .resolve(".spec-coding")
                .resolve("backups")
                .resolve(changeset.id)

            Files.createDirectories(backupDir)

            for (change in changeset.changes) {
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(toVfsPath(change.filePath))
                if (virtualFile != null && virtualFile.exists()) {
                    val content = runReadAction {
                        String(virtualFile.contentsToByteArray(), virtualFile.charset)
                    }

                    val backupFile = backupDir.resolve(virtualFile.name)
                    Files.writeString(backupFile, content)
                }
            }

            logger.info("Created backup for changeset: ${changeset.id}")
        } catch (e: Exception) {
            logger.warn("Failed to create backup", e)
        }
    }

    /**
     * 检查文件是否已被手动修改
     */
    fun checkConflicts(changesetId: String): List<String> {
        val changeset = store.getById(changesetId) ?: return emptyList()
        val conflicts = mutableListOf<String>()

        for (change in changeset.changes) {
            if (change.changeType == FileChange.ChangeType.MODIFIED) {
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(toVfsPath(change.filePath))
                if (virtualFile != null && virtualFile.exists()) {
                    val currentContent = runReadAction {
                        String(virtualFile.contentsToByteArray(), virtualFile.charset)
                    }

                    // 如果当前内容既不是 before 也不是 after，说明被手动修改了
                    if (currentContent != change.beforeContent && currentContent != change.afterContent) {
                        conflicts.add(change.filePath)
                    }
                }
            }
        }

        return conflicts
    }

    private fun toVfsPath(path: String): String {
        return path.replace('\\', '/')
    }

    companion object {
        fun getInstance(project: Project): RollbackManager {
            return project.getService(RollbackManager::class.java)
        }
    }
}
