package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.OperationModeManager
import com.eacape.speccodingplugin.window.WindowStateStore
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * 操作模式选择器面板
 * 显示当前操作模式并允许用户切换
 */
class OperationModeSelector(private val project: Project) : JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)) {

    private val modeManager = OperationModeManager.getInstance(project)
    private val windowStateStore = WindowStateStore.getInstance(project)
    private val label = JBLabel(SpecCodingBundle.message("operation.mode.label"))
    private val comboBox = ComboBox(OperationMode.values())

    init {
        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        label.text = localizedModeLabelText()
        label.font = JBUI.Fonts.smallFont()
        comboBox.font = JBUI.Fonts.smallFont()
        comboBox.putClientProperty("JComponent.roundRect", false)
        comboBox.putClientProperty("JComboBox.isBorderless", true)
        comboBox.putClientProperty("ComboBox.isBorderless", true)
        comboBox.putClientProperty("JComponent.outline", null)
        comboBox.background = JBColor(Color(248, 250, 252), Color(46, 50, 56))
        comboBox.border = JBUI.Borders.empty(0)
        comboBox.isOpaque = false
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = comboBox,
            minWidth = JBUI.scale(MODE_COMBO_MIN_WIDTH),
            maxWidth = JBUI.scale(MODE_COMBO_MAX_WIDTH),
            height = JBUI.scale(MODE_COMBO_HEIGHT),
        )
        add(label)
        add(comboBox)

        // 设置当前模式
        val persistedMode = windowStateStore.snapshot().operationMode
            ?.let { runCatching { OperationMode.valueOf(it) }.getOrNull() }
        val initialMode = persistedMode ?: modeManager.getCurrentMode()
        modeManager.switchMode(initialMode)
        comboBox.selectedItem = initialMode

        // 自定义渲染器显示模式名称和描述
        comboBox.renderer = OperationModeRenderer()
        updateComboWidth()
        installPopupWidthPolicy()
    }

    private fun installPopupWidthPolicy() {
        comboBox.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                SwingUtilities.invokeLater { ensurePopupMinWidth() }
            }

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) = Unit

            override fun popupMenuCanceled(e: PopupMenuEvent?) = Unit
        })
    }

    private fun ensurePopupMinWidth() {
        val popup = comboBox.accessibleContext
            ?.getAccessibleChild(0) as? JPopupMenu ?: return
        val comboWidth = comboBox.width.takeIf { it > 0 } ?: comboBox.preferredSize.width
        val minWidth = comboWidth + JBUI.scale(MODE_POPUP_MIN_EXTRA_WIDTH_PX)
        val targetWidth = computePopupTargetWidth(minWidth)
        val popupPreferred = popup.preferredSize
        if (popupPreferred.width != targetWidth) {
            val resized = Dimension(targetWidth, popupPreferred.height)
            popup.preferredSize = resized
            popup.minimumSize = resized
            popup.size = resized
        }
        val scrollPane = popup.components.firstOrNull { it is JScrollPane } as? JScrollPane ?: return
        val current = scrollPane.preferredSize
        if (current.width != targetWidth) {
            val resized = Dimension(targetWidth, current.height)
            scrollPane.preferredSize = resized
            scrollPane.minimumSize = resized
        }
        val list = scrollPane.viewport?.view as? JList<*> ?: return
        val listTargetWidth = (targetWidth - JBUI.scale(MODE_POPUP_LIST_WIDTH_OFFSET_PX)).coerceAtLeast(1)
        if (list.fixedCellWidth != listTargetWidth) {
            list.fixedCellWidth = listTargetWidth
            list.revalidate()
            list.repaint()
        }
    }

    private fun computePopupTargetWidth(minWidth: Int): Int {
        @Suppress("UNCHECKED_CAST")
        val renderer = comboBox.renderer as? ListCellRenderer<in OperationMode>
        val list = JList(OperationMode.values())
        list.font = comboBox.font ?: JBUI.Fonts.smallFont()
        val widestCell = OperationMode.entries.maxOf { mode ->
            val component = renderer?.getListCellRendererComponent(list, mode, 0, false, false)
                ?: return@maxOf minWidth
            component.preferredSize.width
        }
        val desired = widestCell + JBUI.scale(MODE_POPUP_OVERHEAD_WIDTH_PX)
        return desired
            .coerceAtLeast(minWidth)
            .coerceAtMost(JBUI.scale(MODE_POPUP_MAX_WIDTH))
    }

    private fun setupListeners() {
        comboBox.addActionListener {
            val selectedMode = comboBox.selectedItem as? OperationMode ?: return@addActionListener
            updateComboWidth()
            if (selectedMode != modeManager.getCurrentMode()) {
                modeManager.switchMode(selectedMode)
                windowStateStore.updateOperationMode(selectedMode.name)
                onModeChanged(selectedMode)
            }
        }
    }

    /**
     * 模式变更回调
     */
    private fun onModeChanged(mode: OperationMode) {
        // 可以在这里添加通知或其他 UI 更新
        // 例如显示模式切换的提示信息
    }

    /**
     * 获取当前选中的模式
     */
    fun getSelectedMode(): OperationMode {
        return comboBox.selectedItem as? OperationMode ?: OperationMode.DEFAULT
    }

    /**
     * 设置选中的模式
     */
    fun setSelectedMode(mode: OperationMode) {
        comboBox.selectedItem = mode
        updateComboWidth()
        windowStateStore.updateOperationMode(mode.name)
    }

    /**
     * 刷新显示
     */
    fun refresh() {
        comboBox.selectedItem = modeManager.getCurrentMode()
        updateComboWidth()
        refreshLocalizedTexts()
    }

    fun refreshLocalizedTexts() {
        label.text = localizedModeLabelText()
        updateComboWidth()
        comboBox.repaint()
        revalidate()
        repaint()
    }

    private fun updateComboWidth() {
        ComboBoxAutoWidthSupport.refreshSelectedItemAutoWidth(comboBox)
    }

    private fun localizedModeLabelText(): String {
        return SpecCodingBundle.message("operation.mode.label")
            .trim()
            .trimEnd(':', '：')
    }

    companion object {
        private const val MODE_COMBO_MIN_WIDTH = 76
        private const val MODE_COMBO_MAX_WIDTH = 180
        private const val MODE_COMBO_HEIGHT = 28
        private const val MODE_POPUP_MIN_EXTRA_WIDTH_PX = 14
        private const val MODE_POPUP_MAX_WIDTH = 340
        private const val MODE_POPUP_OVERHEAD_WIDTH_PX = 10
        private const val MODE_POPUP_LIST_WIDTH_OFFSET_PX = 8
    }
}

/**
 * 操作模式渲染器
 */
private class OperationModeRenderer : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val label = component as? JLabel ?: return component

        if (value is OperationMode) {
            val title = modeTitle(value)
            val description = modeDescription(value)
            label.icon = getModeIcon(value)
            label.iconTextGap = JBUI.scale(6)
            label.border = if (index == -1) {
                JBUI.Borders.empty(1, 4)
            } else {
                JBUI.Borders.empty(4, 6, 4, 6)
            }
            label.text = if (index == -1) {
                title
            } else {
                buildPopupText(title, description)
            }
            label.toolTipText = description
        }

        return component
    }

    private fun modeTitle(mode: OperationMode): String {
        return when (mode) {
            OperationMode.DEFAULT -> SpecCodingBundle.message("operation.mode.default.title")
            OperationMode.PLAN -> SpecCodingBundle.message("operation.mode.plan.title")
            OperationMode.AGENT -> SpecCodingBundle.message("operation.mode.agent.title")
            OperationMode.AUTO -> SpecCodingBundle.message("operation.mode.auto.title")
        }
    }

    private fun modeDescription(mode: OperationMode): String {
        return when (mode) {
            OperationMode.DEFAULT -> SpecCodingBundle.message("operation.mode.default.description")
            OperationMode.PLAN -> SpecCodingBundle.message("operation.mode.plan.description")
            OperationMode.AGENT -> SpecCodingBundle.message("operation.mode.agent.description")
            OperationMode.AUTO -> SpecCodingBundle.message("operation.mode.auto.description")
        }
    }

    private fun buildPopupText(title: String, description: String): String {
        return "<html><b>${escapeHtml(title)}</b><br><i>${escapeHtml(description)}</i></html>"
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun getModeIcon(mode: OperationMode): Icon {
        return when (mode) {
            OperationMode.DEFAULT -> MODE_DEFAULT_ICON
            OperationMode.PLAN -> MODE_PLAN_ICON
            OperationMode.AGENT -> MODE_AGENT_ICON
            OperationMode.AUTO -> MODE_AUTO_ICON
        }
    }

    companion object {
        private val MODE_DEFAULT_ICON = IconLoader.getIcon("/icons/mode-default.svg", OperationModeSelector::class.java)
        private val MODE_PLAN_ICON = IconLoader.getIcon("/icons/mode-plan.svg", OperationModeSelector::class.java)
        private val MODE_AGENT_ICON = IconLoader.getIcon("/icons/mode-agent.svg", OperationModeSelector::class.java)
        private val MODE_AUTO_ICON = IconLoader.getIcon("/icons/mode-auto.svg", OperationModeSelector::class.java)
    }
}
