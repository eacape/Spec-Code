package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.spec.Violation
import com.eacape.speccodingplugin.spec.VerifyCommandExecutionResult
import com.eacape.speccodingplugin.spec.VerifyPlan
import com.eacape.speccodingplugin.spec.VerifyPlanConfigSource
import com.eacape.speccodingplugin.spec.VerifyPlanCommand
import com.eacape.speccodingplugin.spec.VerifyPlanPolicy
import com.eacape.speccodingplugin.spec.VerifyRunResult
import com.eacape.speccodingplugin.spec.WorkflowMeta
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CancellationException

class SpecWorkflowActionSupportTest {

    @Test
    fun `jump targets should stay empty when manual stage changes are locked`() {
        val meta = workflowMeta(
            currentStage = StageId.DESIGN,
            stageStates = mapOf(
                StageId.REQUIREMENTS to state(active = true, status = StageProgress.DONE),
                StageId.DESIGN to state(active = true, status = StageProgress.IN_PROGRESS),
                StageId.TASKS to state(active = true, status = StageProgress.NOT_STARTED),
                StageId.IMPLEMENT to state(active = true, status = StageProgress.NOT_STARTED),
                StageId.VERIFY to state(active = false, status = StageProgress.NOT_STARTED),
                StageId.ARCHIVE to state(active = true, status = StageProgress.NOT_STARTED),
            ),
        )

        assertEquals(
            emptyList<StageId>(),
            SpecWorkflowActionSupport.jumpTargets(meta),
        )
    }

    @Test
    fun `rollback targets should stay empty when manual stage changes are locked`() {
        val meta = workflowMeta(
            currentStage = StageId.IMPLEMENT,
            stageStates = mapOf(
                StageId.REQUIREMENTS to state(active = true, status = StageProgress.DONE),
                StageId.DESIGN to state(active = true, status = StageProgress.DONE),
                StageId.TASKS to state(active = true, status = StageProgress.IN_PROGRESS),
                StageId.IMPLEMENT to state(active = true, status = StageProgress.IN_PROGRESS),
                StageId.VERIFY to state(active = false, status = StageProgress.NOT_STARTED),
                StageId.ARCHIVE to state(active = true, status = StageProgress.NOT_STARTED),
            ),
        )

        assertEquals(
            emptyList<StageId>(),
            SpecWorkflowActionSupport.rollbackTargets(meta),
        )
    }

    @Test
    fun `gate summary includes violation details and truncation hint`() {
        val gateResult = GateResult.fromViolations(
            listOf(
                violation("rule-1", 5),
                violation("rule-2", 8),
                violation("rule-3", 13),
                violation("rule-4", 21),
                violation("rule-5", 34),
                violation("rule-6", 55),
            ),
        )

        val summary = SpecWorkflowActionSupport.gateSummary(gateResult, limit = 3)

        assertTrue(summary.contains("rule-1"))
        assertTrue(summary.contains("requirements.md:5"))
        assertTrue(summary.contains("requirement heading"))
        assertTrue(summary.contains("rule-3"))
        assertTrue(!summary.contains("rule-4"))
    }

    @Test
    fun `gate violation details include fix hint and original severity`() {
        val details = SpecWorkflowActionSupport.gateViolationDetails(
            Violation(
                ruleId = "verify-conclusion",
                severity = GateStatus.WARNING,
                fileName = "tasks.md",
                line = 42,
                message = "Verification concluded WARN",
                fixHint = "Review the warning and rerun verification.",
                originalSeverity = GateStatus.ERROR,
            ),
        )

        assertTrue(details.contains("verify-conclusion"))
        assertTrue(details.contains("tasks.md:42"))
        assertTrue(details.contains("Review the warning and rerun verification."))
    }

    @Test
    fun `gate violation option label keeps file and line visible`() {
        val label = SpecWorkflowActionSupport.gateViolationOptionLabel(
            violation("tasks-syntax", 13),
        )

        assertTrue(label.contains("tasks-syntax"))
        assertTrue(label.contains("requirements.md"))
        assertTrue(label.contains("13"))
    }

    @Test
    fun `verification plan summary includes plan scope command and confirmation reasons`() {
        val summary = SpecWorkflowActionSupport.verificationPlanSummary(
            plan = VerifyPlan(
                planId = "verify-plan-001",
                workflowId = "wf-001",
                currentStage = StageId.IMPLEMENT,
                generatedAt = "2026-03-09T12:00:00Z",
                commands = listOf(
                    VerifyPlanCommand(
                        commandId = "gradle-test",
                        displayName = "Gradle Test",
                        command = listOf("./gradlew", "testDebugUnitTest"),
                        workingDirectory = Path.of("."),
                        timeoutMs = 15_000,
                        outputLimitChars = 4_000,
                        redactionPatterns = listOf("token=.*"),
                    ),
                ),
                policy = VerifyPlanPolicy(
                    configSource = VerifyPlanConfigSource.WORKFLOW_PINNED,
                    workflowConfigPinHash = "pin-123",
                    effectiveConfigHash = "cfg-456",
                    usesPinnedSnapshot = true,
                    confirmationRequired = true,
                    confirmationReasons = listOf("Review verify commands before execution."),
                ),
            ),
            scopeTasks = listOf(task("T-002"), task("T-001")),
        )

        assertTrue(summary.contains("verify-plan-001"))
        assertTrue(summary.contains("Task Scope: 2 task(s): T-001, T-002"))
        assertTrue(summary.contains("Gradle Test [gradle-test]"))
        assertTrue(summary.contains("Review verify commands before execution."))
    }

    @Test
    fun `verification run summary includes conclusion document updated tasks and outcomes`() {
        val summary = SpecWorkflowActionSupport.verificationRunSummary(
            VerifyRunResult(
                runId = "verify-run-001",
                workflowId = "wf-001",
                planId = "verify-plan-001",
                currentStage = StageId.VERIFY,
                executedAt = "2026-03-09T12:30:00Z",
                conclusion = VerificationConclusion.WARN,
                summary = "1/1 verify command(s) completed, but output was truncated for gradle-test.",
                verificationDocumentPath = Path.of("verification.md"),
                commandResults = listOf(
                    VerifyCommandExecutionResult(
                        commandId = "gradle-test",
                        exitCode = 0,
                        stdout = "ok",
                        stderr = "",
                        durationMs = 1_234,
                        timedOut = false,
                        stdoutTruncated = true,
                        stderrTruncated = false,
                        redacted = true,
                    ),
                ),
                updatedTasks = listOf(task("T-003"), task("T-001")),
            ),
        )

        assertTrue(summary.contains("Conclusion: WARN"))
        assertTrue(summary.contains("Verification File: verification.md"))
        assertTrue(summary.contains("Updated Tasks: 2 task(s): T-001, T-003"))
        assertTrue(summary.contains("gradle-test: SUCCESS"))
        assertTrue(summary.contains("truncated"))
        assertTrue(summary.contains("redacted"))
    }

    @Test
    fun `capture background task outcome should keep cancellation as data instead of throwing`() {
        val outcome = SpecWorkflowActionSupport.captureBackgroundTaskOutcome<String> {
            throw CancellationException("AI execution cancelled by user.")
        }

        val resolved = SpecWorkflowActionSupport.unwrapBackgroundTaskOutcome(
            outcome = outcome,
            indicatorCancelled = false,
        )

        assertTrue(resolved.isFailure)
        assertTrue(resolved.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `unwrap background task outcome should convert cancelled indicator to process cancelled exception`() {
        val outcome = SpecWorkflowActionSupport.BackgroundTaskOutcome.Failure(
            CancellationException("AI execution cancelled by user."),
        )

        assertThrows(ProcessCanceledException::class.java) {
            SpecWorkflowActionSupport.unwrapBackgroundTaskOutcome(
                outcome = outcome,
                indicatorCancelled = true,
            )
        }
    }

    private fun workflowMeta(
        currentStage: StageId,
        stageStates: Map<StageId, StageState>,
    ): WorkflowMeta {
        return WorkflowMeta(
            workflowId = "wf-001",
            title = "Workflow 001",
            template = WorkflowTemplate.FULL_SPEC,
            stageStates = stageStates,
            currentStage = currentStage,
            verifyEnabled = false,
            configPinHash = null,
            baselineWorkflowId = null,
            artifactDraftStates = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            createdAt = 1L,
            updatedAt = 2L,
        )
    }

    private fun state(active: Boolean, status: StageProgress): StageState {
        return StageState(active = active, status = status)
    }

    private fun task(id: String): StructuredTask {
        return StructuredTask(
            id = id,
            title = "Task $id",
            status = TaskStatus.COMPLETED,
            priority = TaskPriority.P1,
        )
    }

    private fun violation(ruleId: String, line: Int): Violation {
        return Violation(
            ruleId = ruleId,
            severity = GateStatus.ERROR,
            fileName = "requirements.md",
            line = line,
            message = "Broken requirement",
            fixHint = "Fix the requirement heading",
        )
    }
}
