package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class SpecWorkflowPanelVerifyTroubleshootingPlatformTest : BasePlatformTestCase() {

    fun `test verify failure should surface runtime troubleshooting shortcuts`() {
        val expectedSettingsLabel = SpecCodingBundle.message("spec.dialog.troubleshooting.action.openSettings")
        val expectedDemoLabel = SpecCodingBundle.message("spec.dialog.troubleshooting.action.openBundledDemo")
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Verify Troubleshooting",
            description = "surface runtime shortcuts on verify failure",
            template = WorkflowTemplate.QUICK_TASK,
            verifyEnabled = false,
        ).getOrThrow()
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.runVerificationForTest(workflow.id)
        }

        waitUntil {
            panel.currentStatusActionLabelsForTest().contains(expectedSettingsLabel)
        }

        assertTrue(panel.currentStatusTextForTest().isNotBlank())
        assertEquals(
            listOf(
                expectedSettingsLabel,
                expectedDemoLabel,
            ),
            panel.currentStatusActionLabelsForTest(),
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
