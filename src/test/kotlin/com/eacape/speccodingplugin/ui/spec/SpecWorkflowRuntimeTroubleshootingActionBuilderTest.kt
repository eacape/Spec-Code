package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.engine.CliToolInfo
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadiness
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SpecWorkflowRuntimeTroubleshootingActionBuilderTest {

    @Test
    fun `build should normalize workflow id and delegate to runtime troubleshooting coordinator`() {
        val resolvedWorkflowIds = mutableListOf<String>()
        val builder = SpecWorkflowRuntimeTroubleshootingActionBuilder(
            readinessSnapshot = ::readySnapshot,
            trackingSnapshot = ::emptyTracking,
            resolveTemplate = { workflowId ->
                resolvedWorkflowIds += workflowId
                WorkflowTemplate.FULL_SPEC
            },
        )

        val actions = builder.build(
            workflowId = " wf-1 ",
            trigger = SpecWorkflowRuntimeTroubleshootingTrigger.GENERATION_PRECHECK,
        )

        assertEquals(listOf("wf-1"), resolvedWorkflowIds)
        assertEquals(
            listOf(
                SpecWorkflowTroubleshootingAction.SelectEntry(
                    entry = SpecWorkflowPrimaryEntry.QUICK_TASK,
                    label = SpecCodingBundle.message("spec.dialog.troubleshooting.action.switchToQuickTask"),
                ),
                SpecWorkflowTroubleshootingAction.OpenSettings(
                    label = SpecCodingBundle.message("spec.dialog.troubleshooting.action.openSettings"),
                ),
                SpecWorkflowTroubleshootingAction.OpenBundledDemo(
                    label = SpecCodingBundle.message("spec.dialog.troubleshooting.action.openBundledDemo"),
                ),
            ),
            actions,
        )
    }

    @Test
    fun `build should skip provider lookups when workflow id is blank`() {
        var readinessCalls = 0
        var trackingCalls = 0
        var templateCalls = 0
        val builder = SpecWorkflowRuntimeTroubleshootingActionBuilder(
            readinessSnapshot = {
                readinessCalls += 1
                readySnapshot()
            },
            trackingSnapshot = {
                trackingCalls += 1
                emptyTracking()
            },
            resolveTemplate = {
                templateCalls += 1
                WorkflowTemplate.QUICK_TASK
            },
        )

        val actions = builder.build(
            workflowId = "   ",
            trigger = SpecWorkflowRuntimeTroubleshootingTrigger.VERIFY_FAILURE,
        )

        assertTrue(actions.isEmpty())
        assertEquals(0, readinessCalls)
        assertEquals(0, trackingCalls)
        assertEquals(0, templateCalls)
    }

    private fun emptyTracking() = SpecWorkflowFirstRunTrackingSnapshot(
        createAttemptCount = 0,
        createSuccessCount = 0,
        lastAttemptTemplate = null,
        lastSuccessTemplate = null,
        lastSuccessWorkflowId = null,
        lastSuccessArtifactFileName = null,
        lastAttemptAt = null,
        lastSuccessAt = null,
        firstAttemptAt = null,
        firstSuccessAt = null,
    )

    private fun readySnapshot() = LocalEnvironmentReadiness.evaluate(
        LocalEnvironmentReadinessInput(
            projectPath = Path.of("D:/workspace/spec-code"),
            projectWritable = true,
            gitRepositoryDetected = true,
            configuredClaudePath = "",
            configuredCodexPath = "",
            claudeInfo = CliToolInfo(
                available = true,
                path = "claude",
                version = "1.0.0",
            ),
            codexInfo = CliToolInfo(
                available = false,
                path = "codex",
            ),
        ),
    )
}
