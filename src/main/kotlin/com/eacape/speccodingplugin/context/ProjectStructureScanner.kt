package com.eacape.speccodingplugin.context

import com.eacape.speccodingplugin.telemetry.SlowPathBaselineSample
import com.eacape.speccodingplugin.telemetry.emitSlowPathBaseline
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class ProjectStructureScanner(private val project: Project) : Disposable {
    private val logger = thisLogger()
    private val projectRootPath: Path? = runCatching {
        project.basePath
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it).toAbsolutePath().normalize() }
    }.getOrNull()

    private val cachedTreesByDepth = ConcurrentHashMap<Int, String>()

    @Volatile
    private var lastInvalidationReason: String = "cold-start"

    private val cacheHitCount = AtomicLong(0)
    private val cacheMissCount = AtomicLong(0)

    private data class TreeBuildStats(
        var directoryCount: Int = 1,
        var fileCount: Int = 0,
        var truncatedDirectoryCount: Int = 0,
    )

    init {
        subscribeToProjectStructureChanges()
    }

    fun getProjectTree(maxDepth: Int = 4): String {
        cachedTreesByDepth[maxDepth]?.let { cached ->
            val hitCount = cacheHitCount.incrementAndGet()
            val cacheStats = currentCacheStats(hitCount = hitCount)
            if (cacheStats.shouldEmitPeriodicHitLog()) {
                logger.info(
                    "ProjectStructureScanner cache hit: maxDepth=$maxDepth, ${cacheStats.summary()}",
                )
            }
            return cached
        }

        val missCount = cacheMissCount.incrementAndGet()
        val startedAt = System.nanoTime()
        val basePath = project.basePath ?: return ""
        val baseDir = LocalFileSystem.getInstance()
            .findFileByPath(basePath) ?: return ""

        val sb = StringBuilder()
        sb.appendLine(baseDir.name + "/")
        val stats = TreeBuildStats()
        buildTree(baseDir, sb, "", maxDepth, 0, stats)

        val result = sb.toString().trimEnd()
        cachedTreesByDepth[maxDepth] = result
        logTreeBuild(
            baseDir = baseDir,
            maxDepth = maxDepth,
            stats = stats,
            treeLength = result.length,
            elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
            missCount = missCount,
        )
        return result
    }

    fun getProjectStructureContext(): ContextItem {
        val tree = getProjectTree()
        return ContextItem(
            type = ContextType.PROJECT_STRUCTURE,
            label = "Project Structure",
            content = tree,
            filePath = project.basePath,
            priority = 30,
        )
    }

    fun getDirectoryStructureContext(
        directoryPath: String,
        maxDepth: Int = 3,
    ): ContextItem? {
        val normalized = directoryPath.trim().ifBlank { return null }
        val dir = LocalFileSystem.getInstance().findFileByPath(normalized) ?: return null
        if (!dir.isDirectory) return null

        val projectBase = project.basePath
        if (projectBase != null && !dir.path.startsWith(projectBase)) {
            return null
        }

        val sb = StringBuilder()
        sb.appendLine(dir.name + "/")
        buildTree(dir, sb, "", maxDepth, 0, TreeBuildStats())

        val content = sb.toString().trimEnd()
        if (content.isBlank()) return null

        return ContextItem(
            type = ContextType.PROJECT_STRUCTURE,
            label = "Dir: ${dir.name}",
            content = content,
            filePath = dir.path,
            priority = 45,
        )
    }

    fun invalidateCache(reason: String = "manual") {
        val hadCache = cachedTreesByDepth.isNotEmpty()
        cachedTreesByDepth.clear()
        lastInvalidationReason = reason
        logger.info(
            "ProjectStructureScanner cache invalidated: reason=$reason, hadCache=$hadCache, ${currentCacheStats().summary()}",
        )
    }

    override fun dispose() = Unit

    private fun buildTree(
        dir: VirtualFile,
        sb: StringBuilder,
        prefix: String,
        maxDepth: Int,
        currentDepth: Int,
        stats: TreeBuildStats,
    ) {
        val visibleChildren = dir.children
            ?.filter { !isIgnored(it.name) }
            ?: return

        if (currentDepth >= maxDepth) {
            if (visibleChildren.isNotEmpty()) {
                stats.truncatedDirectoryCount += 1
            }
            return
        }

        val children = visibleChildren
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))

        for ((index, child) in children.withIndex()) {
            val isLast = index == children.size - 1
            val connector = if (isLast) "\u2514\u2500\u2500 " else "\u251C\u2500\u2500 "
            val extension = if (isLast) "    " else "\u2502   "

            val suffix = if (child.isDirectory) "/" else ""
            sb.appendLine("$prefix$connector${child.name}$suffix")
            if (child.isDirectory) {
                stats.directoryCount += 1
            } else {
                stats.fileCount += 1
            }

            if (child.isDirectory) {
                buildTree(
                    dir = child,
                    sb = sb,
                    prefix = prefix + extension,
                    maxDepth = maxDepth,
                    currentDepth = currentDepth + 1,
                    stats = stats,
                )
            }
        }
    }

    private fun logTreeBuild(
        baseDir: VirtualFile,
        maxDepth: Int,
        stats: TreeBuildStats,
        treeLength: Int,
        elapsedMs: Long,
        missCount: Long,
    ) {
        emitSlowPathBaseline(
            logger = logger,
            sample = SlowPathBaselineSample(
                operationKey = "ProjectStructureScanner.getProjectTreeMiss",
                elapsedMs = elapsedMs,
            ),
        )
        val cacheStats = currentCacheStats(missCount = missCount)
        val message =
            "ProjectStructureScanner cache miss: root=${baseDir.path}, maxDepth=$maxDepth, elapsedMs=$elapsedMs, " +
                "directories=${stats.directoryCount}, files=${stats.fileCount}, truncatedDirectories=${stats.truncatedDirectoryCount}, " +
                "treeChars=$treeLength, ${cacheStats.summary()}"
        when (determineContextTelemetrySeverity(elapsedMs)) {
            ContextTelemetrySeverity.WARN -> logger.warn(message)
            ContextTelemetrySeverity.INFO,
            ContextTelemetrySeverity.SKIP -> logger.info(message)
        }
    }

    private fun currentCacheStats(
        hitCount: Long = cacheHitCount.get(),
        missCount: Long = cacheMissCount.get(),
    ): ProjectStructureCacheStats {
        return ProjectStructureCacheStats(
            hitCount = hitCount,
            missCount = missCount,
            lastInvalidationReason = lastInvalidationReason,
        )
    }

    private fun isIgnored(name: String): Boolean {
        return name in IGNORED_NAMES
    }

    private fun subscribeToProjectStructureChanges() {
        if (projectRootPath == null) {
            return
        }
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val reason = firstRelevantStructureChangeReason(events) ?: return
                    invalidateCache(reason = reason)
                }
            },
        )
    }

    private fun firstRelevantStructureChangeReason(events: List<VFileEvent>): String? {
        val root = projectRootPath ?: return null
        return events.asSequence()
            .mapNotNull { event -> structureChangeReason(event, root) }
            .firstOrNull()
    }

    private fun structureChangeReason(event: VFileEvent, root: Path): String? {
        val reasonPrefix = when (event) {
            is VFileCreateEvent -> "vfs-create"
            is VFileDeleteEvent -> "vfs-delete"
            is VFileMoveEvent -> "vfs-move"
            is VFileCopyEvent -> "vfs-copy"
            is VFilePropertyChangeEvent -> {
                if (event.isRename) {
                    "vfs-rename"
                } else {
                    null
                }
            }
            else -> null
        } ?: return null

        val affectedRelativePath = affectedPathsForEvent(event)
            .asSequence()
            .mapNotNull { candidatePath -> relativePathWithinProject(candidatePath, root) }
            .firstOrNull { relativePath -> !isIgnoredPath(relativePath) }
            ?: return null

        return "$reasonPrefix:$affectedRelativePath"
    }

    private fun affectedPathsForEvent(event: VFileEvent): List<String> {
        return when (event) {
            is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
            is VFilePropertyChangeEvent -> listOf(event.oldPath, event.newPath)
            else -> listOf(event.path)
        }
    }

    private fun relativePathWithinProject(rawPath: String, root: Path): String? {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        val resolved = try {
            Path.of(trimmed).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            return null
        }

        if (!resolved.startsWith(root)) {
            return null
        }

        val relativePath = root.relativize(resolved)
            .joinToString(separator = "/") { segment -> segment.toString() }
            .trim()
        if (relativePath.isEmpty() || relativePath == ".") {
            return null
        }
        return relativePath
    }

    private fun isIgnoredPath(relativePath: String): Boolean {
        return relativePath
            .replace('\\', '/')
            .split('/')
            .filter(String::isNotBlank)
            .any(::isIgnored)
    }

    companion object {
        private val IGNORED_NAMES = setOf(
            ".git", ".idea", ".gradle",
            "build", "out", "node_modules",
            ".spec-coding", "__pycache__",
            ".DS_Store", "Thumbs.db",
        )

        fun getInstance(project: Project): ProjectStructureScanner {
            return project.getService(ProjectStructureScanner::class.java)
        }
    }
}
