package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.TemplateCloneResult
import com.eacape.speccodingplugin.spec.TemplateSwitchArtifactStrategy
import com.eacape.speccodingplugin.spec.TemplateSwitchPreview
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.actions.SpecWorkflowActionSupport

internal data class SpecWorkflowTemplateCloneEditRequest(
    val initialTitle: String,
    val initialDescription: String,
    val dialogTitle: String,
)

internal data class SpecWorkflowTemplateCloneEditResult(
    val title: String,
    val description: String? = null,
)

internal class SpecWorkflowTemplateCloneCoordinator(
    private val workflowProvider: () -> SpecWorkflow?,
    private val previewTemplateSwitch: (workflowId: String, targetTemplate: WorkflowTemplate) -> Result<TemplateSwitchPreview>,
    private val cloneWorkflowWithTemplate: (
        workflowId: String,
        previewId: String,
        title: String,
        description: String?,
    ) -> Result<TemplateCloneResult>,
    private val runPreviewInBackground: (
        title: String,
        task: () -> TemplateSwitchPreview,
        onSuccess: (TemplateSwitchPreview) -> Unit,
    ) -> Unit,
    private val runCloneInBackground: (
        title: String,
        task: () -> TemplateCloneResult,
        onSuccess: (TemplateCloneResult) -> Unit,
    ) -> Unit,
    private val showBlockedPreview: (title: String, message: String) -> Unit,
    private val confirmPreview: (title: String, message: String) -> Boolean,
    private val editClone: (SpecWorkflowTemplateCloneEditRequest) -> SpecWorkflowTemplateCloneEditResult?,
    private val notifySuccess: (message: String) -> Unit,
    private val onCloneCreated: (workflowId: String) -> Unit,
) {

    fun requestClone(targetTemplate: WorkflowTemplate) {
        val workflow = workflowProvider() ?: return
        if (workflow.template == targetTemplate) {
            return
        }

        runPreviewInBackground(
            SpecCodingBundle.message("spec.action.template.clone.preview"),
            {
                previewTemplateSwitch(workflow.id, targetTemplate).getOrThrow()
            },
            previewSuccess@ { preview ->
                val summary = buildTemplateClonePreviewSummary(workflow, preview)
                val previewTitle = SpecCodingBundle.message("spec.action.template.clone.confirm.title")
                if (preview.hasBlockingImpact()) {
                    showBlockedPreview(previewTitle, summary)
                    return@previewSuccess
                }
                if (!confirmPreview(previewTitle, summary)) {
                    return@previewSuccess
                }

                val draft = editClone(
                    SpecWorkflowTemplateCloneEditRequest(
                        initialTitle = suggestedClonedWorkflowTitle(workflow, preview.toTemplate),
                        initialDescription = workflow.description,
                        dialogTitle = SpecCodingBundle.message("spec.action.template.clone.dialog.title"),
                    ),
                ) ?: return@previewSuccess

                val title = draft.title.trim().takeIf { it.isNotEmpty() } ?: return@previewSuccess
                runCloneInBackground(
                    SpecCodingBundle.message("spec.action.template.clone.executing"),
                    {
                        cloneWorkflowWithTemplate(
                            workflow.id,
                            preview.previewId,
                            title,
                            draft.description,
                        ).getOrThrow()
                    },
                    { result ->
                        notifySuccess(
                            SpecCodingBundle.message(
                                "spec.action.template.clone.success",
                                result.workflow.title.ifBlank { result.workflow.id },
                                SpecWorkflowOverviewPresenter.templateLabel(result.workflow.template),
                            ),
                        )
                        onCloneCreated(result.workflow.id)
                    },
                )
            },
        )
    }

    private fun buildTemplateClonePreviewSummary(
        workflow: SpecWorkflow,
        preview: TemplateSwitchPreview,
    ): String {
        val lines = mutableListOf<String>()
        lines += SpecCodingBundle.message("spec.action.template.clone.summary.workflow", workflow.id)
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.templates",
            SpecWorkflowOverviewPresenter.templateLabel(preview.fromTemplate),
            SpecWorkflowOverviewPresenter.templateLabel(preview.toTemplate),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.stage",
            SpecWorkflowActionSupport.stageLabel(preview.currentStage),
            SpecWorkflowActionSupport.stageLabel(preview.resultingStage),
        )
        if (preview.currentStageChanged) {
            lines += SpecCodingBundle.message("spec.action.template.clone.summary.stageChanged")
        }
        lines += ""
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.addedStages",
            formatStageList(preview.addedActiveStages),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.deactivatedStages",
            formatStageList(preview.deactivatedStages),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.gateAddedStages",
            formatStageList(preview.gateAddedStages),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.gateRemovedStages",
            formatStageList(preview.gateRemovedStages),
        )
        lines += ""
        lines += SpecCodingBundle.message("spec.action.template.clone.summary.artifacts")
        preview.artifactImpacts.forEach { impact ->
            lines += SpecCodingBundle.message(
                "spec.action.template.clone.summary.artifact",
                impact.fileName,
                SpecWorkflowActionSupport.stageLabel(impact.stageId),
                templateSwitchStrategyLabel(impact.strategy),
            )
        }
        if (preview.hasBlockingImpact()) {
            lines += ""
            lines += SpecCodingBundle.message("spec.action.template.clone.summary.blocked")
        }
        lines += ""
        lines += SpecCodingBundle.message("spec.action.template.clone.summary.note")
        return lines.joinToString("\n")
    }

    private fun formatStageList(stages: List<StageId>): String {
        if (stages.isEmpty()) {
            return SpecCodingBundle.message("spec.action.template.clone.summary.none")
        }
        return stages.joinToString(", ") { stage ->
            SpecWorkflowActionSupport.stageLabel(stage)
        }
    }

    private fun templateSwitchStrategyLabel(strategy: TemplateSwitchArtifactStrategy): String {
        return when (strategy) {
            TemplateSwitchArtifactStrategy.REUSE_EXISTING ->
                SpecCodingBundle.message("spec.action.template.clone.strategy.reuse")

            TemplateSwitchArtifactStrategy.GENERATE_SKELETON ->
                SpecCodingBundle.message("spec.action.template.clone.strategy.generate")

            TemplateSwitchArtifactStrategy.BLOCK_SWITCH ->
                SpecCodingBundle.message("spec.action.template.clone.strategy.block")
        }
    }

    private fun suggestedClonedWorkflowTitle(
        workflow: SpecWorkflow,
        targetTemplate: WorkflowTemplate,
    ): String {
        val baseTitle = workflow.title.ifBlank { workflow.id }
        return "$baseTitle (${SpecWorkflowOverviewPresenter.templateLabel(targetTemplate)})"
    }

    private fun TemplateSwitchPreview.hasBlockingImpact(): Boolean {
        return artifactImpacts.any { impact ->
            impact.strategy == TemplateSwitchArtifactStrategy.BLOCK_SWITCH
        }
    }
}
