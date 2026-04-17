package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ClarificationRetryState
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecStorage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertTrue

class SpecWorkflowPanelClarificationRetryRestorePlatformTest : BasePlatformTestCase() {

    fun `test switching workflow should restore clarification retry timeline from loaded state`() {
        val engine = SpecEngine.getInstance(project)
        val baselineWorkflow = engine.createWorkflow(
            title = "Baseline Workflow",
            description = "baseline workflow for retry restore smoke",
        ).getOrThrow()
        val retryWorkflow = engine.createWorkflow(
            title = "Retry Workflow",
            description = "workflow with retry state for loaded-state restore smoke",
        ).getOrThrow()
        persistRetryState(
            workflowId = retryWorkflow.id,
            clarificationRound = 2,
            lastError = "provider timeout",
        )
        val panel = createPanel()

        waitUntil {
            val workflowIds = panel.workflowIdsForTest().toSet()
            workflowIds.contains(baselineWorkflow.id) && workflowIds.contains(retryWorkflow.id)
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(baselineWorkflow.id)
        }
        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == baselineWorkflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(retryWorkflow.id)
        }
        waitUntil(timeoutMs = 30_000) {
            panel.selectedWorkflowIdForTest() == retryWorkflow.id &&
                panel.isProcessTimelineVisibleForTest() &&
                panel.currentProcessTimelineTextForTest().contains(
                    SpecCodingBundle.message("spec.workflow.process.retryRestored", 2),
                )
        }

        val timelineText = panel.currentProcessTimelineTextForTest()
        assertTrue(timelineText.contains(SpecCodingBundle.message("spec.workflow.process.retryRestored", 2)))
        assertTrue(
            timelineText.contains(
                SpecCodingBundle.message("spec.workflow.process.retryLastError", "provider timeout"),
            ),
        )
    }

    private fun createPanel(): SpecWorkflowPanel {
        var panel: SpecWorkflowPanel? = null
        ApplicationManager.getApplication().invokeAndWait {
            panel = SpecWorkflowPanel(project)
            Disposer.register(testRootDisposable, panel!!)
        }
        return panel ?: error("Failed to create SpecWorkflowPanel")
    }

    private fun persistRetryState(
        workflowId: String,
        clarificationRound: Int,
        lastError: String,
    ) {
        val storage = SpecStorage.getInstance(project)
        val current = storage.loadWorkflow(workflowId).getOrThrow()
        storage.saveWorkflow(
            current.copy(
                clarificationRetryState = ClarificationRetryState(
                    input = "Clarify requirements",
                    confirmedContext = "Reuse previous notes",
                    questionsMarkdown = "## Questions",
                    clarificationRound = clarificationRound,
                    lastError = lastError,
                ),
                updatedAt = System.currentTimeMillis(),
            ),
        ).getOrThrow()
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
