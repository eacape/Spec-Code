package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal class WorkflowChatExecutionPromptDialog(
    private val promptText: String,
) : DialogWrapper(true) {
    private val overview = WorkflowChatExecutionPromptDebugOverview.parse(promptText)

    private val copyAction = object : DialogWrapperAction(
        SpecCodingBundle.message("chat.execution.launch.dialog.copy"),
    ) {
        override fun doAction(e: ActionEvent?) {
            CopyPasteManager.getInstance().setContents(StringSelection(promptText))
        }
    }

    init {
        title = SpecCodingBundle.message("chat.execution.launch.dialog.title")
        setOKButtonText(SpecCodingBundle.message("chat.execution.launch.dialog.close"))
        isResizable = true
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(copyAction, okAction)

    override fun createCenterPanel(): JComponent {
        val tabs = JBTabbedPane().apply {
            addTab(
                SpecCodingBundle.message("chat.execution.launch.dialog.tab.overview"),
                createOverviewPanel(),
            )
            addTab(
                SpecCodingBundle.message("chat.execution.launch.dialog.tab.raw"),
                createRawPromptPanel(),
            )
        }

        val lineCount = promptText.lineSequence().count().coerceAtLeast(1)

        val headerPanel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(
                JBLabel(SpecCodingBundle.message("chat.execution.launch.dialog.description")).apply {
                    foreground = SUMMARY_FG
                    font = JBUI.Fonts.smallFont()
                },
                BorderLayout.NORTH,
            )
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(
                        JBLabel(
                            SpecCodingBundle.message(
                                "chat.execution.launch.dialog.stats",
                                lineCount,
                                promptText.length,
                            ),
                        ).apply {
                            foreground = META_FG
                            font = JBUI.Fonts.miniFont()
                        },
                    )
                    add(
                        JBLabel(
                            SpecCodingBundle.message(
                                "chat.execution.launch.dialog.sections",
                                overview.sections.size,
                            ),
                        ).apply {
                            foreground = META_FG
                            font = JBUI.Fonts.miniFont()
                        },
                    )
                },
                BorderLayout.SOUTH,
            )
        }

        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(10)
            preferredSize = JBUI.size(820, 520)
            minimumSize = JBUI.size(660, 360)
            add(headerPanel, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
    }

    private fun createOverviewPanel(): JComponent {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 10, 8, 10)
        }

        overview.leadLines.takeIf { it.isNotEmpty() }?.let { leadLines ->
            content.add(
                createSectionPreviewPanel(
                    WorkflowChatExecutionPromptDebugSection(
                        title = SpecCodingBundle.message("chat.execution.launch.dialog.section.requestMeta"),
                        lines = leadLines,
                    ),
                ),
            )
        }
        if (overview.sections.isEmpty()) {
            if (content.componentCount > 0) {
                content.add(createSectionGap())
            }
            content.add(
                createSectionPreviewPanel(
                    WorkflowChatExecutionPromptDebugSection(
                        title = SpecCodingBundle.message("chat.execution.launch.dialog.section.empty"),
                        lines = listOf(promptText.trim().ifBlank { SpecCodingBundle.message("chat.execution.launch.section.empty") }),
                    ),
                ),
            )
        } else {
            overview.sections.forEachIndexed { index, section ->
                if (index > 0 || overview.leadLines.isNotEmpty()) {
                    content.add(createSectionGap())
                }
                content.add(createSectionPreviewPanel(section))
            }
        }

        return JBScrollPane(content).apply {
            border = JBUI.Borders.customLine(BORDER_FG, 1)
        }
    }

    private fun createRawPromptPanel(): JComponent {
        val promptArea = JBTextArea(promptText).apply {
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
            border = JBUI.Borders.empty(4, 4, 4, 4)
            caretPosition = 0
        }

        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(8)
            add(
                JBScrollPane(promptArea).apply {
                    border = JBUI.Borders.customLine(BORDER_FG, 1)
                },
                BorderLayout.CENTER,
            )
        }
    }

    private fun createSectionGap(): JPanel {
        return JPanel().apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
        }
    }

    private fun createSectionPreviewPanel(section: WorkflowChatExecutionPromptDebugSection): JPanel {
        val previewLines = section.previewLines()
        val hiddenLineCount = section.hiddenLineCount()
        val panel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = true
            background = CARD_BG
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(BORDER_FG, 1),
                JBUI.Borders.empty(10, 12, 10, 12),
            )
        }

        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(
                JBLabel(section.title).apply {
                    foreground = TITLE_FG
                    font = font.deriveFont(Font.BOLD, 12f)
                },
                BorderLayout.CENTER,
            )
            add(
                createBadge(
                    SpecCodingBundle.message(
                        "chat.execution.launch.dialog.section.lines",
                        section.displayLineCount(),
                    ),
                ),
                BorderLayout.EAST,
            )
        }

        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        if (previewLines.isEmpty()) {
            body.add(
                createMutedTextArea(SpecCodingBundle.message("chat.execution.launch.dialog.preview.empty")).apply {
                    border = JBUI.Borders.emptyTop(2)
                },
            )
        } else {
            previewLines.forEachIndexed { index, line ->
                if (index > 0) {
                    body.add(createSectionGap())
                }
                body.add(createPreviewLine(line))
            }
        }
        if (hiddenLineCount > 0) {
            body.add(
                createMutedTextArea(
                    SpecCodingBundle.message(
                        "chat.execution.launch.dialog.section.more",
                        hiddenLineCount,
                    ),
                ).apply {
                    border = JBUI.Borders.emptyTop(8)
                },
            )
        }

        panel.add(header, BorderLayout.NORTH)
        panel.add(body, BorderLayout.CENTER)
        return panel
    }

    private fun createBadge(text: String): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = true
            background = BADGE_BG
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(BADGE_BORDER, 1),
                JBUI.Borders.empty(2, 8, 2, 8),
            )
            add(
                JBLabel(text).apply {
                    foreground = META_FG
                    font = JBUI.Fonts.miniFont()
                },
            )
        }
    }

    private fun createPreviewLine(text: String): JPanel {
        return JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(
                JBLabel("-").apply {
                    foreground = ACCENT_FG
                    font = font.deriveFont(Font.BOLD, 12f)
                },
                BorderLayout.WEST,
            )
            add(createBodyTextArea(text), BorderLayout.CENTER)
        }
    }

    private fun createBodyTextArea(text: String): JBTextArea {
        return JBTextArea(text).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            columns = 72
            font = JBUI.Fonts.label().deriveFont(12f)
            border = JBUI.Borders.empty(0)
            foreground = BODY_FG
            isFocusable = false
        }
    }

    private fun createMutedTextArea(text: String): JBTextArea {
        return createBodyTextArea(text).apply {
            foreground = SUMMARY_FG
        }
    }

    companion object {
        private val CARD_BG = JBColor(Color(247, 250, 254), Color(44, 51, 60))
        private val BADGE_BG = JBColor(Color(238, 243, 249), Color(54, 61, 73))
        private val BADGE_BORDER = JBColor(Color(203, 212, 223), Color(84, 95, 108))
        private val ACCENT_FG = JBColor(Color(70, 104, 150), Color(148, 186, 236))
        private val TITLE_FG = JBColor(Color(55, 63, 74), Color(224, 229, 236))
        private val BODY_FG = JBColor(Color(52, 60, 70), Color(211, 217, 226))
        private val SUMMARY_FG = JBColor(Color(96, 107, 121), Color(176, 184, 196))
        private val META_FG = JBColor(Color(118, 129, 143), Color(154, 165, 179))
        private val BORDER_FG = JBColor(Color(202, 211, 223), Color(79, 90, 102))
    }
}

internal data class WorkflowChatExecutionPromptDebugSection(
    val title: String,
    val lines: List<String>,
) {
    fun displayLineCount(): Int = normalizedDisplayLines().size

    fun previewLines(
        maxLines: Int = DEFAULT_PREVIEW_LINES,
        maxLineLength: Int = DEFAULT_PREVIEW_LINE_LENGTH,
    ): List<String> {
        return normalizedDisplayLines()
            .take(maxLines)
            .map { compactLine(it, maxLineLength) }
    }

    fun hiddenLineCount(maxLines: Int = DEFAULT_PREVIEW_LINES): Int {
        return (displayLineCount() - maxLines).coerceAtLeast(0)
    }

    private fun normalizedDisplayLines(): List<String> {
        return lines.mapNotNull(::normalizeDisplayLine)
    }

    private fun normalizeDisplayLine(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank() || trimmed == "```") {
            return null
        }
        return trimmed
            .replace(BULLET_PREFIX_REGEX, "")
            .replace(NUMBERED_PREFIX_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
            .takeIf(String::isNotBlank)
    }

    private fun compactLine(value: String, maxLineLength: Int): String {
        if (value.length <= maxLineLength) {
            return value
        }
        return value.take((maxLineLength - 3).coerceAtLeast(1)).trimEnd() + "..."
    }

    companion object {
        private const val DEFAULT_PREVIEW_LINES = 3
        private const val DEFAULT_PREVIEW_LINE_LENGTH = 112
        private val BULLET_PREFIX_REGEX = Regex("^[-*+]\\s+")
        private val NUMBERED_PREFIX_REGEX = Regex("^\\d+[.)]\\s+")
        private val MULTI_SPACE_REGEX = Regex("\\s+")
    }
}

internal data class WorkflowChatExecutionPromptDebugOverview(
    val leadLines: List<String>,
    val sections: List<WorkflowChatExecutionPromptDebugSection>,
) {
    companion object {
        fun parse(promptText: String): WorkflowChatExecutionPromptDebugOverview {
            val normalizedLines = promptText
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()

            val leadLines = mutableListOf<String>()
            val sections = mutableListOf<WorkflowChatExecutionPromptDebugSection>()
            var currentSectionTitle: String? = null
            val currentSectionLines = mutableListOf<String>()

            fun flushSection() {
                val title = currentSectionTitle ?: return
                sections += WorkflowChatExecutionPromptDebugSection(
                    title = title,
                    lines = currentSectionLines.toList(),
                )
                currentSectionTitle = null
                currentSectionLines.clear()
            }

            normalizedLines.forEach { rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.startsWith("## ")) {
                    flushSection()
                    currentSectionTitle = trimmed.removePrefix("## ").trim()
                    return@forEach
                }
                if (trimmed.startsWith("### ")) {
                    flushSection()
                    currentSectionTitle = trimmed.removePrefix("### ").trim()
                    return@forEach
                }
                if (currentSectionTitle == null) {
                    if (trimmed.isNotBlank()) {
                        leadLines += trimmed
                    }
                } else {
                    currentSectionLines += rawLine
                }
            }
            flushSection()

            return WorkflowChatExecutionPromptDebugOverview(
                leadLines = leadLines,
                sections = sections,
            )
        }
    }
}
