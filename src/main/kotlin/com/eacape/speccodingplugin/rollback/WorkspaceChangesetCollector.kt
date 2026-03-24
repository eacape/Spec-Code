package com.eacape.speccodingplugin.rollback

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * Collects text file snapshots from workspace and builds FileChange entries
 * by comparing "before" and "after" snapshots.
 */
object WorkspaceChangesetCollector {

    data class Snapshot internal constructor(
        internal val files: Map<String, String>,
    )

    data class CaptureOptions(
        val maxFiles: Int = 3000,
        val maxFileSizeBytes: Long = 1_000_000,
        val excludedTopLevelDirs: Set<String> = DEFAULT_EXCLUDED_TOP_LEVEL_DIRS,
        val excludedRelativePaths: Set<String> = DEFAULT_EXCLUDED_RELATIVE_PATHS,
    )

    fun capture(root: Path, options: CaptureOptions = CaptureOptions()): Snapshot {
        val normalizedRoot = root.toAbsolutePath().normalize()
        if (!normalizedRoot.exists()) return Snapshot(emptyMap())

        val tracked = LinkedHashMap<String, String>()
        Files.walk(normalizedRoot).use { stream ->
            stream.forEach { path ->
                if (tracked.size >= options.maxFiles) return@forEach
                if (!path.isRegularFile()) return@forEach

                val relative = normalizedRoot.relativize(path)
                    .toString()
                    .replace('\\', '/')
                if (relative.isBlank()) return@forEach
                if (isExcluded(relative, options)) return@forEach

                val content = readTextFile(path, options.maxFileSizeBytes) ?: return@forEach
                tracked[relative] = content
            }
        }
        return Snapshot(tracked)
    }

    fun diff(root: Path, before: Snapshot, after: Snapshot, maxChanges: Int = 400): List<FileChange> {
        if (before.files.isEmpty() && after.files.isEmpty()) return emptyList()

        val changes = mutableListOf<FileChange>()
        val sortedBeforeKeys = before.files.keys.sorted()
        val sortedAfterKeys = after.files.keys.sorted()

        val created = sortedAfterKeys.asSequence()
            .filter { key -> !before.files.containsKey(key) }
        for (relative in created) {
            if (changes.size >= maxChanges) return changes
            changes += FileChange(
                filePath = root.resolve(relative).toString(),
                beforeContent = null,
                afterContent = after.files[relative],
                changeType = FileChange.ChangeType.CREATED,
            )
        }

        val deleted = sortedBeforeKeys.asSequence()
            .filter { key -> !after.files.containsKey(key) }
        for (relative in deleted) {
            if (changes.size >= maxChanges) return changes
            changes += FileChange(
                filePath = root.resolve(relative).toString(),
                beforeContent = before.files[relative],
                afterContent = null,
                changeType = FileChange.ChangeType.DELETED,
            )
        }

        val common = sortedBeforeKeys.asSequence()
            .filter { key -> after.files.containsKey(key) }
        for (relative in common) {
            if (changes.size >= maxChanges) return changes
            val beforeContent = before.files[relative]
            val afterContent = after.files[relative]
            if (beforeContent != afterContent) {
                changes += FileChange(
                    filePath = root.resolve(relative).toString(),
                    beforeContent = beforeContent,
                    afterContent = afterContent,
                    changeType = FileChange.ChangeType.MODIFIED,
                )
            }
        }

        return changes
    }

    private fun readTextFile(path: Path, maxFileSizeBytes: Long): String? {
        return runCatching {
            val size = Files.size(path)
            if (size > maxFileSizeBytes) return null

            val bytes = Files.readAllBytes(path)
            if (bytes.any { it == 0.toByte() }) return null

            String(bytes, StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun isExcluded(relativePath: String, options: CaptureOptions): Boolean {
        if (relativePath in options.excludedRelativePaths) {
            return true
        }
        val topLevel = relativePath.substringBefore('/')
        return topLevel in options.excludedTopLevelDirs
    }

    private val DEFAULT_EXCLUDED_TOP_LEVEL_DIRS = setOf(
        ".git",
        ".gradle",
        ".idea",
        "build",
        "out",
        "target",
        "node_modules",
    )

    private val DEFAULT_EXCLUDED_RELATIVE_PATHS = setOf(
        ".spec-coding/changesets.json",
    )
}
