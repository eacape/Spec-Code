package com.eacape.speccodingplugin.ui.spec

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.CardLayout
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

internal class SpecWorkflowDocumentWorkspaceViewUiHost(
    private val tabsPanel: JPanel,
    private val viewLabel: JBLabel,
    private val cardLayout: CardLayout,
    private val cardPanel: JPanel,
    private val buttons: Map<DocumentWorkspaceView, JButton>,
    private val resolvePresentation: () -> SpecWorkflowDocumentWorkspaceViewPresentation,
    private val message: (String) -> String,
    private val documentCardId: String,
    private val structuredTasksCardId: String,
    private val syncStructuredTaskSelectionUi: (String?) -> Unit,
) : SpecWorkflowDocumentWorkspaceViewUi {

    override fun setDocumentWorkspaceTabsVisible(visible: Boolean) {
        tabsPanel.isVisible = visible
    }

    override fun showDocumentWorkspaceCard(view: DocumentWorkspaceView) {
        cardLayout.show(
            cardPanel,
            when (view) {
                DocumentWorkspaceView.DOCUMENT -> documentCardId
                DocumentWorkspaceView.STRUCTURED_TASKS -> structuredTasksCardId
            },
        )
    }

    override fun refreshDocumentWorkspaceViewButtons() {
        buttons.values.forEach(::refreshDocumentWorkspaceViewButtonStyle)
    }

    override fun syncStructuredTaskSelection(taskId: String?) {
        syncStructuredTaskSelectionUi(taskId)
    }

    override fun refreshDocumentWorkspaceContainers() {
        tabsPanel.revalidate()
        tabsPanel.repaint()
        cardPanel.revalidate()
        cardPanel.repaint()
    }

    fun refreshLocalizedTexts() {
        viewLabel.text = message("spec.toolwindow.documents.view.label")
        buttons.forEach { (view, button) ->
            val (labelKey, tooltipKey) = buttonPresentationKeys(view)
            button.text = message(labelKey)
            button.toolTipText = message(tooltipKey)
        }
        refreshDocumentWorkspaceViewButtons()
    }

    fun refreshDocumentWorkspaceViewButtonStyle(button: JButton) {
        val view = button.getClientProperty("documentWorkspaceView") as? DocumentWorkspaceView ?: return
        val presentation = resolvePresentation()
        val selected = view == presentation.effectiveView
        val enabled = view != DocumentWorkspaceView.STRUCTURED_TASKS || presentation.supportsStructuredTasksView
        val hovered = enabled && button.model.isRollover && !selected
        applyDocumentWorkspaceViewButtonStyle(
            button = button,
            selected = selected,
            enabled = enabled,
            hovered = hovered,
        )
    }

    private fun applyDocumentWorkspaceViewButtonStyle(
        button: JButton,
        selected: Boolean,
        enabled: Boolean,
        hovered: Boolean,
    ) {
        button.isEnabled = enabled
        button.background = when {
            !enabled -> DOCUMENT_WORKSPACE_VIEW_GROUP_BG
            selected -> DOCUMENT_WORKSPACE_VIEW_SELECTED_BG
            hovered -> DOCUMENT_WORKSPACE_VIEW_HOVER_BG
            else -> DOCUMENT_WORKSPACE_VIEW_IDLE_BG
        }
        button.foreground = when {
            !enabled -> DOCUMENT_WORKSPACE_VIEW_DISABLED_FG
            selected -> DOCUMENT_WORKSPACE_VIEW_SELECTED_FG
            hovered -> DOCUMENT_WORKSPACE_VIEW_HOVER_FG
            else -> DOCUMENT_WORKSPACE_VIEW_IDLE_FG
        }
        button.border = BorderFactory.createCompoundBorder(
            if (selected || hovered) {
                SpecUiStyle.roundedLineBorder(
                    if (selected) DOCUMENT_WORKSPACE_VIEW_SELECTED_BORDER else DOCUMENT_WORKSPACE_VIEW_HOVER_BORDER,
                    JBUI.scale(DOCUMENT_WORKSPACE_VIEW_BUTTON_ARC),
                )
            } else {
                JBUI.Borders.empty()
            },
            JBUI.Borders.empty(
                DOCUMENT_WORKSPACE_VIEW_BUTTON_VERTICAL_PADDING,
                DOCUMENT_WORKSPACE_VIEW_BUTTON_HORIZONTAL_PADDING,
            ),
        )
        button.font = JBUI.Fonts.smallFont().deriveFont(
            if (selected) Font.BOLD else Font.PLAIN,
            DOCUMENT_WORKSPACE_VIEW_BUTTON_FONT_SIZE,
        )
        val labelFont = JBUI.Fonts.smallFont().deriveFont(
            Font.BOLD,
            DOCUMENT_WORKSPACE_VIEW_BUTTON_FONT_SIZE,
        )
        val width = documentWorkspaceViewButtonTargetWidth(labelFont)
        val size = JBUI.size(width, JBUI.scale(DOCUMENT_WORKSPACE_VIEW_BUTTON_HEIGHT))
        button.preferredSize = size
        button.minimumSize = size
        button.maximumSize = size
    }

    private fun documentWorkspaceViewButtonTargetWidth(labelFont: Font): Int {
        val scaledMinWidth = JBUI.scale(DOCUMENT_WORKSPACE_VIEW_BUTTON_MIN_WIDTH)
        val scaledTextPadding = JBUI.scale(
            DOCUMENT_WORKSPACE_VIEW_BUTTON_HORIZONTAL_PADDING * 2 +
                DOCUMENT_WORKSPACE_VIEW_BUTTON_EXTRA_WIDTH_PADDING,
        )
        return maxOf(
            buttons.values.maxOfOrNull { candidate ->
                candidate.getFontMetrics(labelFont).stringWidth(candidate.text.orEmpty()) + scaledTextPadding
            } ?: scaledMinWidth,
            scaledMinWidth,
        )
    }

    private fun buttonPresentationKeys(view: DocumentWorkspaceView): Pair<String, String> {
        return when (view) {
            DocumentWorkspaceView.DOCUMENT -> {
                "spec.toolwindow.documents.view.document" to
                    "spec.toolwindow.documents.view.document.tooltip"
            }

            DocumentWorkspaceView.STRUCTURED_TASKS -> {
                "spec.toolwindow.documents.view.structuredTasks" to
                    "spec.toolwindow.documents.view.structuredTasks.tooltip"
            }
        }
    }

    private companion object {
        private val DOCUMENT_WORKSPACE_VIEW_GROUP_BG = JBColor(Color(242, 247, 255), Color(57, 63, 73))
        private val DOCUMENT_WORKSPACE_VIEW_SELECTED_BG = JBColor(Color(233, 242, 255), Color(71, 80, 95))
        private val DOCUMENT_WORKSPACE_VIEW_SELECTED_BORDER = JBColor(Color(174, 196, 229), Color(112, 126, 148))
        private val DOCUMENT_WORKSPACE_VIEW_SELECTED_FG = JBColor(Color(43, 67, 105), Color(214, 224, 238))
        private val DOCUMENT_WORKSPACE_VIEW_IDLE_BG = DOCUMENT_WORKSPACE_VIEW_GROUP_BG
        private val DOCUMENT_WORKSPACE_VIEW_IDLE_FG = JBColor(Color(89, 103, 130), Color(177, 188, 203))
        private val DOCUMENT_WORKSPACE_VIEW_HOVER_BG = JBColor(Color(247, 250, 255), Color(63, 71, 82))
        private val DOCUMENT_WORKSPACE_VIEW_HOVER_BORDER = JBColor(Color(198, 213, 237), Color(96, 108, 125))
        private val DOCUMENT_WORKSPACE_VIEW_HOVER_FG = JBColor(Color(71, 89, 122), Color(192, 202, 216))
        private val DOCUMENT_WORKSPACE_VIEW_DISABLED_FG = JBColor(Color(146, 156, 171), Color(124, 132, 145))
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_FONT_SIZE = 10.5f
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_ARC = 10
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_HEIGHT = 22
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_MIN_WIDTH = 52
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_HORIZONTAL_PADDING = 10
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_VERTICAL_PADDING = 2
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_EXTRA_WIDTH_PADDING = 16
    }
}
