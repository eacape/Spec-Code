package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.JPanel

class SpecWorkflowDocumentWorkspaceViewChromeBuilderTest {

    @Test
    fun `build should assemble document workspace chrome with tabs cards and ordered buttons`() {
        val fixture = Fixture()

        val chrome = fixture.build()

        assertEquals("View", chrome.label.text)
        assertSame(chrome.container, chrome.tabsPanel.parent)
        assertSame(chrome.container, chrome.cardPanel.parent)
        assertEquals(
            listOf(DocumentWorkspaceView.DOCUMENT, DocumentWorkspaceView.STRUCTURED_TASKS),
            chrome.buttons.keys.toList(),
        )
        assertEquals("Document", chrome.buttons.getValue(DocumentWorkspaceView.DOCUMENT).text)
        assertEquals("Structured Tasks", chrome.buttons.getValue(DocumentWorkspaceView.STRUCTURED_TASKS).text)
        assertEquals(2, fixture.cursorTrackedButtons.size)
        assertSame(chrome.cardPanel, fixture.documentContent.parent)
        assertSame(chrome.cardPanel, fixture.structuredTasksContent.parent)
    }

    @Test
    fun `build should wire view selection callback and card switching through host`() {
        val fixture = Fixture()
        val chrome = fixture.build()

        chrome.buttons.getValue(DocumentWorkspaceView.STRUCTURED_TASKS).doClick()
        chrome.uiHost.showDocumentWorkspaceCard(DocumentWorkspaceView.STRUCTURED_TASKS)

        assertEquals(listOf(DocumentWorkspaceView.STRUCTURED_TASKS), fixture.selectedViews)
        assertTrue(fixture.structuredTasksContent.isVisible)
    }

    private class Fixture {
        val documentContent = JPanel()
        val structuredTasksContent = JPanel()
        val cursorTrackedButtons = mutableListOf<JButton>()
        val selectedViews = mutableListOf<DocumentWorkspaceView>()

        fun build(): SpecWorkflowDocumentWorkspaceViewChrome {
            return SpecWorkflowDocumentWorkspaceViewChromeBuilder(
                documentContent = documentContent,
                structuredTasksContent = structuredTasksContent,
                installToolbarButtonCursorTracking = { button -> cursorTrackedButtons += button },
                onViewSelected = { view -> selectedViews += view },
                resolvePresentation = {
                    SpecWorkflowDocumentWorkspaceViewPresentation(
                        supportsStructuredTasksView = true,
                        effectiveView = DocumentWorkspaceView.DOCUMENT,
                        shouldSyncStructuredTaskSelection = false,
                    )
                },
                message = { key -> messages.getValue(key) },
                syncStructuredTaskSelectionUi = {},
            ).build()
        }
    }

    private companion object {
        val messages = mapOf(
            "spec.toolwindow.documents.view.label" to "View",
            "spec.toolwindow.documents.view.document" to "Document",
            "spec.toolwindow.documents.view.document.tooltip" to "Show the document view.",
            "spec.toolwindow.documents.view.structuredTasks" to "Structured Tasks",
            "spec.toolwindow.documents.view.structuredTasks.tooltip" to "Show the structured tasks view.",
        )
    }
}
