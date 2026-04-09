package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateAggregation
import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.spec.VerifyRunHistoryEntry
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowWorkspaceSummaryPresenterTest {

    @Test
    fun `build should expose current-stage metrics and section summaries`() {
        val presentation = SpecWorkflowWorkspaceSummaryPresenter.build(
            workflow = workflow(currentStage = StageId.IMPLEMENT),
            overviewState = overviewState(currentStage = StageId.IMPLEMENT, nextStage = StageId.VERIFY),
            workbenchState = workbenchState(
                currentStage = StageId.IMPLEMENT,
                focusedStage = StageId.IMPLEMENT,
                stageStatus = StageProgress.IN_PROGRESS,
                completedChecks = 2,
                totalChecks = 3,
                stepIndex = 4,
                totalSteps = 6,
                fileName = "tasks.md",
            ),
            guidance = SpecWorkflowStageGuidance(
                headline = "Focus Implement",
                summary = "Keep implementation progress auditable.",
                checklist = emptyList(),
            ),
            tasks = listOf(
                task("task-1", TaskStatus.COMPLETED),
                task("task-2", TaskStatus.BLOCKED),
                task("task-3", TaskStatus.PENDING),
            ),
            verifyDeltaState = verifyState(
                verifyEnabled = true,
                baselineCount = 2,
                history = listOf(
                    VerifyRunHistoryEntry(
                        runId = "run-1",
                        planId = "plan-1",
                        executedAt = "2026-04-09T10:00:00Z",
                        occurredAtEpochMs = 1L,
                        currentStage = StageId.VERIFY,
                        conclusion = VerificationConclusion.PASS,
                        summary = "Verification passed",
                        commandCount = 2,
                    ),
                ),
            ),
            gateResult = gateResult(GateStatus.WARNING, errorCount = 1, warningCount = 2, total = 3),
        )

        assertEquals("Workflow Demo", presentation.title)
        assertTrue(presentation.meta.contains("wf-42"))
        assertTrue(presentation.meta.contains(SpecWorkflowOverviewPresenter.stageLabel(StageId.VERIFY)))
        assertEquals("Focus Implement", presentation.focusTitle)
        assertEquals("Keep implementation progress auditable.", presentation.focusSummary)

        assertEquals(SpecCodingBundle.message("spec.toolwindow.overview.currentStage"), presentation.stageMetric.title)
        assertEquals(
            "${SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT)} / 2/3 / 4/6 / " +
                SpecWorkflowOverviewPresenter.progressLabel(StageProgress.IN_PROGRESS),
            presentation.stageMetric.value,
        )
        assertEquals(SpecWorkflowWorkspaceChipTone.INFO, presentation.stageMetric.tone)

        assertEquals(SpecWorkflowWorkspaceChipTone.WARNING, presentation.gateMetric.tone)
        assertEquals(SpecCodingBundle.message("spec.toolwindow.gate.status.warning"), presentation.gateMetric.value)

        assertEquals("1/3", presentation.tasksMetric.value)
        assertEquals(SpecWorkflowWorkspaceChipTone.WARNING, presentation.tasksMetric.tone)

        assertEquals("1 / ${SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.pass")}", presentation.verifyMetric.value)
        assertEquals(SpecWorkflowWorkspaceChipTone.SUCCESS, presentation.verifyMetric.tone)

        assertTrue(presentation.sectionSummaries.overview.contains("2/3"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.summary", 3, 1, 1),
            presentation.sectionSummaries.tasks,
        )
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.gate.summary", 1, 2, 3),
            presentation.sectionSummaries.gate,
        )
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.verifyDelta.summary", 1, "PASS", 2),
            presentation.sectionSummaries.verify,
        )
        assertEquals(
            "${SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT)} | tasks.md",
            presentation.sectionSummaries.documents,
        )
    }

    @Test
    fun `build should use focused-stage label and disabled verify fallback`() {
        val presentation = SpecWorkflowWorkspaceSummaryPresenter.build(
            workflow = workflow(currentStage = StageId.TASKS, verifyEnabled = false),
            overviewState = overviewState(currentStage = StageId.TASKS, nextStage = StageId.IMPLEMENT),
            workbenchState = workbenchState(
                currentStage = StageId.TASKS,
                focusedStage = StageId.DESIGN,
                stageStatus = StageProgress.DONE,
                completedChecks = 3,
                totalChecks = 3,
                stepIndex = 2,
                totalSteps = 5,
                fileName = null,
                title = "design.md",
            ),
            guidance = SpecWorkflowStageGuidance(
                headline = "Focus Design",
                summary = "Review design before continuing.",
                checklist = emptyList(),
            ),
            tasks = emptyList(),
            verifyDeltaState = verifyState(
                verifyEnabled = false,
                baselineCount = 1,
                history = emptyList(),
            ),
            gateResult = null,
        )

        assertEquals(SpecCodingBundle.message("spec.toolwindow.overview.secondary.focus"), presentation.stageMetric.title)
        assertEquals(
            "${SpecWorkflowOverviewPresenter.stageLabel(StageId.DESIGN)} / 3/3 / 2/5",
            presentation.stageMetric.value,
        )
        assertEquals(SpecWorkflowWorkspaceChipTone.INFO, presentation.stageMetric.tone)

        assertEquals("0/0", presentation.tasksMetric.value)
        assertEquals(SpecWorkflowWorkspaceChipTone.INFO, presentation.tasksMetric.tone)

        assertEquals(
            "0 / ${SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.disabled")}",
            presentation.verifyMetric.value,
        )
        assertEquals(SpecWorkflowWorkspaceChipTone.MUTED, presentation.verifyMetric.tone)
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.verifyDelta.summary.disabled", 1),
            presentation.sectionSummaries.verify,
        )
        assertEquals(SpecCodingBundle.message("spec.toolwindow.gate.summary.none"), presentation.sectionSummaries.gate)
        assertEquals(
            "${SpecWorkflowOverviewPresenter.stageLabel(StageId.DESIGN)} | design.md",
            presentation.sectionSummaries.documents,
        )
    }

    private fun workflow(
        currentStage: StageId,
        verifyEnabled: Boolean = true,
    ): SpecWorkflow {
        return SpecWorkflow(
            id = "wf-42",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Workflow Demo",
            template = WorkflowTemplate.FULL_SPEC,
            currentStage = currentStage,
            verifyEnabled = verifyEnabled,
        )
    }

    private fun overviewState(
        currentStage: StageId,
        nextStage: StageId?,
    ): SpecWorkflowOverviewState {
        return SpecWorkflowOverviewState(
            workflowId = "wf-42",
            title = "Workflow Demo",
            status = WorkflowStatus.IN_PROGRESS,
            template = WorkflowTemplate.FULL_SPEC,
            switchableTemplates = emptyList(),
            latestTemplateSwitch = null,
            templateCloneTargets = emptyList(),
            templateLockedSummary = "",
            currentStage = currentStage,
            activeStages = listOf(
                StageId.REQUIREMENTS,
                StageId.DESIGN,
                StageId.TASKS,
                StageId.IMPLEMENT,
                StageId.VERIFY,
                StageId.ARCHIVE,
            ),
            nextStage = nextStage,
            gateStatus = null,
            gateSummary = null,
            stageStepper = SpecWorkflowStageStepperState(
                stages = listOf(
                    SpecWorkflowStageStepState(
                        stageId = currentStage,
                        active = true,
                        current = true,
                        progress = StageProgress.IN_PROGRESS,
                    ),
                ),
                canAdvance = true,
                jumpTargets = emptyList(),
                rollbackTargets = emptyList(),
            ),
            refreshedAtMillis = 0L,
        )
    }

    private fun workbenchState(
        currentStage: StageId,
        focusedStage: StageId,
        stageStatus: StageProgress,
        completedChecks: Int,
        totalChecks: Int,
        stepIndex: Int,
        totalSteps: Int,
        fileName: String?,
        title: String = "Artifact",
    ): SpecWorkflowStageWorkbenchState {
        return SpecWorkflowStageWorkbenchState(
            currentStage = currentStage,
            focusedStage = focusedStage,
            progress = SpecWorkflowStageProgressView(
                stepIndex = stepIndex,
                totalSteps = totalSteps,
                stageStatus = stageStatus,
                completedCheckCount = completedChecks,
                totalCheckCount = totalChecks,
                completionChecks = emptyList(),
            ),
            primaryAction = null,
            overflowActions = emptyList(),
            blockers = emptyList(),
            artifactBinding = SpecWorkflowStageArtifactBinding(
                stageId = focusedStage,
                title = title,
                fileName = fileName,
                documentPhase = null,
                mode = SpecWorkflowWorkbenchDocumentMode.READ_ONLY,
                fallbackEditable = false,
            ),
            implementationFocus = null,
            visibleSections = setOf(SpecWorkflowWorkspaceSectionId.OVERVIEW),
        )
    }

    private fun task(id: String, status: TaskStatus): StructuredTask {
        return StructuredTask(
            id = id,
            title = id,
            status = status,
            priority = TaskPriority.P1,
        )
    }

    private fun verifyState(
        verifyEnabled: Boolean,
        baselineCount: Int,
        history: List<VerifyRunHistoryEntry>,
    ): SpecWorkflowVerifyDeltaState {
        return SpecWorkflowVerifyDeltaState(
            workflowId = "wf-42",
            verifyEnabled = verifyEnabled,
            verificationDocumentAvailable = history.isNotEmpty(),
            verificationHistory = history,
            baselineChoices = List(baselineCount) { index ->
                SpecWorkflowReferenceBaselineChoice(
                    workflowId = "wf-base-$index",
                    title = "Baseline $index",
                )
            },
            preferredBaselineChoiceId = null,
            canPinBaseline = false,
            refreshedAtMillis = 0L,
        )
    }

    private fun gateResult(
        status: GateStatus,
        errorCount: Int,
        warningCount: Int,
        total: Int,
    ): GateResult {
        return GateResult(
            status = status,
            violations = emptyList(),
            aggregation = GateAggregation(
                totalViolationCount = total,
                errorCount = errorCount,
                warningCount = warningCount,
                passedRuleCount = 0,
                warningRuleCount = 0,
                errorRuleCount = 0,
                errorViolations = emptyList(),
                warningViolations = emptyList(),
                summary = "summary",
                canProceed = status != GateStatus.ERROR,
                requiresWarningConfirmation = status == GateStatus.WARNING,
            ),
        )
    }
}
