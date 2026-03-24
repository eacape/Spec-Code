package com.eacape.speccodingplugin.ui.input

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class ImageAttachmentPreviewPanel(
    private val onRemove: (String) -> Unit = {},
    private val onStateChanged: () -> Unit = {},
) : JPanel(FlowLayout(FlowLayout.LEFT, 3, 1)) {

    private val imagePaths = mutableListOf<String>()

    init {
        isOpaque = false
        border = JBUI.Borders.empty(1, 0)
        isVisible = false
    }

    fun addImagePaths(paths: List<String>) {
        var changed = false
        paths.forEach { path ->
            val normalized = path.trim()
            if (normalized.isNotBlank() && imagePaths.none { it.equals(normalized, ignoreCase = true) }) {
                imagePaths += normalized
                changed = true
            }
        }
        if (changed) {
            rebuildUi()
        }
    }

    fun getImagePaths(): List<String> = imagePaths.toList()

    fun clear() {
        if (imagePaths.isEmpty()) {
            return
        }
        imagePaths.clear()
        rebuildUi()
    }

    private fun removeImagePath(path: String) {
        val removed = imagePaths.removeIf { it.equals(path, ignoreCase = true) }
        if (!removed) {
            return
        }
        rebuildUi()
        onRemove(path)
    }

    private fun rebuildUi() {
        removeAll()
        if (imagePaths.isEmpty()) {
            isVisible = false
            revalidate()
            repaint()
            onStateChanged()
            return
        }

        isVisible = true
        imagePaths.forEachIndexed { index, path ->
            add(createChip(path, index))
        }
        revalidate()
        repaint()
        onStateChanged()
    }

    private fun createChip(path: String, index: Int): JPanel {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 1, 0))
        chip.isOpaque = true
        chip.background = JBColor(0xEAF2FF, 0x3A4350)
        chip.border = JBUI.Borders.empty(1, 4)

        val alias = "image#${index + 1}"
        val label = JBLabel(alias, AllIcons.FileTypes.Image, JBLabel.LEADING)
        label.font = JBUI.Fonts.miniFont()
        label.iconTextGap = JBUI.scale(2)
        label.toolTipText = path
        chip.add(label)

        val removeButton = JButton("x")
        removeButton.font = removeButton.font.deriveFont(10f)
        removeButton.foreground = JBColor(0x6D778A, 0xA8B1C4)
        removeButton.isBorderPainted = false
        removeButton.isContentAreaFilled = false
        removeButton.isFocusPainted = false
        removeButton.margin = JBUI.emptyInsets()
        val removeSize = JBUI.scale(14)
        val removeDimension = Dimension(removeSize, removeSize)
        removeButton.preferredSize = removeDimension
        removeButton.minimumSize = removeDimension
        removeButton.maximumSize = removeDimension
        removeButton.toolTipText = SpecCodingBundle.message("toolwindow.image.attach.remove.tooltip")
        removeButton.addActionListener { removeImagePath(path) }
        chip.add(removeButton)

        return chip
    }
}
