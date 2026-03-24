package com.eacape.speccodingplugin.ui.worktree

import com.eacape.speccodingplugin.worktree.WorktreeStatus

data class WorktreeListItem(
    val id: String,
    val specTaskId: String,
    val specTitle: String,
    val branchName: String,
    val worktreePath: String,
    val baseBranch: String,
    val status: WorktreeStatus,
    val isActive: Boolean,
    val updatedAt: Long,
    val lastError: String?,
)

