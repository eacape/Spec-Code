package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JButton
import javax.swing.JPanel

class SpecWorkflowWorkspaceChromeUiHostTest {

    @Test
    fun `showWorkflowListOnlyMode should reparent list section and allow empty card chrome`() {
        val fixture = Fixture()

        fixture.host.showWorkspaceContent()
        fixture.host.showWorkflowListOnlyMode()
        fixture.host.setBackToListEnabled(false)
        fixture.host.showWorkspaceEmptyCard()

        assertFalse(fixture.isWorkspaceMode)
        assertSame(fixture.listSectionContainer, fixture.centerContentPanel.getComponent(0))
        assertFalse(fixture.backToListButton.isEnabled)
        assertTrue(fixture.emptyCard.isVisible)
        assertFalse(fixture.contentCard.isVisible)
    }

    @Test
    fun `showWorkspaceContent should reparent workspace section enable back button and show content card`() {
        val fixture = Fixture()

        fixture.host.showWorkflowListOnlyMode()
        fixture.host.setBackToListEnabled(false)
        fixture.host.showWorkspaceEmptyCard()
        fixture.host.showWorkspaceContent()

        assertTrue(fixture.isWorkspaceMode)
        assertSame(fixture.workspacePanelContainer, fixture.centerContentPanel.getComponent(0))
        assertTrue(fixture.backToListButton.isEnabled)
        assertFalse(fixture.emptyCard.isVisible)
        assertTrue(fixture.contentCard.isVisible)
    }

    private class Fixture {
        var isWorkspaceMode: Boolean = false
        val centerContentPanel = JPanel(BorderLayout())
        val listSectionContainer = JPanel()
        val workspacePanelContainer = JPanel(BorderLayout())
        val workspaceCardLayout = CardLayout()
        val workspaceCardPanel = JPanel(workspaceCardLayout)
        val emptyCard = JPanel()
        val contentCard = JPanel()
        val backToListButton = JButton()
        val host = SpecWorkflowWorkspaceChromeUiHost(
            centerContentPanel = centerContentPanel,
            listSectionContainer = listSectionContainer,
            workspacePanelContainer = workspacePanelContainer,
            workspaceCardLayout = workspaceCardLayout,
            workspaceCardPanel = workspaceCardPanel,
            backToListButton = backToListButton,
            setWorkspaceMode = { isWorkspaceMode = it },
            emptyCardId = "empty",
            contentCardId = "content",
        )

        init {
            workspaceCardPanel.add(emptyCard, "empty")
            workspaceCardPanel.add(contentCard, "content")
            centerContentPanel.add(listSectionContainer, BorderLayout.CENTER)
        }
    }
}
