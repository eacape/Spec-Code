package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.ui.chat.MarkdownRenderer
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants

internal class ClarificationQuestionConfirmDialog(
    private val question: String,
    initialDetail: String,
) : DialogWrapper(true) {

    private val questionPane = JTextPane()
    private val detailArea = JBTextArea(initialDetail, 4, 42)

    var confirmedDetail: String = normalizeDetail(initialDetail)
        private set

    init {
        title = SpecCodingBundle.message("spec.detail.clarify.dialog.title")
        setOKButtonText(SpecCodingBundle.message("spec.detail.clarify.dialog.ok"))
        setCancelButtonText(SpecCodingBundle.message("spec.detail.clarify.dialog.cancel"))
        init()
        styleDialogButtons()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(330))
            minimumSize = Dimension(JBUI.scale(460), JBUI.scale(280))
            isOpaque = true
            background = DIALOG_BG
            border = JBUI.Borders.empty(10, 10, 8, 10)
        }

        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val header = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = HEADER_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = HEADER_BORDER,
                arc = JBUI.scale(12),
                top = 8,
                left = 10,
                bottom = 8,
                right = 10,
            )
            add(
                JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(
                        JBLabel("✓").apply {
                            font = JBUI.Fonts.label().deriveFont(Font.BOLD, 14f)
                            foreground = HEADER_ACCENT
                        },
                        BorderLayout.WEST,
                    )
                    add(
                        JPanel().apply {
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            isOpaque = false
                            add(
                                JBLabel(SpecCodingBundle.message("spec.detail.clarify.dialog.header")).apply {
                                    font = JBUI.Fonts.label().deriveFont(Font.BOLD)
                                    foreground = HEADER_TITLE_FG
                                },
                            )
                            add(Box.createVerticalStrut(JBUI.scale(2)))
                            add(
                                JBLabel(SpecCodingBundle.message("spec.detail.clarify.dialog.hint")).apply {
                                    font = JBUI.Fonts.smallFont()
                                    foreground = HEADER_HINT_FG
                                },
                            )
                        },
                        BorderLayout.CENTER,
                    )
                },
                BorderLayout.CENTER,
            )
        }
        body.add(header)
        body.add(Box.createVerticalStrut(JBUI.scale(8)))

        questionPane.isEditable = false
        questionPane.isOpaque = false
        questionPane.border = JBUI.Borders.empty(2, 2, 2, 2)
        MarkdownRenderer.render(
            questionPane,
            question.ifBlank { SpecCodingBundle.message("common.unknown") },
        )

        val questionCard = createCard(
            title = SpecCodingBundle.message("spec.detail.clarify.dialog.question"),
            content = JBScrollPane(questionPane).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                preferredSize = Dimension(0, JBUI.scale(92))
            },
        )
        body.add(questionCard)
        body.add(Box.createVerticalStrut(JBUI.scale(8)))

        detailArea.lineWrap = true
        detailArea.wrapStyleWord = true
        detailArea.isOpaque = true
        detailArea.background = DETAIL_INPUT_BG
        detailArea.foreground = DETAIL_INPUT_FG
        detailArea.border = JBUI.Borders.empty(4, 8, 4, 8)
        detailArea.emptyText.setText(SpecCodingBundle.message("spec.detail.clarify.dialog.detail.placeholder"))

        val detailCard = createCard(
            title = SpecCodingBundle.message("spec.detail.clarify.dialog.detail"),
            content = JBScrollPane(detailArea).apply {
                border = BorderFactory.createCompoundBorder(
                    SpecUiStyle.roundedLineBorder(DETAIL_INPUT_BORDER, JBUI.scale(10)),
                    JBUI.Borders.empty(),
                )
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                preferredSize = Dimension(0, JBUI.scale(104))
            },
        )
        body.add(detailCard)

        root.add(body, BorderLayout.CENTER)
        return root
    }

    override fun doOKAction() {
        confirmedDetail = normalizeDetail(detailArea.text)
        super.doOKAction()
    }

    override fun getPreferredFocusedComponent(): JComponent = detailArea

    private fun createCard(title: String, content: JComponent): JComponent {
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = true
            background = CARD_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = CARD_BORDER,
                arc = JBUI.scale(10),
                top = 6,
                left = 8,
                bottom = 6,
                right = 8,
            )
            add(
                JBLabel(title).apply {
                    font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
                    foreground = TITLE_FG
                },
                BorderLayout.NORTH,
            )
            add(content, BorderLayout.CENTER)
        }
    }

    private fun styleDialogButtons() {
        getButton(okAction)?.let { styleDialogButton(it, primary = true) }
        getButton(cancelAction)?.let { styleDialogButton(it, primary = false) }
    }

    private fun styleDialogButton(button: JButton, primary: Boolean) {
        val bg = if (primary) BUTTON_PRIMARY_BG else BUTTON_BG
        val borderColor = if (primary) BUTTON_PRIMARY_BORDER else BUTTON_BORDER
        val fg = if (primary) BUTTON_PRIMARY_FG else BUTTON_FG

        button.isFocusPainted = false
        button.isFocusable = false
        button.background = bg
        button.foreground = fg
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(borderColor, JBUI.scale(10)),
            JBUI.Borders.empty(4, 14, 4, 14),
        )
        SpecUiStyle.applyRoundRect(button, arc = 10)
        button.preferredSize = Dimension(JBUI.scale(90), JBUI.scale(30))
    }

    private fun normalizeDetail(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    companion object {
        private val DIALOG_BG = JBColor(Color(246, 250, 255), Color(52, 58, 68))
        private val HEADER_BG = JBColor(Color(237, 246, 255), Color(64, 74, 89))
        private val HEADER_BORDER = JBColor(Color(182, 204, 234), Color(101, 120, 148))
        private val HEADER_ACCENT = JBColor(Color(56, 113, 192), Color(167, 204, 255))
        private val HEADER_TITLE_FG = JBColor(Color(40, 70, 114), Color(219, 231, 248))
        private val HEADER_HINT_FG = JBColor(Color(92, 113, 143), Color(178, 193, 216))
        private val CARD_BG = JBColor(Color(250, 253, 255), Color(58, 64, 75))
        private val CARD_BORDER = JBColor(Color(198, 212, 232), Color(96, 108, 126))
        private val TITLE_FG = JBColor(Color(57, 83, 122), Color(208, 220, 239))
        private val DETAIL_INPUT_BG = JBColor(Color(248, 252, 255), Color(63, 70, 82))
        private val DETAIL_INPUT_BORDER = JBColor(Color(186, 202, 227), Color(102, 114, 133))
        private val DETAIL_INPUT_FG = JBColor(Color(42, 64, 99), Color(214, 224, 240))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val BUTTON_PRIMARY_BG = JBColor(Color(214, 229, 250), Color(78, 99, 129))
        private val BUTTON_PRIMARY_BORDER = JBColor(Color(154, 180, 219), Color(116, 137, 169))
        private val BUTTON_PRIMARY_FG = JBColor(Color(37, 57, 89), Color(223, 232, 246))
    }
}
