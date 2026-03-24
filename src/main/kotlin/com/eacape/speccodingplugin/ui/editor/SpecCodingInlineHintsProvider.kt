package com.eacape.speccodingplugin.ui.editor

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import javax.swing.JComponent
import javax.swing.JPanel

class SpecCodingInlineHintsProvider : InlayHintsProvider<NoSettings>, DumbAware {

    override val key: SettingsKey<NoSettings> = SettingsKey("speccoding.inline.hints")
    override val name: String = SpecCodingBundle.message("editor.inline.provider.name")
    override val previewText: String? = null

    override fun createSettings(): NoSettings = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent {
                return JPanel()
            }
        }
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink,
    ): InlayHintsCollector? {
        val filePath = file.virtualFile?.path ?: return null
        val hintText = EditorInsightPresentation.inlineHint(
            EditorInsightResolver.forProject(file.project).resolve(filePath)
        ) ?: return null
        val anchorElement = firstMeaningfulElement(file) ?: return null

        return object : FactoryInlayHintsCollector(editor) {
            private var rendered = false

            override fun collect(
                element: PsiElement,
                editor: Editor,
                sink: InlayHintsSink,
            ): Boolean {
                if (rendered || element != anchorElement) {
                    return true
                }
                val presentation = factory.smallText(hintText)
                sink.addInlineElement(element.textRange.startOffset, false, presentation, false)
                rendered = true
                return true
            }
        }
    }

    private fun firstMeaningfulElement(file: PsiFile): PsiElement? {
        var cursor: PsiElement? = file.firstChild
        while (cursor != null) {
            if (cursor !is PsiWhiteSpace && cursor !is PsiComment && cursor.textLength > 0) {
                return cursor
            }
            cursor = cursor.nextSibling
        }
        return file.firstChild
    }
}
