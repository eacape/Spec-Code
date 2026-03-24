package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.rollback.Changeset
import com.eacape.speccodingplugin.rollback.ChangesetChangedEvent
import com.eacape.speccodingplugin.rollback.ChangesetChangedListener
import com.eacape.speccodingplugin.rollback.ChangesetStore
import com.eacape.speccodingplugin.rollback.FileChange
import com.eacape.speccodingplugin.rollback.RollbackManager
import com.eacape.speccodingplugin.rollback.RollbackOptions
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import kotlin.math.max

/**
 * 变更时间线面板
 * 展示 AI 操作的变更历史，支持回滚操作
 */
class ChangesetTimelinePanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {

    private val store = ChangesetStore.getInstance(project)
    private val rollbackManager = RollbackManager.getInstance(project)
    private val timelineContainer = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): java.awt.Dimension = preferredSize

        override fun getScrollableUnitIncrement(
            visibleRect: java.awt.Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = JBUI.scale(16)

        override fun getScrollableBlockIncrement(
            visibleRect: java.awt.Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = if (orientation == javax.swing.SwingConstants.VERTICAL) {
            visibleRect.height - JBUI.scale(16)
        } else {
            visibleRect.width - JBUI.scale(16)
        }

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
    private val retentionHintLabel = JBLabel()
    private val countLabel = JBLabel()
    private val formatter = DateTimeFormatter
        .ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    init {
        border = JBUI.Borders.empty(6)
        setupUI()
        subscribeToChangesetEvents()
        refresh()
    }

    private fun setupUI() {
        retentionHintLabel.foreground = SECONDARY_TEXT_COLOR
        retentionHintLabel.font = JBUI.Fonts.smallFont()
        retentionHintLabel.text = SpecCodingBundle.message(
            "changeset.timeline.retentionHint",
            ChangesetStore.MAX_RETAINED_CHANGESETS,
        )

        countLabel.foreground = SECONDARY_TEXT_COLOR
        countLabel.font = JBUI.Fonts.smallFont()

        val topMetaPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 2, 6, 2)
            add(retentionHintLabel, BorderLayout.WEST)
            add(countLabel, BorderLayout.EAST)
        }

        // 时间线容器
        timelineContainer.layout = BoxLayout(
            timelineContainer, BoxLayout.Y_AXIS
        )
        timelineContainer.border = JBUI.Borders.empty(0, 2, 2, 2)
        timelineContainer.background = PANEL_BACKGROUND
        timelineContainer.isOpaque = true

        val scrollPane = JBScrollPane(timelineContainer)
        scrollPane.border = JBUI.Borders.empty()
        scrollPane.verticalScrollBar.unitIncrement = 16
        scrollPane.horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

        add(topMetaPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    /**
     * 刷新时间线
     */
    fun refresh() {
        val changesets = store.getRecent(ChangesetStore.MAX_RETAINED_CHANGESETS)
        timelineContainer.removeAll()
        countLabel.text = SpecCodingBundle.message("changeset.timeline.count", changesets.size)

        if (changesets.isEmpty()) {
            val emptyLabel = JBLabel(SpecCodingBundle.message("changeset.timeline.empty"))
            emptyLabel.foreground = SECONDARY_TEXT_COLOR
            emptyLabel.border = JBUI.Borders.empty(12, 10)
            timelineContainer.add(emptyLabel)
        } else {
            val total = changesets.size
            changesets.forEachIndexed { index, changeset ->
                if (index > 0) {
                    timelineContainer.add(Box.createVerticalStrut(JBUI.scale(6)))
                }
                timelineContainer.add(createChangesetRow(changeset, total - index))
            }
            timelineContainer.add(Box.createVerticalGlue())
        }

        timelineContainer.revalidate()
        timelineContainer.repaint()
    }

    private fun createChangesetRow(
        changeset: Changeset,
        displayIndex: Int,
    ): JPanel {
        val row = JPanel(BorderLayout())
        row.isOpaque = true
        row.background = ROW_BACKGROUND
        row.border = SpecUiStyle.roundedCardBorder(
            lineColor = ROW_DIVIDER,
            arc = JBUI.scale(10),
            top = 6,
            left = 8,
            bottom = 6,
            right = 8,
        )

        val header = JPanel(BorderLayout())
        header.isOpaque = false
        val leftHeader = JPanel(BorderLayout(JBUI.scale(8), 0))
        leftHeader.isOpaque = false

        val indexLabel = JBLabel("[$displayIndex]").apply {
            foreground = INDEX_COLOR
            font = font.deriveFont(Font.BOLD, 11.5f)
        }
        leftHeader.add(indexLabel, BorderLayout.WEST)

        val descLabel = JBLabel(resolveDescription(changeset))
        descLabel.font = descLabel.font.deriveFont(
            java.awt.Font.BOLD, 12f
        )
        descLabel.foreground = PRIMARY_TEXT_COLOR
        leftHeader.add(descLabel, BorderLayout.CENTER)
        header.add(leftHeader, BorderLayout.CENTER)

        val rightHeader = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        rightHeader.isOpaque = false
        val timeStr = formatter.format(changeset.timestamp)
        val timeLabel = JBLabel(timeStr)
        timeLabel.foreground = SECONDARY_TEXT_COLOR
        timeLabel.font = timeLabel.font.deriveFont(10.5f)
        rightHeader.add(timeLabel)

        if (changeset.changes.isNotEmpty()) {
            val rollbackBtn = JButton()
            styleIconActionButton(
                rollbackBtn,
                AllIcons.Actions.Undo,
                SpecCodingBundle.message("changeset.timeline.rollback"),
            )
            rollbackBtn.addActionListener { performRollback(changeset.id) }
            rightHeader.add(rollbackBtn)
        }

        val deleteBtn = JButton()
        styleIconActionButton(
            deleteBtn,
            AllIcons.Actions.GC,
            SpecCodingBundle.message("changeset.timeline.remove"),
        )
        deleteBtn.addActionListener { store.delete(changeset.id) }
        rightHeader.add(deleteBtn)
        header.add(rightHeader, BorderLayout.EAST)

        val secondaryLabel = JBLabel(buildSecondaryLineText(changeset)).apply {
            foreground = SECONDARY_TEXT_COLOR
            font = font.deriveFont(Font.PLAIN, 10.5f)
            border = JBUI.Borders.emptyTop(2)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val content = JPanel(GridBagLayout())
        content.isOpaque = false
        addRowLine(content, 0, header)
        addRowLine(content, 1, secondaryLabel)

        row.add(content, BorderLayout.CENTER)
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.maximumSize = java.awt.Dimension(Int.MAX_VALUE, row.preferredSize.height + JBUI.scale(4))
        return row
    }

    private fun addRowLine(parent: JPanel, rowIndex: Int, component: JComponent) {
        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = rowIndex
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = if (rowIndex == 0) JBUI.insets(0) else JBUI.insetsTop(2)
        }
        parent.add(component, constraints)
    }

    private fun styleIconActionButton(button: JButton, icon: Icon, tooltip: String) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.isBorderPainted = false
        button.isContentAreaFilled = false
        button.isOpaque = false
        button.margin = JBUI.insets(0)
        button.icon = icon
        button.text = ""
        button.toolTipText = tooltip
        button.accessibleContext.accessibleName = tooltip
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.preferredSize = JBUI.size(18, 18)
        button.minimumSize = JBUI.size(18, 18)
    }

    private fun buildSecondaryLineText(changeset: Changeset): String {
        val parts = mutableListOf<String>()
        parts += SpecCodingBundle.message("changeset.timeline.affectedFiles", changeset.changes.size)
        changeset.metadata["source"]
            ?.takeIf { it.isNotBlank() }
            ?.let { parts += SpecCodingBundle.message("changeset.timeline.meta.source", resolveSourceText(it)) }
        changeset.metadata["status"]
            ?.takeIf { it.isNotBlank() }
            ?.let { parts += SpecCodingBundle.message("changeset.timeline.meta.status", resolveStatusText(it)) }
        changeset.metadata["exitCode"]
            ?.takeIf { it.isNotBlank() }
            ?.let { parts += SpecCodingBundle.message("changeset.timeline.meta.exitCode", it) }
        changeset.changes.firstOrNull()?.let { first ->
            val filePart = SpecCodingBundle.message(
                "changeset.timeline.file.entry",
                resolveFileTypeText(first.changeType),
                toDisplayPath(first.filePath),
            )
            parts += filePart
            val more = changeset.changes.size - 1
            if (more > 0) {
                parts += SpecCodingBundle.message("changeset.timeline.more", more)
            }
        }
        val normalized = parts
            .map { compactText(it, SECONDARY_PART_MAX_LENGTH) }
            .joinToString("  ·  ")
        return compactText(normalized, SECONDARY_LINE_MAX_LENGTH)
    }

    private fun resolveFileTypeText(changeType: FileChange.ChangeType): String {
        return when (changeType) {
            FileChange.ChangeType.CREATED -> SpecCodingBundle.message("changeset.timeline.file.type.created")
            FileChange.ChangeType.MODIFIED -> SpecCodingBundle.message("changeset.timeline.file.type.modified")
            FileChange.ChangeType.DELETED -> SpecCodingBundle.message("changeset.timeline.file.type.deleted")
        }
    }

    private fun resolveSourceText(source: String): String {
        return when (source.lowercase()) {
            "workflow-command" -> SpecCodingBundle.message("changeset.timeline.source.workflowCommand")
            "assistant-response" -> SpecCodingBundle.message("changeset.timeline.source.assistantResponse")
            else -> source
        }
    }

    private fun resolveStatusText(status: String): String {
        return when (status.lowercase()) {
            "success" -> SpecCodingBundle.message("changeset.timeline.status.success")
            "failed" -> SpecCodingBundle.message("changeset.timeline.status.failed")
            "timeout" -> SpecCodingBundle.message("changeset.timeline.status.timeout")
            "stopped" -> SpecCodingBundle.message("changeset.timeline.status.stopped")
            "error" -> SpecCodingBundle.message("changeset.timeline.status.error")
            "no-file-change" -> SpecCodingBundle.message("changeset.timeline.status.noFileChange")
            else -> status
        }
    }

    private fun resolveDescription(changeset: Changeset): String {
        val command = changeset.metadata["command"]?.trim().orEmpty()
        if (command.isNotEmpty()) {
            return SpecCodingBundle.message("changeset.timeline.command.title", compactText(command, COMMAND_MAX_LENGTH))
        }
        return compactText(changeset.description, COMMAND_MAX_LENGTH + 9)
    }

    private fun toDisplayPath(path: String): String {
        val normalized = path.replace('\\', '/')
        val projectBase = project.basePath?.replace('\\', '/')
            ?.trimEnd('/')
            .orEmpty()
        if (projectBase.isBlank()) {
            return compactText(normalized, DISPLAY_PATH_MAX_LENGTH)
        }
        val prefix = "$projectBase/"
        val relative = if (normalized.startsWith(prefix, ignoreCase = true)) {
            normalized.substring(prefix.length)
        } else {
            normalized
        }
        return compactText(relative, DISPLAY_PATH_MAX_LENGTH)
    }

    private fun compactText(value: String, maxLength: Int): String {
        val normalized = value.trim()
        if (normalized.length <= maxLength) {
            return normalized
        }
        val head = max(8, maxLength / 2 - 2)
        val tail = max(8, maxLength - head - 3)
        return normalized.take(head) + "..." + normalized.takeLast(tail)
    }

    private fun performRollback(changesetId: String) {
        val result = rollbackManager.rollback(
            changesetId,
            RollbackOptions(createBackup = true)
        )

        SwingUtilities.invokeLater {
            showRollbackNotification(result)
            refresh()
        }
    }

    private fun showRollbackNotification(result: com.eacape.speccodingplugin.rollback.RollbackResult) {
        val type = when (result) {
            is com.eacape.speccodingplugin.rollback.RollbackResult.Success -> NotificationType.INFORMATION
            is com.eacape.speccodingplugin.rollback.RollbackResult.PartialSuccess -> NotificationType.WARNING
            is com.eacape.speccodingplugin.rollback.RollbackResult.Failure -> NotificationType.ERROR
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SpecCoding.Notifications")
            .createNotification(result.getSummary(), type)
            .notify(project)
    }

    private fun subscribeToChangesetEvents() {
        project.messageBus.connect(this).subscribe(
            ChangesetChangedListener.TOPIC,
            object : ChangesetChangedListener {
                override fun onChanged(event: ChangesetChangedEvent) {
                    SwingUtilities.invokeLater {
                        if (!project.isDisposed) {
                            refresh()
                        }
                    }
                }
            },
        )
    }

    override fun dispose() {
    }

    companion object {
        private val PANEL_BACKGROUND = JBColor(
            java.awt.Color(246, 249, 255),
            java.awt.Color(45, 49, 56),
        )
        private val ROW_BACKGROUND = JBColor(
            java.awt.Color(250, 252, 255),
            java.awt.Color(52, 57, 65),
        )
        private val ROW_DIVIDER = JBColor(
            java.awt.Color(206, 216, 232),
            java.awt.Color(83, 92, 108),
        )
        private val PRIMARY_TEXT_COLOR = JBColor(
            java.awt.Color(28, 38, 55),
            java.awt.Color(220, 227, 238),
        )
        private val SECONDARY_TEXT_COLOR = JBColor(
            java.awt.Color(102, 111, 126),
            java.awt.Color(154, 162, 178),
        )
        private val INDEX_COLOR = JBColor(
            java.awt.Color(18, 103, 192),
            java.awt.Color(111, 170, 255),
        )
        private const val DISPLAY_PATH_MAX_LENGTH = 96
        private const val COMMAND_MAX_LENGTH = 56
        private const val SECONDARY_PART_MAX_LENGTH = 72
        private const val SECONDARY_LINE_MAX_LENGTH = 180

        fun getInstance(project: Project): RollbackManager {
            return RollbackManager.getInstance(project)
        }
    }
}
