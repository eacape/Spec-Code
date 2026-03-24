package com.eacape.speccodingplugin.ui.history

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.session.ConversationMessage
import com.eacape.speccodingplugin.session.ConversationRole
import com.eacape.speccodingplugin.ui.chat.MarkdownRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.JPanel
import javax.swing.JTextPane

class HistoryDetailPanel : javax.swing.JPanel(BorderLayout()) {

    private val emptyLabel = JBLabel(SpecCodingBundle.message("history.empty.select"), SwingConstants.CENTER)
    private val markdownPane = JTextPane()
    private val markdownScrollPane = JScrollPane(markdownPane).apply {
        border = JBUI.Borders.empty()
    }
    private val messagesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(10)
    }
    private val messageScrollPane = JScrollPane(messagesPanel).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }
    private var displayedText: String = ""
    private var currentMessages: List<ConversationMessage> = emptyList()
    private var lastViewportWidth: Int = -1

    init {
        border = JBUI.Borders.empty(8)
        markdownPane.isEditable = false
        markdownPane.isOpaque = false
        markdownPane.border = JBUI.Borders.empty(4)
        messageScrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                if (currentMessages.isEmpty()) return
                val width = messageScrollPane.viewport.width
                if (width <= 0 || width == lastViewportWidth) return
                lastViewportWidth = width
                renderMessages(currentMessages, scrollToTop = false)
            }
        })
        showEmpty()
    }

    fun showMessages(messages: List<ConversationMessage>) {
        currentMessages = messages
        renderMessages(messages, scrollToTop = true)
    }

    private fun renderMessages(messages: List<ConversationMessage>, scrollToTop: Boolean) {
        val displayMessages = if (messages.size > MAX_RENDER_MESSAGES) {
            messages.takeLast(MAX_RENDER_MESSAGES)
        } else {
            messages
        }
        displayedText = buildDisplayedText(messages, displayMessages)
        val bubbleWidth = currentBubbleWidth()
        val textWidth = currentTextWidth(bubbleWidth)
        val scrollBar = messageScrollPane.verticalScrollBar
        val oldScrollableRange = (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(0)
        val oldRatio = if (oldScrollableRange > 0) {
            scrollBar.value.toDouble() / oldScrollableRange.toDouble()
        } else {
            0.0
        }

        removeAll()
        messagesPanel.removeAll()
        if (displayMessages.size < messages.size) {
            messagesPanel.add(createTruncationNotice(displayMessages.size, messages.size))
            messagesPanel.add(Box.createVerticalStrut(8))
        }
        displayMessages.forEachIndexed { index, message ->
            messagesPanel.add(createMessageRow(message, bubbleWidth, textWidth))
            if (index != displayMessages.lastIndex) {
                messagesPanel.add(Box.createVerticalStrut(8))
            }
        }

        add(messageScrollPane, BorderLayout.CENTER)
        revalidate()
        repaint()
        SwingUtilities.invokeLater {
            val bar = messageScrollPane.verticalScrollBar
            if (scrollToTop) {
                bar.value = 0
            } else {
                val newScrollableRange = (bar.maximum - bar.visibleAmount).coerceAtLeast(0)
                val restored = if (newScrollableRange > 0) {
                    (oldRatio * newScrollableRange.toDouble()).toInt().coerceIn(0, newScrollableRange)
                } else {
                    0
                }
                bar.value = restored
            }
        }
    }

    fun showText(content: String) {
        showMarkdown(content.trim())
    }

    private fun showMarkdown(content: String) {
        removeAll()
        displayedText = content
        MarkdownRenderer.render(markdownPane, content)
        markdownPane.caretPosition = 0
        add(markdownScrollPane, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    fun showEmpty() {
        removeAll()
        displayedText = ""
        add(emptyLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    internal fun displayedTextForTest(): String = displayedText

    internal fun isShowingEmptyForTest(): Boolean = components.contains(emptyLabel)

    private fun formatTimestamp(timestamp: Long): String {
        return timestampFormatter.format(Instant.ofEpochMilli(timestamp))
    }

    private fun buildDisplayedText(
        allMessages: List<ConversationMessage>,
        displayMessages: List<ConversationMessage>,
    ): String {
        return buildString {
            if (displayMessages.size < allMessages.size) {
                appendLine(
                    SpecCodingBundle.message("history.detail.truncated", displayMessages.size, allMessages.size)
                )
                appendLine()
            }
            displayMessages.forEachIndexed { index, message ->
                appendLine("${roleText(message.role)} · ${formatTimestamp(message.createdAt)}")
                appendLine(message.content.trimEnd())
                if (index != displayMessages.lastIndex) {
                    appendLine()
                }
            }
        }.trim()
    }

    private fun createTruncationNotice(shown: Int, total: Int): JPanel {
        val noticeLabel = JBLabel(
            SpecCodingBundle.message("history.detail.truncated", shown, total),
            SwingConstants.LEFT,
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor(Color(99, 107, 120), Color(168, 176, 190))
        }

        val notice = RoundedBubblePanel(JBUI.scale(10)).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(5, 8)
            updateColors(
                JBColor(Color(243, 246, 251), Color(70, 76, 87)),
                JBColor(Color(209, 218, 232), Color(92, 102, 118)),
            )
            add(noticeLabel, BorderLayout.CENTER)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(notice)
            add(Box.createHorizontalGlue())
        }
    }

    private fun createMessageRow(
        message: ConversationMessage,
        bubbleWidth: Int,
        textWidth: Int,
    ): JPanel {
        val isUser = message.role == ConversationRole.USER
        val rowAlignment = if (isUser) Component.RIGHT_ALIGNMENT else Component.LEFT_ALIGNMENT
        val headerColor = if (isUser) {
            JBColor(Color(76, 97, 124), Color(170, 198, 230))
        } else {
            JBColor(Color(94, 100, 109), Color(156, 164, 178))
        }

        val header = JBLabel(
            "${roleText(message.role)} · ${formatTimestamp(message.createdAt)}",
            if (isUser) SwingConstants.RIGHT else SwingConstants.LEFT,
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = headerColor
            alignmentX = rowAlignment
            maximumSize = Dimension(bubbleWidth, Int.MAX_VALUE)
        }

        val bubble = createMessageBubble(message, textWidth).apply {
            alignmentX = rowAlignment
            maximumSize = Dimension(bubbleWidth, Int.MAX_VALUE)
        }

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            maximumSize = Dimension(bubbleWidth, Int.MAX_VALUE)
            add(header)
            add(Box.createVerticalStrut(3))
            add(bubble)
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            if (isUser) {
                add(Box.createHorizontalGlue())
                add(stack)
            } else {
                add(stack)
                add(Box.createHorizontalGlue())
            }
        }
    }

    private fun createMessageBubble(
        message: ConversationMessage,
        textWidth: Int,
    ): RoundedBubblePanel {
        val contentPane = JTextPane().apply {
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty()
            font = JBUI.Fonts.label()
        }
        val content = message.content.trimEnd().ifBlank { " " }
        MarkdownRenderer.render(contentPane, content)
        contentPane.setSize(Dimension(textWidth, Int.MAX_VALUE))
        val preferred = contentPane.preferredSize
        contentPane.preferredSize = Dimension(minOf(preferred.width, textWidth), preferred.height)

        val (background, borderColor) = bubbleColor(message.role)
        return RoundedBubblePanel(JBUI.scale(12)).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(8, 10)
            updateColors(background, borderColor)
            add(contentPane, BorderLayout.CENTER)
        }
    }

    private fun currentBubbleWidth(): Int {
        val viewportWidth = messageScrollPane.viewport.width
            .takeIf { it > 0 }
            ?: messageScrollPane.width.takeIf { it > 0 }
            ?: width.takeIf { it > 0 }
            ?: MAX_BUBBLE_WIDTH
        return (viewportWidth * BUBBLE_WIDTH_RATIO)
            .toInt()
            .coerceIn(MIN_BUBBLE_WIDTH, MAX_BUBBLE_WIDTH)
    }

    private fun currentTextWidth(bubbleWidth: Int): Int {
        val padding = JBUI.scale(BUBBLE_CONTENT_HORIZONTAL_PADDING)
        return (bubbleWidth - padding).coerceAtLeast(MIN_BUBBLE_TEXT_WIDTH)
    }

    private fun bubbleColor(role: ConversationRole): Pair<Color, Color> {
        return when (role) {
            ConversationRole.USER -> {
                Pair(
                    JBColor(Color(224, 236, 255), Color(65, 87, 117)),
                    JBColor(Color(149, 178, 223), Color(102, 129, 162)),
                )
            }
            ConversationRole.ASSISTANT -> {
                Pair(
                    JBColor(Color(244, 246, 250), Color(64, 69, 78)),
                    JBColor(Color(214, 220, 231), Color(89, 96, 108)),
                )
            }
            ConversationRole.SYSTEM -> {
                Pair(
                    JBColor(Color(243, 246, 251), Color(70, 76, 87)),
                    JBColor(Color(209, 218, 232), Color(92, 102, 118)),
                )
            }
            ConversationRole.TOOL -> {
                Pair(
                    JBColor(Color(238, 249, 240), Color(66, 90, 68)),
                    JBColor(Color(184, 218, 186), Color(86, 118, 90)),
                )
            }
        }
    }

    private fun roleText(role: ConversationRole): String {
        return when (role) {
            ConversationRole.USER -> SpecCodingBundle.message("history.detail.role.user")
            ConversationRole.ASSISTANT -> SpecCodingBundle.message("history.detail.role.assistant")
            ConversationRole.SYSTEM -> SpecCodingBundle.message("history.detail.role.system")
            ConversationRole.TOOL -> SpecCodingBundle.message("history.detail.role.tool")
        }
    }

    private class RoundedBubblePanel(private val arc: Int) : JPanel() {
        private var fillColor: Color = JBColor.PanelBackground
        private var strokeColor: Color = JBColor.border()

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
        private const val MAX_RENDER_MESSAGES = 300
        private const val MIN_BUBBLE_WIDTH = 260
        private const val MAX_BUBBLE_WIDTH = 680
        private const val MIN_BUBBLE_TEXT_WIDTH = 220
        private const val BUBBLE_CONTENT_HORIZONTAL_PADDING = 28
        private const val BUBBLE_WIDTH_RATIO = 0.88
        private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
    }
}
