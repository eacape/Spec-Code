package com.eacape.speccodingplugin.ui.spec

import com.intellij.ui.JBColor
import java.awt.Color
import javax.swing.JLabel
import javax.swing.JPanel

internal class SpecDetailValidationBannerPresenter(
    private val label: JLabel,
    private val bannerPanel: () -> JPanel?,
    private val mutedForeground: Color = JBColor.GRAY,
    private val infoForeground: Color = mutedForeground,
    private val successForeground: Color = JBColor(
        Color(76, 175, 80),
        Color(76, 175, 80),
    ),
    private val errorForeground: Color = JBColor(
        Color(244, 67, 54),
        Color(239, 83, 80),
    ),
    private val generatingForeground: Color = infoForeground,
) {

    fun show(text: String?, foreground: Color = mutedForeground) {
        val message = text.orEmpty()
        label.text = message
        label.foreground = foreground
        bannerPanel()?.let { banner ->
            banner.isVisible = message.isNotBlank()
            banner.parent?.revalidate()
            banner.parent?.repaint()
        }
    }

    fun clear() {
        show("", mutedForeground)
    }

    fun applyPreviewValidation(plan: SpecDetailPreviewValidationPlan?) {
        if (plan == null) {
            clear()
            return
        }
        show(plan.text, foregroundFor(plan.tone))
    }

    fun applyStatus(plan: SpecDetailPreviewStatusPlan?) {
        if (plan == null) {
            clear()
            return
        }
        show(plan.text, foregroundFor(plan.tone))
    }

    private fun foregroundFor(tone: SpecDetailPreviewValidationTone): Color {
        return when (tone) {
            SpecDetailPreviewValidationTone.MUTED -> mutedForeground
            SpecDetailPreviewValidationTone.SUCCESS -> successForeground
            SpecDetailPreviewValidationTone.ERROR -> errorForeground
        }
    }

    private fun foregroundFor(tone: SpecDetailPreviewStatusTone): Color {
        return when (tone) {
            SpecDetailPreviewStatusTone.MUTED -> mutedForeground
            SpecDetailPreviewStatusTone.INFO -> infoForeground
            SpecDetailPreviewStatusTone.GENERATING -> generatingForeground
        }
    }
}
