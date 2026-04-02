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
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.spec.VerifyRunHistoryEntry
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SpecWorkflowPanelLoadCoordinatorTest {

    @Test
    fun `load should gather workflow snapshot, tasks, sources and live progress when workflow exists`() {
        val workflow = workflow(id = "wf-load")
        val tasks = listOf(task("T-1"))
        val codeContextPack = CodeContextPack(phase = workflow.currentPhase)
        val sources = listOf(source("source-1"))
        val liveProgress = mapOf("T-1" to liveProgress(workflow.id, "T-1"))
        val snapshot = workflowSnapshot(workflow.id)

        var parseTasksCalls = 0
        var buildCodeContextCalls = 0
        var listSourcesCalls = 0
        var snapshotCalls = 0
        var liveProgressCalls = 0

        val coordinator = SpecWorkflowPanelLoadCoordinator(
            reloadWorkflow = { Result.success(workflow) },
            parseTasks = {
                parseTasksCalls += 1
                tasks
            },
            buildCodeContext = {
                buildCodeContextCalls += 1
                codeContextPack
            },
            listWorkflowSources = {
                listSourcesCalls += 1
                Result.success(sources)
            },
            buildUiSnapshot = {
                snapshotCalls += 1
                snapshot
            },
            buildTaskLiveProgressByTaskId = {
                liveProgressCalls += 1
                liveProgress
            },
        )

        val loadedState = coordinator.load(
            SpecWorkflowPanelLoadRequest(
                workflowId = workflow.id,
                includeSources = true,
            ),
        )

        assertSame(workflow, loadedState.workflow)
        assertSame(snapshot, loadedState.uiSnapshot)
        assertEquals(tasks, loadedState.tasksResult.getOrThrow())
        assertSame(codeContextPack, loadedState.codeContextResult?.getOrThrow())
        assertEquals(sources, loadedState.sourcesResult?.getOrThrow())
        assertEquals(liveProgress, loadedState.liveProgressByTaskId)
        assertEquals(1, parseTasksCalls)
        assertEquals(1, buildCodeContextCalls)
        assertEquals(1, listSourcesCalls)
        assertEquals(1, snapshotCalls)
        assertEquals(1, liveProgressCalls)
    }

    @Test
    fun `load should skip workflow scoped loaders when workflow is unavailable`() {
        var parseTasksCalls = 0
        var buildCodeContextCalls = 0
        var listSourcesCalls = 0
        var snapshotCalls = 0
        var liveProgressCalls = 0

        val coordinator = SpecWorkflowPanelLoadCoordinator(
            reloadWorkflow = { Result.failure(IllegalStateException("missing")) },
            parseTasks = {
                parseTasksCalls += 1
                emptyList()
            },
            buildCodeContext = {
                buildCodeContextCalls += 1
                CodeContextPack(phase = SpecPhase.DESIGN)
            },
            listWorkflowSources = {
                listSourcesCalls += 1
                Result.success(emptyList())
            },
            buildUiSnapshot = {
                snapshotCalls += 1
                workflowSnapshot("wf-missing")
            },
            buildTaskLiveProgressByTaskId = {
                liveProgressCalls += 1
                emptyMap()
            },
        )

        val loadedState = coordinator.load(
            SpecWorkflowPanelLoadRequest(
                workflowId = "wf-missing",
                includeSources = true,
            ),
        )

        assertNull(loadedState.workflow)
        assertNull(loadedState.uiSnapshot)
        assertEquals(emptyList<StructuredTask>(), loadedState.tasksResult.getOrThrow())
        assertNull(loadedState.codeContextResult)
        assertNull(loadedState.sourcesResult)
        assertTrue(loadedState.liveProgressByTaskId.isEmpty())
        assertEquals(1, parseTasksCalls)
        assertEquals(0, buildCodeContextCalls)
        assertEquals(0, listSourcesCalls)
        assertEquals(0, snapshotCalls)
        assertEquals(0, liveProgressCalls)
    }

    @Test
    fun `load should preserve partial failures without dropping loaded workflow`() {
        val workflow = workflow(id = "wf-partial")
        val tasksFailure = IllegalStateException("tasks failed")
        val contextFailure = IllegalStateException("context failed")

        val coordinator = SpecWorkflowPanelLoadCoordinator(
            reloadWorkflow = { Result.success(workflow) },
            parseTasks = { throw tasksFailure },
            buildCodeContext = { throw contextFailure },
            listWorkflowSources = { Result.failure(IllegalStateException("sources failed")) },
            buildUiSnapshot = { workflowSnapshot(workflow.id) },
            buildTaskLiveProgressByTaskId = { emptyMap() },
        )

        val loadedState = coordinator.load(
            SpecWorkflowPanelLoadRequest(
                workflowId = workflow.id,
                includeSources = true,
            ),
        )

        assertSame(workflow, loadedState.workflow)
        assertNotNull(loadedState.uiSnapshot)
        assertEquals("tasks failed", loadedState.tasksResult.exceptionOrNull()?.message)
        assertEquals("context failed", loadedState.codeContextResult?.exceptionOrNull()?.message)
        assertEquals("sources failed", loadedState.sourcesResult?.exceptionOrNull()?.message)
    }

    @Test
    fun `load should not query sources when caller only needs reload payload`() {
        val workflow = workflow(id = "wf-reload")
        var listSourcesCalls = 0

        val coordinator = SpecWorkflowPanelLoadCoordinator(
            reloadWorkflow = { Result.success(workflow) },
            parseTasks = { emptyList() },
            buildCodeContext = { CodeContextPack(phase = workflow.currentPhase) },
            listWorkflowSources = {
                listSourcesCalls += 1
                Result.success(emptyList())
            },
            buildUiSnapshot = { workflowSnapshot(workflow.id) },
            buildTaskLiveProgressByTaskId = { emptyMap() },
        )

        val loadedState = coordinator.load(
            SpecWorkflowPanelLoadRequest(
                workflowId = workflow.id,
                includeSources = false,
            ),
        )

        assertSame(workflow, loadedState.workflow)
        assertNull(loadedState.sourcesResult)
        assertEquals(0, listSourcesCalls)
    }

    private fun workflow(id: String): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.DESIGN to SpecDocument(
                    id = "design",
                    phase = SpecPhase.DESIGN,
                    content = "design",
                    metadata = SpecMetadata(title = "Design", description = "workflow load test"),
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Workflow $id",
            description = "workflow load test",
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
            importedAt = "2026-04-02T00:00:00Z",
            importedFromStage = StageId.DESIGN,
            importedFromEntry = "test",
        )
    }

    private fun liveProgress(workflowId: String, taskId: String): TaskExecutionLiveProgress {
        return TaskExecutionLiveProgress(
            workflowId = workflowId,
            runId = "run-$taskId",
            taskId = taskId,
            phase = com.eacape.speccodingplugin.spec.ExecutionLivePhase.STREAMING,
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
                        executedAt = "2026-04-02T00:01:00Z",
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
}
