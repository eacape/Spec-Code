package com.eacape.speccodingplugin.ui.spec

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.CardLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel

class SpecWorkflowDocumentWorkspaceViewUiHostTest {

    @Test
    fun `refreshDocumentWorkspaceViewButtons should style selected and unavailable buttons`() {
        val fixture = Fixture()
        fixture.presentation = SpecWorkflowDocumentWorkspaceViewPresentation(
            supportsStructuredTasksView = false,
            effectiveView = DocumentWorkspaceView.DOCUMENT,
            shouldSyncStructuredTaskSelection = false,
        )

        fixture.host.refreshDocumentWorkspaceViewButtons()

        assertTrue(fixture.documentButton.isEnabled)
        assertFalse(fixture.structuredTasksButton.isEnabled)
        assertEquals(Font.BOLD, fixture.documentButton.font.style)
        assertEquals(Font.PLAIN, fixture.structuredTasksButton.font.style)
        assertEquals(JBUI.scale(22), fixture.documentButton.preferredSize.height)
        assertEquals(JBUI.scale(22), fixture.structuredTasksButton.preferredSize.height)
        assertEquals(fixture.documentButton.preferredSize.width, fixture.structuredTasksButton.preferredSize.width)
    }

    @Test
    fun `set tabs visibility and show card should update document workspace chrome`() {
        val fixture = Fixture()

        fixture.host.setDocumentWorkspaceTabsVisible(false)
        fixture.host.showDocumentWorkspaceCard(DocumentWorkspaceView.STRUCTURED_TASKS)

        assertFalse(fixture.tabsPanel.isVisible)
        assertFalse(fixture.documentCard.isVisible)
        assertTrue(fixture.structuredTasksCard.isVisible)
    }

    @Test
    fun `refreshLocalizedTexts should update label button texts and tooltips`() {
        val fixture = Fixture(
            messages = mapOf(
                "spec.toolwindow.documents.view.label" to "View",
                "spec.toolwindow.documents.view.document" to "Doc",
                "spec.toolwindow.documents.view.document.tooltip" to "Open doc",
                "spec.toolwindow.documents.view.structuredTasks" to "Structured tasks",
                "spec.toolwindow.documents.view.structuredTasks.tooltip" to "Open structured tasks",
            ),
        )

        fixture.host.refreshLocalizedTexts()

        assertEquals("View", fixture.viewLabel.text)
        assertEquals("Doc", fixture.documentButton.text)
        assertEquals("Open doc", fixture.documentButton.toolTipText)
        assertEquals("Structured tasks", fixture.structuredTasksButton.text)
        assertEquals("Open structured tasks", fixture.structuredTasksButton.toolTipText)
        assertEquals(fixture.documentButton.preferredSize.width, fixture.structuredTasksButton.preferredSize.width)
    }

    @Test
    fun `syncStructuredTaskSelection and refreshContainers should delegate to ui callbacks`() {
        val fixture = Fixture()

        fixture.host.syncStructuredTaskSelection("task-42")
        fixture.host.refreshDocumentWorkspaceContainers()

        assertEquals(listOf("task-42"), fixture.syncedTaskIds)
        assertTrue(fixture.tabsPanel.revalidateCalls > 0)
        assertTrue(fixture.tabsPanel.repaintCalls > 0)
        assertTrue(fixture.cardPanel.revalidateCalls > 0)
        assertTrue(fixture.cardPanel.repaintCalls > 0)
    }

    private class Fixture(
        private val messages: Map<String, String> = defaultMessages(),
    ) {
        var presentation = SpecWorkflowDocumentWorkspaceViewPresentation(
            supportsStructuredTasksView = true,
            effectiveView = DocumentWorkspaceView.DOCUMENT,
            shouldSyncStructuredTaskSelection = false,
        )
        val tabsPanel = TrackingPanel()
        val viewLabel = JBLabel()
        val cardLayout = CardLayout()
        val cardPanel = TrackingPanel(cardLayout)
        val documentCard = JPanel()
        val structuredTasksCard = JPanel()
        val documentButton = createButton(DocumentWorkspaceView.DOCUMENT)
        val structuredTasksButton = createButton(DocumentWorkspaceView.STRUCTURED_TASKS)
        val syncedTaskIds = mutableListOf<String?>()
        val host = SpecWorkflowDocumentWorkspaceViewUiHost(
            tabsPanel = tabsPanel,
            viewLabel = viewLabel,
            cardLayout = cardLayout,
            cardPanel = cardPanel,
            buttons = linkedMapOf(
                DocumentWorkspaceView.DOCUMENT to documentButton,
                DocumentWorkspaceView.STRUCTURED_TASKS to structuredTasksButton,
            ),
            resolvePresentation = { presentation },
            message = { key -> messages.getValue(key) },
            documentCardId = "document",
            structuredTasksCardId = "structuredTasks",
            syncStructuredTaskSelectionUi = { taskId -> syncedTaskIds += taskId },
        )

        init {
            cardPanel.add(documentCard, "document")
            cardPanel.add(structuredTasksCard, "structuredTasks")
            cardLayout.show(cardPanel, "document")
        }

        private fun createButton(view: DocumentWorkspaceView): JButton {
            return JButton().apply {
                putClientProperty("documentWorkspaceView", view)
                text = view.name
            }
        }
    }

    private class TrackingPanel(
        layoutManager: java.awt.LayoutManager? = null,
    ) : JPanel(layoutManager) {
        var revalidateCalls: Int = 0
        var repaintCalls: Int = 0

        override fun revalidate() {
            revalidateCalls += 1
            super.revalidate()
        }

        override fun repaint() {
            repaintCalls += 1
            super.repaint()
        }
    }

    private companion object {
        fun defaultMessages(): Map<String, String> {
            return mapOf(
                "spec.toolwindow.documents.view.label" to "View",
                "spec.toolwindow.documents.view.document" to "Document",
                "spec.toolwindow.documents.view.document.tooltip" to "Show the document view.",
                "spec.toolwindow.documents.view.structuredTasks" to "Structured Tasks",
                "spec.toolwindow.documents.view.structuredTasks.tooltip" to "Show the structured tasks view.",
            )
        }
    }
}
