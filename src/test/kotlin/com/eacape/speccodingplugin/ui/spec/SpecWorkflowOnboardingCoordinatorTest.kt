package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.engine.CliToolInfo
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadiness
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SpecWorkflowOnboardingCoordinatorTest {

    @Test
    fun `build should keep full spec recommendation when requested path is ready`() {
        val plan = SpecWorkflowOnboardingCoordinator.build(
            requestedTemplate = WorkflowTemplate.FULL_SPEC,
            readiness = readySnapshot(),
        )

        assertEquals(SpecWorkflowPrimaryEntry.FULL_SPEC, plan.recommendedEntry)
        assertFalse(plan.showSettingsShortcut)
        assertTrue(plan.summary.isNotBlank())
        assertTrue(plan.nextStep.isNotBlank())
    }

    @Test
    fun `build should fall back to quick task when full spec is blocked but quick task is ready`() {
        val plan = SpecWorkflowOnboardingCoordinator.build(
            requestedTemplate = WorkflowTemplate.FULL_SPEC,
            readiness = quickOnlySnapshot(),
        )

        assertEquals(SpecWorkflowPrimaryEntry.QUICK_TASK, plan.recommendedEntry)
        assertTrue(plan.showSettingsShortcut)
        assertTrue(plan.summary.isNotBlank())
        assertTrue(plan.nextStep.isNotBlank())
        assertNotNull(
            SpecWorkflowOnboardingCoordinator.blockedEntryValidationMessage(
                entry = SpecWorkflowPrimaryEntry.FULL_SPEC,
                readiness = quickOnlySnapshot(),
            ),
        )
    }

    @Test
    fun `build should steer advanced defaults back to quick task for beta onboarding`() {
        val plan = SpecWorkflowOnboardingCoordinator.build(
            requestedTemplate = WorkflowTemplate.DESIGN_REVIEW,
            readiness = readySnapshot(),
        )

        assertEquals(SpecWorkflowPrimaryEntry.QUICK_TASK, plan.recommendedEntry)
    }

    @Test
    fun `blockedEntryValidationMessage should explain missing git for full spec`() {
        val readiness = quickOnlySnapshot()

        val message = SpecWorkflowOnboardingCoordinator.blockedEntryValidationMessage(
            entry = SpecWorkflowPrimaryEntry.FULL_SPEC,
            readiness = readiness,
        )

        assertNotNull(message)
        assertTrue(message!!.contains("Git"))
        assertNull(
            SpecWorkflowOnboardingCoordinator.blockedEntryValidationMessage(
                entry = SpecWorkflowPrimaryEntry.QUICK_TASK,
                readiness = readiness,
            ),
        )
    }

    @Test
    fun `blockedEntryValidationMessage should require fixing quick task prerequisites when environment is blocked`() {
        val readiness = blockedSnapshot()

        val message = SpecWorkflowOnboardingCoordinator.blockedEntryValidationMessage(
            entry = SpecWorkflowPrimaryEntry.QUICK_TASK,
            readiness = readiness,
        )

        assertNotNull(message)
        assertTrue(message!!.contains("workspace").or(message.contains("工作区")))
        assertNull(
            SpecWorkflowOnboardingCoordinator.blockedEntryValidationMessage(
                entry = SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE,
                readiness = readiness,
            ),
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
