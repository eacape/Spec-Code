package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.CodeGraphEdge
import com.eacape.speccodingplugin.context.CodeGraphEdgeType
import com.eacape.speccodingplugin.context.CodeGraphNode
import com.eacape.speccodingplugin.context.CodeGraphNodeType
import com.eacape.speccodingplugin.context.CodeGraphSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowCodeGraphUiHostTest {

    @Test
    fun `requestShow should render dialog and report generated status when snapshot has edges`() {
        val recorder = RecordingEnvironment().apply {
            buildResult = Result.success(
                snapshot(
                    edges = listOf(
                        CodeGraphEdge(
                            fromId = "file:Main.kt",
                            toId = "symbol:helper",
                            type = CodeGraphEdgeType.CALLS,
                        ),
                    ),
                ),
            )
        }

        host(recorder).requestShow()

        assertEquals(1, recorder.backgroundCalls)
        assertEquals(
            listOf(
                SpecCodingBundle.message("code.graph.status.generating"),
                SpecCodingBundle.message("code.graph.status.generated", 2, 1),
            ),
            recorder.statusTexts,
        )
        assertEquals(1, recorder.dialogRequests.size)
        assertTrue(recorder.dialogRequests.single().summary.contains("Root: Main.kt"))
        assertTrue(recorder.dialogRequests.single().mermaid.contains("graph TD"))
    }

    @Test
    fun `requestShow should report empty status and skip dialog when snapshot has no edges`() {
        val recorder = RecordingEnvironment().apply {
            buildResult = Result.success(snapshot(edges = emptyList()))
        }

        host(recorder).requestShow()

        assertEquals(
            listOf(
                SpecCodingBundle.message("code.graph.status.generating"),
                SpecCodingBundle.message("code.graph.status.empty"),
            ),
            recorder.statusTexts,
        )
        assertTrue(recorder.dialogRequests.isEmpty())
    }

    @Test
    fun `requestShow should report failure when graph build fails`() {
        val recorder = RecordingEnvironment().apply {
            buildResult = Result.failure(IllegalStateException("No active editor"))
        }

        host(recorder).requestShow()

        assertEquals(
            listOf(
                SpecCodingBundle.message("code.graph.status.generating"),
                SpecCodingBundle.message("code.graph.status.failed", "No active editor"),
            ),
            recorder.statusTexts,
        )
        assertTrue(recorder.dialogRequests.isEmpty())
    }

    @Test
    fun `requestShow should report failure when dialog presenter throws`() {
        val recorder = RecordingEnvironment().apply {
            buildResult = Result.success(
                snapshot(
                    edges = listOf(
                        CodeGraphEdge(
                            fromId = "file:Main.kt",
                            toId = "file:Helper.kt",
                            type = CodeGraphEdgeType.DEPENDS_ON,
                        ),
                    ),
                ),
            )
            dialogFailure = IllegalStateException("dialog failed")
        }

        host(recorder).requestShow()

        assertEquals(
            listOf(
                SpecCodingBundle.message("code.graph.status.generating"),
                SpecCodingBundle.message("code.graph.status.failed", "dialog failed"),
            ),
            recorder.statusTexts,
        )
        assertTrue(recorder.dialogRequests.isEmpty())
    }

    private fun host(recorder: RecordingEnvironment): SpecWorkflowCodeGraphUiHost {
        return SpecWorkflowCodeGraphUiHost(
            buildCodeGraph = { recorder.buildResult },
            runBackground = { task, onResult ->
                recorder.backgroundCalls += 1
                onResult(task())
            },
            showDialogUi = { request ->
                recorder.dialogFailure?.let { throw it }
                recorder.dialogRequests += request
            },
            setStatusText = { text ->
                recorder.statusTexts += text
            },
        )
    }

    private fun snapshot(edges: List<CodeGraphEdge>): CodeGraphSnapshot {
        return CodeGraphSnapshot(
            generatedAt = 1L,
            rootFilePath = "/project/Main.kt",
            rootFileName = "Main.kt",
            nodes = listOf(
                CodeGraphNode(
                    id = "file:Main.kt",
                    label = "Main.kt",
                    type = CodeGraphNodeType.FILE,
                ),
                CodeGraphNode(
                    id = "symbol:helper",
                    label = "helper",
                    type = CodeGraphNodeType.SYMBOL,
                ),
            ),
            edges = edges,
        )
    }

    private class RecordingEnvironment {
        var buildResult: Result<CodeGraphSnapshot> = Result.failure(IllegalStateException("missing stub"))
        var backgroundCalls: Int = 0
        var dialogFailure: Throwable? = null
        val dialogRequests = mutableListOf<SpecWorkflowCodeGraphDialogRequest>()
        val statusTexts = mutableListOf<String>()
    }
}
