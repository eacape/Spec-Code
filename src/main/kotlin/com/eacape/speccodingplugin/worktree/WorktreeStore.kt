package com.eacape.speccodingplugin.worktree

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

@Service(Service.Level.PROJECT)
class WorktreeStore(private val project: Project) : WorktreeStateStore {
    private val logger = thisLogger()
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Volatile
    private var loaded = false
    private var state = WorktreeState()

    override fun load(): WorktreeState {
        ensureLoaded()
        return synchronized(lock) { state.copy(bindings = state.bindings.toList()) }
    }

    override fun save(newState: WorktreeState) {
        synchronized(lock) {
            state = newState
            persistToDisk(newState)
            loaded = true
        }
    }

    private fun ensureLoaded() {
        if (loaded) return

        synchronized(lock) {
            if (loaded) return
            state = loadFromDisk()
            loaded = true
        }
    }

    private fun loadFromDisk(): WorktreeState {
        val path = storagePath() ?: return WorktreeState()
        if (!Files.exists(path)) {
            return WorktreeState()
        }

        return try {
            val content = Files.readString(path)
            val stored = json.decodeFromString(StoredWorktreeState.serializer(), content)
            WorktreeState(
                activeWorktreeId = stored.activeWorktreeId,
                bindings = stored.bindings.map { it.toBinding() },
            )
        } catch (e: Exception) {
            logger.warn("Failed to load worktree state", e)
            WorktreeState()
        }
    }

    private fun persistToDisk(newState: WorktreeState) {
        val path = storagePath() ?: return
        try {
            Files.createDirectories(path.parent)
            val payload = StoredWorktreeState(
                version = 1,
                activeWorktreeId = newState.activeWorktreeId,
                bindings = newState.bindings.map { StoredWorktreeBinding.from(it) },
            )
            val content = json.encodeToString(payload)
            Files.writeString(
                path,
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        } catch (e: Exception) {
            logger.warn("Failed to persist worktree state", e)
        }
    }

    private fun storagePath(): Path? {
        val basePath = project.basePath ?: return null
        return Paths.get(basePath)
            .resolve(".spec-coding")
            .resolve("worktrees.json")
    }

    companion object {
        fun getInstance(project: Project): WorktreeStore = project.service()
    }
}
