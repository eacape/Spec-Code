package com.eacape.speccodingplugin.worktree

import kotlinx.serialization.Serializable

/**
 * Worktree 运行状态
 */
enum class WorktreeStatus {
    ACTIVE,
    MERGED,
    REMOVED,
    ERROR,
}

/**
 * Worktree 与 Spec 任务的绑定信息
 */
data class WorktreeBinding(
    val id: String,
    val specTaskId: String,
    val branchName: String,
    val worktreePath: String,
    val baseBranch: String,
    val status: WorktreeStatus = WorktreeStatus.ACTIVE,
    val createdAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
)

/**
 * Worktree 合并结果
 */
data class WorktreeMergeResult(
    val worktreeId: String,
    val sourceBranch: String,
    val targetBranch: String,
    val hasConflicts: Boolean,
    val statusDescription: String,
)

/**
 * Git 合并底层结果（执行器返回）
 */
data class GitMergeOutcome(
    val hasConflicts: Boolean,
    val statusDescription: String,
)

/**
 * 内部持久化状态
 */
data class WorktreeState(
    val activeWorktreeId: String? = null,
    val bindings: List<WorktreeBinding> = emptyList(),
)

@Serializable
internal data class StoredWorktreeState(
    val version: Int = 1,
    val activeWorktreeId: String? = null,
    val bindings: List<StoredWorktreeBinding> = emptyList(),
)

@Serializable
internal data class StoredWorktreeBinding(
    val id: String,
    val specTaskId: String,
    val branchName: String,
    val worktreePath: String,
    val baseBranch: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
) {
    fun toBinding(): WorktreeBinding {
        return WorktreeBinding(
            id = id,
            specTaskId = specTaskId,
            branchName = branchName,
            worktreePath = worktreePath,
            baseBranch = baseBranch,
            status = WorktreeStatus.entries.firstOrNull { it.name == status } ?: WorktreeStatus.ACTIVE,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastError = lastError,
        )
    }

    companion object {
        fun from(binding: WorktreeBinding): StoredWorktreeBinding {
            return StoredWorktreeBinding(
                id = binding.id,
                specTaskId = binding.specTaskId,
                branchName = binding.branchName,
                worktreePath = binding.worktreePath,
                baseBranch = binding.baseBranch,
                status = binding.status.name,
                createdAt = binding.createdAt,
                updatedAt = binding.updatedAt,
                lastError = binding.lastError,
            )
        }
    }
}
