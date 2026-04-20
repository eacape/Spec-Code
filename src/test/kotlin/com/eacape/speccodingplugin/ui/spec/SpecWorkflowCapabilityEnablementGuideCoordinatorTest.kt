package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.engine.CliToolInfo
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadiness
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessInput
import com.eacape.speccodingplugin.ui.settings.SettingsSidebarSection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SpecWorkflowCapabilityEnablementGuideCoordinatorTest {

    @Test
    fun `build should keep reusable capabilities behind blocked readiness`() {
        val guide = SpecWorkflowCapabilityEnablementGuideCoordinator.build(
            readiness = blockedSnapshot(),
            tracking = emptyTracking(),
        )

        assertEquals(
            SpecCodingBundle.message("spec.dialog.capabilityGuide.summary.blocked"),
            guide.summary,
        )
        assertEquals(
            listOf(
                SettingsSidebarSection.PROMPTS,
                SettingsSidebarSection.SKILLS,
                SettingsSidebarSection.HOOKS,
                SettingsSidebarSection.MCP,
            ),
            guide.items.map { it.section },
        )
        assertEquals(
            listOf(
                SpecWorkflowCapabilityEnablementTiming.NEXT,
                SpecWorkflowCapabilityEnablementTiming.LATER,
                SpecWorkflowCapabilityEnablementTiming.LATER,
                SpecWorkflowCapabilityEnablementTiming.LATER,
            ),
            guide.items.map { it.timing },
        )
        assertTrue(guide.items.first().detail.contains(SpecCodingBundle.message("settings.sidebar.prompts")))
        assertTrue(guide.items[2].detail.contains(SpecCodingBundle.message("settings.sidebar.hooks")))
    }

    @Test
    fun `build should keep prompts as the next step before the first successful workflow`() {
        val guide = SpecWorkflowCapabilityEnablementGuideCoordinator.build(
            readiness = readySnapshot(),
            tracking = emptyTracking(),
        )

        assertEquals(
            SpecCodingBundle.message("spec.dialog.capabilityGuide.summary.preFirstSuccess"),
            guide.summary,
        )
        assertEquals(SpecWorkflowCapabilityEnablementTiming.NEXT, guide.items[0].timing)
        assertEquals(SpecWorkflowCapabilityEnablementTiming.LATER, guide.items[1].timing)
        assertTrue(guide.items[0].detail.contains(SpecCodingBundle.message("settings.sidebar.prompts")))
        assertTrue(guide.items[1].detail.contains(SpecCodingBundle.message("settings.sidebar.skills")))
    }

    @Test
    fun `build should advance prompts and skills after the first successful workflow`() {
        val guide = SpecWorkflowCapabilityEnablementGuideCoordinator.build(
            readiness = readySnapshot(),
            tracking = successfulTracking(),
        )

        assertEquals(
            SpecCodingBundle.message("spec.dialog.capabilityGuide.summary.afterFirstSuccess"),
            guide.summary,
        )
        assertEquals(
            listOf(
                SpecWorkflowCapabilityEnablementTiming.NOW,
                SpecWorkflowCapabilityEnablementTiming.NEXT,
                SpecWorkflowCapabilityEnablementTiming.LATER,
                SpecWorkflowCapabilityEnablementTiming.LATER,
            ),
            guide.items.map { it.timing },
        )
        assertTrue(guide.items[0].detail.contains(SpecCodingBundle.message("settings.sidebar.prompts")))
        assertTrue(guide.items[1].detail.contains(SpecCodingBundle.message("settings.sidebar.skills")))
        assertTrue(guide.items[2].detail.contains(SpecCodingBundle.message("settings.sidebar.hooks")))
        assertTrue(guide.items[3].detail.contains(SpecCodingBundle.message("settings.sidebar.mcp")))
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

    private fun successfulTracking() = SpecWorkflowFirstRunTrackingSnapshot(
        createAttemptCount = 1,
        createSuccessCount = 1,
        lastAttemptTemplate = null,
        lastSuccessTemplate = null,
        lastSuccessWorkflowId = "wf-001",
        lastSuccessArtifactFileName = "tasks.md",
        lastAttemptAt = 1_000L,
        lastSuccessAt = 2_000L,
        firstAttemptAt = 1_000L,
        firstSuccessAt = 2_000L,
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
}
