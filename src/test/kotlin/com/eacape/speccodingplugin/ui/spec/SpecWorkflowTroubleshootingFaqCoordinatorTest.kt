package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.engine.CliToolAvailabilityIssue
import com.eacape.speccodingplugin.engine.CliToolAvailabilityIssueKind
import com.eacape.speccodingplugin.engine.CliToolInfo
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadiness
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SpecWorkflowTroubleshootingFaqCoordinatorTest {

    @Test
    fun `build should surface environment blocker and settings entry when quick task cannot start`() {
        val faq = SpecWorkflowTroubleshootingFaqCoordinator.build(
            readiness = blockedSnapshot(),
            tracking = emptyTracking(),
            template = WorkflowTemplate.QUICK_TASK,
        )

        assertEquals(
            SpecCodingBundle.message("spec.dialog.troubleshooting.summary.blocked"),
            faq.summary,
        )
        assertEquals(4, faq.items.size)
        assertEquals(2, faq.actions.size)
        assertTrue(faq.items[0].answer.contains(blockedSnapshot().quickTaskCheck.detail))
        assertTrue(faq.items[0].answer.contains(SpecCodingBundle.message("settings.sidebar.basic")))
        assertTrue(faq.actions[0] is SpecWorkflowTroubleshootingAction.OpenSettings)
        assertTrue(faq.actions[1] is SpecWorkflowTroubleshootingAction.OpenBundledDemo)
    }

    @Test
    fun `build should explain permission and path fixes when cli access is denied`() {
        val readiness = permissionIssueSnapshot()

        val faq = SpecWorkflowTroubleshootingFaqCoordinator.build(
            readiness = readiness,
            tracking = emptyTracking(),
            template = WorkflowTemplate.QUICK_TASK,
        )

        assertTrue(faq.items[1].answer.contains("access denied"))
        assertTrue(faq.items[1].answer.contains(SpecCodingBundle.message("settings.sidebar.basic")))
        assertTrue(faq.items[1].answer.contains(SpecCodingBundle.message("local.setup.check.cliPath")))
        assertTrue(faq.actions[0] is SpecWorkflowTroubleshootingAction.OpenSettings)
    }

    @Test
    fun `build should steer unsuccessful first runs back to bundled demo comparison`() {
        val faq = SpecWorkflowTroubleshootingFaqCoordinator.build(
            readiness = readySnapshot(),
            tracking = retryTracking(),
            template = WorkflowTemplate.QUICK_TASK,
        )

        assertEquals(
            SpecCodingBundle.message("spec.dialog.troubleshooting.summary.retry"),
            faq.summary,
        )
        assertTrue(faq.items[2].answer.contains("tasks.md"))
        assertTrue(faq.items[2].answer.contains(SpecCodingBundle.message("spec.dialog.demo.open")))
        assertTrue(faq.actions[0] is SpecWorkflowTroubleshootingAction.OpenBundledDemo)
        assertTrue(faq.actions[1] is SpecWorkflowTroubleshootingAction.OpenSettings)
    }

    @Test
    fun `build should recommend fuller context before experimental integrations`() {
        val faq = SpecWorkflowTroubleshootingFaqCoordinator.build(
            readiness = readySnapshot(),
            tracking = emptyTracking(),
            template = WorkflowTemplate.QUICK_TASK,
        )

        assertTrue(
            faq.items[3].answer.contains(
                SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.FULL_SPEC),
            ),
        )
        assertTrue(faq.items[3].answer.contains(SpecCodingBundle.message("settings.sidebar.prompts")))
        assertTrue(faq.items[3].answer.contains(SpecCodingBundle.message("settings.sidebar.mcp")))
        assertEquals(
            SpecWorkflowPrimaryEntry.FULL_SPEC,
            (faq.actions[0] as SpecWorkflowTroubleshootingAction.SelectEntry).entry,
        )
        assertTrue(faq.actions[1] is SpecWorkflowTroubleshootingAction.OpenBundledDemo)
    }

    @Test
    fun `build should keep ready full spec shortcuts narrow`() {
        val faq = SpecWorkflowTroubleshootingFaqCoordinator.build(
            readiness = readySnapshot(),
            tracking = emptyTracking(),
            template = WorkflowTemplate.FULL_SPEC,
        )

        assertEquals(1, faq.actions.size)
        assertTrue(faq.actions[0] is SpecWorkflowTroubleshootingAction.OpenBundledDemo)
    }

    @Test
    fun `build should switch full spec back to quick task when git coverage is not ready`() {
        val faq = SpecWorkflowTroubleshootingFaqCoordinator.build(
            readiness = quickTaskOnlySnapshot(),
            tracking = emptyTracking(),
            template = WorkflowTemplate.FULL_SPEC,
        )

        assertEquals(
            SpecWorkflowPrimaryEntry.QUICK_TASK,
            (faq.actions[0] as SpecWorkflowTroubleshootingAction.SelectEntry).entry,
        )
        assertTrue(faq.actions[1] is SpecWorkflowTroubleshootingAction.OpenSettings)
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

    private fun retryTracking() = SpecWorkflowFirstRunTrackingSnapshot(
        createAttemptCount = 1,
        createSuccessCount = 0,
        lastAttemptTemplate = WorkflowTemplate.QUICK_TASK,
        lastSuccessTemplate = null,
        lastSuccessWorkflowId = null,
        lastSuccessArtifactFileName = null,
        lastAttemptAt = 1_000L,
        lastSuccessAt = null,
        firstAttemptAt = 1_000L,
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

    private fun permissionIssueSnapshot() = LocalEnvironmentReadiness.evaluate(
        LocalEnvironmentReadinessInput(
            projectPath = Path.of("D:/workspace/spec-code"),
            projectWritable = true,
            gitRepositoryDetected = true,
            configuredClaudePath = "",
            configuredCodexPath = "C:/blocked/codex.cmd",
            claudeInfo = CliToolInfo(
                available = true,
                path = "claude",
                version = "1.0.0",
            ),
            codexInfo = CliToolInfo(
                available = false,
                path = "C:/blocked/codex.cmd",
                availabilityIssue = CliToolAvailabilityIssue(
                    kind = CliToolAvailabilityIssueKind.ACCESS_DENIED,
                    detail = "access denied while starting CLI command in D:/workspace/spec-code",
                ),
            ),
        ),
    )
}
