package com.eacape.speccodingplugin.context

object ContextGraphPrioritizer {

    fun prioritize(
        items: List<ContextItem>,
        graph: CodeGraphSnapshot,
    ): List<ContextItem> {
        if (items.isEmpty()) {
            return items
        }

        val rootFileId = "file:${graph.rootFilePath}"
        val nodeById = graph.nodes.associateBy { it.id }

        val relatedFilePaths = graph.dependencyEdges()
            .filter { it.fromId == rootFileId }
            .mapNotNull { edge -> nodeById[edge.toId] }
            .filter { it.type == CodeGraphNodeType.FILE }
            .mapNotNull { extractFilePath(it.id) }
            .toSet()

        val relatedSymbolLabels = graph.callEdges()
            .flatMap { edge ->
                listOfNotNull(
                    nodeById[edge.fromId]?.label,
                    nodeById[edge.toId]?.label,
                )
            }
            .toSet()

        return items.map { item ->
            val boosted = item.priority + computeBoost(
                item = item,
                rootFilePath = graph.rootFilePath,
                relatedFilePaths = relatedFilePaths,
                relatedSymbolLabels = relatedSymbolLabels,
            )
            item.copy(priority = boosted.coerceIn(0, 100))
        }
    }

    private fun computeBoost(
        item: ContextItem,
        rootFilePath: String,
        relatedFilePaths: Set<String>,
        relatedSymbolLabels: Set<String>,
    ): Int {
        val itemPath = item.filePath
        return when {
            itemPath != null && itemPath == rootFilePath -> 55
            itemPath != null && itemPath in relatedFilePaths -> 65
            item.type == ContextType.REFERENCED_SYMBOL && item.label in relatedSymbolLabels -> 60
            item.type == ContextType.PROJECT_STRUCTURE -> -20
            else -> 0
        }
    }

    private fun extractFilePath(nodeId: String): String? {
        return if (nodeId.startsWith("file:")) {
            nodeId.removePrefix("file:")
        } else {
            null
        }
    }
}
