package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.StageTransitionGatePreview
import com.eacape.speccodingplugin.spec.StageTransitionResult
import com.eacape.speccodingplugin.spec.StageTransitionType
import com.eacape.speccodingplugin.spec.Violation
import com.eacape.speccodingplugin.spec.WorkflowMeta
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.actions.SpecWorkflowActionSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowStageTransitionCoordinatorTest {

    @Test
    fun `advance should normalize workflow id and surface gate block without executing`() {
        val recorder = RecordingEnvironment().apply {
            previewResult = Result.success(
                preview(
                    transitionType = StageTransitionType.ADVANCE,
                    status = GateStatus.ERROR,
                ),
            )
        }
        val coordinator = coordinator(recorder)

        coordinator.advance(" wf-advance ")

        assertEquals(
            listOf(
                PreviewCall(
                    workflowId = "wf-advance",
                    transitionType = StageTransitionType.ADVANCE,
                    targetStage = null,
                ),
            ),
            recorder.previewCalls,
        )
        assertEquals(
            listOf(
                GateBlockedCall(
                    workflowId = "wf-advance",
                    gateResult = recorder.previewResult.getOrThrow().gateResult,
                ),
            ),
            recorder.gateBlockedCalls,
        )
        assertTrue(recorder.advanceCalls.isEmpty())
        assertTrue(recorder.completions.isEmpty())
    }

    @Test
    fun `advance should stop when warning confirmation is rejected`() {
        val recorder = RecordingEnvironment().apply {
            previewResult = Result.success(
                preview(
                    transitionType = StageTransitionType.ADVANCE,
                    status = GateStatus.WARNING,
                ),
            )
            confirmWarnings = false
        }
        val coordinator = coordinator(recorder)

        coordinator.advance("wf-warning")

        assertEquals(
            listOf(
                WarningConfirmationCall(
                    workflowId = "wf-warning",
                    gateResult = recorder.previewResult.getOrThrow().gateResult,
                ),
            ),
            recorder.warningConfirmationCalls,
        )
        assertTrue(recorder.advanceCalls.isEmpty())
        assertTrue(recorder.completions.isEmpty())
    }

    @Test
    fun `jump should execute after confirmed warning and report target stage`() {
        val recorder = RecordingEnvironment().apply {
            previewResult = Result.success(
                preview(
                    transitionType = StageTransitionType.JUMP,
                    status = GateStatus.WARNING,
                    targetStage = StageId.TASKS,
                ),
            )
            jumpResult = Result.success(
                transitionResult(
                    transitionType = StageTransitionType.JUMP,
                    targetStage = StageId.TASKS,
                ),
            )
        }
        val coordinator = coordinator(recorder)

        coordinator.jump("wf-jump", StageId.TASKS)

        assertEquals(
            listOf(
                PreviewCall(
                    workflowId = "wf-jump",
                    transitionType = StageTransitionType.JUMP,
                    targetStage = StageId.TASKS,
                ),
            ),
            recorder.previewCalls,
        )
        assertEquals(
            listOf(
                WarningConfirmationCall(
                    workflowId = "wf-jump",
                    gateResult = recorder.previewResult.getOrThrow().gateResult,
                ),
            ),
            recorder.warningConfirmationCalls,
        )
        assertEquals(listOf(JumpCall("wf-jump", StageId.TASKS)), recorder.jumpCalls)
        assertEquals(
            listOf(
                CompletionCall(
                    workflowId = "wf-jump",
                    successMessage = SpecCodingBundle.message(
                        "spec.action.jump.success",
                        SpecWorkflowActionSupport.stageLabel(StageId.TASKS),
                    ),
                ),
            ),
            recorder.completions,
        )
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.action.jump.preview"),
                SpecCodingBundle.message("spec.action.jump.executing"),
            ),
            recorder.backgroundTitles,
        )
    }

    @Test
    fun `rollback should execute immediately and report resulting current stage`() {
        val recorder = RecordingEnvironment().apply {
            rollbackResult = Result.success(workflowMeta(currentStage = StageId.DESIGN))
        }
        val coordinator = coordinator(recorder)

        coordinator.rollback(" wf-rollback ", StageId.DESIGN)

        assertTrue(recorder.previewCalls.isEmpty())
        assertEquals(listOf(RollbackCall("wf-rollback", StageId.DESIGN)), recorder.rollbackCalls)
        assertEquals(
            listOf(
                CompletionCall(
                    workflowId = "wf-rollback",
                    successMessage = SpecCodingBundle.message(
                        "spec.action.rollback.success",
                        SpecWorkflowActionSupport.stageLabel(StageId.DESIGN),
                    ),
                ),
            ),
            recorder.completions,
        )
        assertEquals(
            listOf(SpecCodingBundle.message("spec.action.rollback.executing")),
            recorder.backgroundTitles,
        )
    }

    private fun coordinator(recorder: RecordingEnvironment): SpecWorkflowStageTransitionCoordinator {
        return SpecWorkflowStageTransitionCoordinator(
            backgroundRunner = object : SpecWorkflowStageTransitionBackgroundRunner {
                override fun <T> run(request: SpecWorkflowStageTransitionBackgroundRequest<T>) {
                    recorder.backgroundTitles += request.title
                    request.onSuccess(request.task())
                }
            },
            previewStageTransition = { workflowId, transitionType, targetStage ->
                recorder.previewCalls += PreviewCall(workflowId, transitionType, targetStage)
                recorder.previewResult
            },
            advanceWorkflow = { workflowId ->
                recorder.advanceCalls += workflowId
                recorder.advanceResult
            },
            jumpToStage = { workflowId, targetStage ->
                recorder.jumpCalls += JumpCall(workflowId, targetStage)
                recorder.jumpResult
            },
            rollbackToStage = { workflowId, targetStage ->
                recorder.rollbackCalls += RollbackCall(workflowId, targetStage)
                recorder.rollbackResult
            },
            showGateBlocked = { workflowId, gateResult ->
                recorder.gateBlockedCalls += GateBlockedCall(workflowId, gateResult)
            },
            confirmWarnings = { workflowId, gateResult ->
                recorder.warningConfirmationCalls += WarningConfirmationCall(workflowId, gateResult)
                recorder.confirmWarnings
            },
            onTransitionCompleted = { workflowId, successMessage ->
                recorder.completions += CompletionCall(workflowId, successMessage)
            },
        )
    }

    private fun preview(
        transitionType: StageTransitionType,
        status: GateStatus,
        targetStage: StageId = StageId.DESIGN,
    ): StageTransitionGatePreview {
        return StageTransitionGatePreview(
            workflowId = "wf-preview",
            transitionType = transitionType,
            fromStage = StageId.REQUIREMENTS,
            targetStage = targetStage,
            evaluatedStages = listOf(StageId.REQUIREMENTS, targetStage),
            gateResult = GateResult(
                status = status,
                violations = if (status == GateStatus.PASS) {
                    emptyList()
                } else {
                    listOf(
                        Violation(
                            ruleId = "gate.rule",
                            severity = status,
                            fileName = "requirements.md",
                            line = 1,
                            message = "gate status $status",
                        ),
                    )
                },
            ),
        )
    }

    private fun transitionResult(
        transitionType: StageTransitionType,
        targetStage: StageId,
    ): StageTransitionResult {
        return StageTransitionResult(
            workflow = workflow(currentStage = targetStage),
            transitionType = transitionType,
            fromStage = StageId.REQUIREMENTS,
            targetStage = targetStage,
            gateResult = GateResult(status = GateStatus.PASS, violations = emptyList()),
            warningConfirmed = transitionType != StageTransitionType.ADVANCE,
        )
    }

    private fun workflow(currentStage: StageId): SpecWorkflow {
        return SpecWorkflow(
            id = "wf-transition",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Workflow transition",
            template = WorkflowTemplate.FULL_SPEC,
            currentStage = currentStage,
        )
    }

    private fun workflowMeta(currentStage: StageId): WorkflowMeta {
        return WorkflowMeta(
            workflowId = "wf-transition",
            title = "Workflow transition",
            template = WorkflowTemplate.FULL_SPEC,
            stageStates = StageId.entries.associateWith { stageId ->
                if (stageId.ordinal <= currentStage.ordinal) {
                    StageState(active = true, status = StageProgress.DONE)
                } else {
                    StageState(active = true, status = StageProgress.NOT_STARTED)
                }
            },
            currentStage = currentStage,
            currentPhase = SpecPhase.IMPLEMENT,
            verifyEnabled = true,
            configPinHash = null,
            baselineWorkflowId = null,
            artifactDraftStates = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            createdAt = 1L,
            updatedAt = 2L,
            description = "workflow transition",
        )
    }

    private data class PreviewCall(
        val workflowId: String,
        val transitionType: StageTransitionType,
        val targetStage: StageId?,
    )

    private data class JumpCall(
        val workflowId: String,
        val targetStage: StageId,
    )

    private data class RollbackCall(
        val workflowId: String,
        val targetStage: StageId,
    )

    private data class GateBlockedCall(
        val workflowId: String,
        val gateResult: GateResult,
    )

    private data class WarningConfirmationCall(
        val workflowId: String,
        val gateResult: GateResult,
    )

    private data class CompletionCall(
        val workflowId: String,
        val successMessage: String,
    )

    private class RecordingEnvironment {
        val backgroundTitles = mutableListOf<String>()
        val previewCalls = mutableListOf<PreviewCall>()
        val advanceCalls = mutableListOf<String>()
        val jumpCalls = mutableListOf<JumpCall>()
        val rollbackCalls = mutableListOf<RollbackCall>()
        val gateBlockedCalls = mutableListOf<GateBlockedCall>()
        val warningConfirmationCalls = mutableListOf<WarningConfirmationCall>()
        val completions = mutableListOf<CompletionCall>()
        var previewResult: Result<StageTransitionGatePreview> = Result.failure(IllegalStateException("preview missing"))
        var advanceResult: Result<StageTransitionResult> = Result.failure(IllegalStateException("advance missing"))
        var jumpResult: Result<StageTransitionResult> = Result.failure(IllegalStateException("jump missing"))
        var rollbackResult: Result<WorkflowMeta> = Result.failure(IllegalStateException("rollback missing"))
        var confirmWarnings: Boolean = true
    }
}
