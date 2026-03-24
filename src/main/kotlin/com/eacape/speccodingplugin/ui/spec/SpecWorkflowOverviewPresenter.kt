package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageActivationOptions
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageTransitionGatePreview
import com.eacape.speccodingplugin.spec.TemplateSwitchHistoryEntry
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.spec.WorkflowTemplates
import java.util.Locale

internal data class SpecWorkflowStageStepState(
    val stageId: StageId,
    val active: Boolean,
    val current: Boolean,
    val progress: StageProgress,
)

internal data class SpecWorkflowStageStepperState(
    val stages: List<SpecWorkflowStageStepState>,
    val canAdvance: Boolean,
    val jumpTargets: List<StageId>,
    val rollbackTargets: List<StageId>,
)

internal data class SpecWorkflowOverviewState(
    val workflowId: String,
    val title: String,
    val status: WorkflowStatus,
    val template: WorkflowTemplate,
    val switchableTemplates: List<WorkflowTemplate>,
    val latestTemplateSwitch: TemplateSwitchHistoryEntry?,
    val templateCloneTargets: List<WorkflowTemplate>,
    val templateLockedSummary: String,
    val currentStage: StageId,
    val activeStages: List<StageId>,
    val nextStage: StageId?,
    val gateStatus: GateStatus?,
    val gateSummary: String?,
    val stageStepper: SpecWorkflowStageStepperState,
    val refreshedAtMillis: Long,
)

internal object SpecWorkflowOverviewPresenter {
    fun buildState(
        workflow: SpecWorkflow,
        gatePreview: StageTransitionGatePreview?,
        latestTemplateSwitch: TemplateSwitchHistoryEntry? = null,
        refreshedAtMillis: Long,
    ): SpecWorkflowOverviewState {
        val stageOverrides = workflow.stageStates
            .mapValues { (_, state) -> state.active }
            .toMutableMap()
        if (workflow.verifyEnabled && !stageOverrides.containsKey(StageId.VERIFY)) {
            stageOverrides[StageId.VERIFY] = true
        }

        val stagePlan = WorkflowTemplates.definitionOf(workflow.template)
            .buildStagePlan(StageActivationOptions(stageOverrides = stageOverrides))
        val activeStages = stagePlan.activeStages
        val nextStage = if (workflow.status == WorkflowStatus.COMPLETED) {
            null
        } else {
            gatePreview?.targetStage ?: stagePlan.nextActiveStage(workflow.currentStage)
        }

        return SpecWorkflowOverviewState(
            workflowId = workflow.id,
            title = workflow.title,
            status = workflow.status,
            template = workflow.template,
            switchableTemplates = WorkflowTemplate.entries.filterNot { template -> template == workflow.template },
            latestTemplateSwitch = latestTemplateSwitch,
            templateCloneTargets = WorkflowTemplate.entries.filterNot { template -> template == workflow.template },
            templateLockedSummary = SpecCodingBundle.message("spec.toolwindow.overview.template.locked"),
            currentStage = workflow.currentStage,
            activeStages = activeStages,
            nextStage = nextStage,
            gateStatus = gatePreview?.gateResult?.status,
            gateSummary = gatePreview?.gateResult?.aggregation?.summary,
            stageStepper = SpecWorkflowStageStepperState(
                stages = stagePlan.orderedStages.map { stageId ->
                    val stageState = workflow.stageStates[stageId]
                    SpecWorkflowStageStepState(
                        stageId = stageId,
                        active = stagePlan.isActive(stageId),
                        current = workflow.currentStage == stageId,
                        progress = when {
                            workflow.currentStage == stageId -> StageProgress.IN_PROGRESS
                            else -> stageState?.status ?: StageProgress.NOT_STARTED
                        },
                    )
                },
                canAdvance = nextStage != null && workflow.currentStage != StageId.ARCHIVE,
                jumpTargets = emptyList(),
                rollbackTargets = emptyList(),
            ),
            refreshedAtMillis = refreshedAtMillis,
        )
    }

    fun stageLabel(stageId: StageId): String {
        val fallback = stageId.name
            .lowercase(Locale.ROOT)
            .split('_')
            .joinToString(" ") { token -> token.replaceFirstChar { char -> char.titlecase(Locale.ROOT) } }
        return SpecCodingBundle.messageOrDefault(
            "spec.stage.${stageId.name.lowercase(Locale.ROOT)}",
            fallback,
        )
    }

    fun templateLabel(template: WorkflowTemplate): String {
        val fallback = template.name
            .lowercase(Locale.ROOT)
            .split('_')
            .joinToString(" ") { token -> token.replaceFirstChar { char -> char.titlecase(Locale.ROOT) } }
        return SpecCodingBundle.messageOrDefault(
            "spec.template.${template.name.lowercase(Locale.ROOT)}",
            fallback,
        )
    }

    fun progressLabel(progress: StageProgress): String {
        return when (progress) {
            StageProgress.DONE -> SpecCodingBundle.message("spec.action.stage.state.done")
            StageProgress.IN_PROGRESS -> SpecCodingBundle.message("spec.action.stage.state.inProgress")
            StageProgress.NOT_STARTED -> SpecCodingBundle.message("spec.action.stage.state.pending")
        }
    }
}
