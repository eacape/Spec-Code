package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.WorkflowMeta
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowWorkbenchStageSelectionCoordinatorTest {

    @Test
    fun `requestJumpSelection should ignore missing workflow meta`() {
        val recorder = RecordingEnvironment(workflowMeta = null)
        val coordinator = coordinator(recorder)

        coordinator.requestJumpSelection()

        assertTrue(recorder.infoCalls.isEmpty())
        assertTrue(recorder.selectionRequests.isEmpty())
        assertTrue(recorder.jumpCalls.isEmpty())
    }

    @Test
    fun `requestJumpSelection should show info when no jump targets are available`() {
        val recorder = RecordingEnvironment(
            workflowMeta = workflowMeta("wf-jump"),
            jumpTargets = emptyList(),
        )
        val coordinator = coordinator(recorder)

        coordinator.requestJumpSelection()

        assertEquals(
            listOf(
                InfoCall(
                    SpecCodingBundle.message("spec.action.jump.none.title"),
                    SpecCodingBundle.message("spec.action.jump.none.message"),
                ),
            ),
            recorder.infoCalls,
        )
        assertTrue(recorder.selectionRequests.isEmpty())
        assertTrue(recorder.jumpCalls.isEmpty())
    }

    @Test
    fun `requestJumpSelection should open chooser and delegate chosen target`() {
        val recorder = RecordingEnvironment(
            workflowMeta = workflowMeta("wf-jump"),
            jumpTargets = listOf(StageId.VERIFY),
        )
        val coordinator = coordinator(recorder)

        coordinator.requestJumpSelection()

        assertEquals(1, recorder.selectionRequests.size)
        val request = recorder.selectionRequests.single()
        assertEquals("wf-jump", request.workflowMeta.workflowId)
        assertEquals(listOf(StageId.VERIFY), request.stages)
        assertEquals(SpecCodingBundle.message("spec.action.jump.stage.popup.title"), request.title)
        assertEquals(listOf(JumpCall("wf-jump", StageId.VERIFY)), recorder.jumpCalls)
    }

    @Test
    fun `requestRollbackSelection should show info when no rollback targets are available`() {
        val recorder = RecordingEnvironment(
            workflowMeta = workflowMeta("wf-rollback"),
            rollbackTargets = emptyList(),
        )
        val coordinator = coordinator(recorder)

        coordinator.requestRollbackSelection()

        assertEquals(
            listOf(
                InfoCall(
                    SpecCodingBundle.message("spec.action.rollback.none.title"),
                    SpecCodingBundle.message("spec.action.rollback.none.message"),
                ),
            ),
            recorder.infoCalls,
        )
        assertTrue(recorder.selectionRequests.isEmpty())
        assertTrue(recorder.rollbackCalls.isEmpty())
    }

    @Test
    fun `requestRollbackSelection should open chooser and delegate chosen target`() {
        val recorder = RecordingEnvironment(
            workflowMeta = workflowMeta("wf-rollback"),
            rollbackTargets = listOf(StageId.DESIGN),
        )
        val coordinator = coordinator(recorder)

        coordinator.requestRollbackSelection()

        assertEquals(1, recorder.selectionRequests.size)
        val request = recorder.selectionRequests.single()
        assertEquals("wf-rollback", request.workflowMeta.workflowId)
        assertEquals(listOf(StageId.DESIGN), request.stages)
        assertEquals(SpecCodingBundle.message("spec.action.rollback.stage.popup.title"), request.title)
        assertEquals(listOf(RollbackCall("wf-rollback", StageId.DESIGN)), recorder.rollbackCalls)
    }

    private fun coordinator(recorder: RecordingEnvironment): SpecWorkflowWorkbenchStageSelectionCoordinator {
        return SpecWorkflowWorkbenchStageSelectionCoordinator(
            workflowMetaProvider = { recorder.workflowMeta },
            jumpTargets = { recorder.jumpTargets },
            rollbackTargets = { recorder.rollbackTargets },
            showInfo = { title, message ->
                recorder.infoCalls += InfoCall(title, message)
            },
            chooseStage = { request ->
                recorder.selectionRequests += request
                request.stages.firstOrNull()?.let(request.onChosen)
            },
            onJumpSelected = { workflowId, targetStage ->
                recorder.jumpCalls += JumpCall(workflowId, targetStage)
            },
            onRollbackSelected = { workflowId, targetStage ->
                recorder.rollbackCalls += RollbackCall(workflowId, targetStage)
            },
        )
    }

    private fun workflowMeta(workflowId: String): WorkflowMeta {
        return WorkflowMeta(
            workflowId = workflowId,
            title = "Workflow $workflowId",
            template = WorkflowTemplate.FULL_SPEC,
            stageStates = mapOf(
                StageId.REQUIREMENTS to StageState(active = true, status = StageProgress.DONE),
                StageId.DESIGN to StageState(active = true, status = StageProgress.IN_PROGRESS),
                StageId.VERIFY to StageState(active = true, status = StageProgress.NOT_STARTED),
            ),
            currentStage = StageId.DESIGN,
            currentPhase = SpecPhase.DESIGN,
            verifyEnabled = true,
            configPinHash = null,
            baselineWorkflowId = null,
            artifactDraftStates = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            createdAt = 1L,
            updatedAt = 2L,
            description = null,
            changeIntent = SpecChangeIntent.FULL,
        )
    }

    private data class InfoCall(
        val title: String,
        val message: String,
    )

    private data class JumpCall(
        val workflowId: String,
        val targetStage: StageId,
    )

    private data class RollbackCall(
        val workflowId: String,
        val targetStage: StageId,
    )

    private class RecordingEnvironment(
        val workflowMeta: WorkflowMeta?,
        val jumpTargets: List<StageId> = emptyList(),
        val rollbackTargets: List<StageId> = emptyList(),
    ) {
        val infoCalls = mutableListOf<InfoCall>()
        val selectionRequests = mutableListOf<SpecWorkflowWorkbenchStageSelectionRequest>()
        val jumpCalls = mutableListOf<JumpCall>()
        val rollbackCalls = mutableListOf<RollbackCall>()
    }
}
