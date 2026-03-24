package com.eacape.speccodingplugin.rollback

import java.time.Instant

/**
 * 文件变更记录
 */
data class FileChange(
    val filePath: String,
    val beforeContent: String?,  // null 表示新建文件
    val afterContent: String?,   // null 表示删除文件
    val changeType: ChangeType,
    val timestamp: Instant = Instant.now()
) {
    enum class ChangeType {
        CREATED,   // 新建文件
        MODIFIED,  // 修改文件
        DELETED    // 删除文件
    }

    /**
     * 是否可以回滚
     */
    fun canRollback(): Boolean {
        return beforeContent != null || changeType == ChangeType.CREATED
    }

    /**
     * 获取变更摘要
     */
    fun getSummary(): String {
        return when (changeType) {
            ChangeType.CREATED -> "Created: $filePath"
            ChangeType.MODIFIED -> "Modified: $filePath"
            ChangeType.DELETED -> "Deleted: $filePath"
        }
    }
}

/**
 * 变更集（一次 AI 操作的所有文件变更）
 */
data class Changeset(
    val id: String,
    val description: String,
    val changes: List<FileChange>,
    val timestamp: Instant = Instant.now(),
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * 获取变更统计
     */
    fun getStatistics(): ChangesetStatistics {
        return ChangesetStatistics(
            totalFiles = changes.size,
            createdFiles = changes.count { it.changeType == FileChange.ChangeType.CREATED },
            modifiedFiles = changes.count { it.changeType == FileChange.ChangeType.MODIFIED },
            deletedFiles = changes.count { it.changeType == FileChange.ChangeType.DELETED }
        )
    }

    /**
     * 获取变更摘要
     */
    fun getSummary(): String {
        val stats = getStatistics()
        val parts = mutableListOf<String>()

        if (stats.createdFiles > 0) parts.add("${stats.createdFiles} created")
        if (stats.modifiedFiles > 0) parts.add("${stats.modifiedFiles} modified")
        if (stats.deletedFiles > 0) parts.add("${stats.deletedFiles} deleted")

        return parts.joinToString(", ")
    }
}

/**
 * 变更集统计信息
 */
data class ChangesetStatistics(
    val totalFiles: Int,
    val createdFiles: Int,
    val modifiedFiles: Int,
    val deletedFiles: Int
)

/**
 * 回滚结果
 */
sealed class RollbackResult {
    data class Success(
        val rolledBackFiles: List<String>,
        val message: String
    ) : RollbackResult()

    data class PartialSuccess(
        val rolledBackFiles: List<String>,
        val failedFiles: List<String>,
        val errors: Map<String, String>,
        val message: String
    ) : RollbackResult()

    data class Failure(
        val error: String,
        val cause: Throwable? = null
    ) : RollbackResult()

    /**
     * 是否成功
     */
    fun isSuccess(): Boolean = this is Success || this is PartialSuccess

    /**
     * 获取摘要信息
     */
    fun getSummary(): String {
        return when (this) {
            is Success -> message
            is PartialSuccess -> "$message (${failedFiles.size} failed)"
            is Failure -> "Rollback failed: $error"
        }
    }
}

/**
 * 回滚选项
 */
data class RollbackOptions(
    val selectedFiles: Set<String>? = null,  // null 表示回滚所有文件
    val skipConfirmation: Boolean = false,
    val createBackup: Boolean = true
)
