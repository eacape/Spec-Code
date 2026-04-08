package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.spec.WorkflowTemplates
import java.util.Locale

internal data class SpecWorkflowTemplateStageGuide(
    val stageSummary: String,
    val stageMeaningSummary: String,
)

internal object SpecWorkflowTemplateStageGuideCoordinator {
    fun build(
        template: WorkflowTemplate,
        verifyEnabled: Boolean? = null,
    ): SpecWorkflowTemplateStageGuide {
        val entries = WorkflowTemplates.definitionOf(template).stagePlan.mapNotNull { item ->
            when {
                item.id == StageId.VERIFY && item.optional && verifyEnabled == false -> null
                else -> StageGuideEntry(
                    label = stageLabel(
                        stageId = item.id,
                        optional = item.optional && !(item.id == StageId.VERIFY && verifyEnabled == true),
                    ),
                    summary = stageMeaning(item.id),
                )
            }
        }

        return SpecWorkflowTemplateStageGuide(
            stageSummary = entries.joinToString(" -> ") { it.label },
            stageMeaningSummary = entries.joinToString("\n") { "${it.label}: ${it.summary}" },
        )
    }

    private fun stageLabel(stageId: StageId, optional: Boolean): String {
        val label = SpecWorkflowOverviewPresenter.stageLabel(stageId)
        return if (optional) {
            SpecCodingBundle.message("spec.dialog.template.optionalValue", label)
        } else {
            label
        }
    }

    private fun stageMeaning(stageId: StageId): String {
        return SpecCodingBundle.message(
            "spec.dialog.template.stageMeaning.${stageId.name.lowercase(Locale.ROOT)}",
        )
    }

    private data class StageGuideEntry(
        val label: String,
        val summary: String,
    )
}
