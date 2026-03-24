package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager

class SpecWorkflowListPanel(
    private val onWorkflowFocused: (String) -> Unit,
    private val onOpenWorkflow: (String) -> Unit,
    private val onCreateWorkflow: () -> Unit,
    private val onEditWorkflow: (String) -> Unit,
    private val onDeleteWorkflow: (String) -> Unit,
    private val showCreateButton: Boolean = true,
) : JPanel(BorderLayout()) {

    data class WorkflowListItem(
        val workflowId: String,
        val title: String,
        val description: String,
        val currentPhase: SpecPhase,
        val currentStageLabel: String = currentPhase.displayName,
        val status: WorkflowStatus,
        val updatedAt: Long,
        val changeIntent: SpecChangeIntent = SpecChangeIntent.FULL,
        val baselineWorkflowId: String? = null,
    )

    private val listModel = DefaultListModel<WorkflowListItem>()
    private val workflowList = object : JBList<WorkflowListItem>(listModel) {
        override fun getToolTipText(event: MouseEvent?): String? {
            val point = event?.point ?: return null
            return resolveTooltipAt(point)
        }
    }
    private val workflowCellRenderer = WorkflowCellRenderer()
    private val newButton = JButton()
    private var suppressSelectionEvents = false

    init {
        minimumSize = JBUI.size(JBUI.scale(132), 0)
        preferredSize = JBUI.size(JBUI.scale(188), 0)
        setupUI()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty(4, 4, 2, 4)

        newButton.text = newButtonText()
        styleActionButton(newButton)
        newButton.horizontalAlignment = SwingConstants.CENTER

        newButton.addActionListener { onCreateWorkflow() }

        workflowList.cellRenderer = workflowCellRenderer
        workflowList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        workflowList.fixedCellHeight = -1
        workflowList.visibleRowCount = -1
        workflowList.isOpaque = false
        workflowList.setExpandableItemsEnabled(false)
        workflowList.border = JBUI.Borders.empty()
        workflowList.emptyText.text = SpecCodingBundle.message("spec.workflow.empty")
        ToolTipManager.sharedInstance().registerComponent(workflowList)
        workflowList.addListSelectionListener {
            if (suppressSelectionEvents) return@addListSelectionListener
            if (!it.valueIsAdjusting) {
                workflowList.selectedValue?.let { item ->
                    onWorkflowFocused(item.workflowId)
                }
            }
        }
        workflowList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    handleListClick(e)
                }

                override fun mouseExited(e: MouseEvent) {
                    workflowList.cursor = Cursor.getDefaultCursor()
                }
            },
        )
        workflowList.addMouseMotionListener(
            object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    updateListCursor(e.point)
                }
            },
        )
        add(
            JBScrollPane(workflowList).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                viewport.isOpaque = false
                isOpaque = false
            },
            BorderLayout.CENTER,
        )
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(8)
                if (showCreateButton) {
                    add(newButton, BorderLayout.CENTER)
                }
            },
            BorderLayout.SOUTH,
        )
    }

    fun updateWorkflows(items: List<WorkflowListItem>) {
        listModel.clear()
        items.forEach { listModel.addElement(it) }
    }

    fun setSelectedWorkflow(workflowId: String?) {
        suppressSelectionEvents = true
        try {
            if (workflowId == null) {
                workflowList.clearSelection()
                return
            }
            for (i in 0 until listModel.size()) {
                if (listModel[i].workflowId == workflowId) {
                    workflowList.selectedIndex = i
                    return
                }
            }
        } finally {
            suppressSelectionEvents = false
        }
    }

    fun workflowOptionsForCreate(): List<NewSpecWorkflowDialog.WorkflowOption> {
        return (0 until listModel.size()).map { index ->
            val item = listModel[index]
            NewSpecWorkflowDialog.WorkflowOption(
                workflowId = item.workflowId,
                title = item.title,
                description = item.description,
            )
        }
    }

    fun refreshLocalizedTexts() {
        newButton.text = newButtonText()
        styleActionButton(newButton)
        setEmptyText(SpecCodingBundle.message("spec.workflow.empty"))
        workflowList.repaint()
    }

    fun setEmptyText(text: String) {
        workflowList.emptyText.text = text
    }

    fun currentItems(): List<WorkflowListItem> {
        return (0 until listModel.size()).map { listModel[it] }
    }

    fun requestListFocus(preferredWorkflowId: String? = workflowList.selectedValue?.workflowId) {
        val targetWorkflowId = preferredWorkflowId
            ?.takeIf { workflowId -> currentItems().any { item -> item.workflowId == workflowId } }
            ?: currentItems().firstOrNull()?.workflowId
        if (targetWorkflowId != null) {
            setSelectedWorkflow(targetWorkflowId)
        }
        if (workflowList.selectedIndex >= 0) {
            workflowList.ensureIndexIsVisible(workflowList.selectedIndex)
        }
        workflowList.requestFocusInWindow()
    }

    internal fun itemsForTest(): List<WorkflowListItem> {
        return (0 until listModel.size()).map { listModel[it] }
    }

    internal fun selectedWorkflowIdForTest(): String? {
        return workflowList.selectedValue?.workflowId
    }

    internal fun clickNewForTest() {
        newButton.doClick()
    }

    internal fun clickDeleteForTest() {
        workflowList.selectedValue?.let { onDeleteWorkflow(it.workflowId) }
    }

    internal fun clickEditForTest() {
        workflowList.selectedValue?.let { onEditWorkflow(it.workflowId) }
    }

    internal fun clickOpenForTest() {
        workflowList.selectedValue?.let { onOpenWorkflow(it.workflowId) }
    }

    private fun updateListCursor(point: Point) {
        val index = workflowList.locationToIndex(point)
        if (index < 0) {
            workflowList.cursor = Cursor.getDefaultCursor()
            return
        }
        val cellBounds = workflowList.getCellBounds(index, index) ?: run {
            workflowList.cursor = Cursor.getDefaultCursor()
            return
        }
        if (!cellBounds.contains(point)) {
            workflowList.cursor = Cursor.getDefaultCursor()
            return
        }
        workflowList.cursor = if (workflowCellRenderer.resolveRowAction(workflowList, index, point) != null) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private fun handleListClick(event: MouseEvent) {
        val index = workflowList.locationToIndex(event.point)
        if (index < 0) return
        val cellBounds = workflowList.getCellBounds(index, index) ?: return
        if (!cellBounds.contains(event.point)) return
        val selectedId = listModel.getElementAt(index).workflowId
        workflowList.selectedIndex = index
        when (workflowCellRenderer.resolveRowAction(workflowList, index, event.point)) {
            WorkflowCellRenderer.RowAction.OPEN -> onOpenWorkflow(selectedId)
            WorkflowCellRenderer.RowAction.DELETE -> onDeleteWorkflow(selectedId)
            WorkflowCellRenderer.RowAction.EDIT -> onEditWorkflow(selectedId)
            null -> {
                if (event.clickCount >= 2 && event.button == MouseEvent.BUTTON1) {
                    onOpenWorkflow(selectedId)
                }
            }
        }
    }

    private fun resolveTooltipAt(point: Point): String? {
        val index = workflowList.locationToIndex(point)
        if (index < 0) return null
        val cellBounds = workflowList.getCellBounds(index, index) ?: return null
        if (!cellBounds.contains(point)) return null
        return workflowCellRenderer.resolveTooltip(workflowList, index, point)
    }

    private fun newButtonText(): String = "+ ${SpecCodingBundle.message("spec.workflow.new")}"

    private class WorkflowCellRenderer : ListCellRenderer<WorkflowListItem> {
        enum class RowAction {
            OPEN,
            EDIT,
            DELETE,
        }

        private val rowPanel = JPanel(BorderLayout())
        private val cardPanel = RoundedCardPanel(JBUI.scale(12))
        private val contentPanel = JPanel(BorderLayout(0, JBUI.scale(4)))
        private val textPanel = JPanel()
        private val titleLabel = JLabel()
        private val descriptionLabel = JLabel()
        private val phaseLabel = JLabel()
        private val statusLabel = JLabel()
        private val metaRow = JPanel(BorderLayout())
        private val rightPanel = JPanel(BorderLayout(0, RIGHT_PANEL_VERTICAL_GAP))
        private val actionPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, ACTION_ICON_GAP, 0))
        private val openActionLabel = JLabel(SpecWorkflowIcons.OpenDocument)
        private val editActionLabel = JLabel(SpecWorkflowIcons.Edit)
        private val deleteActionLabel = JLabel(SpecWorkflowIcons.Delete)

        init {
            rowPanel.isOpaque = false
            rowPanel.border = JBUI.Borders.empty(0, 0, ROW_BOTTOM_GAP, 0)

            cardPanel.layout = BorderLayout()
            cardPanel.border = JBUI.Borders.empty(CARD_VERTICAL_PAD, CARD_LEFT_PAD, CARD_VERTICAL_PAD, CARD_RIGHT_PAD)

            titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 12f)
            descriptionLabel.font = descriptionLabel.font.deriveFont(Font.PLAIN, 10.5f)

            phaseLabel.font = phaseLabel.font.deriveFont(Font.PLAIN, 10.5f)
            statusLabel.font = statusLabel.font.deriveFont(Font.BOLD, 10.5f)
            statusLabel.horizontalAlignment = SwingConstants.RIGHT
            statusLabel.horizontalTextPosition = SwingConstants.RIGHT
            statusLabel.border = JBUI.Borders.empty(0, 0, 0, ACTION_PANEL_RIGHT_PAD)

            textPanel.isOpaque = false
            textPanel.layout = javax.swing.BoxLayout(textPanel, javax.swing.BoxLayout.Y_AXIS)
            textPanel.add(titleLabel)
            textPanel.add(descriptionLabel)

            metaRow.isOpaque = false
            metaRow.add(phaseLabel, BorderLayout.WEST)

            actionPanel.isOpaque = false
            actionPanel.border = JBUI.Borders.empty(0, ACTION_PANEL_LEFT_PAD, 0, ACTION_PANEL_RIGHT_PAD)
            openActionLabel.preferredSize = JBUI.size(ACTION_ICON_SIZE, ACTION_ICON_SIZE)
            openActionLabel.minimumSize = openActionLabel.preferredSize
            editActionLabel.preferredSize = JBUI.size(ACTION_ICON_SIZE, ACTION_ICON_SIZE)
            editActionLabel.minimumSize = editActionLabel.preferredSize
            deleteActionLabel.preferredSize = JBUI.size(ACTION_ICON_SIZE, ACTION_ICON_SIZE)
            deleteActionLabel.minimumSize = deleteActionLabel.preferredSize
            actionPanel.add(openActionLabel)
            actionPanel.add(editActionLabel)
            actionPanel.add(deleteActionLabel)

            rightPanel.isOpaque = false
            rightPanel.add(actionPanel, BorderLayout.NORTH)
            rightPanel.add(statusLabel, BorderLayout.SOUTH)

            contentPanel.isOpaque = false
            contentPanel.add(textPanel, BorderLayout.CENTER)
            contentPanel.add(metaRow, BorderLayout.SOUTH)

            cardPanel.add(contentPanel, BorderLayout.CENTER)
            cardPanel.add(rightPanel, BorderLayout.EAST)
            rowPanel.add(cardPanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out WorkflowListItem>,
            value: WorkflowListItem?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value == null) return rowPanel

            val statusText = localizeStatus(value.status).lowercase()
            statusLabel.text = statusText
            val statusMetrics = statusLabel.getFontMetrics(statusLabel.font)
            val statusColumnWidth = statusColumnWidth(statusMetrics)
            statusLabel.preferredSize = JBUI.size(statusColumnWidth, statusMetrics.height)
            statusLabel.minimumSize = statusLabel.preferredSize
            statusLabel.maximumSize = JBUI.size(statusColumnWidth, Int.MAX_VALUE)

            val textAreaWidth = estimateTextAreaWidth(list, statusColumnWidth)
            val titleColor = if (isSelected) {
                JBColor(Color(28, 45, 70), Color(230, 236, 244))
            } else {
                JBColor(Color(31, 36, 43), Color(220, 224, 232))
            }
            val phaseColor = if (isSelected) {
                JBColor(Color(73, 89, 113), Color(170, 182, 196))
            } else {
                JBColor(Color(96, 103, 114), Color(157, 164, 174))
            }
            val cardBg = if (isSelected) {
                JBColor(Color(225, 238, 255), Color(66, 84, 110))
            } else {
                JBColor(Color(247, 249, 252), Color(55, 59, 67))
            }
            val cardBorder = if (isSelected) {
                JBColor(Color(129, 167, 225), Color(100, 130, 167))
            } else {
                JBColor(Color(219, 226, 238), Color(74, 80, 90))
            }

            val fullTitleText = value.title.lowercase()
            val titleText = truncateByPixel(
                value = fullTitleText,
                fontMetrics = titleLabel.getFontMetrics(titleLabel.font),
                maxWidthPx = textAreaWidth,
            )
            titleLabel.text = titleText
            titleLabel.foreground = titleColor
            titleLabel.toolTipText = if (titleText != fullTitleText) value.title else null

            val description = value.description.trim()
            descriptionLabel.isVisible = description.isNotBlank()
            if (descriptionLabel.isVisible) {
                val descriptionText = truncateByPixel(
                    value = description,
                    fontMetrics = descriptionLabel.getFontMetrics(descriptionLabel.font),
                    maxWidthPx = textAreaWidth,
                )
                descriptionLabel.text = descriptionText
                descriptionLabel.foreground = phaseColor
                descriptionLabel.toolTipText = if (descriptionText != description) description else null
            } else {
                descriptionLabel.text = ""
                descriptionLabel.toolTipText = null
            }
            val intentLabelText = when (value.changeIntent) {
                SpecChangeIntent.FULL -> SpecCodingBundle.message("spec.workflow.intent.full.short")
                SpecChangeIntent.INCREMENTAL -> SpecCodingBundle.message("spec.workflow.intent.incremental.short")
            }
            val fullPhaseText = "${value.currentStageLabel.lowercase()} · $intentLabelText"
            val phaseText = truncateByPixel(
                value = fullPhaseText,
                fontMetrics = phaseLabel.getFontMetrics(phaseLabel.font),
                maxWidthPx = textAreaWidth.coerceAtLeast(MIN_PHASE_TEXT_WIDTH),
            )
            phaseLabel.text = phaseText
            phaseLabel.foreground = phaseColor
            val baselineTooltip = value.baselineWorkflowId?.takeIf { it.isNotBlank() }?.let {
                SpecCodingBundle.message("spec.workflow.intent.incremental.tooltip", it)
            }
            phaseLabel.toolTipText = when {
                baselineTooltip != null && phaseText != fullPhaseText -> "$fullPhaseText\n$baselineTooltip"
                baselineTooltip != null -> baselineTooltip
                phaseText != fullPhaseText -> fullPhaseText
                else -> null
            }
            openActionLabel.toolTipText = SpecCodingBundle.message("spec.workflow.open")
            editActionLabel.toolTipText = SpecCodingBundle.message("spec.workflow.edit")
            deleteActionLabel.toolTipText = SpecCodingBundle.message("spec.workflow.delete")
            statusLabel.foreground = if (isSelected) {
                JBColor(Color(55, 84, 128), Color(191, 214, 244))
            } else {
                getStatusColor(value.status)
            }

            cardPanel.updateColors(cardBg, cardBorder)
            return rowPanel
        }

        private fun estimateTextAreaWidth(list: JList<*>, statusColumnWidth: Int): Int {
            val listWidth = when {
                list.width > 0 -> list.width
                list.visibleRect.width > 0 -> list.visibleRect.width
                else -> JBUI.scale(188)
            }
            val actionColumnWidth = ACTION_PANEL_LEFT_PAD +
                ACTION_PANEL_RIGHT_PAD +
                ACTION_ICON_SIZE * 3 +
                ACTION_ICON_GAP * 2
            val statusColumnReservedWidth = statusColumnWidth + ACTION_PANEL_RIGHT_PAD
            val rightColumnWidth = maxOf(actionColumnWidth, statusColumnReservedWidth)
            val reservedWidth = CARD_LEFT_PAD +
                CARD_RIGHT_PAD +
                rightColumnWidth +
                ACTION_SAFE_GAP
            return (listWidth - reservedWidth).coerceAtLeast(MIN_TEXT_WIDTH)
        }

        private fun truncateByPixel(
            value: String,
            fontMetrics: FontMetrics,
            maxWidthPx: Int,
        ): String {
            val normalized = value.replace(Regex("\\s+"), " ").trim()
            if (normalized.isEmpty()) return normalized
            if (fontMetrics.stringWidth(normalized) <= maxWidthPx) return normalized

            val ellipsis = "…"
            val ellipsisWidth = fontMetrics.stringWidth(ellipsis)
            if (maxWidthPx <= ellipsisWidth) return ellipsis

            var low = 0
            var high = normalized.length
            while (low < high) {
                val mid = (low + high + 1) / 2
                val prefix = normalized.substring(0, mid).trimEnd()
                val width = fontMetrics.stringWidth(prefix) + ellipsisWidth
                if (width <= maxWidthPx) {
                    low = mid
                } else {
                    high = mid - 1
                }
            }
            val kept = normalized.substring(0, low).trimEnd()
            return if (kept.isEmpty()) ellipsis else "$kept$ellipsis"
        }

        private fun getStatusColor(status: WorkflowStatus): Color = when (status) {
            WorkflowStatus.IN_PROGRESS -> JBColor(Color(33, 150, 243), Color(78, 154, 241))
            WorkflowStatus.PAUSED -> JBColor(Color(255, 152, 0), Color(255, 167, 38))
            WorkflowStatus.COMPLETED -> JBColor(Color(76, 175, 80), Color(76, 175, 80))
            WorkflowStatus.FAILED -> JBColor(Color(244, 67, 54), Color(239, 83, 80))
        }

        private fun localizeStatus(status: WorkflowStatus): String = when (status) {
            WorkflowStatus.IN_PROGRESS -> SpecCodingBundle.message("spec.workflow.status.inProgress")
            WorkflowStatus.PAUSED -> SpecCodingBundle.message("spec.workflow.status.paused")
            WorkflowStatus.COMPLETED -> SpecCodingBundle.message("spec.workflow.status.completed")
            WorkflowStatus.FAILED -> SpecCodingBundle.message("spec.workflow.status.failed")
        }

        private fun statusColumnWidth(fontMetrics: FontMetrics): Int {
            val maxTextWidth = WorkflowStatus.entries
                .maxOf { status -> fontMetrics.stringWidth(localizeStatus(status).lowercase()) }
            return (maxTextWidth + STATUS_COLUMN_SIDE_PADDING).coerceAtLeast(MIN_STATUS_COLUMN_WIDTH)
        }

        private class RoundedCardPanel(private val arc: Int) : JPanel() {
            private var fillColor: Color = JBColor(Color(247, 249, 252), Color(55, 59, 67))
            private var strokeColor: Color = JBColor(Color(219, 226, 238), Color(74, 80, 90))

            init {
                isOpaque = false
            }

            fun updateColors(fill: Color, stroke: Color) {
                fillColor = fill
                strokeColor = stroke
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = fillColor
                    g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                    g2.color = strokeColor
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                } finally {
                    g2.dispose()
                }
            }
        }

        fun resolveRowAction(
            list: JList<out WorkflowListItem>,
            index: Int,
            point: Point,
        ): RowAction? {
            val cellBounds = list.getCellBounds(index, index) ?: return null
            if (!cellBounds.contains(point)) return null
            val value = list.model.getElementAt(index) ?: return null
            val selected = list.selectedIndex == index
            val rendererComponent = getListCellRendererComponent(list, value, index, selected, false)
            rendererComponent.setBounds(0, 0, cellBounds.width, cellBounds.height)
            layoutRecursively(rendererComponent)

            val editRect = SwingUtilities.convertRectangle(
                editActionLabel.parent,
                editActionLabel.bounds,
                rendererComponent,
            )
            val openRect = SwingUtilities.convertRectangle(
                openActionLabel.parent,
                openActionLabel.bounds,
                rendererComponent,
            )
            val deleteRect = SwingUtilities.convertRectangle(
                deleteActionLabel.parent,
                deleteActionLabel.bounds,
                rendererComponent,
            )

            val listOpenRect = Rectangle(
                cellBounds.x + openRect.x - ACTION_HIT_SLOP,
                cellBounds.y + openRect.y - ACTION_HIT_SLOP,
                openRect.width + ACTION_HIT_SLOP * 2,
                openRect.height + ACTION_HIT_SLOP * 2,
            )
            val listEditRect = Rectangle(
                cellBounds.x + editRect.x - ACTION_HIT_SLOP,
                cellBounds.y + editRect.y - ACTION_HIT_SLOP,
                editRect.width + ACTION_HIT_SLOP * 2,
                editRect.height + ACTION_HIT_SLOP * 2,
            )
            val listDeleteRect = Rectangle(
                cellBounds.x + deleteRect.x - ACTION_HIT_SLOP,
                cellBounds.y + deleteRect.y - ACTION_HIT_SLOP,
                deleteRect.width + ACTION_HIT_SLOP * 2,
                deleteRect.height + ACTION_HIT_SLOP * 2,
            )
            return when {
                listOpenRect.contains(point) -> RowAction.OPEN
                listDeleteRect.contains(point) -> RowAction.DELETE
                listEditRect.contains(point) -> RowAction.EDIT
                else -> null
            }
        }

        fun resolveTooltip(
            list: JList<out WorkflowListItem>,
            index: Int,
            point: Point,
        ): String? {
            val cellBounds = list.getCellBounds(index, index) ?: return null
            if (!cellBounds.contains(point)) return null
            val value = list.model.getElementAt(index) ?: return null
            val selected = list.selectedIndex == index
            val rendererComponent = getListCellRendererComponent(list, value, index, selected, false)
            rendererComponent.setBounds(0, 0, cellBounds.width, cellBounds.height)
            layoutRecursively(rendererComponent)
            val target = SwingUtilities.getDeepestComponentAt(
                rendererComponent,
                point.x - cellBounds.x,
                point.y - cellBounds.y,
            )
            return generateSequence(target) { component -> component.parent }
                .filterIsInstance<JComponent>()
                .mapNotNull { component -> component.toolTipText?.takeIf { it.isNotBlank() } }
                .firstOrNull()
        }

        private fun layoutRecursively(component: Component) {
            if (component !is java.awt.Container) return
            component.doLayout()
            component.components.forEach { child ->
                layoutRecursively(child)
            }
        }

        companion object {
            private val ROW_BOTTOM_GAP = JBUI.scale(8)
            private val CARD_VERTICAL_PAD = JBUI.scale(8)
            private val CARD_LEFT_PAD = JBUI.scale(10)
            private val CARD_RIGHT_PAD = JBUI.scale(12)
            private val ACTION_PANEL_LEFT_PAD = JBUI.scale(8)
            private val ACTION_PANEL_RIGHT_PAD = JBUI.scale(4)
            private val ACTION_ICON_SIZE = JBUI.scale(16)
            private val ACTION_ICON_GAP = JBUI.scale(6)
            private val ACTION_SAFE_GAP = JBUI.scale(10)
            private val ACTION_HIT_SLOP = JBUI.scale(3)
            private val MIN_TEXT_WIDTH = JBUI.scale(76)
            private val MIN_PHASE_TEXT_WIDTH = JBUI.scale(36)
            private val STATUS_COLUMN_SIDE_PADDING = JBUI.scale(6)
            private val MIN_STATUS_COLUMN_WIDTH = JBUI.scale(52)
            private val RIGHT_PANEL_VERTICAL_GAP = JBUI.scale(4)
        }
    }

    private fun styleActionButton(button: JButton) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = JBUI.insets(1, 4, 1, 4)
        button.isOpaque = true
        button.foreground = BUTTON_FG
        button.background = BUTTON_BG
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(12)),
            JBUI.Borders.empty(1, 8, 1, 8),
        )
        SpecUiStyle.applyRoundRect(button, arc = 12)
        val textWidth = button.getFontMetrics(button.font).stringWidth(button.text ?: "")
        val insets = button.insets
        val lafWidth = button.preferredSize?.width ?: 0
        val width = maxOf(
            lafWidth,
            textWidth + insets.left + insets.right + JBUI.scale(10),
            JBUI.scale(40),
        )
        button.preferredSize = JBUI.size(width, JBUI.scale(28))
        button.minimumSize = button.preferredSize
    }

    companion object {
        private val BUTTON_BG = JBColor(Color(236, 244, 255), Color(66, 72, 84))
        private val BUTTON_BORDER = JBColor(Color(176, 194, 222), Color(104, 116, 134))
        private val BUTTON_FG = JBColor(Color(42, 66, 104), Color(205, 217, 236))
    }
}
