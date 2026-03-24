package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecArtifactDelta
import com.eacape.speccodingplugin.spec.SpecDeltaExportFormat
import com.eacape.speccodingplugin.spec.SpecDeltaExportResult
import com.eacape.speccodingplugin.spec.SpecDeltaStatus
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflowDelta
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.eacape.speccodingplugin.ui.actions.SpecWorkflowActionSupport
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.RowFilter
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

class SpecDeltaDialog(
    private val project: Project,
    private val delta: SpecWorkflowDelta,
    private val onOpenHistoryDiff: ((phase: SpecPhase) -> Unit)? = null,
    private val onExportReport: ((format: SpecDeltaExportFormat) -> Result<SpecDeltaExportResult>)? = null,
    private val onReportExported: ((result: SpecDeltaExportResult) -> Unit)? = null,
) : DialogWrapper(true) {

    internal enum class StatusFilterOption(
        private val status: SpecDeltaStatus?,
    ) {
        ALL(null),
        ADDED(SpecDeltaStatus.ADDED),
        MODIFIED(SpecDeltaStatus.MODIFIED),
        REMOVED(SpecDeltaStatus.REMOVED),
        UNCHANGED(SpecDeltaStatus.UNCHANGED);

        fun match(statusToCheck: SpecDeltaStatus): Boolean {
            return status == null || statusToCheck == status
        }

        override fun toString(): String {
            return when (this) {
                ALL -> SpecCodingBundle.message("spec.delta.filter.status.all")
                ADDED -> SpecCodingBundle.message("spec.delta.status.added")
                MODIFIED -> SpecCodingBundle.message("spec.delta.status.modified")
                REMOVED -> SpecCodingBundle.message("spec.delta.status.removed")
                UNCHANGED -> SpecCodingBundle.message("spec.delta.status.unchanged")
            }
        }
    }

    private val tableModel = DeltaTableModel(delta.artifactDeltas)
    private val deltaTable = JTable(tableModel)
    private val tableSorter = TableRowSorter(tableModel)
    private val summaryLabel = JBLabel(buildSummaryText(delta))
    private val statusFilterLabel = JBLabel(SpecCodingBundle.message("spec.delta.filter.status.label"))
    private val statusFilterCombo = JComboBox(StatusFilterOption.entries.toTypedArray())
    private val showChangesOnlyCheckBox = JBCheckBox(SpecCodingBundle.message("spec.delta.filter.changedOnly"), false)
    private val previewDiffButton = JButton(SpecCodingBundle.message("spec.delta.preview"))
    private val historyDiffButton = JButton(SpecCodingBundle.message("spec.detail.historyDiff"))
    private val exportMarkdownButton = JButton(SpecCodingBundle.message("spec.delta.export.markdown"))
    private val exportHtmlButton = JButton(SpecCodingBundle.message("spec.delta.export.html"))
    private val exportStatusLabel = JBLabel(SpecCodingBundle.message("spec.delta.export.status.idle"))
    private val detailArea = JBTextArea()
    private var exportInProgress = false

    init {
        title = SpecCodingBundle.message("spec.delta.dialog.title")
        restoreFilterState()
        setupTable()
        setupDetailArea()
        setupActions()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(JBUI.scale(760), JBUI.scale(460))
        panel.border = JBUI.Borders.empty(8)

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        header.isOpaque = false
        header.add(summaryLabel)
        header.add(statusFilterLabel)
        header.add(statusFilterCombo)
        header.add(showChangesOnlyCheckBox)
        previewDiffButton.addActionListener { onPreviewDiffClicked() }
        header.add(previewDiffButton)
        historyDiffButton.isVisible = onOpenHistoryDiff != null
        historyDiffButton.addActionListener { onHistoryDiffClicked() }
        header.add(historyDiffButton)
        exportMarkdownButton.addActionListener { onExportClicked(SpecDeltaExportFormat.MARKDOWN) }
        exportHtmlButton.addActionListener { onExportClicked(SpecDeltaExportFormat.HTML) }
        header.add(exportMarkdownButton)
        header.add(exportHtmlButton)
        panel.add(header, BorderLayout.NORTH)

        val centerSplit = javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT)
        centerSplit.resizeWeight = 0.56
        centerSplit.topComponent = JBScrollPane(deltaTable)
        centerSplit.bottomComponent = JBScrollPane(detailArea)
        panel.add(centerSplit, BorderLayout.CENTER)
        panel.add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(8)
                add(exportStatusLabel, BorderLayout.WEST)
            },
            BorderLayout.SOUTH,
        )

        applyFilters()

        return panel
    }

    override fun createActions() = arrayOf(okAction)

    private fun setupTable() {
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = statusFilterCombo,
            minWidth = JBUI.scale(82),
            maxWidth = JBUI.scale(150),
            height = statusFilterCombo.preferredSize.height.takeIf { it > 0 } ?: JBUI.scale(24),
        )
        deltaTable.rowSorter = tableSorter
        deltaTable.columnModel.getColumn(1).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int,
            ): Component {
                val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                if (component is DefaultTableCellRenderer) {
                    val modelRow = table.convertRowIndexToModel(row)
                    val status = tableModel.deltaAt(modelRow).status
                    component.foreground = if (isSelected) {
                        table.selectionForeground
                    } else {
                        statusColor(status)
                    }
                }
                return component
            }
        }
        deltaTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val viewRow = deltaTable.selectedRow
                if (viewRow >= 0) {
                    updateDetailByViewRow(viewRow)
                }
                updateActionState()
            }
        }
        deltaTable.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1) {
                        onPreviewDiffClicked()
                    }
                }
            },
        )
        showChangesOnlyCheckBox.addActionListener { applyFilters() }
        statusFilterCombo.addActionListener { applyFilters() }
        updateActionState()
    }

    private fun setupDetailArea() {
        detailArea.isEditable = false
        detailArea.lineWrap = true
        detailArea.wrapStyleWord = true
    }

    private fun setupActions() {
        exportStatusLabel.font = JBUI.Fonts.smallFont()
    }

    private fun updateDetailByViewRow(viewRow: Int) {
        val modelRow = deltaTable.convertRowIndexToModel(viewRow)
        val delta = tableModel.deltaAt(modelRow)
        detailArea.text = buildDetailText(delta)
        detailArea.caretPosition = 0
    }

    private fun applyFilters() {
        val selectedOption = statusFilterCombo.selectedItem as? StatusFilterOption ?: StatusFilterOption.ALL
        val changedOnly = showChangesOnlyCheckBox.isSelected
        persistFilterState(selectedOption, changedOnly)

        tableSorter.rowFilter = object : RowFilter<DeltaTableModel, Int>() {
            override fun include(entry: Entry<out DeltaTableModel, out Int>): Boolean {
                val row = entry.identifier
                val status = tableModel.deltaAt(row).status
                return shouldShow(status, selectedOption, changedOnly)
            }
        }

        if (deltaTable.rowCount > 0) {
            deltaTable.setRowSelectionInterval(0, 0)
            updateDetailByViewRow(0)
        } else {
            detailArea.text = SpecCodingBundle.message("spec.delta.filter.empty")
            detailArea.caretPosition = 0
        }
        updateActionState()
    }

    private fun onHistoryDiffClicked() {
        val selected = selectedDelta() ?: return
        if (selected.artifact.phase == null || selected.targetDocument == null) {
            return
        }
        onOpenHistoryDiff?.invoke(selected.artifact.phase)
    }

    private fun onPreviewDiffClicked() {
        val selected = selectedDelta()?.takeIf(::canPreviewDiff) ?: return
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            buildPreviewTitle(selected),
            factory.create(contentForDiff(selected.baselineContent)),
            factory.create(contentForDiff(selected.targetContent)),
            SpecCodingBundle.message("spec.delta.preview.side.baseline", buildSideLabel(selected, baseline = true)),
            SpecCodingBundle.message("spec.delta.preview.side.target", buildSideLabel(selected, baseline = false)),
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun onExportClicked(format: SpecDeltaExportFormat) {
        if (exportInProgress) {
            return
        }
        setExportInProgress(true, format)
        val exportAction = onExportReport ?: { requestedFormat ->
            com.eacape.speccodingplugin.spec.SpecDeltaService.getInstance(project).exportReport(delta, requestedFormat)
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = exportAction(format)
            ApplicationManager.getApplication().invokeLater {
                setExportInProgress(false, format)
                result.onSuccess { export ->
                    exportStatusLabel.text = SpecCodingBundle.message("spec.delta.export.success", export.fileName)
                    SpecWorkflowActionSupport.openFile(project, export.filePath)
                    onReportExported?.invoke(export)
                }.onFailure { error ->
                    exportStatusLabel.text = SpecCodingBundle.message(
                        "spec.delta.export.failed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
                }
            }
        }
    }

    private fun updateActionState() {
        val selected = selectedDelta()
        previewDiffButton.isEnabled = selected?.let(::canPreviewDiff) == true
        historyDiffButton.isEnabled = selected?.targetDocument != null && selected.artifact.phase != null
        exportMarkdownButton.isEnabled = !exportInProgress
        exportHtmlButton.isEnabled = !exportInProgress
    }

    private fun selectedDelta(): SpecArtifactDelta? {
        val viewRow = deltaTable.selectedRow
        if (viewRow < 0 || viewRow >= deltaTable.rowCount) {
            return null
        }
        val modelRow = deltaTable.convertRowIndexToModel(viewRow)
        return tableModel.deltaAt(modelRow)
    }

    private fun restoreFilterState() {
        val properties = PropertiesComponent.getInstance(project)
        val status = parseStatusFilter(properties.getValue(KEY_STATUS_FILTER))
        val changedOnly = properties.getBoolean(KEY_CHANGED_ONLY, false)
        statusFilterCombo.selectedItem = status
        showChangesOnlyCheckBox.isSelected = changedOnly
    }

    private fun persistFilterState(
        statusFilter: StatusFilterOption,
        changedOnly: Boolean,
    ) {
        val properties = PropertiesComponent.getInstance(project)
        properties.setValue(KEY_STATUS_FILTER, statusFilter.name, StatusFilterOption.ALL.name)
        properties.setValue(KEY_CHANGED_ONLY, changedOnly, false)
    }

    private fun buildSummaryText(delta: SpecWorkflowDelta): String {
        val added = delta.count(SpecDeltaStatus.ADDED)
        val modified = delta.count(SpecDeltaStatus.MODIFIED)
        val removed = delta.count(SpecDeltaStatus.REMOVED)
        val unchanged = delta.count(SpecDeltaStatus.UNCHANGED)
        return SpecCodingBundle.message(
            "spec.delta.summary",
            delta.baselineWorkflowId,
            delta.targetWorkflowId,
            added,
            modified,
            removed,
            unchanged,
        )
    }

    private fun buildDetailText(delta: SpecArtifactDelta): String {
        return buildString {
            appendLine(SpecCodingBundle.message("spec.delta.detail.phase", delta.artifact.displayName))
            appendLine(SpecCodingBundle.message("spec.delta.detail.status", localizeStatus(delta.status)))
            appendLine()
            appendLine(SpecCodingBundle.message("spec.delta.detail.baseline"))
            appendLine(snippet(delta.baselineContent))
            appendLine()
            appendLine(SpecCodingBundle.message("spec.delta.detail.target"))
            appendLine(snippet(delta.targetContent))
            if (delta.unifiedDiff.isNotBlank()) {
                appendLine()
                appendLine("Diff:")
                appendLine(delta.unifiedDiff)
            }
        }
    }

    private fun snippet(content: String?): String {
        if (content.isNullOrBlank()) {
            return SpecCodingBundle.message("spec.delta.detail.empty")
        }
        val normalized = content.replace("\r\n", "\n").trim()
        return if (normalized.length <= 300) {
            normalized
        } else {
            normalized.take(300) + SpecCodingBundle.message("spec.delta.detail.truncatedSuffix")
        }
    }

    private fun localizeStatus(status: SpecDeltaStatus): String {
        return when (status) {
            SpecDeltaStatus.ADDED -> SpecCodingBundle.message("spec.delta.status.added")
            SpecDeltaStatus.MODIFIED -> SpecCodingBundle.message("spec.delta.status.modified")
            SpecDeltaStatus.REMOVED -> SpecCodingBundle.message("spec.delta.status.removed")
            SpecDeltaStatus.UNCHANGED -> SpecCodingBundle.message("spec.delta.status.unchanged")
        }
    }

    private fun buildPreviewTitle(delta: SpecArtifactDelta): String {
        return SpecCodingBundle.message(
            "spec.delta.preview.title",
            delta.artifact.displayName,
            this.delta.baselineWorkflowId,
            this.delta.targetWorkflowId,
        )
    }

    private fun buildSideLabel(
        delta: SpecArtifactDelta,
        baseline: Boolean,
    ): String {
        val documentTitle = if (baseline) {
            delta.baselineDocument?.metadata?.title
        } else {
            delta.targetDocument?.metadata?.title
        }?.takeIf { it.isNotBlank() }
        return documentTitle ?: delta.artifact.fileName
    }

    private fun setExportInProgress(
        inProgress: Boolean,
        format: SpecDeltaExportFormat,
    ) {
        exportInProgress = inProgress
        exportStatusLabel.text = if (inProgress) {
            SpecCodingBundle.message("spec.delta.export.progress", format.name)
        } else if (exportStatusLabel.text.isNullOrBlank()) {
            SpecCodingBundle.message("spec.delta.export.status.idle")
        } else {
            exportStatusLabel.text
        }
        updateActionState()
    }

    private class DeltaTableModel(
        private val items: List<SpecArtifactDelta>,
    ) : AbstractTableModel() {
        override fun getRowCount(): Int = items.size

        override fun getColumnCount(): Int = 4

        override fun getColumnName(column: Int): String {
            return when (column) {
                0 -> SpecCodingBundle.message("spec.delta.column.phase")
                1 -> SpecCodingBundle.message("spec.delta.column.status")
                2 -> SpecCodingBundle.message("spec.delta.column.baseline")
                else -> SpecCodingBundle.message("spec.delta.column.target")
            }
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val item = items[rowIndex]
            return when (columnIndex) {
                0 -> item.artifact.displayName
                1 -> when (item.status) {
                    SpecDeltaStatus.ADDED -> SpecCodingBundle.message("spec.delta.status.added")
                    SpecDeltaStatus.MODIFIED -> SpecCodingBundle.message("spec.delta.status.modified")
                    SpecDeltaStatus.REMOVED -> SpecCodingBundle.message("spec.delta.status.removed")
                    SpecDeltaStatus.UNCHANGED -> SpecCodingBundle.message("spec.delta.status.unchanged")
                }

                2 -> item.baselineDocument?.metadata?.title
                    ?: item.artifact.fileName.takeIf { item.baselineContent != null }
                    ?: SpecCodingBundle.message("spec.delta.column.placeholder")

                else -> item.targetDocument?.metadata?.title
                    ?: item.artifact.fileName.takeIf { item.targetContent != null }
                    ?: SpecCodingBundle.message("spec.delta.column.placeholder")
            }
        }

        fun deltaAt(row: Int): SpecArtifactDelta = items[row]
    }

    companion object {
        private const val KEY_STATUS_FILTER = "spec.delta.filter.status"
        private const val KEY_CHANGED_ONLY = "spec.delta.filter.changedOnly"

        internal fun parseStatusFilter(raw: String?): StatusFilterOption {
            if (raw.isNullOrBlank()) {
                return StatusFilterOption.ALL
            }
            return runCatching { StatusFilterOption.valueOf(raw) }.getOrDefault(StatusFilterOption.ALL)
        }

        internal fun shouldShowInChangedOnly(status: SpecDeltaStatus): Boolean {
            return status != SpecDeltaStatus.UNCHANGED
        }

        internal fun shouldShow(
            status: SpecDeltaStatus,
            filterOption: StatusFilterOption,
            changedOnly: Boolean,
        ): Boolean {
            if (!filterOption.match(status)) {
                return false
            }
            return !changedOnly || shouldShowInChangedOnly(status)
        }

        internal fun canPreviewDiff(delta: SpecArtifactDelta): Boolean {
            return !delta.baselineContent.isNullOrBlank() || !delta.targetContent.isNullOrBlank()
        }

        internal fun contentForDiff(content: String?): String {
            return content
                ?.replace("\r\n", "\n")
                ?.replace('\r', '\n')
                .orEmpty()
        }

        private fun statusColor(status: SpecDeltaStatus): Color {
            return when (status) {
                SpecDeltaStatus.ADDED -> JBColor(Color(76, 175, 80), Color(129, 199, 132))
                SpecDeltaStatus.MODIFIED -> JBColor(Color(33, 150, 243), Color(100, 181, 246))
                SpecDeltaStatus.REMOVED -> JBColor(Color(244, 67, 54), Color(239, 154, 154))
                SpecDeltaStatus.UNCHANGED -> JBColor.GRAY
            }
        }
    }

}
