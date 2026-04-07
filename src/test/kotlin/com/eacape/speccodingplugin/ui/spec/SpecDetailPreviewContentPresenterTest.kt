package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane

class SpecDetailPreviewContentPresenterTest {

    @Test
    fun `apply should render markdown content and show validation message`() {
        val pane = JTextPane()
        val label = JLabel()
        val banner = JPanel().apply {
            isVisible = false
        }
        val previewPanePresenter = createPreviewPanePresenter(pane)
        val validationBannerPresenter = SpecDetailValidationBannerPresenter(
            label = label,
            bannerPanel = { banner },
            mutedForeground = Color.GRAY,
            successForeground = Color(0, 128, 0),
            errorForeground = Color(255, 0, 0),
        )
        val presenter = SpecDetailPreviewContentPresenter(
            previewPanePresenter = previewPanePresenter,
            validationBannerPresenter = validationBannerPresenter,
            onKeepGeneratingLabel = {},
        )

        presenter.apply(
            SpecDetailPreviewContentPlan(
                markdownContent = "# Tasks",
                interactivePhase = null,
                keepGeneratingLabel = false,
                validationMessage = SpecDetailPreviewValidationPlan(
                    text = "Read only preview",
                    tone = SpecDetailPreviewValidationTone.MUTED,
                ),
            ),
        )

        assertEquals("rendered:# Tasks", pane.text)
        assertEquals("# Tasks", previewPanePresenter.currentSourceText())
        assertEquals("Read only preview", label.text)
        assertEquals(Color.GRAY, label.foreground)
        assertTrue(banner.isVisible)
    }

    @Test
    fun `apply should keep generating label path and skip validation banner update`() {
        val pane = JTextPane()
        val label = JLabel("stale")
        val banner = JPanel().apply {
            isVisible = false
        }
        var generatingCalls = 0
        val presenter = SpecDetailPreviewContentPresenter(
            previewPanePresenter = createPreviewPanePresenter(pane),
            validationBannerPresenter = SpecDetailValidationBannerPresenter(
                label = label,
                bannerPanel = { banner },
                mutedForeground = Color.GRAY,
                successForeground = Color(0, 128, 0),
                errorForeground = Color(255, 0, 0),
            ),
            onKeepGeneratingLabel = { generatingCalls += 1 },
        )

        presenter.apply(
            SpecDetailPreviewContentPlan(
                markdownContent = "## Generating",
                keepGeneratingLabel = true,
                validationMessage = SpecDetailPreviewValidationPlan(
                    text = "should be ignored",
                    tone = SpecDetailPreviewValidationTone.ERROR,
                ),
            ),
        )

        assertEquals("rendered:## Generating", pane.text)
        assertEquals(1, generatingCalls)
        assertEquals("stale", label.text)
        assertFalse(banner.isVisible)
    }

    private fun createPreviewPanePresenter(pane: JTextPane): SpecDetailPreviewPanePresenter {
        return SpecDetailPreviewPanePresenter(
            pane = pane,
            isEditing = { false },
            hasClarificationState = { false },
            currentWorkflow = { workflow("wf-preview-content") },
            onSaveDocument = { _, _, _ -> },
            onWorkflowUpdated = {},
            onRefreshButtonStates = {},
            renderMarkdown = { target, content -> target.text = "rendered:$content" },
        )
    }

    private fun workflow(id: String): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Preview Content Presenter",
            description = "preview content presenter test",
            createdAt = 1L,
            updatedAt = 2L,
        )
    }
}
