package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecEngine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class SpecWorkflowPanelListActionsPlatformTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        val specEngine = SpecEngine.getInstance(project)
        specEngine.listWorkflows().forEach { workflowId ->
            specEngine.deleteWorkflow(workflowId).getOrThrow()
        }
    }

    fun `test switch workflow popup should open selected workflow`() {
        val specEngine = SpecEngine.getInstance(project)
        val olderWorkflow = specEngine.createWorkflow(
            title = "List Actions Older",
            description = "older workflow",
        ).getOrThrow()
        val latestWorkflow = specEngine.createWorkflow(
            title = "List Actions Latest",
            description = "latest workflow",
        ).getOrThrow()
        val panel = createPanel()

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == latestWorkflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickSwitchWorkflowForTest()
        }

        waitUntil {
            panel.isSwitchWorkflowPopupVisibleForTest()
        }
        assertEquals(listOf(latestWorkflow.id, olderWorkflow.id), panel.switchWorkflowPopupVisibleWorkflowIdsForTest())
        assertEquals(latestWorkflow.id, panel.selectedSwitchWorkflowPopupSelectionForTest())

        ApplicationManager.getApplication().invokeAndWait {
            panel.filterSwitchWorkflowPopupForTest("Older")
        }

        waitUntil {
            panel.switchWorkflowPopupVisibleWorkflowIdsForTest() == listOf(olderWorkflow.id)
        }
        assertEquals(olderWorkflow.id, panel.selectedSwitchWorkflowPopupSelectionForTest())

        ApplicationManager.getApplication().invokeAndWait {
            panel.confirmSwitchWorkflowPopupSelectionForTest()
        }

        waitUntil {
            panel.selectedWorkflowIdForTest() == olderWorkflow.id
        }
        assertEquals(olderWorkflow.id, panel.highlightedWorkflowIdForTest())
    }

    fun `test deleting opened workflow should reopen next recent workflow`() {
        val specEngine = SpecEngine.getInstance(project)
        val olderWorkflow = specEngine.createWorkflow(
            title = "Delete Opened Older",
            description = "first workflow",
        ).getOrThrow()
        val latestWorkflow = specEngine.createWorkflow(
            title = "Delete Opened Latest",
            description = "second workflow",
        ).getOrThrow()
        val panel = createPanel()

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == latestWorkflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.deleteWorkflowForTest(latestWorkflow.id)
        }

        waitUntil {
            panel.isDetailModeForTest() &&
                panel.selectedWorkflowIdForTest() == olderWorkflow.id &&
                panel.highlightedWorkflowIdForTest() == olderWorkflow.id
        }

        assertEquals(listOf(olderWorkflow.id), panel.workflowIdsForTest())
    }

    fun `test deleting workflow from list mode should stay in list mode and clear remaining items incrementally`() {
        val specEngine = SpecEngine.getInstance(project)
        val olderWorkflow = specEngine.createWorkflow(
            title = "Delete List Older",
            description = "first workflow",
        ).getOrThrow()
        val latestWorkflow = specEngine.createWorkflow(
            title = "Delete List Latest",
            description = "second workflow",
        ).getOrThrow()
        val panel = createPanel()

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == latestWorkflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickBackToListForTest()
        }

        waitUntil {
            panel.isListModeForTest() &&
                panel.selectedWorkflowIdForTest() == null &&
                panel.highlightedWorkflowIdForTest() == latestWorkflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.deleteWorkflowForTest(latestWorkflow.id)
        }

        waitUntil {
            panel.isListModeForTest() &&
                panel.selectedWorkflowIdForTest() == null &&
                panel.highlightedWorkflowIdForTest() == olderWorkflow.id
        }
        assertEquals(listOf(olderWorkflow.id), panel.workflowIdsForTest())

        ApplicationManager.getApplication().invokeAndWait {
            panel.deleteWorkflowForTest(olderWorkflow.id)
        }

        waitUntil {
            panel.isListModeForTest() &&
                panel.workflowIdsForTest().isEmpty() &&
                panel.selectedWorkflowIdForTest() == null &&
                panel.highlightedWorkflowIdForTest() == null
        }
    }

    private fun createPanel(): SpecWorkflowPanel {
        var panel: SpecWorkflowPanel? = null
        ApplicationManager.getApplication().invokeAndWait {
            panel = SpecWorkflowPanel(project)
            Disposer.register(testRootDisposable, panel!!)
        }
        return panel ?: error("Failed to create SpecWorkflowPanel")
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
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
