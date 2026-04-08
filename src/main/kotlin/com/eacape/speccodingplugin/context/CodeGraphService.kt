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
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class CodeGraphService(private val project: Project) : Disposable {
    private val logger = thisLogger()
    private val projectRootPath: Path? = runCatching {
        project.basePath
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it).toAbsolutePath().normalize() }
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
        val psiModificationCount: Long,
    )

    private data class CodeGraphCacheKey(
        val rootFilePath: String,
        val options: GraphBuildOptions,
    )

    private data class CachedCodeGraphSnapshot(
        val snapshot: CodeGraphSnapshot,
        val psiModificationCount: Long,
    )

    private data class BuiltCodeGraphSnapshot(
        val snapshot: CodeGraphSnapshot,
        val psiModificationCount: Long,
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
            if (cached.psiModificationCount == request.psiModificationCount) {
                val hitCount = cacheHitCount.incrementAndGet()
                logCacheHit(
                    request = request,
                    options = options,
                    cacheStats = currentCacheStats(hitCount = hitCount),
                )
                return Result.success(cached.snapshot)
            }
            cacheStatus = "stale-psi"
            invalidateCacheEntry(
                cacheKey = cacheKey,
                reason = "psi-change:${request.rootFileName}",
            )
        }

        val missCount = cacheMissCount.incrementAndGet()
        val buildResult = buildSnapshotForActiveEditor(
            expectedRootFilePath = request.rootFilePath,
            options = options,
            buildState = buildState,
        )
        buildResult.onSuccess { built ->
            cachedSnapshots[cacheKey] = CachedCodeGraphSnapshot(
                snapshot = built.snapshot,
                psiModificationCount = built.psiModificationCount,
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
                psiModificationCount = PsiModificationTracker.getInstance(project).modificationCount,
            )
        }
    }

    private fun buildSnapshotForActiveEditor(
        expectedRootFilePath: String,
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
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    ?: throw IllegalStateException("Cannot resolve PSI file")

                val nodes = linkedMapOf<String, CodeGraphNode>()
                val edges = linkedSetOf<CodeGraphEdge>()

                val rootFilePath = virtualFile.path
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
                    psiModificationCount = PsiModificationTracker.getInstance(project).modificationCount,
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

    private fun invalidateCacheEntry(
        cacheKey: CodeGraphCacheKey,
        reason: String,
    ) {
        if (cachedSnapshots.remove(cacheKey) != null) {
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
