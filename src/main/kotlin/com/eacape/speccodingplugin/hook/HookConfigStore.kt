package com.eacape.speccodingplugin.hook

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readText

@Service(Service.Level.PROJECT)
class HookConfigStore(private val project: Project) {
    private val logger = thisLogger()
    private val lock = Any()

    @Volatile
    private var loaded = false
    @Volatile
    private var storeFingerprint: StoreFingerprint? = null

    private val hooks = mutableListOf<HookDefinition>()

    fun listHooks(): List<HookDefinition> {
        ensureLoaded()
        return synchronized(lock) {
            hooks.toList()
        }
    }

    fun getHookById(id: String): HookDefinition? {
        ensureLoaded()
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) {
            return null
        }
        return synchronized(lock) {
            hooks.firstOrNull { it.id == normalizedId }
        }
    }

    fun saveHook(definition: HookDefinition) {
        synchronized(lock) {
            ensureLoaded()
            val snapshot = hooks.toList()
            val normalized = normalize(definition)
            val index = hooks.indexOfFirst { it.id == normalized.id }
            if (index >= 0) {
                hooks[index] = normalized
            } else {
                hooks.add(normalized)
            }
            if (persistToDisk()) {
                if (index >= 0) {
                    logger.info("Updated hook definition: ${normalized.id}")
                } else {
                    logger.info("Saved hook definition: ${normalized.id}")
                }
            } else {
                hooks.clear()
                hooks.addAll(snapshot)
                logger.warn("Save hook rolled back due to persistence failure: ${normalized.id}")
            }
        }
    }

    fun deleteHook(id: String): Boolean {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) {
            return false
        }

        synchronized(lock) {
            ensureLoaded()
            val index = hooks.indexOfFirst { it.id == normalizedId }
            if (index < 0) {
                return false
            }
            val removedHook = hooks.removeAt(index)
            if (persistToDisk()) {
                logger.info("Deleted hook definition: $normalizedId")
                return true
            }
            hooks.add(index, removedHook)
            logger.warn("Delete hook rolled back due to persistence failure: $normalizedId")
            return false
        }
    }

    fun setHookEnabled(id: String, enabled: Boolean): Boolean {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) {
            return false
        }

        synchronized(lock) {
            ensureLoaded()
            val index = hooks.indexOfFirst { it.id == normalizedId }
            if (index < 0) {
                return false
            }
            val previous = hooks[index]
            val updated = previous.copy(enabled = enabled)
            if (previous == updated) {
                return true
            }
            hooks[index] = updated
            if (!persistToDisk()) {
                hooks[index] = previous
                logger.warn("Set hook enabled rolled back due to persistence failure: $normalizedId -> $enabled")
                return false
            }
            logger.info("Hook enabled state updated: $normalizedId -> $enabled")
            return true
        }
    }

    private fun ensureLoaded() {
        synchronized(lock) {
            val path = storePath()
            val currentFingerprint = snapshotStore(path)
            if (loaded && currentFingerprint == storeFingerprint) {
                return
            }
            loadFromDisk(path)
            loaded = true
            storeFingerprint = snapshotStore(path)
        }
    }

    private fun snapshotStore(path: Path?): StoreFingerprint? {
        if (path == null) {
            return null
        }
        if (!path.exists()) {
            return StoreFingerprint(exists = false, lastModifiedMillis = 0L, size = 0L)
        }
        return runCatching {
            StoreFingerprint(
                exists = true,
                lastModifiedMillis = Files.getLastModifiedTime(path).toMillis(),
                size = Files.size(path),
            )
        }.getOrElse { error ->
            logger.debug("Failed to read hooks store fingerprint: ${path.toAbsolutePath()}", error)
            StoreFingerprint(exists = true, lastModifiedMillis = -1L, size = -1L)
        }
    }

    private fun loadFromDisk(path: Path?) {
        if (path == null) {
            hooks.clear()
            return
        }

        if (!path.exists()) {
            logger.info("No hooks config found, starting with empty hook set")
            hooks.clear()
            return
        }

        try {
            val content = path.readText(StandardCharsets.UTF_8)
            val parsed = HookYamlCodec.deserialize(content)
                .map(::normalize)

            hooks.clear()
            hooks.addAll(parsed)
            logger.info("Loaded ${hooks.size} hooks from ${path.toAbsolutePath()}")
        } catch (e: Exception) {
            logger.warn("Failed to load hooks config, fallback to empty list", e)
            hooks.clear()
        }
    }

    private fun persistToDisk(): Boolean {
        val path = storePath() ?: return false
        val content = HookYamlCodec.serialize(hooks)
        try {
            path.parent?.let { Files.createDirectories(it) }
            repeat(PERSIST_WRITE_MAX_RETRY) { attempt ->
                tryMakeWritable(path)
                runCatching {
                    Files.writeString(
                        path,
                        content,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                    )
                }.onSuccess {
                    storeFingerprint = snapshotStore(path)
                    logger.debug("Persisted ${hooks.size} hooks to ${path.toAbsolutePath()}")
                    return true
                }.onFailure { error ->
                    if (error is AccessDeniedException && attempt < PERSIST_WRITE_MAX_RETRY - 1) {
                        Thread.sleep(PERSIST_WRITE_RETRY_INTERVAL_MILLIS * (attempt + 1))
                    } else {
                        throw error
                    }
                }
            }
            return false
        } catch (e: Exception) {
            if (e is AccessDeniedException) {
                logger.warn("Failed to persist hooks config: access denied for ${e.file ?: path}")
            } else {
                logger.error("Failed to persist hooks config", e)
            }
            return false
        }
    }

    private fun tryMakeWritable(path: Path) {
        if (!Files.exists(path)) {
            return
        }
        runCatching { Files.setAttribute(path, "dos:readonly", false) }
        runCatching { path.toFile().setWritable(true) }
    }

    private fun storePath(): Path? {
        val basePath = project.basePath ?: return null
        return Paths.get(basePath)
            .resolve(".spec-coding")
            .resolve("hooks.yaml")
    }

    private fun normalize(definition: HookDefinition): HookDefinition {
        val normalizedId = definition.id.trim()
        val normalizedName = definition.name.trim()

        require(normalizedId.isNotBlank()) { "Hook id cannot be blank" }
        require(normalizedName.isNotBlank()) { "Hook name cannot be blank" }
        require(definition.actions.isNotEmpty()) { "Hook actions cannot be empty" }

        val normalizedConditions = HookConditions(
            filePattern = definition.conditions.filePattern?.trim()?.ifBlank { null },
            specStage = definition.conditions.specStage?.trim()?.ifBlank { null },
        )

        val normalizedActions = definition.actions.map { action ->
            val command = action.command?.trim()?.ifBlank { null }
            val message = action.message?.trim()?.ifBlank { null }
            val args = action.args.mapNotNull { it.trim().ifBlank { null } }

            when (action.type) {
                HookActionType.RUN_COMMAND -> {
                    require(!command.isNullOrBlank()) { "RUN_COMMAND action requires command" }
                }

                HookActionType.SHOW_NOTIFICATION -> {
                    require(!message.isNullOrBlank()) { "SHOW_NOTIFICATION action requires message" }
                }
            }

            action.copy(
                command = command,
                message = message,
                args = args,
                timeoutMillis = action.timeoutMillis.coerceIn(1_000L, 10 * 60_000L),
            )
        }

        return definition.copy(
            id = normalizedId,
            name = normalizedName,
            conditions = normalizedConditions,
            actions = normalizedActions,
        )
    }

    companion object {
        private const val PERSIST_WRITE_MAX_RETRY = 3
        private const val PERSIST_WRITE_RETRY_INTERVAL_MILLIS = 120L

        fun getInstance(project: Project): HookConfigStore = project.service()
    }

    private data class StoreFingerprint(
        val exists: Boolean,
        val lastModifiedMillis: Long,
        val size: Long,
    )
}
