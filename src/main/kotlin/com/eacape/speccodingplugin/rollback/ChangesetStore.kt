package com.eacape.speccodingplugin.rollback

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * 变更集存储（Project-level Service）
 * 管理变更集的持久化和查询
 */
@Service(Service.Level.PROJECT)
class ChangesetStore(private val project: Project) {
    private val logger = thisLogger()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val changesets = mutableListOf<Changeset>()
    private val lock = Any()

    @Volatile
    private var loaded = false

    /**
     * 保存变更集
     */
    fun save(changeset: Changeset) {
        synchronized(lock) {
            ensureLoaded()

            changesets.add(changeset)
            logger.info("Saved changeset: ${changeset.id} - ${changeset.description}")
            val pruned = applyRetentionLimitLocked()
            if (pruned > 0) {
                logger.info("Pruned $pruned old changeset(s), keep latest $MAX_RETAINED_CHANGESETS")
            }

            // 持久化到磁盘
            persistToDisk()
        }
        publishChanged(ChangesetChangedEvent(ChangesetChangedEvent.Action.SAVED, changeset.id))
    }

    /**
     * 获取所有变更集
     */
    fun getAll(): List<Changeset> {
        ensureLoaded()
        return synchronized(lock) {
            changesets.toList()
        }
    }

    /**
     * 根据 ID 获取变更集
     */
    fun getById(id: String): Changeset? {
        ensureLoaded()
        return synchronized(lock) {
            changesets.find { it.id == id }
        }
    }

    /**
     * 获取最近的变更集
     */
    fun getRecent(limit: Int = MAX_RETAINED_CHANGESETS): List<Changeset> {
        ensureLoaded()
        return synchronized(lock) {
            changesets.sortedByDescending { it.timestamp }.take(limit)
        }
    }

    /**
     * 删除变更集
     */
    fun delete(id: String): Boolean {
        val removed = synchronized(lock) {
            ensureLoaded()

            val removed = changesets.removeIf { it.id == id }
            if (removed) {
                logger.info("Deleted changeset: $id")
                persistToDisk()
            }
            removed
        }
        if (removed) {
            publishChanged(ChangesetChangedEvent(ChangesetChangedEvent.Action.DELETED, id))
        }
        return removed
    }

    /**
     * 清空所有变更集
     */
    fun clear() {
        val hadChanges = synchronized(lock) {
            ensureLoaded()
            val hadChanges = changesets.isNotEmpty()
            changesets.clear()
            logger.info("Cleared all changesets")
            persistToDisk()
            hadChanges
        }
        if (hadChanges) {
            publishChanged(ChangesetChangedEvent(ChangesetChangedEvent.Action.CLEARED))
        }
    }

    /**
     * 获取变更集数量
     */
    fun count(): Int {
        ensureLoaded()
        return synchronized(lock) {
            changesets.size
        }
    }

    private fun ensureLoaded() {
        if (loaded) {
            return
        }

        synchronized(lock) {
            if (loaded) {
                return
            }

            loadFromDisk()
            val pruned = applyRetentionLimitLocked()
            if (pruned > 0) {
                logger.info("Pruned $pruned old changeset(s) after load, keep latest $MAX_RETAINED_CHANGESETS")
                persistToDisk()
            }
            loaded = true
        }
    }

    private fun applyRetentionLimitLocked(): Int {
        if (changesets.size <= MAX_RETAINED_CHANGESETS) {
            return 0
        }

        val retainedIds = changesets
            .sortedByDescending { it.timestamp }
            .take(MAX_RETAINED_CHANGESETS)
            .mapTo(mutableSetOf()) { it.id }
        val before = changesets.size
        changesets.removeIf { it.id !in retainedIds }
        return before - changesets.size
    }

    private fun loadFromDisk() {
        val storePath = getStorePath() ?: return

        if (!storePath.exists()) {
            logger.info("No changeset store found, starting fresh")
            return
        }

        try {
            val content = storePath.readText()
            val stored = json.decodeFromString<StoredChangesets>(content)

            changesets.clear()
            changesets.addAll(stored.changesets.map { it.toChangeset() })

            logger.info("Loaded ${changesets.size} changesets from disk")
        } catch (e: Exception) {
            logger.error("Failed to load changesets from disk", e)
        }
    }

    private fun persistToDisk() {
        val storePath = getStorePath() ?: return

        try {
            // 确保目录存在
            storePath.parent?.let { Files.createDirectories(it) }

            val stored = StoredChangesets(
                version = 1,
                changesets = changesets.map { StoredChangeset.fromChangeset(it) }
            )

            val content = json.encodeToString(stored)
            Files.writeString(
                storePath,
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )

            logger.debug("Persisted ${changesets.size} changesets to disk")
        } catch (e: Exception) {
            logger.error("Failed to persist changesets to disk", e)
        }
    }

    private fun getStorePath(): Path? {
        val basePath = project.basePath ?: return null
        return Paths.get(basePath)
            .resolve(".spec-coding")
            .resolve("changesets.json")
    }

    private fun publishChanged(event: ChangesetChangedEvent) {
        runCatching {
            project.messageBus.syncPublisher(ChangesetChangedListener.TOPIC)
                .onChanged(event)
        }.onFailure {
            logger.debug("Failed to publish changeset changed event", it)
        }
    }

    companion object {
        const val MAX_RETAINED_CHANGESETS = 12

        fun getInstance(project: Project): ChangesetStore {
            return project.getService(ChangesetStore::class.java)
        }
    }
}

/**
 * 序列化用的数据结构
 */
@Serializable
private data class StoredChangesets(
    val version: Int,
    val changesets: List<StoredChangeset>
)

@Serializable
private data class StoredChangeset(
    val id: String,
    val description: String,
    val changes: List<StoredFileChange>,
    val timestamp: Long,
    val metadata: Map<String, String>
) {
    fun toChangeset(): Changeset {
        return Changeset(
            id = id,
            description = description,
            changes = changes.map { it.toFileChange() },
            timestamp = Instant.ofEpochMilli(timestamp),
            metadata = metadata
        )
    }

    companion object {
        fun fromChangeset(changeset: Changeset): StoredChangeset {
            return StoredChangeset(
                id = changeset.id,
                description = changeset.description,
                changes = changeset.changes.map { StoredFileChange.fromFileChange(it) },
                timestamp = changeset.timestamp.toEpochMilli(),
                metadata = changeset.metadata
            )
        }
    }
}

@Serializable
private data class StoredFileChange(
    val filePath: String,
    val beforeContent: String?,
    val afterContent: String?,
    val changeType: String,
    val timestamp: Long
) {
    fun toFileChange(): FileChange {
        return FileChange(
            filePath = filePath,
            beforeContent = beforeContent,
            afterContent = afterContent,
            changeType = FileChange.ChangeType.valueOf(changeType),
            timestamp = Instant.ofEpochMilli(timestamp)
        )
    }

    companion object {
        fun fromFileChange(change: FileChange): StoredFileChange {
            return StoredFileChange(
                filePath = change.filePath,
                beforeContent = change.beforeContent,
                afterContent = change.afterContent,
                changeType = change.changeType.name,
                timestamp = change.timestamp.toEpochMilli()
            )
        }
    }
}
