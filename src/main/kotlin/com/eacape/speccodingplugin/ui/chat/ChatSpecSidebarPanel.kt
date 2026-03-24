package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecMarkdownSanitizer
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.ui.RefreshFeedback
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.JTextPane

internal class ChatSpecSidebarPanel(
    private val loadWorkflow: (String) -> Result<SpecWorkflow>,
    private val listWorkflows: () -> List<String>,
    private val onOpenDocument: ((workflowId: String, phase: SpecPhase) -> Unit)? = null,
    private val onEditWorkflow: ((workflowId: String) -> Unit)? = null,
) : JPanel(BorderLayout()) {

    private val titleLabel = JBLabel()
    private val workflowLabel = JBLabel()
    private val workflowDescriptionLabel = JBLabel()
    private val statusBadgeLabel = JBLabel()
    private val phaseLabel = JBLabel()
    private val phaseChipsPanel = JPanel(GridLayout(1, SpecPhase.entries.size, RHYTHM_XS, 0))
    private val refreshButton = JButton()
    private val editWorkflowButton = JButton()
    private val openFileButton = JButton()
    private val documentTitleLabel = JBLabel()
    private val contentPane = JTextPane()
    private val contentCardPanel = JPanel(BorderLayout())
    private val statusLabel = JBLabel()
    private val phaseButtons = linkedMapOf<SpecPhase, JButton>()

    private var currentWorkflowId: String? = null
    private var currentWorkflow: SpecWorkflow? = null
    private var selectedPhase: SpecPhase = SpecPhase.SPECIFY

    init {
        isOpaque = true
        background = JBColor(Color(250, 252, 254), Color(41, 45, 51))
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(
                JBColor(Color(228, 234, 242), Color(73, 80, 90)),
                1,
                0,
                1,
                0,
            ),
            JBUI.Borders.empty(RHYTHM_SM, HORIZONTAL_PADDING, RHYTHM_SM, HORIZONTAL_PADDING),
        )

        val headerPanel = JPanel(BorderLayout())
        headerPanel.isOpaque = false
        headerPanel.border = JBUI.Borders.emptyBottom(RHYTHM_SM)

        val titlePanel = JPanel()
        titlePanel.layout = javax.swing.BoxLayout(titlePanel, javax.swing.BoxLayout.Y_AXIS)
        titlePanel.isOpaque = false
        titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD, 12f)
        workflowLabel.font = workflowLabel.font.deriveFont(11f)
        workflowLabel.foreground = JBColor.GRAY
        workflowDescriptionLabel.font = workflowDescriptionLabel.font.deriveFont(11f)
        workflowDescriptionLabel.foreground = JBColor.GRAY
        titlePanel.add(titleLabel)
        titlePanel.add(workflowLabel)
        titlePanel.add(workflowDescriptionLabel)
        headerPanel.add(titlePanel, BorderLayout.CENTER)

        statusBadgeLabel.font = statusBadgeLabel.font.deriveFont(10f)
        statusBadgeLabel.isOpaque = true
        statusBadgeLabel.border = JBUI.Borders.empty(1, 6, 1, 6)
        statusBadgeLabel.isVisible = false

        val headerActions = JPanel(FlowLayout(FlowLayout.RIGHT, RHYTHM_XS, 0))
        headerActions.isOpaque = false
        styleIconActionButton(refreshButton, AllIcons.Actions.Refresh)
        refreshButton.addActionListener { refreshCurrentWorkflow(showFeedback = true) }
        styleIconActionButton(editWorkflowButton, AllIcons.Actions.Edit)
        editWorkflowButton.addActionListener {
            val handler = onEditWorkflow ?: return@addActionListener
            val workflowId = currentWorkflow?.id ?: currentWorkflowId
            if (!workflowId.isNullOrBlank()) {
                handler.invoke(workflowId)
            }
        }
        styleIconActionButton(openFileButton, AllIcons.Actions.MenuOpen)
        openFileButton.addActionListener { openCurrentPhaseDocument() }
        headerActions.add(statusBadgeLabel)
        headerActions.add(editWorkflowButton)
        headerActions.add(refreshButton)
        headerActions.add(openFileButton)
        headerPanel.add(headerActions, BorderLayout.EAST)

        val phaseRow = JPanel(BorderLayout(RHYTHM_XS, 0))
        phaseRow.isOpaque = false
        phaseRow.border = JBUI.Borders.emptyBottom(RHYTHM_SM)
        phaseLabel.font = JBUI.Fonts.smallFont()
        phaseLabel.foreground = JBColor.GRAY
        phaseChipsPanel.isOpaque = false
        phaseRow.add(phaseLabel, BorderLayout.WEST)
        phaseRow.add(phaseChipsPanel, BorderLayout.CENTER)
        for (phase in SpecPhase.entries) {
            val chip = JButton()
            stylePhaseChipButton(chip)
            chip.addActionListener {
                if (selectedPhase == phase) return@addActionListener
                selectedPhase = phase
                renderCurrentWorkflow()
            }
            phaseButtons[phase] = chip
            phaseChipsPanel.add(chip)
        }

        configureReadableContentPane(contentPane)
        val contentScrollPane = JBScrollPane(contentPane).apply {
            border = JBUI.Borders.empty(0)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            viewport.isOpaque = false
            isOpaque = false
        }
        contentCardPanel.isOpaque = true
        contentCardPanel.background = JBColor(Color(253, 254, 255), Color(47, 51, 58))
        contentCardPanel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor(Color(233, 236, 242), Color(76, 82, 91)), 1),
            JBUI.Borders.empty(RHYTHM_SM, CARD_HORIZONTAL_PADDING, RHYTHM_SM, CARD_HORIZONTAL_PADDING),
        )
        contentCardPanel.add(contentScrollPane, BorderLayout.CENTER)

        val centerPanel = JPanel(BorderLayout())
        centerPanel.isOpaque = false
        centerPanel.add(phaseRow, BorderLayout.NORTH)
        val documentPanel = JPanel(BorderLayout())
        documentPanel.isOpaque = false
        documentTitleLabel.font = JBUI.Fonts.smallFont().deriveFont(java.awt.Font.BOLD)
        documentTitleLabel.foreground = JBColor(
            Color(67, 86, 110),
            Color(179, 192, 210),
        )
        documentTitleLabel.border = JBUI.Borders.emptyBottom(RHYTHM_XS)
        documentPanel.add(documentTitleLabel, BorderLayout.NORTH)
        documentPanel.add(contentCardPanel, BorderLayout.CENTER)
        centerPanel.add(documentPanel, BorderLayout.CENTER)
        centerPanel.add(statusLabel, BorderLayout.SOUTH)

        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = JBColor.GRAY
        statusLabel.border = JBUI.Borders.emptyTop(RHYTHM_SM)

        add(headerPanel, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)

        refreshLocalizedTexts()
        showEmptyState(SpecCodingBundle.message("toolwindow.spec.command.status.none"))
    }

    fun refreshLocalizedTexts() {
        titleLabel.text = SpecCodingBundle.message("toolwindow.spec.sidebar.title")
        phaseLabel.text = SpecCodingBundle.message("toolwindow.spec.card.phase")
        refreshButton.toolTipText = SpecCodingBundle.message("spec.workflow.refresh")
        refreshButton.accessibleContext.accessibleName = refreshButton.toolTipText
        editWorkflowButton.toolTipText = SpecCodingBundle.message("toolwindow.spec.sidebar.edit.tooltip")
        editWorkflowButton.accessibleContext.accessibleName = editWorkflowButton.toolTipText
        openFileButton.toolTipText = SpecCodingBundle.message("toolwindow.spec.sidebar.openFile.tooltip")
        openFileButton.accessibleContext.accessibleName = openFileButton.toolTipText
        for ((phase, button) in phaseButtons) {
            button.text = phaseDisplayName(phase)
        }
        if (currentWorkflow == null) {
            showEmptyState(SpecCodingBundle.message("toolwindow.spec.command.status.none"))
        } else {
            renderCurrentWorkflow()
        }
    }

    fun focusWorkflow(workflowId: String, preferredPhase: SpecPhase? = null) {
        val normalizedId = workflowId.trim()
        if (normalizedId.isBlank()) return
        currentWorkflowId = normalizedId
        reloadWorkflow(preferredPhase = preferredPhase, allowFallbackToLatest = false)
    }

    fun refreshCurrentWorkflow(showFeedback: Boolean = false) {
        reloadWorkflow(preferredPhase = null, allowFallbackToLatest = true, showRefreshFeedback = showFeedback)
    }

    fun hasFocusedWorkflow(workflowId: String?): Boolean {
        val targetId = workflowId?.trim().orEmpty()
        if (targetId.isBlank()) return false
        return currentWorkflowId == targetId
    }

    fun currentFocusedWorkflowId(): String? = currentWorkflowId

    fun clearFocusedWorkflow() {
        currentWorkflowId = null
        currentWorkflow = null
        selectedPhase = SpecPhase.SPECIFY
        showEmptyState(SpecCodingBundle.message("toolwindow.spec.command.status.none"))
    }

    internal fun currentContentForTest(): String = contentPane.text

    internal fun currentStatusForTest(): String = statusLabel.text

    internal fun triggerOpenCurrentPhaseDocumentForTest() {
        openCurrentPhaseDocument()
    }

    private fun reloadWorkflow(
        preferredPhase: SpecPhase?,
        allowFallbackToLatest: Boolean,
        showRefreshFeedback: Boolean = false,
    ) {
        val workflowId = currentWorkflowId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: if (allowFallbackToLatest) {
                latestWorkflowIdOrNull()
            } else {
                null
            }
        if (workflowId.isNullOrBlank()) {
            currentWorkflow = null
            currentWorkflowId = null
            showEmptyState(SpecCodingBundle.message("toolwindow.spec.command.status.none"))
            return
        }

        val previousWorkflowId = currentWorkflow?.id
        loadWorkflow(workflowId)
            .onSuccess { workflow ->
                currentWorkflowId = workflow.id
                currentWorkflow = workflow
                selectedPhase = when {
                    preferredPhase != null -> preferredPhase
                    previousWorkflowId != workflow.id -> workflow.currentPhase
                    else -> selectedPhase
                }
                renderCurrentWorkflow()
                if (showRefreshFeedback) {
                    val successText = SpecCodingBundle.message("common.refresh.success")
                    RefreshFeedback.flashButtonSuccess(refreshButton, successText)
                    RefreshFeedback.flashLabelSuccess(statusLabel, successText, REFRESH_SUCCESS_FG)
                }
            }
            .onFailure { error ->
                showEmptyState(
                    SpecCodingBundle.message(
                        "toolwindow.spec.sidebar.loadFailed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
                )
            }
    }

    private fun renderCurrentWorkflow() {
        val workflow = currentWorkflow
        if (workflow == null) {
            showEmptyState(SpecCodingBundle.message("toolwindow.spec.command.status.none"))
            return
        }
        val phase = selectedPhase
        val document = workflow.getDocument(phase)
        val phaseValidation = document?.validationResult
        val rawContent = document?.content
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            .orEmpty()
        val content = SpecMarkdownSanitizer.sanitize(rawContent)

        titleLabel.text = workflow.title.ifBlank { workflow.id }
        workflowLabel.text = SpecCodingBundle.message("toolwindow.spec.sidebar.workflow", workflow.id)
        val normalizedDescription = workflow.description
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalizedDescription.isBlank()) {
            workflowDescriptionLabel.text = ""
            workflowDescriptionLabel.toolTipText = null
            workflowDescriptionLabel.isVisible = false
        } else {
            workflowDescriptionLabel.text = truncateSingleLine(normalizedDescription, maxChars = 120)
            workflowDescriptionLabel.toolTipText = normalizedDescription
            workflowDescriptionLabel.isVisible = true
        }
        documentTitleLabel.text = SpecCodingBundle.message(
            "toolwindow.spec.sidebar.document",
            phaseDisplayName(phase),
            phase.outputFileName,
        )
        statusBadgeLabel.isVisible = true
        statusBadgeLabel.text = workflowStatusDisplayName(workflow.status)
        applyWorkflowStatusBadgeStyle(workflow.status)
        updatePhaseButtons(workflow)
        editWorkflowButton.isEnabled = onEditWorkflow != null
        openFileButton.isEnabled = onOpenDocument != null
        if (content.isBlank()) {
            MarkdownRenderer.render(
                contentPane,
                SpecCodingBundle.message("spec.detail.noDocumentForPhase", phase.displayName),
            )
            statusLabel.text = SpecCodingBundle.message("toolwindow.spec.sidebar.emptyDocument")
            statusLabel.foreground = JBColor.GRAY
        } else {
            MarkdownRenderer.render(contentPane, content)
            val validationText = when {
                phaseValidation == null -> SpecCodingBundle.message("spec.detail.validation.none")
                phaseValidation.valid -> SpecCodingBundle.message("spec.detail.validation.passed")
                else -> SpecCodingBundle.message("spec.detail.validation.failed")
            }
            val lineCount = content.lineSequence().count()
            statusLabel.text = SpecCodingBundle.message(
                "toolwindow.spec.sidebar.status",
                validationText,
                lineCount,
            )
            statusLabel.foreground = when {
                phaseValidation == null -> JBColor.GRAY
                phaseValidation.valid -> JBColor(Color(74, 154, 80), Color(112, 191, 118))
                else -> JBColor(Color(195, 52, 52), Color(255, 138, 128))
            }
        }
        contentPane.caretPosition = 0
    }

    private fun showEmptyState(message: String) {
        titleLabel.text = SpecCodingBundle.message("toolwindow.spec.sidebar.title")
        workflowLabel.text = ""
        workflowDescriptionLabel.text = ""
        workflowDescriptionLabel.toolTipText = null
        workflowDescriptionLabel.isVisible = false
        documentTitleLabel.text = SpecCodingBundle.message("toolwindow.spec.sidebar.document.empty")
        statusBadgeLabel.isVisible = false
        updatePhaseButtons(null)
        editWorkflowButton.isEnabled = false
        openFileButton.isEnabled = onOpenDocument != null
        statusLabel.foreground = JBColor.GRAY
        statusLabel.text = message
        MarkdownRenderer.render(contentPane, message)
        contentPane.caretPosition = 0
    }

    private fun truncateSingleLine(value: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= maxChars) return normalized
        return normalized.take(maxChars - 1).trimEnd() + "…"
    }

    private fun openCurrentPhaseDocument() {
        val openDocument = onOpenDocument ?: return
        if (currentWorkflow == null) {
            reloadWorkflow(preferredPhase = null, allowFallbackToLatest = true)
        }
        val workflow = currentWorkflow ?: return
        val phase = workflow.currentPhase
        if (selectedPhase != phase) {
            selectedPhase = phase
            renderCurrentWorkflow()
        }
        if (workflow.getDocument(phase) == null) {
            statusLabel.foreground = JBColor.GRAY
            statusLabel.text = SpecCodingBundle.message("spec.history.noCurrentDocument")
            return
        }
        openDocument.invoke(workflow.id, phase)
    }

    private fun latestWorkflowIdOrNull(): String? {
        return runCatching {
            listWorkflows()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .sorted()
                .lastOrNull()
        }.onFailure { error ->
            showEmptyState(
                SpecCodingBundle.message(
                    "toolwindow.spec.sidebar.loadFailed",
                    error.message ?: SpecCodingBundle.message("common.unknown"),
                ),
            )
        }.getOrNull()
    }

    private fun updatePhaseButtons(workflow: SpecWorkflow?) {
        for ((phase, button) in phaseButtons) {
            val selected = selectedPhase == phase
            val isCurrent = workflow?.currentPhase == phase
            val hasDocument = workflow?.getDocument(phase) != null
            button.text = phaseDisplayName(phase)
            button.isEnabled = workflow != null
            button.toolTipText = when {
                workflow == null -> null
                selected -> SpecCodingBundle.message("toolwindow.spec.sidebar.phase.selected")
                hasDocument -> SpecCodingBundle.message("toolwindow.spec.sidebar.phase.generated")
                else -> SpecCodingBundle.message("toolwindow.spec.sidebar.phase.pending")
            }
            val borderColor = when {
                selected -> JBColor(Color(133, 170, 222), Color(111, 142, 188))
                isCurrent -> JBColor(Color(210, 223, 241), Color(88, 102, 123))
                else -> JBColor(Color(232, 237, 243), Color(80, 86, 95))
            }
            val bgColor = when {
                selected -> JBColor(Color(237, 245, 255), Color(58, 77, 104))
                isCurrent -> JBColor(Color(244, 248, 253), Color(55, 61, 70))
                hasDocument -> JBColor(Color(246, 251, 246), Color(52, 64, 56))
                else -> JBColor(Color(249, 251, 254), Color(49, 53, 59))
            }
            val fgColor = when {
                selected -> JBColor(Color(58, 92, 146), Color(192, 218, 253))
                hasDocument -> JBColor(Color(69, 100, 71), Color(160, 204, 166))
                else -> JBColor(Color(98, 106, 118), Color(182, 190, 202))
            }
            button.background = bgColor
            button.foreground = fgColor
            button.border = JBUI.Borders.customLine(borderColor, 1)
            val size = JBUI.size(PHASE_CHIP_MIN_WIDTH, CONTROL_HEIGHT)
            button.preferredSize = size
            button.minimumSize = size
            button.maximumSize = JBUI.size(Int.MAX_VALUE, CONTROL_HEIGHT)
        }
    }

    private fun applyWorkflowStatusBadgeStyle(status: WorkflowStatus) {
        val style = when (status) {
            WorkflowStatus.IN_PROGRESS -> Triple(
                JBColor(Color(231, 242, 255), Color(61, 84, 113)),
                JBColor(Color(57, 91, 142), Color(196, 220, 250)),
                JBColor(Color(186, 209, 240), Color(106, 138, 179)),
            )
            WorkflowStatus.PAUSED -> Triple(
                JBColor(Color(248, 241, 226), Color(95, 84, 60)),
                JBColor(Color(135, 105, 53), Color(238, 218, 170)),
                JBColor(Color(228, 208, 166), Color(125, 113, 87)),
            )
            WorkflowStatus.COMPLETED -> Triple(
                JBColor(Color(234, 248, 236), Color(53, 80, 59)),
                JBColor(Color(67, 113, 73), Color(170, 225, 178)),
                JBColor(Color(182, 223, 188), Color(90, 127, 97)),
            )
            WorkflowStatus.FAILED -> Triple(
                JBColor(Color(252, 237, 237), Color(96, 60, 60)),
                JBColor(Color(149, 65, 65), Color(249, 186, 186)),
                JBColor(Color(234, 188, 188), Color(122, 87, 87)),
            )
        }
        statusBadgeLabel.background = style.first
        statusBadgeLabel.foreground = style.second
        statusBadgeLabel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(style.third, 1),
            JBUI.Borders.empty(1, 6, 1, 6),
        )
    }

    private fun workflowStatusDisplayName(status: WorkflowStatus): String {
        return when (status) {
            WorkflowStatus.IN_PROGRESS -> SpecCodingBundle.message("spec.workflow.status.inProgress")
            WorkflowStatus.PAUSED -> SpecCodingBundle.message("spec.workflow.status.paused")
            WorkflowStatus.COMPLETED -> SpecCodingBundle.message("spec.workflow.status.completed")
            WorkflowStatus.FAILED -> SpecCodingBundle.message("spec.workflow.status.failed")
        }
    }

    private fun phaseDisplayName(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> SpecCodingBundle.message("spec.phase.specify")
            SpecPhase.DESIGN -> SpecCodingBundle.message("spec.phase.design")
            SpecPhase.IMPLEMENT -> SpecCodingBundle.message("spec.phase.implement")
        }
    }

    private fun configureReadableContentPane(pane: JTextPane) {
        pane.isEditable = false
        pane.isOpaque = false
        pane.border = JBUI.Borders.empty(0)
    }

    private fun styleTextActionButton(button: JButton) {
        button.margin = JBUI.insets(0, 6, 0, 6)
        button.isFocusPainted = false
        button.isFocusable = false
        button.font = JBUI.Fonts.smallFont()
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.border = JBUI.Borders.empty(1, 4, 1, 4)
        button.putClientProperty("JButton.buttonType", "borderless")
    }

    private fun styleIconActionButton(button: JButton, icon: javax.swing.Icon) {
        styleTextActionButton(button)
        button.icon = icon
        button.text = ""
        button.preferredSize = JBUI.size(CONTROL_HEIGHT, CONTROL_HEIGHT)
        button.minimumSize = JBUI.size(CONTROL_HEIGHT, CONTROL_HEIGHT)
        button.maximumSize = JBUI.size(CONTROL_HEIGHT, CONTROL_HEIGHT)
        button.margin = JBUI.insets(0, 0, 0, 0)
    }

    private fun stylePhaseChipButton(button: JButton) {
        button.margin = JBUI.insets(0, 8, 0, 8)
        button.isFocusPainted = false
        button.isFocusable = false
        button.isOpaque = true
        button.isContentAreaFilled = true
        button.font = JBUI.Fonts.smallFont()
        button.putClientProperty("JButton.buttonType", "borderless")
        button.horizontalAlignment = javax.swing.SwingConstants.CENTER
    }

    companion object {
        private const val RHYTHM_XS = 4
        private const val RHYTHM_SM = 8
        private const val HORIZONTAL_PADDING = 10
        private const val CARD_HORIZONTAL_PADDING = 9
        private const val CONTROL_HEIGHT = 22
        private const val PHASE_CHIP_MIN_WIDTH = 76
        private val REFRESH_SUCCESS_FG = JBColor(Color(74, 154, 80), Color(112, 191, 118))
    }
}
