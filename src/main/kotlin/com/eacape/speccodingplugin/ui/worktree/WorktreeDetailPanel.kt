package com.eacape.speccodingplugin.ui.worktree

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.eacape.speccodingplugin.worktree.WorktreeStatus
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants

class WorktreeDetailPanel : JPanel(BorderLayout()) {

    private val emptyLabel = JBLabel(SpecCodingBundle.message("worktree.empty.select"), SwingConstants.CENTER)

    private val summaryTaskLabel = JBLabel()
    private val summaryTitleLabel = JBLabel()
    private val specTaskIdLabel = JBLabel()
    private val specTitleLabel = JBLabel()
    private val statusLabel = JBLabel()
    private val branchLabel = JBLabel()
    private val baseBranchLabel = JBLabel()
    private val pathLabel = JBLabel()
    private val updatedAtLabel = JBLabel()
    private val errorArea = JBTextArea()
    private var currentItem: WorktreeListItem? = null

    init {
        border = JBUI.Borders.empty(8)
        isOpaque = true
        background = PANEL_BG
        emptyLabel.foreground = EMPTY_FG
        emptyLabel.font = JBUI.Fonts.label().deriveFont(Font.PLAIN, 12.5f)
        showEmpty()
    }

    fun updateWorktree(item: WorktreeListItem) {
        currentItem = item
        removeAll()

        summaryTaskLabel.text = item.specTaskId
        summaryTaskLabel.font = summaryTaskLabel.font.deriveFont(Font.BOLD, 13.5f)
        summaryTitleLabel.text = item.specTitle
        summaryTitleLabel.font = summaryTitleLabel.font.deriveFont(Font.PLAIN, 11.5f)
        summaryTitleLabel.foreground = SUBTITLE_FG

        specTaskIdLabel.text = item.specTaskId
        specTitleLabel.text = item.specTitle
        val statusText = when (item.status) {
            WorktreeStatus.ACTIVE -> SpecCodingBundle.message("worktree.status.active")
            WorktreeStatus.MERGED -> SpecCodingBundle.message("worktree.status.merged")
            WorktreeStatus.REMOVED -> SpecCodingBundle.message("worktree.status.removed")
            WorktreeStatus.ERROR -> SpecCodingBundle.message("worktree.status.error")
        }
        statusLabel.text = if (item.isActive) {
            "$statusText (${SpecCodingBundle.message("worktree.detail.active")})"
        } else {
            statusText
        }
        branchLabel.text = item.branchName
        baseBranchLabel.text = item.baseBranch
        pathLabel.text = item.worktreePath
        pathLabel.toolTipText = item.worktreePath
        pathLabel.foreground = PATH_FG
        updatedAtLabel.text = formatTimestamp(item.updatedAt)

        val summaryPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(summaryTaskLabel)
            add(Box.createVerticalStrut(2))
            add(summaryTitleLabel)
        }

        val fieldsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(createFieldRow("worktree.detail.specTaskId", specTaskIdLabel))
            add(createFieldRow("worktree.detail.specTitle", specTitleLabel))
            add(createFieldRow("worktree.detail.status", statusLabel))
            add(createFieldRow("worktree.detail.branch", branchLabel))
            add(createFieldRow("worktree.detail.baseBranch", baseBranchLabel))
            add(createFieldRow("worktree.detail.path", pathLabel))
            add(createFieldRow("worktree.detail.updatedAt", updatedAtLabel))
        }

        val summaryCard = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = CARD_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = CARD_BORDER,
                arc = JBUI.scale(12),
                top = 10,
                left = 12,
                bottom = 10,
                right = 12,
            )
            add(summaryPanel, BorderLayout.NORTH)
            add(fieldsPanel, BorderLayout.CENTER)
        }
        add(summaryCard, BorderLayout.NORTH)

        errorArea.isEditable = false
        errorArea.lineWrap = true
        errorArea.wrapStyleWord = true
        errorArea.background = ERROR_BG
        errorArea.foreground = ERROR_FG
        errorArea.border = JBUI.Borders.empty(6, 8)
        errorArea.text = item.lastError ?: SpecCodingBundle.message("worktree.detail.noError")

        val errorPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(
                JBLabel(SpecCodingBundle.message("worktree.detail.lastError")).apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = FIELD_NAME_FG
                    border = JBUI.Borders.emptyBottom(4)
                },
                BorderLayout.NORTH,
            )
            add(
                JScrollPane(errorArea).apply {
                    border = SpecUiStyle.roundedLineBorder(CARD_BORDER, JBUI.scale(10))
                    viewportBorder = null
                },
                BorderLayout.CENTER,
            )
        }
        add(errorPanel, BorderLayout.CENTER)

        revalidate()
        repaint()
    }

    private fun createFieldRow(nameKey: String, valueLabel: JBLabel): JPanel {
        val nameLabel = JBLabel(SpecCodingBundle.message(nameKey)).apply {
            font = JBUI.Fonts.smallFont()
            foreground = FIELD_NAME_FG
        }
        valueLabel.font = valueLabel.font.deriveFont(Font.PLAIN, 12f)
        return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
            add(nameLabel, BorderLayout.WEST)
            add(valueLabel, BorderLayout.CENTER)
        }
    }

    fun showEmpty() {
        currentItem = null
        removeAll()
        add(emptyLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun formatTimestamp(value: Long): String {
        return runCatching {
            DETAIL_TIME_FORMATTER.format(Instant.ofEpochMilli(value))
        }.getOrDefault(value.toString())
    }

    fun refreshLocalizedTexts() {
        emptyLabel.text = SpecCodingBundle.message("worktree.empty.select")
        currentItem?.let(::updateWorktree)
    }

    internal fun displayedSpecTaskIdForTest(): String = specTaskIdLabel.text

    internal fun displayedStatusForTest(): String = statusLabel.text

    internal fun displayedLastErrorForTest(): String = errorArea.text

    internal fun isShowingEmptyForTest(): Boolean = components.contains(emptyLabel)

    companion object {
        private val DETAIL_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        private val PANEL_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val CARD_BG = JBColor(Color(251, 252, 254), Color(50, 54, 61))
        private val CARD_BORDER = JBColor(Color(204, 215, 233), Color(84, 92, 105))
        private val SUBTITLE_FG = JBColor(Color(88, 98, 113), Color(168, 176, 189))
        private val FIELD_NAME_FG = JBColor(Color(82, 92, 108), Color(171, 180, 194))
        private val PATH_FG = JBColor(Color(55, 77, 110), Color(204, 216, 236))
        private val ERROR_BG = JBColor(Color(248, 250, 253), Color(58, 63, 71))
        private val ERROR_FG = JBColor(Color(73, 90, 117), Color(189, 201, 219))
        private val EMPTY_FG = JBColor(Color(106, 121, 141), Color(173, 187, 208))
    }
}
