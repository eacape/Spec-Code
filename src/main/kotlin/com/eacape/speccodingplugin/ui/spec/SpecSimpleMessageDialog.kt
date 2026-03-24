package com.eacape.speccodingplugin.ui.spec

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel

internal class SpecSimpleMessageDialog(
    project: Project,
    dialogTitle: String,
    private val messageText: String,
    private val closeButtonText: String,
) : DialogWrapper(project, true) {

    init {
        title = dialogTitle
        setOKButtonText(closeButtonText)
        isResizable = false
        init()
        styleCloseButton()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val messageArea = JBTextArea(messageText).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            foreground = MESSAGE_FG
            font = JBUI.Fonts.label()
            border = JBUI.Borders.empty()
        }

        val contentCard = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = true
            background = CARD_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, JBUI.scale(4), 0, 0, ACCENT),
                SpecUiStyle.roundedCardBorder(
                    lineColor = CARD_BORDER,
                    arc = JBUI.scale(12),
                    top = 12,
                    left = 14,
                    bottom = 12,
                    right = 14,
                ),
            )
            add(
                JBLabel(title).apply {
                    font = JBUI.Fonts.label().deriveFont(Font.BOLD)
                    foreground = TITLE_FG
                },
                BorderLayout.NORTH,
            )
            add(messageArea, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(440), JBUI.scale(170))
            minimumSize = Dimension(JBUI.scale(380), JBUI.scale(150))
            isOpaque = true
            background = DIALOG_BG
            border = JBUI.Borders.empty(12, 12, 8, 12)
            add(contentCard, BorderLayout.CENTER)
        }
    }

    private fun styleCloseButton() {
        getButton(okAction)?.let { button ->
            button.isFocusPainted = false
            button.background = BUTTON_BG
            button.foreground = BUTTON_FG
            button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            button.border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
                JBUI.Borders.empty(4, 16, 4, 16),
            )
            SpecUiStyle.applyRoundRect(button, arc = 10)
            button.preferredSize = Dimension(JBUI.scale(90), JBUI.scale(30))
        }
    }

    companion object {
        private val DIALOG_BG = JBColor(Color(248, 250, 253), Color(53, 58, 66))
        private val CARD_BG = JBColor(Color(255, 250, 250), Color(61, 54, 57))
        private val CARD_BORDER = JBColor(Color(224, 197, 202), Color(118, 95, 101))
        private val ACCENT = JBColor(Color(207, 91, 106), Color(210, 126, 139))
        private val TITLE_FG = JBColor(Color(113, 49, 61), Color(237, 214, 218))
        private val MESSAGE_FG = JBColor(Color(90, 56, 63), Color(227, 214, 217))
        private val BUTTON_BG = JBColor(Color(249, 239, 241), Color(84, 70, 74))
        private val BUTTON_BORDER = JBColor(Color(217, 187, 193), Color(124, 103, 109))
        private val BUTTON_FG = JBColor(Color(92, 55, 62), Color(229, 215, 218))
    }
}
