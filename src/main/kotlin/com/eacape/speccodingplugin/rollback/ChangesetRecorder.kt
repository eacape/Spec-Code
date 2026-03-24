package com.eacape.speccodingplugin.rollback

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.time.Instant
import java.util.UUID

/**
 * 变更集记录器
 * 自动捕获 AI 文件操作的 before/after 快照
 */
class ChangesetRecorder(private val project: Project) {
    private val logger = thisLogger()

    private var currentChangeset: MutableChangeset? = null
    private val lock = Any()

    /**
     * 开始记录新的变更集
     */
    fun startRecording(description: String, metadata: Map<String, String> = emptyMap()): String {
        synchronized(lock) {
            // 如果有未完成的变更集，先完成它
            if (currentChangeset != null) {
                logger.warn("Starting new changeset while previous one is not finished")
                finishRecording()
            }

            val changesetId = UUID.randomUUID().toString()
            currentChangeset = MutableChangeset(
                id = changesetId,
                description = description,
                metadata = metadata,
                timestamp = Instant.now()
            )

            logger.info("Started recording changeset: $changesetId - $description")
            return changesetId
        }
    }

    /**
     * 记录文件创建
     */
    fun recordFileCreation(file: VirtualFile, content: String) {
        synchronized(lock) {
            val changeset = currentChangeset
                ?: throw IllegalStateException("No active changeset. Call startRecording() first.")

            val change = FileChange(
                filePath = file.path,
                beforeContent = null,
                afterContent = content,
                changeType = FileChange.ChangeType.CREATED
            )

            changeset.changes.add(change)
            logger.debug("Recorded file creation: ${file.path}")
        }
    }

    /**
     * 记录文件修改
     */
    fun recordFileModification(file: VirtualFile, beforeContent: String, afterContent: String) {
        synchronized(lock) {
            val changeset = currentChangeset
                ?: throw IllegalStateException("No active changeset. Call startRecording() first.")

            val change = FileChange(
                filePath = file.path,
                beforeContent = beforeContent,
                afterContent = afterContent,
                changeType = FileChange.ChangeType.MODIFIED
            )

            changeset.changes.add(change)
            logger.debug("Recorded file modification: ${file.path}")
        }
    }

    /**
     * 记录文件删除
     */
    fun recordFileDeletion(file: VirtualFile, beforeContent: String) {
        synchronized(lock) {
            val changeset = currentChangeset
                ?: throw IllegalStateException("No active changeset. Call startRecording() first.")

            val change = FileChange(
                filePath = file.path,
                beforeContent = beforeContent,
                afterContent = null,
                changeType = FileChange.ChangeType.DELETED
            )

            changeset.changes.add(change)
            logger.debug("Recorded file deletion: ${file.path}")
        }
    }

    /**
     * 完成当前变更集的记录
     */
    fun finishRecording(): Changeset? {
        synchronized(lock) {
            val changeset = currentChangeset ?: return null

            val result = Changeset(
                id = changeset.id,
                description = changeset.description,
                changes = changeset.changes.toList(),
                timestamp = changeset.timestamp,
                metadata = changeset.metadata
            )

            currentChangeset = null
            logger.info("Finished recording changeset: ${result.id} (${result.changes.size} changes)")

            return result
        }
    }

    /**
     * 取消当前变更集的记录
     */
    fun cancelRecording() {
        synchronized(lock) {
            val changeset = currentChangeset
            if (changeset != null) {
                logger.info("Cancelled recording changeset: ${changeset.id}")
                currentChangeset = null
            }
        }
    }

    /**
     * 检查是否正在记录
     */
    fun isRecording(): Boolean {
        synchronized(lock) {
            return currentChangeset != null
        }
    }

    /**
     * 获取当前变更集的统计信息
     */
    fun getCurrentStatistics(): ChangesetStatistics? {
        synchronized(lock) {
            val changeset = currentChangeset ?: return null

            return ChangesetStatistics(
                totalFiles = changeset.changes.size,
                createdFiles = changeset.changes.count { it.changeType == FileChange.ChangeType.CREATED },
                modifiedFiles = changeset.changes.count { it.changeType == FileChange.ChangeType.MODIFIED },
                deletedFiles = changeset.changes.count { it.changeType == FileChange.ChangeType.DELETED }
            )
        }
    }

    /**
     * 可变的变更集（用于构建过程）
     */
    private data class MutableChangeset(
        val id: String,
        val description: String,
        val metadata: Map<String, String>,
        val timestamp: Instant,
        val changes: MutableList<FileChange> = mutableListOf()
    )

    companion object {
        /**
         * 读取文件内容（在 Read Action 中执行）
         */
        fun readFileContent(file: VirtualFile): String {
            return ReadAction.compute<String, Throwable> {
                String(file.contentsToByteArray(), file.charset)
            }
        }
    }
}
