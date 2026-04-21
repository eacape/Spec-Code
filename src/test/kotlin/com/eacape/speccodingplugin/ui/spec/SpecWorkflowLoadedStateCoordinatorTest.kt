package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.CodeContextPack
import com.eacape.speccodingplugin.spec.ExecutionLivePhase
import com.eacape.speccodingplugin.spec.GateResult
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
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.spec.VerifyRunHistoryEntry
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SpecWorkflowLoadedStateCoordinatorTest {

    @Test
    fun `apply should ignore stale load results for another selected workflow`() {
        val recorder = RecordingCallbacks()
        var fallbackCalls = 0
        val coordinator = coordinator(
            buildUiSnapshot = {
                fallbackCalls += 1
                workflowSnapshot(it.id)
            },
        )

        coordinator.apply(
            request = SpecWorkflowLoadedStateApplyRequest(
                workflowId = "wf-current",
                selectedWorkflowId = "wf-other",
                loadedState = loadedState(workflow = workflow("wf-current")),
            ),
            callbacks = recorder,
        )

        assertEquals(0, fallbackCalls)
        assertTrue(recorder.clearOpenedWorkflowUiCalls.isEmpty())
        assertNull(recorder.coreState)
        assertTrue(recorder.sourceCalls.isEmpty())
        assertNull(recorder.taskState)
        assertTrue(recorder.statusTexts.isEmpty())
    }

    @Test
    fun `apply should clear opened workflow ui when workflow can no longer be loaded`() {
        val recorder = RecordingCallbacks()
        val coordinator = coordinator()

        coordinator.apply(
            request = SpecWorkflowLoadedStateApplyRequest(
                workflowId = "wf-missing",
                selectedWorkflowId = "wf-missing",
                loadedState = SpecWorkflowPanelLoadedState(
                    workflow = null,
                    uiSnapshot = null,
                    tasksResult = Result.success(emptyList()),
                    codeContextResult = null,
                    sourcesResult = null,
                    liveProgressByTaskId = emptyMap(),
                ),
            ),
            callbacks = recorder,
        )

        assertEquals(listOf(false), recorder.clearOpenedWorkflowUiCalls)
        assertNull(recorder.coreState)
        assertTrue(recorder.sourceCalls.isEmpty())
        assertNull(recorder.taskState)
    }

    @Test
    fun `apply should forward loaded workflow state and preserve source selection on same workflow`() {
        val workflow = workflow("wf-loaded", status = WorkflowStatus.COMPLETED)
        val snapshot = workflowSnapshot(workflow.id)
        val codeContext = CodeContextPack(phase = workflow.currentPhase)
        val rawTasks = listOf(task("T-1"))
        val decoratedTasks = listOf(task("T-1-decorated"))
        val sources = listOf(source("source-1"))
        val liveProgress = mapOf("T-1" to liveProgress(workflow.id, "T-1"))
        val recorder = RecordingCallbacks()
        var updatedWorkflow: SpecWorkflow? = null
        val coordinator = coordinator(
            decorateTasksWithExecutionState = { _, _, _ -> decoratedTasks },
            currentTimeMillis = { 1234L },
        )

        coordinator.apply(
            request = SpecWorkflowLoadedStateApplyRequest(
                workflowId = workflow.id,
                selectedWorkflowId = workflow.id,
                loadedState = loadedState(
                    workflow = workflow,
                    snapshot = snapshot,
                    tasksResult = Result.success(rawTasks),
                    codeContextResult = Result.success(codeContext),
                    sourcesResult = Result.success(sources),
                    liveProgressByTaskId = liveProgress,
                ),
                followCurrentPhase = true,
                previousSelectedWorkflowId = workflow.id,
            ),
            callbacks = recorder,
            onUpdated = { updatedWorkflow = it },
        )

        assertSame(workflow, recorder.coreState?.workflow)
        assertSame(snapshot, recorder.coreState?.snapshot)
        assertEquals(true, recorder.coreState?.followCurrentPhase)
        assertSame(codeContext, recorder.coreState?.codeContextResult?.getOrThrow())
        assertEquals(
            listOf(SourceApplyCall(workflow, sources, preserveSelection = true)),
            recorder.sourceCalls,
        )
        assertSame(workflow, recorder.taskState?.workflow)
        assertSame(snapshot, recorder.taskState?.snapshot)
        assertEquals(decoratedTasks, recorder.taskState?.tasks)
        assertEquals(liveProgress, recorder.taskState?.liveProgressByTaskId)
        assertEquals(1234L, recorder.taskState?.refreshedAtMillis)
        assertEquals(listOf(workflow.id), recorder.pendingOpenWorkflowRequests)
        assertEquals(listOf(workflow), recorder.actionAvailabilityUpdates)
        assertEquals(emptyList<String>(), recorder.restoredClarificationWorkflowIds)
        assertTrue(recorder.statusTexts.isEmpty())
        assertSame(workflow, updatedWorkflow)
    }

    @Test
    fun `apply should keep workflow visible while surfacing source and task failures`() {
        val workflow = workflow("wf-failure")
        val snapshot = workflowSnapshot(workflow.id)
        val recorder = RecordingCallbacks()
        val coordinator = coordinator(currentTimeMillis = { 5678L })

        coordinator.apply(
            request = SpecWorkflowLoadedStateApplyRequest(
                workflowId = workflow.id,
                selectedWorkflowId = workflow.id,
                loadedState = loadedState(
                    workflow = workflow,
                    snapshot = snapshot,
                    tasksResult = Result.failure(IllegalStateException("tasks failed")),
                    sourcesResult = Result.failure(IllegalStateException("sources failed")),
                    liveProgressByTaskId = mapOf("T-1" to liveProgress(workflow.id, "T-1")),
                ),
                previousSelectedWorkflowId = "wf-previous",
            ),
            callbacks = recorder,
        )

        assertSame(workflow, recorder.coreState?.workflow)
        assertEquals(
            listOf(SourceApplyCall(workflow, emptyList(), preserveSelection = false)),
            recorder.sourceCalls,
        )
        assertEquals(emptyList<StructuredTask>(), recorder.taskState?.tasks)
        assertEquals(emptyMap<String, TaskExecutionLiveProgress>(), recorder.taskState?.liveProgressByTaskId)
        assertEquals(5678L, recorder.taskState?.refreshedAtMillis)
        assertEquals(listOf(workflow.id), recorder.restoredClarificationWorkflowIds)
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.workflow.error", "rendered:sources failed"),
                SpecCodingBundle.message("spec.workflow.error", "rendered:tasks failed"),
            ),
            recorder.statusTexts,
        )
    }

    @Test
    fun `apply should rebuild missing snapshot and skip source refresh on reload payloads`() {
        val workflow = workflow("wf-reload")
        val fallbackSnapshot = workflowSnapshot(workflow.id)
        val recorder = RecordingCallbacks()
        var fallbackCalls = 0
        val coordinator = coordinator(
            buildUiSnapshot = {
                fallbackCalls += 1
                fallbackSnapshot
            },
            currentTimeMillis = { 42L },
        )

        coordinator.apply(
            request = SpecWorkflowLoadedStateApplyRequest(
                workflowId = workflow.id,
                selectedWorkflowId = workflow.id,
                loadedState = loadedState(
                    workflow = workflow,
                    snapshot = null,
                    tasksResult = Result.success(emptyList()),
                    sourcesResult = null,
                ),
                previousSelectedWorkflowId = "wf-previous",
            ),
            callbacks = recorder,
        )

        assertEquals(1, fallbackCalls)
        assertSame(fallbackSnapshot, recorder.coreState?.snapshot)
        assertTrue(recorder.sourceCalls.isEmpty())
        assertEquals(listOf(workflow.id), recorder.restoredClarificationWorkflowIds)
        assertEquals(listOf(workflow.id), recorder.pendingOpenWorkflowRequests)
    }

    @Test
    fun `apply should continue with follow up actions when core ui callback fails`() {
        val workflow = workflow("wf-core-ui-failure")
        val sources = listOf(source("source-1"))
        val tasks = listOf(task("T-1"))
        val recorder = RecordingCallbacks().apply {
            coreFailure = IllegalStateException("detail crashed")
        }
        val coordinator = coordinator(currentTimeMillis = { 314L })

        coordinator.apply(
            request = SpecWorkflowLoadedStateApplyRequest(
                workflowId = workflow.id,
                selectedWorkflowId = workflow.id,
                loadedState = loadedState(
                    workflow = workflow,
                    tasksResult = Result.success(tasks),
                    sourcesResult = Result.success(sources),
                ),
                previousSelectedWorkflowId = "wf-previous",
            ),
            callbacks = recorder,
        )

        assertSame(workflow, recorder.coreState?.workflow)
        assertEquals(
            listOf(SourceApplyCall(workflow, sources, preserveSelection = false)),
            recorder.sourceCalls,
        )
        assertEquals(tasks, recorder.taskState?.tasks)
        assertEquals(listOf(workflow.id), recorder.restoredClarificationWorkflowIds)
        assertEquals(listOf(workflow.id), recorder.pendingOpenWorkflowRequests)
        assertEquals(listOf(workflow), recorder.actionAvailabilityUpdates)
        assertEquals(
            listOf(SpecCodingBundle.message("spec.workflow.error", "rendered:detail crashed")),
            recorder.statusTexts,
        )
    }

    @Test
    fun `apply should keep workflow follow up actions running when task ui callback fails`() {
        val workflow = workflow("wf-task-ui-failure")
        val tasks = listOf(task("T-1"))
        val recorder = RecordingCallbacks().apply {
            taskFailure = IllegalStateException("workspace crashed")
        }
        val coordinator = coordinator(currentTimeMillis = { 2718L })

        coordinator.apply(
            request = SpecWorkflowLoadedStateApplyRequest(
                workflowId = workflow.id,
                selectedWorkflowId = workflow.id,
                loadedState = loadedState(
                    workflow = workflow,
                    tasksResult = Result.success(tasks),
                    sourcesResult = Result.success(emptyList()),
                ),
                previousSelectedWorkflowId = "wf-previous",
            ),
            callbacks = recorder,
        )

        assertSame(workflow, recorder.coreState?.workflow)
        assertEquals(tasks, recorder.taskState?.tasks)
        assertEquals(listOf(workflow.id), recorder.restoredClarificationWorkflowIds)
        assertEquals(listOf(workflow.id), recorder.pendingOpenWorkflowRequests)
        assertEquals(listOf(workflow), recorder.actionAvailabilityUpdates)
        assertEquals(
            listOf(SpecCodingBundle.message("spec.workflow.error", "rendered:workspace crashed")),
            recorder.statusTexts,
        )
    }

    private fun coordinator(
        buildUiSnapshot: (SpecWorkflow) -> SpecWorkflowUiSnapshot = { workflow -> workflowSnapshot(workflow.id) },
        decorateTasksWithExecutionState: (
            workflow: SpecWorkflow,
            tasks: List<StructuredTask>,
            liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
        ) -> List<StructuredTask> = { _, tasks, _ -> tasks },
        currentTimeMillis: () -> Long = { 99L },
    ): SpecWorkflowLoadedStateCoordinator {
        return SpecWorkflowLoadedStateCoordinator(
            buildUiSnapshot = buildUiSnapshot,
            decorateTasksWithExecutionState = decorateTasksWithExecutionState,
            renderFailureMessage = { error -> "rendered:${error.message}" },
            currentTimeMillis = currentTimeMillis,
        )
    }

    private fun loadedState(
        workflow: SpecWorkflow,
        snapshot: SpecWorkflowUiSnapshot? = workflowSnapshot(workflow.id),
        tasksResult: Result<List<StructuredTask>> = Result.success(emptyList()),
        codeContextResult: Result<CodeContextPack>? = null,
        sourcesResult: Result<List<WorkflowSourceAsset>>? = Result.success(emptyList()),
        liveProgressByTaskId: Map<String, TaskExecutionLiveProgress> = emptyMap(),
    ): SpecWorkflowPanelLoadedState {
        return SpecWorkflowPanelLoadedState(
            workflow = workflow,
            uiSnapshot = snapshot,
            tasksResult = tasksResult,
            codeContextResult = codeContextResult,
            sourcesResult = sourcesResult,
            liveProgressByTaskId = liveProgressByTaskId,
        )
    }

    private fun workflow(
        workflowId: String,
        status: WorkflowStatus = WorkflowStatus.IN_PROGRESS,
    ): SpecWorkflow {
        return SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.DESIGN to SpecDocument(
                    id = "design",
                    phase = SpecPhase.DESIGN,
                    content = "design",
                    metadata = SpecMetadata(title = "Design", description = "loaded state test"),
                ),
            ),
            status = status,
            title = "Workflow $workflowId",
            description = "loaded state test",
            template = WorkflowTemplate.FULL_SPEC,
            currentStage = StageId.DESIGN,
            createdAt = 1L,
            updatedAt = 2L,
        )
    }

    private fun task(id: String): StructuredTask {
        return StructuredTask(
            id = id,
            title = "Task $id",
            status = TaskStatus.PENDING,
            priority = TaskPriority.P1,
        )
    }

    private fun source(id: String): WorkflowSourceAsset {
        return WorkflowSourceAsset(
            sourceId = id,
            originalFileName = "$id.md",
            storedRelativePath = "sources/$id.md",
            mediaType = "text/markdown",
            fileSize = 128L,
            contentHash = "hash-$id",
            importedAt = "2026-04-11T00:00:00Z",
            importedFromStage = StageId.DESIGN,
            importedFromEntry = "test",
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
                        executedAt = "2026-04-11T00:01:00Z",
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

    private class RecordingCallbacks : SpecWorkflowLoadedStateCallbacks {
        val clearOpenedWorkflowUiCalls = mutableListOf<Boolean>()
        var coreState: SpecWorkflowLoadedCoreUiState? = null
        val sourceCalls = mutableListOf<SourceApplyCall>()
        var taskState: SpecWorkflowLoadedTaskUiState? = null
        val restoredClarificationWorkflowIds = mutableListOf<String>()
        val pendingOpenWorkflowRequests = mutableListOf<String>()
        val actionAvailabilityUpdates = mutableListOf<SpecWorkflow>()
        val statusTexts = mutableListOf<String>()
        var coreFailure: Throwable? = null
        var sourceFailure: Throwable? = null
        var taskFailure: Throwable? = null
        var restoreClarificationFailure: Throwable? = null
        var pendingOpenFailure: Throwable? = null
        var actionAvailabilityFailure: Throwable? = null
        var statusFailure: Throwable? = null

        override fun clearOpenedWorkflowUi(resetHighlight: Boolean) {
            clearOpenedWorkflowUiCalls += resetHighlight
        }

        override fun applyWorkflowCore(state: SpecWorkflowLoadedCoreUiState) {
            coreState = state
            coreFailure?.let { throw it }
        }

        override fun applyWorkflowSources(
            workflow: SpecWorkflow,
            assets: List<WorkflowSourceAsset>,
            preserveSelection: Boolean,
        ) {
            sourceCalls += SourceApplyCall(workflow, assets, preserveSelection)
            sourceFailure?.let { throw it }
        }

        override fun applyWorkflowTasks(state: SpecWorkflowLoadedTaskUiState) {
            taskState = state
            taskFailure?.let { throw it }
        }

        override fun restorePendingClarificationState(workflowId: String) {
            restoredClarificationWorkflowIds += workflowId
            restoreClarificationFailure?.let { throw it }
        }

        override fun applyPendingOpenWorkflowRequest(workflowId: String) {
            pendingOpenWorkflowRequests += workflowId
            pendingOpenFailure?.let { throw it }
        }

        override fun updateWorkflowActionAvailability(workflow: SpecWorkflow) {
            actionAvailabilityUpdates += workflow
            actionAvailabilityFailure?.let { throw it }
        }

        override fun setStatusText(text: String) {
            statusTexts += text
            statusFailure?.let { throw it }
        }
    }

    private data class SourceApplyCall(
        val workflow: SpecWorkflow,
        val assets: List<WorkflowSourceAsset>,
        val preserveSelection: Boolean,
    )
}
