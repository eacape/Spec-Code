package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel

internal class SpecDetailCollapsibleSectionView(
    titleLabel: JBLabel,
    content: Component,
    expandedInitially: Boolean,
    private val activeToggleForeground: Color,
    private val inactiveToggleForeground: Color,
    private val disabledToggleForeground: Color,
    private val onToggle: (Boolean) -> Unit,
) {

    val bodyContainer = JPanel(BorderLayout()).apply {
        isOpaque = false
        isVisible = expandedInitially
        add(content, BorderLayout.CENTER)
    }

    private val toggleButton = JButton().apply {
        isFocusable = false
        isFocusPainted = false
        isContentAreaFilled = false
        isOpaque = false
        isBorderPainted = false
        font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        foreground = inactiveToggleForeground
        margin = JBUI.insets(0, 6, 0, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener {
            val nextExpanded = !bodyContainer.isVisible
            bodyContainer.isVisible = nextExpanded
            onToggle(nextExpanded)
        }
    }

    val root = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
        isOpaque = false
        add(
            JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(titleLabel, BorderLayout.WEST)
                add(toggleButton, BorderLayout.EAST)
            },
            BorderLayout.NORTH,
        )
        add(bodyContainer, BorderLayout.CENTER)
    }

    init {
        applyToggleState(expanded = expandedInitially, enabled = true)
    }

    fun setBodyVisible(visible: Boolean) {
        bodyContainer.isVisible = visible
    }

    fun applyToggleState(expanded: Boolean, enabled: Boolean) {
        val key = if (expanded && enabled) {
            "spec.detail.toggle.collapse"
        } else {
            "spec.detail.toggle.expand"
        }
        toggleButton.text = SpecCodingBundle.message(key)
        toggleButton.toolTipText = toggleButton.text
        toggleButton.isEnabled = enabled
        toggleButton.foreground = when {
            !enabled -> disabledToggleForeground
            expanded -> activeToggleForeground
            else -> inactiveToggleForeground
        }
        toggleButton.cursor = if (enabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
        applyToggleButtonSize()
    }

    private fun applyToggleButtonSize() {
        val textWidth = toggleButton.getFontMetrics(toggleButton.font).stringWidth(toggleButton.text.orEmpty())
        val horizontalPadding = toggleButton.margin.left + toggleButton.margin.right + JBUI.scale(14)
        val targetWidth = maxOf(JBUI.scale(68), textWidth + horizontalPadding)
        val targetSize = JBUI.size(targetWidth, JBUI.scale(22))
        toggleButton.preferredSize = targetSize
        toggleButton.minimumSize = targetSize
    }

    internal fun clickToggleForTest() {
        toggleButton.doClick()
    }

    internal fun toggleTextForTest(): String = toggleButton.text.orEmpty()

    internal fun toggleHasEnoughWidthForTest(): Boolean {
        val textWidth = toggleButton.getFontMetrics(toggleButton.font).stringWidth(toggleButton.text.orEmpty())
        val horizontalPadding = toggleButton.margin.left + toggleButton.margin.right + JBUI.scale(14)
        return toggleButton.preferredSize.width >= textWidth + horizontalPadding
    }

    internal fun toggleCanFitTextForTest(text: String): Boolean {
        toggleButton.text = text
        applyToggleButtonSize()
        return toggleHasEnoughWidthForTest()
    }

    internal fun isToggleEnabledForTest(): Boolean = toggleButton.isEnabled

    internal fun isToggleHandCursorForTest(): Boolean = toggleButton.cursor.type == Cursor.HAND_CURSOR
}
