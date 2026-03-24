package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.TaskVerificationResult
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.time.Instant
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

internal data class SpecTaskCompletionDialogResult(
    val relatedFiles: List<String>,
    val verificationResult: TaskVerificationResult?,
)

internal object SpecTaskCompletionDialogs {
    fun showCompletionConfirmation(
        taskId: String,
        initialRelatedFiles: List<String>,
        initialVerificationResult: TaskVerificationResult?,
    ): SpecTaskCompletionDialogResult? {
        val relatedFilesDialog = ListEditDialog(
            title = SpecCodingBundle.message("spec.toolwindow.tasks.relatedFiles.confirm.title", taskId),
            hintText = SpecCodingBundle.message("spec.toolwindow.tasks.relatedFiles.confirm.hint"),
            initialLines = initialRelatedFiles,
            okText = SpecCodingBundle.message("spec.toolwindow.tasks.complete.ok"),
        )
        if (!relatedFilesDialog.showAndGet()) {
            return null
        }

        val verificationDialog = TaskVerificationResultDialog(
            taskId = taskId,
            initialResult = initialVerificationResult,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.verification.confirm.title", taskId),
        )
        if (!verificationDialog.showAndGet()) {
            return null
        }

        return SpecTaskCompletionDialogResult(
            relatedFiles = relatedFilesDialog.resultLines,
            verificationResult = verificationDialog.result,
        )
    }
}

internal class ListEditDialog(
    title: String,
    private val hintText: String,
    initialLines: List<String>,
    okText: String? = null,
) : DialogWrapper(true) {

    private val textArea = JBTextArea().apply {
        text = initialLines.joinToString("\n")
        lineWrap = false
        wrapStyleWord = false
    }

    var resultLines: List<String> = emptyList()
        private set

    init {
        this.title = title
        okText?.let(::setOKButtonText)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val hintLabel = JBLabel(hintText).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor(Color(86, 96, 110), Color(175, 182, 190))
            border = JBUI.Borders.emptyBottom(6)
        }
        val areaScroll = JBScrollPane(textArea).apply {
            preferredSize = JBUI.size(520, 260)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(10)
            add(hintLabel, BorderLayout.NORTH)
            add(areaScroll, BorderLayout.CENTER)
        }
    }

    override fun doOKAction() {
        resultLines = parseListLines(textArea.text.orEmpty())
        super.doOKAction()
    }

    private fun parseListLines(raw: String): List<String> {
        return raw
            .replace('\r', '\n')
            .split('\n', ',', ';')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }
}

internal class TaskVerificationResultDialog(
    private val taskId: String,
    initialResult: TaskVerificationResult?,
    title: String,
) : DialogWrapper(true) {
    private val conclusionCombo = JComboBox(VerificationDialogOption.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create<VerificationDialogOption> { label, value, _ ->
            label.text = value?.displayName.orEmpty()
        }
    }
    private val runIdField = JBTextField(initialResult?.runId.orEmpty())
    private val atField = JBTextField(initialResult?.at.orEmpty())
    private val summaryArea = JBTextArea().apply {
        text = initialResult?.summary.orEmpty()
        lineWrap = true
        wrapStyleWord = true
        rows = 5
    }

    var result: TaskVerificationResult? = initialResult
        private set

    init {
        this.title = title
        conclusionCombo.selectedItem = VerificationDialogOption.from(initialResult?.conclusion)
        conclusionCombo.addActionListener { syncFieldsForSelection() }
        init()
        syncFieldsForSelection()
    }

    override fun createCenterPanel(): JComponent {
        val hintLabel = JBLabel(
            SpecCodingBundle.message("spec.toolwindow.tasks.verification.dialog.hint", taskId),
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor(Color(86, 96, 110), Color(175, 182, 190))
            border = JBUI.Borders.emptyBottom(6)
        }
        val formPanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            isOpaque = false
            add(createFieldRow(SpecCodingBundle.message("spec.toolwindow.tasks.verification.conclusion"), conclusionCombo))
            add(createFieldRow(SpecCodingBundle.message("spec.toolwindow.tasks.verification.runId"), runIdField))
            add(createFieldRow(SpecCodingBundle.message("spec.toolwindow.tasks.verification.at"), atField))
            add(
                createFieldRow(
                    SpecCodingBundle.message("spec.toolwindow.tasks.verification.summary"),
                    JBScrollPane(summaryArea).apply {
                        preferredSize = JBUI.size(0, 120)
                        minimumSize = JBUI.size(0, 120)
                    },
                ),
            )
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(10)
            add(hintLabel, BorderLayout.NORTH)
            add(formPanel, BorderLayout.CENTER)
        }
    }

    override fun doValidate(): ValidationInfo? {
        val option = conclusionCombo.selectedItem as? VerificationDialogOption ?: return null
        if (option.conclusion == null) {
            return null
        }
        if (runIdField.text.isNullOrBlank()) {
            return ValidationInfo(
                SpecCodingBundle.message("spec.toolwindow.tasks.verification.validation.runId"),
                runIdField,
            )
        }
        if (atField.text.isNullOrBlank()) {
            return ValidationInfo(
                SpecCodingBundle.message("spec.toolwindow.tasks.verification.validation.at"),
                atField,
            )
        }
        if (summaryArea.text.isNullOrBlank()) {
            return ValidationInfo(
                SpecCodingBundle.message("spec.toolwindow.tasks.verification.validation.summary"),
                summaryArea,
            )
        }
        return null
    }

    override fun doOKAction() {
        val option = conclusionCombo.selectedItem as? VerificationDialogOption ?: return
        result = option.conclusion?.let { conclusion ->
            TaskVerificationResult(
                conclusion = conclusion,
                runId = runIdField.text.orEmpty().trim(),
                summary = summaryArea.text.orEmpty().trim(),
                at = atField.text.orEmpty().trim(),
            )
        }
        super.doOKAction()
    }

    private fun syncFieldsForSelection() {
        val option = conclusionCombo.selectedItem as? VerificationDialogOption ?: VerificationDialogOption.NONE
        val editable = option.conclusion != null
        if (editable && runIdField.text.isNullOrBlank()) {
            runIdField.text = defaultRunId()
        }
        if (editable && atField.text.isNullOrBlank()) {
            atField.text = Instant.now().toString()
        }
        if (editable && summaryArea.text.isNullOrBlank()) {
            summaryArea.text = SpecCodingBundle.message("spec.toolwindow.tasks.verification.summary.default", taskId)
        }
        runIdField.isEnabled = editable
        atField.isEnabled = editable
        summaryArea.isEnabled = editable
    }

    private fun defaultRunId(): String {
        val slug = taskId.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return "manual-$slug"
    }

    private fun createFieldRow(labelText: String, field: JComponent): JComponent {
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(8)
            add(JBLabel(labelText).apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor(Color(86, 96, 110), Color(175, 182, 190))
            }, BorderLayout.NORTH)
            add(field, BorderLayout.CENTER)
        }
    }

    private enum class VerificationDialogOption(
        val displayName: String,
        val conclusion: VerificationConclusion?,
    ) {
        NONE(SpecCodingBundle.message("spec.toolwindow.tasks.verification.option.none"), null),
        PASS(SpecCodingBundle.message("spec.toolwindow.tasks.verification.option.pass"), VerificationConclusion.PASS),
        WARN(SpecCodingBundle.message("spec.toolwindow.tasks.verification.option.warn"), VerificationConclusion.WARN),
        FAIL(SpecCodingBundle.message("spec.toolwindow.tasks.verification.option.fail"), VerificationConclusion.FAIL);

        companion object {
            fun from(conclusion: VerificationConclusion?): VerificationDialogOption {
                return entries.firstOrNull { option -> option.conclusion == conclusion } ?: NONE
            }
        }
    }
}
