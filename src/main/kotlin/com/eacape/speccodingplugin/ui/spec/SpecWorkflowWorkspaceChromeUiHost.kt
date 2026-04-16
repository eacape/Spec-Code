package com.eacape.speccodingplugin.ui.spec

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JPanel
import java.awt.CardLayout

internal class SpecWorkflowWorkspaceChromeUiHost(
    private val centerContentPanel: JPanel,
    private val listSectionContainer: Component,
    private val workspacePanelContainer: Component,
    private val workspaceCardLayout: CardLayout,
    private val workspaceCardPanel: JPanel,
    private val backToListButton: JButton,
    private val setWorkspaceMode: (Boolean) -> Unit,
    private val emptyCardId: String,
    private val contentCardId: String,
) {

    fun showWorkflowListOnlyMode() {
        setWorkspaceMode(false)
        reparentToCenter(listSectionContainer)
    }

    fun setBackToListEnabled(enabled: Boolean) {
        backToListButton.isEnabled = enabled
    }

    fun showWorkspaceEmptyCard() {
        workspaceCardLayout.show(workspaceCardPanel, emptyCardId)
    }

    fun showWorkspaceContent() {
        showWorkflowWorkspaceMode()
        setBackToListEnabled(true)
        workspaceCardLayout.show(workspaceCardPanel, contentCardId)
    }

    private fun showWorkflowWorkspaceMode() {
        setWorkspaceMode(true)
        reparentToCenter(workspacePanelContainer)
    }

    private fun reparentToCenter(component: Component) {
        detachFromParent(component)
        if (centerContentPanel.componentCount == 1 && centerContentPanel.getComponent(0) === component) {
            return
        }
        centerContentPanel.removeAll()
        centerContentPanel.add(component, BorderLayout.CENTER)
        centerContentPanel.revalidate()
        centerContentPanel.repaint()
    }

    private fun detachFromParent(component: Component) {
        (component.parent as? Container)?.remove(component)
    }
}
