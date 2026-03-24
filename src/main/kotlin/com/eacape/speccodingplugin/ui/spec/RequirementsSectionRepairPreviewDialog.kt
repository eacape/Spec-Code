package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.RequirementsSectionRepairPreview
import com.eacape.speccodingplugin.spec.RequirementsSectionSupport
import com.eacape.speccodingplugin.ui.chat.MarkdownRenderer
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane

internal class RequirementsSectionRepairPreviewDialog(
    private val project: Project,
    private val preview: RequirementsSectionRepairPreview,
) : DialogWrapper(project, true) {

    private val previewPane = JTextPane().apply {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(2, 2, 2, 2)
    }

    private val diffAction = object : DialogWrapperAction(
        SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.preview.diff"),
    ) {
        override fun doAction(e: ActionEvent?) {
            showDiff()
        }
    }

    init {
        title = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.preview.title")
        setOKButtonText(SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.preview.apply"))
        init()
        renderPreview()
    }

    override fun createCenterPanel(): JComponent {
        val sectionsLabel = JBLabel(
            SpecCodingBundle.message(
                "spec.toolwindow.gate.quickFix.aiFill.preview.sections",
                RequirementsSectionSupport.describeSections(preview.patches.map { patch -> patch.sectionId }),
            ),
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor(Color(86, 96, 110), Color(175, 182, 190))
            border = JBUI.Borders.emptyBottom(4)
        }
        val hintLabel = JBLabel(
            SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.preview.hint"),
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor(Color(86, 96, 110), Color(175, 182, 190))
            border = JBUI.Borders.emptyBottom(8)
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(10)
            add(
                JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                    isOpaque = false
                    add(sectionsLabel, BorderLayout.NORTH)
                    add(hintLabel, BorderLayout.CENTER)
                },
                BorderLayout.NORTH,
            )
            add(
                JBScrollPane(previewPane).apply {
                    preferredSize = JBUI.size(720, 420)
                    minimumSize = JBUI.size(640, 320)
                },
                BorderLayout.CENTER,
            )
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(diffAction, okAction, cancelAction)
    }

    private fun renderPreview() {
        val markdown = preview.patches.joinToString(separator = "\n\n") { patch -> patch.renderedBlock }
            .ifBlank { SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.preview.empty") }
        runCatching {
            MarkdownRenderer.render(previewPane, markdown)
            previewPane.caretPosition = 0
        }.onFailure {
            previewPane.text = markdown
            previewPane.caretPosition = 0
        }
    }

    private fun showDiff() {
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.diff.title"),
            factory.create(preview.originalContent),
            factory.create(preview.updatedContent),
            SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.diff.original"),
            SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.diff.updated"),
        )
        DiffManager.getInstance().showDiff(project, request)
    }
}
