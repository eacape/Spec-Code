package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingConstants

internal class SpecWorkflowOverviewPanel(
    private val onStageSelected: (StageId) -> Unit = {},
    private val onWorkbenchActionRequested: (SpecWorkflowWorkbenchAction) -> Unit = {},
    onTemplateCloneRequested: (WorkflowTemplate) -> Unit = {},
) : JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))) {
    private val emptyLabel = JBLabel()
    private val contentPanel = JPanel()
    private val focusTitleLabel = JBLabel()
    private val focusMetaLabel = createBodyLabel(VALUE_SECONDARY_FG)
    private val focusSummaryLabel = createBodyLabel(FOCUS_SUMMARY_FG)
    private val focusDetailLabels = List(4) { createBodyLabel(VALUE_SECONDARY_FG) }
    private val focusDetailsPanel = JPanel()
    private val blockersTitleLabel = createSectionTitleLabel().apply {
        foreground = BLOCKER_TITLE_FG
    }
    private val blockerLabels = List(3) { createBodyLabel(BLOCKER_FG) }
    private val blockersPanel = JPanel()
    private val primaryActionButton = JButton().apply {
        isFocusable = false
        addActionListener { currentWorkbenchState?.primaryAction?.let(onWorkbenchActionRequested) }
        SpecUiStyle.styleIconActionButton(this, size = 24, arc = 12)
    }
    private val overflowActionsButton = JButton().apply {
        isFocusable = false
        addActionListener { showOverflowActionsMenu() }
        SpecUiStyle.styleIconActionButton(this, size = 24, arc = 12)
    }
    private val focusActionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(primaryActionButton)
        add(overflowActionsButton)
    }
    private val checklistTitleLabel = createSectionTitleLabel()
    private val checklistLabels = List(4) { createChecklistLabel() }
    private val stageFlowTitleLabel = createSectionTitleLabel()
    private val detailsTitleLabel = createSectionTitleLabel()
    private val workflowValueLabel = createValueLabel()
    private val statusValueLabel = createValueLabel()
    private val templateValueLabel = createValueLabel()
    private val templateLockSummaryValueLabel = createBodyLabel(VALUE_SECONDARY_FG)
    private val currentStageValueLabel = createValueLabel()
    private val progressValueLabel = createValueLabel()
    private val activeStagesValueLabel = createValueLabel()
    private val nextStageValueLabel = createValueLabel()
    private val refreshedValueLabel = createValueLabel()
    private val gateSummaryValueLabel = createBodyLabel(VALUE_SECONDARY_FG)
    private val gateChipLabel = JBLabel()
    private val gateStatusSummaryRow = JPanel()
    private val gateStatusSummaryContainer = JPanel(BorderLayout())
    private val templateTargetComboBox = ComboBox<WorkflowTemplate>().apply {
        isFocusable = false
        renderer = SimpleListCellRenderer.create<WorkflowTemplate> { label, value, _ ->
            label.text = value?.let(SpecWorkflowOverviewPresenter::templateLabel)
                ?: SpecCodingBundle.message("spec.toolwindow.overview.template.target.none")
        }
    }
    private val templateCloneButton = createIconActionButton {
        val targetTemplate = templateTargetComboBox.selectedItem as? WorkflowTemplate ?: return@createIconActionButton
        onTemplateCloneRequested(targetTemplate)
    }
    private val templateValuePanel = JPanel(BorderLayout(0, JBUI.scale(6)))
    private val stageStepperPanel = SpecWorkflowStageStepperPanel(onStageSelected = onStageSelected)
    private val workflowKeyLabel = createKeyLabel()
    private val statusKeyLabel = createKeyLabel()
    private val templateKeyLabel = createKeyLabel()
    private val currentStageKeyLabel = createKeyLabel()
    private val progressKeyLabel = createKeyLabel()
    private val activeStagesKeyLabel = createKeyLabel()
    private val nextStageKeyLabel = createKeyLabel()
    private val advanceGateKeyLabel = createKeyLabel()
    private val refreshedKeyLabel = createKeyLabel()
    private var currentState: SpecWorkflowOverviewState? = null
    private var currentWorkbenchState: SpecWorkflowStageWorkbenchState? = null
    private var emptyMessageKey: String = "spec.toolwindow.overview.empty"

    init {
        configureStaticUi()
        buildContent()
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = templateTargetComboBox,
            minWidth = JBUI.scale(96),
            maxWidth = JBUI.scale(240),
            height = templateTargetComboBox.preferredSize.height.takeIf { it > 0 } ?: JBUI.scale(28),
        )
        add(emptyLabel, BorderLayout.CENTER)
        add(contentPanel, BorderLayout.NORTH)
        refreshLocalizedTexts()
        showEmpty()
    }

    fun updateOverview(
        state: SpecWorkflowOverviewState,
        workbenchState: SpecWorkflowStageWorkbenchState? = null,
    ) {
        currentState = state
        currentWorkbenchState = workbenchState
        emptyMessageKey = "spec.toolwindow.overview.empty"
        renderCurrentState()
    }

    fun showEmpty() {
        currentState = null
        currentWorkbenchState = null
        emptyMessageKey = "spec.toolwindow.overview.empty"
        renderEmptyState()
    }

    fun showLoading() {
        currentState = null
        currentWorkbenchState = null
        emptyMessageKey = "spec.toolwindow.overview.loading"
        renderEmptyState()
    }

    fun refreshLocalizedTexts() {
        workflowKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.secondary.workflow")
        statusKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.secondary.status")
        templateKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.secondary.template")
        currentStageKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.currentStage")
        progressKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.secondary.progress")
        activeStagesKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.secondary.activeStages")
        nextStageKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.secondary.next")
        advanceGateKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.advanceGate")
        refreshedKeyLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.secondary.refreshed")
        checklistTitleLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.checklist.title")
        blockersTitleLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.blockers.title")
        stageFlowTitleLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.stageFlow.title")
        detailsTitleLabel.text = SpecCodingBundle.message("spec.toolwindow.overview.secondary.title")
        applyTemplateButtonPresentation()
        applyOverflowButtonPresentation()
        stageStepperPanel.refreshLocalizedTexts()
        if (currentState != null) renderCurrentState() else renderEmptyState()
    }

    internal fun snapshotForTest(): Map<String, String> {
        val stageStepperSnapshot = stageStepperPanel.snapshotForTest()
        val checklist = checklistLabels
            .mapNotNull { label -> label.text?.trim()?.takeIf { it.isNotEmpty() } }
            .joinToString(" | ")
        val blockers = blockerLabels
            .mapNotNull { label -> label.text?.trim()?.takeIf { it.isNotEmpty() } }
            .joinToString(" | ")
        val focusDetails = focusDetailLabels
            .mapNotNull { label -> label.text?.trim()?.takeIf { it.isNotEmpty() } }
            .joinToString(" | ")
        return mapOf(
            "workflow" to workflowValueLabel.text.orEmpty(),
            "status" to statusValueLabel.text.orEmpty(),
            "template" to templateValueLabel.text.orEmpty(),
            "templateLockSummary" to templateLockSummaryValueLabel.text.orEmpty(),
            "templateCloneEnabled" to templateCloneButton.isEnabled.toString(),
            "templateCloneText" to templateCloneButton.text.orEmpty(),
            "templateCloneTooltip" to templateCloneButton.toolTipText.orEmpty(),
            "templateCloneAccessibleName" to templateCloneButton.accessibleContext.accessibleName.orEmpty(),
            "templateCloneAccessibleDescription" to templateCloneButton.accessibleContext.accessibleDescription.orEmpty(),
            "templateCloneHasIcon" to (templateCloneButton.icon != null).toString(),
            "templateCloneRolloverEnabled" to templateCloneButton.isRolloverEnabled.toString(),
            "templateCloneFocusable" to templateCloneButton.isFocusable.toString(),
            "currentStage" to currentStageValueLabel.text.orEmpty(),
            "progress" to progressValueLabel.text.orEmpty(),
            "activeStages" to activeStagesValueLabel.text.orEmpty(),
            "nextStage" to nextStageValueLabel.text.orEmpty(),
            "gateStatus" to gateChipLabel.text.orEmpty(),
            "gateStatusOpaque" to gateChipLabel.isOpaque.toString(),
            "gateStatusInsets" to gateChipLabel.border.getBorderInsets(gateChipLabel).let { insets ->
                "${insets.top},${insets.left},${insets.bottom},${insets.right}"
            },
            "gateContainerInsets" to gateStatusSummaryContainer.border.getBorderInsets(gateStatusSummaryContainer).let { insets ->
                "${insets.top},${insets.left},${insets.bottom},${insets.right}"
            },
            "gateStatusFont" to gateChipLabel.font.let { font -> "${font.size2D}:${font.style}" },
            "gateSummaryFont" to gateSummaryValueLabel.font.let { font -> "${font.size2D}:${font.style}" },
            "gateStatusBeforeSummary" to (
                gateStatusSummaryRow.components.indexOf(gateChipLabel) <
                    gateStatusSummaryRow.components.indexOf(gateSummaryValueLabel)
                ).toString(),
            "gateSummary" to gateSummaryValueLabel.text.orEmpty(),
            "stageFlow" to stageStepperSnapshot.getValue("stages"),
            "focusedStage" to stageStepperSnapshot.getValue("focusedStage"),
            "refreshed" to refreshedValueLabel.text.orEmpty(),
            "focusTitle" to focusTitleLabel.text.orEmpty(),
            "focusMeta" to focusMetaLabel.text.orEmpty(),
            "focusSummary" to focusSummaryLabel.text.orEmpty(),
            "focusDetails" to focusDetails,
            "blockers" to blockers,
            "checklist" to checklist,
            "primaryActionVisible" to primaryActionButton.isVisible.toString(),
            "primaryActionEnabled" to primaryActionButton.isEnabled.toString(),
            "primaryActionText" to primaryActionButton.text.orEmpty(),
            "primaryActionIconId" to SpecWorkflowIcons.debugId(primaryActionButton.icon),
            "primaryActionHasIcon" to (primaryActionButton.icon != null).toString(),
            "primaryActionRolloverEnabled" to primaryActionButton.isRolloverEnabled.toString(),
            "primaryActionFocusable" to primaryActionButton.isFocusable.toString(),
            "primaryActionTooltip" to primaryActionButton.toolTipText.orEmpty(),
            "primaryActionAccessibleName" to primaryActionButton.accessibleContext.accessibleName.orEmpty(),
            "primaryActionAccessibleDescription" to primaryActionButton.accessibleContext.accessibleDescription.orEmpty(),
            "overflowEnabled" to overflowActionsButton.isEnabled.toString(),
            "overflowVisible" to overflowActionsButton.isVisible.toString(),
            "overflowIconId" to SpecWorkflowIcons.debugId(overflowActionsButton.icon),
            "overflowTooltip" to overflowActionsButton.toolTipText.orEmpty(),
            "overflowFocusable" to overflowActionsButton.isFocusable.toString(),
            "overflowAccessibleName" to overflowActionsButton.accessibleContext.accessibleName.orEmpty(),
            "overflowAccessibleDescription" to overflowActionsButton.accessibleContext.accessibleDescription.orEmpty(),
            "overflowActions" to currentWorkbenchState
                ?.overflowActions
                ?.joinToString(" | ") { action -> action.label }
                .orEmpty(),
            "empty" to emptyLabel.text.orEmpty(),
        )
    }

    internal fun clickPrimaryActionForTest() {
        primaryActionButton.doClick()
    }

    internal fun clickStageForTest(stageId: StageId) {
        stageStepperPanel.clickStageForTest(stageId)
    }

    private fun configureStaticUi() {
        isOpaque = false
        emptyLabel.horizontalAlignment = SwingConstants.LEFT
        emptyLabel.verticalAlignment = SwingConstants.TOP
        emptyLabel.foreground = EMPTY_TEXT_FG
        emptyLabel.font = JBUI.Fonts.smallFont()
        emptyLabel.border = JBUI.Borders.empty(4)
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.isOpaque = false
        contentPanel.border = JBUI.Borders.empty(2, 2, 2, 2)
        focusTitleLabel.font = JBUI.Fonts.label().deriveFont(Font.BOLD, 13.5f)
        focusTitleLabel.foreground = VALUE_TEXT_FG
        blockersPanel.layout = BoxLayout(blockersPanel, BoxLayout.Y_AXIS)
        blockersPanel.isOpaque = false
        focusDetailsPanel.layout = BoxLayout(focusDetailsPanel, BoxLayout.Y_AXIS)
        focusDetailsPanel.isOpaque = false
        focusDetailLabels.forEachIndexed { index, label ->
            if (index > 0) {
                focusDetailsPanel.add(Box.createVerticalStrut(JBUI.scale(3)))
            }
            focusDetailsPanel.add(label)
        }
        blockerLabels.forEachIndexed { index, label ->
            if (index > 0) {
                blockersPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
            }
            blockersPanel.add(label)
        }
        gateChipLabel.horizontalAlignment = SwingConstants.LEFT
        gateChipLabel.verticalAlignment = SwingConstants.TOP
        gateChipLabel.border = JBUI.Borders.empty()
        gateChipLabel.isOpaque = false
        gateChipLabel.alignmentY = Component.TOP_ALIGNMENT
        gateChipLabel.font = JBUI.Fonts.smallFont()
        gateStatusSummaryRow.layout = BoxLayout(gateStatusSummaryRow, BoxLayout.X_AXIS)
        gateStatusSummaryRow.isOpaque = false
        gateStatusSummaryRow.alignmentX = Component.LEFT_ALIGNMENT
        gateStatusSummaryRow.add(gateChipLabel)
        gateStatusSummaryRow.add(Box.createHorizontalStrut(JBUI.scale(6)))
        gateStatusSummaryRow.add(gateSummaryValueLabel.apply { alignmentY = Component.TOP_ALIGNMENT })
        gateStatusSummaryRow.add(Box.createHorizontalGlue())
        gateStatusSummaryContainer.isOpaque = false
        gateStatusSummaryContainer.border = JBUI.Borders.empty()
        gateStatusSummaryContainer.add(gateStatusSummaryRow, BorderLayout.CENTER)
        templateValuePanel.isOpaque = false
        templateValuePanel.add(
            JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(templateValueLabel)
                add(Box.createVerticalStrut(JBUI.scale(2)))
                add(templateLockSummaryValueLabel)
            },
            BorderLayout.NORTH,
        )
        templateValuePanel.add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(templateTargetComboBox)
                add(templateCloneButton)
            },
            BorderLayout.CENTER,
        )
    }

    private fun buildContent() {
        contentPanel.add(createCard(buildFocusCard()))
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        contentPanel.add(createCard(buildChecklistCard()))
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        contentPanel.add(createCard(buildStageFlowCard()))
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        contentPanel.add(createCard(buildDetailsCard()))
    }

    private fun buildFocusCard(): JPanel {
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(focusTitleLabel)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(focusMetaLabel)
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(headerPanel, BorderLayout.NORTH)
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(focusSummaryLabel)
                    add(Box.createVerticalStrut(JBUI.scale(6)))
                    add(focusDetailsPanel)
                    add(Box.createVerticalStrut(JBUI.scale(6)))
                    add(blockersTitleLabel)
                    add(Box.createVerticalStrut(JBUI.scale(4)))
                    add(blockersPanel)
                },
                BorderLayout.CENTER,
            )
            add(focusActionsPanel, BorderLayout.SOUTH)
        }
    }

    private fun buildChecklistCard(): JPanel {
        val checklistBody = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            checklistLabels.forEachIndexed { index, label ->
                if (index > 0) add(Box.createVerticalStrut(JBUI.scale(4)))
                add(label)
            }
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(checklistTitleLabel, BorderLayout.NORTH)
            add(checklistBody, BorderLayout.CENTER)
        }
    }

    private fun buildStageFlowCard(): JPanel {
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(stageFlowTitleLabel, BorderLayout.NORTH)
            add(stageStepperPanel, BorderLayout.CENTER)
        }
    }

    private fun buildDetailsCard(): JPanel {
        val grid = JPanel(GridBagLayout()).apply {
            isOpaque = false
        }
        var row = 0
        row = addDetailRow(grid, row, workflowKeyLabel, workflowValueLabel)
        row = addDetailRow(grid, row, statusKeyLabel, statusValueLabel)
        row = addDetailRow(grid, row, currentStageKeyLabel, currentStageValueLabel)
        row = addDetailRow(grid, row, progressKeyLabel, progressValueLabel)
        row = addDetailRow(grid, row, templateKeyLabel, templateValuePanel)
        row = addDetailRow(grid, row, activeStagesKeyLabel, activeStagesValueLabel)
        row = addDetailRow(grid, row, nextStageKeyLabel, nextStageValueLabel)
        row = addDetailRow(grid, row, advanceGateKeyLabel, gateStatusSummaryContainer)
        addDetailRow(grid, row, refreshedKeyLabel, refreshedValueLabel, fillVertical = true)
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(detailsTitleLabel, BorderLayout.NORTH)
            add(grid, BorderLayout.CENTER)
        }
    }
    private fun renderCurrentState() {
        val state = currentState ?: return
        val workbenchState = currentWorkbenchState ?: fallbackWorkbenchState(state)
        currentWorkbenchState = workbenchState
        emptyLabel.isVisible = false
        contentPanel.isVisible = true

        val guidance = SpecWorkflowStageGuidanceBuilder.build(state, workbenchState)
        focusTitleLabel.text = guidance.headline
        focusMetaLabel.text = buildFocusMetaText(state, workbenchState)
        focusSummaryLabel.text = guidance.summary
        updateFocusDetails(workbenchState.focusDetails)
        updateBlockers(workbenchState.blockers)
        updateChecklist(guidance.checklist)
        updatePrimaryAction(workbenchState.primaryAction)
        updateOverflowActions(workbenchState.overflowActions)
        updateFocusActionsVisibility()

        workflowValueLabel.text = state.title.takeIf { it.isNotBlank() }?.let { title ->
            if (title == state.workflowId) state.workflowId else "$title | ${state.workflowId}"
        } ?: state.workflowId
        statusValueLabel.text = workflowStatusText(state.status)
        templateValueLabel.text = SpecCodingBundle.message(
            "spec.toolwindow.overview.template.current",
            SpecWorkflowOverviewPresenter.templateLabel(state.template),
        )
        templateLockSummaryValueLabel.text = state.templateLockedSummary
        updateTemplateTargets(state.templateCloneTargets)
        SpecUiStyle.setIconActionEnabled(
            button = templateCloneButton,
            enabled = state.templateCloneTargets.isNotEmpty(),
            disabledReason = if (state.templateCloneTargets.isEmpty()) {
                SpecCodingBundle.message("spec.toolwindow.overview.template.clone.unavailable")
            } else {
                null
            },
        )
        currentStageValueLabel.text = SpecWorkflowOverviewPresenter.stageLabel(state.currentStage)
        progressValueLabel.text = buildProgressText(workbenchState)
        activeStagesValueLabel.text = state.activeStages.joinToString(" -> ") { stage ->
            SpecWorkflowOverviewPresenter.stageLabel(stage)
        }
        nextStageValueLabel.text = state.nextStage?.let(SpecWorkflowOverviewPresenter::stageLabel)
            ?: SpecCodingBundle.message("spec.toolwindow.overview.nextStage.none")
        gateChipLabel.text = gateStatusText(state.gateStatus)
        applyGateChipStyle(state.gateStatus)
        gateSummaryValueLabel.text = state.gateSummary
            ?: SpecCodingBundle.message("spec.toolwindow.overview.advanceGate.unavailable")
        stageStepperPanel.updateState(state.stageStepper, focusedStage = workbenchState.focusedStage)
        refreshedValueLabel.text = REFRESHED_AT_FORMATTER.format(
            Instant.ofEpochMilli(state.refreshedAtMillis).atZone(ZoneId.systemDefault()),
        )
        revalidate()
        repaint()
    }

    private fun renderEmptyState() {
        contentPanel.isVisible = false
        emptyLabel.isVisible = true
        emptyLabel.text = SpecCodingBundle.message(emptyMessageKey)
        focusTitleLabel.text = ""
        focusMetaLabel.text = ""
        focusSummaryLabel.text = ""
        updateFocusDetails(emptyList())
        updateBlockers(emptyList())
        updateChecklist(emptyList())
        updatePrimaryAction(null)
        updateOverflowActions(emptyList())
        updateFocusActionsVisibility()
        workflowValueLabel.text = ""
        statusValueLabel.text = ""
        templateValueLabel.text = ""
        templateLockSummaryValueLabel.text = ""
        currentStageValueLabel.text = ""
        progressValueLabel.text = ""
        activeStagesValueLabel.text = ""
        nextStageValueLabel.text = ""
        gateChipLabel.text = ""
        applyGateChipStyle(null)
        gateSummaryValueLabel.text = ""
        refreshedValueLabel.text = ""
        updateTemplateTargets(emptyList())
        SpecUiStyle.setIconActionEnabled(
            button = templateCloneButton,
            enabled = false,
            disabledReason = SpecCodingBundle.message("spec.toolwindow.overview.template.clone.selectWorkflow"),
        )
        stageStepperPanel.clear()
        revalidate()
        repaint()
    }

    private fun showOverflowActionsMenu() {
        val actions = currentWorkbenchState?.overflowActions.orEmpty()
        if (actions.isEmpty()) return
        val menu = JPopupMenu()
        actions.forEach { action ->
            menu.add(
                javax.swing.JMenuItem(action.label).apply {
                    icon = SpecWorkflowIcons.workbenchAction(action.kind)
                    isEnabled = action.enabled
                    toolTipText = if (action.enabled) action.label else action.disabledReason ?: action.label
                    accessibleContext.accessibleName = action.label
                    accessibleContext.accessibleDescription = toolTipText
                    addActionListener { onWorkbenchActionRequested(action) }
                },
            )
        }
        menu.show(overflowActionsButton, 0, overflowActionsButton.height)
    }
    private fun fallbackWorkbenchState(state: SpecWorkflowOverviewState): SpecWorkflowStageWorkbenchState {
        val activeStages = state.activeStages.ifEmpty { state.stageStepper.stages.map { it.stageId } }
        val currentStep = state.stageStepper.stages.firstOrNull { it.stageId == state.currentStage }
        return SpecWorkflowStageWorkbenchState(
            currentStage = state.currentStage,
            focusedStage = state.currentStage,
            progress = SpecWorkflowStageProgressView(
                stepIndex = activeStages.indexOf(state.currentStage).coerceAtLeast(0) + 1,
                totalSteps = activeStages.size.coerceAtLeast(1),
                stageStatus = currentStep?.progress ?: StageProgress.IN_PROGRESS,
                completedCheckCount = 0,
                totalCheckCount = 0,
                completionChecks = emptyList(),
            ),
            primaryAction = null,
            overflowActions = emptyList(),
            blockers = emptyList(),
            focusDetails = emptyList(),
            artifactBinding = SpecWorkflowStageArtifactBinding(
                stageId = state.currentStage,
                title = SpecWorkflowOverviewPresenter.stageLabel(state.currentStage),
                fileName = state.currentStage.artifactFileName,
                documentPhase = null,
                mode = SpecWorkflowWorkbenchDocumentMode.READ_ONLY,
                fallbackEditable = false,
            ),
            implementationFocus = null,
            visibleSections = SpecWorkflowWorkspaceLayout.visibleSections(
                currentStage = state.currentStage,
                status = state.status,
            ),
        )
    }

    private fun buildFocusMetaText(
        state: SpecWorkflowOverviewState,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ): String {
        return if (workbenchState.focusedStage == state.currentStage) {
            SpecCodingBundle.message(
                "spec.toolwindow.overview.focus.meta.current",
                workbenchState.progress.completedCheckCount,
                workbenchState.progress.totalCheckCount,
                workbenchState.progress.stepIndex,
                workbenchState.progress.totalSteps,
                SpecWorkflowOverviewPresenter.progressLabel(workbenchState.progress.stageStatus),
            )
        } else {
            SpecCodingBundle.message(
                "spec.toolwindow.overview.focus.meta.focused",
                SpecWorkflowOverviewPresenter.stageLabel(state.currentStage),
                workbenchState.progress.completedCheckCount,
                workbenchState.progress.totalCheckCount,
                workbenchState.progress.stepIndex,
                workbenchState.progress.totalSteps,
                SpecWorkflowOverviewPresenter.progressLabel(workbenchState.progress.stageStatus),
            )
        }
    }

    private fun buildProgressText(workbenchState: SpecWorkflowStageWorkbenchState): String {
        return SpecCodingBundle.message(
            "spec.toolwindow.overview.progress.value",
            workbenchState.progress.completedCheckCount,
            workbenchState.progress.totalCheckCount,
            workbenchState.progress.stepIndex,
            workbenchState.progress.totalSteps,
            SpecWorkflowOverviewPresenter.progressLabel(workbenchState.progress.stageStatus),
        )
    }
    private fun updateTemplateTargets(templates: List<WorkflowTemplate>) {
        val previousSelection = templateTargetComboBox.selectedItem as? WorkflowTemplate
        templateTargetComboBox.removeAllItems()
        templates.forEach(templateTargetComboBox::addItem)
        if (templates.isEmpty()) {
            templateTargetComboBox.isEnabled = false
            return
        }
        templateTargetComboBox.selectedItem = previousSelection?.takeIf { templates.contains(it) } ?: templates.first()
        templateTargetComboBox.isEnabled = true
    }
    private fun updateChecklist(lines: List<String>) {
        checklistLabels.forEachIndexed { index, label ->
            val value = lines.getOrNull(index).orEmpty()
            label.text = value
            label.isVisible = value.isNotBlank()
        }
    }

    private fun updateBlockers(blockers: List<String>) {
        val visible = blockers.isNotEmpty()
        blockersTitleLabel.isVisible = visible
        blockersPanel.isVisible = visible
        blockerLabels.forEachIndexed { index, label ->
            val value = blockers.getOrNull(index).orEmpty()
            label.text = value
            label.isVisible = value.isNotBlank()
        }
    }

    private fun updateFocusDetails(details: List<String>) {
        val visible = details.isNotEmpty()
        focusDetailsPanel.isVisible = visible
        focusDetailLabels.forEachIndexed { index, label ->
            val value = details.getOrNull(index).orEmpty()
            label.text = value
            label.isVisible = value.isNotBlank()
        }
    }

    private fun updatePrimaryAction(action: SpecWorkflowWorkbenchAction?) {
        if (action == null) {
            primaryActionButton.isVisible = false
            primaryActionButton.isEnabled = false
            primaryActionButton.text = ""
            primaryActionButton.icon = null
            primaryActionButton.disabledIcon = null
            primaryActionButton.toolTipText = null
            primaryActionButton.accessibleContext?.accessibleName = null
            primaryActionButton.accessibleContext?.accessibleDescription = null
            return
        }
        SpecUiStyle.applyIconActionPresentation(
            button = primaryActionButton,
            presentation = SpecIconActionPresentation(
                icon = SpecWorkflowIcons.workbenchAction(action.kind),
                text = when (action.kind) {
                    SpecWorkflowWorkbenchActionKind.ADVANCE ->
                        SpecCodingBundle.message("spec.toolwindow.overview.nextStage")

                    else -> ""
                },
                tooltip = action.label,
                accessibleName = action.label,
                enabled = action.enabled,
                disabledReason = action.disabledReason,
            ),
        )
        primaryActionButton.isVisible = true
    }

    private fun updateOverflowActions(actions: List<SpecWorkflowWorkbenchAction>) {
        overflowActionsButton.isVisible = actions.isNotEmpty()
        val disabledReason = when {
            actions.isEmpty() -> SpecCodingBundle.message("spec.toolwindow.overview.more.unavailable")
            actions.any { it.enabled } -> null
            else -> actions.firstNotNullOfOrNull { action -> action.disabledReason }
                ?: SpecCodingBundle.message("spec.toolwindow.overview.more.unavailable")
        }
        SpecUiStyle.applyIconActionPresentation(
            button = overflowActionsButton,
            presentation = SpecIconActionPresentation(
                icon = SpecWorkflowIcons.IntentionBulb,
                tooltip = SpecCodingBundle.message("spec.toolwindow.overview.more.tooltip"),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.overview.more.tooltip"),
                enabled = actions.any { it.enabled },
                disabledReason = disabledReason,
            ),
        )
    }

    private fun updateFocusActionsVisibility() {
        focusActionsPanel.isVisible = primaryActionButton.isVisible || overflowActionsButton.isVisible
    }

    private fun addDetailRow(
        panel: JPanel,
        row: Int,
        keyLabel: JLabel,
        valueComponent: Component,
        fillVertical: Boolean = false,
    ): Int {
        panel.add(
            keyLabel,
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(0, 0, JBUI.scale(8), JBUI.scale(12))
            },
        )
        panel.add(
            valueComponent,
            GridBagConstraints().apply {
                gridx = 1
                gridy = row
                weightx = 1.0
                weighty = if (fillVertical) 1.0 else 0.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(0, 0, JBUI.scale(8), 0)
            },
        )
        return row + 1
    }
    private fun applyTemplateButtonPresentation() {
        SpecUiStyle.applyIconActionPresentation(
            button = templateCloneButton,
            presentation = SpecIconActionPresentation(
                icon = SpecWorkflowIcons.Clone,
                tooltip = SpecCodingBundle.message("spec.toolwindow.overview.template.clone"),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.overview.template.clone"),
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.overview.template.clone.selectWorkflow"),
            ),
        )
    }

    private fun applyOverflowButtonPresentation() {
        SpecUiStyle.applyIconActionPresentation(
            button = overflowActionsButton,
            presentation = SpecIconActionPresentation(
                icon = SpecWorkflowIcons.IntentionBulb,
                tooltip = SpecCodingBundle.message("spec.toolwindow.overview.more.tooltip"),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.overview.more.tooltip"),
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.overview.more.unavailable"),
            ),
        )
    }

    private fun workflowStatusText(status: WorkflowStatus): String {
        return when (status) {
            WorkflowStatus.IN_PROGRESS -> SpecCodingBundle.message("spec.workflow.status.inProgress")
            WorkflowStatus.PAUSED -> SpecCodingBundle.message("spec.workflow.status.paused")
            WorkflowStatus.COMPLETED -> SpecCodingBundle.message("spec.workflow.status.completed")
            WorkflowStatus.FAILED -> SpecCodingBundle.message("spec.workflow.status.failed")
        }
    }

    private fun gateStatusText(status: GateStatus?): String {
        return when (status) {
            GateStatus.PASS -> SpecCodingBundle.message("spec.toolwindow.overview.advanceGate.pass")
            GateStatus.WARNING -> SpecCodingBundle.message("spec.toolwindow.overview.advanceGate.warning")
            GateStatus.ERROR -> SpecCodingBundle.message("spec.toolwindow.overview.advanceGate.error")
            null -> SpecCodingBundle.message("spec.toolwindow.overview.advanceGate.unavailable.short")
        }
    }

    private fun applyGateChipStyle(status: GateStatus?) {
        val foreground = when (status) {
            GateStatus.PASS -> PASS_FG
            GateStatus.WARNING -> WARN_FG
            GateStatus.ERROR -> ERROR_FG
            null -> NEUTRAL_FG
        }
        gateChipLabel.isOpaque = false
        gateChipLabel.foreground = foreground
        gateChipLabel.border = JBUI.Borders.empty()
    }
    private fun createCard(content: Component): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = CARD_BG
            border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(CARD_BORDER, JBUI.scale(16)),
                JBUI.Borders.empty(10, 12, 10, 12),
            )
            add(content, BorderLayout.CENTER)
        }
    }

    private fun createSectionTitleLabel(): JBLabel {
        return JBLabel().apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD, 11.5f)
            foreground = SECTION_TITLE_FG
        }
    }

    private fun createKeyLabel(): JBLabel {
        return JBLabel().apply {
            font = JBUI.Fonts.smallFont().deriveFont(10.5f)
            foreground = KEY_TEXT_FG
            verticalAlignment = SwingConstants.TOP
        }
    }

    private fun createValueLabel(): JBLabel {
        return JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = VALUE_TEXT_FG
            verticalAlignment = SwingConstants.TOP
        }
    }

    private fun createBodyLabel(foreground: Color): JBLabel {
        return JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            this.foreground = foreground
            verticalAlignment = SwingConstants.TOP
        }
    }

    private fun createChecklistLabel(): JBLabel {
        return createBodyLabel(CHECKLIST_FG).apply {
            border = JBUI.Borders.emptyLeft(2)
        }
    }

    private fun createIconActionButton(onClick: () -> Unit): JButton {
        return JButton().apply {
            isFocusable = false
            addActionListener { onClick() }
            SpecUiStyle.styleIconActionButton(this, size = 24, arc = 12)
        }
    }

    companion object {
        private val REFRESHED_AT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val KEY_TEXT_FG = JBColor(Color(110, 118, 131), Color(140, 149, 163))
        private val VALUE_TEXT_FG = JBColor(Color(35, 39, 47), Color(216, 222, 233))
        private val VALUE_SECONDARY_FG = JBColor(Color(102, 112, 127), Color(165, 175, 190))
        private val EMPTY_TEXT_FG = JBColor(Color(120, 127, 138), Color(132, 141, 153))
        private val SECTION_TITLE_FG = JBColor(Color(62, 80, 118), Color(204, 215, 231))
        private val FOCUS_SUMMARY_FG = JBColor(Color(84, 96, 116), Color(177, 186, 199))
        private val CHECKLIST_FG = JBColor(Color(74, 86, 105), Color(189, 199, 214))
        private val BLOCKER_TITLE_FG = JBColor(Color(148, 67, 67), Color(244, 190, 190))
        private val BLOCKER_FG = JBColor(Color(161, 73, 73), Color(244, 203, 203))
        private val CARD_BG = JBColor(Color(250, 252, 255), Color(55, 61, 71))
        private val CARD_BORDER = JBColor(Color(209, 220, 237), Color(85, 96, 111))
        private val PASS_FG = JBColor(Color(39, 94, 57), Color(194, 232, 204))
        private val WARN_FG = JBColor(Color(150, 96, 0), Color(245, 212, 152))
        private val ERROR_FG = JBColor(Color(166, 53, 60), Color(250, 196, 200))
        private val NEUTRAL_FG = JBColor(Color(85, 94, 106), Color(192, 199, 208))
    }
}
