package com.eacape.speccodingplugin.ui.worktree

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.eacape.speccodingplugin.worktree.WorktreeStatus
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

class WorktreeListPanel(
    private val onWorktreeSelected: (String) -> Unit,
    private val onCreateWorktree: () -> Unit,
    private val onSwitchWorktree: (String) -> Unit,
    private val onMergeWorktree: (String) -> Unit,
    private val onCleanupWorktree: (String) -> Unit,
) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<WorktreeListItem>()
    private val worktreeList = JBList(listModel)
    private val createButton = JButton(SpecCodingBundle.message("worktree.action.create"))
    private val switchButton = JButton(SpecCodingBundle.message("worktree.action.switch"))
    private val mergeButton = JButton(SpecCodingBundle.message("worktree.action.merge"))
    private val cleanupButton = JButton(SpecCodingBundle.message("worktree.action.cleanup"))

    init {
        border = JBUI.Borders.empty(8)
        minimumSize = JBUI.size(JBUI.scale(220), 0)
        setupUI()
    }

    private fun setupUI() {
        val toolbar = JPanel(GridLayout(1, 0, 6, 0)).apply {
            isOpaque = false
        }

        createButton.addActionListener { onCreateWorktree() }
        switchButton.addActionListener { selectedId()?.let(onSwitchWorktree) }
        mergeButton.addActionListener { selectedId()?.let(onMergeWorktree) }
        cleanupButton.addActionListener { selectedId()?.let(onCleanupWorktree) }
        styleToolbarButton(createButton)
        styleToolbarButton(switchButton)
        styleToolbarButton(mergeButton)
        styleToolbarButton(cleanupButton)

        toolbar.add(createButton)
        toolbar.add(switchButton)
        toolbar.add(mergeButton)
        toolbar.add(cleanupButton)

        val toolbarCard = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = TOOLBAR_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = TOOLBAR_BORDER,
                arc = JBUI.scale(12),
                top = 8,
                left = 8,
                bottom = 8,
                right = 8,
            )
            add(toolbar, BorderLayout.CENTER)
        }
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(8)
                add(toolbarCard, BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )

        worktreeList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        worktreeList.cellRenderer = WorktreeCellRenderer()
        worktreeList.fixedCellHeight = -1
        worktreeList.visibleRowCount = -1
        worktreeList.border = JBUI.Borders.empty()
        worktreeList.background = LIST_BG
        worktreeList.selectionBackground = LIST_ROW_SELECTED_BG
        worktreeList.selectionForeground = LIST_ROW_SELECTED_FG
        worktreeList.emptyText.text = SpecCodingBundle.message("worktree.list.empty")
        worktreeList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateButtonStates(worktreeList.selectedValue)
                worktreeList.selectedValue?.let { item -> onWorktreeSelected(item.id) }
            }
        }

        updateButtonStates(null)
        add(
            JBScrollPane(worktreeList).apply {
                border = SpecUiStyle.roundedCardBorder(
                    lineColor = LIST_BORDER,
                    arc = JBUI.scale(12),
                    top = 2,
                    left = 2,
                    bottom = 2,
                    right = 2,
                )
                viewport.isOpaque = false
                isOpaque = false
            },
            BorderLayout.CENTER,
        )
    }

    fun updateWorktrees(items: List<WorktreeListItem>) {
        val selectedId = selectedId()
        listModel.clear()
        items.forEach(listModel::addElement)

        if (selectedId != null) {
            setSelectedWorktree(selectedId)
        }
        if (worktreeList.selectedValue == null) {
            updateButtonStates(null)
        }
    }

    fun refreshLocalizedTexts() {
        createButton.text = SpecCodingBundle.message("worktree.action.create")
        switchButton.text = SpecCodingBundle.message("worktree.action.switch")
        mergeButton.text = SpecCodingBundle.message("worktree.action.merge")
        cleanupButton.text = SpecCodingBundle.message("worktree.action.cleanup")
        worktreeList.emptyText.text = SpecCodingBundle.message("worktree.list.empty")
        worktreeList.repaint()
    }

    fun setSelectedWorktree(worktreeId: String?) {
        if (worktreeId == null) {
            worktreeList.clearSelection()
            updateButtonStates(null)
            return
        }

        for (i in 0 until listModel.size()) {
            if (listModel[i].id == worktreeId) {
                worktreeList.selectedIndex = i
                updateButtonStates(listModel[i])
                return
            }
        }
    }

    internal fun itemsForTest(): List<WorktreeListItem> = (0 until listModel.size()).map { listModel[it] }

    internal fun selectedWorktreeIdForTest(): String? = selectedId()

    internal fun buttonStatesForTest(): Map<String, Boolean> {
        return mapOf(
            "createEnabled" to createButton.isEnabled,
            "switchEnabled" to switchButton.isEnabled,
            "mergeEnabled" to mergeButton.isEnabled,
            "cleanupEnabled" to cleanupButton.isEnabled,
        )
    }

    internal fun clickCreateForTest() {
        createButton.doClick()
    }

    internal fun clickSwitchForTest() {
        switchButton.doClick()
    }

    internal fun clickMergeForTest() {
        mergeButton.doClick()
    }

    internal fun clickCleanupForTest() {
        cleanupButton.doClick()
    }

    private fun selectedId(): String? = worktreeList.selectedValue?.id

    private fun styleToolbarButton(button: JButton) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.isOpaque = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = JBUI.insets(4, 10, 4, 10)
        button.background = BUTTON_BG
        button.foreground = BUTTON_FG
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
            JBUI.Borders.empty(0),
        )
        SpecUiStyle.applyRoundRect(button, arc = 10)
        button.preferredSize = JBUI.size(
            maxOf(JBUI.scale(52), button.preferredSize.width),
            JBUI.scale(28),
        )
        button.minimumSize = button.preferredSize
    }

    private fun updateButtonStates(selected: WorktreeListItem?) {
        val hasSelection = selected != null
        val isActive = selected?.status == WorktreeStatus.ACTIVE
        switchButton.isEnabled = hasSelection && isActive
        mergeButton.isEnabled = hasSelection && isActive
        cleanupButton.isEnabled = hasSelection && selected.status != WorktreeStatus.REMOVED
    }

    private class WorktreeCellRenderer : ListCellRenderer<WorktreeListItem> {
        private val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        private val titleLabel = JBLabel()
        private val subtitleLabel = JBLabel()
        private val detailLabel = JBLabel()
        private val textPanel = JPanel()

        init {
            panel.isOpaque = true
            panel.border = JBUI.Borders.empty(8, 10)

            titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 12.5f)

            subtitleLabel.font = subtitleLabel.font.deriveFont(Font.PLAIN, 11f)
            subtitleLabel.foreground = SUBTITLE_FG

            detailLabel.font = detailLabel.font.deriveFont(Font.PLAIN, 10.5f)

            textPanel.isOpaque = false
            textPanel.layout = BoxLayout(textPanel, BoxLayout.Y_AXIS)
            textPanel.add(titleLabel)
            textPanel.add(subtitleLabel)
            textPanel.add(detailLabel)

            panel.add(textPanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out WorktreeListItem>,
            value: WorktreeListItem?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            if (value != null) {
                val activeMarker = if (value.isActive) {
                    SpecCodingBundle.message("worktree.list.activeMarker")
                } else {
                    ""
                }
                val statusText = when (value.status) {
                    WorktreeStatus.ACTIVE -> SpecCodingBundle.message("worktree.status.active")
                    WorktreeStatus.MERGED -> SpecCodingBundle.message("worktree.status.merged")
                    WorktreeStatus.REMOVED -> SpecCodingBundle.message("worktree.status.removed")
                    WorktreeStatus.ERROR -> SpecCodingBundle.message("worktree.status.error")
                }
                titleLabel.text = "${value.specTaskId}$activeMarker"
                subtitleLabel.text = value.specTitle
                detailLabel.text = SpecCodingBundle.message("worktree.list.detail", statusText, value.branchName)
            } else {
                titleLabel.text = ""
                subtitleLabel.text = ""
                detailLabel.text = ""
            }

            panel.background = if (isSelected) list.selectionBackground else list.background
            panel.border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(
                    if (isSelected) LIST_ROW_SELECTED_BORDER else LIST_ROW_BORDER,
                    JBUI.scale(10),
                ),
                JBUI.Borders.empty(8, 10),
            )
            titleLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
            subtitleLabel.foreground = if (isSelected) list.selectionForeground else SUBTITLE_FG
            detailLabel.foreground = if (isSelected) list.selectionForeground else statusColor(value?.status ?: WorktreeStatus.ACTIVE)
            return panel
        }

        private fun statusColor(status: WorktreeStatus): Color {
            return when (status) {
                WorktreeStatus.ACTIVE -> JBColor(Color(76, 175, 80), Color(76, 175, 80))
                WorktreeStatus.MERGED -> JBColor(Color(33, 150, 243), Color(78, 154, 241))
                WorktreeStatus.REMOVED -> JBColor.GRAY
                WorktreeStatus.ERROR -> JBColor(Color(244, 67, 54), Color(239, 83, 80))
            }
        }

        companion object {
            private val SUBTITLE_FG = JBColor(Color(88, 98, 113), Color(168, 176, 189))
        }
    }

    companion object {
        private val TOOLBAR_BG = JBColor(Color(246, 249, 255), Color(57, 62, 70))
        private val TOOLBAR_BORDER = JBColor(Color(204, 216, 236), Color(87, 98, 114))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val LIST_BG = JBColor(Color(248, 251, 255), Color(56, 62, 72))
        private val LIST_BORDER = JBColor(Color(204, 215, 233), Color(84, 92, 105))
        private val LIST_ROW_BORDER = JBColor(Color(212, 223, 239), Color(90, 100, 116))
        private val LIST_ROW_SELECTED_BORDER = JBColor(Color(158, 186, 223), Color(119, 139, 170))
        private val LIST_ROW_SELECTED_BG = JBColor(Color(226, 238, 255), Color(75, 91, 114))
        private val LIST_ROW_SELECTED_FG = JBColor(Color(35, 55, 86), Color(229, 237, 249))
    }
}
