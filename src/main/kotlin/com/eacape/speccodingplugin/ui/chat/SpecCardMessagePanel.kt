package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.DocumentRevisionConflictException
import com.eacape.speccodingplugin.spec.SpecMarkdownSanitizer
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextPane
import javax.swing.Timer

/**
 * Spec 卡片消息组件（Phase B）。
 * 以结构化卡片展示 workflow 状态和文档预览，并提供快速操作。
 */
internal data class SpecCardPanelSnapshot(
    val metadata: SpecCardMetadata,
    val cardMarkdown: String,
    val documentContent: String,
)

internal class SpecCardSaveConflictException(
    val latestContent: String,
    val expectedRevision: Long? = null,
    val actualRevision: Long? = null,
) : IllegalStateException(
    "Spec card save conflict" +
        listOfNotNull(
            expectedRevision?.let { "expected=$it" },
            actualRevision?.let { "actual=$it" },
        ).joinToString(prefix = " (", postfix = ")", separator = ", ").takeIf { it.length > 3 }.orEmpty(),
)

internal class SpecCardMessagePanel(
    metadata: SpecCardMetadata,
    cardMarkdown: String,
    initialDocumentContent: String,
    private val onDeleteMessage: ((ChatMessagePanel) -> Unit)? = null,
    private var onContinueMessage: ((ChatMessagePanel) -> Unit)? = null,
    private val onOpenSpecTab: (() -> Unit)? = null,
    private val onOpenDocument: ((SpecCardMetadata) -> Unit)? = null,
    private val onFocusSpecSidebar: ((SpecCardMetadata) -> Unit)? = null,
    private val onSaveDocument: ((SpecCardMetadata, String, Boolean) -> Result<SpecCardPanelSnapshot>)? = null,
    private val onAdvancePhase: ((SpecCardMetadata) -> Unit)? = null,
) : ChatMessagePanel(
    role = MessageRole.ASSISTANT,
    initialContent = cardMarkdown,
    onDelete = onDeleteMessage,
    onContinue = onContinueMessage,
) {

    private enum class CardState {
        PREVIEW,
        EXPANDED,
        EDITING,
    }

    private var metadata = metadata
    private var cardMarkdown = cardMarkdown
    private var documentContent = initialDocumentContent.trim()
    private var state = CardState.PREVIEW
    private var busy = false
    private var hasRevisionConflict = false
    private var conflictLatestContent: String? = null
    private var conflictDiffExpanded = false

    private val contentPane = JTextPane()
    private val editorArea = JTextArea()
    private val editorScroll = JScrollPane(editorArea)
    private val conflictDiffArea = JTextArea()
    private val conflictDiffScroll = JScrollPane(conflictDiffArea)
    private val wrapper = JPanel(BorderLayout())
    private val bodyPanel = JPanel(BorderLayout())
    private val actionButtons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
    private val statusHintLabel = JBLabel()
    private val toggleButton = JButton()
    private val titleLabel = JBLabel()
    private val summaryLabel = JBLabel()
    private val phaseProgressLabel = JBLabel()

    init {
        removeAll()
        layout = BorderLayout()
        isOpaque = false
        border = JBUI.Borders.emptyBottom(10)

        wrapper.isOpaque = true
        wrapper.background = JBColor(Color(245, 249, 245), Color(38, 49, 40))
        wrapper.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(
                JBColor(Color(210, 219, 231), Color(89, 97, 108)),
                1,
                1,
                1,
                1,
            ),
            JBUI.Borders.empty(8, 10, 8, 10),
        )

        val header = createHeader()
        bodyPanel.isOpaque = false
        bodyPanel.border = JBUI.Borders.emptyTop(6)

        configureReadableTextPane(contentPane)
        contentPane.border = JBUI.Borders.empty(0, 0, 2, 0)

        editorArea.lineWrap = true
        editorArea.wrapStyleWord = true
        editorArea.font = JBUI.Fonts.create(Font.MONOSPACED, 12)
        editorArea.border = JBUI.Borders.empty(4, 0)
        editorScroll.border = JBUI.Borders.empty()
        editorScroll.preferredSize = JBUI.size(0, 220)
        editorScroll.minimumSize = JBUI.size(0, 160)

        conflictDiffArea.isEditable = false
        conflictDiffArea.lineWrap = false
        conflictDiffArea.wrapStyleWord = false
        conflictDiffArea.font = JBUI.Fonts.create(Font.MONOSPACED, 11)
        conflictDiffArea.border = JBUI.Borders.empty(4, 0)
        conflictDiffScroll.border = JBUI.Borders.emptyTop(4)
        conflictDiffScroll.preferredSize = JBUI.size(0, 170)
        conflictDiffScroll.minimumSize = JBUI.size(0, 120)

        wrapper.add(header, BorderLayout.NORTH)
        wrapper.add(bodyPanel, BorderLayout.CENTER)
        wrapper.add(createActionRow(), BorderLayout.SOUTH)

        add(wrapper, BorderLayout.CENTER)
        renderCard()
    }

    override fun updateContinueAction(onContinue: ((ChatMessagePanel) -> Unit)?) {
        if (onContinueMessage === onContinue) {
            return
        }
        onContinueMessage = onContinue
        rebuildActionRow()
    }

    private fun createHeader(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false

        val left = JPanel()
        left.layout = BoxLayout(left, BoxLayout.Y_AXIS)
        left.isOpaque = false

        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        titleRow.isOpaque = false
        titleRow.add(JBLabel(AllIcons.FileTypes.Text))
        titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD, 12f)
        titleRow.add(titleLabel)
        left.add(titleRow)

        val summaryRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        summaryRow.isOpaque = false
        summaryLabel.font = summaryLabel.font.deriveFont(11f)
        summaryLabel.foreground = JBColor.GRAY
        summaryRow.add(summaryLabel)
        left.add(summaryRow)

        val progressRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        progressRow.isOpaque = false
        phaseProgressLabel.font = phaseProgressLabel.font.deriveFont(10f)
        phaseProgressLabel.foreground = JBColor(Color(106, 130, 166), Color(151, 177, 214))
        progressRow.add(phaseProgressLabel)
        left.add(progressRow)

        toggleButton.addActionListener {
            if (busy || state == CardState.EDITING) return@addActionListener
            state = if (state == CardState.PREVIEW) CardState.EXPANDED else CardState.PREVIEW
            renderCard()
        }
        styleInlineActionButton(toggleButton)

        panel.add(left, BorderLayout.CENTER)
        panel.add(toggleButton, BorderLayout.EAST)
        return panel
    }

    private fun createActionRow(): JPanel {
        val row = JPanel(BorderLayout())
        row.isOpaque = false
        row.border = JBUI.Borders.emptyTop(6)
        actionButtons.isOpaque = false
        statusHintLabel.font = statusHintLabel.font.deriveFont(11f)
        statusHintLabel.foreground = JBColor.GRAY
        row.add(actionButtons, BorderLayout.WEST)
        row.add(statusHintLabel, BorderLayout.EAST)
        return row
    }

    private fun rebuildActionRow() {
        actionButtons.removeAll()
        var hasTextAction = false

        fun addTextAction(button: JButton) {
            if (hasTextAction) {
                actionButtons.add(createActionSeparatorLabel())
            }
            actionButtons.add(button)
            hasTextAction = true
        }

        if (state == CardState.EDITING) {
            val saveButton = JButton(SpecCodingBundle.message("toolwindow.spec.card.action.save"))
            styleTextActionButton(saveButton)
            saveButton.isEnabled = onSaveDocument != null && !busy
            saveButton.addActionListener { saveEditingContent(force = false) }
            addTextAction(saveButton)

            if (hasRevisionConflict) {
                if (!conflictLatestContent.isNullOrBlank()) {
                    val diffButton = JButton(
                        SpecCodingBundle.message(
                            if (conflictDiffExpanded) {
                                "toolwindow.spec.card.action.hideDiff"
                            } else {
                                "toolwindow.spec.card.action.showDiff"
                            }
                        )
                    )
                    styleTextActionButton(diffButton)
                    diffButton.isEnabled = !busy
                    diffButton.addActionListener {
                        if (busy) return@addActionListener
                        conflictDiffExpanded = !conflictDiffExpanded
                        renderCard()
                    }
                    addTextAction(diffButton)
                }

                val forceSaveButton = JButton(SpecCodingBundle.message("toolwindow.spec.card.action.forceSave"))
                styleTextActionButton(forceSaveButton)
                forceSaveButton.isEnabled = onSaveDocument != null && !busy
                forceSaveButton.addActionListener { saveEditingContent(force = true) }
                addTextAction(forceSaveButton)
            }

            val cancelButton = JButton(SpecCodingBundle.message("toolwindow.spec.card.action.cancel"))
            styleTextActionButton(cancelButton)
            cancelButton.isEnabled = !busy
            cancelButton.addActionListener {
                if (busy) return@addActionListener
                state = CardState.EXPANDED
                hasRevisionConflict = false
                conflictLatestContent = null
                conflictDiffExpanded = false
                editorArea.text = resolveDisplayContent()
                showHint("", isError = false)
                renderCard()
            }
            addTextAction(cancelButton)
        } else {
            val editButton = JButton(SpecCodingBundle.message("toolwindow.spec.card.action.edit"))
            styleTextActionButton(editButton)
            editButton.isEnabled = onSaveDocument != null && !busy
            editButton.addActionListener {
                if (busy) return@addActionListener
                state = CardState.EDITING
                hasRevisionConflict = false
                conflictLatestContent = null
                conflictDiffExpanded = false
                editorArea.text = resolveDisplayContent()
                showHint("", isError = false)
                renderCard()
            }
            addTextAction(editButton)

            if (metadata.phase.next() != null && onAdvancePhase != null) {
                val nextButton = JButton(SpecCodingBundle.message("toolwindow.spec.card.action.next"))
                styleTextActionButton(nextButton)
                nextButton.isEnabled = !busy
                nextButton.addActionListener {
                    if (busy) return@addActionListener
                    onAdvancePhase.invoke(metadata)
                }
                addTextAction(nextButton)
            }
        }

        val openTabButton = JButton(SpecCodingBundle.message("toolwindow.spec.quick.open"))
        styleTextActionButton(openTabButton)
        openTabButton.toolTipText = SpecCodingBundle.message("toolwindow.spec.quick.open.tooltip")
        openTabButton.isEnabled = onOpenSpecTab != null && !busy
        openTabButton.addActionListener { onOpenSpecTab?.invoke() }
        addTextAction(openTabButton)

        if (onFocusSpecSidebar != null) {
            val sidebarButton = JButton(SpecCodingBundle.message("toolwindow.spec.card.action.sidebar"))
            styleTextActionButton(sidebarButton)
            sidebarButton.isEnabled = !busy
            sidebarButton.addActionListener { onFocusSpecSidebar.invoke(metadata) }
            addTextAction(sidebarButton)
        }

        val openDocButton = JButton(SpecCodingBundle.message("chat.workflow.action.openFile.short"))
        styleTextActionButton(openDocButton)
        openDocButton.toolTipText = SpecCodingBundle.message(
            "chat.workflow.action.openFile.tooltip",
            "${metadata.workflowId}/${metadata.phase.outputFileName}",
        )
        openDocButton.isEnabled = onOpenDocument != null && !busy
        openDocButton.addActionListener { onOpenDocument?.invoke(metadata) }
        addTextAction(openDocButton)

        if (hasTextAction) {
            actionButtons.add(createActionSpacerLabel())
        }

        val copyButton = JButton()
        styleIconActionButton(
            button = copyButton,
            icon = AllIcons.Actions.Copy,
            tooltip = SpecCodingBundle.message("chat.message.copy.all"),
        )
        copyButton.isEnabled = !busy
        copyButton.addActionListener {
            val copied = copyToClipboard(resolveDisplayContent())
            showCopyFeedback(copyButton, copied)
        }
        actionButtons.add(copyButton)

        val continueHandler = onContinueMessage
        if (continueHandler != null) {
            val continueButton = JButton()
            styleIconActionButton(
                button = continueButton,
                icon = AllIcons.Actions.Execute,
                tooltip = SpecCodingBundle.message("chat.message.continue"),
            )
            continueButton.isEnabled = !busy
            continueButton.addActionListener { continueHandler.invoke(this) }
            actionButtons.add(continueButton)
        }

        if (onDeleteMessage != null) {
            val deleteButton = JButton()
            styleIconActionButton(
                button = deleteButton,
                icon = AllIcons.Actions.GC,
                tooltip = SpecCodingBundle.message("chat.message.delete"),
            )
            deleteButton.isEnabled = !busy
            deleteButton.addActionListener { onDeleteMessage.invoke(this) }
            actionButtons.add(deleteButton)
        }

        actionButtons.revalidate()
        actionButtons.repaint()
    }

    private fun renderCard() {
        renderHeader()
        renderBody()
        rebuildActionRow()
        revalidate()
        repaint()
    }

    private fun renderHeader() {
        val title = metadata.title.ifBlank { metadata.workflowId }
        titleLabel.text = title
        summaryLabel.text = buildString {
            append(metadata.phase.displayName)
            append(" · ")
            append(workflowStatusDisplayName(metadata.status))
            append(" · ")
            append(metadata.workflowId)
        }
        phaseProgressLabel.text = buildPhaseProgress(metadata.phase)
        toggleButton.isVisible = state != CardState.EDITING
        if (state != CardState.EDITING) {
            toggleButton.text = SpecCodingBundle.message(
                if (state == CardState.EXPANDED) "chat.workflow.toggle.collapse" else "chat.workflow.toggle.expand"
            )
        }
    }

    private fun renderBody() {
        bodyPanel.removeAll()
        if (state == CardState.EDITING) {
            val editingContainer = JPanel(BorderLayout())
            editingContainer.isOpaque = false
            editingContainer.add(editorScroll, BorderLayout.CENTER)
            if (hasRevisionConflict && conflictDiffExpanded && !conflictLatestContent.isNullOrBlank()) {
                val latestContent = conflictLatestContent.orEmpty()
                val currentEdited = editorArea.text
                val stats = computeLineDiffStats(latestContent, currentEdited)
                val title = SpecCodingBundle.message("toolwindow.spec.card.diff.title")
                val statsLine = SpecCodingBundle.message(
                    "toolwindow.spec.card.diff.stats",
                    stats.addedLines,
                    stats.removedLines,
                )
                conflictDiffArea.text = buildUnifiedDiffPreview(latestContent, currentEdited)
                conflictDiffArea.caretPosition = 0

                val diffContainer = JPanel(BorderLayout())
                diffContainer.isOpaque = false
                diffContainer.border = JBUI.Borders.emptyTop(6)
                val header = JBLabel("$title · $statsLine")
                header.font = header.font.deriveFont(11f)
                header.foreground = JBColor.GRAY
                diffContainer.add(header, BorderLayout.NORTH)
                diffContainer.add(conflictDiffScroll, BorderLayout.CENTER)
                editingContainer.add(diffContainer, BorderLayout.SOUTH)
            }
            bodyPanel.add(editingContainer, BorderLayout.CENTER)
            return
        }
        val content = if (state == CardState.EXPANDED) {
            resolveDisplayContent()
        } else {
            toCollapsedPreview(resolveDisplayContent())
        }
        MarkdownRenderer.render(contentPane, content.ifBlank { SpecCodingBundle.message("toolwindow.spec.card.empty") })
        bodyPanel.add(contentPane, BorderLayout.CENTER)
    }

    private fun buildPhaseProgress(currentPhase: SpecPhase): String {
        return SpecPhase.entries.joinToString("  ") { phase ->
            when {
                phase.ordinal < currentPhase.ordinal -> "◉ ${phase.displayName}"
                phase == currentPhase -> "● ${phase.displayName}"
                else -> "○ ${phase.displayName}"
            }
        }
    }

    private fun saveEditingContent(force: Boolean) {
        val handler = onSaveDocument ?: return
        if (busy) return

        val edited = editorArea.text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (edited.isBlank()) {
            showHint(SpecCodingBundle.message("toolwindow.spec.card.save.empty"), isError = true)
            return
        }

        setBusy(true)
        showHint(SpecCodingBundle.message("toolwindow.spec.card.save.saving"), isError = false)
        if (!force) {
            hasRevisionConflict = false
            conflictLatestContent = null
            conflictDiffExpanded = false
        }
        val metadataAtSave = metadata
        val application = ApplicationManager.getApplication()
        if (application == null) {
            val result = runCatching { handler.invoke(metadataAtSave, edited, force).getOrThrow() }
            applySaveResult(result)
            return
        }

        application.executeOnPooledThread {
            val result = runCatching { handler.invoke(metadataAtSave, edited, force).getOrThrow() }
            application.invokeLater {
                if (!isDisplayable) {
                    return@invokeLater
                }
                applySaveResult(result)
            }
        }
    }

    private fun applySaveResult(result: Result<SpecCardPanelSnapshot>) {
        setBusy(false)
        result.onSuccess { snapshot ->
            metadata = snapshot.metadata
            cardMarkdown = snapshot.cardMarkdown
            documentContent = snapshot.documentContent.trim()
            state = CardState.EXPANDED
            hasRevisionConflict = false
            conflictLatestContent = null
            conflictDiffExpanded = false
            showHint(SpecCodingBundle.message("toolwindow.spec.card.save.saved"), isError = false)
            renderCard()
        }.onFailure { error ->
            val conflictError = when (error) {
                is SpecCardSaveConflictException -> error
                else -> null
            }
            val isConflict = conflictError != null ||
                error is DocumentRevisionConflictException ||
                error.message?.contains("revision conflict", ignoreCase = true) == true
            if (isConflict) {
                hasRevisionConflict = true
                conflictLatestContent = conflictError?.latestContent
                conflictDiffExpanded = false
                showHint(SpecCodingBundle.message("toolwindow.spec.card.save.conflict"), isError = true)
            } else {
                showHint(
                    SpecCodingBundle.message(
                        "toolwindow.spec.card.save.failed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    ),
                    isError = true,
                )
            }
            renderCard()
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        renderCard()
    }

    private fun showHint(message: String, isError: Boolean) {
        statusHintLabel.text = message
        statusHintLabel.foreground = if (isError) {
            JBColor(Color(198, 40, 40), Color(255, 138, 128))
        } else {
            JBColor.GRAY
        }
    }

    private data class LineDiffStats(
        val addedLines: Int,
        val removedLines: Int,
    )

    private fun computeLineDiffStats(
        baselineContent: String,
        targetContent: String,
    ): LineDiffStats {
        val baselineLines = normalizeLines(baselineContent)
        val targetLines = normalizeLines(targetContent)
        if (baselineLines.isEmpty() && targetLines.isEmpty()) {
            return LineDiffStats(0, 0)
        }
        val lcs = lcsLength(baselineLines, targetLines)
        return LineDiffStats(
            addedLines = (targetLines.size - lcs).coerceAtLeast(0),
            removedLines = (baselineLines.size - lcs).coerceAtLeast(0),
        )
    }

    private fun buildUnifiedDiffPreview(
        baselineContent: String,
        targetContent: String,
    ): String {
        val baselineLines = normalizeLines(baselineContent)
        val targetLines = normalizeLines(targetContent)
        if (baselineLines.isEmpty() && targetLines.isEmpty()) {
            return ""
        }
        if (baselineLines.isEmpty()) {
            return targetLines.take(DIFF_PREVIEW_MAX_LINES).joinToString("\n") { "+ $it" }
        }
        if (targetLines.isEmpty()) {
            return baselineLines.take(DIFF_PREVIEW_MAX_LINES).joinToString("\n") { "- $it" }
        }

        val rows = baselineLines.size
        val cols = targetLines.size
        val dp = Array(rows + 1) { IntArray(cols + 1) }
        for (i in 1..rows) {
            for (j in 1..cols) {
                dp[i][j] = if (baselineLines[i - 1] == targetLines[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        val operations = mutableListOf<String>()
        var i = rows
        var j = cols
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && baselineLines[i - 1] == targetLines[j - 1] -> {
                    operations += "  ${baselineLines[i - 1]}"
                    i -= 1
                    j -= 1
                }
                j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                    operations += "+ ${targetLines[j - 1]}"
                    j -= 1
                }
                i > 0 -> {
                    operations += "- ${baselineLines[i - 1]}"
                    i -= 1
                }
            }
        }

        val preview = operations
            .asReversed()
            .take(DIFF_PREVIEW_MAX_LINES)
            .toMutableList()
        val omitted = operations.size - preview.size
        if (omitted > 0) {
            preview += SpecCodingBundle.message("toolwindow.spec.card.diff.moreLines", omitted)
        }
        return preview.joinToString("\n")
    }

    private fun normalizeLines(content: String): List<String> {
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isBlank()) return emptyList()
        return normalized.split("\n")
    }

    private fun lcsLength(
        baselineLines: List<String>,
        targetLines: List<String>,
    ): Int {
        val rows = baselineLines.size
        val cols = targetLines.size
        val dp = Array(rows + 1) { IntArray(cols + 1) }
        for (i in 1..rows) {
            for (j in 1..cols) {
                dp[i][j] = if (baselineLines[i - 1] == targetLines[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        return dp[rows][cols]
    }

    private fun resolveDisplayContent(): String {
        val raw = if (documentContent.isNotBlank()) {
            documentContent
        } else {
            extractPreviewFromCardMarkdown(cardMarkdown)
        }
        return SpecMarkdownSanitizer.sanitize(raw)
    }

    private fun toCollapsedPreview(content: String): String {
        val filteredLines = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .filterNot(::isFirstLevelHeading)
            .toList()
        if (filteredLines.isEmpty()) {
            return ""
        }

        val previewLines = if (filteredLines.size > PREVIEW_MAX_LINES) {
            filteredLines.take(PREVIEW_MAX_LINES - 1) + PREVIEW_ELLIPSIS_LINE
        } else {
            filteredLines.take(PREVIEW_MAX_LINES)
        }

        val normalized = previewLines.joinToString("\n").trim()
        if (normalized.length <= PREVIEW_MAX_CHARS) {
            return normalized
        }
        val clippedLines = normalized
            .take(PREVIEW_MAX_CHARS)
            .trimEnd()
            .lineSequence()
            .take((PREVIEW_MAX_LINES - 1).coerceAtLeast(1))
            .toList()
        return if (clippedLines.isEmpty()) {
            PREVIEW_ELLIPSIS_LINE
        } else {
            (clippedLines + PREVIEW_ELLIPSIS_LINE).joinToString("\n")
        }
    }

    private fun isFirstLevelHeading(line: String): Boolean {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("#")) return false
        if (trimmed.startsWith("##")) return false
        return trimmed.drop(1).startsWith(" ") || trimmed.drop(1).isNotBlank()
    }

    private fun extractPreviewFromCardMarkdown(markdown: String): String {
        if (markdown.isBlank()) return ""
        val lines = markdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
        val previewHeaderIndex = lines.indexOfFirst { line ->
            line.trimStart().startsWith("### ") && PREVIEW_HEADER_REGEX.containsMatchIn(line)
        }
        if (previewHeaderIndex < 0 || previewHeaderIndex >= lines.lastIndex) {
            return markdown.trim()
        }
        return lines.subList(previewHeaderIndex + 1, lines.size).joinToString("\n").trim()
    }

    private fun workflowStatusDisplayName(status: WorkflowStatus): String {
        return when (status) {
            WorkflowStatus.IN_PROGRESS -> SpecCodingBundle.message("spec.workflow.status.inProgress")
            WorkflowStatus.PAUSED -> SpecCodingBundle.message("spec.workflow.status.paused")
            WorkflowStatus.COMPLETED -> SpecCodingBundle.message("spec.workflow.status.completed")
            WorkflowStatus.FAILED -> SpecCodingBundle.message("spec.workflow.status.failed")
        }
    }

    private fun configureReadableTextPane(pane: JTextPane) {
        pane.isEditable = false
        pane.isOpaque = false
        pane.isFocusable = true
        pane.cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
    }

    private fun copyToClipboard(text: String): Boolean {
        val selection = StringSelection(text)
        val copiedByIde = runCatching {
            CopyPasteManager.getInstance().setContents(selection)
        }.isSuccess
        if (copiedByIde) return true
        return runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            true
        }.getOrElse { false }
    }

    private fun showCopyFeedback(button: JButton, copied: Boolean) {
        val previousTimer = button.getClientProperty(COPY_FEEDBACK_TIMER_KEY) as? Timer
        previousTimer?.stop()
        val originalText = button.text
        val originalIcon = button.icon
        val originalToolTip = button.toolTipText

        button.icon = null
        button.text = if (copied) "OK" else "!"
        button.toolTipText = SpecCodingBundle.message(
            if (copied) "chat.message.copy.copied" else "chat.message.copy.failed"
        )

        val timer = Timer(COPY_FEEDBACK_DURATION_MS) {
            button.text = originalText
            button.icon = originalIcon
            button.toolTipText = originalToolTip
            button.putClientProperty(COPY_FEEDBACK_TIMER_KEY, null)
        }
        timer.isRepeats = false
        button.putClientProperty(COPY_FEEDBACK_TIMER_KEY, timer)
        timer.start()
    }

    private fun styleTextActionButton(button: JButton) {
        button.margin = JBUI.insets(0, 0, 0, 0)
        button.isFocusPainted = false
        button.isBorderPainted = false
        button.isFocusable = false
        button.font = button.font.deriveFont(12f)
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.border = JBUI.Borders.empty(0, 10, 0, 10)
        button.putClientProperty("JButton.buttonType", "borderless")
        button.putClientProperty("JButton.minimumWidth", 0)
        val label = button.text.orEmpty()
        if (label.isNotEmpty()) {
            val metrics = button.getFontMetrics(button.font)
            val compactSize = JBUI.size(metrics.stringWidth(label) + 20, metrics.height + 2)
            button.preferredSize = compactSize
            button.minimumSize = compactSize
            button.maximumSize = compactSize
        }
    }

    private fun createActionSeparatorLabel(): JBLabel {
        return JBLabel("|").apply {
            foreground = JBColor(
                Color(176, 184, 196),
                Color(129, 136, 146),
            )
            font = font.deriveFont(10f)
            border = JBUI.Borders.empty(0, 2)
        }
    }

    private fun createActionSpacerLabel(): JBLabel {
        return JBLabel("").apply {
            border = JBUI.Borders.empty(0, 0)
        }
    }

    private fun styleInlineActionButton(button: JButton) {
        button.margin = JBUI.insets(1, 4, 1, 4)
        button.isFocusPainted = false
        button.isFocusable = false
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.border = JBUI.Borders.empty(0, 2)
        button.font = button.font.deriveFont(11f)
        button.foreground = JBColor(
            Color(57, 95, 151),
            Color(141, 190, 255),
        )
        button.putClientProperty("JButton.buttonType", "borderless")
    }

    private fun styleIconActionButton(button: JButton, icon: javax.swing.Icon, tooltip: String) {
        styleTextActionButton(button)
        button.margin = JBUI.insets(0, 2, 0, 2)
        button.border = JBUI.Borders.empty(0, 2, 0, 2)
        button.icon = icon
        button.text = ""
        button.toolTipText = tooltip
        button.accessibleContext.accessibleName = tooltip
        button.preferredSize = JBUI.size(26, 22)
        button.minimumSize = JBUI.size(26, 22)
    }

    companion object {
        private const val PREVIEW_MAX_LINES = 15
        private const val PREVIEW_MAX_CHARS = 1200
        private const val DIFF_PREVIEW_MAX_LINES = 220
        private const val COPY_FEEDBACK_DURATION_MS = 900
        private const val COPY_FEEDBACK_TIMER_KEY = "spec.card.copy.feedback.timer"
        private const val PREVIEW_ELLIPSIS_LINE = "......"
        private val PREVIEW_HEADER_REGEX = Regex("preview|预览", RegexOption.IGNORE_CASE)
    }
}
