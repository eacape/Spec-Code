package com.eacape.speccodingplugin.ui.spec

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

internal data class SpecWorkflowDocumentWorkspaceViewChrome(
    val container: JPanel,
    val label: JBLabel,
    val tabsPanel: JPanel,
    val switcherPanel: JPanel,
    val cardPanel: JPanel,
    val buttons: Map<DocumentWorkspaceView, JButton>,
    val uiHost: SpecWorkflowDocumentWorkspaceViewUiHost,
)

internal class SpecWorkflowDocumentWorkspaceViewChromeBuilder(
    private val documentContent: Component,
    private val structuredTasksContent: Component,
    private val installToolbarButtonCursorTracking: (JButton) -> Unit,
    private val onViewSelected: (DocumentWorkspaceView) -> Unit,
    private val resolvePresentation: () -> SpecWorkflowDocumentWorkspaceViewPresentation,
    private val message: (String) -> String,
    private val syncStructuredTaskSelectionUi: (String?) -> Unit,
) {

    fun build(): SpecWorkflowDocumentWorkspaceViewChrome {
        val buttons = linkedMapOf<DocumentWorkspaceView, JButton>()
        var uiHost: SpecWorkflowDocumentWorkspaceViewUiHost? = null
        val label = JBLabel(message("spec.toolwindow.documents.view.label")).apply {
            foreground = DOCUMENT_WORKSPACE_VIEW_LABEL_FG
            font = JBUI.Fonts.smallFont().deriveFont(DOCUMENT_WORKSPACE_VIEW_LABEL_FONT_SIZE)
        }
        val switcherPanel = JPanel(
            FlowLayout(
                FlowLayout.LEFT,
                JBUI.scale(DOCUMENT_WORKSPACE_VIEW_SWITCHER_GAP),
                0,
            ),
        ).apply {
            isOpaque = true
            background = DOCUMENT_WORKSPACE_VIEW_GROUP_BG
            border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(
                    DOCUMENT_WORKSPACE_VIEW_GROUP_BORDER,
                    JBUI.scale(DOCUMENT_WORKSPACE_VIEW_GROUP_ARC),
                ),
                JBUI.Borders.empty(
                    DOCUMENT_WORKSPACE_VIEW_GROUP_INSET,
                    DOCUMENT_WORKSPACE_VIEW_GROUP_INSET,
                ),
            )
        }
        val tabsPanel = JPanel(
            FlowLayout(
                FlowLayout.LEFT,
                JBUI.scale(DOCUMENT_WORKSPACE_VIEW_ROW_GAP),
                0,
            ),
        ).apply {
            isOpaque = false
            add(label)
            add(switcherPanel)
        }
        DocumentWorkspaceView.entries.forEach { view ->
            switcherPanel.add(
                createDocumentWorkspaceViewButton(
                    view = view,
                    labelKey = buttonLabelKey(view),
                    tooltipKey = buttonTooltipKey(view),
                    uiHostProvider = { uiHost },
                ).also { button ->
                    buttons[view] = button
                },
            )
        }
        val cardPanel = JPanel(CardLayout()).apply {
            isOpaque = false
            add(documentContent, DOCUMENT_WORKSPACE_CARD_DOCUMENT)
            add(structuredTasksContent, DOCUMENT_WORKSPACE_CARD_STRUCTURED_TASKS)
        }
        val host = SpecWorkflowDocumentWorkspaceViewUiHost(
            tabsPanel = tabsPanel,
            viewLabel = label,
            cardLayout = cardPanel.layout as CardLayout,
            cardPanel = cardPanel,
            buttons = buttons,
            resolvePresentation = resolvePresentation,
            message = message,
            documentCardId = DOCUMENT_WORKSPACE_CARD_DOCUMENT,
            structuredTasksCardId = DOCUMENT_WORKSPACE_CARD_STRUCTURED_TASKS,
            syncStructuredTaskSelectionUi = syncStructuredTaskSelectionUi,
        )
        uiHost = host
        val container = JPanel(BorderLayout(0, JBUI.scale(DOCUMENT_WORKSPACE_VIEW_CONTAINER_GAP))).apply {
            isOpaque = false
            add(tabsPanel, BorderLayout.NORTH)
            add(cardPanel, BorderLayout.CENTER)
        }
        return SpecWorkflowDocumentWorkspaceViewChrome(
            container = container,
            label = label,
            tabsPanel = tabsPanel,
            switcherPanel = switcherPanel,
            cardPanel = cardPanel,
            buttons = buttons,
            uiHost = host,
        )
    }

    private fun createDocumentWorkspaceViewButton(
        view: DocumentWorkspaceView,
        labelKey: String,
        tooltipKey: String,
        uiHostProvider: () -> SpecWorkflowDocumentWorkspaceViewUiHost?,
    ): JButton {
        return JButton().apply {
            isFocusable = false
            isFocusPainted = false
            isOpaque = true
            isBorderPainted = true
            isContentAreaFilled = true
            isRolloverEnabled = true
            margin = JBUI.emptyInsets()
            installToolbarButtonCursorTracking(this)
            addActionListener {
                onViewSelected(view)
            }
            model.addChangeListener {
                uiHostProvider()?.refreshDocumentWorkspaceViewButtonStyle(this)
            }
            putClientProperty("documentWorkspaceView", view)
            text = message(labelKey)
            toolTipText = message(tooltipKey)
            font = JBUI.Fonts.smallFont()
        }
    }

    private fun buttonLabelKey(view: DocumentWorkspaceView): String {
        return when (view) {
            DocumentWorkspaceView.DOCUMENT -> "spec.toolwindow.documents.view.document"
            DocumentWorkspaceView.STRUCTURED_TASKS -> "spec.toolwindow.documents.view.structuredTasks"
        }
    }

    private fun buttonTooltipKey(view: DocumentWorkspaceView): String {
        return when (view) {
            DocumentWorkspaceView.DOCUMENT -> "spec.toolwindow.documents.view.document.tooltip"
            DocumentWorkspaceView.STRUCTURED_TASKS -> "spec.toolwindow.documents.view.structuredTasks.tooltip"
        }
    }

    private companion object {
        private val DOCUMENT_WORKSPACE_VIEW_LABEL_FG = JBColor(Color(112, 124, 143), Color(172, 182, 196))
        private val DOCUMENT_WORKSPACE_VIEW_GROUP_BG = JBColor(Color(242, 247, 255), Color(57, 63, 73))
        private val DOCUMENT_WORKSPACE_VIEW_GROUP_BORDER = JBColor(Color(202, 215, 236), Color(89, 100, 116))
        private const val DOCUMENT_WORKSPACE_VIEW_LABEL_FONT_SIZE = 10.5f
        private const val DOCUMENT_WORKSPACE_VIEW_ROW_GAP = 6
        private const val DOCUMENT_WORKSPACE_VIEW_SWITCHER_GAP = 2
        private const val DOCUMENT_WORKSPACE_VIEW_GROUP_INSET = 2
        private const val DOCUMENT_WORKSPACE_VIEW_GROUP_ARC = 11
        private const val DOCUMENT_WORKSPACE_VIEW_CONTAINER_GAP = 6
        private const val DOCUMENT_WORKSPACE_CARD_DOCUMENT = "document"
        private const val DOCUMENT_WORKSPACE_CARD_STRUCTURED_TASKS = "structuredTasks"
    }
}
