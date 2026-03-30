package com.eacape.speccodingplugin.context

import com.eacape.speccodingplugin.telemetry.SlowPathBaselineSample
import com.eacape.speccodingplugin.telemetry.emitSlowPathBaseline
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil

@Service(Service.Level.PROJECT)
class CodeGraphService(private val project: Project) {
    private val logger = thisLogger()

    data class GraphBuildOptions(
        val maxDependencies: Int = 20,
        val maxCallEdges: Int = 40,
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

    fun buildFromActiveEditor(options: GraphBuildOptions = GraphBuildOptions()): Result<CodeGraphSnapshot> {
        val startedAt = System.nanoTime()
        val buildState = CodeGraphBuildState(
            maxDependencies = options.maxDependencies,
            maxCallEdges = options.maxCallEdges,
        )

        val result = runCatching {
            ReadAction.compute<CodeGraphSnapshot, Throwable> {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    ?: throw IllegalStateException("No active editor")
                val virtualFile = editor.virtualFile
                    ?: throw IllegalStateException("No active file")
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

                CodeGraphSnapshot(
                    generatedAt = System.currentTimeMillis(),
                    rootFilePath = rootFilePath,
                    rootFileName = virtualFile.name,
                    nodes = nodes.values.toList(),
                    edges = edges.toList(),
                )
            }
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        logBuildTelemetry(
            telemetry = buildState.toTelemetry(
                elapsedMs = elapsedMs,
                outcome = if (result.isSuccess) "success" else "failure",
            ),
            error = result.exceptionOrNull(),
        )
        return result
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
                logger.warn("CodeGraphService build failed: ${telemetry.summary()}", error)
            }
            return
        }

        val message = "CodeGraphService build: ${telemetry.summary()}"
        when {
            severity == ContextTelemetrySeverity.WARN -> logger.warn(message)
            severity == ContextTelemetrySeverity.INFO || telemetry.dependencyLimitHit || telemetry.callLimitHit ->
                logger.info(message)
        }
    }

    private fun isExpectedEditorStateFailure(error: Throwable): Boolean {
        return error is IllegalStateException && error.message in setOf(
            "No active editor",
            "No active file",
            "Cannot resolve PSI file",
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
