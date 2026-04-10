package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.engine.CliToolInfo
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadiness
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SpecWorkflowRuntimeTroubleshootingCoordinatorTest {

    @Test
    fun `build should offer settings and demo before first visible success`() {
        val actions = SpecWorkflowRuntimeTroubleshootingCoordinator.build(
            trigger = SpecWorkflowRuntimeTroubleshootingTrigger.TASK_EXECUTION_PRECHECK,
            readiness = readySnapshot(),
            tracking = emptyTracking(),
            template = WorkflowTemplate.QUICK_TASK,
        )

        assertEquals(
            listOf(
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
    fun `build should keep only settings after first visible success when environment is ready`() {
        val actions = SpecWorkflowRuntimeTroubleshootingCoordinator.build(
            trigger = SpecWorkflowRuntimeTroubleshootingTrigger.TASK_EXECUTION_FAILURE,
            readiness = readySnapshot(),
            tracking = successTracking(),
            template = WorkflowTemplate.QUICK_TASK,
        )

        assertEquals(
            listOf(
                SpecWorkflowTroubleshootingAction.OpenSettings(
                    label = SpecCodingBundle.message("spec.dialog.troubleshooting.action.openSettings"),
                ),
            ),
            actions,
        )
    }

    @Test
    fun `build should keep bundled demo when readiness regresses after a success`() {
        val actions = SpecWorkflowRuntimeTroubleshootingCoordinator.build(
            trigger = SpecWorkflowRuntimeTroubleshootingTrigger.TASK_EXECUTION_FAILURE,
            readiness = blockedSnapshot(),
            tracking = successTracking(),
            template = WorkflowTemplate.FULL_SPEC,
        )

        assertEquals(
            listOf(
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
    fun `build should offer quick task fallback before first visible success on full spec`() {
        val actions = SpecWorkflowRuntimeTroubleshootingCoordinator.build(
            trigger = SpecWorkflowRuntimeTroubleshootingTrigger.TASK_EXECUTION_PRECHECK,
            readiness = readySnapshot(),
            tracking = emptyTracking(),
            template = WorkflowTemplate.FULL_SPEC,
        )

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
    fun `build should offer quick task fallback when full spec regresses but quick task stays ready`() {
        val actions = SpecWorkflowRuntimeTroubleshootingCoordinator.build(
            trigger = SpecWorkflowRuntimeTroubleshootingTrigger.TASK_EXECUTION_FAILURE,
            readiness = quickTaskOnlySnapshot(),
            tracking = successTracking(),
            template = WorkflowTemplate.FULL_SPEC,
        )

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

    private fun successTracking() = SpecWorkflowFirstRunTrackingSnapshot(
        createAttemptCount = 2,
        createSuccessCount = 1,
        lastAttemptTemplate = WorkflowTemplate.FULL_SPEC,
        lastSuccessTemplate = WorkflowTemplate.QUICK_TASK,
        lastSuccessWorkflowId = "wf-success",
        lastSuccessArtifactFileName = "tasks.md",
        lastAttemptAt = 2_000L,
        lastSuccessAt = 1_000L,
        firstAttemptAt = 500L,
        firstSuccessAt = 1_000L,
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

    private fun blockedSnapshot() = LocalEnvironmentReadiness.evaluate(
        LocalEnvironmentReadinessInput(
            projectPath = null,
            projectWritable = false,
            gitRepositoryDetected = false,
            configuredClaudePath = "",
            configuredCodexPath = "",
            claudeInfo = CliToolInfo(
                available = false,
                path = "claude",
            ),
            codexInfo = CliToolInfo(
                available = false,
                path = "codex",
            ),
        ),
    )

    private fun quickTaskOnlySnapshot() = LocalEnvironmentReadiness.evaluate(
        LocalEnvironmentReadinessInput(
            projectPath = Path.of("D:/workspace/spec-code"),
            projectWritable = true,
            gitRepositoryDetected = false,
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
