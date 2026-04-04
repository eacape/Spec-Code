package com.eacape.speccodingplugin.ui.spec

import java.awt.Color
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

internal data class SpecDetailClarificationPreviewDocumentPalette(
    val bodyForeground: Color,
    val titleForeground: Color,
    val mutedForeground: Color,
    val questionCodeBackground: Color,
    val questionCodeForeground: Color,
    val detailChipBackground: Color,
    val detailChipForeground: Color,
    val baseFontFamily: String,
    val baseFontSize: Int,
    val codeFontFamily: String = "JetBrains Mono",
)

internal object SpecDetailClarificationPreviewDocumentWriter {

    fun write(
        doc: StyledDocument,
        plan: SpecDetailClarificationPreviewRenderPlan,
        palette: SpecDetailClarificationPreviewDocumentPalette,
    ) {
        doc.remove(0, doc.length)
        val newlineAttrs = SimpleAttributeSet()
        val attrsByStyle = attributesByStyle(palette)

        plan.operations.forEach { operation ->
            when (operation) {
                SpecDetailClarificationPreviewRenderOperation.Newline ->
                    doc.insertString(doc.length, "\n", newlineAttrs)

                is SpecDetailClarificationPreviewRenderOperation.Text -> {
                    if (operation.text.isNotEmpty()) {
                        doc.insertString(
                            doc.length,
                            operation.text,
                            attrsByStyle.getValue(operation.style),
                        )
                    }
                }
            }
        }
    }

    private fun attributesByStyle(
        palette: SpecDetailClarificationPreviewDocumentPalette,
    ): Map<SpecDetailClarificationPreviewRenderTextStyle, SimpleAttributeSet> {
        val bodyAttrs = SimpleAttributeSet().apply {
            StyleConstants.setFontFamily(this, palette.baseFontFamily)
            StyleConstants.setFontSize(this, palette.baseFontSize)
            StyleConstants.setForeground(this, palette.bodyForeground)
        }
        val titleAttrs = SimpleAttributeSet(bodyAttrs).apply {
            StyleConstants.setBold(this, true)
            StyleConstants.setForeground(this, palette.titleForeground)
        }
        val questionBoldAttrs = SimpleAttributeSet(bodyAttrs).apply {
            StyleConstants.setBold(this, true)
        }
        val questionCodeAttrs = SimpleAttributeSet(bodyAttrs).apply {
            StyleConstants.setFontFamily(this, palette.codeFontFamily)
            StyleConstants.setBackground(this, palette.questionCodeBackground)
            StyleConstants.setForeground(this, palette.questionCodeForeground)
        }
        val detailChipAttrs = SimpleAttributeSet(bodyAttrs).apply {
            StyleConstants.setBold(this, true)
            StyleConstants.setBackground(this, palette.detailChipBackground)
            StyleConstants.setForeground(this, palette.detailChipForeground)
        }
        val mutedAttrs = SimpleAttributeSet(bodyAttrs).apply {
            StyleConstants.setForeground(this, palette.mutedForeground)
        }

        return mapOf(
            SpecDetailClarificationPreviewRenderTextStyle.BODY to bodyAttrs,
            SpecDetailClarificationPreviewRenderTextStyle.TITLE to titleAttrs,
            SpecDetailClarificationPreviewRenderTextStyle.QUESTION_BOLD to questionBoldAttrs,
            SpecDetailClarificationPreviewRenderTextStyle.QUESTION_CODE to questionCodeAttrs,
            SpecDetailClarificationPreviewRenderTextStyle.DETAIL_CHIP to detailChipAttrs,
            SpecDetailClarificationPreviewRenderTextStyle.MUTED to mutedAttrs,
        )
    }
}
