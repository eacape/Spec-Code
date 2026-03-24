package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

class CodeGraphDialog(
    summary: String,
    mermaid: String,
) : DialogWrapper(true) {

    private val summaryArea = JBTextArea(summary)
    private val mermaidArea = JBTextArea(mermaid)

    init {
        title = SpecCodingBundle.message("code.graph.dialog.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tabs = JBTabbedPane()
        summaryArea.isEditable = false
        summaryArea.border = JBUI.Borders.empty(8)
        summaryArea.lineWrap = true
        summaryArea.wrapStyleWord = true
        tabs.addTab(
            SpecCodingBundle.message("code.graph.dialog.tab.summary"),
            JBScrollPane(summaryArea),
        )

        mermaidArea.isEditable = false
        mermaidArea.border = JBUI.Borders.empty(8)
        mermaidArea.lineWrap = false
        tabs.addTab(
            SpecCodingBundle.message("code.graph.dialog.tab.mermaid"),
            JBScrollPane(mermaidArea),
        )

        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(520))
            add(tabs, BorderLayout.CENTER)
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)
}
