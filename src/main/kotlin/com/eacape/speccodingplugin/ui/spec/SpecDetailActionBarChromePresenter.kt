package com.eacape.speccodingplugin.ui.spec

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton

internal class SpecDetailActionBarChromePresenter(
    private val buttons: SpecDetailActionBarButtons,
) {

    fun refreshChrome() {
        buttons.all().forEach(SpecDetailButtonChromeStyler::apply)
    }

    fun applySetupVisibility() {
        buttons.save.isVisible = false
        buttons.cancelEdit.isVisible = false
        buttons.confirmGenerate.isVisible = false
        buttons.regenerateClarification.isVisible = false
        buttons.skipClarification.isVisible = false
        buttons.cancelClarification.isVisible = false
    }
}

internal object SpecDetailButtonChromeStyler {

    fun apply(button: JButton) {
        val iconOnly = button.icon != null && button.text.isNullOrBlank()
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = if (iconOnly) JBUI.insets(0, 0, 0, 0) else JBUI.insets(1, 4, 1, 4)
        button.isOpaque = true
        button.foreground = BUTTON_FG
        SpecUiStyle.applyRoundRect(button, arc = 10)
        if (iconOnly) {
            SpecUiStyle.styleIconActionButton(button, size = 22, arc = 10)
        } else {
            button.background = BUTTON_BG
            button.border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
                JBUI.Borders.empty(1, 5, 1, 5),
            )
            val textWidth = button.getFontMetrics(button.font).stringWidth(button.text ?: "")
            val insets = button.insets
            val lafWidth = button.preferredSize?.width ?: 0
            val width = maxOf(
                lafWidth,
                textWidth + insets.left + insets.right + JBUI.scale(10),
                JBUI.scale(40),
            )
            button.preferredSize = JBUI.size(width, JBUI.scale(26))
            button.minimumSize = button.preferredSize
        }
        button.cursor = if (button.isEnabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
    private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
    private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
}
