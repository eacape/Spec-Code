package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

class EditSpecWorkflowDialog(
    initialTitle: String,
    initialDescription: String,
    private val dialogTitle: String = SpecCodingBundle.message("spec.dialog.editWorkflow.title"),
) : DialogWrapper(true) {

    private val titleField = JBTextField(initialTitle)
    private val descriptionArea = JBTextArea(initialDescription, 4, 40)

    var resultTitle: String? = null
        private set
    var resultDescription: String? = null
        private set

    init {
        init()
        title = dialogTitle
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8)

        val titleLabel = JBLabel(SpecCodingBundle.message("spec.dialog.field.title"))
        titleLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(titleLabel)
        panel.add(javax.swing.Box.createVerticalStrut(4))

        titleField.alignmentX = JComponent.LEFT_ALIGNMENT
        titleField.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
        panel.add(titleField)
        panel.add(javax.swing.Box.createVerticalStrut(12))

        val descLabel = JBLabel(SpecCodingBundle.message("spec.dialog.field.description"))
        descLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(descLabel)
        panel.add(javax.swing.Box.createVerticalStrut(4))

        descriptionArea.lineWrap = true
        descriptionArea.wrapStyleWord = true
        val scrollPane = JScrollPane(descriptionArea)
        scrollPane.alignmentX = JComponent.LEFT_ALIGNMENT
        scrollPane.preferredSize = Dimension(400, 100)
        panel.add(scrollPane)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (titleField.text.isNullOrBlank()) {
            return ValidationInfo(SpecCodingBundle.message("spec.dialog.validation.titleRequired"), titleField)
        }
        return null
    }

    override fun doOKAction() {
        resultTitle = titleField.text.trim()
        resultDescription = descriptionArea.text.trim()
        super.doOKAction()
    }

    override fun getPreferredFocusedComponent() = titleField
}
