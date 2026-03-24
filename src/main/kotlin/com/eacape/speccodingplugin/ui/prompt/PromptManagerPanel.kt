package com.eacape.speccodingplugin.ui.prompt

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.prompt.PromptManager
import com.eacape.speccodingplugin.prompt.PromptScope
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.border.AbstractBorder
import javax.swing.border.CompoundBorder

/**
 * Prompt 管理面板
 */
class PromptManagerPanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {

    private val promptManager = PromptManager.getInstance(project)
    private val listModel = DefaultListModel<PromptTemplate>()
    private val promptList = JBList(listModel)
    private val promptListCellRenderer = PromptListCellRenderer()
    private val newBtn = JButton()

    @Volatile
    private var isDisposed = false

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8)
        setupUI()
        subscribeToLocaleEvents()
        refreshLocalizedTexts()
        refresh()
    }

    private fun setupUI() {
        styleActionButton(newBtn)

        promptList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        promptList.cellRenderer = promptListCellRenderer
        promptList.isOpaque = false
        promptList.background = LIST_SECTION_BG
        promptList.fixedCellHeight = -1
        promptList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    handleListClick(e.point, e.clickCount)
                }

                override fun mouseExited(e: MouseEvent) {
                    promptList.cursor = Cursor.getDefaultCursor()
                }
            },
        )
        promptList.addMouseMotionListener(
            object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    updateListCursor(e.point)
                }
            },
        )

        val scrollPane = JBScrollPane(promptList).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            viewport.background = LIST_SECTION_BG
            isOpaque = false
        }

        val listCard = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = LIST_SECTION_BG
            border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedCardBorder(
                    lineColor = LIST_SECTION_BORDER,
                    arc = JBUI.scale(12),
                    top = 1,
                    left = 1,
                    bottom = 1,
                    right = 1,
                ),
                JBUI.Borders.empty(2),
            )
            add(scrollPane, BorderLayout.CENTER)
        }

        newBtn.addActionListener { onNew() }

        add(listCard, BorderLayout.CENTER)
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(8)
                add(newBtn, BorderLayout.CENTER)
            },
            BorderLayout.SOUTH,
        )
    }

    fun refresh() {
        listModel.clear()
        promptManager.listPromptTemplates().forEach { listModel.addElement(it) }
    }

    private fun refreshLocalizedTexts() {
        newBtn.text = SpecCodingBundle.message("prompt.manager.newPrompt")
        updateActionButtonSize(newBtn)
        revalidate()
        promptList.repaint()
    }

    private fun subscribeToLocaleEvents() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    invokeLaterSafe {
                        refreshLocalizedTexts()
                        refresh()
                    }
                }
            },
        )
    }

    private fun updateListCursor(point: Point) {
        val index = promptList.locationToIndex(point)
        if (index < 0) {
            promptList.cursor = Cursor.getDefaultCursor()
            return
        }
        val cellBounds = promptList.getCellBounds(index, index) ?: run {
            promptList.cursor = Cursor.getDefaultCursor()
            return
        }
        val selected = listModel.getElementAt(index)
        val action = promptListCellRenderer.resolveRowAction(promptList, index, point)
        val clickable = when (action) {
            PromptListCellRenderer.RowAction.DELETE -> selected.scope == PromptScope.PROJECT
            PromptListCellRenderer.RowAction.EDIT -> true
            null -> false
        }
        promptList.cursor = if (clickable) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private fun handleListClick(point: Point, clickCount: Int) {
        val index = promptList.locationToIndex(point)
        if (index < 0) {
            return
        }
        val cellBounds = promptList.getCellBounds(index, index) ?: return
        if (!cellBounds.contains(point)) {
            return
        }
        val selected = listModel.getElementAt(index)
        promptList.selectedIndex = index
        when (promptListCellRenderer.resolveRowAction(promptList, index, point)) {
            PromptListCellRenderer.RowAction.EDIT -> onEdit(selected)
            PromptListCellRenderer.RowAction.DELETE -> if (selected.scope == PromptScope.PROJECT) onDelete(selected)
            null -> if (clickCount >= 2) onEdit(selected)
        }
    }

    private fun styleActionButton(button: JButton) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.font = button.font.deriveFont(java.awt.Font.BOLD, 11f)
        button.margin = JBUI.emptyInsets()
        button.isOpaque = true
        button.isContentAreaFilled = true
        button.background = ACTION_BUTTON_BG
        button.foreground = ACTION_BUTTON_FG
        button.border = CompoundBorder(
            RoundedLineBorder(
                lineColor = ACTION_BUTTON_BORDER,
                arc = JBUI.scale(12),
            ),
            JBUI.Borders.empty(3, 10, 3, 10),
        )
        button.putClientProperty("JButton.buttonType", "roundRect")
        button.putClientProperty("JComponent.roundRectArc", JBUI.scale(12))
        updateActionButtonSize(button)
    }

    private fun updateActionButtonSize(button: JButton) {
        val textWidth = button.getFontMetrics(button.font).stringWidth(button.text ?: "")
        val insets = button.insets
        val width = maxOf(
            textWidth + insets.left + insets.right + JBUI.scale(14),
            JBUI.scale(64),
        )
        button.preferredSize = JBUI.size(width, JBUI.scale(28))
        button.minimumSize = button.preferredSize
    }

    private fun onNew() {
        val existingIds = promptManager.listPromptTemplates().map { it.id }.toSet()
        val dialog = PromptEditorDialog(
            project = project,
            existingPromptIds = existingIds,
        )
        if (dialog.showAndGet()) {
            val template = dialog.result ?: return
            promptManager.upsertTemplate(template)
            refresh()
        }
    }

    private fun onEdit(template: PromptTemplate? = promptList.selectedValue) {
        val selected = template ?: return
        val existingIds = promptManager.listPromptTemplates().map { it.id }.toSet()
        val dialog = PromptEditorDialog(
            project = project,
            existing = selected,
            existingPromptIds = existingIds,
        )
        if (dialog.showAndGet()) {
            val updated = dialog.result ?: return
            promptManager.upsertTemplate(updated)
            refresh()
        }
    }

    private fun onDelete(template: PromptTemplate? = promptList.selectedValue) {
        val selected = template ?: return
        promptManager.deleteTemplate(selected.id)
        refresh()
    }

    private fun invokeLaterSafe(action: () -> Unit) {
        if (isDisposed) {
            return
        }
        ApplicationManager.getApplication().invokeLater {
            if (!isDisposed && !project.isDisposed) {
                action()
            }
        }
    }

    override fun dispose() {
        isDisposed = true
    }

    private class RoundedLineBorder(
        private val lineColor: Color,
        private val arc: Int,
        private val thickness: Int = 1,
    ) : AbstractBorder() {
        override fun paintBorder(
            c: java.awt.Component?,
            g: Graphics?,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            val graphics = g as? Graphics2D ?: return
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g2.color = lineColor
                val safeThickness = thickness.coerceAtLeast(1)
                repeat(safeThickness) { index ->
                    val offset = index + 0.5f
                    val drawWidth = width - index * 2 - 1f
                    val drawHeight = height - index * 2 - 1f
                    if (drawWidth <= 0f || drawHeight <= 0f) return@repeat
                    val arcSize = (arc - index * 2).coerceAtLeast(2).toFloat()
                    g2.draw(
                        RoundRectangle2D.Float(
                            x + offset,
                            y + offset,
                            drawWidth,
                            drawHeight,
                            arcSize,
                            arcSize,
                        ),
                    )
                }
            } finally {
                g2.dispose()
            }
        }

        override fun getBorderInsets(c: java.awt.Component?): Insets = Insets(
            thickness,
            thickness,
            thickness,
            thickness,
        )

        override fun getBorderInsets(c: java.awt.Component?, insets: Insets): Insets {
            insets.set(
                thickness,
                thickness,
                thickness,
                thickness,
            )
            return insets
        }
    }

    companion object {
        private val LIST_SECTION_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val LIST_SECTION_BORDER = JBColor(Color(204, 215, 233), Color(84, 92, 105))
        private val ACTION_BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val ACTION_BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val ACTION_BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
    }
}
