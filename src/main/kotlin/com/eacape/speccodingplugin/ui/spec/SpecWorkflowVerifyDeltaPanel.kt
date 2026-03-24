package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDeltaBaselineRef
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.spec.VerifyRunHistoryEntry
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

internal sealed interface SpecWorkflowDeltaBaselineChoice {
    val stableId: String
}

internal data class SpecWorkflowReferenceBaselineChoice(
    val workflowId: String,
    val title: String,
) : SpecWorkflowDeltaBaselineChoice {
    override val stableId: String = "workflow:$workflowId"
}

internal data class SpecWorkflowPinnedDeltaBaselineChoice(
    val baseline: SpecDeltaBaselineRef,
) : SpecWorkflowDeltaBaselineChoice {
    override val stableId: String = "baseline:${baseline.baselineId}"
}

internal data class SpecWorkflowVerifyDeltaState(
    val workflowId: String,
    val verifyEnabled: Boolean,
    val verificationDocumentAvailable: Boolean,
    val verificationHistory: List<VerifyRunHistoryEntry>,
    val baselineChoices: List<SpecWorkflowDeltaBaselineChoice>,
    val deltaSummary: String? = null,
    val preferredBaselineChoiceId: String?,
    val canPinBaseline: Boolean,
    val refreshedAtMillis: Long,
)

internal class SpecWorkflowVerifyDeltaPanel(
    private val onRunVerifyRequested: (workflowId: String) -> Unit = {},
    private val onOpenVerificationRequested: (workflowId: String) -> Unit = {},
    private val onCompareBaselineRequested: (workflowId: String, choice: SpecWorkflowDeltaBaselineChoice) -> Unit = { _, _ -> },
    private val onPinBaselineRequested: (workflowId: String) -> Unit = {},
    private val showHeader: Boolean = true,
) : JPanel(BorderLayout(0, JBUI.scale(6))) {

    private val headerTitleLabel = JBLabel().apply {
        font = JBUI.Fonts.label().deriveFont(12.5f)
        foreground = HEADER_FG
    }
    private val headerSummaryLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = HEADER_SECONDARY_FG
    }
    private val headerRefreshedLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.RIGHT
        font = JBUI.Fonts.smallFont().deriveFont(10.5f)
        foreground = HEADER_SECONDARY_FG
    }
    private val statusChipLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        border = BorderFactory.createEmptyBorder()
        isOpaque = true
        font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD, 10.5f)
    }
    private val baselineLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = HEADER_SECONDARY_FG
    }
    private val baselineComboBox = JComboBox<SpecWorkflowDeltaBaselineChoice>().apply {
        renderer = SimpleListCellRenderer.create { label, value, _ ->
            label.text = baselineChoiceLabel(value)
        }
        isEnabled = false
    }
    private val runVerifyButton = JButton().apply {
        isFocusable = false
        isEnabled = false
        SpecUiStyle.styleIconActionButton(this, size = 24, arc = 12)
        addActionListener { handleRunVerify() }
    }
    private val openVerificationButton = JButton().apply {
        isFocusable = false
        isEnabled = false
        SpecUiStyle.styleIconActionButton(this, size = 24, arc = 12)
        addActionListener { handleOpenVerification() }
    }
    private val compareButton = JButton().apply {
        isFocusable = false
        isEnabled = false
        SpecUiStyle.styleIconActionButton(this, size = 24, arc = 12)
        addActionListener { handleCompareBaseline() }
    }
    private val pinBaselineButton = JButton().apply {
        isFocusable = false
        isEnabled = false
        SpecUiStyle.styleIconActionButton(this, size = 24, arc = 12)
        addActionListener { handlePinBaseline() }
    }

    private val historyListModel = DefaultListModel<VerifyRunHistoryEntry>()
    private val historyList = JBList(historyListModel).apply {
        cellRenderer = HistoryCellRenderer()
        visibleRowCount = -1
        fixedCellHeight = -1
        isOpaque = false
        border = JBUI.Borders.empty()
        emptyText.text = SpecCodingBundle.message("spec.toolwindow.verifyDelta.empty")
    }
    private val detailArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6, 8)
        background = DETAIL_BG
        foreground = DETAIL_FG
        font = JBUI.Fonts.smallFont()
    }

    private var currentState: SpecWorkflowVerifyDeltaState? = null
    private var emptyMessageKey: String = "spec.toolwindow.verifyDelta.empty"
    private var currentSelectionId: String? = null
    private val historyScrollPane = JBScrollPane(historyList).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        viewport.isOpaque = false
        isOpaque = false
        SpecUiStyle.applyFastVerticalScrolling(this)
    }
    private val detailScrollPane = JBScrollPane(detailArea).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        SpecUiStyle.applyFastVerticalScrolling(this)
    }
    private val contentSplitPane = JSplitPane(
        JSplitPane.VERTICAL_SPLIT,
        historyScrollPane,
        detailScrollPane,
    ).apply {
        resizeWeight = DEFAULT_SPLIT_RESIZE_WEIGHT
        dividerLocation = EMPTY_HISTORY_HEIGHT
        isContinuousLayout = true
        border = JBUI.Borders.empty()
        background = DETAIL_BG
        SpecUiStyle.applyChatLikeSpecDivider(this, dividerSize = JBUI.scale(4))
    }
    private val headerPanel = buildHeader()
    private val contentPanel = buildContent()

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 4, 6, 4)
        minimumSize = JBUI.size(220, 0)
        add(headerPanel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = baselineComboBox,
            minWidth = JBUI.scale(120),
            maxWidth = JBUI.scale(320),
            height = JBUI.scale(24),
        )

        baselineComboBox.addActionListener {
            updateControls()
            updateDetail()
        }
        historyList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                currentSelectionId = historyList.selectedValue?.runId
                updateControls()
                updateDetail()
            }
        }
        refreshLocalizedTexts()
        showEmpty()
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        val height = insets.top +
            insets.bottom +
            headerPanel.preferredSize.height +
            JBUI.scale(6) +
            dynamicContentHeight()
        return Dimension(base.width, height)
    }

    private fun dynamicContentHeight(): Int {
        return dynamicHistoryHeight() + contentSplitPane.dividerSize + dynamicDetailHeight()
    }

    private fun dynamicHistoryHeight(): Int {
        val listHeight = historyList.preferredSize.height.takeIf { it > 0 } ?: EMPTY_HISTORY_HEIGHT
        val scrollBorderInsets = historyScrollPane.border?.getBorderInsets(historyScrollPane)
        val verticalBorder = (scrollBorderInsets?.top ?: 0) + (scrollBorderInsets?.bottom ?: 0)
        return maxOf(EMPTY_HISTORY_HEIGHT, listHeight + verticalBorder)
    }

    private fun dynamicDetailHeight(): Int {
        val textInsets = detailArea.insets
        val lineHeight = detailArea.getFontMetrics(detailArea.font).height
        val logicalLineCount = detailArea.text.lines().size.coerceAtLeast(MIN_DETAIL_LINE_COUNT)
        val lineBasedHeight = logicalLineCount * lineHeight + textInsets.top + textInsets.bottom
        val preferredTextHeight = detailArea.preferredSize.height.takeIf { it > 0 } ?: 0
        val scrollBorderInsets = detailScrollPane.border?.getBorderInsets(detailScrollPane)
        val verticalBorder = (scrollBorderInsets?.top ?: 0) + (scrollBorderInsets?.bottom ?: 0)
        return maxOf(MIN_DETAIL_HEIGHT, lineBasedHeight, preferredTextHeight) + verticalBorder
    }

    private fun applyPreferredSplitPaneLayout() {
        val emptyHistory = historyListModel.isEmpty()
        val historyTargetHeight = if (emptyHistory) {
            EMPTY_HISTORY_HEIGHT
        } else {
            dynamicHistoryHeight().coerceAtMost(MAX_HISTORY_PANE_HEIGHT)
        }
        val detailReservedHeight = if (emptyHistory) {
            EMPTY_STATE_DETAIL_RESERVED_HEIGHT
        } else {
            MIN_DETAIL_HEIGHT
        }
        val availableContentHeight = (contentSplitPane.height - contentSplitPane.dividerSize)
            .takeIf { it > 0 }
        val dividerLocation = if (availableContentHeight != null) {
            val maxHistoryHeight = (availableContentHeight - detailReservedHeight)
                .coerceAtLeast(MIN_HISTORY_PANE_HEIGHT)
            historyTargetHeight.coerceAtMost(maxHistoryHeight)
        } else {
            historyTargetHeight
        }
        contentSplitPane.resizeWeight = if (emptyHistory) EMPTY_STATE_SPLIT_RESIZE_WEIGHT else DEFAULT_SPLIT_RESIZE_WEIGHT
        contentSplitPane.dividerLocation = dividerLocation
    }

    fun refreshLocalizedTexts() {
        headerTitleLabel.text = SpecCodingBundle.message("spec.toolwindow.verifyDelta.title")
        baselineLabel.text = SpecCodingBundle.message("spec.toolwindow.verifyDelta.baseline.label")
        applyActionButtonPresentation()
        updateHeader()
        updateDetail()
        baselineComboBox.repaint()
        ComboBoxAutoWidthSupport.refreshSelectedItemAutoWidth(baselineComboBox)
    }

    fun showEmpty() {
        currentState = null
        currentSelectionId = null
        emptyMessageKey = "spec.toolwindow.verifyDelta.empty"
        historyListModel.clear()
        baselineComboBox.model = DefaultComboBoxModel<SpecWorkflowDeltaBaselineChoice>()
        historyList.emptyText.text = SpecCodingBundle.message(emptyMessageKey)
        updateHeader()
        updateControls()
        updateDetail()
        applyPreferredSplitPaneLayout()
        revalidate()
        repaint()
    }

    fun showLoading() {
        currentState = null
        currentSelectionId = null
        emptyMessageKey = "spec.toolwindow.verifyDelta.loading"
        historyListModel.clear()
        baselineComboBox.model = DefaultComboBoxModel<SpecWorkflowDeltaBaselineChoice>()
        historyList.emptyText.text = SpecCodingBundle.message(emptyMessageKey)
        updateHeader()
        updateControls()
        updateDetail()
        applyPreferredSplitPaneLayout()
        revalidate()
        repaint()
    }

    fun updateState(state: SpecWorkflowVerifyDeltaState) {
        currentState = state
        val previousRunId = currentSelectionId
        emptyMessageKey = "spec.toolwindow.verifyDelta.empty"

        historyListModel.clear()
        state.verificationHistory.forEach(historyListModel::addElement)
        historyList.emptyText.text = if (state.verificationHistory.isEmpty()) {
            SpecCodingBundle.message("spec.toolwindow.verifyDelta.history.empty")
        } else {
            ""
        }

        updateBaselineChoices(state)
        if (state.verificationHistory.isNotEmpty()) {
            val selectedIndex = state.verificationHistory.indexOfFirst { entry -> entry.runId == previousRunId }
                .takeIf { it >= 0 }
                ?: 0
            historyList.selectedIndex = selectedIndex
            historyList.ensureIndexIsVisible(selectedIndex)
            currentSelectionId = historyList.selectedValue?.runId
        } else {
            historyList.clearSelection()
            currentSelectionId = null
        }

        updateHeader()
        updateControls()
        updateDetail()
        applyPreferredSplitPaneLayout()
        revalidate()
        repaint()
    }

    internal fun snapshotForTest(): Map<String, String> {
        return mapOf(
            "headerVisible" to showHeader.toString(),
            "headerTitle" to headerTitleLabel.text.orEmpty(),
            "headerSummary" to headerSummaryLabel.text.orEmpty(),
            "headerRefreshed" to headerRefreshedLabel.text.orEmpty(),
            "statusChip" to statusChipLabel.text.orEmpty(),
            "workflowId" to currentState?.workflowId.orEmpty(),
            "historyCount" to historyListModel.size().toString(),
            "emptyText" to historyList.emptyText.text.orEmpty(),
            "dividerLocation" to contentSplitPane.dividerLocation.toString(),
            "resizeWeight" to contentSplitPane.resizeWeight.toString(),
            "runText" to runVerifyButton.text.orEmpty(),
            "runTooltip" to runVerifyButton.toolTipText.orEmpty(),
            "runHasIcon" to (runVerifyButton.icon != null).toString(),
            "runIconId" to SpecWorkflowIcons.debugId(runVerifyButton.icon),
            "runFocusable" to runVerifyButton.isFocusable.toString(),
            "runAccessibleName" to runVerifyButton.accessibleContext.accessibleName.orEmpty(),
            "runAccessibleDescription" to runVerifyButton.accessibleContext.accessibleDescription.orEmpty(),
            "openText" to openVerificationButton.text.orEmpty(),
            "openTooltip" to openVerificationButton.toolTipText.orEmpty(),
            "openHasIcon" to (openVerificationButton.icon != null).toString(),
            "openIconId" to SpecWorkflowIcons.debugId(openVerificationButton.icon),
            "openFocusable" to openVerificationButton.isFocusable.toString(),
            "openAccessibleName" to openVerificationButton.accessibleContext.accessibleName.orEmpty(),
            "openAccessibleDescription" to openVerificationButton.accessibleContext.accessibleDescription.orEmpty(),
            "compareText" to compareButton.text.orEmpty(),
            "compareTooltip" to compareButton.toolTipText.orEmpty(),
            "compareHasIcon" to (compareButton.icon != null).toString(),
            "compareIconId" to SpecWorkflowIcons.debugId(compareButton.icon),
            "compareFocusable" to compareButton.isFocusable.toString(),
            "compareAccessibleName" to compareButton.accessibleContext.accessibleName.orEmpty(),
            "compareAccessibleDescription" to compareButton.accessibleContext.accessibleDescription.orEmpty(),
            "pinText" to pinBaselineButton.text.orEmpty(),
            "pinTooltip" to pinBaselineButton.toolTipText.orEmpty(),
            "pinHasIcon" to (pinBaselineButton.icon != null).toString(),
            "pinIconId" to SpecWorkflowIcons.debugId(pinBaselineButton.icon),
            "pinFocusable" to pinBaselineButton.isFocusable.toString(),
            "pinAccessibleName" to pinBaselineButton.accessibleContext.accessibleName.orEmpty(),
            "pinAccessibleDescription" to pinBaselineButton.accessibleContext.accessibleDescription.orEmpty(),
        )
    }

    private fun applyActionButtonPresentation() {
        SpecUiStyle.applyIconActionPresentation(
            button = runVerifyButton,
            presentation = SpecIconActionPresentation(
                icon = SpecWorkflowIcons.Execute,
                tooltip = SpecCodingBundle.message("spec.toolwindow.verifyDelta.run"),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.verifyDelta.run"),
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.verifyDelta.run.disabled.noWorkflow"),
            ),
        )
        SpecUiStyle.applyIconActionPresentation(
            button = openVerificationButton,
            presentation = SpecIconActionPresentation(
                icon = SpecWorkflowIcons.OpenDocument,
                tooltip = SpecCodingBundle.message("spec.toolwindow.verifyDelta.open"),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.verifyDelta.open"),
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.verifyDelta.open.disabled.noWorkflow"),
            ),
        )
        SpecUiStyle.applyIconActionPresentation(
            button = compareButton,
            presentation = SpecIconActionPresentation(
                icon = SpecWorkflowIcons.History,
                tooltip = SpecCodingBundle.message("spec.toolwindow.verifyDelta.compare"),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.verifyDelta.compare"),
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.verifyDelta.compare.disabled.noWorkflow"),
            ),
        )
        SpecUiStyle.applyIconActionPresentation(
            button = pinBaselineButton,
            presentation = SpecIconActionPresentation(
                icon = SpecWorkflowIcons.Save,
                tooltip = SpecCodingBundle.message("spec.toolwindow.verifyDelta.pin"),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.verifyDelta.pin"),
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.verifyDelta.pin.disabled.noWorkflow"),
            ),
        )
    }

    private fun buildHeader(): JPanel {
        val titleRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(headerTitleLabel, BorderLayout.WEST)
            add(headerRefreshedLabel, BorderLayout.EAST)
        }
        val controlsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(statusChipLabel)
            add(runVerifyButton)
            add(openVerificationButton)
            add(baselineLabel)
            add(baselineComboBox)
            add(compareButton)
            add(pinBaselineButton)
        }
        if (!showHeader) {
            return JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 2, 4, 2)
                add(controlsRow, BorderLayout.CENTER)
            }
        }
        return JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 2, 4, 2)
            add(titleRow, BorderLayout.NORTH)
            add(headerSummaryLabel, BorderLayout.CENTER)
            add(controlsRow, BorderLayout.SOUTH)
        }
    }

    private fun buildContent(): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(contentSplitPane, BorderLayout.CENTER)
        }
    }

    private fun updateHeader() {
        val state = currentState
        val refreshedAtMillis = state?.refreshedAtMillis
        headerRefreshedLabel.text = refreshedAtMillis?.let { millis ->
            REFRESHED_AT_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        }.orEmpty()
        when {
            state == null -> {
                headerSummaryLabel.text = SpecCodingBundle.message("spec.toolwindow.verifyDelta.summary.none")
                headerSummaryLabel.toolTipText = null
                statusChipLabel.text = SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.unavailable")
                applyStatusChipStyle(null)
            }

            else -> {
                val latest = state.verificationHistory.firstOrNull()
                headerSummaryLabel.text = when {
                    latest != null -> SpecCodingBundle.message(
                        "spec.toolwindow.verifyDelta.summary",
                        state.verificationHistory.size,
                        conclusionText(latest.conclusion),
                        state.baselineChoices.size,
                    )
                    state.verifyEnabled -> SpecCodingBundle.message(
                        "spec.toolwindow.verifyDelta.summary.noRuns",
                        state.baselineChoices.size,
                    )
                    else -> SpecCodingBundle.message(
                        "spec.toolwindow.verifyDelta.summary.disabled",
                        state.baselineChoices.size,
                    )
                }
                headerSummaryLabel.toolTipText = latest?.summary?.takeIf { it.isNotBlank() }
                statusChipLabel.text = when {
                    latest != null -> conclusionText(latest.conclusion)
                    state.verifyEnabled -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.pending")
                    else -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.disabled")
                }
                applyStatusChipStyle(latest?.conclusion)
            }
        }
    }

    private fun updateBaselineChoices(state: SpecWorkflowVerifyDeltaState) {
        val previousChoiceId = (baselineComboBox.selectedItem as? SpecWorkflowDeltaBaselineChoice)?.stableId
        baselineComboBox.model = DefaultComboBoxModel(state.baselineChoices.toTypedArray())
        val selectedChoice = state.baselineChoices.firstOrNull { choice ->
            choice.stableId == previousChoiceId || choice.stableId == state.preferredBaselineChoiceId
        } ?: state.baselineChoices.firstOrNull()
        baselineComboBox.selectedItem = selectedChoice
        baselineComboBox.isEnabled = state.baselineChoices.isNotEmpty()
    }

    private fun updateControls() {
        val state = currentState
        val workflowId = state?.workflowId
        val selectedBaseline = baselineComboBox.selectedItem as? SpecWorkflowDeltaBaselineChoice
        val runDisabledReason = when {
            workflowId.isNullOrBlank() -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.run.disabled.noWorkflow")
            state?.verifyEnabled != true -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.run.disabled.verifyOff")
            else -> null
        }
        val openDisabledReason = when {
            workflowId.isNullOrBlank() -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.open.disabled.noWorkflow")
            state?.verificationDocumentAvailable != true -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.open.disabled.noDocument")
            else -> null
        }
        val compareDisabledReason = when {
            workflowId.isNullOrBlank() -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.compare.disabled.noWorkflow")
            selectedBaseline == null -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.compare.disabled.noBaseline")
            else -> null
        }
        val pinDisabledReason = when {
            workflowId.isNullOrBlank() -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.pin.disabled.noWorkflow")
            state?.canPinBaseline != true -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.pin.unavailable")
            else -> null
        }
        SpecUiStyle.setIconActionEnabled(runVerifyButton, enabled = runDisabledReason == null, disabledReason = runDisabledReason)
        SpecUiStyle.setIconActionEnabled(openVerificationButton, enabled = openDisabledReason == null, disabledReason = openDisabledReason)
        SpecUiStyle.setIconActionEnabled(compareButton, enabled = compareDisabledReason == null, disabledReason = compareDisabledReason)
        SpecUiStyle.setIconActionEnabled(pinBaselineButton, enabled = pinDisabledReason == null, disabledReason = pinDisabledReason)
    }

    private fun updateDetail() {
        val selectedRun = historyList.selectedValue
        val selectedBaseline = baselineComboBox.selectedItem as? SpecWorkflowDeltaBaselineChoice
        detailArea.text = when {
            selectedRun != null -> buildRunDetail(selectedRun)
            currentState == null -> SpecCodingBundle.message(emptyMessageKey)
            currentState?.verifyEnabled == true -> buildNoRunDetail(selectedBaseline)
            else -> buildDisabledDetail(selectedBaseline)
        }
        detailArea.caretPosition = 0
        detailArea.revalidate()
    }

    private fun buildRunDetail(entry: VerifyRunHistoryEntry): String {
        return buildString {
            appendLine(SpecCodingBundle.message("spec.toolwindow.verifyDelta.detail.runId", entry.runId))
            appendLine(SpecCodingBundle.message("spec.toolwindow.verifyDelta.detail.planId", entry.planId))
            appendLine(SpecCodingBundle.message("spec.toolwindow.verifyDelta.detail.executedAt", entry.executedAt))
            appendLine(
                SpecCodingBundle.message(
                    "spec.toolwindow.verifyDelta.detail.stage",
                    SpecWorkflowOverviewPresenter.stageLabel(entry.currentStage),
                ),
            )
            appendLine(
                SpecCodingBundle.message(
                    "spec.toolwindow.verifyDelta.detail.conclusion",
                    conclusionText(entry.conclusion),
                ),
            )
            appendLine(
                SpecCodingBundle.message(
                    "spec.toolwindow.verifyDelta.detail.scope",
                    formatList(entry.scopeTaskIds),
                ),
            )
            appendLine(
                SpecCodingBundle.message(
                    "spec.toolwindow.verifyDelta.detail.commands",
                    entry.commandCount,
                    formatList(entry.failedCommandIds),
                    formatList(entry.truncatedCommandIds),
                    formatList(entry.redactedCommandIds),
                ),
            )
            appendLine()
            appendLine(SpecCodingBundle.message("spec.toolwindow.verifyDelta.detail.summary"))
            appendLine(entry.summary.ifBlank { SpecCodingBundle.message("spec.toolwindow.verifyDelta.detail.summary.empty") })
        }
    }

    private fun buildNoRunDetail(selectedBaseline: SpecWorkflowDeltaBaselineChoice?): String {
        return buildString {
            appendLine(SpecCodingBundle.message("spec.toolwindow.verifyDelta.detail.noRuns"))
            appendLine()
            appendLine(SpecCodingBundle.message("spec.toolwindow.verifyDelta.detail.baseline"))
            appendLine(selectedBaseline?.let(::baselineChoiceDetails) ?: SpecCodingBundle.message("spec.toolwindow.verifyDelta.baseline.none"))
        }
    }

    private fun buildDisabledDetail(selectedBaseline: SpecWorkflowDeltaBaselineChoice?): String {
        return buildString {
            appendLine(SpecCodingBundle.message("spec.toolwindow.verifyDelta.detail.disabled"))
            appendLine()
            appendLine(SpecCodingBundle.message("spec.toolwindow.verifyDelta.detail.baseline"))
            appendLine(selectedBaseline?.let(::baselineChoiceDetails) ?: SpecCodingBundle.message("spec.toolwindow.verifyDelta.baseline.none"))
        }
    }

    private fun handleRunVerify() {
        val workflowId = currentState?.workflowId ?: return
        onRunVerifyRequested(workflowId)
    }

    private fun handleOpenVerification() {
        val workflowId = currentState?.workflowId ?: return
        onOpenVerificationRequested(workflowId)
    }

    private fun handleCompareBaseline() {
        val workflowId = currentState?.workflowId ?: return
        val selectedChoice = baselineComboBox.selectedItem as? SpecWorkflowDeltaBaselineChoice ?: return
        onCompareBaselineRequested(workflowId, selectedChoice)
    }

    private fun handlePinBaseline() {
        val workflowId = currentState?.workflowId ?: return
        onPinBaselineRequested(workflowId)
    }

    private fun baselineChoiceLabel(choice: SpecWorkflowDeltaBaselineChoice?): String {
        return when (choice) {
            is SpecWorkflowReferenceBaselineChoice -> SpecCodingBundle.message(
                "spec.toolwindow.verifyDelta.baseline.workflow",
                choice.title,
                choice.workflowId,
            )

            is SpecWorkflowPinnedDeltaBaselineChoice -> {
                val label = choice.baseline.label ?: choice.baseline.baselineId
                SpecCodingBundle.message("spec.toolwindow.verifyDelta.baseline.pinned", label)
            }

            null -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.baseline.none")
        }
    }

    private fun baselineChoiceDetails(choice: SpecWorkflowDeltaBaselineChoice): String {
        return when (choice) {
            is SpecWorkflowReferenceBaselineChoice -> SpecCodingBundle.message(
                "spec.toolwindow.verifyDelta.baseline.detail.workflow",
                choice.title,
                choice.workflowId,
            )

            is SpecWorkflowPinnedDeltaBaselineChoice -> {
                val label = choice.baseline.label ?: choice.baseline.baselineId
                val createdAt = REFRESHED_AT_FORMATTER.format(
                    Instant.ofEpochMilli(choice.baseline.createdAt).atZone(ZoneId.systemDefault()),
                )
                SpecCodingBundle.message(
                    "spec.toolwindow.verifyDelta.baseline.detail.pinned",
                    label,
                    choice.baseline.snapshotId,
                    createdAt,
                )
            }
        }
    }

    private fun conclusionText(conclusion: VerificationConclusion): String {
        return when (conclusion) {
            VerificationConclusion.PASS -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.pass")
            VerificationConclusion.WARN -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.warn")
            VerificationConclusion.FAIL -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.fail")
        }
    }

    private fun applyStatusChipStyle(conclusion: VerificationConclusion?) {
        val (background, foreground, border) = when (conclusion) {
            VerificationConclusion.PASS -> Triple(PASS_BG, PASS_FG, PASS_BORDER)
            VerificationConclusion.WARN -> Triple(WARN_BG, WARN_FG, WARN_BORDER)
            VerificationConclusion.FAIL -> Triple(ERROR_BG, ERROR_FG, ERROR_BORDER)
            null -> Triple(NEUTRAL_BG, NEUTRAL_FG, NEUTRAL_BORDER)
        }
        statusChipLabel.background = background
        statusChipLabel.foreground = foreground
        statusChipLabel.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(border, JBUI.scale(10)),
            JBUI.Borders.empty(2, 8, 2, 8),
        )
    }

    private fun formatList(items: List<String>): String {
        return items.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: SpecCodingBundle.message("spec.toolwindow.verifyDelta.detail.none")
    }

    private class HistoryCellRenderer : javax.swing.ListCellRenderer<VerifyRunHistoryEntry> {
        private val panel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(8, 10)
            isOpaque = true
        }
        private val summaryLabel = JBLabel().apply {
            font = JBUI.Fonts.label().deriveFont(12f)
            foreground = TITLE_FG
        }
        private val metaLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont().deriveFont(10.5f)
            foreground = META_FG
        }
        private val chipLabel = JBLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            border = BorderFactory.createEmptyBorder()
            isOpaque = true
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD, 10.5f)
        }

        init {
            panel.add(
                JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                    isOpaque = false
                    add(summaryLabel, BorderLayout.NORTH)
                    add(metaLabel, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
            panel.add(chipLabel, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out VerifyRunHistoryEntry>,
            value: VerifyRunHistoryEntry?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            if (value == null) {
                summaryLabel.text = ""
                metaLabel.text = ""
                chipLabel.text = ""
                return panel
            }
            summaryLabel.text = value.summary.ifBlank { value.runId }
            metaLabel.text = "${value.runId} · ${value.executedAt}"
            chipLabel.text = value.conclusion.name
            val palette = when (value.conclusion) {
                VerificationConclusion.PASS -> ChipPalette(PASS_BG, PASS_FG, PASS_BORDER)
                VerificationConclusion.WARN -> ChipPalette(WARN_BG, WARN_FG, WARN_BORDER)
                VerificationConclusion.FAIL -> ChipPalette(ERROR_BG, ERROR_FG, ERROR_BORDER)
            }
            chipLabel.background = if (isSelected) palette.bg.darker() else palette.bg
            chipLabel.foreground = palette.fg
            chipLabel.border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(palette.border, JBUI.scale(10)),
                BorderFactory.createEmptyBorder(2, 6, 2, 6),
            )
            panel.background = if (isSelected) SELECTED_BG else ROW_BG
            return panel
        }
    }

    private data class ChipPalette(
        val bg: Color,
        val fg: Color,
        val border: Color,
    )

    companion object {
        private val EMPTY_HISTORY_HEIGHT = JBUI.scale(72)
        private val MIN_HISTORY_PANE_HEIGHT = JBUI.scale(56)
        private val MAX_HISTORY_PANE_HEIGHT = JBUI.scale(132)
        private val MIN_DETAIL_HEIGHT = JBUI.scale(72)
        private val EMPTY_STATE_DETAIL_RESERVED_HEIGHT = JBUI.scale(108)
        private const val MIN_DETAIL_LINE_COUNT = 4
        private const val DEFAULT_SPLIT_RESIZE_WEIGHT = 0.5
        private const val EMPTY_STATE_SPLIT_RESIZE_WEIGHT = 0.32
        private val HEADER_FG = JBColor(Color(35, 40, 47), Color(222, 226, 232))
        private val HEADER_SECONDARY_FG = JBColor(Color(86, 96, 110), Color(175, 182, 190))
        private val DETAIL_BG = JBColor(Color(253, 254, 255), Color(45, 50, 58))
        private val DETAIL_FG = JBColor(Color(53, 60, 70), Color(212, 218, 228))
        private val TITLE_FG = JBColor(Color(30, 36, 44), Color(225, 229, 235))
        private val META_FG = JBColor(Color(86, 96, 110), Color(175, 182, 190))
        private val ROW_BG = JBColor(Color(255, 255, 255), Color(47, 51, 56))
        private val SELECTED_BG = JBColor(Color(231, 241, 255), Color(59, 77, 92))
        private val REFRESHED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private val PASS_BG = JBColor(Color(233, 246, 237), Color(54, 85, 63))
        private val PASS_FG = JBColor(Color(39, 94, 57), Color(194, 232, 204))
        private val PASS_BORDER = JBColor(Color(154, 204, 170), Color(87, 134, 102))

        private val WARN_BG = JBColor(Color(255, 244, 229), Color(97, 77, 42))
        private val WARN_FG = JBColor(Color(150, 96, 0), Color(245, 212, 152))
        private val WARN_BORDER = JBColor(Color(232, 191, 111), Color(138, 112, 63))

        private val ERROR_BG = JBColor(Color(252, 235, 236), Color(97, 54, 58))
        private val ERROR_FG = JBColor(Color(166, 53, 60), Color(250, 196, 200))
        private val ERROR_BORDER = JBColor(Color(226, 140, 147), Color(145, 92, 98))

        private val NEUTRAL_BG = JBColor(Color(241, 244, 248), Color(72, 77, 85))
        private val NEUTRAL_FG = JBColor(Color(85, 94, 106), Color(192, 199, 208))
        private val NEUTRAL_BORDER = JBColor(Color(201, 208, 217), Color(98, 108, 121))
    }
}
