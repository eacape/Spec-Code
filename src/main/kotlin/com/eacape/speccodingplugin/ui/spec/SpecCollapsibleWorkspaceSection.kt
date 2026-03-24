package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel

internal class SpecCollapsibleWorkspaceSection(
    private val titleProvider: () -> String,
    content: Component,
    expandedInitially: Boolean = true,
    private val maxExpandedBodyHeight: Int? = null,
    private val onExpandedChanged: (Boolean) -> Unit = {},
) : JPanel(BorderLayout(0, JBUI.scale(6))) {

    private val titleLabel = JBLabel()
    private val summaryLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = SECTION_SUMMARY_FG
        isVisible = false
    }
    private val toggleButton = JButton().apply {
        isFocusable = false
        isFocusPainted = false
        isContentAreaFilled = false
        isOpaque = false
        isBorderPainted = false
        font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        foreground = SECTION_TOGGLE_FG
        margin = JBUI.insets(0, 6, 0, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener {
            setExpanded(!isExpanded())
        }
    }
    private val contentWrapper = object : JPanel(BorderLayout()) {
        override fun getPreferredSize(): Dimension {
            val base = content.preferredSize ?: super.getPreferredSize()
            return base.withClampedHeight()
        }

        override fun getMaximumSize(): Dimension {
            val preferred = preferredSize
            return Dimension(Int.MAX_VALUE, preferred.height)
        }
    }.apply {
        isOpaque = false
        add(content, BorderLayout.CENTER)
    }
    private val bodyContainer = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(contentWrapper, BorderLayout.CENTER)
    }

    init {
        isOpaque = false
        border = JBUI.Borders.emptyTop(2)
        add(
            JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(2))).apply {
                isOpaque = false
                add(
                    JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                        isOpaque = false
                        add(titleLabel, BorderLayout.NORTH)
                        add(summaryLabel, BorderLayout.CENTER)
                    },
                    BorderLayout.CENTER,
                )
                add(toggleButton, BorderLayout.EAST)
            },
            BorderLayout.NORTH,
        )
        add(bodyContainer, BorderLayout.CENTER)
        refreshLocalizedTexts()
        setExpanded(expandedInitially, notify = false)
    }

    private fun Dimension.withClampedHeight(): Dimension {
        val clampedHeight = maxExpandedBodyHeight?.let { height.coerceAtMost(it) } ?: height
        return Dimension(width, clampedHeight)
    }

    fun refreshLocalizedTexts() {
        titleLabel.text = titleProvider()
        titleLabel.font = JBUI.Fonts.label().deriveFont(Font.BOLD, 12.5f)
        titleLabel.foreground = SECTION_TITLE_FG
        updateTogglePresentation()
    }

    fun setSummary(summary: String?) {
        val value = summary?.trim().orEmpty()
        summaryLabel.text = value
        summaryLabel.toolTipText = value.ifEmpty { null }
        summaryLabel.isVisible = value.isNotEmpty()
    }

    fun setExpanded(expanded: Boolean, notify: Boolean = true) {
        if (bodyContainer.isVisible == expanded) {
            updateTogglePresentation()
            return
        }
        bodyContainer.isVisible = expanded
        updateTogglePresentation()
        revalidate()
        repaint()
        if (notify) {
            onExpandedChanged(expanded)
        }
    }

    fun isExpanded(): Boolean = bodyContainer.isVisible

    private fun updateTogglePresentation() {
        val expanded = isExpanded()
        val key = if (expanded) {
            "spec.detail.toggle.collapse"
        } else {
            "spec.detail.toggle.expand"
        }
        toggleButton.text = SpecCodingBundle.message(key)
        toggleButton.toolTipText = toggleButton.text
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

    internal fun toggleButtonTextForTest(): String = toggleButton.text.orEmpty()

    internal fun toggleButtonHasEnoughWidthForTextForTest(): Boolean {
        val textWidth = toggleButton.getFontMetrics(toggleButton.font).stringWidth(toggleButton.text.orEmpty())
        val horizontalPadding = toggleButton.margin.left + toggleButton.margin.right + JBUI.scale(14)
        return toggleButton.preferredSize.width >= textWidth + horizontalPadding
    }

    internal fun toggleButtonCanFitTextForTest(text: String): Boolean {
        toggleButton.text = text
        applyToggleButtonSize()
        return toggleButtonHasEnoughWidthForTextForTest()
    }

    internal fun bodyPreferredHeightForTest(): Int = contentWrapper.preferredSize.height

    internal fun bodyMaximumHeightForTest(): Int = contentWrapper.maximumSize.height

    companion object {
        private val SECTION_TITLE_FG = JBColor(Color(53, 70, 108), Color(211, 220, 235))
        private val SECTION_SUMMARY_FG = JBColor(Color(102, 116, 144), Color(154, 166, 184))
        private val SECTION_TOGGLE_FG = JBColor(Color(86, 108, 148), Color(170, 184, 208))
    }
}
