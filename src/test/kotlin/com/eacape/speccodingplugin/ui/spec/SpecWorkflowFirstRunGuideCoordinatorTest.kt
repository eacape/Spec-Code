package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.engine.CliToolInfo
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadiness
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SpecWorkflowFirstRunGuideCoordinatorTest {

    @Test
    fun `build should keep quick task as shortest first pass when ready`() {
        val guide = SpecWorkflowFirstRunGuideCoordinator.build(
            selectedEntry = SpecWorkflowPrimaryEntry.QUICK_TASK,
            template = WorkflowTemplate.QUICK_TASK,
            readiness = readySnapshot(),
        )

        assertEquals(SpecCodingBundle.message("spec.dialog.firstRun.quickTask.summary"), guide.summary)
        assertEquals(3, guide.steps.size)
        assertEquals(
            SpecCodingBundle.message("spec.dialog.firstRun.quickTask.step.start", "tasks.md"),
            guide.steps[1],
        )
    }

    @Test
    fun `build should describe full spec planning chain when full spec is ready`() {
        val guide = SpecWorkflowFirstRunGuideCoordinator.build(
            selectedEntry = SpecWorkflowPrimaryEntry.FULL_SPEC,
            template = WorkflowTemplate.FULL_SPEC,
            readiness = readySnapshot(),
        )

        assertEquals(SpecCodingBundle.message("spec.dialog.firstRun.fullSpec.summary"), guide.summary)
        assertEquals(
            SpecCodingBundle.message("spec.dialog.firstRun.fullSpec.step.start", "requirements.md"),
            guide.steps[1],
        )
        assertEquals(
            SpecCodingBundle.message("spec.dialog.firstRun.fullSpec.step.finish"),
            guide.steps[2],
        )
    }

    @Test
    fun `build should steer blocked full spec back to quick task`() {
        val guide = SpecWorkflowFirstRunGuideCoordinator.build(
            selectedEntry = SpecWorkflowPrimaryEntry.FULL_SPEC,
            template = WorkflowTemplate.FULL_SPEC,
            readiness = quickOnlySnapshot(),
        )

        assertEquals(SpecCodingBundle.message("spec.dialog.firstRun.fullSpecBlocked.summary"), guide.summary)
        assertEquals(
            SpecCodingBundle.message("spec.dialog.firstRun.fullSpecBlocked.step.switch"),
            guide.steps[0],
        )
        assertEquals(
            SpecCodingBundle.message("spec.dialog.firstRun.fullSpecBlocked.step.start", "tasks.md"),
            guide.steps[1],
        )
    }

    @Test
    fun `build should keep advanced template as a secondary path once quick task is ready`() {
        val guide = SpecWorkflowFirstRunGuideCoordinator.build(
            selectedEntry = SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE,
            template = WorkflowTemplate.DESIGN_REVIEW,
            readiness = readySnapshot(),
        )

        assertEquals(SpecCodingBundle.message("spec.dialog.firstRun.advanced.summary"), guide.summary)
        assertEquals(
            SpecCodingBundle.message(
                "spec.dialog.firstRun.advanced.step.start",
                SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.DESIGN_REVIEW),
                "design.md",
            ),
            guide.steps[1],
        )
    }

    @Test
    fun `build should expose blocked remediation when quick task is not ready`() {
        val guide = SpecWorkflowFirstRunGuideCoordinator.build(
            selectedEntry = SpecWorkflowPrimaryEntry.QUICK_TASK,
            template = WorkflowTemplate.QUICK_TASK,
            readiness = blockedSnapshot(),
        )

        assertEquals(SpecCodingBundle.message("spec.dialog.firstRun.blocked.summary"), guide.summary)
        assertEquals(
            SpecCodingBundle.message("spec.dialog.firstRun.blocked.step.fix"),
            guide.steps[0],
        )
        assertEquals(
            SpecCodingBundle.message("spec.dialog.firstRun.blocked.step.expect", "tasks.md"),
            guide.steps[2],
        )
    }

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

    private fun quickOnlySnapshot() = LocalEnvironmentReadiness.evaluate(
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
}
