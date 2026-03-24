package com.eacape.speccodingplugin.ui.worktree

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.intellij.ui.JBColor
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.util.UUID
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class NewWorktreeDialog(
    specTaskId: String? = null,
    specTitle: String? = null,
    baseBranch: String = DEFAULT_BASE_BRANCH,
) : DialogWrapper(true) {

    private val resolvedSpecTaskId = resolveSpecTaskId(specTaskId)
    private val shortNameField = JBTextField()
    private val baseBranchField = JBTextField(baseBranch.trim().ifBlank { DEFAULT_BASE_BRANCH })
    private val branchPreviewValueLabel = JBLabel()

    var resultSpecTaskId: String? = null
        private set
    var resultShortName: String? = null
        private set
    var resultBaseBranch: String? = null
        private set

    init {
        title = SpecCodingBundle.message("worktree.dialog.new.title")
        if (!specTitle.isNullOrBlank()) {
            setTitle(SpecCodingBundle.message("worktree.dialog.new.title.withSpec", specTitle))
        }
        shortNameField.emptyText.text = SpecCodingBundle.message("worktree.dialog.field.shortName.placeholder")
        baseBranchField.emptyText.text = SpecCodingBundle.message("worktree.dialog.field.baseBranch.placeholder")
        shortNameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refreshBranchPreview()

            override fun removeUpdate(e: DocumentEvent?) = refreshBranchPreview()

            override fun changedUpdate(e: DocumentEvent?) = refreshBranchPreview()
        })
        init()
        styleDialogButtons()
        refreshBranchPreview()
    }

    override fun createCenterPanel(): JComponent {
        val formPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 10, 6, 10)
        }

        val subtitleLabel = JBLabel(SpecCodingBundle.message("worktree.dialog.new.subtitle")).apply {
            font = JBUI.Fonts.smallFont()
            foreground = SUBTITLE_FG
            border = JBUI.Borders.empty(0, 2, 8, 2)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        formPanel.add(subtitleLabel)
        formPanel.add(labeledField(SpecCodingBundle.message("worktree.dialog.field.shortName"), shortNameField))
        formPanel.add(Box.createVerticalStrut(10))
        formPanel.add(labeledField(SpecCodingBundle.message("worktree.dialog.field.baseBranch"), baseBranchField))
        formPanel.add(Box.createVerticalStrut(12))
        formPanel.add(createBranchPreviewCard())

        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(238))
            isOpaque = true
            background = DIALOG_BG
            border = JBUI.Borders.empty(8, 8, 6, 8)
            add(formPanel, BorderLayout.CENTER)
        }
    }

    override fun doValidate(): ValidationInfo? {
        return when (validateInput(readInput())) {
            NewWorktreeValidationError.SHORT_NAME_REQUIRED -> {
                ValidationInfo(
                    SpecCodingBundle.message("worktree.dialog.validation.shortNameRequired"),
                    shortNameField,
                )
            }

            NewWorktreeValidationError.BASE_BRANCH_REQUIRED -> {
                ValidationInfo(
                    SpecCodingBundle.message("worktree.dialog.validation.baseBranchRequired"),
                    baseBranchField,
                )
            }

            null -> null
        }
    }

    override fun doOKAction() {
        val normalized = normalizeInput(readInput())
        resultSpecTaskId = normalized.specTaskId
        resultShortName = normalized.shortName
        resultBaseBranch = normalized.baseBranch
        super.doOKAction()
    }

    override fun getPreferredFocusedComponent(): JComponent = shortNameField

    private fun labeledField(label: String, field: JBTextField): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            isOpaque = false
        }
        panel.add(
            JBLabel(label).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
                foreground = LABEL_FG
            }
        )
        panel.add(Box.createVerticalStrut(4))
        panel.add(field.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
            preferredSize = Dimension(0, JBUI.scale(30))
            minimumSize = preferredSize
            background = INPUT_BG
            foreground = INPUT_FG
            border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(INPUT_BORDER, JBUI.scale(10)),
                JBUI.Borders.empty(4, 8, 4, 8),
            )
        })
        return panel
    }

    private fun createBranchPreviewCard(): JComponent {
        val titleLabel = JBLabel(SpecCodingBundle.message("worktree.dialog.preview.branch")).apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            foreground = LABEL_FG
        }
        branchPreviewValueLabel.font = JBUI.Fonts.smallFont()
        branchPreviewValueLabel.foreground = PREVIEW_VALUE_FG

        return JPanel(BorderLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            isOpaque = true
            background = PREVIEW_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = PREVIEW_BORDER,
                arc = JBUI.scale(10),
                top = 6,
                left = 8,
                bottom = 6,
                right = 8,
            )
            add(titleLabel, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyTop(4)
                    add(branchPreviewValueLabel, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
        }
    }

    private fun refreshBranchPreview() {
        val normalizedSpec = normalizeBranchSegment(resolvedSpecTaskId)
        val shortNameText = shortNameField.text.orEmpty()
        val normalizedShortName = normalizeBranchSegmentOrBlank(shortNameText)
        val suffix = normalizedShortName.ifBlank { "..." }
        branchPreviewValueLabel.text = "spec/$normalizedSpec-$suffix"
    }

    private fun normalizeBranchSegment(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "worktree" }
    }

    private fun normalizeBranchSegmentOrBlank(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
    }

    private fun styleDialogButtons() {
        getButton(okAction)?.let { button ->
            styleDialogButton(button, primary = true)
        }
        getButton(cancelAction)?.let { button ->
            styleDialogButton(button, primary = false)
        }
    }

    private fun styleDialogButton(button: JButton, primary: Boolean) {
        val bg = if (primary) BUTTON_PRIMARY_BG else BUTTON_BG
        val border = if (primary) BUTTON_PRIMARY_BORDER else BUTTON_BORDER
        val fg = if (primary) BUTTON_PRIMARY_FG else BUTTON_FG

        button.isFocusPainted = false
        button.isFocusable = false
        button.background = bg
        button.foreground = fg
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(border, JBUI.scale(10)),
            JBUI.Borders.empty(4, 14, 4, 14),
        )
        SpecUiStyle.applyRoundRect(button, arc = 10)
        button.preferredSize = Dimension(JBUI.scale(92), JBUI.scale(30))
    }

    private fun readInput(): NewWorktreeInput {
        return NewWorktreeInput(
            specTaskId = resolvedSpecTaskId,
            shortName = shortNameField.text.orEmpty(),
            baseBranch = baseBranchField.text.orEmpty(),
        )
    }

    companion object {
        internal const val DEFAULT_BASE_BRANCH: String = "main"
        private val DIALOG_BG = JBColor(Color(247, 250, 255), Color(53, 58, 66))
        private val SUBTITLE_FG = JBColor(Color(90, 103, 124), Color(171, 181, 196))
        private val LABEL_FG = JBColor(Color(58, 78, 109), Color(204, 214, 232))
        private val INPUT_BG = JBColor(Color(245, 249, 255), Color(63, 69, 80))
        private val INPUT_BORDER = JBColor(Color(184, 200, 226), Color(103, 115, 133))
        private val INPUT_FG = JBColor(Color(44, 67, 101), Color(217, 227, 242))
        private val PREVIEW_BG = JBColor(Color(240, 247, 255), Color(60, 68, 79))
        private val PREVIEW_BORDER = JBColor(Color(192, 209, 233), Color(96, 109, 128))
        private val PREVIEW_VALUE_FG = JBColor(Color(46, 70, 106), Color(212, 223, 241))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val BUTTON_PRIMARY_BG = JBColor(Color(213, 228, 250), Color(77, 98, 128))
        private val BUTTON_PRIMARY_BORDER = JBColor(Color(154, 180, 219), Color(116, 137, 169))
        private val BUTTON_PRIMARY_FG = JBColor(Color(37, 57, 89), Color(223, 232, 246))

        internal fun resolveSpecTaskId(specTaskId: String?): String {
            return specTaskId?.trim()?.ifBlank { null } ?: UUID.randomUUID().toString()
        }

        internal fun validateInput(input: NewWorktreeInput): NewWorktreeValidationError? {
            if (input.shortName.isBlank()) {
                return NewWorktreeValidationError.SHORT_NAME_REQUIRED
            }
            if (input.baseBranch.isBlank()) {
                return NewWorktreeValidationError.BASE_BRANCH_REQUIRED
            }
            return null
        }

        internal fun normalizeInput(input: NewWorktreeInput): NewWorktreeInput {
            return NewWorktreeInput(
                specTaskId = input.specTaskId.trim(),
                shortName = input.shortName.trim(),
                baseBranch = input.baseBranch.trim(),
            )
        }
    }
}

internal data class NewWorktreeInput(
    val specTaskId: String,
    val shortName: String,
    val baseBranch: String,
)

internal enum class NewWorktreeValidationError {
    SHORT_NAME_REQUIRED,
    BASE_BRANCH_REQUIRED,
}
