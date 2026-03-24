package com.eacape.speccodingplugin.ui.input

import com.eacape.speccodingplugin.ui.completion.CompletionItem
import com.eacape.speccodingplugin.ui.completion.TriggerParser
import com.eacape.speccodingplugin.ui.completion.TriggerParseResult
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.ActionMap
import javax.swing.JComponent
import javax.swing.InputMap
import javax.swing.KeyStroke
import javax.swing.JTextArea
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.BadLocationException
import javax.swing.text.DefaultHighlighter

/**
 * 增强输入框
 * 基于 JTextArea，支持触发字符检测、补全弹窗、Enter 发送 / Shift+Enter 换行
 */
class SmartInputField(
    private var placeholder: String = "",
    private val onSend: (String) -> Unit = {},
    private val onTrigger: (TriggerParseResult) -> Unit = {},
    private val onTriggerDismiss: () -> Unit = {},
    private val onCompletionSelect: (CompletionItem) -> Unit = {},
    private val onPasteIntercept: (() -> Boolean)? = null,
) : JTextArea() {
    private val logger = logger<SmartInputField>()
    private val promptReferenceHighlighter = DefaultHighlighter.DefaultHighlightPainter(PROMPT_REFERENCE_BG)
    private val promptReferenceHighlightTags = mutableListOf<Any>()

    private var debounceTimer: Timer? = null
    private var lastTrigger: TriggerParseResult? = null

    val completionPopup = CompletionPopup(::handleCompletionSelect)

    // Keep binary compatibility for callers compiled against the old constructor.
    constructor(
        placeholder: String,
        onSend: (String) -> Unit,
        onTrigger: (TriggerParseResult) -> Unit,
        onTriggerDismiss: () -> Unit,
        onCompletionSelect: (CompletionItem) -> Unit,
    ) : this(
        placeholder = placeholder,
        onSend = onSend,
        onTrigger = onTrigger,
        onTriggerDismiss = onTriggerDismiss,
        onCompletionSelect = onCompletionSelect,
        onPasteIntercept = null,
    )

    init {
        rows = 1
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6, 8)
        font = JBUI.Fonts.label()

        setupKeyBindings()
        setupDocumentListener()
    }

    private fun setupKeyBindings() {
        val inputMap = getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = actionMap
        val shortcutMask = runCatching { Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx }
            .getOrDefault(InputEvent.CTRL_DOWN_MASK)

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_SEND)
        actionMap.put(
            ACTION_SEND,
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    if (completionPopup.isVisible) {
                        completionPopup.confirmSelection()
                        return
                    }
                    val input = text.trim()
                    if (input.isNotBlank()) {
                        onSend(input)
                    }
                }
            },
        )

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), ACTION_NEWLINE)
        actionMap.put(
            ACTION_NEWLINE,
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    replaceSelection("\n")
                }
            },
        )

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ACTION_DISMISS_COMPLETION)
        actionMap.put(
            ACTION_DISMISS_COMPLETION,
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    if (completionPopup.isVisible) {
                        completionPopup.hide()
                        onTriggerDismiss()
                    }
                }
            },
        )

        bindCompletionNavigationAction(
            inputMap = inputMap,
            actionMap = actionMap,
            keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0),
            actionKey = ACTION_COMPLETION_UP,
            fallbackDirection = -1,
            onCompletionNavigate = { completionPopup.moveUp() },
        )
        bindCompletionNavigationAction(
            inputMap = inputMap,
            actionMap = actionMap,
            keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
            actionKey = ACTION_COMPLETION_DOWN,
            fallbackDirection = 1,
            onCompletionNavigate = { completionPopup.moveDown() },
        )

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), ACTION_COMPLETION_CONFIRM)
        actionMap.put(
            ACTION_COMPLETION_CONFIRM,
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    if (completionPopup.isVisible) {
                        completionPopup.confirmSelection()
                    }
                }
            },
        )

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcutMask), ACTION_PASTE_CONTENT)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK), ACTION_PASTE_CONTENT)
        actionMap.put(
            ACTION_PASTE_CONTENT,
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    logger.warn("[PasteDiag] ACTION_PASTE_CONTENT triggered on SmartInputField")
                    paste()
                }
            },
        )
    }

    private fun bindCompletionNavigationAction(
        inputMap: InputMap,
        actionMap: ActionMap,
        keyStroke: KeyStroke,
        actionKey: String,
        fallbackDirection: Int,
        onCompletionNavigate: () -> Unit,
    ) {
        val fallbackAction = resolveExistingAction(inputMap, actionMap, keyStroke)
        inputMap.put(keyStroke, actionKey)
        actionMap.put(
            actionKey,
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    if (completionPopup.isVisible) {
                        onCompletionNavigate()
                        return
                    }
                    val handledByFallback = runCatching {
                        fallbackAction?.actionPerformed(e)
                        fallbackAction != null
                    }.getOrDefault(false)
                    if (!handledByFallback) {
                        moveCaretByLogicalLine(fallbackDirection)
                    }
                }
            },
        )
    }

    private fun resolveExistingAction(
        inputMap: InputMap,
        actionMap: ActionMap,
        keyStroke: KeyStroke,
    ): Action? {
        val existingActionKey = inputMap.get(keyStroke) ?: return null
        return actionMap.get(existingActionKey)
    }

    private fun moveCaretByLogicalLine(direction: Int) {
        val currentOffset = caretPosition.coerceIn(0, document.length)
        val currentLine = runCatching { getLineOfOffset(currentOffset) }.getOrElse { return }
        val targetLine = (currentLine + direction).coerceIn(0, lineCount - 1)
        if (targetLine == currentLine) {
            return
        }
        try {
            val currentLineStart = getLineStartOffset(currentLine)
            val targetLineStart = getLineStartOffset(targetLine)
            val targetLineEnd = if (targetLine == lineCount - 1) {
                document.length
            } else {
                (getLineEndOffset(targetLine) - 1).coerceAtMost(document.length)
            }
            val targetColumn = (currentOffset - currentLineStart).coerceAtLeast(0)
            caretPosition = (targetLineStart + targetColumn).coerceAtMost(targetLineEnd)
        } catch (_: BadLocationException) {
        }
    }

    private fun setupDocumentListener() {
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onInputChanged()
            override fun removeUpdate(e: DocumentEvent) = onInputChanged()
            override fun changedUpdate(e: DocumentEvent) = onInputChanged()
        })
    }

    private fun onInputChanged() {
        refreshPromptReferenceHighlights()
        scheduleCheck()
    }

    private fun scheduleCheck() {
        debounceTimer?.stop()
        debounceTimer = Timer(150) { checkTrigger() }
        debounceTimer?.isRepeats = false
        debounceTimer?.start()
    }

    private fun refreshPromptReferenceHighlights() {
        val currentHighlighter = highlighter ?: return
        clearPromptReferenceHighlights(currentHighlighter)

        val currentText = text.orEmpty()
        if (currentText.isBlank()) return

        PROMPT_REFERENCE_REGEX
            .findAll(currentText)
            .take(MAX_PROMPT_REFERENCE_HIGHLIGHTS)
            .forEach { match ->
                runCatching {
                    val tag = currentHighlighter.addHighlight(
                        match.range.first,
                        match.range.last + 1,
                        promptReferenceHighlighter,
                    )
                    promptReferenceHighlightTags += tag
                }
            }
    }

    private fun clearPromptReferenceHighlights(currentHighlighter: javax.swing.text.Highlighter) {
        if (promptReferenceHighlightTags.isEmpty()) return
        promptReferenceHighlightTags.forEach { tag ->
            currentHighlighter.removeHighlight(tag)
        }
        promptReferenceHighlightTags.clear()
    }

    private fun checkTrigger() {
        val currentText = text ?: ""
        val caret = caretPosition

        val result = TriggerParser.parse(currentText, caret)
        if (result != null) {
            lastTrigger = result
            onTrigger(result)
        } else if (lastTrigger != null) {
            lastTrigger = null
            completionPopup.hide()
            onTriggerDismiss()
        }
    }

    private fun handleCompletionSelect(item: CompletionItem) {
        val trigger = lastTrigger ?: return
        val before = text.substring(0, trigger.triggerOffset)
        val after = text.substring(caretPosition)

        // For @ and # triggers, don't insert the full text into the input;
        // instead clear the trigger text and notify the parent via callback
        if (item.contextItem != null) {
            text = before + after
            caretPosition = before.length
        } else {
            text = before + item.insertText + " " + after
            caretPosition = (before + item.insertText + " ").length
        }

        lastTrigger = null
        refreshPromptReferenceHighlights()
        onCompletionSelect(item)
    }

    fun showCompletions(items: List<CompletionItem>) {
        if (items.isEmpty()) {
            completionPopup.hide()
            return
        }
        completionPopup.show(items, this)
    }

    fun setPlaceholderText(value: String) {
        if (placeholder == value) {
            return
        }
        placeholder = value
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        // Draw placeholder when empty
        if (text.isNullOrEmpty() && placeholder.isNotBlank()) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            )
            g2.color = JBColor.GRAY
            g2.font = font
            val fm = g2.fontMetrics
            val x = insets.left
            val y = insets.top + fm.ascent
            g2.drawString(placeholder, x, y)
        }
    }

    override fun paste() {
        logger.warn("[PasteDiag] SmartInputField.paste invoked; hasIntercept=${onPasteIntercept != null}")
        val intercepted = runCatching {
            onPasteIntercept?.invoke() == true
        }.onFailure { error ->
            logger.warn("[PasteDiag] SmartInputField paste intercept failed: ${error.message}", error)
        }.getOrDefault(false)
        if (intercepted) {
            logger.warn("[PasteDiag] SmartInputField.paste handled by intercept")
            return
        }
        logger.warn("[PasteDiag] SmartInputField.paste delegated to super")
        super.paste()
    }

    companion object {
        private const val ACTION_SEND = "specCoding.send"
        private const val ACTION_NEWLINE = "specCoding.newline"
        private const val ACTION_DISMISS_COMPLETION = "specCoding.dismissCompletion"
        private const val ACTION_COMPLETION_UP = "specCoding.completionUp"
        private const val ACTION_COMPLETION_DOWN = "specCoding.completionDown"
        private const val ACTION_COMPLETION_CONFIRM = "specCoding.completionConfirm"
        private const val ACTION_PASTE_CONTENT = "specCoding.pasteContent"
        private const val MAX_PROMPT_REFERENCE_HIGHLIGHTS = 200
        private val PROMPT_REFERENCE_REGEX = Regex("""(?<!\S)#([\p{L}\p{N}_.-]+)""")
        private val PROMPT_REFERENCE_BG = JBColor(
            Color(224, 237, 255),
            Color(59, 80, 108),
        )
    }
}
