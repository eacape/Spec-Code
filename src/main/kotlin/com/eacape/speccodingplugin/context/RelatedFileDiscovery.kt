package com.eacape.speccodingplugin.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
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

/**
 * 相关文件发现服务（Project-level Service）。
 * 当前先显式区分文本启发式层和未来语义层，避免两类策略继续混在同一个 service 里增长。
 */
@Service(Service.Level.PROJECT)
class RelatedFileDiscovery(private val project: Project) : Disposable {
    private val logger = thisLogger()
    private val projectRootPath: Path? = runCatching {
        project.basePath
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it).toAbsolutePath().normalize() }
    }.getOrNull()
    private var semanticResolver: RelatedFileSemanticResolver = RelatedFileSemanticResolver.unavailable()
    private val cachedResults = ConcurrentHashMap<String, CachedRelatedFileDiscoveryResult>()

    @Volatile
    private var lastInvalidationReason: String = "cold-start"

    private val cacheHitCount = AtomicLong(0)
    private val cacheMissCount = AtomicLong(0)

    private data class ActiveEditorDiscoveryRequest(
        val filePath: String,
        val fileName: String,
        val content: String,
        val documentModificationStamp: Long,
        val context: RelatedFileDiscoveryContext,
    )

    private data class CachedRelatedFileDiscoveryResult(
        val result: RelatedFileDiscoveryResult,
        val documentModificationStamp: Long,
    )

    init {
        subscribeToStructureChanges()
    }

    internal constructor(
        project: Project,
        semanticResolver: RelatedFileSemanticResolver = RelatedFileSemanticResolver.unavailable(),
    ) : this(project) {
        this.semanticResolver = semanticResolver
    }

    fun discoverRelatedFiles(): List<ContextItem> {
        return discoverRelatedFilesDetailed().items
    }

    internal fun discoverRelatedFilesDetailed(): RelatedFileDiscoveryResult {
        val request = resolveActiveEditorRequest() ?: return RelatedFileDiscoveryResult.empty()
        var cacheStatus = "miss"

        cachedResults[request.filePath]?.let { cached ->
            if (cached.documentModificationStamp == request.documentModificationStamp) {
                val hitCount = cacheHitCount.incrementAndGet()
                logCacheHit(
                    request = request,
                    cacheStats = currentCacheStats(hitCount = hitCount),
                )
                return cached.result
            }
            cacheStatus = "stale-document"
            invalidateCacheEntry(
                filePath = request.filePath,
                reason = "document-change:${request.fileName}",
            )
        }

        val missCount = cacheMissCount.incrementAndGet()

        val heuristicResult = RelatedFileDiscoveryCoordinator.discoverHeuristicLayer(
            content = request.content,
            context = request.context,
            resolveFile = ::resolveExistingFile,
        )
        val semanticResult = semanticResolver.resolve(request.context)
        val result = RelatedFileDiscoveryCoordinator.merge(heuristicResult, semanticResult)
        cachedResults[request.filePath] = CachedRelatedFileDiscoveryResult(
            result = result,
            documentModificationStamp = request.documentModificationStamp,
        )
        logDiscoveryTelemetry(
            currentFileName = request.fileName,
            language = request.context.language,
            result = result,
            cacheStatus = cacheStatus,
            cacheStats = currentCacheStats(missCount = missCount),
        )
        return result
    }

    internal fun cacheStatsSnapshot(): RelatedFileCacheStats = currentCacheStats()

    override fun dispose() = Unit

    private fun resolveExistingFile(candidatePath: Path): RelatedFileResolvedFile? {
        val virtualFile = LocalFileSystem.getInstance()
            .findFileByPath(candidatePath.toString())
            ?: return null
        if (virtualFile.isDirectory) {
            return null
        }
        return RelatedFileResolvedFile(
            path = virtualFile.path,
            name = virtualFile.name,
        )
    }

    private fun logDiscoveryTelemetry(
        currentFileName: String,
        language: RelatedFileDiscoveryLanguage,
        result: RelatedFileDiscoveryResult,
        cacheStatus: String,
        cacheStats: RelatedFileCacheStats,
    ) {
        if (
            result.heuristicReferenceCount == 0 &&
            result.items.isEmpty() &&
            result.skippedLayers.isEmpty()
        ) {
            return
        }
        logger.info(
            "RelatedFileDiscovery: ${result.telemetry(currentFileName, language).summary()}, " +
                "cacheStatus=$cacheStatus, ${cacheStats.summary()}",
        )
    }

    private fun logCacheHit(
        request: ActiveEditorDiscoveryRequest,
        cacheStats: RelatedFileCacheStats,
    ) {
        if (!cacheStats.shouldEmitPeriodicHitLog()) {
            return
        }
        logger.info(
            "RelatedFileDiscovery cache hit: file=${request.fileName}, path=${request.filePath}, " +
                "language=${request.context.language.wireName}, ${cacheStats.summary()}",
        )
    }

    private fun resolveActiveEditorRequest(): ActiveEditorDiscoveryRequest? {
        val editor = getActiveEditor() ?: return null
        return ReadAction.compute<ActiveEditorDiscoveryRequest?, Throwable> {
            val virtualFile = editor.virtualFile ?: return@compute null
            val basePath = project.basePath ?: return@compute null
            ActiveEditorDiscoveryRequest(
                filePath = virtualFile.path,
                fileName = virtualFile.name,
                content = editor.document.text,
                documentModificationStamp = editor.document.modificationStamp,
                context = RelatedFileDiscoveryContext(
                    basePath = Path.of(basePath),
                    activeFilePath = Path.of(virtualFile.path),
                    language = RelatedFileDiscoveryLanguage.fromFileName(virtualFile.name),
                ),
            )
        }
    }

    private fun getActiveEditor(): Editor? {
        return FileEditorManager.getInstance(project).selectedTextEditor
    }

    private fun currentCacheStats(
        hitCount: Long = cacheHitCount.get(),
        missCount: Long = cacheMissCount.get(),
    ): RelatedFileCacheStats {
        return RelatedFileCacheStats(
            hitCount = hitCount,
            missCount = missCount,
            lastInvalidationReason = lastInvalidationReason,
        )
    }

    private fun invalidateCache(reason: String) {
        val hadCache = cachedResults.isNotEmpty()
        cachedResults.clear()
        lastInvalidationReason = reason
        if (hadCache) {
            logger.info("RelatedFileDiscovery cache invalidated: reason=$reason, ${currentCacheStats().summary()}")
        }
    }

    private fun invalidateCacheEntry(
        filePath: String,
        reason: String,
    ) {
        if (cachedResults.remove(filePath) != null) {
            lastInvalidationReason = reason
        }
    }

    private fun subscribeToStructureChanges() {
        if (projectRootPath == null) {
            return
        }
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val reason = firstRelevantStructureChangeReason(events) ?: return
                    invalidateCache(reason)
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
            is VFilePropertyChangeEvent -> if (event.isRename) "vfs-rename" else null
            else -> null
        } ?: return null

        val relativePath = affectedPathsForEvent(event)
            .asSequence()
            .mapNotNull { rawPath -> relativePathWithinProject(rawPath, root) }
            .firstOrNull()
            ?: return null

        return "$reasonPrefix:$relativePath"
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

    companion object {
        fun getInstance(project: Project): RelatedFileDiscovery {
            return project.service()
        }
    }
}
