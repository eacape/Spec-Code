package com.eacape.speccodingplugin.context

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodeGraphRendererTest {

    @Test
    fun `renderSummary should include root and edge counts`() {
        val snapshot = CodeGraphSnapshot(
            generatedAt = 1L,
            rootFilePath = "/repo/src/Main.kt",
            rootFileName = "Main.kt",
            nodes = listOf(
                CodeGraphNode(id = "file:/repo/src/Main.kt", label = "Main.kt", type = CodeGraphNodeType.FILE),
                CodeGraphNode(id = "file:/repo/src/Dep.kt", label = "Dep.kt", type = CodeGraphNodeType.FILE),
                CodeGraphNode(id = "symbol:run@10", label = "run", type = CodeGraphNodeType.SYMBOL),
                CodeGraphNode(id = "symbol:work@20", label = "work", type = CodeGraphNodeType.SYMBOL),
            ),
            edges = listOf(
                CodeGraphEdge(
                    fromId = "file:/repo/src/Main.kt",
                    toId = "file:/repo/src/Dep.kt",
                    type = CodeGraphEdgeType.DEPENDS_ON,
                ),
                CodeGraphEdge(
                    fromId = "symbol:run@10",
                    toId = "symbol:work@20",
                    type = CodeGraphEdgeType.CALLS,
                ),
            ),
        )

        val summary = CodeGraphRenderer.renderSummary(snapshot)
        assertTrue(summary.contains("Root: Main.kt"))
        assertTrue(summary.contains("Dependencies: 1"))
        assertTrue(summary.contains("Calls: 1"))
        assertTrue(summary.contains("symbol:run@10 -> symbol:work@20"))
    }

    @Test
    fun `renderMermaid should sanitize ids and labels`() {
        val snapshot = CodeGraphSnapshot(
            generatedAt = 1L,
            rootFilePath = "/repo/Main.kt",
            rootFileName = "Main.kt",
            nodes = listOf(
                CodeGraphNode(
                    id = "file:/repo/Main.kt",
                    label = "Main \"Entry\"",
                    type = CodeGraphNodeType.FILE,
                ),
                CodeGraphNode(
                    id = "symbol:run@10",
                    label = "run",
                    type = CodeGraphNodeType.SYMBOL,
                ),
            ),
            edges = listOf(
                CodeGraphEdge(
                    fromId = "file:/repo/Main.kt",
                    toId = "symbol:run@10",
                    type = CodeGraphEdgeType.CALLS,
                ),
            ),
        )

        val mermaid = CodeGraphRenderer.renderMermaid(snapshot)
        assertTrue(mermaid.contains("graph TD"))
        assertTrue(mermaid.contains("file__repo_Main_kt"))
        assertTrue(mermaid.contains("Main 'Entry'"))
        assertTrue(mermaid.contains("-->|calls|"))
    }
}
