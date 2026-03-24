package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.prompt.PromptTemplate
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

internal class PromptTemplateCellRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ) = super.getListCellRendererComponent(
        list,
        (value as? PromptTemplate)?.name ?: "",
        index,
        isSelected,
        cellHasFocus,
    )
}
