package com.eacape.speccodingplugin.ui.spec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

class SpecWorkflowWorkspaceCardBuilderTest {

    @Test
    fun `build should assemble summary cards sections and documents content into workspace panel`() {
        val fixture = Fixture()

        val chrome = fixture.build()

        assertEquals(2, fixture.workspaceCardPanel.componentCount)
        assertTrue(SwingUtilities.isDescendingFrom(chrome.summaryUi.titleLabel, fixture.workspaceCardPanel))
        assertTrue(SwingUtilities.isDescendingFrom(chrome.summaryUi.stageMetric.root, fixture.workspaceCardPanel))
        assertTrue(SwingUtilities.isDescendingFrom(fixture.backToListButton, fixture.workspaceCardPanel))
        assertTrue(SwingUtilities.isDescendingFrom(fixture.documentsContent, fixture.workspaceCardPanel))
        assertFalse(chrome.summaryUi.stageMetric.root.isVisible)
        assertEquals(
            SpecWorkflowWorkspaceSectionId.entries,
            fixture.sectionItems.keys.toList(),
        )
        assertTrue(
            SwingUtilities.isDescendingFrom(
                chrome.sections.overview,
                fixture.sectionItems.getValue(SpecWorkflowWorkspaceSectionId.OVERVIEW),
            ),
        )
        assertTrue(
            SwingUtilities.isDescendingFrom(
                chrome.sections.documents,
                fixture.sectionItems.getValue(SpecWorkflowWorkspaceSectionId.DOCUMENTS),
            ),
        )
    }

    @Test
    fun `build should route section expansion changes through callback`() {
        val fixture = Fixture()
        val chrome = fixture.build()

        chrome.sections.tasks.setExpanded(false)
        chrome.sections.documents.setExpanded(false)

        assertEquals(
            listOf(
                SpecWorkflowWorkspaceSectionId.TASKS to false,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS to false,
            ),
            fixture.expansionEvents,
        )
    }

    private class Fixture {
        val workspaceCardPanel = JPanel(java.awt.CardLayout())
        val backToListButton = JButton("Back")
        val overviewContent = JPanel()
        val tasksContent = JPanel()
        val gateContent = JPanel()
        val verifyContent = JPanel()
        val documentsContent = JPanel()
        val sectionItems = linkedMapOf<SpecWorkflowWorkspaceSectionId, JPanel>()
        val expansionEvents = mutableListOf<Pair<SpecWorkflowWorkspaceSectionId, Boolean>>()

        fun build(): SpecWorkflowWorkspaceCardChrome {
            return SpecWorkflowWorkspaceCardBuilder(
                workspaceCardPanel = workspaceCardPanel,
                backToListButton = backToListButton,
                overviewContent = overviewContent,
                tasksContent = tasksContent,
                gateContent = gateContent,
                verifyContent = verifyContent,
                documentsContent = documentsContent,
                sectionItems = sectionItems,
                onSectionExpandedChanged = { id, expanded ->
                    expansionEvents += id to expanded
                },
                emptyCardId = "empty",
                contentCardId = "content",
            ).build()
        }
    }
}
