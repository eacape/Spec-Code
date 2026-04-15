package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.CodeGraphEdge
import com.eacape.speccodingplugin.context.CodeGraphEdgeType
import com.eacape.speccodingplugin.context.CodeGraphNode
import com.eacape.speccodingplugin.context.CodeGraphNodeType
import com.eacape.speccodingplugin.context.CodeGraphSnapshot
import com.eacape.speccodingplugin.spec.SpecEngine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class SpecWorkflowPanelCodeGraphPlatformTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        val specEngine = SpecEngine.getInstance(project)
        specEngine.listWorkflows().forEach { workflowId ->
            specEngine.deleteWorkflow(workflowId).getOrThrow()
        }
    }

    fun `test code graph action should build graph and delegate dialog presenter`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Code Graph Workflow",
            description = "code graph smoke",
        ).getOrThrow()
        val snapshot = CodeGraphSnapshot(
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
                    id = "file:Helper.kt",
                    label = "Helper.kt",
                    type = CodeGraphNodeType.FILE,
                ),
                CodeGraphNode(
                    id = "symbol:helper",
                    label = "helper",
                    type = CodeGraphNodeType.SYMBOL,
                ),
            ),
            edges = listOf(
                CodeGraphEdge(
                    fromId = "file:Main.kt",
                    toId = "file:Helper.kt",
                    type = CodeGraphEdgeType.DEPENDS_ON,
                ),
                CodeGraphEdge(
                    fromId = "file:Main.kt",
                    toId = "symbol:helper",
                    type = CodeGraphEdgeType.CALLS,
                ),
            ),
        )

        val dialogRequests = mutableListOf<Pair<String, String>>()
        val panel = createPanel(
            codeGraphBuilder = { Result.success(snapshot) },
            codeGraphDialogPresenter = { summary, mermaid ->
                dialogRequests += summary to mermaid
            },
        )

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickCodeGraphForTest()
        }

        waitUntil {
            dialogRequests.size == 1 &&
                panel.currentStatusTextForTest() ==
                SpecCodingBundle.message("code.graph.status.generated", snapshot.nodes.size, snapshot.edges.size)
        }

        val summary = dialogRequests.single().first
        val mermaid = dialogRequests.single().second
        assertTrue(summary.contains("Root: Main.kt"))
        assertTrue(summary.contains("Dependencies: 1"))
        assertTrue(summary.contains("Calls: 1"))
        assertTrue(mermaid.contains("graph TD"))
        assertEquals(
            SpecCodingBundle.message("code.graph.status.generated", snapshot.nodes.size, snapshot.edges.size),
            panel.currentStatusTextForTest(),
        )
    }

    private fun createPanel(
        codeGraphBuilder: (() -> Result<CodeGraphSnapshot>)? = null,
        codeGraphDialogPresenter: ((String, String) -> Unit)? = null,
    ): SpecWorkflowPanel {
        var panel: SpecWorkflowPanel? = null
        ApplicationManager.getApplication().invokeAndWait {
            panel = SpecWorkflowPanel(
                project,
                codeGraphBuilder = codeGraphBuilder,
                codeGraphDialogPresenter = codeGraphDialogPresenter ?: { _, _ -> },
            )
            Disposer.register(testRootDisposable, panel!!)
        }
        return panel ?: error("Failed to create SpecWorkflowPanel")
    }

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            if (condition()) {
                return
            }
            Thread.sleep(50)
        }
        fail("Condition was not met within ${timeoutMs}ms")
    }
}
