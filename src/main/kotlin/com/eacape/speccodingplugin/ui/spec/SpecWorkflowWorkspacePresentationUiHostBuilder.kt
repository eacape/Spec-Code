package com.eacape.speccodingplugin.ui.spec

import com.intellij.ui.JBColor
import java.awt.Color
import javax.swing.JPanel

internal class SpecWorkflowWorkspacePresentationUiHostBuilder(
    private val summaryUi: SpecWorkflowWorkspaceSummaryUi,
    private val sections: SpecWorkflowWorkspaceCardSections,
    private val sectionItems: Map<SpecWorkflowWorkspaceSectionId, JPanel>,
    private val sectionContainer: JPanel,
) {

    fun build(): SpecWorkflowWorkspacePresentationUiHost {
        return SpecWorkflowWorkspacePresentationUiHost(
            summaryTitleLabel = summaryUi.titleLabel,
            summaryMetaLabel = summaryUi.metaLabel,
            summaryFocusLabel = summaryUi.focusLabel,
            summaryHintLabel = summaryUi.hintLabel,
            stageMetric = summaryUi.stageMetric,
            gateMetric = summaryUi.gateMetric,
            tasksMetric = summaryUi.tasksMetric,
            verifyMetric = summaryUi.verifyMetric,
            sections = linkedMapOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW to workspaceSectionUi(sections.overview),
                SpecWorkflowWorkspaceSectionId.TASKS to workspaceSectionUi(sections.tasks),
                SpecWorkflowWorkspaceSectionId.GATE to workspaceSectionUi(sections.gate),
                SpecWorkflowWorkspaceSectionId.VERIFY to workspaceSectionUi(sections.verify),
                SpecWorkflowWorkspaceSectionId.DOCUMENTS to workspaceSectionUi(sections.documents),
            ),
            sectionItems = sectionItems,
            sectionContainer = sectionContainer,
            resolveMetricColors = ::workspaceMetricColors,
        )
    }

    private fun workspaceMetricColors(
        tone: SpecWorkflowWorkspaceChipTone,
    ): SpecWorkflowWorkspaceMetricColors {
        return when (tone) {
            SpecWorkflowWorkspaceChipTone.INFO -> SpecWorkflowWorkspaceMetricColors(
                foreground = WORKSPACE_INFO_CHIP_FG,
            )

            SpecWorkflowWorkspaceChipTone.SUCCESS -> SpecWorkflowWorkspaceMetricColors(
                foreground = WORKSPACE_SUCCESS_CHIP_FG,
            )

            SpecWorkflowWorkspaceChipTone.WARNING -> SpecWorkflowWorkspaceMetricColors(
                foreground = WORKSPACE_WARNING_CHIP_FG,
            )

            SpecWorkflowWorkspaceChipTone.ERROR -> SpecWorkflowWorkspaceMetricColors(
                foreground = WORKSPACE_ERROR_CHIP_FG,
            )

            SpecWorkflowWorkspaceChipTone.MUTED -> SpecWorkflowWorkspaceMetricColors(
                foreground = WORKSPACE_MUTED_CHIP_FG,
            )
        }
    }

    private fun workspaceSectionUi(section: SpecCollapsibleWorkspaceSection): SpecWorkflowWorkspaceSectionUi {
        return SpecWorkflowWorkspaceSectionUi(
            setSummary = section::setSummary,
            setExpanded = { expanded ->
                section.setExpanded(expanded, notify = false)
            },
        )
    }

    private companion object {
        private val WORKSPACE_INFO_CHIP_FG = JBColor(Color(48, 74, 112), Color(210, 220, 235))
        private val WORKSPACE_SUCCESS_CHIP_FG = JBColor(Color(42, 118, 71), Color(177, 225, 194))
        private val WORKSPACE_WARNING_CHIP_FG = JBColor(Color(140, 96, 28), Color(239, 210, 146))
        private val WORKSPACE_ERROR_CHIP_FG = JBColor(Color(152, 52, 52), Color(244, 182, 182))
        private val WORKSPACE_MUTED_CHIP_FG = JBColor(Color(98, 109, 126), Color(173, 181, 194))
    }
}
