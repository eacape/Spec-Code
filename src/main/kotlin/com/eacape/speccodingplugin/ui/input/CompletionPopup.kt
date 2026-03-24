package com.eacape.speccodingplugin.ui.input

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.ui.completion.CompletionItem
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import java.awt.Component
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.ListSelectionModel

/**
 * 补全弹窗
 * 使用 JBPopupFactory 实现原生风格的补全列表
 */
class CompletionPopup(
    private val onSelect: (CompletionItem) -> Unit,
) {
    private val listModel = DefaultListModel<CompletionItem>()
    private val list = JBList(listModel)
    private var popup: JBPopup? = null

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = CompletionCellRenderer()
    }

    fun show(items: List<CompletionItem>, owner: Component) {
        hide()

        if (items.isEmpty()) return

        listModel.clear()
        items.forEach { listModel.addElement(it) }
        list.selectedIndex = 0

        @Suppress("DEPRECATION")
        popup = JBPopupFactory.getInstance()
            .createListPopupBuilder(list)
            .setRequestFocus(false)
            .setItemChosenCallback { item ->
                if (item is CompletionItem) {
                    onSelect(item)
                }
            }
            .createPopup()

        popup?.showUnderneathOf(owner)
    }

    fun hide() {
        popup?.cancel()
        popup = null
    }

    val isVisible: Boolean
        get() = popup?.isVisible == true

    fun moveUp() {
        val idx = list.selectedIndex
        if (idx > 0) {
            list.selectedIndex = idx - 1
            list.ensureIndexIsVisible(idx - 1)
        }
    }

    fun moveDown() {
        val idx = list.selectedIndex
        if (idx < listModel.size - 1) {
            list.selectedIndex = idx + 1
            list.ensureIndexIsVisible(idx + 1)
        }
    }

    fun confirmSelection(): Boolean {
        val selected = list.selectedValue ?: return false
        onSelect(selected)
        hide()
        return true
    }
}

private class CompletionCellRenderer : ColoredListCellRenderer<CompletionItem>() {
    override fun customizeCellRenderer(
        list: JList<out CompletionItem>,
        value: CompletionItem,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        icon = value.icon
        append(value.displayText, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        if (value.description.isNotBlank()) {
            append(
                SpecCodingBundle.message("input.completion.item.description", value.description),
                SimpleTextAttributes.GRAYED_ATTRIBUTES,
            )
        }
    }
}
