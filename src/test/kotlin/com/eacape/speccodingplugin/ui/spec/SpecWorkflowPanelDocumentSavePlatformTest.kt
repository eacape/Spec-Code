package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class SpecWorkflowPanelDocumentSavePlatformTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        val specEngine = SpecEngine.getInstance(project)
        specEngine.listWorkflows().forEach { workflowId ->
            specEngine.deleteWorkflow(workflowId).getOrThrow()
        }
    }

    fun `test document save should persist workflow document through document save coordinator`() {
        val specEngine = SpecEngine.getInstance(project)
        val workflow = specEngine.createWorkflow(
            title = "Document Save Workflow",
            description = "document save smoke",
            template = WorkflowTemplate.QUICK_TASK,
        ).getOrThrow()
        val initialContent = """
            ### T-002: rollout
            - [ ] Ship fix
            - [x] Verify smoke
        """.trimIndent()
        val expectedContent = """
            ### T-002: rollout
            - [x] Ship fix
            - [x] Verify smoke
        """.trimIndent()
        specEngine.updateDocumentContent(
            workflowId = workflow.id,
            phase = SpecPhase.IMPLEMENT,
            content = initialContent,
        ).getOrThrow()

        val panel = createPanel()

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.selectDocumentPhaseForTest(SpecPhase.IMPLEMENT)
        }

        waitUntil {
            panel.selectedDocumentPhaseForTest() == SpecPhase.IMPLEMENT.name
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.saveDocumentForTest(
                phase = SpecPhase.IMPLEMENT,
                content = expectedContent,
            )
        }

        waitUntil {
            panel.lastDocumentSaveResultForTest() != null
        }

        val updatedDocument = panel.lastDocumentSaveResultForTest()
            ?.getOrNull()
            ?.documents
            ?.get(SpecPhase.IMPLEMENT)
            ?.content
            .orEmpty()
        val persistedDocument = specEngine.reloadWorkflow(workflow.id)
            .getOrThrow()
            .documents[SpecPhase.IMPLEMENT]
            ?.content
            .orEmpty()

        assertTrue(updatedDocument.startsWith(expectedContent))
        assertTrue(panel.currentDocumentPreviewTextForTest().startsWith(expectedContent))
        assertTrue(persistedDocument.startsWith(expectedContent))
    }

    private fun createPanel(): SpecWorkflowPanel {
        var panel: SpecWorkflowPanel? = null
        ApplicationManager.getApplication().invokeAndWait {
            panel = SpecWorkflowPanel(project)
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
