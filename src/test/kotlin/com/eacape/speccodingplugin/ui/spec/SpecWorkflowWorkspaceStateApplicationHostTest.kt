package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.intellij.openapi.diagnostic.Logger
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SpecWorkflowWorkspaceStateApplicationHostTest {

    @Test
    fun `apply should cache workspace state and emit telemetry`() {
        val ui = Recorder()
        val telemetrySamples = mutableListOf<SpecWorkflowWorkspacePresentationTelemetry>()
        val host = host(
            ui = ui,
            resolveWorkbenchState = { _, state ->
                state.copy(
                    artifactBinding = state.artifactBinding.copy(
                        documentPhase = SpecPhase.IMPLEMENT,
                        fileName = StageId.TASKS.artifactFileName,
                    ),
                )
            },
            supportsStructuredTasksDocumentWorkspaceView = { true },
            telemetrySamples = telemetrySamples,
        )
        val workflow = workflow()
        val overviewState = overviewState(currentStage = StageId.IMPLEMENT, nextStage = StageId.VERIFY)
        val tasks = listOf(task("task-1", TaskStatus.PENDING), task("task-2", TaskStatus.COMPLETED))
        val appliedState = host.apply(
            workflow = workflow,
            overviewState = overviewState,
            tasks = tasks,
            liveProgressByTaskId = liveProgress(),
            verifyDeltaState = verifyState(workflow.id),
            gateResult = GateResult.fromViolations(emptyList()),
            focusedStage = StageId.IMPLEMENT,
        )

        assertSame(appliedState, host.currentState())
        assertEquals(1, ui.showWorkspaceContentCalls)
        assertEquals(listOf(true), ui.workbenchSyncSelections)
        assertEquals(listOf("task-1"), ui.syncedTaskIds)
        assertFalse(ui.visibleSectionIds.last().contains(SpecWorkflowWorkspaceSectionId.TASKS))
        assertNotNull(ui.summaryPresentations.single())
        assertEquals(1, telemetrySamples.size)
        assertEquals(workflow.id, telemetrySamples.single().workflowId)
        assertEquals(StageId.IMPLEMENT, telemetrySamples.single().focusedStage)
        assertEquals(64L, telemetrySamples.single().elapsedMs)
    }

    @Test
    fun `apply should not re-sync task selection when state stays on same implementation focus`() {
        val ui = Recorder()
        val host = host(ui = ui)
        val workflow = workflow()
        val overviewState = overviewState(currentStage = StageId.IMPLEMENT, nextStage = StageId.VERIFY)
        val tasks = listOf(task("task-1", TaskStatus.PENDING))
        val verifyState = verifyState(workflow.id)

        host.apply(
            workflow = workflow,
            overviewState = overviewState,
            tasks = tasks,
            liveProgressByTaskId = liveProgress(),
            verifyDeltaState = verifyState,
            gateResult = null,
            focusedStage = StageId.IMPLEMENT,
        )
        host.apply(
            workflow = workflow,
            overviewState = overviewState,
            tasks = tasks,
            liveProgressByTaskId = liveProgress(),
            verifyDeltaState = verifyState,
            gateResult = null,
            focusedStage = StageId.IMPLEMENT,
        )

        assertEquals(listOf(true, false), ui.workbenchSyncSelections)
        assertEquals(listOf("task-1"), ui.syncedTaskIds)
    }

    @Test
    fun `section override should persist for same token and reset after clear`() {
        val ui = Recorder()
        val host = host(ui = ui)
        val workflow = workflow()
        val overviewState = overviewState(currentStage = StageId.IMPLEMENT, nextStage = StageId.VERIFY)
        val tasks = listOf(task("task-1", TaskStatus.PENDING))
        val verifyState = verifyState(workflow.id)

        host.apply(
            workflow = workflow,
            overviewState = overviewState,
            tasks = tasks,
            liveProgressByTaskId = emptyMap(),
            verifyDeltaState = verifyState,
            gateResult = null,
            focusedStage = StageId.IMPLEMENT,
        )
        assertEquals(false, ui.expandedStates.last()[SpecWorkflowWorkspaceSectionId.VERIFY])

        host.rememberSectionOverride(SpecWorkflowWorkspaceSectionId.VERIFY, true)
        host.apply(
            workflow = workflow,
            overviewState = overviewState,
            tasks = tasks,
            liveProgressByTaskId = emptyMap(),
            verifyDeltaState = verifyState,
            gateResult = null,
            focusedStage = StageId.IMPLEMENT,
        )
        assertTrue(ui.expandedStates.last()[SpecWorkflowWorkspaceSectionId.VERIFY] == true)

        host.clear()
        host.apply(
            workflow = workflow,
            overviewState = overviewState,
            tasks = tasks,
            liveProgressByTaskId = emptyMap(),
            verifyDeltaState = verifyState,
            gateResult = null,
            focusedStage = StageId.IMPLEMENT,
        )
        assertEquals(false, ui.expandedStates.last()[SpecWorkflowWorkspaceSectionId.VERIFY])
    }

    private fun host(
        ui: Recorder,
        resolveWorkbenchState: (SpecWorkflow, SpecWorkflowStageWorkbenchState) -> SpecWorkflowStageWorkbenchState = { _, state ->
            state
        },
        supportsStructuredTasksDocumentWorkspaceView: (SpecWorkflowStageWorkbenchState) -> Boolean = { false },
        telemetrySamples: MutableList<SpecWorkflowWorkspacePresentationTelemetry> = mutableListOf(),
    ): SpecWorkflowWorkspaceStateApplicationHost {
        return SpecWorkflowWorkspaceStateApplicationHost(
            workspaceUi = ui,
            workspacePresentationTelemetry = SpecWorkflowWorkspacePresentationTelemetryTracker(
                logger = mockk<Logger>(relaxed = true),
                nanoTimeProvider = sequenceNanoTimeProvider(0L, 64_000_000L, 128_000_000L, 192_000_000L),
                emitTelemetry = { _, telemetry ->
                    telemetrySamples += telemetry
                },
            ),
            resolveWorkbenchState = resolveWorkbenchState,
            supportsStructuredTasksDocumentWorkspaceView = supportsStructuredTasksDocumentWorkspaceView,
        )
    }

    private fun workflow(): SpecWorkflow {
        return SpecWorkflow(
            id = "wf-workspace",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to SpecDocument(
                    id = "implement",
                    phase = SpecPhase.IMPLEMENT,
                    content = "# Tasks\n\n- [ ] task-1",
                    metadata = SpecMetadata(title = "Tasks", description = "workspace host test"),
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Workflow Workspace",
            description = "workspace host test",
            template = WorkflowTemplate.FULL_SPEC,
            currentStage = StageId.IMPLEMENT,
            createdAt = 1L,
            updatedAt = 2L,
            changeIntent = SpecChangeIntent.FULL,
        )
    }

    private fun overviewState(
        currentStage: StageId,
        nextStage: StageId?,
    ): SpecWorkflowOverviewState {
        return SpecWorkflowOverviewState(
            workflowId = "wf-workspace",
            title = "Workflow Workspace",
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
                        stageId = StageId.REQUIREMENTS,
                        active = true,
                        current = false,
                        progress = StageProgress.DONE,
                    ),
                    SpecWorkflowStageStepState(
                        stageId = StageId.DESIGN,
                        active = true,
                        current = false,
                        progress = StageProgress.DONE,
                    ),
                    SpecWorkflowStageStepState(
                        stageId = StageId.TASKS,
                        active = true,
                        current = false,
                        progress = StageProgress.DONE,
                    ),
                    SpecWorkflowStageStepState(
                        stageId = StageId.IMPLEMENT,
                        active = true,
                        current = true,
                        progress = StageProgress.IN_PROGRESS,
                    ),
                    SpecWorkflowStageStepState(
                        stageId = StageId.VERIFY,
                        active = false,
                        current = false,
                        progress = StageProgress.NOT_STARTED,
                    ),
                    SpecWorkflowStageStepState(
                        stageId = StageId.ARCHIVE,
                        active = false,
                        current = false,
                        progress = StageProgress.NOT_STARTED,
                    ),
                ),
                canAdvance = true,
                jumpTargets = emptyList(),
                rollbackTargets = emptyList(),
            ),
            refreshedAtMillis = 42L,
        )
    }

    private fun task(
        taskId: String,
        status: TaskStatus,
    ): StructuredTask {
        return StructuredTask(
            id = taskId,
            title = "Task $taskId",
            status = status,
            priority = TaskPriority.P1,
        )
    }

    private fun liveProgress(): Map<String, TaskExecutionLiveProgress> {
        return mapOf(
            "task-1" to TaskExecutionLiveProgress(
                workflowId = "wf-workspace",
                runId = "run-task-1",
                taskId = "task-1",
                phase = com.eacape.speccodingplugin.spec.ExecutionLivePhase.STREAMING,
                startedAt = Instant.EPOCH,
                lastUpdatedAt = Instant.EPOCH,
            ),
        )
    }

    private fun verifyState(workflowId: String): SpecWorkflowVerifyDeltaState {
        return SpecWorkflowVerifyDeltaState(
            workflowId = workflowId,
            verifyEnabled = true,
            verificationDocumentAvailable = true,
            verificationHistory = emptyList(),
            baselineChoices = emptyList(),
            deltaSummary = null,
            preferredBaselineChoiceId = null,
            canPinBaseline = false,
            refreshedAtMillis = 42L,
        )
    }

    private fun sequenceNanoTimeProvider(vararg values: Long): () -> Long {
        val queue = ArrayDeque(values.toList())
        val lastValue = values.lastOrNull() ?: 0L
        return {
            if (queue.isEmpty()) {
                lastValue
            } else {
                queue.removeFirst()
            }
        }
    }

    private class Recorder : SpecWorkflowWorkspaceStateApplicationUi {
        var showWorkspaceContentCalls = 0
        val summaryPresentations = mutableListOf<SpecWorkflowWorkspaceSummaryPresentation>()
        val workbenchSyncSelections = mutableListOf<Boolean>()
        val syncedTaskIds = mutableListOf<String>()
        val visibleSectionIds = mutableListOf<Set<SpecWorkflowWorkspaceSectionId>>()
        val expandedStates = mutableListOf<Map<SpecWorkflowWorkspaceSectionId, Boolean>>()

        override fun showWorkspaceContent() {
            showWorkspaceContentCalls += 1
        }

        override fun updateOverview(
            overviewState: SpecWorkflowOverviewState,
            workbenchState: SpecWorkflowStageWorkbenchState,
        ) = Unit

        override fun applySummary(presentation: SpecWorkflowWorkspaceSummaryPresentation) {
            summaryPresentations += presentation
        }

        override fun updateWorkbenchState(
            state: SpecWorkflowStageWorkbenchState,
            syncSelection: Boolean,
        ) {
            workbenchSyncSelections += syncSelection
        }

        override fun syncStructuredTaskSelection(taskId: String) {
            syncedTaskIds += taskId
        }

        override fun updateDocumentWorkspaceViewPresentation(workbenchState: SpecWorkflowStageWorkbenchState) = Unit

        override fun applyWorkspaceSectionPresentation(
            summaries: SpecWorkflowWorkspaceSectionSummaries,
            visibleSectionIds: Set<SpecWorkflowWorkspaceSectionId>,
            expandedStates: Map<SpecWorkflowWorkspaceSectionId, Boolean>,
        ) {
            this.visibleSectionIds += visibleSectionIds
            this.expandedStates += expandedStates
        }
    }
}
