package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.engine.CliToolInfo
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadiness
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessInput
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SpecWorkflowFirstRunStatusCoordinatorTest {

    @Test
    fun `build should report completed first run when a successful workflow exists`() {
        val presentation = SpecWorkflowFirstRunStatusCoordinator.build(
            readiness = readySnapshot(),
            tracking = SpecWorkflowFirstRunTrackingSnapshot(
                createAttemptCount = 1,
                createSuccessCount = 1,
                lastAttemptTemplate = WorkflowTemplate.QUICK_TASK,
                lastSuccessTemplate = WorkflowTemplate.QUICK_TASK,
                lastSuccessWorkflowId = "wf-001",
                lastSuccessArtifactFileName = "tasks.md",
                lastAttemptAt = 1_000L,
                lastSuccessAt = 121_000L,
                firstAttemptAt = 1_000L,
                firstSuccessAt = 121_000L,
            ),
        )

        assertTrue(presentation.summary.isNotBlank())
        assertTrue(presentation.details.any { it.contains("1/1") })
        assertTrue(presentation.details.any { it.contains("wf-001") })
        assertTrue(presentation.details.any { it.contains("tasks.md") })
        assertTrue(
            presentation.details.any {
                it == SpecCodingBundle.message(
                    "spec.dialog.firstRun.status.detail.firstSuccessWithinTarget",
                    "2:00",
                    "5:00",
                )
            },
        )
    }

    @Test
    fun `build should highlight missing success after a ready attempt`() {
        val presentation = SpecWorkflowFirstRunStatusCoordinator.build(
            readiness = readySnapshot(),
            tracking = SpecWorkflowFirstRunTrackingSnapshot(
                createAttemptCount = 1,
                createSuccessCount = 0,
                lastAttemptTemplate = WorkflowTemplate.FULL_SPEC,
                lastSuccessTemplate = null,
                lastSuccessWorkflowId = null,
                lastSuccessArtifactFileName = null,
                lastAttemptAt = 1_000L,
                lastSuccessAt = null,
                firstAttemptAt = 1_000L,
            ),
        )

        assertTrue(presentation.summary.isNotBlank())
        assertTrue(presentation.details.any { it.contains("0/1") })
        assertTrue(
            presentation.details.any {
                it.contains(SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.FULL_SPEC))
            },
        )
        assertTrue(presentation.details.any { it.contains("tasks.md") })
        assertTrue(
            presentation.details.any {
                it == SpecCodingBundle.message(
                    "spec.dialog.firstRun.status.detail.targetPending",
                    "tasks.md",
                    "5:00",
                )
            },
        )
    }

    @Test
    fun `build should report when the first visible artifact exceeded the beta target`() {
        val presentation = SpecWorkflowFirstRunStatusCoordinator.build(
            readiness = readySnapshot(),
            tracking = SpecWorkflowFirstRunTrackingSnapshot(
                createAttemptCount = 1,
                createSuccessCount = 1,
                lastAttemptTemplate = WorkflowTemplate.QUICK_TASK,
                lastSuccessTemplate = WorkflowTemplate.QUICK_TASK,
                lastSuccessWorkflowId = "wf-002",
                lastSuccessArtifactFileName = "tasks.md",
                lastAttemptAt = 1_000L,
                lastSuccessAt = 361_000L,
                firstAttemptAt = 1_000L,
                firstSuccessAt = 361_000L,
            ),
        )

        assertTrue(
            presentation.details.any {
                it == SpecCodingBundle.message(
                    "spec.dialog.firstRun.status.detail.firstSuccessOverTarget",
                    "6:00",
                    "5:00",
                )
            },
        )
    }

    @Test
    fun `build should keep environment blocked messaging when readiness is not met`() {
        val presentation = SpecWorkflowFirstRunStatusCoordinator.build(
            readiness = blockedSnapshot(),
            tracking = SpecWorkflowFirstRunTrackingSnapshot(
                createAttemptCount = 0,
                createSuccessCount = 0,
                lastAttemptTemplate = null,
                lastSuccessTemplate = null,
                lastSuccessWorkflowId = null,
                lastSuccessArtifactFileName = null,
                lastAttemptAt = null,
                lastSuccessAt = null,
            ),
        )

        assertTrue(presentation.summary.isNotBlank())
        assertTrue(
            presentation.details.any {
                it == SpecCodingBundle.message(
                    "spec.dialog.firstRun.status.detail.environment",
                    SpecCodingBundle.message("spec.dialog.firstRun.status.environment.blocked"),
                )
            },
        )
        assertTrue(
            presentation.details.any {
                it == SpecCodingBundle.message("spec.dialog.firstRun.status.detail.nextBlocked")
            },
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
