package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.SpecChangeIntent
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SpecWorkflowWorkspacePresentationRequestBuilderTest {

    @Test
    fun `buildRefreshRequest should reuse applied workspace state without fallbacks`() {
        val workflow = workflow(id = "wf-refresh")
        val overviewState = overviewState(workflowId = workflow.id, title = "cached-overview")
        val verifyDeltaState = verifyDeltaState(workflowId = workflow.id, refreshedAtMillis = 300L)
        val gateResult = GateResult(status = GateStatus.PASS, violations = emptyList())
        val appliedState = appliedState(
            overviewState = overviewState,
            verifyDeltaState = verifyDeltaState,
            gateResult = gateResult,
        )
        val builder = builder()

        val request = builder.buildRefreshRequest(
            workflow = workflow,
            appliedState = appliedState,
        )

        assertSame(overviewState, request?.overviewState)
        assertSame(verifyDeltaState, request?.verifyDeltaState)
        assertEquals(appliedState.tasks, request?.tasks)
        assertEquals(appliedState.liveProgressByTaskId, request?.liveProgressByTaskId)
        assertSame(gateResult, request?.gateResult)
        assertTrue(recorder.overviewBuilds.isEmpty())
        assertTrue(recorder.verifyBuilds.isEmpty())
    }

    @Test
    fun `buildFocusStageRequest should reuse applied workspace state when available`() {
        val workflow = workflow(id = "wf-focus")
        val overviewState = overviewState(workflowId = workflow.id, title = "focused-overview")
        val verifyDeltaState = verifyDeltaState(workflowId = workflow.id, refreshedAtMillis = 400L)
        val appliedState = appliedState(
            overviewState = overviewState,
            verifyDeltaState = verifyDeltaState,
            gateResult = null,
        )
        val builder = builder(currentTimeMillis = 999L)

        val request = builder.buildFocusStageRequest(
            workflow = workflow,
            appliedState = appliedState,
        )

        assertSame(overviewState, request?.overviewState)
        assertSame(verifyDeltaState, request?.verifyDeltaState)
        assertEquals(appliedState.tasks, request?.tasks)
        assertEquals(appliedState.liveProgressByTaskId, request?.liveProgressByTaskId)
        assertTrue(recorder.overviewBuilds.isEmpty())
        assertTrue(recorder.verifyBuilds.isEmpty())
    }

    @Test
    fun `buildFocusStageRequest should fall back to overview and verify builders when workspace state is missing`() {
        val workflow = workflow(id = "wf-fallback")
        val builder = builder(currentTimeMillis = 1234L)

        val request = builder.buildFocusStageRequest(
            workflow = workflow,
            appliedState = null,
        )

        assertEquals("fallback-overview:wf-fallback", request?.overviewState?.title)
        assertEquals(workflow.id, request?.verifyDeltaState?.workflowId)
        assertEquals(1234L, request?.verifyDeltaState?.refreshedAtMillis)
        assertEquals(listOf("wf-fallback"), recorder.overviewBuilds)
        assertEquals(listOf("wf-fallback@1234"), recorder.verifyBuilds)
        assertTrue(request?.tasks?.isEmpty() == true)
        assertTrue(request?.liveProgressByTaskId?.isEmpty() == true)
        assertNull(request?.gateResult)
    }

    @Test
    fun `request builders should return null when workflow or applied state is missing`() {
        val workflow = workflow(id = "wf-missing")
        val overviewState = overviewState(workflowId = workflow.id, title = "cached")
        val verifyDeltaState = verifyDeltaState(workflowId = workflow.id, refreshedAtMillis = 1L)
        val appliedState = appliedState(
            overviewState = overviewState,
            verifyDeltaState = verifyDeltaState,
            gateResult = null,
        )
        val builder = builder()

        assertNull(builder.buildRefreshRequest(workflow = null, appliedState = appliedState))
        assertNull(builder.buildRefreshRequest(workflow = workflow, appliedState = null))
        assertNull(builder.buildFocusStageRequest(workflow = null, appliedState = appliedState))
        assertTrue(recorder.overviewBuilds.isEmpty())
        assertTrue(recorder.verifyBuilds.isEmpty())
    }

    private val recorder = RecordingEnvironment()

    private fun builder(currentTimeMillis: Long = 500L): SpecWorkflowWorkspacePresentationRequestBuilder {
        return SpecWorkflowWorkspacePresentationRequestBuilder(
            buildOverviewState = { workflow ->
                recorder.overviewBuilds += workflow.id
                overviewState(
                    workflowId = workflow.id,
                    title = "fallback-overview:${workflow.id}",
                    refreshedAtMillis = currentTimeMillis,
                )
            },
            buildVerifyDeltaState = { workflow, refreshedAtMillis ->
                recorder.verifyBuilds += "${workflow.id}@$refreshedAtMillis"
                verifyDeltaState(
                    workflowId = workflow.id,
                    refreshedAtMillis = refreshedAtMillis,
                )
            },
            currentTimeMillis = { currentTimeMillis },
        )
    }

    private fun workflow(id: String): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = id,
            template = WorkflowTemplate.FULL_SPEC,
            currentStage = StageId.IMPLEMENT,
            createdAt = 1L,
            updatedAt = 2L,
            changeIntent = SpecChangeIntent.FULL,
        )
    }

    private fun appliedState(
        overviewState: SpecWorkflowOverviewState,
        verifyDeltaState: SpecWorkflowVerifyDeltaState,
        gateResult: GateResult?,
    ): SpecWorkflowWorkspaceAppliedState {
        val tasks = listOf(
            StructuredTask(
                id = "task-1",
                title = "task-1",
                status = TaskStatus.PENDING,
                priority = TaskPriority.P1,
            ),
        )
        val liveProgress = mapOf(
            "task-1" to TaskExecutionLiveProgress(
                workflowId = overviewState.workflowId,
                runId = "run-1",
                taskId = "task-1",
                phase = com.eacape.speccodingplugin.spec.ExecutionLivePhase.STREAMING,
                startedAt = Instant.parse("2026-04-14T00:00:00Z"),
                lastUpdatedAt = Instant.parse("2026-04-14T00:00:05Z"),
            ),
        )
        return SpecWorkflowWorkspaceAppliedState(
            overviewState = overviewState,
            workbenchState = workbenchState(),
            verifyDeltaState = verifyDeltaState,
            gateResult = gateResult,
            tasks = tasks,
            liveProgressByTaskId = liveProgress,
        )
    }

    private fun workbenchState(): SpecWorkflowStageWorkbenchState {
        return SpecWorkflowStageWorkbenchState(
            currentStage = StageId.IMPLEMENT,
            focusedStage = StageId.IMPLEMENT,
            progress = SpecWorkflowStageProgressView(
                stepIndex = 1,
                totalSteps = 1,
                stageStatus = StageProgress.IN_PROGRESS,
                completedCheckCount = 0,
                totalCheckCount = 0,
                completionChecks = emptyList(),
            ),
            primaryAction = null,
            overflowActions = emptyList(),
            blockers = emptyList(),
            artifactBinding = SpecWorkflowStageArtifactBinding(
                stageId = StageId.IMPLEMENT,
                title = "Tasks",
                fileName = StageId.TASKS.artifactFileName,
                documentPhase = SpecPhase.IMPLEMENT,
                mode = SpecWorkflowWorkbenchDocumentMode.READ_ONLY,
                fallbackEditable = false,
            ),
            visibleSections = emptySet(),
        )
    }

    private fun overviewState(
        workflowId: String,
        title: String,
        refreshedAtMillis: Long = 200L,
    ): SpecWorkflowOverviewState {
        return SpecWorkflowOverviewState(
            workflowId = workflowId,
            title = title,
            status = WorkflowStatus.IN_PROGRESS,
            template = WorkflowTemplate.FULL_SPEC,
            switchableTemplates = emptyList(),
            latestTemplateSwitch = null,
            templateCloneTargets = emptyList(),
            templateLockedSummary = "locked",
            currentStage = StageId.IMPLEMENT,
            activeStages = listOf(StageId.IMPLEMENT, StageId.VERIFY),
            nextStage = StageId.VERIFY,
            gateStatus = GateStatus.PASS,
            gateSummary = "pass",
            stageStepper = SpecWorkflowStageStepperState(
                stages = listOf(
                    SpecWorkflowStageStepState(
                        stageId = StageId.IMPLEMENT,
                        active = true,
                        current = true,
                        progress = StageProgress.IN_PROGRESS,
                    ),
                    SpecWorkflowStageStepState(
                        stageId = StageId.VERIFY,
                        active = true,
                        current = false,
                        progress = StageProgress.NOT_STARTED,
                    ),
                ),
                canAdvance = true,
                jumpTargets = emptyList(),
                rollbackTargets = emptyList(),
            ),
            refreshedAtMillis = refreshedAtMillis,
        )
    }

    private fun verifyDeltaState(
        workflowId: String,
        refreshedAtMillis: Long,
    ): SpecWorkflowVerifyDeltaState {
        return SpecWorkflowVerifyDeltaState(
            workflowId = workflowId,
            verifyEnabled = true,
            verificationDocumentAvailable = true,
            verificationHistory = emptyList(),
            baselineChoices = emptyList(),
            deltaSummary = null,
            preferredBaselineChoiceId = null,
            canPinBaseline = false,
            refreshedAtMillis = refreshedAtMillis,
        )
    }

    private class RecordingEnvironment {
        val overviewBuilds = mutableListOf<String>()
        val verifyBuilds = mutableListOf<String>()
    }
}
