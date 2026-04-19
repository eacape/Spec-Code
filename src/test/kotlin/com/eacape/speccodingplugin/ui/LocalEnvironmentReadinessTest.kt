package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.engine.CliToolAvailabilityIssue
import com.eacape.speccodingplugin.engine.CliToolAvailabilityIssueKind
import com.eacape.speccodingplugin.engine.CliToolInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class LocalEnvironmentReadinessTest {

    @Test
    fun `evaluate should mark quick task and full spec ready when workspace git and cli are available`() {
        val snapshot = LocalEnvironmentReadiness.evaluate(
            LocalEnvironmentReadinessInput(
                projectPath = Path.of("D:/workspace/spec-code"),
                projectWritable = true,
                gitRepositoryDetected = true,
                configuredClaudePath = "",
                configuredCodexPath = "",
                claudeInfo = CliToolInfo(
                    available = true,
                    path = "C:/tools/claude.cmd",
                    version = "1.2.3",
                ),
                codexInfo = CliToolInfo(
                    available = false,
                    path = "codex",
                ),
            ),
        )

        assertTrue(snapshot.quickTaskReady)
        assertTrue(snapshot.fullSpecReady)
        assertEquals(LocalEnvironmentCheckSeverity.READY, snapshot.summarySeverity)
        assertTrue(LocalEnvironmentReadiness.formatDetails(snapshot).contains("Quick Task"))
        assertTrue(LocalEnvironmentReadiness.formatDetails(snapshot).contains("Full Spec"))
    }

    @Test
    fun `evaluate should keep full spec blocked when git repository is missing`() {
        val snapshot = LocalEnvironmentReadiness.evaluate(
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

        assertTrue(snapshot.quickTaskReady)
        assertFalse(snapshot.fullSpecReady)
        assertEquals(LocalEnvironmentCheckSeverity.WARNING, snapshot.summarySeverity)
        assertTrue(snapshot.fullSpecCheck.detail.contains("Git"))
    }

    @Test
    fun `evaluate should block both entry paths when cli and workspace are missing`() {
        val snapshot = LocalEnvironmentReadiness.evaluate(
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

        assertFalse(snapshot.quickTaskReady)
        assertFalse(snapshot.fullSpecReady)
        assertEquals(LocalEnvironmentCheckSeverity.BLOCKER, snapshot.summarySeverity)
        assertTrue(
            snapshot.quickTaskCheck.detail.contains(SpecCodingBundle.message("local.setup.requirement.workspace")),
        )
        assertTrue(
            snapshot.quickTaskCheck.detail.contains(SpecCodingBundle.message("local.setup.requirement.cli")),
        )
    }

    @Test
    fun `evaluate should warn when configured cli path is stale but another cli is still available`() {
        val snapshot = LocalEnvironmentReadiness.evaluate(
            LocalEnvironmentReadinessInput(
                projectPath = Path.of("D:/workspace/spec-code"),
                projectWritable = true,
                gitRepositoryDetected = true,
                configuredClaudePath = "C:/broken/claude.cmd",
                configuredCodexPath = "",
                claudeInfo = CliToolInfo(
                    available = false,
                    path = "claude",
                ),
                codexInfo = CliToolInfo(
                    available = true,
                    path = "C:/tools/codex.cmd",
                    version = "0.9.0",
                ),
            ),
        )

        val cliPathCheck = snapshot.detailChecks.first {
            it.label == SpecCodingBundle.message("local.setup.check.cliPath")
        }
        assertEquals(LocalEnvironmentCheckSeverity.WARNING, cliPathCheck.severity)
        assertTrue(cliPathCheck.detail.contains("claude"))
        assertTrue(cliPathCheck.detail.contains("codex"))
    }

    @Test
    fun `evaluate should include structured cli failure detail when probes fail`() {
        val snapshot = LocalEnvironmentReadiness.evaluate(
            LocalEnvironmentReadinessInput(
                projectPath = Path.of("D:/workspace/spec-code"),
                projectWritable = true,
                gitRepositoryDetected = true,
                configuredClaudePath = "C:/broken/claude.cmd",
                configuredCodexPath = "C:/blocked/codex.cmd",
                claudeInfo = CliToolInfo(
                    available = false,
                    path = "C:/broken/claude.cmd",
                    availabilityIssue = CliToolAvailabilityIssue(
                        kind = CliToolAvailabilityIssueKind.EXECUTABLE_NOT_FOUND,
                        detail = "cli executable was not found: claude.cmd",
                    ),
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

        val cliCheck = snapshot.detailChecks.first {
            it.label == SpecCodingBundle.message("local.setup.check.cli")
        }
        val cliPathCheck = snapshot.detailChecks.first {
            it.label == SpecCodingBundle.message("local.setup.check.cliPath")
        }

        assertTrue(cliCheck.detail.contains("claude.cmd"))
        assertTrue(cliCheck.detail.contains("access denied"))
        assertTrue(cliPathCheck.detail.contains("claude: cli executable was not found"))
        assertTrue(cliPathCheck.detail.contains("codex: access denied"))
    }

    @Test
    fun `evaluate should surface configured path failures distinctly from generic missing cli issues`() {
        val snapshot = LocalEnvironmentReadiness.evaluate(
            LocalEnvironmentReadinessInput(
                projectPath = Path.of("D:/workspace/spec-code"),
                projectWritable = true,
                gitRepositoryDetected = true,
                configuredClaudePath = "C:/broken/claude.cmd",
                configuredCodexPath = "",
                claudeInfo = CliToolInfo(
                    available = false,
                    path = "C:/broken/claude.cmd",
                    availabilityIssue = CliToolAvailabilityIssue(
                        kind = CliToolAvailabilityIssueKind.EXECUTABLE_PATH_INVALID,
                        detail = "configured cli path was not found: C:/broken/claude.cmd",
                    ),
                ),
                codexInfo = CliToolInfo(
                    available = true,
                    path = "C:/tools/codex.cmd",
                    version = "1.0.0",
                ),
            ),
        )

        val cliPathCheck = snapshot.detailChecks.first {
            it.label == SpecCodingBundle.message("local.setup.check.cliPath")
        }

        assertEquals(LocalEnvironmentCheckSeverity.WARNING, cliPathCheck.severity)
        assertTrue(cliPathCheck.detail.contains("configured cli path was not found"))
        assertTrue(cliPathCheck.detail.contains("C:/broken/claude.cmd"))
    }
}
