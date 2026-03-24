package com.eacape.speccodingplugin.ui.editor

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import javax.swing.Icon

class SpecCodingLineMarkerProvider : RelatedItemLineMarkerProvider(), DumbAware {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        val file = element.containingFile ?: return
        if (element.parent != file) {
            return
        }
        if (element != firstMeaningfulElement(file)) {
            return
        }
        val filePath = file.virtualFile?.path ?: return
        val insight = EditorInsightResolver.forProject(element.project).resolve(filePath)
        if (!insight.hasContent) {
            return
        }

        insight.aiChange?.let { aiChange ->
            result.add(
                marker(
                    element = element,
                    icon = SpecCodingEditorIcons.AI_CHANGE,
                    tooltip = EditorInsightPresentation.gutterAiTooltip(aiChange),
                )
            )
        }

        insight.specAssociation?.let { specAssociation ->
            result.add(
                marker(
                    element = element,
                    icon = SpecCodingEditorIcons.SPEC_LINK,
                    tooltip = EditorInsightPresentation.gutterSpecTooltip(specAssociation),
                )
            )
        }
    }

    private fun marker(
        element: PsiElement,
        icon: Icon,
        tooltip: String,
    ): RelatedItemLineMarkerInfo<PsiElement> {
        return NavigationGutterIconBuilder
            .create(icon)
            .setTargets(listOf(element))
            .setTooltipText(tooltip)
            .createLineMarkerInfo(element)
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
