package com.eacape.speccodingplugin.ui.prompt

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.prompt.PromptScope
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities

/**
 * Prompt 列表项渲染器
 */
class PromptListCellRenderer : ListCellRenderer<PromptTemplate> {
    enum class RowAction {
        EDIT,
        DELETE,
    }

    private val rowPanel = JPanel(BorderLayout())
    private val cardPanel = RoundedCardPanel(JBUI.scale(12))
    private val topRow = JPanel(BorderLayout(6, 0))
    private val nameLabel = JLabel()
    private val scopeLabel = JLabel()
    private val tagsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
    private val rightPanel = JPanel(BorderLayout(0, 0))
    private val actionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, ACTION_ICON_GAP, 0))
    private val editActionLabel = JLabel(AllIcons.Actions.Edit)
    private val deleteActionLabel = JLabel(AllIcons.Actions.GC)

    init {
        rowPanel.isOpaque = false
        rowPanel.border = JBUI.Borders.empty(0, 0, 6, 0)

        cardPanel.layout = BorderLayout()
        cardPanel.border = JBUI.Borders.empty(8, 10)

        topRow.isOpaque = false
        nameLabel.font = nameLabel.font.deriveFont(Font.BOLD, 12.5f)

        scopeLabel.font = scopeLabel.font.deriveFont(Font.BOLD, 10f)
        scopeLabel.border = JBUI.Borders.empty(1, 6, 1, 6)
        scopeLabel.isOpaque = true

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(nameLabel)
            add(scopeLabel)
        }

        tagsPanel.isOpaque = false
        rightPanel.isOpaque = false
        actionPanel.isOpaque = false
        actionPanel.border = JBUI.Borders.emptyRight(ACTION_RIGHT_PAD)
        editActionLabel.preferredSize = JBUI.size(ACTION_ICON_SIZE, ACTION_ICON_SIZE)
        editActionLabel.minimumSize = editActionLabel.preferredSize
        editActionLabel.isOpaque = false
        deleteActionLabel.preferredSize = JBUI.size(ACTION_ICON_SIZE, ACTION_ICON_SIZE)
        deleteActionLabel.minimumSize = deleteActionLabel.preferredSize
        deleteActionLabel.isOpaque = false
        editActionLabel.toolTipText = SpecCodingBundle.message("prompt.manager.edit")
        deleteActionLabel.toolTipText = SpecCodingBundle.message("prompt.manager.delete")
        actionPanel.add(editActionLabel)
        actionPanel.add(deleteActionLabel)
        rightPanel.add(tagsPanel, BorderLayout.CENTER)
        rightPanel.add(actionPanel, BorderLayout.EAST)

        topRow.add(left, BorderLayout.CENTER)
        topRow.add(rightPanel, BorderLayout.EAST)

        cardPanel.add(topRow, BorderLayout.CENTER)
        rowPanel.add(cardPanel, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(
        list: JList<out PromptTemplate>,
        value: PromptTemplate?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        if (value == null) {
            nameLabel.text = ""
            scopeLabel.text = ""
            tagsPanel.removeAll()
            return rowPanel
        }

        nameLabel.text = value.name
        val scopeName = SpecCodingBundle.messageOrDefault("prompt.scope.${value.scope.name.lowercase()}", value.scope.name)
        scopeLabel.text = scopeName
        editActionLabel.toolTipText = SpecCodingBundle.message("prompt.manager.edit")
        deleteActionLabel.toolTipText = SpecCodingBundle.message("prompt.manager.delete")
        tagsPanel.removeAll()
        value.tags.take(3).forEach { tag ->
            val tagLabel = JLabel(tag)
            tagLabel.font = tagLabel.font.deriveFont(10f)
            tagLabel.foreground = TAG_TEXT_FG
            tagsPanel.add(tagLabel)
        }
        editActionLabel.isEnabled = true
        deleteActionLabel.isEnabled = value.scope == PromptScope.PROJECT

        val cardBackground = if (isSelected) CARD_BG_SELECTED else CARD_BG_DEFAULT
        val cardBorder = if (isSelected) CARD_BORDER_SELECTED else CARD_BORDER_DEFAULT
        val titleColor = if (isSelected) TITLE_FG_SELECTED else TITLE_FG_DEFAULT
        val scopeBg = if (isSelected) SCOPE_BADGE_BG_SELECTED else SCOPE_BADGE_BG
        val scopeFg = if (isSelected) SCOPE_BADGE_FG_SELECTED else SCOPE_BADGE_FG

        cardPanel.updateColors(cardBackground, cardBorder)
        nameLabel.foreground = titleColor
        scopeLabel.background = scopeBg
        scopeLabel.foreground = scopeFg
        editActionLabel.icon = if (isSelected) AllIcons.Actions.EditSource else AllIcons.Actions.Edit
        deleteActionLabel.icon = AllIcons.Actions.GC
        editActionLabel.border = JBUI.Borders.empty()
        deleteActionLabel.border = JBUI.Borders.empty()
        return rowPanel
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

    companion object {
        private val ACTION_ICON_SIZE = JBUI.scale(16)
        private val ACTION_ICON_GAP = JBUI.scale(6)
        private val ACTION_RIGHT_PAD = JBUI.scale(10)
        private val ACTION_HIT_SLOP = JBUI.scale(3)

        private val CARD_BG_DEFAULT = JBColor(Color(245, 249, 255), Color(58, 64, 74))
        private val CARD_BORDER_DEFAULT = JBColor(Color(202, 214, 234), Color(91, 101, 117))
        private val CARD_BG_SELECTED = JBColor(Color(224, 237, 255), Color(74, 86, 103))
        private val CARD_BORDER_SELECTED = JBColor(Color(145, 175, 219), Color(120, 140, 168))
        private val TITLE_FG_DEFAULT = JBColor(Color(22, 24, 29), Color(224, 224, 224))
        private val TITLE_FG_SELECTED = JBColor(Color(25, 41, 64), Color(224, 234, 247))
        private val SCOPE_BADGE_BG = JBColor(Color(232, 244, 233), Color(74, 107, 81))
        private val SCOPE_BADGE_BG_SELECTED = JBColor(Color(202, 221, 248), Color(88, 109, 142))
        private val SCOPE_BADGE_FG = JBColor(Color(44, 96, 56), Color(204, 236, 210))
        private val SCOPE_BADGE_FG_SELECTED = JBColor(Color(37, 70, 118), Color(214, 227, 246))
        private val TAG_TEXT_FG = JBColor(Color(88, 112, 144), Color(169, 196, 229))
    }

    fun resolveRowAction(
        list: JList<out PromptTemplate>,
        index: Int,
        point: Point,
    ): RowAction? {
        val cellBounds = list.getCellBounds(index, index) ?: return null
        if (!cellBounds.contains(point)) {
            return null
        }

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
        val deleteRect = SwingUtilities.convertRectangle(
            deleteActionLabel.parent,
            deleteActionLabel.bounds,
            rendererComponent,
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
            listDeleteRect.contains(point) -> RowAction.DELETE
            listEditRect.contains(point) -> RowAction.EDIT
            else -> null
        }
    }

    private fun layoutRecursively(component: Component) {
        if (component !is Container) {
            return
        }
        component.doLayout()
        component.components.forEach(::layoutRecursively)
    }
}
