package com.eacape.speccodingplugin.ui.history

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.session.SessionSummary
import com.eacape.speccodingplugin.session.displayWorkflowChatSessionTitle
import com.eacape.speccodingplugin.session.isWorkflowChatSessionTitle
import com.eacape.speccodingplugin.session.resolvedWorkflowChatBinding
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.BasicStroke
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.font.TextAttribute
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import kotlin.math.roundToInt

class HistorySessionListPanel(
    private val onSessionSelected: (String) -> Unit,
    private val onOpenSession: (String) -> Unit,
    private val onContinueSession: (String) -> Unit,
    private val onDeleteSession: (String) -> Unit,
    private val onBranchSession: (String) -> Unit,
) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<SessionSummary>()
    private val sessionList = HoverInfoSessionList(listModel)

    private var selectionInfoText: String = SpecCodingBundle.message("history.info.empty")
    private var hoverIndex: Int = -1
    private var hoverAction: RowAction? = null

    init {
        border = JBUI.Borders.empty(4)
        setupUi()
    }

    private fun setupUi() {
        sessionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sessionList.cellRenderer = SessionCellRenderer()
        sessionList.fixedCellHeight = -1
        sessionList.visibleRowCount = -1
        sessionList.isOpaque = false
        sessionList.setExpandableItemsEnabled(false)
        sessionList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selected = sessionList.selectedValue
                updateSelectionInfo(selected)
                selected?.id?.let(onSessionSelected)
            }
        }
        installMouseInteractions()
        updateSelectionInfo(null)

        val listScrollPane = JBScrollPane(sessionList).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            viewport.isOpaque = false
            isOpaque = false
        }
        add(
            RoundedCardPanel(JBUI.scale(12)).apply {
                layout = BorderLayout()
                isOpaque = false
                border = JBUI.Borders.empty(8, 8, 8, 8)
                updateColors(LIST_SURFACE_BG, LIST_SURFACE_BORDER)
                add(listScrollPane, BorderLayout.CENTER)
            },
            BorderLayout.CENTER,
        )
    }

    fun updateSessions(items: List<SessionSummary>) {
        listModel.clear()
        items.forEach(listModel::addElement)
        sessionList.revalidate()
        sessionList.repaint()
        updateSelectionInfo(sessionList.selectedValue)
    }

    fun setSelectedSession(sessionId: String?) {
        if (sessionId == null) {
            sessionList.clearSelection()
            updateSelectionInfo(null)
            return
        }

        for (i in 0 until listModel.size()) {
            val summary = listModel.getElementAt(i)
            if (summary.id == sessionId) {
                sessionList.selectedIndex = i
                updateSelectionInfo(summary)
                return
            }
        }

        sessionList.clearSelection()
        updateSelectionInfo(null)
    }

    internal fun selectedSessionIdForTest(): String? = selectedSessionId()

    internal fun sessionsForTest(): List<SessionSummary> = (0 until listModel.size()).map { listModel.getElementAt(it) }

    internal fun buttonStatesForTest(): Map<String, Boolean> {
        val hasSelection = selectedSessionId() != null
        return mapOf(
            "openEnabled" to hasSelection,
            "continueEnabled" to hasSelection,
            "branchEnabled" to hasSelection,
            "deleteEnabled" to hasSelection,
        )
    }

    internal fun clickOpenForTest() {
        selectedSessionId()?.let(onOpenSession)
    }

    internal fun clickDeleteForTest() {
        selectedSessionId()?.let(onDeleteSession)
    }

    internal fun clickContinueForTest() {
        selectedSessionId()?.let(onContinueSession)
    }

    internal fun clickBranchForTest() {
        selectedSessionId()?.let(onBranchSession)
    }

    internal fun selectedInfoTextForTest(): String = selectionInfoText

    private fun selectedSessionId(): String? = sessionList.selectedValue?.id

    private fun isSpecSession(summary: SessionSummary): Boolean {
        if (summary.resolvedWorkflowChatBinding() != null) return true
        return isWorkflowChatSessionTitle(summary.title)
    }

    private fun formatTimestamp(timestamp: Long): String {
        return timestampFormatter.format(Instant.ofEpochMilli(timestamp))
    }

    private fun buildBindingText(summary: SessionSummary): String {
        return when {
            !summary.worktreeId.isNullOrBlank() -> SpecCodingBundle.message(
                "history.binding.worktree",
                summary.worktreeId,
            )

            !summary.branchName.isNullOrBlank() -> SpecCodingBundle.message(
                "history.binding.branch",
                summary.branchName,
            )

            summary.resolvedWorkflowChatBinding() != null -> SpecCodingBundle.message(
                "history.binding.spec",
                summary.resolvedWorkflowChatBinding()!!.workflowId,
            )

            else -> SpecCodingBundle.message("history.binding.general")
        }
    }

    private fun resolveProviderText(summary: SessionSummary): String {
        return summary.modelProvider
            ?.trim()
            ?.ifBlank { SpecCodingBundle.message("common.unknown") }
            ?: SpecCodingBundle.message("common.unknown")
    }

    private fun updateSelectionInfo(summary: SessionSummary?) {
        selectionInfoText = if (summary == null) {
            SpecCodingBundle.message("history.info.empty")
        } else {
            buildString {
                appendLine(
                    SpecCodingBundle.message(
                        "history.info.titleLine",
                        displayWorkflowChatSessionTitle(summary.title).orEmpty(),
                    ),
                )
                append(SpecCodingBundle.message("history.tooltip.id", summary.id))
            }
        }
    }

    private fun installMouseInteractions() {
        sessionList.addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseMoved(event: MouseEvent) {
                    updateHoverState(event.point)
                }

                override fun mouseDragged(event: MouseEvent) {
                    updateHoverState(event.point)
                }
            },
        )
        sessionList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseExited(event: MouseEvent?) {
                    clearHoverState()
                }

                override fun mouseClicked(event: MouseEvent) {
                    val actionHit = resolveActionHit(event.point) ?: return
                    sessionList.selectedIndex = actionHit.index
                    val sessionId = listModel.getElementAt(actionHit.index).id
                    when (actionHit.action) {
                        RowAction.OPEN -> onOpenSession(sessionId)
                        RowAction.CONTINUE -> onContinueSession(sessionId)
                        RowAction.BRANCH -> onBranchSession(sessionId)
                        RowAction.DELETE -> onDeleteSession(sessionId)
                    }
                    event.consume()
                }
            },
        )
    }

    private fun updateHoverState(point: Point) {
        val oldIndex = hoverIndex
        val oldAction = hoverAction
        val hit = resolveActionHit(point)
        hoverIndex = hit?.index ?: -1
        hoverAction = hit?.action
        sessionList.cursor = if (hoverAction != null) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }

        if (oldIndex != hoverIndex || oldAction != hoverAction) {
            if (oldIndex >= 0) {
                sessionList.repaint(sessionList.getCellBounds(oldIndex, oldIndex))
            }
            if (hoverIndex >= 0) {
                sessionList.repaint(sessionList.getCellBounds(hoverIndex, hoverIndex))
            }
        }
    }

    private fun clearHoverState() {
        val oldIndex = hoverIndex
        hoverIndex = -1
        hoverAction = null
        sessionList.cursor = Cursor.getDefaultCursor()
        if (oldIndex >= 0) {
            sessionList.repaint(sessionList.getCellBounds(oldIndex, oldIndex))
        }
    }

    private fun resolveActionHit(point: Point): ActionHit? {
        val index = sessionList.locationToIndex(point)
        if (index < 0) return null
        val cellBounds = sessionList.getCellBounds(index, index) ?: return null
        if (!cellBounds.contains(point)) return null

        val zoneTop = cellBounds.y + ACTION_ZONE_TOP
        val zoneBottom = zoneTop + ACTION_HIT_SIZE
        if (point.y < zoneTop || point.y > zoneBottom) return null

        val startX = cellBounds.x + cellBounds.width - ACTION_STRIP_WIDTH
        val endX = cellBounds.x + cellBounds.width - ACTION_STRIP_RIGHT_PADDING
        if (point.x < startX || point.x > endX) return null

        var iconLeft = startX + ACTION_STRIP_LEFT_PADDING
        RowAction.entries.forEach { action ->
            val iconRight = iconLeft + ACTION_HIT_SIZE
            if (point.x in iconLeft..iconRight) {
                return ActionHit(index = index, action = action)
            }
            iconLeft += ACTION_HIT_SIZE + ACTION_ICON_GAP
        }
        return null
    }

    private inner class HoverInfoSessionList(model: DefaultListModel<SessionSummary>) : JBList<SessionSummary>(model) {
        override fun getToolTipText(event: MouseEvent?): String? {
            if (event == null) return null
            val action = resolveActionHit(event.point)?.action ?: return null
            return SpecCodingBundle.message(action.tooltipKey)
        }
    }

    private inner class SessionCellRenderer : ListCellRenderer<SessionSummary> {
        private val rowPanel = JPanel(BorderLayout())
        private val cardPanel = RoundedCardPanel(JBUI.scale(12))
        private val topRow = JPanel(BorderLayout(6, 0))
        private val modeLabel = JLabel()
        private val titleLabel = JLabel()
        private val actionPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, ACTION_STRIP_LEFT_PADDING, 0, ACTION_STRIP_RIGHT_PADDING)
        }
        private val actionHosts = mutableMapOf<RowAction, JPanel>()
        private val detailLabel = JLabel()
        private val providerLabel = JLabel()
        private val updatedLabel = JLabel()
        private val accentStripe = JPanel()

        init {
            rowPanel.isOpaque = false
            rowPanel.border = JBUI.Borders.empty(0, 0, 9, 0)

            cardPanel.layout = BorderLayout()
            cardPanel.border = JBUI.Borders.empty(8, 8, 8, 10)

            topRow.isOpaque = false

            modeLabel.font = deriveSemiboldFont(modeLabel.font, 10.5f)
            modeLabel.horizontalAlignment = SwingConstants.LEFT
            modeLabel.border = JBUI.Borders.empty()
            modeLabel.alignmentX = Component.LEFT_ALIGNMENT

            titleLabel.font = deriveTitleFont(titleLabel.font, 12.8f)
            titleLabel.horizontalAlignment = SwingConstants.LEFT
            titleLabel.alignmentX = Component.LEFT_ALIGNMENT
            detailLabel.font = detailLabel.font.deriveFont(Font.PLAIN, 11f)
            detailLabel.horizontalAlignment = SwingConstants.LEFT
            detailLabel.alignmentX = Component.LEFT_ALIGNMENT
            providerLabel.font = providerLabel.font.deriveFont(Font.PLAIN, 10.5f)
            providerLabel.horizontalAlignment = SwingConstants.LEFT
            providerLabel.alignmentX = Component.LEFT_ALIGNMENT
            updatedLabel.font = updatedLabel.font.deriveFont(Font.PLAIN, 10.5f)
            updatedLabel.horizontalAlignment = SwingConstants.LEFT
            updatedLabel.alignmentX = Component.LEFT_ALIGNMENT

            RowAction.entries.forEachIndexed { idx, action ->
                val iconLabel = JLabel(ScaledIcon(action.icon, ACTION_ICON_SCALE)).apply {
                    horizontalAlignment = SwingConstants.CENTER
                    verticalAlignment = SwingConstants.CENTER
                    preferredSize = JBUI.size(ACTION_HIT_SIZE, ACTION_HIT_SIZE)
                }
                val host = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(1)
                    preferredSize = JBUI.size(ACTION_HIT_SIZE, ACTION_HIT_SIZE)
                    minimumSize = preferredSize
                    maximumSize = preferredSize
                    add(iconLabel, BorderLayout.CENTER)
                }
                actionHosts[action] = host
                actionPanel.add(host)
                if (idx < RowAction.entries.size - 1) {
                    actionPanel.add(Box.createHorizontalStrut(ACTION_ICON_GAP))
                }
            }

            topRow.add(modeLabel, BorderLayout.WEST)
            topRow.add(titleLabel, BorderLayout.CENTER)
            topRow.add(actionPanel, BorderLayout.EAST)
            topRow.alignmentX = Component.LEFT_ALIGNMENT
            topRow.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))

            accentStripe.isOpaque = true
            accentStripe.preferredSize = JBUI.size(4, 0)
            accentStripe.minimumSize = JBUI.size(4, 0)
            accentStripe.maximumSize = JBUI.size(4, Int.MAX_VALUE)

            val content = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(topRow)
                add(Box.createVerticalStrut(4))
                add(detailLabel)
                add(Box.createVerticalStrut(3))
                add(providerLabel)
                add(Box.createVerticalStrut(2))
                add(updatedLabel)
            }
            cardPanel.add(accentStripe, BorderLayout.WEST)
            cardPanel.add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyLeft(8)
                    add(content, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
            rowPanel.add(cardPanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out SessionSummary>,
            value: SessionSummary?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            if (value != null) {
                val isSpec = isSpecSession(value)
                modeLabel.text = if (isSpec) {
                    SpecCodingBundle.message("history.mode.spec")
                } else {
                    SpecCodingBundle.message("history.mode.vibe")
                }
                val bindingDetail = SpecCodingBundle.message(
                    "history.binding.detail",
                    buildBindingText(value),
                    value.messageCount,
                )
                val updated = formatTimestamp(value.updatedAt)
                titleLabel.text = displayWorkflowChatSessionTitle(value.title).orEmpty()
                detailLabel.text = bindingDetail
                providerLabel.text = SpecCodingBundle.message("history.tooltip.provider", resolveProviderText(value))
                updatedLabel.text = SpecCodingBundle.message("history.tooltip.updated", updated)

                modeLabel.foreground = if (isSpec) MODE_SPEC_TEXT else MODE_VIBE_TEXT
                accentStripe.background = if (isSpec) CARD_ACCENT_SPEC else CARD_ACCENT_VIBE
            }

            val cardBackground = if (isSelected) CARD_BG_SELECTED else CARD_BG_DEFAULT
            val cardBorder = if (isSelected) CARD_BORDER_SELECTED else CARD_BORDER_DEFAULT
            val textColor = if (isSelected) {
                JBColor(Color(25, 41, 64), Color(224, 234, 247))
            } else {
                JBColor(Color(22, 24, 29), Color(224, 224, 224))
            }
            val subTextColor = if (isSelected) {
                JBColor(Color(49, 66, 90), Color(192, 210, 233))
            } else {
                JBColor(Color(96, 103, 114), Color(157, 164, 174))
            }

            cardPanel.updateColors(cardBackground, cardBorder)
            titleLabel.foreground = textColor
            detailLabel.foreground = subTextColor
            providerLabel.foreground = subTextColor
            updatedLabel.foreground = subTextColor

            RowAction.entries.forEach { action ->
                val host = actionHosts[action] ?: return@forEach
                val hovered = hoverIndex == index && hoverAction == action
                host.isOpaque = hovered
                if (hovered) {
                    host.background = if (isSelected) ACTION_ICON_HOVER_BG_SELECTED else ACTION_ICON_HOVER_BG
                    host.border = BorderFactory.createLineBorder(
                        if (isSelected) ACTION_ICON_HOVER_BORDER_SELECTED else ACTION_ICON_HOVER_BORDER,
                        1,
                    )
                } else {
                    host.background = JBUI.CurrentTheme.List.BACKGROUND
                    host.border = JBUI.Borders.empty(1)
                }
            }

            if (isSelected) {
                accentStripe.background = if (value?.let(::isSpecSession) == true) {
                    CARD_ACCENT_SPEC_SELECTED
                } else {
                    CARD_ACCENT_VIBE_SELECTED
                }
            }

            return rowPanel
        }
    }

    private class RoundedCardPanel(private val arc: Int) : JPanel() {
        private var fillColor: Color = CARD_BG_DEFAULT
        private var strokeColor: Color = CARD_BORDER_DEFAULT

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

    private data class ActionHit(
        val index: Int,
        val action: RowAction,
    )

    private enum class RowAction(val icon: Icon, val tooltipKey: String) {
        OPEN(HISTORY_OPEN_ICON, "history.action.open"),
        CONTINUE(ContinueSessionIcon(), "history.action.continue"),
        BRANCH(AllIcons.Vcs.Branch, "history.action.branch"),
        DELETE(AllIcons.Actions.GC, "history.action.delete"),
    }

    private class ContinueSessionIcon : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g2.color = CONTINUE_ICON_COLOR
                g2.stroke = BasicStroke(1.15f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.translate(x.toDouble(), y.toDouble())
                g2.drawLine(3, 4, 8, 8)
                g2.drawLine(8, 8, 3, 12)
                g2.drawLine(8, 4, 13, 8)
                g2.drawLine(13, 8, 8, 12)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = 16

        override fun getIconHeight(): Int = 16
    }

    private class ScaledIcon(
        private val delegate: Icon,
        private val scale: Float,
    ) : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.translate(x.toDouble(), y.toDouble())
                g2.scale(scale.toDouble(), scale.toDouble())
                delegate.paintIcon(c, g2, 0, 0)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = (delegate.iconWidth * scale).roundToInt()

        override fun getIconHeight(): Int = (delegate.iconHeight * scale).roundToInt()
    }

    companion object {
        private fun deriveSemiboldFont(base: Font, size: Float): Font {
            return base.deriveFont(
                mapOf(
                    TextAttribute.SIZE to size,
                    TextAttribute.WEIGHT to TextAttribute.WEIGHT_SEMIBOLD,
                ),
            )
        }

        private fun deriveTitleFont(base: Font, size: Float): Font {
            return base.deriveFont(
                mapOf(
                    TextAttribute.SIZE to size,
                    TextAttribute.WEIGHT to TextAttribute.WEIGHT_SEMIBOLD,
                    TextAttribute.TRACKING to 0.012f,
                ),
            )
        }

        private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())

        private val CARD_BG_DEFAULT = JBColor(Color(247, 249, 252), Color(55, 59, 67))
        private val CARD_BORDER_DEFAULT = JBColor(Color(219, 226, 238), Color(74, 80, 90))
        private val CARD_BG_SELECTED = JBColor(Color(221, 235, 254), Color(66, 84, 108))
        private val CARD_BORDER_SELECTED = JBColor(Color(123, 162, 224), Color(96, 127, 166))
        private val LIST_SURFACE_BG = JBColor(Color(244, 247, 253), Color(52, 57, 65))
        private val LIST_SURFACE_BORDER = JBColor(Color(210, 220, 236), Color(81, 91, 105))
        private val CARD_ACCENT_SPEC = JBColor(Color(89, 132, 208), Color(117, 145, 192))
        private val CARD_ACCENT_SPEC_SELECTED = JBColor(Color(63, 110, 196), Color(143, 173, 220))
        private val CARD_ACCENT_VIBE = JBColor(Color(106, 154, 115), Color(117, 153, 124))
        private val CARD_ACCENT_VIBE_SELECTED = JBColor(Color(73, 136, 83), Color(146, 185, 153))
        private val MODE_SPEC_TEXT = JBColor(Color(48, 92, 165), Color(185, 210, 244))
        private val MODE_VIBE_TEXT = JBColor(Color(47, 117, 63), Color(185, 228, 193))

        private val ACTION_ICON_HOVER_BG = JBColor(Color(231, 239, 252), Color(82, 96, 116))
        private val ACTION_ICON_HOVER_BORDER = JBColor(Color(158, 184, 226), Color(122, 145, 175))
        private val ACTION_ICON_HOVER_BG_SELECTED = JBColor(Color(206, 224, 251), Color(92, 112, 139))
        private val ACTION_ICON_HOVER_BORDER_SELECTED = JBColor(Color(120, 156, 219), Color(139, 167, 201))
        private val HISTORY_OPEN_ICON = IconLoader.getIcon("/icons/history-open-new-tab.svg", HistorySessionListPanel::class.java)
        private val CONTINUE_ICON_COLOR = JBColor(Color(69, 160, 96), Color(146, 224, 165))

        private const val ACTION_ICON_SCALE = 1.0f
        private val ACTION_HIT_SIZE = JBUI.scale(20)
        private val ACTION_ICON_GAP = JBUI.scale(5)
        private val ACTION_STRIP_LEFT_PADDING = JBUI.scale(4)
        private val ACTION_STRIP_RIGHT_PADDING = JBUI.scale(8)
        private val ACTION_ZONE_TOP = JBUI.scale(7)
        private val ACTION_STRIP_WIDTH = ACTION_STRIP_LEFT_PADDING +
            ACTION_STRIP_RIGHT_PADDING +
            RowAction.entries.size * ACTION_HIT_SIZE +
            (RowAction.entries.size - 1) * ACTION_ICON_GAP
    }
}
