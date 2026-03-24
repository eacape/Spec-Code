package com.eacape.speccodingplugin.worktree

internal interface WorktreeStateStore {
    fun load(): WorktreeState
    fun save(newState: WorktreeState)
}
