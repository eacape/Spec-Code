package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StructuredTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal object SpecTaskDependencySelectorSupport {
    fun buttonText(dependsOn: List<String>): String {
        val normalized = dependsOn.map(String::trim).filter(String::isNotEmpty)
        return when (normalized.size) {
            0 -> SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.button.empty")
            1 -> SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.button.single", normalized[0])
            2 -> SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.button.double", normalized[0], normalized[1])
            else -> SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.button.count", normalized.size)
        }
    }

    fun tooltip(selectedTask: StructuredTask?, tasksById: Map<String, StructuredTask>): String {
        val task = selectedTask ?: return SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.unavailable")
        if (task.dependsOn.isEmpty()) {
            return SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.edit.tooltip.none", task.id)
        }
        val summary = task.dependsOn.joinToString(", ") { dependencyId ->
            tasksById[dependencyId]?.let { dependency ->
                "$dependencyId ${dependency.title}"
            } ?: dependencyId
        }
        return SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.edit.tooltip.selected", task.id, summary)
    }

    fun visibleCandidates(
        selectedTaskId: String,
        tasks: Collection<StructuredTask>,
        selectedIds: Set<String>,
        query: String,
    ): List<StructuredTask> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        return tasks.asSequence()
            .filter { task -> task.id != selectedTaskId }
            .filter { task ->
                normalizedQuery.isBlank() || buildSearchText(task).contains(normalizedQuery)
            }
            .sortedWith(
                compareByDescending<StructuredTask> { task -> task.id in selectedIds }
                    .thenBy { task -> task.id },
            )
            .toList()
    }

    private fun buildSearchText(task: StructuredTask): String {
        return "${task.id} ${task.title}".lowercase(Locale.ROOT)
    }
}

internal class SpecTaskDependencySelectorPopup(
    private val selectedTask: StructuredTask,
    tasks: Collection<StructuredTask>,
    initialDependencies: List<String>,
    private val onApply: (List<String>) -> Unit,
) {
    private val allTasks = tasks.toList()
    private val availableTaskIds = allTasks.asSequence().map(StructuredTask::id).toSet()
    private val workingSelectedIds = linkedSetOf<String>().apply {
        initialDependencies
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter { dependencyId -> dependencyId in availableTaskIds && dependencyId != selectedTask.id }
            .forEach(::add)
    }
    private val listModel = DefaultListModel<StructuredTask>()
    private val taskList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 10
        fixedCellHeight = -1
        cellRenderer = DependencyTaskCellRenderer { task -> task.id in workingSelectedIds }
        emptyText.text = SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.popup.empty")
    }
    private val searchField = SearchTextField(false).apply {
        textEditor.putClientProperty(
            "JTextField.placeholderText",
            SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.popup.search.placeholder"),
        )
    }
    private val selectionLabel = JBLabel()
    private val clearButton = JButton(SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.popup.clear"))
    private val cancelButton = JButton(SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.popup.cancel"))
    private val applyButton = JButton(SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.popup.apply"))
    private val rootPanel = buildRootPanel()
    private var popup: JBPopup? = null

    init {
        bindSearchField()
        bindTaskList()
        clearButton.addActionListener {
            if (workingSelectedIds.isNotEmpty()) {
                workingSelectedIds.clear()
                rebuildVisibleList()
            }
        }
        cancelButton.addActionListener { popup?.cancel() }
        applyButton.addActionListener { applySelection() }
        rebuildVisibleList()
    }

    fun showUnderneathOf(owner: Component) {
        popup?.cancel()
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(rootPanel, searchField.textEditor)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelOnWindowDeactivation(true)
            .createPopup()
        popup?.showUnderneathOf(owner)
        ApplicationManager.getApplication().invokeLater {
            searchField.textEditor.requestFocusInWindow()
        }
    }

    private fun buildRootPanel(): JComponent {
        val hintLabel = JBLabel(
            SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.popup.hint", selectedTask.id),
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor.namedColor(
                "Label.infoForeground",
                JBColor(0x5B6B7F, 0xA8B3C2),
            )
            border = JBUI.Borders.emptyBottom(6)
        }
        val footerButtons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(clearButton)
            add(cancelButton)
            add(applyButton)
        }
        val footer = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(selectionLabel, BorderLayout.WEST)
            add(footerButtons, BorderLayout.EAST)
        }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(10)
            preferredSize = JBUI.size(460, 360)
            add(
                JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                    isOpaque = false
                    add(hintLabel, BorderLayout.NORTH)
                    add(searchField, BorderLayout.CENTER)
                },
                BorderLayout.NORTH,
            )
            add(
                JBScrollPane(taskList).apply {
                    border = SpecUiStyle.roundedLineBorder(
                        lineColor = JBColor(0xC9D6E8, 0x566173),
                        arc = JBUI.scale(10),
                    )
                    viewportBorder = JBUI.Borders.empty()
                    SpecUiStyle.applyFastVerticalScrolling(this)
                },
                BorderLayout.CENTER,
            )
            add(footer, BorderLayout.SOUTH)
        }
    }

    private fun bindSearchField() {
        searchField.textEditor.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = rebuildVisibleList()

                override fun removeUpdate(e: DocumentEvent?) = rebuildVisibleList()

                override fun changedUpdate(e: DocumentEvent?) = rebuildVisibleList()
            },
        )
        searchField.textEditor.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
            "spec.task.dependsOn.focusList",
        )
        searchField.textEditor.actionMap.put(
            "spec.task.dependsOn.focusList",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    if (listModel.size() > 0) {
                        if (taskList.selectedIndex < 0) {
                            taskList.selectedIndex = 0
                        }
                        taskList.requestFocusInWindow()
                    }
                }
            },
        )
    }

    private fun bindTaskList() {
        taskList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = taskList.locationToIndex(e.point)
                    if (index < 0) {
                        return
                    }
                    val bounds = taskList.getCellBounds(index, index) ?: return
                    if (!bounds.contains(e.point)) {
                        return
                    }
                    val task = listModel.getElementAt(index)
                    toggleDependency(task.id)
                }
            },
        )
        taskList.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0),
            "spec.task.dependsOn.toggle",
        )
        taskList.actionMap.put(
            "spec.task.dependsOn.toggle",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    val task = taskList.selectedValue ?: return
                    toggleDependency(task.id)
                }
            },
        )
        taskList.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            "spec.task.dependsOn.apply",
        )
        taskList.actionMap.put(
            "spec.task.dependsOn.apply",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    applySelection()
                }
            },
        )
    }

    private fun toggleDependency(taskId: String) {
        if (taskId in workingSelectedIds) {
            workingSelectedIds.remove(taskId)
        } else {
            workingSelectedIds.add(taskId)
        }
        rebuildVisibleList(preferredSelectionId = taskId)
    }

    private fun rebuildVisibleList(preferredSelectionId: String? = null) {
        val currentSelectionId = preferredSelectionId ?: taskList.selectedValue?.id
        val candidates = SpecTaskDependencySelectorSupport.visibleCandidates(
            selectedTaskId = selectedTask.id,
            tasks = allTasks,
            selectedIds = workingSelectedIds,
            query = searchField.text,
        )
        listModel.clear()
        candidates.forEach(listModel::addElement)
        val selectionIndex = when {
            candidates.isEmpty() -> -1
            currentSelectionId == null -> 0
            else -> candidates.indexOfFirst { candidate -> candidate.id == currentSelectionId }.takeIf { it >= 0 } ?: 0
        }
        if (selectionIndex >= 0) {
            taskList.selectedIndex = selectionIndex
            taskList.ensureIndexIsVisible(selectionIndex)
        } else {
            taskList.clearSelection()
        }
        updateFooter()
        taskList.repaint()
    }

    private fun updateFooter() {
        val selectionCount = workingSelectedIds.size
        selectionLabel.text = if (selectionCount == 0) {
            SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.popup.selection.none")
        } else {
            SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.popup.selection.count", selectionCount)
        }
        clearButton.isEnabled = selectionCount > 0
    }

    private fun applySelection() {
        val result = workingSelectedIds.toList().sorted()
        popup?.cancel()
        onApply(result)
    }
}

private class DependencyTaskCellRenderer(
    private val isSelectedDependency: (StructuredTask) -> Boolean,
) : DefaultListCellRenderer() {
    private val container = JPanel(BorderLayout(JBUI.scale(8), 0))
    private val checkBox = JCheckBox().apply {
        isOpaque = false
        isFocusable = false
    }
    private val titleLabel = JBLabel().apply {
        font = JBUI.Fonts.label().deriveFont(12f)
    }
    private val metaLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor.namedColor(
            "Label.infoForeground",
            JBColor(0x5B6B7F, 0xA8B3C2),
        )
    }
    private val textPanel = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
        isOpaque = false
        add(titleLabel, BorderLayout.NORTH)
        add(metaLabel, BorderLayout.CENTER)
    }

    init {
        container.border = JBUI.Borders.empty(6, 8)
        container.add(checkBox, BorderLayout.WEST)
        container.add(textPanel, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val task = value as? StructuredTask
        val listBackground = if (isSelected && list != null) list.selectionBackground else list?.background ?: container.background
        val listForeground = if (isSelected && list != null) list.selectionForeground else list?.foreground ?: titleLabel.foreground
        container.background = listBackground
        checkBox.background = listBackground
        titleLabel.foreground = listForeground
        if (task == null) {
            checkBox.isSelected = false
            titleLabel.text = ""
            metaLabel.text = ""
            return container
        }
        checkBox.isSelected = isSelectedDependency(task)
        titleLabel.text = "${task.id}  ${task.title}"
        metaLabel.text = task.displayStatus.name
        return container
    }
}
