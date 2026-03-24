package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SearchTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.KeyEvent
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal object SpecWorkflowSwitcherSupport {
    fun visibleItems(
        items: Collection<SpecWorkflowListPanel.WorkflowListItem>,
        query: String,
    ): List<SpecWorkflowListPanel.WorkflowListItem> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        return items.asSequence()
            .sortedWith(
                compareByDescending<SpecWorkflowListPanel.WorkflowListItem> { item -> item.updatedAt }
                    .thenBy { item -> item.workflowId },
            )
            .filter { item ->
                normalizedQuery.isBlank() || buildSearchText(item).contains(normalizedQuery)
            }
            .toList()
    }

    private fun buildSearchText(item: SpecWorkflowListPanel.WorkflowListItem): String {
        return listOf(
            item.workflowId,
            item.title,
            item.description,
            item.currentStageLabel,
            item.currentPhase.name,
            item.status.name,
            item.changeIntent.name,
            item.baselineWorkflowId.orEmpty(),
        ).joinToString(" ").lowercase(Locale.ROOT)
    }
}

internal class SpecWorkflowSwitcherPopup(
    items: Collection<SpecWorkflowListPanel.WorkflowListItem>,
    initialSelectionWorkflowId: String?,
    private val onOpenWorkflow: (String) -> Unit,
    private val onEditWorkflow: (String) -> Unit,
    private val onDeleteWorkflow: (String) -> Unit,
) {
    private val allItems = items.toList()
    private val searchField = SearchTextField(false).apply {
        textEditor.putClientProperty(
            "JTextField.placeholderText",
            SpecCodingBundle.message("spec.workflow.switcher.search.placeholder"),
        )
    }
    private val listPanel = SpecWorkflowListPanel(
        onWorkflowFocused = { workflowId -> selectedWorkflowId = workflowId },
        onOpenWorkflow = { workflowId ->
            cancel()
            onOpenWorkflow(workflowId)
        },
        onCreateWorkflow = {},
        onEditWorkflow = { workflowId ->
            cancel()
            onEditWorkflow(workflowId)
        },
        onDeleteWorkflow = { workflowId ->
            cancel()
            onDeleteWorkflow(workflowId)
        },
        showCreateButton = false,
    ).apply {
        setEmptyText(SpecCodingBundle.message("spec.workflow.switcher.empty"))
    }
    private val rootPanel = buildRootPanel()
    private var popup: JBPopup? = null
    private var selectedWorkflowId: String? = initialSelectionWorkflowId
        ?.takeIf { workflowId -> allItems.any { item -> item.workflowId == workflowId } }
        ?: allItems.firstOrNull()?.workflowId

    init {
        bindSearchField()
        rebuildVisibleItems(preferredSelectionWorkflowId = selectedWorkflowId)
    }

    fun showUnderneathOf(owner: Component) {
        popup?.cancel()
        rebuildVisibleItems(preferredSelectionWorkflowId = selectedWorkflowId)
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(rootPanel, searchField.textEditor)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelOnWindowDeactivation(true)
            .setMovable(true)
            .setResizable(true)
            .createPopup()
        popup?.showUnderneathOf(owner)
        ApplicationManager.getApplication().invokeLater {
            searchField.textEditor.requestFocusInWindow()
        }
    }

    fun cancel() {
        popup?.cancel()
        popup = null
    }

    internal fun isVisibleForTest(): Boolean = popup?.isVisible == true

    internal fun visibleWorkflowIdsForTest(): List<String> {
        return listPanel.currentItems().map { item -> item.workflowId }
    }

    internal fun applySearchForTest(query: String) {
        searchField.text = query
        rebuildVisibleItems(preferredSelectionWorkflowId = selectedWorkflowId)
    }

    internal fun confirmSelectionForTest() {
        openSelectedWorkflow()
    }

    internal fun selectedWorkflowIdForTest(): String? = selectedWorkflowId

    private fun buildRootPanel(): JComponent {
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(10)
            preferredSize = JBUI.size(520, 420)
            add(searchField, BorderLayout.NORTH)
            add(listPanel, BorderLayout.CENTER)
        }
    }

    private fun bindSearchField() {
        searchField.textEditor.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = rebuildVisibleItems()

                override fun removeUpdate(e: DocumentEvent?) = rebuildVisibleItems()

                override fun changedUpdate(e: DocumentEvent?) = rebuildVisibleItems()
            },
        )
        searchField.textEditor.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
            "spec.workflow.switcher.focusList",
        )
        searchField.textEditor.actionMap.put(
            "spec.workflow.switcher.focusList",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    listPanel.requestListFocus(selectedWorkflowId)
                }
            },
        )
        searchField.textEditor.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            "spec.workflow.switcher.openSelected",
        )
        searchField.textEditor.actionMap.put(
            "spec.workflow.switcher.openSelected",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    openSelectedWorkflow()
                }
            },
        )
    }

    private fun rebuildVisibleItems(preferredSelectionWorkflowId: String? = selectedWorkflowId) {
        val visibleItems = SpecWorkflowSwitcherSupport.visibleItems(allItems, searchField.text)
        listPanel.updateWorkflows(visibleItems)
        val targetSelectionWorkflowId = preferredSelectionWorkflowId
            ?.takeIf { workflowId -> visibleItems.any { item -> item.workflowId == workflowId } }
            ?: visibleItems.firstOrNull()?.workflowId
        selectedWorkflowId = targetSelectionWorkflowId
        listPanel.setSelectedWorkflow(targetSelectionWorkflowId)
    }

    private fun openSelectedWorkflow() {
        val workflowId = selectedWorkflowId
            ?: listPanel.currentItems().firstOrNull()?.workflowId
            ?: return
        cancel()
        onOpenWorkflow(workflowId)
    }
}
