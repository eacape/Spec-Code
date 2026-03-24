package com.eacape.speccodingplugin.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil

@Service(Service.Level.PROJECT)
class CodeGraphService(private val project: Project) {

    data class GraphBuildOptions(
        val maxDependencies: Int = 20,
        val maxCallEdges: Int = 40,
    )

    fun buildFromActiveEditor(options: GraphBuildOptions = GraphBuildOptions()): Result<CodeGraphSnapshot> {
        return runCatching {
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
                nodes[rootFileId] = CodeGraphNode(
                    id = rootFileId,
                    label = virtualFile.name,
                    type = CodeGraphNodeType.FILE,
                )

                collectDependencyEdges(
                    rootFilePath = rootFilePath,
                    rootFileId = rootFileId,
                    rootFileLabel = virtualFile.name,
                    psiFile = psiFile,
                    nodes = nodes,
                    edges = edges,
                    maxDependencies = options.maxDependencies,
                )

                collectCallEdges(
                    rootFilePath = rootFilePath,
                    psiFile = psiFile,
                    nodes = nodes,
                    edges = edges,
                    maxCallEdges = options.maxCallEdges,
                )

                CodeGraphSnapshot(
                    generatedAt = System.currentTimeMillis(),
                    rootFilePath = rootFilePath,
                    rootFileName = virtualFile.name,
                    nodes = nodes.values.toList(),
                    edges = edges.toList(),
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
    ) {
        var dependencyCount = 0
        PsiTreeUtil.collectElements(psiFile) { true }.forEach { element ->
            if (dependencyCount >= maxDependencies) {
                return
            }
            element.references.forEach { reference ->
                if (dependencyCount >= maxDependencies) {
                    return
                }
                val targetFile = reference.resolve()?.containingFile?.virtualFile ?: return@forEach
                if (targetFile.path == rootFilePath) {
                    return@forEach
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
    }

    private fun collectCallEdges(
        rootFilePath: String,
        psiFile: PsiElement,
        nodes: MutableMap<String, CodeGraphNode>,
        edges: MutableSet<CodeGraphEdge>,
        maxCallEdges: Int,
    ) {
        val namedElements = PsiTreeUtil.findChildrenOfType(psiFile, PsiNamedElement::class.java)
            .filter { !it.name.isNullOrBlank() }
            .filter { it.containingFile?.virtualFile?.path == rootFilePath }
            .toList()
        if (namedElements.isEmpty()) {
            return
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
        PsiTreeUtil.collectElements(psiFile) { true }.forEach { element ->
            if (callCount >= maxCallEdges) {
                return
            }

            element.references.forEach { reference ->
                if (callCount >= maxCallEdges) {
                    return
                }

                val target = reference.resolve() as? PsiNamedElement ?: return@forEach
                if (target.containingFile?.virtualFile?.path != rootFilePath) {
                    return@forEach
                }

                val caller = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java, false)
                    ?: return@forEach
                if (caller.containingFile?.virtualFile?.path != rootFilePath) {
                    return@forEach
                }

                val callerId = symbolIdByOffset[symbolKey(caller)] ?: return@forEach
                val targetId = symbolIdByOffset[symbolKey(target)] ?: return@forEach
                if (callerId == targetId) {
                    return@forEach
                }

                if (edges.add(CodeGraphEdge(callerId, targetId, CodeGraphEdgeType.CALLS))) {
                    callCount += 1
                }
            }
        }
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
