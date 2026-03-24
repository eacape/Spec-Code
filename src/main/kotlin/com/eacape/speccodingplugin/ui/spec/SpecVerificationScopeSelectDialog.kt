package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StructuredTask
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel

class SpecVerificationScopeSelectDialog(
    tasks: List<StructuredTask>,
) : DialogWrapper(true) {
    private val taskList = JBList(tasks)

    var selectedTaskIds: List<String> = emptyList()
        private set

    init {
        title = SpecCodingBundle.message("spec.action.editor.fixVerification.selectScope.title")
        taskList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        taskList.cellRenderer = SimpleListCellRenderer.create { label, value, _ ->
            if (value == null) {
                label.text = ""
            } else {
                label.text = "${value.id} ${value.title}"
            }
        }
        init()
        if (tasks.isNotEmpty()) {
            taskList.setSelectionInterval(0, tasks.lastIndex)
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(360))
        panel.border = JBUI.Borders.empty(8)

        val hintLabel = JBLabel(SpecCodingBundle.message("spec.action.editor.fixVerification.selectScope.hint"))
        panel.add(hintLabel, BorderLayout.NORTH)

        val scrollPane = JScrollPane(taskList)
        scrollPane.border = JBUI.Borders.emptyTop(8)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun doOKAction() {
        selectedTaskIds = taskList
            .selectedValuesList
            .map(StructuredTask::id)
            .sorted()
        super.doOKAction()
    }
}

