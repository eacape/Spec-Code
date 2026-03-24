package com.eacape.speccodingplugin.ui.chat

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.JViewport

/**
 * 消息列表面板
 * 管理多条 ChatMessagePanel 的容器
 */
class ChatMessagesListPanel(
    private val maxVisibleMessages: Int = DEFAULT_MAX_VISIBLE_MESSAGES,
) : JPanel(), Scrollable {

    private val messages = mutableListOf<ChatMessagePanel>()
    private val scrollPane: JBScrollPane
    private val scrollUnitIncrementPx = JBUI.scale(DEFAULT_SCROLL_UNIT_INCREMENT)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)

        scrollPane = JBScrollPane(this)
        scrollPane.border = JBUI.Borders.empty()
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.verticalScrollBar.unitIncrement = scrollUnitIncrementPx
        scrollPane.verticalScrollBar.blockIncrement = JBUI.scale(DEFAULT_SCROLL_BLOCK_INCREMENT)
        scrollPane.verticalScrollBar.putClientProperty("JScrollBar.fastWheelScrolling", true)
        scrollPane.viewport.scrollMode = JViewport.BLIT_SCROLL_MODE
        scrollPane.putClientProperty("JScrollPane.smoothScrolling", true)
    }

    fun getScrollPane(): JBScrollPane = scrollPane

    /**
     * 添加消息
     */
    fun addMessage(panel: ChatMessagePanel): ChatMessagePanel {
        messages.add(panel)
        add(panel)
        trimIfNeeded()
        applyMessageCompaction()
        revalidate()
        scrollToBottom()
        return panel
    }

    /**
     * 获取最后一条消息
     */
    fun getLastMessage(): ChatMessagePanel? = messages.lastOrNull()

    /**
     * 清空所有消息
     */
    fun clearAll() {
        messages.clear()
        removeAll()
        revalidate()
        repaint()
    }

    /**
     * 获取消息数量
     */
    fun messageCount(): Int = messages.size

    /**
     * 删除指定消息
     */
    fun removeMessage(panel: ChatMessagePanel) {
        messages.remove(panel)
        remove(panel)
        applyMessageCompaction()
        revalidate()
        repaint()
    }

    /**
     * 获取所有消息
     */
    fun getAllMessages(): List<ChatMessagePanel> = messages.toList()

    /**
     * 滚动到底部
     */
    fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    fun isNearBottom(thresholdPx: Int = DEFAULT_NEAR_BOTTOM_THRESHOLD_PX): Boolean {
        val bar = scrollPane.verticalScrollBar
        val remaining = bar.maximum - (bar.value + bar.visibleAmount)
        return remaining <= thresholdPx
    }

    private fun trimIfNeeded() {
        if (messages.size <= maxVisibleMessages) {
            return
        }
        val overflow = messages.size - maxVisibleMessages
        repeat(overflow) {
            val removed = messages.removeAt(0)
            remove(removed)
        }
    }

    private fun applyMessageCompaction() {
        val keepFullStart = (messages.size - DEFAULT_MAX_FULL_RENDER_MESSAGES).coerceAtLeast(0)
        messages.forEachIndexed { index, panel ->
            panel.setLightweightMode(index < keepFullStart)
        }
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int {
        return scrollUnitIncrementPx
    }

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int {
        return (visibleRect.height - scrollUnitIncrementPx).coerceAtLeast(scrollUnitIncrementPx)
    }

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false

    companion object {
        private const val DEFAULT_MAX_VISIBLE_MESSAGES = 240
        private const val DEFAULT_MAX_FULL_RENDER_MESSAGES = 60
        private const val DEFAULT_NEAR_BOTTOM_THRESHOLD_PX = 80
        private const val DEFAULT_SCROLL_UNIT_INCREMENT = 28
        private const val DEFAULT_SCROLL_BLOCK_INCREMENT = 112
    }
}
