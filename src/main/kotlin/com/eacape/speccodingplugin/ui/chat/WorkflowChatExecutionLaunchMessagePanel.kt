package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchFallbackReason
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchPresentation
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchRestorePayload
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLegacyCompactNotice
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionPresentationSection
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionPresentationSectionKind
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea

internal class WorkflowChatExecutionLaunchMessagePanel(
    private val payload: WorkflowChatExecutionLaunchRestorePayload,
    visibleContent: String,
    private val rawPromptContent: String? = null,
    compact: Boolean = false,
    private val inspectRawPrompt: ((String) -> Unit)? = null,
    private val onDeleteMessage: ((ChatMessagePanel) -> Unit)? = null,
) : ChatMessagePanel(
    role = MessageRole.USER,
    initialContent = visibleContent,
    onDelete = onDeleteMessage,
) {

    private val wrapper = JPanel(BorderLayout())
    private val bodyPanel = JPanel()
    private val heroPanel = JPanel(BorderLayout())
    private val heroTextPanel = JPanel()
    private val heroIconPanel = JPanel(BorderLayout())
    private val heroIconLabel = JBLabel()
    private val heroTitleLabel = JBLabel()
    private val heroMetaLabel = JBLabel()
    private val metaChipPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4)))
    private val userNotePanel = JPanel()
    private val systemContextPanel = JPanel()
    private val systemContextSummaryPanel = JPanel()
    private val systemContextDetailsPanel = JPanel()
    private val systemContextToggleButton = JButton()
    private val debugPanel = JPanel()
    private val rawPromptToggleButton = JButton()
    private val notesPanel = JPanel()
    private val titleLabel = JBLabel(SpecCodingBundle.message("chat.execution.launch.title"))
    private val summaryLabel = JBLabel(SpecCodingBundle.message("chat.execution.launch.summary"))
    private var compactMode = compact
    private var systemContextExpanded = false
    private var rawPromptVisible = false
    private var rawPromptInspectInvocations = 0

    init {
        removeAll()
        layout = BorderLayout()
        isOpaque = false
        border = JBUI.Borders.emptyBottom(10)

        wrapper.isOpaque = true
        wrapper.background = CARD_BG
        wrapper.border = SpecUiStyle.roundedCardBorder(
            lineColor = CARD_BORDER,
            arc = JBUI.scale(18),
            top = 9,
            left = 11,
            bottom = 9,
            right = 11,
        )

        val header = createHeader()

        bodyPanel.layout = BoxLayout(bodyPanel, BoxLayout.Y_AXIS)
        bodyPanel.isOpaque = false
        bodyPanel.border = JBUI.Borders.emptyTop(8)

        heroPanel.isOpaque = false
        heroPanel.border = JBUI.Borders.empty(4, 0, 2, 0)
        heroPanel.alignmentX = LEFT_ALIGNMENT

        heroTextPanel.layout = BoxLayout(heroTextPanel, BoxLayout.Y_AXIS)
        heroTextPanel.isOpaque = false
        heroTextPanel.alignmentX = LEFT_ALIGNMENT

        heroIconPanel.isOpaque = false
        heroIconPanel.border = JBUI.Borders.emptyRight(10)
        heroIconPanel.add(heroIconLabel, BorderLayout.CENTER)

        heroTitleLabel.font = heroTitleLabel.font.deriveFont(Font.BOLD, 13f)
        heroTitleLabel.foreground = HERO_TITLE_FG
        heroTitleLabel.alignmentX = LEFT_ALIGNMENT

        heroMetaLabel.font = heroMetaLabel.font.deriveFont(11f)
        heroMetaLabel.foreground = SUMMARY_FG
        heroMetaLabel.border = JBUI.Borders.emptyTop(2)
        heroMetaLabel.alignmentX = LEFT_ALIGNMENT

        metaChipPanel.isOpaque = false
        metaChipPanel.alignmentX = LEFT_ALIGNMENT
        metaChipPanel.border = JBUI.Borders.emptyTop(8)

        heroTextPanel.add(heroTitleLabel)
        heroTextPanel.add(heroMetaLabel)
        heroTextPanel.add(metaChipPanel)

        heroPanel.add(heroIconPanel, BorderLayout.WEST)
        heroPanel.add(heroTextPanel, BorderLayout.CENTER)

        userNotePanel.layout = BoxLayout(userNotePanel, BoxLayout.Y_AXIS)
        userNotePanel.isOpaque = false
        userNotePanel.alignmentX = LEFT_ALIGNMENT

        systemContextPanel.layout = BoxLayout(systemContextPanel, BoxLayout.Y_AXIS)
        systemContextPanel.isOpaque = false
        systemContextPanel.alignmentX = LEFT_ALIGNMENT

        systemContextSummaryPanel.layout = BoxLayout(systemContextSummaryPanel, BoxLayout.Y_AXIS)
        systemContextSummaryPanel.isOpaque = false
        systemContextSummaryPanel.alignmentX = LEFT_ALIGNMENT

        systemContextDetailsPanel.layout = BoxLayout(systemContextDetailsPanel, BoxLayout.Y_AXIS)
        systemContextDetailsPanel.isOpaque = false
        systemContextDetailsPanel.alignmentX = LEFT_ALIGNMENT

        debugPanel.layout = BoxLayout(debugPanel, BoxLayout.Y_AXIS)
        debugPanel.isOpaque = false
        debugPanel.alignmentX = LEFT_ALIGNMENT

        notesPanel.layout = BoxLayout(notesPanel, BoxLayout.Y_AXIS)
        notesPanel.isOpaque = false
        notesPanel.alignmentX = LEFT_ALIGNMENT

        bodyPanel.add(heroPanel)
        bodyPanel.add(userNotePanel)
        bodyPanel.add(systemContextPanel)
        bodyPanel.add(debugPanel)
        bodyPanel.add(notesPanel)

        wrapper.add(header, BorderLayout.NORTH)
        wrapper.add(bodyPanel, BorderLayout.CENTER)
        add(wrapper, BorderLayout.CENTER)

        renderCard()
    }

    internal fun snapshotForTest(): Map<String, String> {
        return when (val current = payload) {
            is WorkflowChatExecutionLaunchRestorePayload.Presentation -> mapOf(
                "visible" to "true",
                "kind" to "presentation",
                "workflowId" to current.launch.workflowId,
                "taskId" to current.launch.taskId,
                "runId" to current.launch.runId,
                "taskTitle" to current.launch.taskTitle,
                "focusedStage" to current.launch.focusedStage.name,
                "trigger" to current.launch.trigger.name,
                "sectionKinds" to current.launch.sections.joinToString(",") { it.kind.name },
                "rawPromptDebugAvailable" to current.launch.rawPromptDebugAvailable.toString(),
                "compactMode" to compactMode.toString(),
                "userNoteVisible" to (userNotePanel.componentCount > 0).toString(),
                "titleHasIcon" to (titleLabel.icon != null).toString(),
                "systemContextExpanded" to systemContextExpanded.toString(),
                "debugEntryVisible" to rawPromptToggleButton.isVisible.toString(),
                "rawPromptVisible" to rawPromptVisible.toString(),
                "rawPromptInspectInvocations" to rawPromptInspectInvocations.toString(),
                "content" to getContent(),
            )

            is WorkflowChatExecutionLaunchRestorePayload.LegacyCompact -> mapOf(
                "visible" to "true",
                "kind" to "legacy",
                "workflowId" to current.notice.workflowId.orEmpty(),
                "taskId" to current.notice.taskId.orEmpty(),
                "runId" to current.notice.runId.orEmpty(),
                "taskTitle" to current.notice.taskTitle.orEmpty(),
                "focusedStage" to current.notice.focusedStage?.name.orEmpty(),
                "trigger" to current.notice.trigger?.name.orEmpty(),
                "sectionKinds" to current.notice.sectionKinds.joinToString(",") { it.name },
                "fallbackReason" to current.notice.fallbackReason.name,
                "rawPromptDebugAvailable" to current.notice.rawPromptDebugAvailable.toString(),
                "compactMode" to compactMode.toString(),
                "userNoteVisible" to (userNotePanel.componentCount > 0).toString(),
                "titleHasIcon" to (titleLabel.icon != null).toString(),
                "systemContextExpanded" to systemContextExpanded.toString(),
                "debugEntryVisible" to rawPromptToggleButton.isVisible.toString(),
                "rawPromptVisible" to rawPromptVisible.toString(),
                "rawPromptInspectInvocations" to rawPromptInspectInvocations.toString(),
                "content" to getContent(),
            )
        }
    }

    internal fun toggleSystemContextForTest() {
        systemContextToggleButton.doClick()
    }

    internal fun toggleRawPromptForTest() {
        rawPromptToggleButton.doClick()
    }

    internal fun setCompactMode(compact: Boolean) {
        if (compactMode == compact) {
            return
        }
        compactMode = compact
        renderCard()
    }

    private fun createHeader(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false

        val left = JPanel()
        left.layout = BoxLayout(left, BoxLayout.Y_AXIS)
        left.isOpaque = false

        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 12f)
        titleLabel.foreground = TITLE_FG
        titleLabel.iconTextGap = JBUI.scale(6)
        titleLabel.icon = null
        left.add(titleLabel)

        summaryLabel.font = summaryLabel.font.deriveFont(11f)
        summaryLabel.foreground = SUMMARY_FG
        left.add(summaryLabel)

        panel.add(left, BorderLayout.CENTER)

        if (onDeleteMessage != null) {
            val deleteButton = JButton(AllIcons.General.Remove).apply {
                isOpaque = false
                isContentAreaFilled = false
                isBorderPainted = false
                toolTipText = SpecCodingBundle.message("chat.message.delete")
                addActionListener { onDeleteMessage.invoke(this@WorkflowChatExecutionLaunchMessagePanel) }
            }
            panel.add(deleteButton, BorderLayout.EAST)
        }
        return panel
    }

    private fun renderCard() {
        metaChipPanel.removeAll()
        userNotePanel.removeAll()
        systemContextPanel.removeAll()
        systemContextSummaryPanel.removeAll()
        systemContextDetailsPanel.removeAll()
        debugPanel.removeAll()
        notesPanel.removeAll()
        heroTitleLabel.text = SpecCodingBundle.message("chat.execution.launch.title")
        heroMetaLabel.text = ""
        heroMetaLabel.isVisible = false
        heroIconLabel.icon = AllIcons.Actions.Execute
        rawPromptToggleButton.isVisible = false
        summaryLabel.text = if (compactMode) {
            SpecCodingBundle.message("chat.execution.launch.summary.history")
        } else {
            SpecCodingBundle.message("chat.execution.launch.summary")
        }

        when (val current = payload) {
            is WorkflowChatExecutionLaunchRestorePayload.Presentation -> renderPresentation(current.launch)
            is WorkflowChatExecutionLaunchRestorePayload.LegacyCompact -> renderLegacy(current.notice)
        }

        revalidate()
        repaint()
    }

    private fun renderPresentation(launch: WorkflowChatExecutionLaunchPresentation) {
        renderHero(
            workflowId = launch.workflowId,
            taskId = launch.taskId,
            taskTitle = launch.taskTitle,
            runId = launch.runId,
            focusedStage = launch.focusedStage,
            trigger = launch.trigger,
            taskStatus = launch.taskStatusBeforeExecution,
            taskPriority = launch.taskPriority,
            legacy = false,
        )

        renderUserNote(launch.supplementalInstruction)

        val visibleSections = launch.sections.filter { section ->
            section.itemCount > 0 || !section.emptyStateReason.isNullOrBlank()
        }
        renderSystemContext(
            summaryLines = buildDisplaySummaryLines(buildContextSummaryLines(visibleSections)),
            detailPanels = visibleSections.map { section ->
                createSectionPanel(
                    title = sectionLabel(section.kind),
                    detail = sectionPreview(section),
                    badgeText = sectionBadgeText(section),
                    palette = DETAIL_SECTION_PALETTE,
                    topInset = 0,
                )
            },
            badgeText = visibleSections.size.takeIf { it > 0 }?.toString(),
            allowExpand = visibleSections.isNotEmpty(),
        )

        launch.degradationReasons.forEach { reason ->
            notesPanel.add(createNoticePanel(reason))
        }
        renderDebugEntry(launch.rawPromptDebugAvailable)
    }

    private fun renderLegacy(notice: WorkflowChatExecutionLegacyCompactNotice) {
        renderHero(
            workflowId = notice.workflowId,
            taskId = notice.taskId,
            taskTitle = notice.taskTitle,
            runId = notice.runId,
            focusedStage = notice.focusedStage,
            trigger = notice.trigger,
            legacy = true,
        )

        if (notice.supplementalInstructionPresent) {
            renderUserNote(SpecCodingBundle.message("chat.execution.launch.note.legacy.userNote"))
        }

        renderSystemContext(
            summaryLines = buildDisplaySummaryLines(
                if (notice.sectionKinds.isEmpty()) {
                    listOf(SpecCodingBundle.message("chat.execution.launch.section.empty"))
                } else {
                    notice.sectionKinds.map { kind -> sectionLabel(kind) }
                },
            ),
            detailPanels = emptyList(),
            badgeText = notice.sectionKinds.size.takeIf { it > 0 }?.toString(),
            allowExpand = false,
        )

        notesPanel.add(createNoticePanel(SpecCodingBundle.message("chat.execution.launch.note.legacy")))
        notesPanel.add(createNoticePanel(legacyReasonLabel(notice.fallbackReason)))
        renderDebugEntry(notice.rawPromptDebugAvailable)
    }

    private fun renderUserNote(userNote: String?) {
        val normalized = userNote?.trim()?.takeIf(String::isNotBlank) ?: return
        userNotePanel.add(
            createSectionPanel(
                title = SpecCodingBundle.message("chat.execution.launch.section.userNote"),
                detail = normalized,
                badgeText = null,
                palette = USER_NOTE_SECTION_PALETTE,
            ),
        )
    }

    private fun renderSystemContext(
        summaryLines: List<String>,
        detailPanels: List<JPanel>,
        badgeText: String?,
        allowExpand: Boolean,
    ) {
        val container = createSurfacePanel(
            palette = CONTEXT_SECTION_PALETTE,
            topInset = 8,
        )

        val header = JPanel(BorderLayout())
        header.isOpaque = false

        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(
                JBLabel(SpecCodingBundle.message("chat.execution.launch.section.systemContext")).apply {
                    foreground = TITLE_FG
                    font = font.deriveFont(Font.BOLD, 11f)
                },
            )
            badgeText?.takeIf(String::isNotBlank)?.let { badge ->
                add(
                    JBLabel(badge).apply {
                        foreground = SUMMARY_FG
                        font = font.deriveFont(10f)
                    },
                )
            }
        }
        header.add(headerLeft, BorderLayout.WEST)

        systemContextToggleButton.apply {
            isVisible = allowExpand
            actionListeners.forEach(::removeActionListener)
            styleSecondaryActionButton(this)
            addActionListener {
                systemContextExpanded = !systemContextExpanded
                refreshSystemContextExpansion()
            }
        }
        if (allowExpand) {
            header.add(systemContextToggleButton, BorderLayout.EAST)
        }

        summaryLines.forEachIndexed { index, line ->
            systemContextSummaryPanel.add(createSummaryLinePanel(line))
            if (index < summaryLines.lastIndex) {
                systemContextSummaryPanel.add(Box.createVerticalStrut(JBUI.scale(6)))
            }
        }

        detailPanels.forEachIndexed { index, panel ->
            systemContextDetailsPanel.add(panel)
            if (index < detailPanels.lastIndex) {
                systemContextDetailsPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(systemContextSummaryPanel)
            if (detailPanels.isNotEmpty()) {
                add(Box.createVerticalStrut(JBUI.scale(10)))
                add(systemContextDetailsPanel)
            }
        }

        container.add(header, BorderLayout.NORTH)
        container.add(content, BorderLayout.CENTER)
        systemContextPanel.add(container)
        refreshSystemContextExpansion()
    }

    private fun refreshSystemContextExpansion() {
        systemContextDetailsPanel.isVisible = systemContextExpanded && systemContextDetailsPanel.componentCount > 0
        systemContextToggleButton.text = if (systemContextExpanded) {
            SpecCodingBundle.message("chat.execution.launch.action.hideContext")
        } else {
            SpecCodingBundle.message("chat.execution.launch.action.showContext")
        }
        systemContextPanel.revalidate()
        systemContextPanel.repaint()
    }

    private fun renderDebugEntry(rawPromptDebugAvailable: Boolean) {
        if (!rawPromptDebugAvailable) {
            return
        }
        val normalizedRawPrompt = rawPromptContent?.trim()?.takeIf(String::isNotBlank)
        val debugCard = createSurfacePanel(
            palette = DEBUG_SECTION_PALETTE,
            topInset = 8,
        )
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(
                JBLabel(SpecCodingBundle.message("chat.execution.launch.dialog.title")).apply {
                    foreground = TITLE_FG
                    font = font.deriveFont(Font.BOLD, 11f)
                },
                BorderLayout.WEST,
            )
        }
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        rawPromptToggleButton.apply {
            isVisible = normalizedRawPrompt != null
            styleSecondaryActionButton(this)
            icon = AllIcons.Actions.MenuOpen
            text = SpecCodingBundle.message("chat.execution.launch.action.inspectPrompt")
            actionListeners.forEach(::removeActionListener)
            addActionListener {
                normalizedRawPrompt?.let(::openRawPromptInspector)
            }
        }

        if (!compactMode) {
            content.add(
                createNoticePanel(
                    SpecCodingBundle.message("chat.execution.launch.note.rawPromptHidden"),
                    topInset = 0,
                ),
            )
            normalizedRawPrompt?.let { rawPrompt ->
                content.add(Box.createVerticalStrut(JBUI.scale(8)))
                content.add(
                    createWrappedLabel(
                        SpecCodingBundle.message(
                            "chat.execution.launch.note.rawPromptStats",
                            rawPrompt.lineSequence().count(),
                            rawPrompt.length,
                        ),
                        foreground = SUMMARY_FG,
                    ).apply {
                        border = JBUI.Borders.empty()
                    },
                )
            }
        }

        if (normalizedRawPrompt != null) {
            if (content.componentCount > 0) {
                content.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
            content.add(createActionRow(rawPromptToggleButton))
        }

        debugCard.add(header, BorderLayout.NORTH)
        debugCard.add(content, BorderLayout.CENTER)
        debugPanel.add(debugCard)
    }

    private fun openRawPromptInspector(rawPrompt: String) {
        rawPromptInspectInvocations += 1
        inspectRawPrompt?.invoke(rawPrompt) ?: WorkflowChatExecutionPromptDialog(rawPrompt).show()
    }

    private fun buildContextSummaryLines(
        sections: List<WorkflowChatExecutionPresentationSection>,
    ): List<String> {
        if (sections.isEmpty()) {
            return listOf(SpecCodingBundle.message("chat.execution.launch.section.empty"))
        }
        return sections.map { section ->
            val headline = sectionSummaryHeadline(section)
            val countLabel = sectionBadgeText(section)?.let { " [$it]" }.orEmpty()
            "${sectionLabel(section.kind)}$countLabel: $headline"
        }
    }

    private fun buildDisplaySummaryLines(lines: List<String>): List<String> {
        if (!compactMode || lines.size <= COMPACT_SUMMARY_LINE_LIMIT) {
            return lines
        }
        return lines.take(COMPACT_SUMMARY_LINE_LIMIT) + SpecCodingBundle.message("chat.execution.launch.note.moreItems")
    }

    private fun renderHero(
        workflowId: String?,
        taskId: String?,
        taskTitle: String?,
        runId: String?,
        focusedStage: StageId?,
        trigger: ExecutionTrigger?,
        taskStatus: TaskStatus? = null,
        taskPriority: TaskPriority? = null,
        legacy: Boolean,
    ) {
        heroTitleLabel.text = resolveHeroTitle(taskId, taskTitle)

        val lineage = listOfNotNull(
            workflowId?.trim()?.takeIf(String::isNotBlank)?.let {
                SpecCodingBundle.message("chat.execution.launch.meta.workflow", it)
            },
            runId?.trim()?.takeIf(String::isNotBlank)?.let {
                SpecCodingBundle.message("chat.execution.launch.meta.run", it)
            },
        ).joinToString(" · ")
        heroMetaLabel.text = lineage
        heroMetaLabel.isVisible = lineage.isNotBlank()

        heroIconLabel.icon = when {
            legacy || compactMode -> AllIcons.Vcs.HistoryInline
            trigger == ExecutionTrigger.USER_RETRY -> AllIcons.Actions.Refresh
            trigger == ExecutionTrigger.SYSTEM_RECOVERY -> AllIcons.Vcs.HistoryInline
            else -> AllIcons.Actions.Execute
        }

        metaChipPanel.removeAll()
        focusedStage?.let { stage ->
            metaChipPanel.add(createMetaChip(stageLabel(stage), ChipTone.ACCENT))
        }
        trigger?.let { executionTrigger ->
            metaChipPanel.add(
                createMetaChip(
                    triggerLabel(executionTrigger),
                    if (executionTrigger == ExecutionTrigger.SYSTEM_RECOVERY) ChipTone.WARNING else ChipTone.NEUTRAL,
                ),
            )
        }
        taskStatus?.let { status ->
            metaChipPanel.add(createMetaChip(formatTaskStatus(status), chipTone(status)))
        }
        taskPriority?.let { priority ->
            metaChipPanel.add(createMetaChip(priority.name, chipTone(priority)))
        }
    }

    private fun createActionRow(button: JButton): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(button)
        }
    }

    private fun createMetaChip(text: String, tone: ChipTone): JPanel {
        val palette = when (tone) {
            ChipTone.ACCENT -> CHIP_ACCENT_PALETTE
            ChipTone.SUCCESS -> CHIP_SUCCESS_PALETTE
            ChipTone.WARNING -> CHIP_WARNING_PALETTE
            ChipTone.MUTED -> CHIP_MUTED_PALETTE
            ChipTone.NEUTRAL -> CHIP_NEUTRAL_PALETTE
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = true
            background = palette.background
            border = JBUI.Borders.empty(3, 8, 3, 8)
            add(
                JBLabel(text).apply {
                    foreground = palette.foreground
                    font = font.deriveFont(11f)
                },
            )
        }
    }

    private fun createSectionPanel(
        title: String,
        detail: String,
        badgeText: String?,
        palette: SectionPalette = DETAIL_SECTION_PALETTE,
        topInset: Int = 8,
    ): JPanel {
        val panel = createSurfacePanel(
            palette = palette,
            topInset = topInset,
        )

        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        header.isOpaque = false
        header.add(
            JBLabel(title).apply {
                foreground = palette.titleForeground
                font = font.deriveFont(Font.BOLD, 11f)
            },
        )
        badgeText?.takeIf(String::isNotBlank)?.let { badge ->
            header.add(
                JBLabel(badge).apply {
                    foreground = palette.metaForeground
                    font = font.deriveFont(10f)
                },
            )
        }

        panel.add(header, BorderLayout.NORTH)
        panel.add(createWrappedLabel(detail, foreground = palette.bodyForeground), BorderLayout.CENTER)
        return panel
    }

    private fun createWrappedLabel(text: String, foreground: Color = BODY_FG): JTextArea {
        return JTextArea(text).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            isFocusable = false
            this.foreground = foreground
            font = JBUI.Fonts.label().deriveFont(11f)
            border = JBUI.Borders.empty(2, 0, 0, 0)
        }
    }

    private fun createSummaryLinePanel(text: String): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
            add(
                JBLabel(">").apply {
                    foreground = SUMMARY_FG
                    font = font.deriveFont(Font.BOLD, 11f)
                    border = JBUI.Borders.emptyRight(8)
                },
                BorderLayout.WEST,
            )
            add(
                createWrappedLabel(text).apply {
                    border = JBUI.Borders.empty()
                },
                BorderLayout.CENTER,
            )
        }
    }

    private fun createNoticePanel(text: String, topInset: Int = 8): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(topInset)
            add(
                createWrappedLabel(text, foreground = SUMMARY_FG).apply {
                    border = JBUI.Borders.empty()
                },
                BorderLayout.CENTER,
            )
        }
    }

    private fun sectionLabel(kind: WorkflowChatExecutionPresentationSectionKind): String {
        return when (kind) {
            WorkflowChatExecutionPresentationSectionKind.DEPENDENCIES ->
                SpecCodingBundle.message("chat.execution.launch.section.dependencies")

            WorkflowChatExecutionPresentationSectionKind.ARTIFACT_SUMMARIES ->
                SpecCodingBundle.message("chat.execution.launch.section.artifactSummaries")

            WorkflowChatExecutionPresentationSectionKind.CLARIFICATION_CONCLUSIONS ->
                SpecCodingBundle.message("chat.execution.launch.section.clarificationConclusions")

            WorkflowChatExecutionPresentationSectionKind.CODE_CONTEXT ->
                SpecCodingBundle.message("chat.execution.launch.section.codeContext")
        }
    }

    private fun sectionBadgeText(section: WorkflowChatExecutionPresentationSection): String? {
        val count = section.itemCount.takeIf { it > 0 } ?: return null
        return buildString {
            append(count)
            if (section.truncated) {
                append("+")
            }
        }
    }

    private fun sectionPreview(section: WorkflowChatExecutionPresentationSection): String {
        val previewItems = section.previewItems
            .mapNotNull { item ->
                normalizeExecutionPreview(item, SECTION_DETAIL_PREVIEW_MAX_LENGTH)
                    .takeIf(String::isNotBlank)
            }
            .map { item -> "- $item" }
        val lines = if (previewItems.isNotEmpty()) {
            previewItems.toMutableList()
        } else {
            mutableListOf(
                normalizeExecutionPreview(
                    section.emptyStateReason ?: SpecCodingBundle.message("chat.execution.launch.section.empty"),
                    SECTION_DETAIL_PREVIEW_MAX_LENGTH,
                ),
            )
        }
        if (section.truncated) {
            lines += SpecCodingBundle.message("chat.execution.launch.note.moreItems")
        }
        return lines.joinToString(separator = "\n")
    }

    private fun sectionSummaryHeadline(section: WorkflowChatExecutionPresentationSection): String {
        val summary = section.previewItems
            .asSequence()
            .mapNotNull { item ->
                normalizeExecutionPreview(item, SECTION_SUMMARY_PREVIEW_MAX_LENGTH)
                    .takeIf(String::isNotBlank)
            }
            .firstOrNull()
            ?: normalizeExecutionPreview(
                section.emptyStateReason ?: SpecCodingBundle.message("chat.execution.launch.section.empty"),
                SECTION_SUMMARY_PREVIEW_MAX_LENGTH,
            )
        val hiddenCount = (section.itemCount - 1).coerceAtLeast(0)
        return if (hiddenCount > 0) {
            "$summary (+$hiddenCount)"
        } else {
            summary
        }
    }

    private fun normalizeExecutionPreview(
        text: String,
        maxLength: Int,
    ): String {
        val normalized = text.lineSequence()
            .map(::normalizeExecutionPreviewLine)
            .filter(String::isNotBlank)
            .joinToString(" · ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        if (normalized.isBlank()) {
            return ""
        }
        if (normalized.length <= maxLength) {
            return normalized
        }
        return normalized.take(maxLength - 3).trimEnd() + "..."
    }

    private fun normalizeExecutionPreviewLine(line: String): String {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed == "```") {
            return ""
        }
        val withoutListPrefix = trimmed
            .removePrefix("- ")
            .removePrefix("* ")
            .replace(ORDERED_LIST_PREFIX, "")
            .trim()
        if (withoutListPrefix.count { it == '|' } < 2) {
            return withoutListPrefix
        }
        return normalizeTableLikePreview(withoutListPrefix)
    }

    private fun normalizeTableLikePreview(value: String): String {
        val rawCells = value.split('|')
            .map { cell -> cell.trim().replace(WHITESPACE_REGEX, " ") }
            .filter(String::isNotBlank)
            .filterNot(::isTableSeparatorCell)
        if (rawCells.isEmpty()) {
            return ""
        }
        val parts = mutableListOf<String>()
        var cells = rawCells
        val first = cells.first()
        if (!isGenericTableHeaderCell(first)) {
            parts += first.removeSuffix(":")
            cells = cells.drop(1)
        }
        if (cells.size >= 2 && isFieldContentHeaderPair(cells[0], cells[1])) {
            cells = cells.drop(2)
        }
        if (cells.size >= 2) {
            cells.chunked(2).forEach { chunk ->
                if (chunk.isEmpty()) {
                    return@forEach
                }
                parts += if (chunk.size == 2) {
                    "${chunk[0]} ${chunk[1]}".trim()
                } else {
                    chunk[0]
                }
            }
            return parts.joinToString(" · ")
        }
        parts += cells
        return parts.joinToString(" · ")
    }

    private fun isTableSeparatorCell(value: String): Boolean = TABLE_SEPARATOR_CELL.matches(value)

    private fun isGenericTableHeaderCell(value: String): Boolean {
        return value.equals("field", ignoreCase = true) ||
            value.equals("content", ignoreCase = true) ||
            value.equals("字段", ignoreCase = true) ||
            value.equals("内容", ignoreCase = true)
    }

    private fun isFieldContentHeaderPair(
        first: String,
        second: String,
    ): Boolean {
        return isGenericTableHeaderCell(first) && isGenericTableHeaderCell(second)
    }

    private fun triggerLabel(trigger: ExecutionTrigger): String {
        return when (trigger) {
            ExecutionTrigger.USER_EXECUTE -> SpecCodingBundle.message("chat.execution.launch.trigger.execute")
            ExecutionTrigger.USER_RETRY -> SpecCodingBundle.message("chat.execution.launch.trigger.retry")
            ExecutionTrigger.SYSTEM_RECOVERY -> SpecCodingBundle.message("chat.execution.launch.trigger.recovery")
        }
    }

    private fun legacyReasonLabel(reason: WorkflowChatExecutionLaunchFallbackReason): String {
        return when (reason) {
            WorkflowChatExecutionLaunchFallbackReason.MISSING_PRESENTATION_METADATA ->
                SpecCodingBundle.message("chat.execution.launch.note.legacy.missingPresentation")

            WorkflowChatExecutionLaunchFallbackReason.UNRECOGNIZED_LEGACY_PROMPT ->
                SpecCodingBundle.message("chat.execution.launch.note.legacy.unrecognized")
        }
    }

    private fun resolveHeroTitle(taskId: String?, taskTitle: String?): String {
        val normalizedTaskId = taskId?.trim()?.takeIf(String::isNotBlank)
        val normalizedTaskTitle = taskTitle?.trim()?.takeIf(String::isNotBlank)
        return when {
            normalizedTaskId != null && normalizedTaskTitle != null && normalizedTaskTitle != normalizedTaskId ->
                "$normalizedTaskId · $normalizedTaskTitle"

            normalizedTaskTitle != null -> normalizedTaskTitle
            normalizedTaskId != null -> normalizedTaskId
            else -> SpecCodingBundle.message("chat.execution.launch.title")
        }
    }

    private fun stageLabel(stage: StageId): String {
        return when (stage) {
            StageId.REQUIREMENTS -> SpecCodingBundle.message("spec.stage.requirements")
            StageId.DESIGN -> SpecCodingBundle.message("spec.stage.design")
            StageId.TASKS -> SpecCodingBundle.message("spec.stage.tasks")
            StageId.IMPLEMENT -> SpecCodingBundle.message("spec.stage.implement")
            StageId.VERIFY -> SpecCodingBundle.message("spec.stage.verify")
            StageId.ARCHIVE -> SpecCodingBundle.message("spec.stage.archive")
        }
    }

    private fun formatTaskStatus(status: TaskStatus): String {
        return when (status) {
            TaskStatus.PENDING -> SpecCodingBundle.message("toolwindow.workflow.binding.task.status.pending")
            TaskStatus.BLOCKED -> SpecCodingBundle.message("toolwindow.workflow.binding.task.status.blocked")
            TaskStatus.IN_PROGRESS -> SpecCodingBundle.message("toolwindow.workflow.binding.task.status.inProgress")
            TaskStatus.COMPLETED -> SpecCodingBundle.message("toolwindow.workflow.binding.task.status.completed")
            TaskStatus.CANCELLED -> SpecCodingBundle.message("toolwindow.workflow.binding.task.status.cancelled")
        }
    }

    private fun chipTone(status: TaskStatus): ChipTone {
        return when (status) {
            TaskStatus.PENDING -> ChipTone.ACCENT
            TaskStatus.IN_PROGRESS,
            TaskStatus.COMPLETED,
            -> ChipTone.SUCCESS
            TaskStatus.BLOCKED -> ChipTone.WARNING
            TaskStatus.CANCELLED -> ChipTone.MUTED
        }
    }

    private fun chipTone(priority: TaskPriority): ChipTone {
        return when (priority) {
            TaskPriority.P0,
            TaskPriority.P1,
            -> ChipTone.WARNING
            TaskPriority.P2 -> ChipTone.NEUTRAL
        }
    }

    private fun createSurfacePanel(
        palette: SectionPalette,
        topInset: Int,
    ): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            background = palette.background
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(topInset, 0, 0, 0)
        }
    }

    private fun styleSecondaryActionButton(button: JButton) {
        button.margin = JBUI.insets(0)
        button.isFocusPainted = false
        button.isFocusable = false
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.isBorderPainted = false
        button.foreground = ACTION_FG
        button.font = JBUI.Fonts.smallFont()
        button.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        button.border = JBUI.Borders.empty(2, 0, 2, 0)
        button.putClientProperty("JButton.buttonType", "borderless")
    }

    private data class ChipPalette(
        val background: Color,
        val foreground: Color,
    )

    private data class SectionPalette(
        val background: Color,
        val border: Color,
        val titleForeground: Color,
        val bodyForeground: Color,
        val metaForeground: Color,
    )

    private enum class ChipTone {
        ACCENT,
        SUCCESS,
        WARNING,
        MUTED,
        NEUTRAL,
    }

    companion object {
        private val CARD_BG = JBColor(Color(249, 251, 254), Color(39, 45, 54))
        private val CARD_BORDER = JBColor(Color(230, 236, 245), Color(67, 76, 88))
        private val HERO_TITLE_FG = JBColor(Color(36, 53, 79), Color(228, 233, 241))
        private val TITLE_FG = JBColor(Color(49, 66, 92), Color(225, 232, 243))
        private val SUMMARY_FG = JBColor(Color(104, 118, 140), Color(159, 170, 186))
        private val BODY_FG = JBColor(Color(72, 84, 103), Color(213, 220, 231))
        private val ACTION_FG = JBColor(Color(45, 96, 160), Color(157, 201, 255))
        private val CHIP_ACCENT_PALETTE = ChipPalette(
            background = JBColor(Color(232, 240, 253), Color(66, 80, 99)),
            foreground = JBColor(Color(39, 87, 155), Color(197, 220, 255)),
        )
        private val CHIP_SUCCESS_PALETTE = ChipPalette(
            background = JBColor(Color(231, 245, 236), Color(57, 83, 69)),
            foreground = JBColor(Color(28, 110, 69), Color(190, 236, 209)),
        )
        private val CHIP_WARNING_PALETTE = ChipPalette(
            background = JBColor(Color(255, 244, 228), Color(92, 74, 62)),
            foreground = JBColor(Color(155, 94, 16), Color(248, 219, 174)),
        )
        private val CHIP_MUTED_PALETTE = ChipPalette(
            background = JBColor(Color(242, 245, 249), Color(73, 79, 88)),
            foreground = JBColor(Color(95, 106, 119), Color(197, 204, 214)),
        )
        private val CHIP_NEUTRAL_PALETTE = ChipPalette(
            background = JBColor(Color(239, 244, 250), Color(61, 69, 80)),
            foreground = JBColor(Color(74, 91, 116), Color(204, 213, 226)),
        )
        private val USER_NOTE_SECTION_PALETTE = SectionPalette(
            background = JBColor(Color(255, 250, 239), Color(84, 72, 55)),
            border = JBColor(Color(235, 210, 159), Color(126, 105, 82)),
            titleForeground = JBColor(Color(135, 96, 27), Color(247, 219, 165)),
            bodyForeground = JBColor(Color(120, 88, 36), Color(234, 222, 196)),
            metaForeground = JBColor(Color(148, 113, 56), Color(214, 195, 162)),
        )
        private val CONTEXT_SECTION_PALETTE = SectionPalette(
            background = JBColor(Color(250, 252, 255), Color(46, 53, 63)),
            border = JBColor(Color(207, 218, 236), Color(82, 94, 109)),
            titleForeground = TITLE_FG,
            bodyForeground = BODY_FG,
            metaForeground = SUMMARY_FG,
        )
        private val DETAIL_SECTION_PALETTE = SectionPalette(
            background = JBColor(Color(244, 248, 254), Color(52, 59, 69)),
            border = JBColor(Color(208, 219, 237), Color(85, 97, 112)),
            titleForeground = TITLE_FG,
            bodyForeground = BODY_FG,
            metaForeground = SUMMARY_FG,
        )
        private val SUMMARY_LINE_PALETTE = SectionPalette(
            background = JBColor(Color(243, 247, 253), Color(53, 60, 71)),
            border = JBColor(Color(209, 219, 236), Color(86, 97, 113)),
            titleForeground = TITLE_FG,
            bodyForeground = BODY_FG,
            metaForeground = SUMMARY_FG,
        )
        private val DEBUG_SECTION_PALETTE = SectionPalette(
            background = JBColor(Color(247, 250, 255), Color(47, 54, 64)),
            border = JBColor(Color(204, 216, 235), Color(84, 96, 111)),
            titleForeground = TITLE_FG,
            bodyForeground = BODY_FG,
            metaForeground = SUMMARY_FG,
        )
        private val NOTE_SECTION_PALETTE = SectionPalette(
            background = JBColor(Color(244, 247, 252), Color(58, 64, 74)),
            border = JBColor(Color(205, 214, 227), Color(88, 97, 109)),
            titleForeground = SUMMARY_FG,
            bodyForeground = SUMMARY_FG,
            metaForeground = SUMMARY_FG,
        )
        private const val COMPACT_SUMMARY_LINE_LIMIT = 2
        private const val SECTION_SUMMARY_PREVIEW_MAX_LENGTH = 140
        private const val SECTION_DETAIL_PREVIEW_MAX_LENGTH = 220
        private val ORDERED_LIST_PREFIX = Regex("^\\d+[.)]\\s+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val TABLE_SEPARATOR_CELL = Regex("^[\\-:]+$")
    }
}
