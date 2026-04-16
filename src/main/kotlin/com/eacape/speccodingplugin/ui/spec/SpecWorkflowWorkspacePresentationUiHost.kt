package com.eacape.speccodingplugin.ui.spec

import com.intellij.ui.components.JBLabel
import java.awt.Color
import javax.swing.JPanel

internal data class SpecWorkflowWorkspaceMetricUi(
    val root: JPanel,
    val titleLabel: JBLabel,
    val valueLabel: JBLabel,
)

internal data class SpecWorkflowWorkspaceMetricColors(
    val foreground: Color,
)

internal data class SpecWorkflowWorkspaceSectionUi(
    val setSummary: (String?) -> Unit,
    val setExpanded: (Boolean) -> Unit,
)

internal class SpecWorkflowWorkspacePresentationUiHost(
    private val summaryTitleLabel: JBLabel,
    private val summaryMetaLabel: JBLabel,
    private val summaryFocusLabel: JBLabel,
    private val summaryHintLabel: JBLabel,
    private val stageMetric: SpecWorkflowWorkspaceMetricUi,
    private val gateMetric: SpecWorkflowWorkspaceMetricUi,
    private val tasksMetric: SpecWorkflowWorkspaceMetricUi,
    private val verifyMetric: SpecWorkflowWorkspaceMetricUi,
    private val sections: Map<SpecWorkflowWorkspaceSectionId, SpecWorkflowWorkspaceSectionUi>,
    private val sectionItems: Map<SpecWorkflowWorkspaceSectionId, JPanel>,
    private val sectionContainer: JPanel,
    private val resolveMetricColors: (SpecWorkflowWorkspaceChipTone) -> SpecWorkflowWorkspaceMetricColors,
) {

    fun applySummary(presentation: SpecWorkflowWorkspaceSummaryPresentation) {
        summaryTitleLabel.text = presentation.title
        summaryMetaLabel.text = presentation.meta
        summaryFocusLabel.text = presentation.focusTitle
        summaryHintLabel.text = presentation.focusSummary
        applyMetric(stageMetric, presentation.stageMetric)
        applyMetric(gateMetric, presentation.gateMetric)
        applyMetric(tasksMetric, presentation.tasksMetric)
        applyMetric(verifyMetric, presentation.verifyMetric)
    }

    fun clearSummary() {
        summaryTitleLabel.text = ""
        summaryMetaLabel.text = ""
        summaryFocusLabel.text = ""
        summaryHintLabel.text = ""
        clearMetric(stageMetric)
        clearMetric(gateMetric)
        clearMetric(tasksMetric)
        clearMetric(verifyMetric)
    }

    fun applyWorkspaceSectionPresentation(
        summaries: SpecWorkflowWorkspaceSectionSummaries,
        visibleSectionIds: Set<SpecWorkflowWorkspaceSectionId>,
        expandedStates: Map<SpecWorkflowWorkspaceSectionId, Boolean>,
    ) {
        sectionSummariesById(summaries).forEach { (sectionId, summary) ->
            sections[sectionId]?.setSummary?.invoke(summary)
        }
        expandedStates.forEach { (sectionId, expanded) ->
            sections[sectionId]?.setExpanded?.invoke(expanded)
        }
        sectionItems.forEach { (sectionId, item) ->
            item.isVisible = visibleSectionIds.contains(sectionId)
        }
        sectionContainer.revalidate()
        sectionContainer.repaint()
    }

    fun resetWorkspaceSections() {
        sections.values.forEach { section ->
            section.setSummary(null)
            section.setExpanded(true)
        }
    }

    fun showAllWorkspaceSections() {
        sectionItems.values.forEach { item ->
            item.isVisible = true
        }
    }

    internal fun summarySnapshotForTest(): Map<String, String> {
        return mapOf(
            "stageTitle" to stageMetric.titleLabel.text.orEmpty(),
            "stageValue" to stageMetric.valueLabel.text.orEmpty(),
            "gateTitle" to gateMetric.titleLabel.text.orEmpty(),
            "gateValue" to gateMetric.valueLabel.text.orEmpty(),
            "tasksTitle" to tasksMetric.titleLabel.text.orEmpty(),
            "tasksValue" to tasksMetric.valueLabel.text.orEmpty(),
            "verifyTitle" to verifyMetric.titleLabel.text.orEmpty(),
            "verifyValue" to verifyMetric.valueLabel.text.orEmpty(),
            "focusTitle" to summaryFocusLabel.text.orEmpty(),
            "focusHint" to summaryHintLabel.text.orEmpty(),
        )
    }

    private fun applyMetric(
        metric: SpecWorkflowWorkspaceMetricUi,
        presentation: SpecWorkflowWorkspaceMetricPresentation,
    ) {
        val colors = resolveMetricColors(presentation.tone)
        metric.titleLabel.text = "${presentation.title}:"
        metric.valueLabel.text = presentation.value
        metric.valueLabel.foreground = colors.foreground
        metric.root.isVisible = presentation.value.isNotBlank()
    }

    private fun clearMetric(metric: SpecWorkflowWorkspaceMetricUi) {
        metric.titleLabel.text = ""
        metric.valueLabel.text = ""
        metric.root.isVisible = false
    }

    private fun sectionSummariesById(
        summaries: SpecWorkflowWorkspaceSectionSummaries,
    ): Map<SpecWorkflowWorkspaceSectionId, String> {
        return linkedMapOf(
            SpecWorkflowWorkspaceSectionId.OVERVIEW to summaries.overview,
            SpecWorkflowWorkspaceSectionId.TASKS to summaries.tasks,
            SpecWorkflowWorkspaceSectionId.GATE to summaries.gate,
            SpecWorkflowWorkspaceSectionId.VERIFY to summaries.verify,
            SpecWorkflowWorkspaceSectionId.DOCUMENTS to summaries.documents,
        )
    }
}
