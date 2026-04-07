package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import javax.swing.JLabel
import javax.swing.JPanel

class SpecDetailValidationBannerPresenterTest {

    @Test
    fun `show and clear should update label text and banner visibility`() {
        val label = JLabel("stale")
        val banner = JPanel().apply {
            isVisible = false
        }
        val muted = Color.GRAY
        val presenter = SpecDetailValidationBannerPresenter(
            label = label,
            bannerPanel = { banner },
            mutedForeground = muted,
            successForeground = Color(0, 128, 0),
            errorForeground = Color(255, 0, 0),
        )

        presenter.show("Needs attention", foreground = Color(255, 0, 0))

        assertEquals("Needs attention", label.text)
        assertEquals(Color(255, 0, 0), label.foreground)
        assertTrue(banner.isVisible)

        presenter.clear()

        assertEquals("", label.text)
        assertEquals(muted, label.foreground)
        assertFalse(banner.isVisible)
    }

    @Test
    fun `applyPreviewValidation should map tone to configured colors`() {
        val label = JLabel()
        val banner = JPanel()
        val muted = Color.GRAY
        val info = Color(64, 64, 64)
        val success = Color(0, 128, 0)
        val error = Color(255, 0, 0)
        val generating = Color(255, 140, 0)
        val presenter = SpecDetailValidationBannerPresenter(
            label = label,
            bannerPanel = { banner },
            mutedForeground = muted,
            infoForeground = info,
            successForeground = success,
            errorForeground = error,
            generatingForeground = generating,
        )

        presenter.applyPreviewValidation(
            SpecDetailPreviewValidationPlan(
                text = "Looks good",
                tone = SpecDetailPreviewValidationTone.SUCCESS,
            ),
        )
        assertEquals("Looks good", label.text)
        assertEquals(success, label.foreground)
        assertTrue(banner.isVisible)

        presenter.applyPreviewValidation(
            SpecDetailPreviewValidationPlan(
                text = "Read only",
                tone = SpecDetailPreviewValidationTone.MUTED,
            ),
        )
        assertEquals("Read only", label.text)
        assertEquals(muted, label.foreground)

        presenter.applyPreviewValidation(
            SpecDetailPreviewValidationPlan(
                text = "Broken",
                tone = SpecDetailPreviewValidationTone.ERROR,
            ),
        )
        assertEquals("Broken", label.text)
        assertEquals(error, label.foreground)
    }

    @Test
    fun `applyStatus should map preview status tone to configured colors`() {
        val label = JLabel()
        val banner = JPanel()
        val muted = Color.GRAY
        val info = Color(64, 64, 64)
        val generating = Color(255, 140, 0)
        val presenter = SpecDetailValidationBannerPresenter(
            label = label,
            bannerPanel = { banner },
            mutedForeground = muted,
            infoForeground = info,
            successForeground = Color(0, 128, 0),
            errorForeground = Color(255, 0, 0),
            generatingForeground = generating,
        )

        presenter.applyStatus(
            SpecDetailPreviewStatusPlan(
                text = "No workflow",
                tone = SpecDetailPreviewStatusTone.MUTED,
            ),
        )
        assertEquals("No workflow", label.text)
        assertEquals(muted, label.foreground)
        assertTrue(banner.isVisible)

        presenter.applyStatus(
            SpecDetailPreviewStatusPlan(
                text = "Clarify next",
                tone = SpecDetailPreviewStatusTone.INFO,
            ),
        )
        assertEquals("Clarify next", label.text)
        assertEquals(info, label.foreground)

        presenter.applyStatus(
            SpecDetailPreviewStatusPlan(
                text = "Generating...",
                tone = SpecDetailPreviewStatusTone.GENERATING,
            ),
        )
        assertEquals("Generating...", label.text)
        assertEquals(generating, label.foreground)
    }
}
