package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.CodeContextPack
import com.eacape.speccodingplugin.spec.ExecutionLivePhase
import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.spec.VerifyRunHistoryEntry
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Instant

class SpecWorkflowStateApplicationAdapterTest {

    @Test
    fun `list refresh callbacks should delegate ui actions`() {
        val recorder = Recorder()
        val adapter = recorder.adapter()
        val items = listOf(workflowListItem("wf-1"))

        adapter.listRefreshCallbacks.cancelWorkflowSwitcherPopup()
        adapter.listRefreshCallbacks.updateWorkflowItems(items)
        adapter.listRefreshCallbacks.setStatusText("ready")
        adapter.listRefreshCallbacks.setSwitchWorkflowEnabled(true)
        adapter.listRefreshCallbacks.dropPendingOpenRequestIfInvalid(setOf("wf-1", "wf-2"))
        adapter.listRefreshCallbacks.highlightWorkflow("wf-2")
        adapter.listRefreshCallbacks.loadWorkflow("wf-2")
        adapter.listRefreshCallbacks.clearOpenedWorkflowUi(resetHighlight = true)

        assertEquals(1, recorder.cancelWorkflowSwitcherPopupCalls)
        assertEquals(listOf(items), recorder.workflowItemsUpdates)
        assertEquals(listOf("ready"), recorder.statusTexts)
        assertEquals(listOf(true), recorder.switchWorkflowEnabledUpdates)
        assertEquals(listOf(setOf("wf-1", "wf-2")), recorder.validWorkflowIdUpdates)
        assertEquals(listOf("wf-2"), recorder.highlightedWorkflowIds)
        assertEquals(listOf("wf-2"), recorder.loadedWorkflowIds)
        assertEquals(listOf(true), recorder.clearOpenedWorkflowUiCalls)
    }

    @Test
    fun `loaded state callbacks should delegate core task and action updates`() {
        val recorder = Recorder()
        val adapter = recorder.adapter()
        val workflow = workflow("wf-loaded")
        val snapshot = workflowSnapshot(workflow.id)
        val codeContext = Result.success(CodeContextPack(phase = workflow.currentPhase))
        val tasks = listOf(task("T-1"))
        val liveProgress = mapOf("T-1" to liveProgress(workflow.id, "T-1"))
        val sources = listOf(source("source-1"))

        adapter.loadedStateCallbacks.applyWorkflowCore(
            SpecWorkflowLoadedCoreUiState(
                workflow = workflow,
                snapshot = snapshot,
                followCurrentPhase = true,
                codeContextResult = codeContext,
            ),
        )
        adapter.loadedStateCallbacks.applyWorkflowSources(workflow, sources, preserveSelection = true)
        adapter.loadedStateCallbacks.applyWorkflowTasks(
            SpecWorkflowLoadedTaskUiState(
                workflow = workflow,
                snapshot = snapshot,
                tasks = tasks,
                liveProgressByTaskId = liveProgress,
                refreshedAtMillis = 99L,
            ),
        )
        adapter.loadedStateCallbacks.restorePendingClarificationState(workflow.id)
        adapter.loadedStateCallbacks.applyPendingOpenWorkflowRequest(workflow.id)
        adapter.loadedStateCallbacks.updateWorkflowActionAvailability(workflow.copy(status = WorkflowStatus.COMPLETED))
        adapter.loadedStateCallbacks.updateWorkflowActionAvailability(workflow.copy(status = WorkflowStatus.IN_PROGRESS))
        adapter.loadedStateCallbacks.setStatusText("done")

        assertSame(workflow, recorder.currentWorkflow)
        assertEquals(listOf(workflow.id), recorder.syncedClarificationWorkflowIds)
        assertEquals(listOf(workflow.id), recorder.phaseIndicatorUpdates)
        assertEquals(listOf(snapshot.overviewState), recorder.overviewUpdates)
        assertEquals(listOf(snapshot.verifyDeltaState), recorder.verifyDeltaUpdates)
        assertEquals(
            listOf(GateResultCall(workflow.id, snapshot.gateResult, snapshot.refreshedAtMillis)),
            recorder.gateResultUpdates,
        )
        assertEquals(listOf(DetailWorkflowCall(workflow, true)), recorder.detailWorkflowUpdates)
        assertEquals(listOf(CodeContextCall(workflow, codeContext)), recorder.codeContextUpdates)
        assertEquals(listOf(SourceCall(workflow, sources, true)), recorder.sourceUpdates)
        assertEquals(
            listOf(TaskPanelCall(workflow.id, tasks, liveProgress, 99L)),
            recorder.tasksPanelUpdates,
        )
        assertEquals(
            listOf(TaskPanelCall(workflow.id, tasks, liveProgress, 99L)),
            recorder.detailTasksPanelUpdates,
        )
        assertEquals(
            listOf(WorkspacePresentationCall(workflow, snapshot.overviewState, tasks, liveProgress, snapshot.verifyDeltaState, snapshot.gateResult)),
            recorder.workspacePresentationUpdates,
        )
        assertEquals(listOf(workflow.id), recorder.restoredClarificationWorkflowIds)
        assertEquals(listOf(workflow.id), recorder.pendingOpenWorkflowRequests)
        assertEquals(listOf(true, false), recorder.archiveEnabledUpdates)
        assertEquals(listOf(true, true), recorder.createWorktreeEnabledUpdates)
        assertEquals(listOf(true, true), recorder.mergeWorktreeEnabledUpdates)
        assertEquals(listOf(true, true), recorder.deltaEnabledUpdates)
        assertEquals(listOf("done"), recorder.statusTexts)
    }

    @Test
    fun `show workflow load in progress should forward loading updates in order`() {
        val recorder = Recorder()
        val adapter = recorder.adapter()

        adapter.showWorkflowLoadInProgress()

        assertEquals(
            listOf(
                "overviewLoading",
                "verifyLoading",
                "tasksLoading",
                "detailTasksLoading",
                "gateLoading",
                "workspaceContent",
            ),
            recorder.loadingEvents,
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
                    metadata = SpecMetadata(title = "Design", description = "state adapter test"),
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Workflow $workflowId",
            description = "state adapter test",
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
                        executedAt = "2026-04-14T00:01:00Z",
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

    private fun workflowListItem(workflowId: String): SpecWorkflowListPanel.WorkflowListItem {
        return SpecWorkflowListPanel.WorkflowListItem(
            workflowId = workflowId,
            title = "Workflow $workflowId",
            description = "state adapter test",
            currentPhase = SpecPhase.DESIGN,
            currentStageLabel = "Design",
            status = WorkflowStatus.IN_PROGRESS,
            updatedAt = 1L,
            changeIntent = SpecChangeIntent.FULL,
            baselineWorkflowId = null,
        )
    }

    private fun task(taskId: String): StructuredTask {
        return StructuredTask(
            id = taskId,
            title = "Task $taskId",
            status = TaskStatus.PENDING,
            priority = TaskPriority.P1,
        )
    }

    private fun liveProgress(workflowId: String, taskId: String): TaskExecutionLiveProgress {
        return TaskExecutionLiveProgress(
            workflowId = workflowId,
            runId = "run-$taskId",
            taskId = taskId,
            phase = ExecutionLivePhase.STREAMING,
            startedAt = Instant.EPOCH,
            lastUpdatedAt = Instant.EPOCH,
        )
    }

    private fun source(sourceId: String): WorkflowSourceAsset {
        return WorkflowSourceAsset(
            sourceId = sourceId,
            originalFileName = "$sourceId.md",
            storedRelativePath = "sources/$sourceId.md",
            mediaType = "text/markdown",
            fileSize = 128L,
            contentHash = "hash-$sourceId",
            importedAt = "2026-04-14T00:00:00Z",
            importedFromStage = StageId.DESIGN,
            importedFromEntry = "test",
        )
    }

    private class Recorder : SpecWorkflowStateApplicationUi {
        var cancelWorkflowSwitcherPopupCalls = 0
        val workflowItemsUpdates = mutableListOf<List<SpecWorkflowListPanel.WorkflowListItem>>()
        val statusTexts = mutableListOf<String?>()
        val switchWorkflowEnabledUpdates = mutableListOf<Boolean>()
        val validWorkflowIdUpdates = mutableListOf<Set<String>>()
        val highlightedWorkflowIds = mutableListOf<String?>()
        val loadedWorkflowIds = mutableListOf<String>()
        val clearOpenedWorkflowUiCalls = mutableListOf<Boolean>()
        var currentWorkflow: SpecWorkflow? = null
        val syncedClarificationWorkflowIds = mutableListOf<String>()
        val phaseIndicatorUpdates = mutableListOf<String>()
        val overviewUpdates = mutableListOf<SpecWorkflowOverviewState>()
        val verifyDeltaUpdates = mutableListOf<SpecWorkflowVerifyDeltaState>()
        val gateResultUpdates = mutableListOf<GateResultCall>()
        val detailWorkflowUpdates = mutableListOf<DetailWorkflowCall>()
        val codeContextUpdates = mutableListOf<CodeContextCall>()
        val sourceUpdates = mutableListOf<SourceCall>()
        val tasksPanelUpdates = mutableListOf<TaskPanelCall>()
        val detailTasksPanelUpdates = mutableListOf<TaskPanelCall>()
        val workspacePresentationUpdates = mutableListOf<WorkspacePresentationCall>()
        val restoredClarificationWorkflowIds = mutableListOf<String>()
        val pendingOpenWorkflowRequests = mutableListOf<String>()
        val createWorktreeEnabledUpdates = mutableListOf<Boolean>()
        val mergeWorktreeEnabledUpdates = mutableListOf<Boolean>()
        val deltaEnabledUpdates = mutableListOf<Boolean>()
        val archiveEnabledUpdates = mutableListOf<Boolean>()
        val loadingEvents = mutableListOf<String>()

        fun adapter(): SpecWorkflowStateApplicationAdapter {
            return SpecWorkflowStateApplicationAdapter(ui = this)
        }

        override fun cancelWorkflowSwitcherPopup() {
            cancelWorkflowSwitcherPopupCalls += 1
        }

        override fun updateWorkflowItems(items: List<SpecWorkflowListPanel.WorkflowListItem>) {
            workflowItemsUpdates += items
        }

        override fun setStatusText(text: String?) {
            statusTexts += text
        }

        override fun setSwitchWorkflowEnabled(enabled: Boolean) {
            switchWorkflowEnabledUpdates += enabled
        }

        override fun dropPendingOpenRequestIfInvalid(validWorkflowIds: Set<String>) {
            validWorkflowIdUpdates += validWorkflowIds
        }

        override fun highlightWorkflow(workflowId: String?) {
            highlightedWorkflowIds += workflowId
        }

        override fun loadWorkflow(workflowId: String) {
            loadedWorkflowIds += workflowId
        }

        override fun clearOpenedWorkflowUi(resetHighlight: Boolean) {
            clearOpenedWorkflowUiCalls += resetHighlight
        }

        override fun applyWorkflowCore(state: SpecWorkflowLoadedCoreUiState) {
            currentWorkflow = state.workflow
            syncedClarificationWorkflowIds += state.workflow.id
            phaseIndicatorUpdates += state.workflow.id
            overviewUpdates += state.snapshot.overviewState
            verifyDeltaUpdates += state.snapshot.verifyDeltaState
            gateResultUpdates += GateResultCall(
                state.workflow.id,
                state.snapshot.gateResult,
                state.snapshot.refreshedAtMillis,
            )
            detailWorkflowUpdates += DetailWorkflowCall(state.workflow, state.followCurrentPhase)
            codeContextUpdates += CodeContextCall(state.workflow, state.codeContextResult)
        }

        override fun applyWorkflowSources(
            workflow: SpecWorkflow,
            assets: List<WorkflowSourceAsset>,
            preserveSelection: Boolean,
        ) {
            sourceUpdates += SourceCall(workflow, assets, preserveSelection)
        }

        override fun applyWorkflowTasks(state: SpecWorkflowLoadedTaskUiState) {
            tasksPanelUpdates += TaskPanelCall(
                state.workflow.id,
                state.tasks,
                state.liveProgressByTaskId,
                state.refreshedAtMillis,
            )
            detailTasksPanelUpdates += TaskPanelCall(
                state.workflow.id,
                state.tasks,
                state.liveProgressByTaskId,
                state.refreshedAtMillis,
            )
            workspacePresentationUpdates += WorkspacePresentationCall(
                workflow = state.workflow,
                overviewState = state.snapshot.overviewState,
                tasks = state.tasks,
                liveProgressByTaskId = state.liveProgressByTaskId,
                verifyDeltaState = state.snapshot.verifyDeltaState,
                gateResult = state.snapshot.gateResult,
            )
        }

        override fun restorePendingClarificationState(workflowId: String) {
            restoredClarificationWorkflowIds += workflowId
        }

        override fun applyPendingOpenWorkflowRequest(workflowId: String) {
            pendingOpenWorkflowRequests += workflowId
        }

        override fun updateWorkflowActionAvailability(workflow: SpecWorkflow) {
            createWorktreeEnabledUpdates += true
            mergeWorktreeEnabledUpdates += true
            deltaEnabledUpdates += true
            archiveEnabledUpdates += (workflow.status == WorkflowStatus.COMPLETED)
        }

        override fun showWorkflowLoadInProgress() {
            loadingEvents += "overviewLoading"
            loadingEvents += "verifyLoading"
            loadingEvents += "tasksLoading"
            loadingEvents += "detailTasksLoading"
            loadingEvents += "gateLoading"
            loadingEvents += "workspaceContent"
        }
    }

    private data class GateResultCall(
        val workflowId: String,
        val gateResult: GateResult?,
        val refreshedAtMillis: Long,
    )

    private data class DetailWorkflowCall(
        val workflow: SpecWorkflow,
        val followCurrentPhase: Boolean,
    )

    private data class CodeContextCall(
        val workflow: SpecWorkflow,
        val codeContextResult: Result<CodeContextPack>?,
    )

    private data class SourceCall(
        val workflow: SpecWorkflow,
        val assets: List<WorkflowSourceAsset>,
        val preserveSelection: Boolean,
    )

    private data class TaskPanelCall(
        val workflowId: String,
        val tasks: List<StructuredTask>,
        val liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
        val refreshedAtMillis: Long,
    )

    private data class WorkspacePresentationCall(
        val workflow: SpecWorkflow,
        val overviewState: SpecWorkflowOverviewState,
        val tasks: List<StructuredTask>,
        val liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
        val verifyDeltaState: SpecWorkflowVerifyDeltaState,
        val gateResult: GateResult?,
    )
}
