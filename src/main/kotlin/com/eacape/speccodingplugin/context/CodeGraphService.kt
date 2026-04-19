package com.eacape.speccodingplugin.context

import com.eacape.speccodingplugin.telemetry.SlowPathBaselineSample
import com.eacape.speccodingplugin.telemetry.emitSlowPathBaseline
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class CodeGraphService(private val project: Project) : Disposable {
    private val logger = thisLogger()
    private val projectRootLocator: ProjectRootLocator? = runCatching {
        val rootPath = project.guessProjectDir()?.path
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: project.basePath
                ?.trim()
                ?.takeIf(String::isNotBlank)
        rootPath?.let { rawPath ->
            val normalizedPath = normalizePathString(rawPath)
            ProjectRootLocator(
                normalizedPath = normalizedPath,
                localPath = toLocalPathOrNull(normalizedPath),
            )
        }
    }.getOrNull()
    private val cachedSnapshots = ConcurrentHashMap<CodeGraphCacheKey, CachedCodeGraphSnapshot>()

    @Volatile
    private var lastInvalidationReason: String = "cold-start"

    private val cacheHitCount = AtomicLong(0)
    private val cacheMissCount = AtomicLong(0)

    data class GraphBuildOptions(
        val maxDependencies: Int = 20,
        val maxCallEdges: Int = 40,
    )

    private data class ActiveEditorGraphRequest(
        val rootFilePath: String,
        val rootFileName: String,
        val rootContentModificationStamp: Long,
    )

    private data class CodeGraphCacheKey(
        val rootFilePath: String,
        val options: GraphBuildOptions,
    )

    private data class CachedCodeGraphSnapshot(
        val snapshot: CodeGraphSnapshot,
        val rootContentModificationStamp: Long,
        val trackedFilePaths: Set<String>,
    )

    private data class BuiltCodeGraphSnapshot(
        val snapshot: CodeGraphSnapshot,
        val rootContentModificationStamp: Long,
        val trackedFilePaths: Set<String>,
    )

    private data class DependencyCollectionStats(
        val edgeCount: Int,
        val referenceScanCount: Int,
        val limitHit: Boolean,
    )

    private data class CallCollectionStats(
        val edgeCount: Int,
        val referenceScanCount: Int,
        val namedElementCount: Int,
        val limitHit: Boolean,
    )

    private data class CodeGraphBuildState(
        val maxDependencies: Int,
        val maxCallEdges: Int,
        var rootFilePath: String? = null,
        var rootFileName: String? = null,
        var nodeCount: Int = 0,
        var edgeCount: Int = 0,
        var dependencyEdgeCount: Int = 0,
        var callEdgeCount: Int = 0,
        var dependencyReferenceScans: Int = 0,
        var callReferenceScans: Int = 0,
        var namedElementCount: Int = 0,
        var dependencyLimitHit: Boolean = false,
        var callLimitHit: Boolean = false,
    ) {
        fun toTelemetry(elapsedMs: Long, outcome: String): CodeGraphBuildTelemetry {
            return CodeGraphBuildTelemetry(
                rootFilePath = rootFilePath,
                rootFileName = rootFileName,
                trigger = "active-editor",
                elapsedMs = elapsedMs,
                nodeCount = nodeCount,
                edgeCount = edgeCount,
                dependencyEdgeCount = dependencyEdgeCount,
                callEdgeCount = callEdgeCount,
                dependencyReferenceScans = dependencyReferenceScans,
                callReferenceScans = callReferenceScans,
                namedElementCount = namedElementCount,
                maxDependencies = maxDependencies,
                maxCallEdges = maxCallEdges,
                dependencyLimitHit = dependencyLimitHit,
                callLimitHit = callLimitHit,
                outcome = outcome,
            )
        }
    }

    init {
        subscribeToStructureChanges()
    }

    fun buildFromActiveEditor(options: GraphBuildOptions = GraphBuildOptions()): Result<CodeGraphSnapshot> {
        val startedAt = System.nanoTime()
        val buildState = CodeGraphBuildState(
            maxDependencies = options.maxDependencies,
            maxCallEdges = options.maxCallEdges,
        )

        val request = runCatching { resolveActiveEditorRequest() }.getOrElse { error ->
            logBuildTelemetry(
                telemetry = buildState.toTelemetry(elapsedMs = elapsedMsSince(startedAt), outcome = "failure"),
                error = error,
                cacheStatus = "miss",
                cacheStats = currentCacheStats(),
            )
            return Result.failure(error)
        }

        buildState.rootFilePath = request.rootFilePath
        buildState.rootFileName = request.rootFileName
        val cacheKey = CodeGraphCacheKey(
            rootFilePath = request.rootFilePath,
            options = options,
        )

        var cacheStatus = "miss"
        cachedSnapshots[cacheKey]?.let { cached ->
            if (cached.rootContentModificationStamp == request.rootContentModificationStamp) {
                val hitCount = cacheHitCount.incrementAndGet()
                logCacheHit(
                    request = request,
                    options = options,
                    cacheStats = currentCacheStats(hitCount = hitCount),
                )
                return Result.success(cached.snapshot)
            }
            cacheStatus = "stale-root-content"
            invalidateCacheEntry(
                cacheKey = cacheKey,
                reason = "root-content-change:${request.rootFileName}",
            )
        }

        val missCount = cacheMissCount.incrementAndGet()
        val buildResult = buildSnapshotForActiveEditor(
            expectedRootFilePath = request.rootFilePath,
            expectedRootContentModificationStamp = request.rootContentModificationStamp,
            options = options,
            buildState = buildState,
        )
        buildResult.onSuccess { built ->
            cachedSnapshots[cacheKey] = CachedCodeGraphSnapshot(
                snapshot = built.snapshot,
                rootContentModificationStamp = built.rootContentModificationStamp,
                trackedFilePaths = built.trackedFilePaths,
            )
        }

        logBuildTelemetry(
            telemetry = buildState.toTelemetry(
                elapsedMs = elapsedMsSince(startedAt),
                outcome = if (buildResult.isSuccess) "success" else "failure",
            ),
            error = buildResult.exceptionOrNull(),
            cacheStatus = cacheStatus,
            cacheStats = currentCacheStats(missCount = missCount),
        )
        return buildResult.map { built -> built.snapshot }
    }

    override fun dispose() = Unit

    private fun resolveActiveEditorRequest(): ActiveEditorGraphRequest {
        return ReadAction.compute<ActiveEditorGraphRequest, Throwable> {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
                ?: throw IllegalStateException("No active editor")
            val virtualFile = editor.virtualFile
                ?: throw IllegalStateException("No active file")
            ActiveEditorGraphRequest(
                rootFilePath = virtualFile.path,
                rootFileName = virtualFile.name,
                rootContentModificationStamp = editor.document.modificationStamp,
            )
        }
    }

    private fun buildSnapshotForActiveEditor(
        expectedRootFilePath: String,
        expectedRootContentModificationStamp: Long,
        options: GraphBuildOptions,
        buildState: CodeGraphBuildState,
    ): Result<BuiltCodeGraphSnapshot> {
        return runCatching {
            ReadAction.compute<BuiltCodeGraphSnapshot, Throwable> {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    ?: throw IllegalStateException("No active editor")
                val virtualFile = editor.virtualFile
                    ?: throw IllegalStateException("No active file")
                if (virtualFile.path != expectedRootFilePath) {
                    throw IllegalStateException("Active file changed during graph build")
                }
                if (editor.document.modificationStamp != expectedRootContentModificationStamp) {
                    throw IllegalStateException("Active file changed during graph build")
                }
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    ?: throw IllegalStateException("Cannot resolve PSI file")

                val nodes = linkedMapOf<String, CodeGraphNode>()
                val edges = linkedSetOf<CodeGraphEdge>()
                val trackedFilePaths = linkedSetOf<String>()

                val rootFilePath = virtualFile.path
                trackedFilePaths += rootFilePath
                val rootFileId = fileNodeId(rootFilePath)
                buildState.rootFilePath = rootFilePath
                buildState.rootFileName = virtualFile.name
                nodes[rootFileId] = CodeGraphNode(
                    id = rootFileId,
                    label = virtualFile.name,
                    type = CodeGraphNodeType.FILE,
                )

                val dependencyStats = collectDependencyEdges(
                    rootFilePath = rootFilePath,
                    rootFileId = rootFileId,
                    rootFileLabel = virtualFile.name,
                    psiFile = psiFile,
                    nodes = nodes,
                    edges = edges,
                    trackedFilePaths = trackedFilePaths,
                    maxDependencies = options.maxDependencies,
                )
                buildState.dependencyEdgeCount = dependencyStats.edgeCount
                buildState.dependencyReferenceScans = dependencyStats.referenceScanCount
                buildState.dependencyLimitHit = dependencyStats.limitHit

                val callStats = collectCallEdges(
                    rootFilePath = rootFilePath,
                    psiFile = psiFile,
                    nodes = nodes,
                    edges = edges,
                    maxCallEdges = options.maxCallEdges,
                )
                buildState.callEdgeCount = callStats.edgeCount
                buildState.callReferenceScans = callStats.referenceScanCount
                buildState.namedElementCount = callStats.namedElementCount
                buildState.callLimitHit = callStats.limitHit
                buildState.nodeCount = nodes.size
                buildState.edgeCount = edges.size

                BuiltCodeGraphSnapshot(
                    snapshot = CodeGraphSnapshot(
                        generatedAt = System.currentTimeMillis(),
                        rootFilePath = rootFilePath,
                        rootFileName = virtualFile.name,
                        nodes = nodes.values.toList(),
                        edges = edges.toList(),
                    ),
                    rootContentModificationStamp = editor.document.modificationStamp,
                    trackedFilePaths = trackedFilePaths.toSet(),
                )
            }
        }
    }

    private fun collectDependencyEdges(
        rootFilePath: String,
        rootFileId: String,
        rootFileLabel: String,
        psiFile: PsiElement,
        nodes: MutableMap<String, CodeGraphNode>,
        edges: MutableSet<CodeGraphEdge>,
        trackedFilePaths: MutableSet<String>,
        maxDependencies: Int,
    ): DependencyCollectionStats {
        var dependencyCount = 0
        var referenceScanCount = 0
        outer@ for (element in PsiTreeUtil.collectElements(psiFile) { true }) {
            for (reference in element.references) {
                if (dependencyCount >= maxDependencies) {
                    break@outer
                }
                referenceScanCount += 1
                val targetFile = reference.resolve()?.containingFile?.virtualFile ?: continue
                if (targetFile.path == rootFilePath) {
                    continue
                }
                trackedFilePaths += targetFile.path

                val targetFileId = fileNodeId(targetFile.path)
                nodes.putIfAbsent(
                    targetFileId,
                    CodeGraphNode(
                        id = targetFileId,
                        label = targetFile.name,
                        type = CodeGraphNodeType.FILE,
                    ),
                )
                nodes.putIfAbsent(
                    rootFileId,
                    CodeGraphNode(
                        id = rootFileId,
                        label = rootFileLabel,
                        type = CodeGraphNodeType.FILE,
                    ),
                )

                if (edges.add(CodeGraphEdge(rootFileId, targetFileId, CodeGraphEdgeType.DEPENDS_ON))) {
                    dependencyCount += 1
                }
            }
        }
        return DependencyCollectionStats(
            edgeCount = dependencyCount,
            referenceScanCount = referenceScanCount,
            limitHit = dependencyCount >= maxDependencies,
        )
    }

    private fun collectCallEdges(
        rootFilePath: String,
        psiFile: PsiElement,
        nodes: MutableMap<String, CodeGraphNode>,
        edges: MutableSet<CodeGraphEdge>,
        maxCallEdges: Int,
    ): CallCollectionStats {
        val namedElements = PsiTreeUtil.findChildrenOfType(psiFile, PsiNamedElement::class.java)
            .filter { !it.name.isNullOrBlank() }
            .filter { it.containingFile?.virtualFile?.path == rootFilePath }
            .toList()
        if (namedElements.isEmpty()) {
            return CallCollectionStats(
                edgeCount = 0,
                referenceScanCount = 0,
                namedElementCount = 0,
                limitHit = false,
            )
        }

        val symbolIdByOffset = namedElements.associateBy(
            keySelector = { symbolKey(it) },
            valueTransform = { symbolNodeId(it) },
        )

        namedElements.forEach { named ->
            val nodeId = symbolNodeId(named)
            nodes.putIfAbsent(
                nodeId,
                CodeGraphNode(
                    id = nodeId,
                    label = named.name ?: "anonymous",
                    type = CodeGraphNodeType.SYMBOL,
                ),
            )
        }

        var callCount = 0
        var referenceScanCount = 0
        outer@ for (element in PsiTreeUtil.collectElements(psiFile) { true }) {
            for (reference in element.references) {
                if (callCount >= maxCallEdges) {
                    break@outer
                }
                referenceScanCount += 1

                val target = reference.resolve() as? PsiNamedElement ?: continue
                if (target.containingFile?.virtualFile?.path != rootFilePath) {
                    continue
                }

                val caller = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java, false)
                    ?: continue
                if (caller.containingFile?.virtualFile?.path != rootFilePath) {
                    continue
                }

                val callerId = symbolIdByOffset[symbolKey(caller)] ?: continue
                val targetId = symbolIdByOffset[symbolKey(target)] ?: continue
                if (callerId == targetId) {
                    continue
                }

                if (edges.add(CodeGraphEdge(callerId, targetId, CodeGraphEdgeType.CALLS))) {
                    callCount += 1
                }
            }
        }
        return CallCollectionStats(
            edgeCount = callCount,
            referenceScanCount = referenceScanCount,
            namedElementCount = namedElements.size,
            limitHit = callCount >= maxCallEdges,
        )
    }

    private fun logBuildTelemetry(
        telemetry: CodeGraphBuildTelemetry,
        error: Throwable?,
        cacheStatus: String,
        cacheStats: CodeGraphCacheStats,
    ) {
        emitSlowPathBaseline(
            logger = logger,
            sample = SlowPathBaselineSample(
                operationKey = "CodeGraphService.buildFromActiveEditor",
                elapsedMs = telemetry.elapsedMs,
            ),
        )
        val severity = determineContextTelemetrySeverity(telemetry.elapsedMs)
        if (error != null) {
            if (!isExpectedEditorStateFailure(error) || severity != ContextTelemetrySeverity.SKIP) {
                logger.warn(
                    "CodeGraphService build failed: ${telemetry.summary()}, cacheStatus=$cacheStatus, ${cacheStats.summary()}",
                    error,
                )
            }
            return
        }

        val message =
            "CodeGraphService build: ${telemetry.summary()}, cacheStatus=$cacheStatus, ${cacheStats.summary()}"
        when {
            severity == ContextTelemetrySeverity.WARN -> logger.warn(message)
            severity == ContextTelemetrySeverity.INFO || telemetry.dependencyLimitHit || telemetry.callLimitHit ->
                logger.info(message)
        }
    }

    private fun logCacheHit(
        request: ActiveEditorGraphRequest,
        options: GraphBuildOptions,
        cacheStats: CodeGraphCacheStats,
    ) {
        if (!cacheStats.shouldEmitPeriodicHitLog()) {
            return
        }
        logger.info(
            "CodeGraphService cache hit: file=${request.rootFileName}, path=${request.rootFilePath}, " +
                "maxDependencies=${options.maxDependencies}, maxCallEdges=${options.maxCallEdges}, ${cacheStats.summary()}",
        )
    }

    private fun currentCacheStats(
        hitCount: Long = cacheHitCount.get(),
        missCount: Long = cacheMissCount.get(),
    ): CodeGraphCacheStats {
        return CodeGraphCacheStats(
            hitCount = hitCount,
            missCount = missCount,
            lastInvalidationReason = lastInvalidationReason,
        )
    }

    internal fun cacheStatsSnapshot(): CodeGraphCacheStats = currentCacheStats()

    private fun invalidateCache(reason: String) {
        val hadCache = cachedSnapshots.isNotEmpty()
        cachedSnapshots.clear()
        lastInvalidationReason = reason
        if (hadCache) {
            logger.info("CodeGraphService cache invalidated: reason=$reason, ${currentCacheStats().summary()}")
        }
    }

    private fun invalidateCacheEntries(
        affectedPaths: Set<String>,
        reason: String,
    ) {
        if (affectedPaths.isEmpty()) {
            return
        }
        val normalizedPaths = affectedPaths.mapTo(linkedSetOf()) { it.trim() }
        val keysToInvalidate = cachedSnapshots.entries
            .mapNotNull { (key, cached) ->
                if (cached.trackedFilePaths.any(normalizedPaths::contains)) {
                    key
                } else {
                    null
                }
            }
        if (keysToInvalidate.isEmpty()) {
            return
        }
        keysToInvalidate.forEach(cachedSnapshots::remove)
        lastInvalidationReason = reason
        logger.info(
            "CodeGraphService cache invalidated selectively: reason=$reason, " +
                "affectedPaths=${normalizedPaths.joinToString(separator = "|")}, " +
                "invalidatedEntries=${keysToInvalidate.size}, ${currentCacheStats().summary()}",
        )
    }

    private fun invalidateCacheEntry(
        cacheKey: CodeGraphCacheKey,
        reason: String,
    ) {
        if (cachedSnapshots.remove(cacheKey) != null) {
            lastInvalidationReason = reason
        }
    }

    private fun subscribeToStructureChanges() {
        if (projectRootLocator == null) {
            return
        }
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    applyStructureChangeInvalidation(events)
                }
            },
        )
    }

    private data class StructureChangeImpact(
        val reason: String,
        val requiresFullInvalidation: Boolean,
        val affectedPaths: Set<String> = emptySet(),
    )

    private data class ProjectRootLocator(
        val normalizedPath: String,
        val localPath: Path?,
    )

    private fun applyStructureChangeInvalidation(events: List<VFileEvent>) {
        val root = projectRootLocator ?: return
        var fullInvalidationReason: String? = null
        var selectiveInvalidationReason: String? = null
        val selectiveAffectedPaths = linkedSetOf<String>()

        events.asSequence()
            .mapNotNull { event -> structureChangeImpact(event, root) }
            .forEach { impact ->
                if (impact.requiresFullInvalidation) {
                    if (fullInvalidationReason == null) {
                        fullInvalidationReason = impact.reason
                    }
                } else if (impact.affectedPaths.isNotEmpty()) {
                    if (selectiveInvalidationReason == null) {
                        selectiveInvalidationReason = impact.reason
                    }
                    selectiveAffectedPaths += impact.affectedPaths
                }
            }

        when {
            fullInvalidationReason != null -> invalidateCache(fullInvalidationReason!!)
            selectiveInvalidationReason != null -> invalidateCacheEntries(
                affectedPaths = selectiveAffectedPaths,
                reason = selectiveInvalidationReason!!,
            )
        }
    }

    private fun structureChangeImpact(event: VFileEvent, root: ProjectRootLocator): StructureChangeImpact? {
        val classification = when (event) {
            is VFileCreateEvent -> "vfs-create" to true
            is VFileCopyEvent -> "vfs-copy" to true
            is VFileDeleteEvent -> "vfs-delete" to false
            is VFileMoveEvent -> "vfs-move" to false
            is VFilePropertyChangeEvent -> if (event.isRename) {
                "vfs-rename" to false
            } else {
                null
            }
            else -> null
        } ?: return null
        val (reasonPrefix, requiresFullInvalidation) = classification

        val affectedPaths = affectedPathsForEvent(event)
            .asSequence()
            .mapNotNull { rawPath -> normalizedPathWithinProject(rawPath, root) }
            .toCollection(linkedSetOf())
        val relativePath = affectedPaths.firstOrNull()
            ?.let { normalizedPath -> relativePathWithinProject(normalizedPath, root) }
            ?: return null

        return StructureChangeImpact(
            reason = "$reasonPrefix:$relativePath",
            requiresFullInvalidation = requiresFullInvalidation,
            affectedPaths = affectedPaths,
        )
    }

    private fun affectedPathsForEvent(event: VFileEvent): List<String> {
        return when (event) {
            is VFileMoveEvent -> listOfNotNull(event.oldPath, event.newPath, event.file?.path)
            is VFilePropertyChangeEvent -> {
                if (event.isRename) {
                    val parentPath = event.file?.parent?.path
                    val oldName = event.oldValue as? String
                    val newName = event.newValue as? String
                    listOfNotNull(
                        parentPath?.let { path -> oldName?.let { "$path/$it" } },
                        parentPath?.let { path -> newName?.let { "$path/$it" } },
                        event.oldPath,
                        event.newPath,
                        event.file?.path,
                    )
                } else {
                    listOfNotNull(event.oldPath, event.newPath, event.file?.path)
                }
            }
            else -> listOf(event.path)
        }
    }

    private fun normalizedPathWithinProject(rawPath: String, root: ProjectRootLocator): String? {
        val normalizedRawPath = normalizePathString(rawPath)
        if (normalizedRawPath.isEmpty()) {
            return null
        }

        val rootLocalPath = root.localPath
        val candidateLocalPath = toLocalPathOrNull(normalizedRawPath)
        if (rootLocalPath != null && candidateLocalPath != null) {
            if (!candidateLocalPath.startsWith(rootLocalPath)) {
                return null
            }
            return candidateLocalPath.toString()
        }

        return if (normalizedRawPath == root.normalizedPath || normalizedRawPath.startsWith("${root.normalizedPath}/")) {
            normalizedRawPath
        } else {
            null
        }
    }

    private fun relativePathWithinProject(rawPath: String, root: ProjectRootLocator): String? {
        val normalizedPath = normalizedPathWithinProject(rawPath, root)
            ?: return null
        val rootLocalPath = root.localPath
        val candidateLocalPath = toLocalPathOrNull(normalizedPath)
        val relativePath = if (rootLocalPath != null && candidateLocalPath != null) {
            rootLocalPath.relativize(candidateLocalPath)
                .joinToString(separator = "/") { segment -> segment.toString() }
                .trim()
        } else {
            normalizedPath.removePrefix(root.normalizedPath)
                .trimStart('/')
                .trim()
        }
        if (relativePath.isEmpty() || relativePath == ".") {
            return null
        }
        return relativePath
    }

    private fun normalizePathString(rawPath: String): String {
        return rawPath.trim()
            .replace('\\', '/')
            .trimEnd('/')
    }

    private fun toLocalPathOrNull(rawPath: String): Path? {
        if (rawPath.contains("://")) {
            return null
        }
        return try {
            Path.of(rawPath).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun elapsedMsSince(startedAt: Long): Long {
        return (System.nanoTime() - startedAt) / 1_000_000
    }

    private fun isExpectedEditorStateFailure(error: Throwable): Boolean {
        return error is IllegalStateException && error.message in setOf(
            "No active editor",
            "No active file",
            "Cannot resolve PSI file",
            "Active file changed during graph build",
        )
    }

    private fun fileNodeId(path: String): String = "file:$path"

    private fun symbolNodeId(element: PsiNamedElement): String = "symbol:${symbolKey(element)}"

    private fun symbolKey(element: PsiNamedElement): String {
        val name = element.name ?: "anonymous"
        return "$name@${element.textRange.startOffset}"
    }

    companion object {
        fun getInstance(project: Project): CodeGraphService = project.service()
    }
}
