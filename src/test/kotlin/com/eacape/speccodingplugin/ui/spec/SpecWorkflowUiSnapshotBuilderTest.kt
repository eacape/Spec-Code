package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.StageTransitionGatePreview
import com.eacape.speccodingplugin.spec.StageTransitionType
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpecWorkflowUiSnapshotBuilderTest {

    @Test
    fun `build should preview advance gate and share refreshed timestamp across state builders`() {
        val preview = gatePreview("wf-1")
        val recorder = RecordingEnvironment(
            previewAdvanceGateResult = Result.success(preview),
            currentTimeMillis = 1234L,
        )
        val builder = builder(recorder)

        val snapshot = builder.build(workflow(id = "wf-1"))

        assertEquals(listOf("wf-1"), recorder.previewAdvanceGateCalls)
        assertEquals(preview, recorder.overviewGatePreview)
        assertEquals(1234L, recorder.overviewRefreshedAtMillis)
        assertEquals(1234L, recorder.verifyDeltaRefreshedAtMillis)
        assertEquals(preview.gateResult, snapshot.gateResult)
        assertEquals(1234L, snapshot.refreshedAtMillis)
        assertEquals("overview:wf-1", snapshot.overviewState.title)
        assertEquals("verify:wf-1", snapshot.verifyDeltaState.workflowId)
    }

    @Test
    fun `build should skip advance gate preview for completed and archived workflows`() {
        val recorder = RecordingEnvironment(previewAdvanceGateResult = Result.success(gatePreview("ignored")))
        val builder = builder(recorder)

        val completedSnapshot = builder.build(workflow(id = "wf-completed", status = WorkflowStatus.COMPLETED))
        val archivedSnapshot = builder.build(workflow(id = "wf-archived", currentStage = StageId.ARCHIVE))

        assertEquals(emptyList<String>(), recorder.previewAdvanceGateCalls)
        assertNull(recorder.overviewGatePreviews[0])
        assertNull(recorder.overviewGatePreviews[1])
        assertNull(completedSnapshot.gateResult)
        assertNull(archivedSnapshot.gateResult)
    }

    @Test
    fun `build should degrade when advance gate preview fails`() {
        val recorder = RecordingEnvironment(
            previewAdvanceGateResult = Result.failure(IllegalStateException("preview unavailable")),
        )
        val builder = builder(recorder)

        val snapshot = builder.build(workflow(id = "wf-2"))

        assertEquals(listOf("wf-2"), recorder.previewAdvanceGateCalls)
        assertNull(recorder.overviewGatePreview)
        assertNull(snapshot.gateResult)
        assertEquals(
            listOf("Unable to preview advance gate for workflow wf-2"),
            recorder.loggedFailures,
        )
    }

    private fun builder(recorder: RecordingEnvironment): SpecWorkflowUiSnapshotBuilder {
        return SpecWorkflowUiSnapshotBuilder(
            previewAdvanceGate = { workflowId ->
                recorder.previewAdvanceGateCalls += workflowId
                recorder.previewAdvanceGateResult
            },
            buildOverviewState = { workflow, gatePreview, refreshedAtMillis ->
                recorder.overviewGatePreviews += gatePreview
                recorder.overviewGatePreview = gatePreview
                recorder.overviewRefreshedAtMillis = refreshedAtMillis
                SpecWorkflowOverviewState(
                    workflowId = workflow.id,
                    title = "overview:${workflow.id}",
                    status = workflow.status,
                    template = workflow.template,
                    switchableTemplates = emptyList(),
                    latestTemplateSwitch = null,
                    templateCloneTargets = emptyList(),
                    templateLockedSummary = "locked",
                    currentStage = workflow.currentStage,
                    activeStages = listOf(workflow.currentStage),
                    nextStage = gatePreview?.targetStage,
                    gateStatus = gatePreview?.gateResult?.status,
                    gateSummary = gatePreview?.gateResult?.aggregation?.summary,
                    stageStepper = SpecWorkflowStageStepperState(
                        stages = emptyList(),
                        canAdvance = gatePreview != null,
                        jumpTargets = emptyList(),
                        rollbackTargets = emptyList(),
                    ),
                    refreshedAtMillis = refreshedAtMillis,
                )
            },
            buildVerifyDeltaState = { workflow, refreshedAtMillis ->
                recorder.verifyDeltaRefreshedAtMillis = refreshedAtMillis
                SpecWorkflowVerifyDeltaState(
                    workflowId = "verify:${workflow.id}",
                    verifyEnabled = false,
                    verificationDocumentAvailable = false,
                    verificationHistory = emptyList(),
                    baselineChoices = emptyList(),
                    deltaSummary = null,
                    preferredBaselineChoiceId = null,
                    canPinBaseline = false,
                    refreshedAtMillis = refreshedAtMillis,
                )
            },
            currentTimeMillis = { recorder.currentTimeMillis },
            logGatePreviewFailure = { message, _ ->
                recorder.loggedFailures += message
            },
        )
    }

    private fun workflow(
        id: String,
        status: WorkflowStatus = WorkflowStatus.IN_PROGRESS,
        currentStage: StageId = StageId.TASKS,
    ): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = status,
            title = id,
            template = WorkflowTemplate.FULL_SPEC,
            currentStage = currentStage,
            stageStates = mapOf(
                currentStage to StageState(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                ),
            ),
        )
    }

    private fun gatePreview(workflowId: String): StageTransitionGatePreview {
        return StageTransitionGatePreview(
            workflowId = workflowId,
            transitionType = StageTransitionType.ADVANCE,
            fromStage = StageId.TASKS,
            targetStage = StageId.IMPLEMENT,
            evaluatedStages = listOf(StageId.TASKS),
            gateResult = GateResult(
                status = GateStatus.PASS,
                violations = emptyList(),
            ),
        )
    }

    private data class RecordingEnvironment(
        val previewAdvanceGateResult: Result<StageTransitionGatePreview>,
        val currentTimeMillis: Long = 1000L,
        val previewAdvanceGateCalls: MutableList<String> = mutableListOf(),
        val overviewGatePreviews: MutableList<StageTransitionGatePreview?> = mutableListOf(),
        var overviewGatePreview: StageTransitionGatePreview? = null,
        var overviewRefreshedAtMillis: Long? = null,
        var verifyDeltaRefreshedAtMillis: Long? = null,
        val loggedFailures: MutableList<String> = mutableListOf(),
    )
}
