package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

class SpecBaselineSelectDialog(
    workflowOptions: List<WorkflowOption>,
    currentWorkflowId: String,
) : DialogWrapper(true) {

    data class WorkflowOption(
        val workflowId: String,
        val title: String,
        val description: String,
    ) {
        override fun toString(): String {
            return SpecCodingBundle.message("spec.delta.workflow.option", title, workflowId)
        }
    }

    private val currentWorkflowField = JBTextField(currentWorkflowId)
    private val baselineCombo = JComboBox(CollectionComboBoxModel(workflowOptions))
    private val hintArea = JBTextArea()

    var selectedBaselineWorkflowId: String? = null
        private set

    init {
        title = SpecCodingBundle.message("spec.delta.selectBaseline.title")
        currentWorkflowField.isEditable = false
        setupHintArea()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(JBUI.scale(520), JBUI.scale(220))

        val form = JPanel()
        form.layout = BoxLayout(form, BoxLayout.Y_AXIS)
        form.border = JBUI.Borders.empty(8)

        val currentLabel = JBLabel(SpecCodingBundle.message("spec.delta.currentWorkflow"))
        currentLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        form.add(currentLabel)
        form.add(javax.swing.Box.createVerticalStrut(4))

        currentWorkflowField.alignmentX = JComponent.LEFT_ALIGNMENT
        currentWorkflowField.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
        form.add(currentWorkflowField)
        form.add(javax.swing.Box.createVerticalStrut(10))

        val baselineLabel = JBLabel(SpecCodingBundle.message("spec.delta.baselineWorkflow"))
        baselineLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        form.add(baselineLabel)
        form.add(javax.swing.Box.createVerticalStrut(4))

        baselineCombo.alignmentX = JComponent.LEFT_ALIGNMENT
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = baselineCombo,
            minWidth = JBUI.scale(160),
            maxWidth = JBUI.scale(520),
            height = JBUI.scale(30),
        )
        form.add(baselineCombo)
        form.add(javax.swing.Box.createVerticalStrut(10))

        val hintScroll = JScrollPane(hintArea)
        hintScroll.alignmentX = JComponent.LEFT_ALIGNMENT
        hintScroll.preferredSize = Dimension(0, JBUI.scale(85))
        form.add(hintScroll)

        panel.add(form, BorderLayout.CENTER)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (baselineCombo.itemCount == 0) {
            return ValidationInfo(SpecCodingBundle.message("spec.delta.emptyCandidates"), baselineCombo)
        }
        if (baselineCombo.selectedItem !is WorkflowOption) {
            return ValidationInfo(SpecCodingBundle.message("spec.delta.selectBaseline.required"), baselineCombo)
        }
        return null
    }

    override fun doOKAction() {
        selectedBaselineWorkflowId = (baselineCombo.selectedItem as? WorkflowOption)?.workflowId
        super.doOKAction()
    }

    private fun setupHintArea() {
        hintArea.isEditable = false
        hintArea.isOpaque = false
        hintArea.lineWrap = true
        hintArea.wrapStyleWord = true
        hintArea.text = SpecCodingBundle.message("spec.delta.tip")
    }
}
