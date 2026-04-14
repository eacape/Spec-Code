package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.CodeContextPack
import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.spec.VerifyRunHistoryEntry
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowLoadExecutionCoordinatorTest {

    @Test
    fun `requestWorkflowLoad should show progress before scheduling background load and then apply loaded state`() {
        val workflow = workflow("wf-load")
        val snapshot = workflowSnapshot(workflow.id)
        val events = mutableListOf<String>()
        val scheduledBlocks = mutableListOf<() -> Unit>()
        val appliedCalls = mutableListOf<RecordedApply>()

        val coordinator = coordinator(
            reloadWorkflow = {
                events += "load"
                Result.success(workflow)
            },
            buildUiSnapshot = { snapshot },
            showLoadInProgress = { events += "show" },
            launchLoad = { action ->
                events += "schedule"
                scheduledBlocks += action
            },
            applyLoadedWorkflow = { workflowId, loadedState, followCurrentPhase, previousSelectedWorkflowId, onUpdated ->
                events += "apply"
                appliedCalls += RecordedApply(
                    workflowId = workflowId,
                    loadedState = loadedState,
                    followCurrentPhase = followCurrentPhase,
                    previousSelectedWorkflowId = previousSelectedWorkflowId,
                    onUpdated = onUpdated,
                )
            },
        )
        val onUpdated: (SpecWorkflow) -> Unit = {}

        coordinator.requestWorkflowLoad(
            loadTrigger = SpecWorkflowLoadTrigger(
                workflowId = workflow.id,
                includeSources = true,
                followCurrentPhase = true,
                previousSelectedWorkflowId = "wf-prev",
            ),
            onUpdated = onUpdated,
        )

        assertEquals(listOf("show", "schedule"), events)
        assertTrue(appliedCalls.isEmpty())
        assertEquals(1, scheduledBlocks.size)

        scheduledBlocks.single().invoke()

        assertEquals(listOf("show", "schedule", "load", "apply"), events)
        val applied = appliedCalls.single()
        assertEquals(workflow.id, applied.workflowId)
        assertSame(workflow, applied.loadedState.workflow)
        assertSame(snapshot, applied.loadedState.uiSnapshot)
        assertEquals(true, applied.followCurrentPhase)
        assertEquals("wf-prev", applied.previousSelectedWorkflowId)
        assertSame(onUpdated, applied.onUpdated)
        assertTrue(applied.loadedState.sourcesResult?.isSuccess == true)
    }

    @Test
    fun `requestWorkflowLoad should skip sources when trigger only requests reload payload`() {
        val workflow = workflow("wf-reload")
        var listSourcesCalls = 0
        var appliedCall: RecordedApply? = null
        var scheduledBlock: (() -> Unit)? = null

        val coordinator = coordinator(
            reloadWorkflow = { Result.success(workflow) },
            listWorkflowSources = {
                listSourcesCalls += 1
                Result.success(emptyList())
            },
            launchLoad = { action -> scheduledBlock = action },
            applyLoadedWorkflow = { workflowId, loadedState, followCurrentPhase, previousSelectedWorkflowId, onUpdated ->
                appliedCall = RecordedApply(
                    workflowId = workflowId,
                    loadedState = loadedState,
                    followCurrentPhase = followCurrentPhase,
                    previousSelectedWorkflowId = previousSelectedWorkflowId,
                    onUpdated = onUpdated,
                )
            },
        )

        coordinator.requestWorkflowLoad(
            loadTrigger = SpecWorkflowLoadTrigger(
                workflowId = workflow.id,
                includeSources = false,
            ),
        )

        scheduledBlock?.invoke()

        assertEquals(0, listSourcesCalls)
        assertEquals(workflow.id, appliedCall?.workflowId)
        assertNull(appliedCall?.loadedState?.sourcesResult)
        assertEquals(false, appliedCall?.followCurrentPhase)
        assertNull(appliedCall?.previousSelectedWorkflowId)
        assertNull(appliedCall?.onUpdated)
    }

    private fun coordinator(
        reloadWorkflow: (String) -> Result<SpecWorkflow>,
        listWorkflowSources: (String) -> Result<List<com.eacape.speccodingplugin.spec.WorkflowSourceAsset>> = {
            Result.success(emptyList())
        },
        buildUiSnapshot: (SpecWorkflow) -> SpecWorkflowUiSnapshot = { workflowSnapshot(it.id) },
        showLoadInProgress: () -> Unit = {},
        launchLoad: (() -> Unit) -> Unit,
        applyLoadedWorkflow: (
            workflowId: String,
            loadedState: SpecWorkflowPanelLoadedState,
            followCurrentPhase: Boolean,
            previousSelectedWorkflowId: String?,
            onUpdated: ((SpecWorkflow) -> Unit)?,
        ) -> Unit,
    ): SpecWorkflowLoadExecutionCoordinator {
        return SpecWorkflowLoadExecutionCoordinator(
            loadCoordinator = SpecWorkflowPanelLoadCoordinator(
                reloadWorkflow = reloadWorkflow,
                parseTasks = { emptyList<StructuredTask>() },
                buildCodeContext = { CodeContextPack(phase = it.currentPhase) },
                listWorkflowSources = listWorkflowSources,
                buildUiSnapshot = buildUiSnapshot,
                buildTaskLiveProgressByTaskId = { emptyMap() },
            ),
            showLoadInProgress = showLoadInProgress,
            launchLoad = launchLoad,
            applyLoadedWorkflow = applyLoadedWorkflow,
        )
    }

    private fun workflow(workflowId: String): SpecWorkflow {
        return SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.DESIGN to SpecDocument(
                    id = "design",
                    phase = SpecPhase.DESIGN,
                    content = "design",
                    metadata = SpecMetadata(title = "Design", description = "load execution test"),
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Workflow $workflowId",
            description = "load execution test",
            template = WorkflowTemplate.FULL_SPEC,
            currentStage = StageId.DESIGN,
            createdAt = 1L,
            updatedAt = 2L,
        )
    }

    private fun workflowSnapshot(workflowId: String): SpecWorkflowUiSnapshot {
        return SpecWorkflowUiSnapshot(
            overviewState = SpecWorkflowOverviewState(
                workflowId = workflowId,
                title = "Workflow $workflowId",
                status = WorkflowStatus.IN_PROGRESS,
                template = WorkflowTemplate.FULL_SPEC,
                switchableTemplates = listOf(WorkflowTemplate.QUICK_TASK),
                latestTemplateSwitch = null,
                templateCloneTargets = listOf(WorkflowTemplate.QUICK_TASK),
                templateLockedSummary = "locked",
                currentStage = StageId.DESIGN,
                activeStages = listOf(StageId.REQUIREMENTS, StageId.DESIGN, StageId.TASKS, StageId.IMPLEMENT, StageId.ARCHIVE),
                nextStage = StageId.TASKS,
                gateStatus = null,
                gateSummary = null,
                stageStepper = SpecWorkflowStageStepperState(
                    stages = listOf(
                        SpecWorkflowStageStepState(
                            stageId = StageId.DESIGN,
                            active = true,
                            current = true,
                            progress = StageProgress.IN_PROGRESS,
                        ),
                    ),
                    canAdvance = true,
                    jumpTargets = emptyList(),
                    rollbackTargets = emptyList(),
                ),
                refreshedAtMillis = 42L,
            ),
            verifyDeltaState = SpecWorkflowVerifyDeltaState(
                workflowId = workflowId,
                verifyEnabled = true,
                verificationDocumentAvailable = true,
                verificationHistory = listOf(
                    VerifyRunHistoryEntry(
                        runId = "verify-1",
                        planId = "plan-1",
                        executedAt = "2026-04-13T00:01:00Z",
                        occurredAtEpochMs = 1L,
                        currentStage = StageId.VERIFY,
                        conclusion = VerificationConclusion.PASS,
                        summary = "ok",
                        commandCount = 1,
                    ),
                ),
                baselineChoices = emptyList(),
                deltaSummary = null,
                preferredBaselineChoiceId = null,
                canPinBaseline = false,
                refreshedAtMillis = 42L,
            ),
            gateResult = GateResult.fromViolations(emptyList()),
            refreshedAtMillis = 42L,
        )
    }

    private data class RecordedApply(
        val workflowId: String,
        val loadedState: SpecWorkflowPanelLoadedState,
        val followCurrentPhase: Boolean,
        val previousSelectedWorkflowId: String?,
        val onUpdated: ((SpecWorkflow) -> Unit)?,
    )
}
