package com.eacape.speccodingplugin.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextGraphPrioritizerTest {

    @Test
    fun `prioritize should boost related file and symbol items`() {
        val snapshot = graphSnapshot()
        val relatedFile = ContextItem(
            type = ContextType.REFERENCED_FILE,
            label = "Service.kt",
            content = "service",
            filePath = "/repo/src/Service.kt",
            priority = 20,
            tokenEstimate = 5,
        )
        val relatedSymbol = ContextItem(
            type = ContextType.REFERENCED_SYMBOL,
            label = "run",
            content = "fun run()",
            filePath = "/repo/src/Main.kt",
            priority = 20,
            tokenEstimate = 5,
        )
        val unrelated = ContextItem(
            type = ContextType.REFERENCED_FILE,
            label = "Other.kt",
            content = "other",
            filePath = "/repo/src/Other.kt",
            priority = 60,
            tokenEstimate = 5,
        )

        val prioritized = ContextGraphPrioritizer.prioritize(
            items = listOf(relatedFile, relatedSymbol, unrelated),
            graph = snapshot,
        )

        val byLabel = prioritized.associateBy { it.label }
        assertTrue((byLabel["Service.kt"]?.priority ?: 0) > (byLabel["Other.kt"]?.priority ?: 0))
        assertTrue((byLabel["run"]?.priority ?: 0) > 20)
    }

    @Test
    fun `prioritize should keep item priorities when no relation found`() {
        val snapshot = graphSnapshot()
        val item = ContextItem(
            type = ContextType.REFERENCED_FILE,
            label = "Unrelated.kt",
            content = "x",
            filePath = "/repo/src/Unrelated.kt",
            priority = 42,
            tokenEstimate = 5,
        )

        val prioritized = ContextGraphPrioritizer.prioritize(
            items = listOf(item),
            graph = snapshot,
        )

        assertEquals(42, prioritized.first().priority)
    }

    private fun graphSnapshot(): CodeGraphSnapshot {
        return CodeGraphSnapshot(
            generatedAt = 1L,
            rootFilePath = "/repo/src/Main.kt",
            rootFileName = "Main.kt",
            nodes = listOf(
                CodeGraphNode("file:/repo/src/Main.kt", "Main.kt", CodeGraphNodeType.FILE),
                CodeGraphNode("file:/repo/src/Service.kt", "Service.kt", CodeGraphNodeType.FILE),
                CodeGraphNode("symbol:main@10", "main", CodeGraphNodeType.SYMBOL),
                CodeGraphNode("symbol:run@20", "run", CodeGraphNodeType.SYMBOL),
            ),
            edges = listOf(
                CodeGraphEdge(
                    fromId = "file:/repo/src/Main.kt",
                    toId = "file:/repo/src/Service.kt",
                    type = CodeGraphEdgeType.DEPENDS_ON,
                ),
                CodeGraphEdge(
                    fromId = "symbol:main@10",
                    toId = "symbol:run@20",
                    type = CodeGraphEdgeType.CALLS,
                ),
            ),
        )
    }
}
