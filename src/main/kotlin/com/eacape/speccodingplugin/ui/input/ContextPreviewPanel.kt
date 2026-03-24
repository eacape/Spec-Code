package com.eacape.speccodingplugin.ui.input

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class ContextPreviewPanel(
    project: Project,
    private val onRemove: (ContextItem) -> Unit = {},
    private val onStateChanged: () -> Unit = {},
) : JPanel(FlowLayout(FlowLayout.LEFT, 3, 1)), Disposable {

    private val items = mutableListOf<ContextItem>()

    init {
        isOpaque = false
        border = JBUI.Borders.empty(1, 0)
        isVisible = false

        ApplicationManager.getApplication()?.messageBus?.connect(this)?.subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    rebuildUi()
                }
            },
        )
    }

    fun addItem(item: ContextItem) {
        if (items.any { it.type == item.type && it.filePath == item.filePath && it.label == item.label }) {
            return
        }
        items += item
        rebuildUi()
    }

    fun removeItem(item: ContextItem) {
        if (items.remove(item)) {
            rebuildUi()
        }
    }

    fun getItems(): List<ContextItem> = items.toList()

    fun clear() {
        if (items.isEmpty()) {
            return
        }
        items.clear()
        rebuildUi()
    }

    private fun rebuildUi() {
        removeAll()
        if (items.isEmpty()) {
            isVisible = false
            revalidate()
            repaint()
            onStateChanged()
            return
        }

        isVisible = true
        items.forEach { item ->
            add(createItemChip(item))
        }
        revalidate()
        repaint()
        onStateChanged()
    }

    private fun createItemChip(item: ContextItem): JPanel {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 1, 0))
        chip.isOpaque = true
        chip.background = JBColor(Color(0xEDF4FF), Color(0x39424F))
        chip.border = JBUI.Borders.empty(1, 4)

        val tooltip = buildItemTooltip(item)
        chip.toolTipText = tooltip

        val label = JBLabel(truncateTail(resolveDisplayName(item), MAX_LABEL_LENGTH), AllIcons.FileTypes.Text, JBLabel.LEADING)
        label.font = JBUI.Fonts.miniFont()
        label.foreground = JBColor(Color(0x35506F), Color(0xD5E4F5))
        label.iconTextGap = JBUI.scale(2)
        label.toolTipText = tooltip
        chip.add(label)

        val removeButton = JButton("x")
        removeButton.font = removeButton.font.deriveFont(10f)
        removeButton.foreground = JBColor(Color(0x6D778A), Color(0xA8B1C4))
        removeButton.isBorderPainted = false
        removeButton.isContentAreaFilled = false
        removeButton.isFocusPainted = false
        removeButton.margin = JBUI.emptyInsets()
        removeButton.toolTipText = SpecCodingBundle.message("context.chip.removeTooltip")
        val removeSize = JBUI.scale(14)
        val removeDimension = Dimension(removeSize, removeSize)
        removeButton.preferredSize = removeDimension
        removeButton.minimumSize = removeDimension
        removeButton.maximumSize = removeDimension
        removeButton.addActionListener {
            removeItem(item)
            onRemove(item)
        }
        chip.add(removeButton)

        return chip
    }

    private fun buildItemTooltip(item: ContextItem): String {
        val displayPath = item.filePath?.takeIf { it.isNotBlank() } ?: item.label
        return buildString {
            append("<html>")
            append(escapeHtml(displayPath))
            append("</html>")
        }
    }

    private fun resolveDisplayName(item: ContextItem): String {
        val candidate = item.filePath
            ?.replace('\\', '/')
            ?.trimEnd('/')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        return candidate ?: item.label
    }

    private fun truncateTail(value: String, maxLength: Int): String {
        if (value.length <= maxLength) {
            return value
        }
        return value.take((maxLength - ELLIPSIS.length).coerceAtLeast(0)) + ELLIPSIS
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    override fun dispose() = Unit

    companion object {
        private const val MAX_LABEL_LENGTH = 24
        private const val ELLIPSIS = "..."
    }
}
