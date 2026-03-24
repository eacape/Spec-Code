package com.eacape.speccodingplugin.context

enum class CodeGraphNodeType {
    FILE,
    SYMBOL,
}

enum class CodeGraphEdgeType {
    DEPENDS_ON,
    CALLS,
}

data class CodeGraphNode(
    val id: String,
    val label: String,
    val type: CodeGraphNodeType,
)

data class CodeGraphEdge(
    val fromId: String,
    val toId: String,
    val type: CodeGraphEdgeType,
)

data class CodeGraphSnapshot(
    val generatedAt: Long,
    val rootFilePath: String,
    val rootFileName: String,
    val nodes: List<CodeGraphNode>,
    val edges: List<CodeGraphEdge>,
) {
    fun dependencyEdges(): List<CodeGraphEdge> = edges.filter { it.type == CodeGraphEdgeType.DEPENDS_ON }

    fun callEdges(): List<CodeGraphEdge> = edges.filter { it.type == CodeGraphEdgeType.CALLS }
}

object CodeGraphRenderer {
    fun renderSummary(snapshot: CodeGraphSnapshot): String {
        val dependencies = snapshot.dependencyEdges()
        val callEdges = snapshot.callEdges()

        return buildString {
            appendLine("Root: ${snapshot.rootFileName}")
            appendLine("Nodes: ${snapshot.nodes.size}")
            appendLine("Dependencies: ${dependencies.size}")
            appendLine("Calls: ${callEdges.size}")
            appendLine()
            appendLine("Dependency Edges")
            if (dependencies.isEmpty()) {
                appendLine("- (none)")
            } else {
                dependencies.forEach { edge ->
                    appendLine("- ${edge.fromId} -> ${edge.toId}")
                }
            }
            appendLine()
            appendLine("Call Edges")
            if (callEdges.isEmpty()) {
                appendLine("- (none)")
            } else {
                callEdges.forEach { edge ->
                    appendLine("- ${edge.fromId} -> ${edge.toId}")
                }
            }
        }.trim()
    }

    fun renderMermaid(snapshot: CodeGraphSnapshot): String {
        val nodeMap = snapshot.nodes.associateBy { it.id }
        val lines = mutableListOf<String>()
        lines += "graph TD"

        snapshot.nodes.forEach { node ->
            lines += "  ${safeNodeId(node.id)}[\"${safeLabel(node.label)}\"]"
        }

        snapshot.edges.forEach { edge ->
            val fromNode = nodeMap[edge.fromId] ?: return@forEach
            val toNode = nodeMap[edge.toId] ?: return@forEach
            val connector = when (edge.type) {
                CodeGraphEdgeType.DEPENDS_ON -> "-->|depends|"
                CodeGraphEdgeType.CALLS -> "-->|calls|"
            }
            lines += "  ${safeNodeId(fromNode.id)} $connector ${safeNodeId(toNode.id)}"
        }

        return lines.joinToString("\n")
    }

    private fun safeNodeId(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9_]"), "_")
    }

    private fun safeLabel(raw: String): String {
        return raw.replace("\"", "'").replace("\n", " ").trim()
    }
}
