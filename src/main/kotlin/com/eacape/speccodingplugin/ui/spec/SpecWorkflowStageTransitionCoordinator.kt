package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageTransitionGatePreview
import com.eacape.speccodingplugin.spec.StageTransitionResult
import com.eacape.speccodingplugin.spec.StageTransitionType
import com.eacape.speccodingplugin.spec.WorkflowMeta
import com.eacape.speccodingplugin.ui.actions.SpecWorkflowActionSupport

internal data class SpecWorkflowStageTransitionBackgroundRequest<T>(
    val title: String,
    val task: () -> T,
    val onSuccess: (T) -> Unit,
)

internal interface SpecWorkflowStageTransitionBackgroundRunner {
    fun <T> run(request: SpecWorkflowStageTransitionBackgroundRequest<T>)
}

internal class SpecWorkflowStageTransitionCoordinator(
    private val backgroundRunner: SpecWorkflowStageTransitionBackgroundRunner,
    private val previewStageTransition: (
        workflowId: String,
        transitionType: StageTransitionType,
        targetStage: StageId?,
    ) -> Result<StageTransitionGatePreview>,
    private val advanceWorkflow: (workflowId: String) -> Result<StageTransitionResult>,
    private val jumpToStage: (workflowId: String, targetStage: StageId) -> Result<StageTransitionResult>,
    private val rollbackToStage: (workflowId: String, targetStage: StageId) -> Result<WorkflowMeta>,
    private val showGateBlocked: (workflowId: String, gateResult: GateResult) -> Unit,
    private val confirmWarnings: (workflowId: String, gateResult: GateResult) -> Boolean,
    private val onTransitionCompleted: (workflowId: String, successMessage: String) -> Unit,
) {

    fun advance(workflowId: String) {
        val normalizedWorkflowId = workflowId.trim().ifBlank { return }
        previewAndExecute(
            workflowId = normalizedWorkflowId,
            transitionType = StageTransitionType.ADVANCE,
            targetStage = null,
            previewTitle = SpecCodingBundle.message("spec.action.advance.preview"),
        ) {
            executeAdvance(normalizedWorkflowId)
        }
    }

    fun jump(workflowId: String, targetStage: StageId) {
        val normalizedWorkflowId = workflowId.trim().ifBlank { return }
        previewAndExecute(
            workflowId = normalizedWorkflowId,
            transitionType = StageTransitionType.JUMP,
            targetStage = targetStage,
            previewTitle = SpecCodingBundle.message("spec.action.jump.preview"),
        ) {
            executeJump(normalizedWorkflowId, targetStage)
        }
    }

    fun rollback(workflowId: String, targetStage: StageId) {
        val normalizedWorkflowId = workflowId.trim().ifBlank { return }
        backgroundRunner.run(
            SpecWorkflowStageTransitionBackgroundRequest(
                title = SpecCodingBundle.message("spec.action.rollback.executing"),
                task = {
                    rollbackToStage(normalizedWorkflowId, targetStage).getOrThrow()
                },
                onSuccess = { meta ->
                    onTransitionCompleted(
                        normalizedWorkflowId,
                        SpecCodingBundle.message(
                            "spec.action.rollback.success",
                            SpecWorkflowActionSupport.stageLabel(meta.currentStage),
                        ),
                    )
                },
            ),
        )
    }

    private fun previewAndExecute(
        workflowId: String,
        transitionType: StageTransitionType,
        targetStage: StageId?,
        previewTitle: String,
        onProceed: () -> Unit,
    ) {
        backgroundRunner.run(
            SpecWorkflowStageTransitionBackgroundRequest(
                title = previewTitle,
                task = {
                    previewStageTransition(
                        workflowId,
                        transitionType,
                        targetStage,
                    ).getOrThrow()
                },
                onSuccess = { preview ->
                    when (preview.gateResult.status) {
                        GateStatus.ERROR -> showGateBlocked(workflowId, preview.gateResult)
                        GateStatus.WARNING -> {
                            if (confirmWarnings(workflowId, preview.gateResult)) {
                                onProceed()
                            }
                        }

                        GateStatus.PASS -> onProceed()
                    }
                },
            ),
        )
    }

    private fun executeAdvance(workflowId: String) {
        backgroundRunner.run(
            SpecWorkflowStageTransitionBackgroundRequest(
                title = SpecCodingBundle.message("spec.action.advance.executing"),
                task = {
                    advanceWorkflow(workflowId).getOrThrow()
                },
                onSuccess = { result ->
                    onTransitionCompleted(
                        workflowId,
                        SpecCodingBundle.message(
                            "spec.action.advance.success",
                            SpecWorkflowActionSupport.stageLabel(result.targetStage),
                        ),
                    )
                },
            ),
        )
    }

    private fun executeJump(workflowId: String, targetStage: StageId) {
        backgroundRunner.run(
            SpecWorkflowStageTransitionBackgroundRequest(
                title = SpecCodingBundle.message("spec.action.jump.executing"),
                task = {
                    jumpToStage(workflowId, targetStage).getOrThrow()
                },
                onSuccess = { result ->
                    onTransitionCompleted(
                        workflowId,
                        SpecCodingBundle.message(
                            "spec.action.jump.success",
                            SpecWorkflowActionSupport.stageLabel(result.targetStage),
                        ),
                    )
                },
            ),
        )
    }
}
