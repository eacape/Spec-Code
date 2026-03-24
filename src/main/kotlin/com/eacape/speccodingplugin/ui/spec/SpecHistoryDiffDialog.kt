package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMarkdownSanitizer
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.eacape.speccodingplugin.ui.chat.MarkdownRenderer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import com.intellij.ui.components.JBCheckBox
import javax.swing.JTextPane

class SpecHistoryDiffDialog(
    private val phase: SpecPhase,
    private val currentDocument: SpecDocument,
    snapshots: List<SnapshotVersion>,
    private val onDeleteSnapshot: (SnapshotVersion) -> Boolean,
    private val onPruneSnapshots: (Int) -> Int,
    private val onExportSummary: (String) -> Result<String>,
) : DialogWrapper(true) {

    data class SnapshotVersion(
        val snapshotId: String,
        val createdAt: Long,
        val document: SpecDocument,
    ) {
        override fun toString(): String {
            return SpecCodingBundle.message(
                "spec.history.dialog.snapshot.option",
                formatTimestamp(createdAt),
                snapshotId,
            )
        }
    }

    private sealed interface CompareTargetOption

    private object CurrentDocumentTargetOption : CompareTargetOption {
        override fun toString(): String = SpecCodingBundle.message("spec.history.dialog.compareTarget.current")
    }

    private data class SnapshotTargetOption(
        val snapshot: SnapshotVersion,
    ) : CompareTargetOption {
        override fun toString(): String = snapshot.toString()
    }

    private data class ComparisonTargetSelection(
        val displayName: String,
        val content: String,
    )

    private val snapshotCombo = JComboBox(snapshots.toTypedArray())
    private val compareTargetCombo = JComboBox<CompareTargetOption>()
    private val compareTargetLabel = JBLabel(SpecCodingBundle.message("spec.history.dialog.compareTarget"))
    private val statusLabel = JBLabel("")
    private val summaryOnlyCheckBox = JBCheckBox(SpecCodingBundle.message("spec.history.dialog.summaryOnly"), false)
    private val summaryLinesSpinner = JSpinner(SpinnerNumberModel(DEFAULT_SUMMARY_LINES, 1, 500, 1))
    private val keepLatestSpinner = JSpinner(SpinnerNumberModel(DEFAULT_KEEP_LATEST, 0, 500, 1))
    private val deleteSnapshotButton = JButton(SpecCodingBundle.message("spec.history.dialog.deleteSnapshot"))
    private val pruneSnapshotsButton = JButton(SpecCodingBundle.message("spec.history.dialog.pruneSnapshots"))
    private val exportSummaryButton = JButton(SpecCodingBundle.message("spec.history.dialog.exportSummary"))
    private val baselinePane = JTextPane()
    private val targetPane = JTextPane()
    private val targetPanelTitle = JBLabel(SpecCodingBundle.message("spec.history.dialog.target"))
    private val statusChipPanel = JPanel(BorderLayout())
    private var updatingCompareTargetOptions = false

    init {
        title = SpecCodingBundle.message("spec.history.dialog.title")
        setupToolbarControls()
        setupAreas()
        rebuildCompareTargetOptions(preferredSnapshotId = null)
        snapshotCombo.addActionListener { refreshComparison() }
        compareTargetCombo.addActionListener {
            if (!updatingCompareTargetOptions) {
                refreshComparison()
            }
        }
        summaryOnlyCheckBox.addActionListener {
            summaryLinesSpinner.isEnabled = summaryOnlyCheckBox.isSelected
            refreshComparison()
        }
        summaryLinesSpinner.addChangeListener { refreshComparison() }
        deleteSnapshotButton.addActionListener { onDeleteSnapshotClicked() }
        pruneSnapshotsButton.addActionListener { onPruneSnapshotsClicked() }
        exportSummaryButton.addActionListener { onExportSummaryClicked() }
        summaryLinesSpinner.isEnabled = summaryOnlyCheckBox.isSelected
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.preferredSize = Dimension(JBUI.scale(980), JBUI.scale(620))
        panel.border = JBUI.Borders.empty(8)
        panel.isOpaque = false

        val controlsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(createHeaderChipLabel(SpecCodingBundle.message("spec.history.dialog.phase", phase.displayName)))
            add(JBLabel(SpecCodingBundle.message("spec.history.dialog.snapshot")))
            add(snapshotCombo)
            add(compareTargetLabel)
            add(compareTargetCombo)
            add(summaryOnlyCheckBox)
            add(JBLabel(SpecCodingBundle.message("spec.history.dialog.summaryLines")))
            add(summaryLinesSpinner)
        }
        val actionsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(exportSummaryButton)
            add(deleteSnapshotButton)
            add(JBLabel(SpecCodingBundle.message("spec.history.dialog.keepLatest")))
            add(keepLatestSpinner)
            add(pruneSnapshotsButton)
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyLeft(JBUI.scale(4))
                    add(statusChipPanel, BorderLayout.WEST)
                },
            )
        }
        val header = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(createSectionContainer(controlsRow, top = 6, left = 8, bottom = 6, right = 8), BorderLayout.NORTH)
            add(createSectionContainer(actionsRow, top = 6, left = 8, bottom = 6, right = 8), BorderLayout.CENTER)
        }
        panel.add(header, BorderLayout.NORTH)

        val baselinePanel = createContentPanel(
            SpecCodingBundle.message("spec.history.dialog.baseline"),
            baselinePane,
        )
        val targetPanel = createContentPanel(
            targetPanelTitle.text,
            targetPane,
            targetPanelTitle,
        )

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, baselinePanel, targetPanel).apply {
            dividerSize = JBUI.scale(6)
            isContinuousLayout = true
            border = JBUI.Borders.empty()
            background = PANEL_BG
        }
        split.resizeWeight = 0.5
        split.dividerLocation = JBUI.scale(490)
        panel.add(split, BorderLayout.CENTER)

        refreshComparison()
        return panel
    }

    override fun createActions() = arrayOf(okAction)

    private fun setupAreas() {
        baselinePane.isEditable = false
        baselinePane.isOpaque = false
        baselinePane.border = JBUI.Borders.empty(2, 4)
        baselinePane.font = JBUI.Fonts.label()

        targetPane.isEditable = false
        targetPane.isOpaque = false
        targetPane.border = JBUI.Borders.empty(2, 4)
        targetPane.font = JBUI.Fonts.label()

        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = STATUS_TEXT_FG
        statusChipPanel.isOpaque = true
        statusChipPanel.background = STATUS_CHIP_BG
        statusChipPanel.border = SpecUiStyle.roundedCardBorder(
            lineColor = STATUS_CHIP_BORDER,
            arc = JBUI.scale(12),
            top = 3,
            left = 8,
            bottom = 3,
            right = 8,
        )
        statusChipPanel.removeAll()
        statusChipPanel.add(statusLabel, BorderLayout.CENTER)
    }

    private fun setupToolbarControls() {
        styleCompactCombo(snapshotCombo, minWidth = 140, maxWidth = 420)
        styleCompactCombo(compareTargetCombo, minWidth = 140, maxWidth = 420)
        styleNumberSpinner(summaryLinesSpinner, width = 72)
        styleNumberSpinner(keepLatestSpinner, width = 66)
        styleToolbarCheckBox(summaryOnlyCheckBox)
        styleToolbarButton(exportSummaryButton)
        styleToolbarButton(deleteSnapshotButton)
        styleToolbarButton(pruneSnapshotsButton)
    }

    private fun createContentPanel(
        title: String,
        pane: JTextPane,
        titleLabel: JBLabel = JBLabel(title),
    ): JPanel {
        titleLabel.text = title
        titleLabel.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        titleLabel.foreground = TITLE_FG
        return createSectionContainer(
            JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                isOpaque = false
                add(titleLabel, BorderLayout.NORTH)
                add(
                    JBScrollPane(pane).apply {
                        border = BorderFactory.createEmptyBorder()
                        viewport.isOpaque = false
                        isOpaque = false
                    },
                    BorderLayout.CENTER,
                )
            },
            top = 6,
            left = 8,
            bottom = 8,
            right = 8,
        )
    }

    private fun createSectionContainer(
        content: JComponent,
        top: Int = 2,
        left: Int = 2,
        bottom: Int = 2,
        right: Int = 2,
    ): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = PANEL_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = PANEL_BORDER,
                arc = JBUI.scale(12),
                top = top,
                left = left,
                bottom = bottom,
                right = right,
            )
            add(content, BorderLayout.CENTER)
        }
    }

    private fun createHeaderChipLabel(text: String): JBLabel {
        return JBLabel(text).apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            foreground = TITLE_FG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = CHIP_BORDER,
                arc = JBUI.scale(10),
                top = 1,
                left = 8,
                bottom = 1,
                right = 8,
            )
            isOpaque = true
            background = CHIP_BG
        }
    }

    private fun styleCompactCombo(combo: JComboBox<*>, minWidth: Int, maxWidth: Int) {
        combo.font = JBUI.Fonts.smallFont()
        combo.background = CONTROL_BG
        combo.foreground = CONTROL_FG
        combo.putClientProperty("JComponent.roundRect", false)
        combo.putClientProperty("JComboBox.isBorderless", true)
        combo.putClientProperty("ComboBox.isBorderless", true)
        combo.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(CONTROL_BORDER, JBUI.scale(10)),
            JBUI.Borders.empty(0, 6, 0, 6),
        )
        combo.isOpaque = true
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = combo,
            minWidth = JBUI.scale(minWidth),
            maxWidth = JBUI.scale(maxWidth),
            height = JBUI.scale(28),
        )
    }

    private fun styleNumberSpinner(spinner: JSpinner, width: Int) {
        val height = JBUI.scale(28)
        spinner.preferredSize = Dimension(JBUI.scale(width), height)
        spinner.minimumSize = Dimension(JBUI.scale(width), height)
        spinner.maximumSize = Dimension(JBUI.scale(width), height)
        spinner.font = JBUI.Fonts.smallFont()
    }

    private fun styleToolbarCheckBox(checkBox: JCheckBox) {
        checkBox.isOpaque = false
        checkBox.font = JBUI.Fonts.smallFont()
        checkBox.foreground = CONTROL_FG
    }

    private fun styleToolbarButton(button: JButton) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.font = button.font.deriveFont(Font.BOLD, 10f)
        button.margin = JBUI.insets(1, 6, 1, 6)
        button.isOpaque = true
        button.foreground = BUTTON_FG
        button.background = BUTTON_BG
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
            JBUI.Borders.empty(1, 7, 1, 7),
        )
        SpecUiStyle.applyRoundRect(button, arc = 10)
        val textWidth = button.getFontMetrics(button.font).stringWidth(button.text ?: "")
        val insets = button.insets
        val width = maxOf(
            button.preferredSize?.width ?: 0,
            textWidth + insets.left + insets.right + JBUI.scale(8),
            JBUI.scale(76),
        )
        button.preferredSize = JBUI.size(width, JBUI.scale(28))
    }

    private fun refreshComparison() {
        val selected = snapshotCombo.selectedItem as? SnapshotVersion
        if (selected == null) {
            targetPanelTitle.text = SpecCodingBundle.message("spec.history.dialog.target")
            renderMarkdown(baselinePane, "")
            renderMarkdown(targetPane, buildDisplayContent(currentDocument.content))
            statusLabel.text = SpecCodingBundle.message("spec.history.noSnapshot")
            deleteSnapshotButton.isEnabled = false
            pruneSnapshotsButton.isEnabled = false
            exportSummaryButton.isEnabled = false
            return
        }

        exportSummaryButton.isEnabled = true
        val canDelete = canDeleteSnapshot(selected, currentSnapshots())
        deleteSnapshotButton.isEnabled = canDelete
        deleteSnapshotButton.toolTipText = if (canDelete) {
            null
        } else {
            SpecCodingBundle.message("spec.history.dialog.deleteLatestBlocked")
        }
        pruneSnapshotsButton.isEnabled = snapshotCombo.itemCount > 0

        val baselineContent = selected.document.content
        val targetSelection = resolveTargetSelection()
        val targetContent = targetSelection.content
        val changed = isChanged(baselineContent, targetContent)
        val diffStats = computeLineDiffStats(baselineContent, targetContent)
        val statusText = if (changed) {
            SpecCodingBundle.message("spec.history.diff.changed")
        } else {
            SpecCodingBundle.message("spec.history.diff.unchanged")
        }

        statusLabel.text = SpecCodingBundle.message(
            "spec.history.dialog.status.withStatsAndTarget",
            selected.snapshotId,
            targetSelection.displayName,
            statusText,
            diffStats.addedLines,
            diffStats.removedLines,
        )
        renderMarkdown(baselinePane, buildDisplayContent(baselineContent))
        renderMarkdown(targetPane, buildDisplayContent(targetContent))
        targetPanelTitle.text = buildTargetPanelTitle(targetSelection.displayName)
    }

    private fun buildTargetPanelTitle(targetDisplayName: String): String {
        val targetTitle = SpecCodingBundle.message("spec.history.dialog.target")
        val currentLabel = SpecCodingBundle.message("spec.history.dialog.compareTarget.current")
        if (targetDisplayName == currentLabel) {
            return "$targetTitle · $currentLabel"
        }
        return "$targetTitle · $targetDisplayName"
    }

    private fun renderMarkdown(pane: JTextPane, markdown: String) {
        runCatching {
            MarkdownRenderer.render(pane, markdown)
            pane.caretPosition = 0
        }.onFailure {
            pane.text = markdown
            pane.caretPosition = 0
        }
    }

    private fun onDeleteSnapshotClicked() {
        val selected = snapshotCombo.selectedItem as? SnapshotVersion ?: return
        if (!canDeleteSnapshot(selected, currentSnapshots())) {
            val message = SpecCodingBundle.message("spec.history.dialog.deleteLatestBlocked")
            statusLabel.text = message
            Messages.showWarningDialog(
                message,
                SpecCodingBundle.message("spec.history.dialog.deleteSnapshot"),
            )
            return
        }

        val confirm = Messages.showYesNoDialog(
            SpecCodingBundle.message("spec.history.dialog.deleteConfirm", selected.snapshotId),
            SpecCodingBundle.message("spec.history.dialog.deleteSnapshot"),
            Messages.getQuestionIcon(),
        )
        if (confirm != Messages.YES) {
            return
        }

        val deleted = onDeleteSnapshot(selected)
        if (!deleted) {
            statusLabel.text = SpecCodingBundle.message("spec.history.dialog.deleteFailed")
            return
        }

        val currentTargetSnapshotId = selectedTargetSnapshotId()
        snapshotCombo.removeItem(selected)
        rebuildCompareTargetOptions(currentTargetSnapshotId)
        statusLabel.text = SpecCodingBundle.message("spec.history.dialog.deleteDone")
        refreshComparison()
    }

    private fun onPruneSnapshotsClicked() {
        val keepLatest = (keepLatestSpinner.value as? Int) ?: DEFAULT_KEEP_LATEST
        val pruned = onPruneSnapshots(keepLatest)
        if (pruned < 0) {
            statusLabel.text = SpecCodingBundle.message("spec.history.dialog.pruneFailed")
            return
        }

        val items = mutableListOf<SnapshotVersion>()
        for (idx in 0 until snapshotCombo.itemCount) {
            val item = snapshotCombo.getItemAt(idx)
            if (item != null) {
                items += item
            }
        }
        val refreshed = items.sortedByDescending { it.createdAt }.take(keepLatest)
        snapshotCombo.removeAllItems()
        refreshed.forEach { snapshotCombo.addItem(it) }

        val targetSnapshotId = selectedTargetSnapshotId()
        rebuildCompareTargetOptions(targetSnapshotId)
        if (snapshotCombo.itemCount > 0) {
            snapshotCombo.selectedIndex = 0
        }
        statusLabel.text = SpecCodingBundle.message("spec.history.dialog.pruneDone", pruned)
        refreshComparison()
    }

    private fun currentSnapshots(): List<SnapshotVersion> {
        val snapshots = mutableListOf<SnapshotVersion>()
        for (idx in 0 until snapshotCombo.itemCount) {
            val item = snapshotCombo.getItemAt(idx)
            if (item != null) {
                snapshots += item
            }
        }
        return snapshots
    }

    private fun buildDisplayContent(content: String): String {
        val sanitizedContent = SpecMarkdownSanitizer.sanitize(content)
        if (!summaryOnlyCheckBox.isSelected) {
            return sanitizedContent
        }

        val maxLines = (summaryLinesSpinner.value as? Int) ?: DEFAULT_SUMMARY_LINES
        val summary = summarizeTopLines(sanitizedContent, maxLines)
        if (summary.omittedLines <= 0) {
            return summary.text
        }

        return buildString {
            append(summary.text)
            if (summary.text.isNotEmpty()) {
                appendLine()
            }
            append(SpecCodingBundle.message("spec.history.dialog.summary.more", summary.omittedLines))
        }
    }

    private fun resolveTargetSelection(): ComparisonTargetSelection {
        return when (val option = compareTargetCombo.selectedItem as? CompareTargetOption) {
            is SnapshotTargetOption -> {
                ComparisonTargetSelection(
                    displayName = option.snapshot.snapshotId,
                    content = option.snapshot.document.content,
                )
            }

            else -> {
                ComparisonTargetSelection(
                    displayName = SpecCodingBundle.message("spec.history.dialog.compareTarget.current"),
                    content = currentDocument.content,
                )
            }
        }
    }

    private fun selectedTargetSnapshotId(): String? {
        val option = compareTargetCombo.selectedItem as? SnapshotTargetOption
        return option?.snapshot?.snapshotId
    }

    private fun rebuildCompareTargetOptions(preferredSnapshotId: String?) {
        val preferred = preferredSnapshotId ?: selectedTargetSnapshotId()
        updatingCompareTargetOptions = true
        try {
            compareTargetCombo.removeAllItems()
            compareTargetCombo.addItem(CurrentDocumentTargetOption)
            currentSnapshots().forEach { snapshot ->
                compareTargetCombo.addItem(SnapshotTargetOption(snapshot))
            }

            val targetIndex = if (preferred == null) {
                0
            } else {
                (0 until compareTargetCombo.itemCount)
                    .firstOrNull { index ->
                        val option = compareTargetCombo.getItemAt(index)
                        option is SnapshotTargetOption && option.snapshot.snapshotId == preferred
                    } ?: 0
            }
            if (compareTargetCombo.itemCount > 0) {
                compareTargetCombo.selectedIndex = targetIndex
            }
        } finally {
            updatingCompareTargetOptions = false
        }
    }

    private fun onExportSummaryClicked() {
        val baselineSnapshot = snapshotCombo.selectedItem as? SnapshotVersion ?: return
        val targetSelection = resolveTargetSelection()
        val maxLines = (summaryLinesSpinner.value as? Int) ?: DEFAULT_SUMMARY_LINES
        val summary = buildComparisonSummary(
            baselineContent = baselineSnapshot.document.content,
            targetContent = targetSelection.content,
            maxLines = maxLines,
        )
        val exportContent = buildExportContent(
            phaseDisplayName = phase.displayName,
            baselineLabel = baselineSnapshot.snapshotId,
            targetLabel = targetSelection.displayName,
            summary = summary,
        )

        exportSummaryButton.isEnabled = false
        statusLabel.text = SpecCodingBundle.message("spec.history.dialog.exporting")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = onExportSummary(exportContent)
            ApplicationManager.getApplication().invokeLater {
                exportSummaryButton.isEnabled = snapshotCombo.itemCount > 0
                result.onSuccess { fileName ->
                    statusLabel.text = SpecCodingBundle.message("spec.history.dialog.exportDone", fileName)
                }.onFailure { error ->
                    statusLabel.text = SpecCodingBundle.message(
                        "spec.history.dialog.exportFailed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
                }
            }
        }
    }

    private fun buildExportContent(
        phaseDisplayName: String,
        baselineLabel: String,
        targetLabel: String,
        summary: ComparisonSummary,
    ): String {
        val statusText = if (summary.changed) {
            SpecCodingBundle.message("spec.history.diff.changed")
        } else {
            SpecCodingBundle.message("spec.history.diff.unchanged")
        }
        return buildString {
            appendLine("# ${SpecCodingBundle.message("spec.history.export.title")}")
            appendLine()
            appendLine(SpecCodingBundle.message("spec.history.export.phase", phaseDisplayName))
            appendLine(SpecCodingBundle.message("spec.history.export.baseline", baselineLabel))
            appendLine(SpecCodingBundle.message("spec.history.export.target", targetLabel))
            appendLine(SpecCodingBundle.message("spec.history.export.status", statusText))
            appendLine(SpecCodingBundle.message("spec.history.export.added", summary.addedLines))
            appendLine(SpecCodingBundle.message("spec.history.export.removed", summary.removedLines))
            appendLine()
            appendLine(SpecCodingBundle.message("spec.history.export.baselineSnippet"))
            appendLine()
            appendLine(renderSummaryForExport(summary.baselineSummary))
            appendLine()
            appendLine(SpecCodingBundle.message("spec.history.export.targetSnippet"))
            appendLine()
            append(renderSummaryForExport(summary.targetSummary))
        }
    }

    private fun renderSummaryForExport(summary: SummaryResult): String {
        if (summary.omittedLines <= 0) {
            return summary.text
        }
        return buildString {
            append(summary.text)
            if (summary.text.isNotEmpty()) {
                appendLine()
            }
            append(SpecCodingBundle.message("spec.history.dialog.summary.more", summary.omittedLines))
        }
    }

    companion object {
        private const val DEFAULT_SUMMARY_LINES = 40
        private const val DEFAULT_KEEP_LATEST = 5

        private val PANEL_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val PANEL_BORDER = JBColor(Color(204, 215, 233), Color(84, 92, 105))
        private val CONTROL_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val CONTROL_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val CONTROL_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val TITLE_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val CHIP_BG = JBColor(Color(234, 243, 255), Color(61, 71, 84))
        private val CHIP_BORDER = JBColor(Color(173, 194, 223), Color(96, 112, 132))
        private val STATUS_CHIP_BG = JBColor(Color(236, 244, 255), Color(66, 76, 91))
        private val STATUS_CHIP_BORDER = JBColor(Color(178, 198, 226), Color(99, 116, 140))
        private val STATUS_TEXT_FG = JBColor(Color(52, 72, 106), Color(201, 213, 232))

        internal data class LineDiffStats(
            val addedLines: Int,
            val removedLines: Int,
        )

        internal data class SummaryResult(
            val text: String,
            val omittedLines: Int,
        )

        internal data class ComparisonSummary(
            val changed: Boolean,
            val addedLines: Int,
            val removedLines: Int,
            val baselineSummary: SummaryResult,
            val targetSummary: SummaryResult,
        )

        private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())

        internal fun isChanged(
            baselineContent: String,
            currentContent: String,
        ): Boolean {
            return baselineContent.replace("\r\n", "\n").trim() != currentContent.replace("\r\n", "\n").trim()
        }

        internal fun summarizeTopLines(
            content: String,
            maxLines: Int,
        ): SummaryResult {
            val normalized = content.replace("\r\n", "\n").trim()
            if (normalized.isBlank()) {
                return SummaryResult("", 0)
            }

            val lines = normalized.split("\n")
            if (lines.size <= maxLines) {
                return SummaryResult(normalized, 0)
            }

            return SummaryResult(
                text = lines.take(maxLines).joinToString("\n"),
                omittedLines = lines.size - maxLines,
            )
        }

        internal fun computeLineDiffStats(
            baselineContent: String,
            currentContent: String,
        ): LineDiffStats {
            val baselineLines = normalizeLines(baselineContent)
            val currentLines = normalizeLines(currentContent)

            if (baselineLines.isEmpty() && currentLines.isEmpty()) {
                return LineDiffStats(0, 0)
            }

            val lcsLength = lcsLength(baselineLines, currentLines)
            val removed = baselineLines.size - lcsLength
            val added = currentLines.size - lcsLength
            return LineDiffStats(
                addedLines = added.coerceAtLeast(0),
                removedLines = removed.coerceAtLeast(0),
            )
        }

        internal fun buildComparisonSummary(
            baselineContent: String,
            targetContent: String,
            maxLines: Int,
        ): ComparisonSummary {
            val normalizedMaxLines = maxLines.coerceAtLeast(1)
            val stats = computeLineDiffStats(baselineContent, targetContent)
            return ComparisonSummary(
                changed = isChanged(baselineContent, targetContent),
                addedLines = stats.addedLines,
                removedLines = stats.removedLines,
                baselineSummary = summarizeTopLines(baselineContent, normalizedMaxLines),
                targetSummary = summarizeTopLines(targetContent, normalizedMaxLines),
            )
        }

        internal fun canDeleteSnapshot(
            selected: SnapshotVersion,
            snapshots: List<SnapshotVersion>,
        ): Boolean {
            val latest = snapshots.maxByOrNull { it.createdAt } ?: return false
            return latest.snapshotId != selected.snapshotId
        }

        private fun normalizeLines(content: String): List<String> {
            val normalized = content.replace("\r\n", "\n").trim()
            if (normalized.isBlank()) {
                return emptyList()
            }
            return normalized.split("\n")
        }

        private fun lcsLength(
            baselineLines: List<String>,
            currentLines: List<String>,
        ): Int {
            val rows = baselineLines.size
            val cols = currentLines.size
            val dp = Array(rows + 1) { IntArray(cols + 1) }

            for (i in 1..rows) {
                for (j in 1..cols) {
                    dp[i][j] = if (baselineLines[i - 1] == currentLines[j - 1]) {
                        dp[i - 1][j - 1] + 1
                    } else {
                        maxOf(dp[i - 1][j], dp[i][j - 1])
                    }
                }
            }

            return dp[rows][cols]
        }

        private fun formatTimestamp(timestamp: Long): String {
            return formatter.format(Instant.ofEpochMilli(timestamp))
        }
    }
}
