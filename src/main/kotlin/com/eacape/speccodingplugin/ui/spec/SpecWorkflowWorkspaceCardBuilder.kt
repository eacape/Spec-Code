package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants

internal data class SpecWorkflowWorkspaceSummaryUi(
    val titleLabel: JBLabel,
    val metaLabel: JBLabel,
    val focusLabel: JBLabel,
    val hintLabel: JBLabel,
    val stageMetric: SpecWorkflowWorkspaceMetricUi,
    val gateMetric: SpecWorkflowWorkspaceMetricUi,
    val tasksMetric: SpecWorkflowWorkspaceMetricUi,
    val verifyMetric: SpecWorkflowWorkspaceMetricUi,
)

internal data class SpecWorkflowWorkspaceCardSections(
    val overview: SpecCollapsibleWorkspaceSection,
    val tasks: SpecCollapsibleWorkspaceSection,
    val gate: SpecCollapsibleWorkspaceSection,
    val verify: SpecCollapsibleWorkspaceSection,
    val documents: SpecCollapsibleWorkspaceSection,
)

internal data class SpecWorkflowWorkspaceCardChrome(
    val summaryUi: SpecWorkflowWorkspaceSummaryUi,
    val sections: SpecWorkflowWorkspaceCardSections,
)

internal class SpecWorkflowWorkspaceCardBuilder(
    private val workspaceCardPanel: JPanel,
    private val backToListButton: JButton,
    private val overviewContent: Component,
    private val tasksContent: Component,
    private val gateContent: Component,
    private val verifyContent: Component,
    private val documentsContent: Component,
    private val sectionItems: MutableMap<SpecWorkflowWorkspaceSectionId, JPanel>,
    private val onSectionExpandedChanged: (SpecWorkflowWorkspaceSectionId, Boolean) -> Unit,
    private val emptyCardId: String,
    private val contentCardId: String,
) {

    fun build(): SpecWorkflowWorkspaceCardChrome {
        val summaryUi = createSummaryUi()
        val sectionsStack = object : JPanel(), Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

            override fun getScrollableUnitIncrement(
                visibleRect: Rectangle,
                orientation: Int,
                direction: Int,
            ): Int = JBUI.scale(WORKSPACE_SCROLL_UNIT_INCREMENT)

            override fun getScrollableBlockIncrement(
                visibleRect: Rectangle,
                orientation: Int,
                direction: Int,
            ): Int {
                val unit = getScrollableUnitIncrement(visibleRect, orientation, direction)
                return if (orientation == SwingConstants.VERTICAL) {
                    (visibleRect.height - unit).coerceAtLeast(unit)
                } else {
                    (visibleRect.width - unit).coerceAtLeast(unit)
                }
            }

            override fun getScrollableTracksViewportWidth(): Boolean = true

            override fun getScrollableTracksViewportHeight(): Boolean = false
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }

        val sections = SpecWorkflowWorkspaceCardSections(
            overview = createWorkspaceSection(
                id = SpecWorkflowWorkspaceSectionId.OVERVIEW,
                titleKey = "spec.toolwindow.section.overview",
                content = overviewContent,
            ),
            tasks = createWorkspaceSection(
                id = SpecWorkflowWorkspaceSectionId.TASKS,
                titleKey = "spec.toolwindow.section.tasks",
                content = tasksContent,
                maxExpandedBodyHeight = SCROLLABLE_WORKSPACE_SECTION_MAX_HEIGHT,
            ),
            gate = createWorkspaceSection(
                id = SpecWorkflowWorkspaceSectionId.GATE,
                titleKey = "spec.toolwindow.section.gate",
                content = gateContent,
                maxExpandedBodyHeight = SCROLLABLE_WORKSPACE_SECTION_MAX_HEIGHT,
            ),
            verify = createWorkspaceSection(
                id = SpecWorkflowWorkspaceSectionId.VERIFY,
                titleKey = "spec.toolwindow.section.verify",
                content = verifyContent,
                maxExpandedBodyHeight = SCROLLABLE_WORKSPACE_SECTION_MAX_HEIGHT,
            ),
            documents = createWorkspaceSection(
                id = SpecWorkflowWorkspaceSectionId.DOCUMENTS,
                titleKey = "spec.toolwindow.section.documents",
                content = documentsContent,
            ),
        )

        sectionsStack.add(createWorkspaceStackItem(buildWorkspaceSummaryCard(summaryUi)))
        sectionsStack.add(Box.createVerticalStrut(JBUI.scale(6)))

        sectionItems.clear()
        listOf(
            SpecWorkflowWorkspaceSectionId.OVERVIEW to sections.overview,
            SpecWorkflowWorkspaceSectionId.TASKS to sections.tasks,
            SpecWorkflowWorkspaceSectionId.GATE to sections.gate,
            SpecWorkflowWorkspaceSectionId.VERIFY to sections.verify,
            SpecWorkflowWorkspaceSectionId.DOCUMENTS to sections.documents,
        ).forEachIndexed { index, (sectionId, section) ->
            val item = createWorkspaceSectionItem(
                content = section,
                addBottomGap = index < SpecWorkflowWorkspaceSectionId.entries.lastIndex,
            )
            sectionItems[sectionId] = item
            sectionsStack.add(item)
        }

        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(
                JBScrollPane(sectionsStack).apply {
                    border = JBUI.Borders.empty()
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                    viewport.isOpaque = false
                    isOpaque = false
                    SpecUiStyle.applyFastVerticalScrolling(
                        scrollPane = this,
                        unitIncrement = WORKSPACE_SCROLL_UNIT_INCREMENT,
                        blockIncrement = WORKSPACE_SCROLL_BLOCK_INCREMENT,
                    )
                },
                BorderLayout.CENTER,
            )
        }

        workspaceCardPanel.apply {
            removeAll()
            isOpaque = false
            add(buildWorkspaceEmptyState(), emptyCardId)
            add(contentPanel, contentCardId)
        }
        return SpecWorkflowWorkspaceCardChrome(
            summaryUi = summaryUi,
            sections = sections,
        )
    }

    private fun createWorkspaceSection(
        id: SpecWorkflowWorkspaceSectionId,
        titleKey: String,
        content: Component,
        maxExpandedBodyHeight: Int? = null,
    ): SpecCollapsibleWorkspaceSection {
        return SpecCollapsibleWorkspaceSection(
            titleProvider = { SpecCodingBundle.message(titleKey) },
            content = content,
            expandedInitially = true,
            maxExpandedBodyHeight = maxExpandedBodyHeight,
            onExpandedChanged = { expanded ->
                onSectionExpandedChanged(id, expanded)
            },
        )
    }

    private fun createWorkspaceSectionItem(
        content: Component,
        addBottomGap: Boolean,
    ): JPanel {
        return createWorkspaceStackItem(
            component = createSectionContainer(
                content = content,
                padding = WORKSPACE_SECTION_CARD_PADDING,
                backgroundColor = DETAIL_SECTION_BG,
                borderColor = DETAIL_SECTION_BORDER,
            ),
            addBottomGap = addBottomGap,
        )
    }

    private fun createWorkspaceStackItem(
        component: Component,
        addBottomGap: Boolean = false,
    ): JPanel {
        return object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension {
                val preferred = preferredSize
                return Dimension(Int.MAX_VALUE, preferred.height)
            }
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            if (addBottomGap) {
                border = JBUI.Borders.emptyBottom(6)
            }
            add(component, BorderLayout.CENTER)
        }
    }

    private fun buildWorkspaceSummaryCard(summaryUi: SpecWorkflowWorkspaceSummaryUi): JPanel {
        summaryUi.titleLabel.font = JBUI.Fonts.label().deriveFont(Font.BOLD, 13f)
        summaryUi.titleLabel.foreground = WORKSPACE_SUMMARY_TITLE_FG
        summaryUi.metaLabel.font = JBUI.Fonts.smallFont()
        summaryUi.metaLabel.foreground = WORKSPACE_SUMMARY_META_FG
        summaryUi.focusLabel.font = JBUI.Fonts.label().deriveFont(Font.BOLD, 12.5f)
        summaryUi.focusLabel.foreground = WORKSPACE_SUMMARY_TITLE_FG
        summaryUi.hintLabel.font = JBUI.Fonts.smallFont()
        summaryUi.hintLabel.foreground = WORKSPACE_SUMMARY_META_FG

        val titleStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(summaryUi.titleLabel)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(summaryUi.metaLabel)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(summaryUi.focusLabel)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(summaryUi.hintLabel)
        }
        val headerRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(backToListButton)
                },
                BorderLayout.WEST,
            )
            add(titleStack, BorderLayout.CENTER)
        }
        val chipRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(summaryUi.stageMetric.root)
            add(summaryUi.gateMetric.root)
            add(summaryUi.tasksMetric.root)
            add(summaryUi.verifyMetric.root)
        }

        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            name = "workspaceSummaryCard"
            isOpaque = true
            background = WORKSPACE_SUMMARY_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = WORKSPACE_SUMMARY_BORDER,
                arc = JBUI.scale(16),
                top = 8,
                left = 10,
                bottom = 8,
                right = 10,
            )
            add(
                JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                    isOpaque = false
                    add(headerRow, BorderLayout.NORTH)
                    add(chipRow, BorderLayout.SOUTH)
                },
                BorderLayout.CENTER,
            )
        }
    }

    private fun createSummaryUi(): SpecWorkflowWorkspaceSummaryUi {
        return SpecWorkflowWorkspaceSummaryUi(
            titleLabel = JBLabel(),
            metaLabel = JBLabel(),
            focusLabel = JBLabel(),
            hintLabel = JBLabel(),
            stageMetric = createWorkspaceSummaryMetric(),
            gateMetric = createWorkspaceSummaryMetric(),
            tasksMetric = createWorkspaceSummaryMetric(),
            verifyMetric = createWorkspaceSummaryMetric(),
        )
    }

    private fun createWorkspaceSummaryMetric(): SpecWorkflowWorkspaceMetricUi {
        val titleLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont().deriveFont(10.5f)
            foreground = WORKSPACE_SUMMARY_LABEL_FG
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        val valueLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD, 10.5f)
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        val root = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)).apply {
            isOpaque = false
            isVisible = false
            add(titleLabel)
            add(valueLabel)
        }
        return SpecWorkflowWorkspaceMetricUi(
            root = root,
            titleLabel = titleLabel,
            valueLabel = valueLabel,
        )
    }

    private fun buildWorkspaceEmptyState(): JPanel {
        val titleLabel = JBLabel(SpecCodingBundle.message("spec.detail.noWorkflow")).apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD, 13f)
            foreground = WORKSPACE_EMPTY_TITLE_FG
        }
        val descriptionLabel = JBLabel(
            "<html>${SpecCodingBundle.message("spec.toolwindow.overview.empty")}</html>",
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = WORKSPACE_EMPTY_DESCRIPTION_FG
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = true
            background = DETAIL_SECTION_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = DETAIL_SECTION_BORDER,
                arc = JBUI.scale(16),
                top = 18,
                left = 18,
                bottom = 18,
                right = 18,
            )
            add(titleLabel, BorderLayout.NORTH)
            add(descriptionLabel, BorderLayout.CENTER)
        }
    }

    private fun createSectionContainer(
        content: Component,
        padding: Int,
        backgroundColor: Color,
        borderColor: Color,
    ): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = backgroundColor
            border = SpecUiStyle.roundedCardBorder(
                lineColor = borderColor,
                arc = JBUI.scale(14),
                top = padding,
                left = padding,
                bottom = padding,
                right = padding,
            )
            add(content, BorderLayout.CENTER)
        }
    }

    private companion object {
        private val WORKSPACE_SUMMARY_BG = JBColor(Color(245, 249, 255), Color(56, 62, 72))
        private val WORKSPACE_SUMMARY_BORDER = JBColor(Color(201, 214, 235), Color(86, 96, 110))
        private val WORKSPACE_SUMMARY_TITLE_FG = JBColor(Color(42, 59, 94), Color(214, 223, 236))
        private val WORKSPACE_SUMMARY_META_FG = JBColor(Color(94, 110, 139), Color(160, 171, 188))
        private val WORKSPACE_SUMMARY_LABEL_FG = JBColor(Color(112, 124, 143), Color(172, 182, 196))
        private val WORKSPACE_EMPTY_TITLE_FG = JBColor(Color(57, 72, 104), Color(214, 223, 236))
        private val WORKSPACE_EMPTY_DESCRIPTION_FG = JBColor(Color(101, 117, 145), Color(166, 176, 193))
        private val DETAIL_SECTION_BG = JBColor(Color(249, 252, 255), Color(50, 56, 65))
        private val DETAIL_SECTION_BORDER = JBColor(Color(204, 217, 236), Color(84, 94, 109))
        private const val WORKSPACE_SECTION_CARD_PADDING = 12
        private val SCROLLABLE_WORKSPACE_SECTION_MAX_HEIGHT = JBUI.scale(320)
        private const val WORKSPACE_SCROLL_UNIT_INCREMENT = 24
        private const val WORKSPACE_SCROLL_BLOCK_INCREMENT = 96
    }
}
