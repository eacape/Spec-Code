package com.eacape.speccodingplugin.ui.spec

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

internal class SpecWorkflowStatusStripUiHost(
    statusChipBackground: Color,
    statusChipBorder: Color,
    statusTextColor: Color,
    private val styleActionButton: (JButton) -> Unit,
    private val performAction: (SpecWorkflowTroubleshootingAction) -> Unit,
    val statusLabel: JBLabel = JBLabel(""),
    val statusActionPanel: JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)),
    val statusChipPanel: JPanel = JPanel(BorderLayout()),
) {

    init {
        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = statusTextColor
        statusActionPanel.isOpaque = false
        statusActionPanel.isVisible = false
        statusChipPanel.isOpaque = true
        statusChipPanel.background = statusChipBackground
        statusChipPanel.border = SpecUiStyle.roundedCardBorder(
            lineColor = statusChipBorder,
            arc = JBUI.scale(10),
            top = 1,
            left = 6,
            bottom = 1,
            right = 6,
        )
        statusChipPanel.isVisible = false
        statusChipPanel.removeAll()
        statusChipPanel.add(statusLabel, BorderLayout.CENTER)
        statusChipPanel.add(statusActionPanel, BorderLayout.EAST)
    }

    fun apply(presentation: SpecWorkflowStatusPresentation) {
        statusLabel.text = presentation.text
        statusActionPanel.removeAll()
        presentation.actions.forEach { action ->
            statusActionPanel.add(createActionButton(action))
        }
        statusActionPanel.isVisible = presentation.actions.isNotEmpty()
        statusChipPanel.isVisible = presentation.text.isNotEmpty()
        statusChipPanel.revalidate()
        statusChipPanel.repaint()
    }

    internal fun currentStatusTextForTest(): String = statusLabel.text.orEmpty()

    internal fun currentStatusActionLabelsForTest(): List<String> {
        return statusActionPanel.components
            .filterIsInstance<JButton>()
            .map { button -> button.text.orEmpty() }
            .filter { label -> label.isNotBlank() }
    }

    private fun createActionButton(action: SpecWorkflowTroubleshootingAction): JButton {
        return JButton(action.label).apply {
            addActionListener { performAction(action) }
            styleActionButton(this)
        }
    }
}
