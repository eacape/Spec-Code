package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.CodeContextPack
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMarkdownSanitizer
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.ui.chat.MarkdownRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextPane
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.BadLocationException
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer

class SpecDetailPanel(
    private val onGenerate: (String) -> Unit,
    private val canGenerateWithEmptyInput: () -> Boolean = { false },
    private val onAddWorkflowSourcesRequested: () -> Unit,
    private val onRemoveWorkflowSourceRequested: (String) -> Unit,
    private val onRestoreWorkflowSourcesRequested: () -> Unit,
    private val onClarificationConfirm: (String, String) -> Unit,
    private val onClarificationRegenerate: (String, String) -> Unit,
    private val onClarificationSkip: (String) -> Unit,
    private val onClarificationCancel: () -> Unit,
    private val onNextPhase: () -> Unit,
    private val onGoBack: () -> Unit,
    private val onComplete: () -> Unit,
    private val onPauseResume: () -> Unit,
    private val onOpenInEditor: (SpecPhase) -> Unit,
    private val onOpenArtifactInEditor: (String) -> Unit,
    private val onShowHistoryDiff: (SpecPhase) -> Unit,
    private val onSaveDocument: (SpecPhase, String, (Result<SpecWorkflow>) -> Unit) -> Unit,
    private val onClarificationDraftAutosave: (String, String, String, List<String>) -> Unit,
) : JPanel(BorderLayout()) {

    private val treeRoot = DefaultMutableTreeNode(SpecCodingBundle.message("spec.detail.documents"))
    private val treeModel = DefaultTreeModel(treeRoot)
    private val documentTree = JTree(treeModel)
    private val phaseStepperRail = PhaseStepperRail()
    private val documentTabsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
    private val documentTabButtons = linkedMapOf<SpecPhase, JButton>()

    private val previewPane = JTextPane()
    private val clarificationQuestionsPane = JTextPane()
    private val clarificationPreviewPane = JTextPane()
    private val processTimelinePane = JTextPane()
    private val previewCardLayout = CardLayout()
    private val previewCardPanel = JPanel(previewCardLayout)
    private val processTimelineLabel = JBLabel(SpecCodingBundle.message("spec.detail.process.title"))
    private val clarificationQuestionsLabel = JBLabel(SpecCodingBundle.message("spec.detail.clarify.questions.title"))
    private val clarificationChecklistHintLabel = JBLabel(SpecCodingBundle.message("spec.detail.clarify.checklist.hint"))
    private val clarificationPreviewLabel = JBLabel(SpecCodingBundle.message("spec.detail.clarify.preview.title"))
    private val clarificationQuestionsCardLayout = CardLayout()
    private val clarificationQuestionsCardPanel = JPanel(clarificationQuestionsCardLayout)
    private val clarificationChecklistPanel = JPanel()
    private val editorArea = JBTextArea(14, 40)
    private val validationLabel = JBLabel("")
    private val composerSourcePanel = SpecComposerSourcePanel(
        onAddRequested = { onAddWorkflowSourcesRequested() },
        onRemoveRequested = { sourceId -> onRemoveWorkflowSourceRequested(sourceId) },
        onRestoreRequested = { onRestoreWorkflowSourcesRequested() },
    )
    private val composerCodeContextPanel = SpecComposerCodeContextPanel()
    private lateinit var validationBannerPanel: JPanel
    private val inputArea = JBTextArea(3, 40)
    private lateinit var clarificationSplitPane: JSplitPane
    private lateinit var composerSection: SpecDetailCollapsibleSectionView
    private lateinit var clarificationQuestionsSection: SpecDetailCollapsibleSectionView
    private lateinit var clarificationPreviewSection: SpecDetailCollapsibleSectionView
    private lateinit var processTimelineSection: SpecDetailCollapsibleSectionView
    private lateinit var inputSectionContainer: JPanel
    private lateinit var actionButtonPanel: JPanel
    private lateinit var bottomPanelContainer: JPanel
    private lateinit var mainSplitPane: JSplitPane
    private var isPhaseStepperEnabled: Boolean = true

    private var currentWorkflow: SpecWorkflow? = null
    private var selectedPhase: SpecPhase? = null
    private var generatingPercent: Int = 0
    private var generatingFrameIndex: Int = 0
    private var isGeneratingActive: Boolean = false
    private var isClarificationGenerating: Boolean = false
    private var generationAnimationTimer: Timer? = null

    private var isEditing: Boolean = false
    private var explicitRevisionPhase: SpecPhase? = null
    private var editingPhase: SpecPhase? = null
    private var preferredWorkbenchPhase: SpecPhase? = null
    private var workbenchArtifactBinding: SpecWorkflowStageArtifactBinding? = null
    private var composerSourceState = ComposerSourceState()
    private var composerCodeContextState = ComposerCodeContextState()
    private var activePreviewCard: SpecDetailPreviewSurfaceCard = SpecDetailPreviewSurfaceCard.PREVIEW
    private var clarificationState: SpecDetailClarificationFormState? = null
    private var activeChecklistDetailIndex: Int? = null
    private var isClarificationChecklistReadOnly: Boolean = false
    private var isBottomCollapsedForChecklist: Boolean = false
    private var isProcessTimelineExpanded: Boolean = true
    private var isClarificationQuestionsExpanded: Boolean = true
    private var isClarificationPreviewExpanded: Boolean = true
    private var isClarificationPreviewContentVisible: Boolean = true
    private var isComposerExpanded: Boolean = true
    private var composerContextKey: String? = null
    private var composerManualOverride: Boolean? = null
    private var hasAppliedInitialBottomHeight: Boolean = false
    private var processTimelineModel = SpecDetailProcessTimelineRenderModel()
    private val composerTitleLabel = JBLabel()
    private val validationBannerPresenter = SpecDetailValidationBannerPresenter(
        label = validationLabel,
        bannerPanel = { if (::validationBannerPanel.isInitialized) validationBannerPanel else null },
        infoForeground = TREE_TEXT,
        generatingForeground = GENERATING_FG,
    )
    private val previewPanePresenter = SpecDetailPreviewPanePresenter(
        pane = previewPane,
        isEditing = { isEditing },
        hasClarificationState = { clarificationState != null },
        currentWorkflow = { currentWorkflow },
        resolveRevisionLockedPhase = ::currentReadOnlyRevisionLockedPhase,
        onSaveDocument = onSaveDocument,
        onWorkflowUpdated = { updated ->
            currentWorkflow = updated
            updateWorkflow(updated)
        },
        onRefreshButtonStates = ::updateButtonStates,
    )
    private val previewContentPresenter = SpecDetailPreviewContentPresenter(
        previewPanePresenter = previewPanePresenter,
        validationBannerPresenter = validationBannerPresenter,
        onKeepGeneratingLabel = ::updateGeneratingLabel,
    )
    private val actionBarButtons = SpecDetailActionBarButtons.create()
    private val actionBarPresenter = SpecDetailActionBarPresenter(
        buttons = actionBarButtons,
    )
    private val actionBarChromePresenter = SpecDetailActionBarChromePresenter(
        buttons = actionBarButtons,
    )
    private val actionBarCommandAdapter = SpecDetailActionBarCommandAdapter(
        buttons = actionBarButtons,
        context = SpecDetailActionBarCommandContext(
            inputText = { inputArea.text },
            currentWorkflow = { currentWorkflow },
            selectedPhase = { selectedPhase },
            workbenchArtifactFileName = { workbenchArtifactBinding?.fileName },
            canGenerateWithEmptyInput = canGenerateWithEmptyInput,
            resolveDetailViewState = ::resolveDetailViewState,
            clarificationState = { clarificationState },
            clarificationText = ::clarificationText,
        ),
        callbacks = SpecDetailActionBarCommandCallbacks(
            onGenerate = onGenerate,
            onInputRequired = ::showInputRequiredHint,
            onClearInput = ::clearInput,
            onNextPhase = onNextPhase,
            onGoBack = onGoBack,
            onComplete = onComplete,
            onPauseResume = onPauseResume,
            onOpenInEditor = onOpenInEditor,
            onOpenArtifactInEditor = onOpenArtifactInEditor,
            onShowHistoryDiff = onShowHistoryDiff,
            onSetExplicitRevisionPhase = { phase -> explicitRevisionPhase = phase },
            onStartEditing = ::startEditing,
            onSaveEditing = ::saveEditing,
            onCancelEditing = { stopEditing(keepText = false) },
            onApplyClarificationActionPlan = ::applyClarificationActionPlan,
        ),
    )
    private val actionBarLayoutBuilder = SpecDetailActionBarLayoutBuilder(
        buttons = actionBarButtons,
        presenter = actionBarPresenter,
        chromePresenter = actionBarChromePresenter,
        commandAdapter = actionBarCommandAdapter,
        footerDivider = COMPOSER_FOOTER_DIVIDER,
        initializePresentation = ::refreshActionButtonPresentation,
    )

    init {
        setupUI()
    }

    private data class ComposerSourceState(
        val workflowId: String? = null,
        val assets: List<WorkflowSourceAsset> = emptyList(),
        val selectedSourceIds: Set<String> = emptySet(),
        val editable: Boolean = false,
    )

    private data class ComposerCodeContextState(
        val workflowId: String? = null,
        val codeContextPack: CodeContextPack? = null,
    )

    enum class ProcessTimelineState {
        INFO,
        ACTIVE,
        DONE,
        FAILED,
    }

    data class ProcessTimelineEntry(
        val text: String,
        val state: ProcessTimelineState = ProcessTimelineState.INFO,
    )

    private fun setupUI() {
        border = JBUI.Borders.empty(2)

        val previewPanel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = true
            background = PREVIEW_COLUMN_BG
        }
        previewPane.isEditable = false
        previewPane.isOpaque = false
        previewPane.border = JBUI.Borders.empty(2, 2)
        SpecDetailPreviewChecklistInteractionBinder.bind(
            pane = previewPane,
            onToggleRequested = previewPanePresenter::handleToggleRequested,
            onCursorRefreshRequested = previewPanePresenter::refreshCursor,
        )

        previewCardPanel.isOpaque = false
        previewCardPanel.add(
            createSectionContainer(
                JBScrollPane(previewPane).apply {
                    border = JBUI.Borders.empty()
                    SpecUiStyle.applyFastVerticalScrolling(this)
                },
                backgroundColor = PREVIEW_SECTION_BG,
                borderColor = PREVIEW_SECTION_BORDER,
            ),
            CARD_PREVIEW,
        )
        editorArea.lineWrap = false
        editorArea.wrapStyleWord = false
        previewCardPanel.add(
            createSectionContainer(
                JBScrollPane(editorArea).apply {
                    border = JBUI.Borders.empty()
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                    SpecUiStyle.applyFastVerticalScrolling(this)
                },
                backgroundColor = PREVIEW_SECTION_BG,
                borderColor = PREVIEW_SECTION_BORDER,
            ),
            CARD_EDIT,
        )
        previewCardPanel.add(createClarificationCard(), CARD_CLARIFY)
        applyDocumentViewportSizing(previewCardPanel)
        switchPreviewCard(SpecDetailPreviewSurfaceCard.PREVIEW)
        val processTimelineSectionRoot = createProcessTimelineSection()
        setProcessTimelineVisible(false)
        previewPanel.add(processTimelineSectionRoot, BorderLayout.NORTH)
        previewPanel.add(
            previewCardPanel,
            BorderLayout.CENTER,
        )
        validationLabel.border = JBUI.Borders.empty(3, 2)
        validationLabel.font = JBUI.Fonts.smallFont()
        previewPanel.add(
            JPanel(BorderLayout()).apply {
                validationBannerPanel = this
                isOpaque = true
                background = STATUS_BG
                border = SpecUiStyle.roundedCardBorder(
                    lineColor = STATUS_BORDER,
                    arc = JBUI.scale(10),
                    top = 0,
                    left = 6,
                    bottom = 0,
                    right = 6,
                )
                add(validationLabel, BorderLayout.CENTER)
                isVisible = false
            },
            BorderLayout.SOUTH,
        )
        val composerContent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 4, 2, 4)
        }
        composerSourcePanel.isOpaque = false
        composerCodeContextPanel.isOpaque = false
        composerContent.add(
            JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                isOpaque = false
                add(composerSourcePanel, BorderLayout.NORTH)
                add(composerCodeContextPanel, BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )

        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.isOpaque = true
        inputArea.background = COMPOSER_EDITOR_BG
        inputArea.foreground = TREE_TEXT
        inputArea.caretColor = TREE_TEXT
        inputArea.border = JBUI.Borders.empty()
        inputArea.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = onClarificationInputEdited()
                override fun removeUpdate(e: DocumentEvent?) = onClarificationInputEdited()
                override fun changedUpdate(e: DocumentEvent?) = onClarificationInputEdited()
            },
        )
        updateInputPlaceholder(null)
        val inputScroll = JBScrollPane(inputArea)
        inputScroll.border = JBUI.Borders.empty()
        inputScroll.isOpaque = false
        inputScroll.viewport.isOpaque = true
        inputScroll.viewport.background = COMPOSER_EDITOR_BG
        inputScroll.preferredSize = java.awt.Dimension(0, JBUI.scale(56))
        inputScroll.minimumSize = java.awt.Dimension(0, JBUI.scale(56))
        SpecUiStyle.applyFastVerticalScrolling(inputScroll)
        inputSectionContainer = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = COMPOSER_EDITOR_BG
            border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(COMPOSER_EDITOR_BORDER, JBUI.scale(12)),
                JBUI.Borders.empty(8, 10, 8, 10),
            )
            add(inputScroll, BorderLayout.CENTER)
        }
        composerContent.add(inputSectionContainer, BorderLayout.CENTER)

        val actionBarLayout = actionBarLayoutBuilder.build()
        actionButtonPanel = actionBarLayout.buttonPanel
        composerContent.add(actionBarLayout.footerContainer, BorderLayout.SOUTH)
        composerTitleLabel.text = SpecCodingBundle.message("spec.detail.composer.title")
        styleClarificationSectionLabel(composerTitleLabel)
        composerSection = createCollapsibleSection(
            titleLabel = composerTitleLabel,
            content = createSectionContainer(
                composerContent,
                backgroundColor = COMPOSER_CARD_BG,
                borderColor = COMPOSER_CARD_BORDER,
            ),
            expanded = true,
            onToggle = { expanded ->
                isComposerExpanded = expanded
                composerManualOverride = expanded
                refreshComposerSectionLayout()
            },
        )
        bottomPanelContainer = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
            add(composerSection.root, BorderLayout.CENTER)
        }
        add(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(createTopAnchoredStackItem(previewPanel))
                add(createTopAnchoredStackItem(bottomPanelContainer))
            },
            BorderLayout.NORTH,
        )
        syncComposerSectionState(forceReset = true)
    }

    private fun applyInitialBottomPanelHeightIfNeeded() {
        if (hasAppliedInitialBottomHeight || !::mainSplitPane.isInitialized || !::bottomPanelContainer.isInitialized) {
            return
        }
        val total = mainSplitPane.height - mainSplitPane.dividerSize
        if (total <= 0) {
            return
        }
        val desiredBottomHeight = bottomPanelContainer.preferredSize.height.coerceAtLeast(JBUI.scale(44))
        mainSplitPane.dividerLocation = (total - desiredBottomHeight).coerceIn(0, total)
        hasAppliedInitialBottomHeight = true
    }

    private fun refreshActionButtonPresentation(workflow: SpecWorkflow? = currentWorkflow) {
        actionBarPresenter.applyPresentation(
            SpecDetailActionBarPresentationCoordinator.resolve(
                composeMode = currentComposeActionMode(workflow = workflow),
                workflowStatus = workflow?.status,
                editRequiresExplicitRevisionStart = workflow
                    ?.let(::resolveDetailViewState)
                    ?.editRequiresExplicitRevisionStart == true,
                customIcons = SpecDetailActionBarCustomIcons(
                    save = DETAIL_SAVE_ICON,
                    startRevision = DETAIL_START_REVISION_ICON,
                ),
            ),
        )
        actionBarChromePresenter.refreshChrome()
    }

    private fun currentComposeActionMode(
        phase: SpecPhase? = currentWorkflow?.currentPhase,
        workflow: SpecWorkflow? = currentWorkflow,
    ): ArtifactComposeActionMode {
        val workflow = workflow ?: return ArtifactComposeActionMode.GENERATE
        val resolvedPhase = phase ?: workflow.currentPhase
        return workflow.resolveComposeActionMode(resolvedPhase)
    }

    private fun configureDocumentTabsPanel() {
        documentTabsPanel.isOpaque = false
        documentTabsPanel.removeAll()
        documentTabButtons.clear()
        SpecPhase.entries.forEach { phase ->
            val button = JButton().apply {
                isFocusable = false
                addActionListener {
                    if (!isPhaseStepperEnabled) return@addActionListener
                    val workflow = currentWorkflow ?: return@addActionListener
                    if (selectedPhase == phase) return@addActionListener
                    selectedPhase = phase
                    updateTreeSelection(phase)
                    showDocumentPreview(phase)
                    updateButtonStates(workflow)
                    updatePhaseStepperVisuals()
                }
            }
            SpecDetailButtonChromeStyler.apply(button)
            documentTabsPanel.add(button)
            documentTabButtons[phase] = button
        }
        updatePhaseStepperVisuals()
    }

    private fun phaseStepperTitle(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> SpecCodingBundle.message("spec.detail.step.requirements")
            SpecPhase.DESIGN -> SpecCodingBundle.message("spec.detail.step.design")
            SpecPhase.IMPLEMENT -> SpecCodingBundle.message("spec.detail.step.taskList")
        }
    }

    private fun isWorkbenchArtifactOnlyView(): Boolean {
        return SpecDetailPanelViewState.isArtifactOnlyView(workbenchArtifactBinding)
    }

    internal fun allowStageFocusChange(targetStage: StageId): Boolean {
        val targetPhase = documentPhaseForStage(targetStage)
        if (isEditing) {
            val editing = editingPhase
            if (editing != null && editing != targetPhase) {
                Messages.showWarningDialog(
                    SpecCodingBundle.message(
                        "spec.detail.stageSwitch.blocked.editing",
                        SpecWorkflowOverviewPresenter.stageLabel(targetStage),
                    ),
                    SpecCodingBundle.message("spec.detail.stageSwitch.blocked.title"),
                )
                return false
            }
        }
        val clarificationPhase = clarificationState?.phase
        if (clarificationPhase != null && clarificationPhase != targetPhase) {
            Messages.showWarningDialog(
                SpecCodingBundle.message(
                    "spec.detail.stageSwitch.blocked.clarifying",
                    SpecWorkflowOverviewPresenter.stageLabel(targetStage),
                ),
                SpecCodingBundle.message("spec.detail.stageSwitch.blocked.title"),
            )
            return false
        }
        return true
    }

    private fun setPhaseStepperEnabled(enabled: Boolean) {
        isPhaseStepperEnabled = enabled
        updatePhaseStepperVisuals()
    }

    private fun updatePhaseStepperVisuals() {
        val workflow = currentWorkflow
        val completedPhases = workflow
            ?.let { wf ->
                SpecPhase.entries
                    .filter { phase -> wf.documents.containsKey(phase) && phase != wf.currentPhase }
                    .toSet()
            }
            .orEmpty()
        val artifactOnlyView = isWorkbenchArtifactOnlyView()
        phaseStepperRail.updateState(
            selected = selectedPhase.takeUnless { artifactOnlyView },
            current = workflow?.currentPhase,
            completed = completedPhases,
            interactive = isPhaseStepperEnabled && workflow != null,
        )
    }

    private fun documentPhaseForStage(stageId: StageId): SpecPhase? {
        return when (stageId) {
            StageId.REQUIREMENTS -> SpecPhase.SPECIFY
            StageId.DESIGN -> SpecPhase.DESIGN
            StageId.TASKS,
            StageId.IMPLEMENT,
            -> SpecPhase.IMPLEMENT

            StageId.VERIFY,
            StageId.ARCHIVE,
            -> null
        }
    }

    private fun applyDocumentTabButtonStyle(
        button: JButton,
        selected: Boolean,
        current: Boolean,
        available: Boolean,
    ) {
        val background = when {
            selected -> DOCUMENT_TAB_BG_SELECTED
            current -> DOCUMENT_TAB_BG_CURRENT
            available -> DOCUMENT_TAB_BG_AVAILABLE
            else -> DOCUMENT_TAB_BG_IDLE
        }
        val border = when {
            selected -> DOCUMENT_TAB_BORDER_SELECTED
            current -> DOCUMENT_TAB_BORDER_CURRENT
            available -> DOCUMENT_TAB_BORDER_AVAILABLE
            else -> DOCUMENT_TAB_BORDER_IDLE
        }
        val foreground = when {
            selected -> DOCUMENT_TAB_TEXT_SELECTED
            current -> DOCUMENT_TAB_TEXT_CURRENT
            available -> DOCUMENT_TAB_TEXT_AVAILABLE
            else -> DOCUMENT_TAB_TEXT_IDLE
        }
        button.background = background
        button.foreground = foreground
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(border, JBUI.scale(10)),
            JBUI.Borders.empty(2, 8, 2, 8),
        )
    }

    private inner class PhaseStepperRail : JPanel() {
        private var onPhaseSelected: ((SpecPhase) -> Unit)? = null
        private var selectedPhase: SpecPhase? = null
        private var currentPhase: SpecPhase? = null
        private var completedPhases: Set<SpecPhase> = emptySet()
        private var interactionEnabled: Boolean = false
        private var hoveredPhase: SpecPhase? = null
        private var chipBounds: Map<SpecPhase, Rectangle> = emptyMap()

        init {
            isOpaque = false
            border = JBUI.Borders.empty()
            preferredSize = Dimension(0, JBUI.scale(42))
            minimumSize = Dimension(0, JBUI.scale(38))
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseExited(e: MouseEvent?) {
                        updateHoveredPhase(null)
                    }

                    override fun mouseClicked(e: MouseEvent) {
                        if (!interactionEnabled || !SwingUtilities.isLeftMouseButton(e)) return
                        val phase = resolvePhaseAt(e.x, e.y) ?: return
                        onPhaseSelected?.invoke(phase)
                    }
                },
            )
            addMouseMotionListener(
                object : MouseMotionAdapter() {
                    override fun mouseMoved(e: MouseEvent) {
                        updateHoveredPhase(resolvePhaseAt(e.x, e.y))
                    }
                },
            )
        }

        fun setOnPhaseSelected(listener: (SpecPhase) -> Unit) {
            onPhaseSelected = listener
        }

        fun updateState(
            selected: SpecPhase?,
            current: SpecPhase?,
            completed: Set<SpecPhase>,
            interactive: Boolean,
        ) {
            selectedPhase = selected
            currentPhase = current
            completedPhases = completed
            interactionEnabled = interactive
            if (!interactive) {
                hoveredPhase = null
            }
            cursor = if (interactionEnabled && hoveredPhase != null) {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            } else {
                Cursor.getDefaultCursor()
            }
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = (g.create() as? Graphics2D) ?: return
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val phases = SpecPhase.entries
            if (phases.isEmpty() || width <= 0 || height <= 0) {
                g2.dispose()
                return
            }

            val strongLabelFont = JBUI.Fonts.label().deriveFont(Font.BOLD, 12f)
            val normalLabelFont = JBUI.Fonts.label().deriveFont(Font.PLAIN, 12f)
            val outerPaddingX = 0
            val outerPaddingY = JBUI.scale(4)
            val trackX = outerPaddingX
            val trackY = outerPaddingY
            val trackWidth = (width - outerPaddingX * 2).coerceAtLeast(phases.size)
            val trackHeight = (height - outerPaddingY * 2).coerceAtLeast(JBUI.scale(28))
            val trackRadius = JBUI.scale(14)
            val baselineInset = JBUI.scale(6)
            val baselineY = (trackY + trackHeight + JBUI.scale(3)).coerceAtMost(height - JBUI.scale(2))
            val baseSegmentWidth = trackWidth / phases.size
            val widthRemainder = trackWidth % phases.size

            g2.color = STEPPER_CHIP_TRACK_BG
            g2.fillRoundRect(trackX, trackY, trackWidth, trackHeight, trackRadius, trackRadius)
            g2.color = STEPPER_CHIP_TRACK_BORDER
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat() + 0.18f)
            g2.drawRoundRect(trackX, trackY, trackWidth - 1, trackHeight - 1, trackRadius, trackRadius)
            g2.color = withAlpha(STEPPER_CHIP_TRACK_BORDER, 72)
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
            g2.drawRoundRect(trackX + 1, trackY + 1, trackWidth - 3, trackHeight - 3, trackRadius, trackRadius)

            val bounds = linkedMapOf<SpecPhase, Rectangle>()
            val previousClip = g2.clip
            g2.clip = RoundRectangle2D.Float(
                trackX.toFloat(),
                trackY.toFloat(),
                trackWidth.toFloat(),
                trackHeight.toFloat(),
                trackRadius.toFloat(),
                trackRadius.toFloat(),
            )
            var segmentX = trackX
            phases.forEachIndexed { index, phase ->
                val segmentWidth = baseSegmentWidth + if (index < widthRemainder) 1 else 0
                val rect = Rectangle(segmentX, trackY, segmentWidth, trackHeight)
                segmentX += segmentWidth
                bounds[phase] = rect
                val selected = phase == selectedPhase
                val current = phase == currentPhase
                val done = phase in completedPhases
                val hovered = interactionEnabled && hoveredPhase == phase

                if (current) {
                    g2.color = withAlpha(STEPPER_CHIP_GLOW, if (hovered) 72 else 56)
                    g2.fillRect(
                        rect.x - JBUI.scale(2),
                        rect.y - JBUI.scale(2),
                        rect.width + JBUI.scale(4),
                        rect.height + JBUI.scale(4),
                    )
                }

                val segmentFill = when {
                    current -> STEPPER_CHIP_BG_CURRENT
                    selected -> STEPPER_CHIP_BG_SELECTED
                    done -> STEPPER_CHIP_BG_DONE
                    hovered -> STEPPER_CHIP_BG_HOVER
                    else -> STEPPER_CHIP_BG_PENDING
                }
                g2.color = segmentFill
                g2.fillRect(rect.x, rect.y, rect.width, rect.height)
            }
            g2.clip = previousClip

            var dividerX = trackX
            for (index in 0 until phases.size - 1) {
                dividerX += baseSegmentWidth + if (index < widthRemainder) 1 else 0
                g2.color = STEPPER_CHIP_DIVIDER
                g2.stroke = BasicStroke(JBUI.scale(1).toFloat() + 0.08f)
                g2.drawLine(
                    dividerX,
                    trackY + JBUI.scale(5),
                    dividerX,
                    trackY + trackHeight - JBUI.scale(6),
                )
            }

            phases.forEach { phase ->
                val rect = bounds[phase] ?: return@forEach
                val selected = phase == selectedPhase
                val current = phase == currentPhase
                val done = phase in completedPhases
                val hovered = interactionEnabled && hoveredPhase == phase
                val label = phaseStepperTitle(phase)
                g2.font = if (current || selected) {
                    strongLabelFont
                } else {
                    normalLabelFont
                }
                val labelMetrics = g2.fontMetrics
                val displayLabel = fitTextToWidth(label, rect.width - JBUI.scale(12), labelMetrics)
                val displayWidth = labelMetrics.stringWidth(displayLabel)
                val labelX = rect.x + ((rect.width - displayWidth) / 2).coerceAtLeast(0)
                val labelY = rect.y + (rect.height + labelMetrics.ascent - labelMetrics.descent) / 2
                g2.color = when {
                    current -> STEPPER_CHIP_TEXT_CURRENT
                    selected -> STEPPER_CHIP_TEXT_SELECTED
                    done -> STEPPER_CHIP_TEXT_DONE
                    hovered -> STEPPER_CHIP_TEXT_HOVER
                    else -> STEPPER_CHIP_TEXT_PENDING
                }
                g2.drawString(displayLabel, labelX, labelY)
            }

            g2.color = withAlpha(STEPPER_CHIP_BASELINE, 170)
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat() + 0.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.drawLine(
                trackX + baselineInset,
                baselineY,
                trackX + trackWidth - baselineInset,
                baselineY,
            )
            val emphasisPhase = selectedPhase ?: currentPhase
            emphasisPhase?.let { phase ->
                val rect = bounds[phase] ?: return@let
                val activeStart = (rect.x + JBUI.scale(13)).coerceAtLeast(trackX + baselineInset)
                val activeEnd = (rect.x + rect.width - JBUI.scale(13)).coerceAtMost(trackX + trackWidth - baselineInset)
                if (activeEnd > activeStart) {
                    g2.color = withAlpha(STEPPER_CHIP_BASELINE_ACTIVE_START, 56)
                    g2.stroke = BasicStroke(JBUI.scale(2).toFloat() + 0.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.drawLine(activeStart, baselineY, activeEnd, baselineY)
                    g2.paint = GradientPaint(
                        activeStart.toFloat(),
                        baselineY.toFloat(),
                        STEPPER_CHIP_BASELINE_ACTIVE_START,
                        activeEnd.toFloat(),
                        baselineY.toFloat(),
                        STEPPER_CHIP_BASELINE_ACTIVE_END,
                    )
                    g2.stroke = BasicStroke(JBUI.scale(1).toFloat() + 0.45f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.drawLine(activeStart, baselineY, activeEnd, baselineY)
                }
            }
            chipBounds = bounds
            g2.dispose()
        }

        private fun updateHoveredPhase(phase: SpecPhase?) {
            val normalized = if (interactionEnabled) phase else null
            if (hoveredPhase == normalized) return
            hoveredPhase = normalized
            cursor = if (normalized != null) {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            } else {
                Cursor.getDefaultCursor()
            }
            repaint()
        }

        private fun resolvePhaseAt(x: Int, y: Int): SpecPhase? {
            return chipBounds.entries.firstOrNull { (_, rect) -> rect.contains(x, y) }?.key
        }

        private fun fitTextToWidth(text: String, maxWidth: Int, metrics: java.awt.FontMetrics): String {
            if (maxWidth <= 0) return ""
            if (metrics.stringWidth(text) <= maxWidth) return text
            val ellipsis = "…"
            val ellipsisWidth = metrics.stringWidth(ellipsis)
            if (ellipsisWidth >= maxWidth) return ellipsis
            var low = 0
            var high = text.length
            while (low < high) {
                val mid = (low + high + 1) / 2
                val candidate = text.substring(0, mid)
                val width = metrics.stringWidth(candidate) + ellipsisWidth
                if (width <= maxWidth) {
                    low = mid
                } else {
                    high = mid - 1
                }
            }
            return text.substring(0, low).trimEnd() + ellipsis
        }

        private fun withAlpha(color: Color, alpha: Int): Color {
            return Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
        }
    }

    private fun createClarificationCard(): JPanel {
        clarificationQuestionsPane.isEditable = false
        clarificationQuestionsPane.isOpaque = false
        clarificationQuestionsPane.border = JBUI.Borders.empty(2, 2)
        styleClarificationSectionLabel(clarificationQuestionsLabel)
        clarificationChecklistHintLabel.font = JBUI.Fonts.smallFont()
        clarificationChecklistHintLabel.foreground = TREE_FILE_TEXT
        clarificationChecklistHintLabel.border = JBUI.Borders.empty(0, 2, 2, 2)

        clarificationChecklistPanel.layout = BoxLayout(clarificationChecklistPanel, BoxLayout.Y_AXIS)
        clarificationChecklistPanel.isOpaque = false
        val checklistHeaderPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(clarificationChecklistHintLabel, BorderLayout.CENTER)
        }

        clarificationQuestionsCardPanel.isOpaque = false
        clarificationQuestionsCardPanel.add(
            JBScrollPane(clarificationQuestionsPane).apply {
                border = JBUI.Borders.empty()
                SpecUiStyle.applyFastVerticalScrolling(this)
            },
            CLARIFY_QUESTIONS_CARD_MARKDOWN,
        )
        clarificationQuestionsCardPanel.add(
            JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                isOpaque = false
                add(checklistHeaderPanel, BorderLayout.NORTH)
                add(
                    JBScrollPane(clarificationChecklistPanel).apply {
                        border = JBUI.Borders.empty()
                        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                        SpecUiStyle.applyFastVerticalScrolling(this)
                    },
                    BorderLayout.CENTER,
                )
            },
            CLARIFY_QUESTIONS_CARD_CHECKLIST,
        )

        clarificationPreviewPane.isEditable = false
        clarificationPreviewPane.isOpaque = false
        clarificationPreviewPane.border = JBUI.Borders.empty(2, 2)
        styleClarificationSectionLabel(clarificationPreviewLabel)

        clarificationQuestionsSection = createCollapsibleSection(
            titleLabel = clarificationQuestionsLabel,
            content = createSectionContainer(
                clarificationQuestionsCardPanel,
                backgroundColor = CLARIFICATION_QUESTIONS_BG,
                borderColor = CLARIFICATION_QUESTIONS_BORDER,
            ),
            expanded = isClarificationQuestionsExpanded,
        ) { expanded ->
            isClarificationQuestionsExpanded = expanded
            refreshClarificationSectionsLayout()
        }
        val questionsSection = clarificationQuestionsSection.root

        clarificationPreviewSection = createCollapsibleSection(
            titleLabel = clarificationPreviewLabel,
            content = createSectionContainer(
                JBScrollPane(clarificationPreviewPane).apply {
                    border = JBUI.Borders.empty()
                    SpecUiStyle.applyFastVerticalScrolling(this)
                },
                backgroundColor = CLARIFICATION_PREVIEW_BG,
                borderColor = CLARIFICATION_PREVIEW_BORDER,
            ),
            expanded = isClarificationPreviewExpanded,
        ) { expanded ->
            isClarificationPreviewExpanded = expanded
            refreshClarificationSectionsLayout()
        }

        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = true
            background = CLARIFICATION_CARD_BG
            clarificationSplitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
                topComponent = questionsSection
                bottomComponent = clarificationPreviewSection.root
                resizeWeight = 0.58
                border = JBUI.Borders.empty()
                background = PANEL_BG
                SpecUiStyle.applyChatLikeSpecDivider(
                    splitPane = this,
                    dividerSize = JBUI.scale(4),
                )
            }
            add(clarificationSplitPane, BorderLayout.CENTER)
            refreshClarificationSectionsLayout()
        }
    }

    private fun createProcessTimelineSection(): JPanel {
        processTimelinePane.isEditable = false
        processTimelinePane.isOpaque = false
        processTimelinePane.border = JBUI.Borders.empty(2, 2)
        styleClarificationSectionLabel(processTimelineLabel)

        processTimelineSection = createCollapsibleSection(
            titleLabel = processTimelineLabel,
            content = createSectionContainer(
                JBScrollPane(processTimelinePane).apply {
                    border = JBUI.Borders.empty()
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                    preferredSize = JBUI.size(0, JBUI.scale(96))
                    SpecUiStyle.applyFastVerticalScrolling(this)
                },
                backgroundColor = PROCESS_SECTION_BG,
                borderColor = PROCESS_SECTION_BORDER,
            ),
            expanded = isProcessTimelineExpanded,
        ) { expanded ->
            isProcessTimelineExpanded = expanded
            applyProcessTimelineCollapseState()
        }
        return processTimelineSection.root.apply {
            border = JBUI.Borders.emptyBottom(JBUI.scale(2))
            applyProcessTimelineCollapseState()
        }
    }

    private fun createCollapsibleSection(
        titleLabel: JBLabel,
        content: Component,
        expanded: Boolean,
        onToggle: (Boolean) -> Unit,
    ): SpecDetailCollapsibleSectionView {
        return SpecDetailCollapsibleSectionView(
            titleLabel = titleLabel,
            content = content,
            expandedInitially = expanded,
            activeToggleForeground = COLLAPSE_TOGGLE_TEXT_ACTIVE,
            inactiveToggleForeground = TREE_FILE_TEXT,
            disabledToggleForeground = TREE_STATUS_PENDING_TEXT,
            onToggle = onToggle,
        )
    }

    private fun createTopAnchoredStackItem(component: Component): JPanel {
        return object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension {
                val preferred = preferredSize
                return Dimension(Int.MAX_VALUE, preferred.height)
            }
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(component, BorderLayout.CENTER)
        }
    }

    private fun refreshCollapsibleToggleTexts() {
        if (::processTimelineSection.isInitialized) {
            processTimelineSection.applyToggleState(
                expanded = isProcessTimelineExpanded,
                enabled = true,
            )
        }
        if (::clarificationQuestionsSection.isInitialized) {
            clarificationQuestionsSection.applyToggleState(
                expanded = isClarificationQuestionsExpanded,
                enabled = true,
            )
        }
        if (::clarificationPreviewSection.isInitialized) {
            clarificationPreviewSection.applyToggleState(
                expanded = isClarificationPreviewExpanded,
                enabled = isClarificationPreviewContentVisible,
            )
        }
        if (::composerSection.isInitialized) {
            composerSection.applyToggleState(
                expanded = isComposerExpanded,
                enabled = currentWorkflow != null,
            )
        }
    }

    private fun styleClarificationSectionLabel(label: JBLabel) {
        label.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        label.foreground = SECTION_TITLE_FG
        label.border = JBUI.Borders.empty(0, 2, 2, 2)
    }

    private fun previewSurfacePlan(
        card: SpecDetailPreviewSurfaceCard = activePreviewCard,
    ): SpecDetailPreviewSurfacePlan {
        return SpecDetailPreviewSurfaceCoordinator.preserveCurrent(
            currentCard = card,
            hasClarificationState = clarificationState != null,
            isClarificationGenerating = isClarificationGenerating,
        )
    }

    private fun applyPreviewSurfacePlan(plan: SpecDetailPreviewSurfacePlan) {
        setClarificationPreviewVisible(plan.clarificationPreviewVisible)
        switchPreviewCard(plan.card)
    }

    private fun switchPreviewCard(card: SpecDetailPreviewSurfaceCard) {
        activePreviewCard = card
        previewCardLayout.show(previewCardPanel, card.cardKey())
        refreshActionButtonCursors()
    }

    private fun SpecDetailPreviewSurfaceCard.cardKey(): String {
        return when (this) {
            SpecDetailPreviewSurfaceCard.PREVIEW -> CARD_PREVIEW
            SpecDetailPreviewSurfaceCard.EDIT -> CARD_EDIT
            SpecDetailPreviewSurfaceCard.CLARIFY -> CARD_CLARIFY
        }
    }

    private fun showInputRequiredHint(phase: SpecPhase?) {
        if (phase != SpecPhase.SPECIFY) return
        validationBannerPresenter.show(
            ArtifactComposeActionUiText.inputRequired(currentComposeActionMode(phase)),
            JBColor(Color(213, 52, 52), Color(255, 140, 140)),
        )
    }

    private fun createSectionContainer(
        content: java.awt.Component,
        backgroundColor: Color = PANEL_BG,
        borderColor: Color = PANEL_BORDER,
    ): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = backgroundColor
            border = SpecUiStyle.roundedCardBorder(
                lineColor = borderColor,
                arc = JBUI.scale(14),
                top = 2,
                left = 2,
                bottom = 2,
                right = 2,
            )
            add(content, BorderLayout.CENTER)
        }
    }

    private fun applyDocumentViewportSizing(component: JPanel) {
        val viewportSize = JBUI.size(0, JBUI.scale(DOCUMENT_VIEWPORT_HEIGHT))
        component.preferredSize = viewportSize
        component.minimumSize = viewportSize
    }

    private fun updateButtonCursor(button: JButton) {
        button.cursor = if (button.isEnabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private fun refreshActionButtonCursors() {
        actionBarPresenter.refreshCursors()
        documentTabButtons.values.forEach(::updateButtonCursor)
    }

    fun refreshLocalizedTexts() {
        treeRoot.userObject = SpecCodingBundle.message("spec.detail.documents")
        treeModel.reload()
        processTimelineLabel.text = SpecCodingBundle.message("spec.detail.process.title")
        clarificationQuestionsLabel.text = SpecCodingBundle.message("spec.detail.clarify.questions.title")
        clarificationChecklistHintLabel.text = SpecCodingBundle.message("spec.detail.clarify.checklist.hint")
        clarificationPreviewLabel.text = SpecCodingBundle.message("spec.detail.clarify.preview.title")
        composerTitleLabel.text = SpecCodingBundle.message("spec.detail.composer.title")
        composerSourcePanel.refreshLocalizedTexts()
        composerCodeContextPanel.refreshLocalizedTexts()
        refreshActionButtonPresentation()
        updateInputPlaceholder(currentWorkflow?.currentPhase)
        updatePhaseStepperVisuals()
        refreshCollapsibleToggleTexts()
        renderProcessTimeline()
        if (isClarificationGenerating) {
            renderClarificationQuestions(
                markdown = ArtifactComposeActionUiText.clarificationGenerating(currentComposeActionMode()),
                structuredQuestions = emptyList(),
                questionDecisions = emptyMap(),
                questionDetails = emptyMap(),
            )
        } else {
            clarificationState?.let { state ->
                renderClarificationQuestions(
                    markdown = state.questionsMarkdown,
                    structuredQuestions = state.structuredQuestions,
                    questionDecisions = state.questionDecisions,
                    questionDetails = state.questionDetails,
                )
            }
        }
        applyPreviewSurfacePlan(previewSurfacePlan())
        updateClarificationPreview()
        refreshInputAreaMode()
        if (currentWorkflow == null) {
            validationBannerPresenter.applyStatus(SpecDetailPreviewStatusCoordinator.noWorkflow())
        } else {
            showActivePreview()
        }
    }

    fun updateWorkflow(workflow: SpecWorkflow?, followCurrentPhase: Boolean = false) {
        val previousWorkflowId = currentWorkflow?.id
        currentWorkflow = workflow
        if (workflow == null) {
            showEmpty()
            return
        }
        if (previousWorkflowId != workflow.id) {
            explicitRevisionPhase = null
            applyClarificationLifecycleState(SpecDetailClarificationLifecycleCoordinator.clear())
            clearProcessTimeline()
            composerSourcePanel.clear()
        }
        if (previousWorkflowId != workflow.id || composerCodeContextState.codeContextPack?.phase != workflow.currentPhase) {
            composerCodeContextState = ComposerCodeContextState(workflowId = workflow.id)
        }
        if (clarificationState?.phase != null && clarificationState?.phase != workflow.currentPhase) {
            applyClarificationLifecycleState(SpecDetailClarificationLifecycleCoordinator.clear())
        }
        applyClarificationLifecycleState(
            SpecDetailClarificationLifecycleCoordinator.stopGenerating(
                state = currentClarificationLifecycleState(),
                unlockChecklist = false,
            ),
        )
        stopGeneratingAnimation()
        if (followCurrentPhase) {
            clearInput()
        }
        rebuildTree(workflow)
        updateInputPlaceholder(workflow.currentPhase)
        val preservedPhase = selectedPhase?.takeIf { !followCurrentPhase && previousWorkflowId == workflow.id }
        val artifactOnlyView = previousWorkflowId == workflow.id && isWorkbenchArtifactOnlyView()
        selectedPhase = when {
            artifactOnlyView -> null
            preferredWorkbenchPhase != null -> preferredWorkbenchPhase
            else -> preservedPhase ?: workflow.currentPhase
        }
        refreshActionButtonPresentation(workflow)
        setPhaseStepperEnabled(!isEditing)
        updateTreeSelection(selectedPhase, forceComposerReset = false)
        updateButtonStates(workflow)
        refreshComposerCodeContextPanelState()
        refreshInputAreaMode()
        syncComposerSectionState(forceReset = previousWorkflowId != workflow.id || followCurrentPhase)
        applyPreviewSurfacePlan(
            SpecDetailPreviewSurfaceCoordinator.forWorkflow(
                hasClarificationState = clarificationState != null,
                isClarificationGenerating = isClarificationGenerating,
            ),
        )
        if (clarificationState == null) {
            showActivePreview(keepGeneratingIndicator = false)
        } else {
            updateClarificationPreview()
        }
    }

    fun showEmpty() {
        applyClarificationLifecycleState(SpecDetailClarificationLifecycleCoordinator.clear())
        stopGeneratingAnimation()
        isEditing = false
        explicitRevisionPhase = null
        editingPhase = null
        preferredWorkbenchPhase = null
        workbenchArtifactBinding = null
        composerSourceState = ComposerSourceState()
        composerCodeContextState = ComposerCodeContextState()
        selectedPhase = null
        clearProcessTimeline()
        treeRoot.removeAllChildren()
        treeModel.reload()
        setPhaseStepperEnabled(false)
        updateTreeSelection(null)
        applyPreviewSurfacePlan(SpecDetailPreviewSurfaceCoordinator.forPreview())
        documentTree.isEnabled = true
        previewPanePresenter.reset()
        clarificationQuestionsPane.text = ""
        clarificationChecklistPanel.removeAll()
        clarificationChecklistHintLabel.text = SpecCodingBundle.message("spec.detail.clarify.checklist.hint")
        clarificationPreviewPane.text = ""
        validationBannerPresenter.applyStatus(SpecDetailPreviewStatusCoordinator.noWorkflow())
        inputArea.isEnabled = true
        inputArea.isEditable = true
        inputArea.toolTipText = null
        composerSourcePanel.clear()
        composerCodeContextPanel.clear()
        updateInputPlaceholder(null)
        refreshActionButtonPresentation()
        actionBarPresenter.applyEmptyState()
        composerContextKey = null
        composerManualOverride = null
        if (::composerSection.isInitialized) {
            setComposerExpanded(false)
        }
    }

    internal fun updateWorkbenchState(
        state: SpecWorkflowStageWorkbenchState,
        syncSelection: Boolean,
    ) {
        workbenchArtifactBinding = state.artifactBinding
        preferredWorkbenchPhase = state.artifactBinding.documentPhase
        val workflow = currentWorkflow ?: return
        updatePhaseStepperVisuals()
        if (isEditing) {
            updateButtonStates(workflow)
            return
        }
        if (syncSelection) {
            val desiredPhase = preferredWorkbenchPhase
            if (desiredPhase != null) {
                if (selectedPhase != desiredPhase) {
                    updateTreeSelection(desiredPhase, forceComposerReset = false)
                }
            } else if (selectedPhase != null) {
                updateTreeSelection(null, forceComposerReset = false)
            }
        }
        updateButtonStates(workflow)
        if (clarificationState == null && (syncSelection || isWorkbenchArtifactOnlyView())) {
            showActivePreview(keepGeneratingIndicator = false)
        }
    }

    fun updateWorkflowSources(
        workflowId: String?,
        assets: List<WorkflowSourceAsset>,
        selectedSourceIds: Set<String>,
        editable: Boolean,
    ) {
        composerSourceState = ComposerSourceState(
            workflowId = workflowId,
            assets = assets,
            selectedSourceIds = selectedSourceIds,
            editable = editable,
        )
        refreshComposerSourcePanelState()
    }

    fun updateAutoCodeContext(
        workflowId: String?,
        codeContextPack: CodeContextPack?,
    ) {
        composerCodeContextState = ComposerCodeContextState(
            workflowId = workflowId,
            codeContextPack = codeContextPack,
        )
        refreshComposerCodeContextPanelState()
    }

    private fun updateInputPlaceholder(currentPhase: SpecPhase?) {
        val lockedPhase = currentWorkflow?.let(::currentReadOnlyRevisionLockedPhase)
        inputArea.emptyText.text = if (lockedPhase != null) {
            SpecCodingBundle.message("spec.detail.revision.input.locked", phaseStepperTitle(lockedPhase))
        } else {
            ArtifactComposeActionUiText.inputPlaceholder(
                mode = currentComposeActionMode(currentPhase),
                phase = currentPhase,
                isClarifying = clarificationState != null,
                checklistMode = clarificationState?.structuredQuestions?.isNotEmpty() == true,
            )
        }
    }

    private fun updateTreeSelection(phase: SpecPhase?, forceComposerReset: Boolean = true) {
        selectedPhase = phase
        updatePhaseStepperVisuals()
        syncComposerSectionState(forceReset = forceComposerReset)
    }

    private fun showActivePreview(keepGeneratingIndicator: Boolean = true) {
        val workflow = currentWorkflow ?: return
        if (!keepGeneratingIndicator || !isGeneratingActive) {
            stopGeneratingAnimation()
        }
        previewContentPresenter.apply(
            SpecDetailPreviewContentCoordinator.forActivePreview(
                workflow = workflow,
                selectedPhase = selectedPhase,
                workbenchArtifactBinding = workbenchArtifactBinding,
                isGeneratingActive = isGeneratingActive,
                keepGeneratingIndicator = keepGeneratingIndicator,
                revisionLockedPhase = currentReadOnlyRevisionLockedPhase(workflow),
                isEditing = isEditing,
            ),
        )
    }

    private fun resolveDisplayedDocumentPhase(workflow: SpecWorkflow): SpecPhase? {
        return resolveDetailViewState(workflow).displayedDocumentPhase
    }

    private fun currentReadOnlyRevisionLockedPhase(workflow: SpecWorkflow): SpecPhase? {
        return resolveDetailViewState(workflow).revisionLockedPhase
    }

    private fun revisionLockedDisabledReason(phase: SpecPhase): String {
        return SpecCodingBundle.message("spec.detail.revision.locked.action", phaseStepperTitle(phase))
    }

    private fun resolveEditablePhase(workflow: SpecWorkflow): SpecPhase? {
        return resolveDetailViewState(workflow).editablePhase
    }

    private fun startEditing() {
        if (isEditing || clarificationState != null) return
        val workflow = currentWorkflow ?: return
        val viewState = resolveDetailViewState(workflow)
        val phase = viewState.editablePhase ?: return
        if (viewState.revisionLockedPhase == phase) {
            return
        }
        val document = workflow.getDocument(phase)
        isEditing = true
        editingPhase = phase
        editorArea.text = document?.content.orEmpty()
        editorArea.caretPosition = 0
        applyPreviewSurfacePlan(SpecDetailPreviewSurfaceCoordinator.forEdit())
        documentTree.isEnabled = false
        setPhaseStepperEnabled(false)
        refreshInputAreaMode()
        updateButtonStates(workflow)
    }

    private fun stopEditing(keepText: Boolean) {
        val workflow = currentWorkflow ?: return
        val completedRevisionPhase = editingPhase
        if (!keepText) {
            editorArea.text = ""
        }
        isEditing = false
        editingPhase = null
        if (completedRevisionPhase != null && explicitRevisionPhase == completedRevisionPhase) {
            explicitRevisionPhase = null
        }
        applyPreviewSurfacePlan(SpecDetailPreviewSurfaceCoordinator.forPreview())
        documentTree.isEnabled = true
        setPhaseStepperEnabled(true)
        refreshInputAreaMode()
        updateButtonStates(workflow)
        showActivePreview(keepGeneratingIndicator = false)
    }

    private fun saveEditing() {
        val workflow = currentWorkflow ?: return
        val phase = editingPhase ?: return
        val normalized = normalizeContent(editorArea.text)
        if (normalized.isBlank()) {
            Messages.showWarningDialog(
                SpecCodingBundle.message("spec.detail.edit.emptyNotAllowed"),
                SpecCodingBundle.message("spec.detail.save"),
            )
            return
        }
        actionBarButtons.save.isEnabled = false
        actionBarButtons.cancelEdit.isEnabled = false
        onSaveDocument(phase, normalized) { result ->
            actionBarButtons.save.isEnabled = true
            actionBarButtons.cancelEdit.isEnabled = true
            result.onSuccess { updated ->
                currentWorkflow = updated
                stopEditing(keepText = false)
                updateWorkflow(updated)
            }.onFailure {
                updateButtonStates(workflow)
            }
        }
    }

    private fun normalizeContent(content: String): String {
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    fun showClarificationGenerating(
        phase: SpecPhase,
        input: String,
        suggestedDetails: String = input,
    ) {
        val lifecyclePlan = SpecDetailClarificationLifecycleCoordinator.showGenerating(
            phase = phase,
            input = input,
            suggestedDetails = suggestedDetails,
            mode = currentComposeActionMode(phase),
        )
        applyClarificationLifecycleState(lifecyclePlan.lifecycleState)
        renderClarificationQuestions(
            markdown = lifecyclePlan.questionsMarkdown,
            structuredQuestions = emptyList(),
            questionDecisions = emptyMap(),
            questionDetails = emptyMap(),
        )
        inputArea.text = lifecyclePlan.suggestedDetails
        inputArea.caretPosition = 0
        updateInputPlaceholder(phase)
        refreshInputAreaMode()
        applyPreviewSurfacePlan(lifecyclePlan.previewSurfacePlan)
        updateClarificationPreview()
        persistClarificationDraftSnapshot()

        generatingPercent = 0
        startGeneratingAnimation()
        updateGeneratingLabel()
        currentWorkflow?.let { updateButtonStates(it) } ?: actionBarPresenter.disableAll()
    }

    fun showClarificationDraft(
        phase: SpecPhase,
        input: String,
        questionsMarkdown: String,
        suggestedDetails: String = input,
        structuredQuestions: List<String> = emptyList(),
    ) {
        val lifecyclePlan = SpecDetailClarificationLifecycleCoordinator.showDraft(
            phase = phase,
            input = input,
            questionsMarkdown = questionsMarkdown,
            suggestedDetails = suggestedDetails,
            structuredQuestions = structuredQuestions,
            clarificationText = clarificationText(),
            mode = currentComposeActionMode(phase),
        )
        stopGeneratingAnimation()
        applyClarificationLifecycleState(lifecyclePlan.lifecycleState)
        val draftState = requireNotNull(lifecyclePlan.lifecycleState.clarificationState)
        renderClarificationQuestions(
            markdown = lifecyclePlan.questionsMarkdown,
            structuredQuestions = draftState.structuredQuestions,
            questionDecisions = draftState.questionDecisions,
            questionDetails = draftState.questionDetails,
        )
        if (draftState.checklistMode) {
            applyClarificationInputSync(lifecyclePlan.inputSyncPlan)
        } else {
            inputArea.text = lifecyclePlan.suggestedDetails
            inputArea.caretPosition = 0
        }
        updateInputPlaceholder(phase)
        refreshInputAreaMode()
        applyPreviewSurfacePlan(lifecyclePlan.previewSurfacePlan)
        updateClarificationPreview()
        persistClarificationDraftSnapshot()
        validationBannerPresenter.applyStatus(lifecyclePlan.statusPlan)
        currentWorkflow?.let { updateButtonStates(it) } ?: actionBarPresenter.disableAll()
    }

    fun showProcessTimeline(entries: List<ProcessTimelineEntry>) {
        processTimelineModel = processTimelineModel.replace(entries)
        renderProcessTimeline()
    }

    fun appendProcessTimelineEntry(
        text: String,
        state: ProcessTimelineState = ProcessTimelineState.INFO,
    ) {
        processTimelineModel = processTimelineModel.append(
            text = text,
            state = state,
        )
        renderProcessTimeline()
    }

    fun clearProcessTimeline() {
        processTimelineModel = processTimelineModel.clear()
        renderProcessTimeline()
    }

    fun lockClarificationChecklistInteractions() {
        setClarificationChecklistReadOnly(true)
    }

    fun unlockClarificationChecklistInteractions() {
        setClarificationChecklistReadOnly(false)
    }

    private fun setClarificationChecklistReadOnly(readOnly: Boolean) {
        if (isClarificationChecklistReadOnly == readOnly) {
            return
        }
        isClarificationChecklistReadOnly = readOnly
        clarificationState
            ?.takeIf { it.checklistMode }
            ?.let { state ->
                renderClarificationQuestions(
                    markdown = state.questionsMarkdown,
                    structuredQuestions = state.structuredQuestions,
                    questionDecisions = state.questionDecisions,
                    questionDetails = state.questionDetails,
                )
            }
        refreshInputAreaMode()
        currentWorkflow?.let { updateButtonStates(it) }
    }

    private fun currentClarificationLifecycleState(): SpecDetailClarificationLifecycleState {
        return SpecDetailClarificationLifecycleState(
            clarificationState = clarificationState,
            activeDetailIndex = activeChecklistDetailIndex,
            checklistReadOnly = isClarificationChecklistReadOnly,
            isGeneratingActive = isGeneratingActive,
            isClarificationGenerating = isClarificationGenerating,
        )
    }

    private fun applyClarificationLifecycleState(
        state: SpecDetailClarificationLifecycleState,
        rerenderChecklistOnReadOnlyChange: Boolean = false,
    ) {
        val readOnlyChanged = isClarificationChecklistReadOnly != state.checklistReadOnly
        clarificationState = state.clarificationState
        activeChecklistDetailIndex = state.activeDetailIndex
        isGeneratingActive = state.isGeneratingActive
        isClarificationGenerating = state.isClarificationGenerating
        if (rerenderChecklistOnReadOnlyChange && readOnlyChanged) {
            setClarificationChecklistReadOnly(state.checklistReadOnly)
        } else {
            isClarificationChecklistReadOnly = state.checklistReadOnly
        }
    }

    fun exitClarificationMode(clearInput: Boolean = false) {
        val lifecyclePlan = SpecDetailClarificationLifecycleCoordinator.exit(
            isEditing = isEditing,
            clearInput = clearInput,
        )
        stopGeneratingAnimation()
        applyClarificationLifecycleState(lifecyclePlan.lifecycleState)
        clarificationQuestionsPane.text = ""
        clarificationPreviewPane.text = ""
        updateInputPlaceholder(currentWorkflow?.currentPhase)
        if (lifecyclePlan.clearInput) {
            inputArea.text = ""
        }
        applyPreviewSurfacePlan(lifecyclePlan.previewSurfacePlan)
        refreshInputAreaMode()
        currentWorkflow?.let { updateButtonStates(it) } ?: actionBarPresenter.disableAll()
    }

    private fun renderClarificationQuestions(
        markdown: String,
        structuredQuestions: List<String>,
        questionDecisions: Map<Int, SpecDetailClarificationQuestionDecision>,
        questionDetails: Map<Int, String>,
    ) {
        if (structuredQuestions.isNotEmpty()) {
            renderChecklistClarificationQuestions(
                structuredQuestions = structuredQuestions,
                questionDecisions = questionDecisions,
                questionDetails = questionDetails,
            )
            clarificationQuestionsCardLayout.show(clarificationQuestionsCardPanel, CLARIFY_QUESTIONS_CARD_CHECKLIST)
            return
        }
        activeChecklistDetailIndex = null
        renderMarkdownClarificationQuestions(markdown)
        clarificationQuestionsCardLayout.show(clarificationQuestionsCardPanel, CLARIFY_QUESTIONS_CARD_MARKDOWN)
    }

    private fun renderMarkdownClarificationQuestions(markdown: String) {
        val content = SpecMarkdownSanitizer.sanitize(markdown).ifBlank {
            SpecCodingBundle.message("spec.workflow.clarify.noQuestions")
        }
        runCatching {
            MarkdownRenderer.render(clarificationQuestionsPane, content)
            clarificationQuestionsPane.caretPosition = 0
        }.onFailure {
            clarificationQuestionsPane.text = content
            clarificationQuestionsPane.caretPosition = 0
        }
    }

    private fun renderChecklistClarificationQuestions(
        structuredQuestions: List<String>,
        questionDecisions: Map<Int, SpecDetailClarificationQuestionDecision>,
        questionDetails: Map<Int, String>,
    ) {
        clarificationChecklistPanel.removeAll()
        val renderPlan = SpecDetailClarificationChecklistRenderCoordinator.buildPlan(
            state = clarificationState,
            structuredQuestions = structuredQuestions,
            questionDecisions = questionDecisions,
            activeDetailIndex = activeChecklistDetailIndex,
        )
        activeChecklistDetailIndex = renderPlan.activeDetailIndex
        val checklistEditable = !isClarificationChecklistReadOnly
        renderPlan.rowPlans.forEachIndexed { rowIndex, rowPlan ->
            clarificationChecklistPanel.add(
                createChecklistQuestionItem(
                    rowPlan = rowPlan,
                    editable = checklistEditable,
                ),
            )
            if (rowIndex < renderPlan.rowPlans.lastIndex) {
                clarificationChecklistPanel.add(Box.createVerticalStrut(JBUI.scale(1)))
            }
        }
        val progress = renderPlan.progress
        val summary = SpecCodingBundle.message(
            "spec.detail.clarify.checklist.progress",
            progress.confirmedCount,
            progress.notApplicableCount,
            progress.totalCount,
        )
        val hint = SpecCodingBundle.message("spec.detail.clarify.checklist.hint")
        clarificationChecklistHintLabel.text = "$summary  ·  $hint"
        clarificationChecklistPanel.revalidate()
        clarificationChecklistPanel.repaint()
    }

    private fun createChecklistQuestionItem(
        rowPlan: SpecDetailClarificationChecklistRowPlan,
        editable: Boolean,
    ): JPanel {
        val index = rowPlan.index
        val decision = rowPlan.decision
        val indicator = JBLabel(rowPlan.indicatorSymbol).apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            foreground = checklistIndicatorForeground(rowPlan.tone)
            border = JBUI.Borders.empty(1, 2, 0, 2)
            cursor = if (editable) {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            } else {
                Cursor.getDefaultCursor()
            }
        }
        val questionText = JTextPane().apply {
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty(0, 2, 0, 2)
            cursor = if (editable) {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            } else {
                Cursor.getDefaultCursor()
            }
        }
        renderChecklistQuestionText(
            target = questionText,
            segments = rowPlan.questionSegments,
            fallbackText = rowPlan.normalizedQuestion,
        )
        val confirmButton = createChecklistChoiceButton(
            text = SpecCodingBundle.message("spec.detail.clarify.checklist.choice.confirm"),
            selected = rowPlan.confirmSelected,
            selectedBackground = CHECKLIST_CONFIRM_BG,
            selectedForeground = CHECKLIST_CONFIRM_TEXT,
            normalBackground = CHECKLIST_CHOICE_BG,
            normalForeground = TREE_FILE_TEXT,
            enabled = editable,
        ) {
            onChecklistQuestionConfirmRequested(index)
        }
        val notApplicableButton = createChecklistChoiceButton(
            text = SpecCodingBundle.message("spec.detail.clarify.checklist.choice.na"),
            selected = rowPlan.notApplicableSelected,
            selectedBackground = CHECKLIST_NA_BG,
            selectedForeground = CHECKLIST_NA_TEXT,
            normalBackground = CHECKLIST_CHOICE_BG,
            normalForeground = TREE_FILE_TEXT,
            enabled = editable,
        ) {
            onChecklistQuestionDecisionChanged(
                index = index,
                decision = rowPlan.notApplicableToggleDecision,
            )
        }
        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(confirmButton)
            add(notApplicableButton)
        }
        val questionHeader = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(indicator, BorderLayout.WEST)
            add(
                JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(questionText, BorderLayout.CENTER)
                    add(actionsPanel, BorderLayout.EAST)
                },
                BorderLayout.CENTER,
            )
        }
        val rowColors = checklistRowColors(rowPlan.tone)
        val row = JPanel(BorderLayout(0, 0)).apply {
            isOpaque = true
            background = rowColors.background
            border = SpecUiStyle.roundedCardBorder(
                lineColor = rowColors.border,
                arc = JBUI.scale(10),
                top = 1,
                left = 8,
                bottom = 1,
                right = 8,
            )
            add(questionHeader, BorderLayout.CENTER)
        }
        val toggleListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                if (!editable) {
                    return
                }
                if (e == null || e.button != MouseEvent.BUTTON1) {
                    return
                }
                onChecklistQuestionRowClicked(index, decision)
            }
        }
        if (editable) {
            row.addMouseListener(toggleListener)
            indicator.addMouseListener(toggleListener)
            questionText.addMouseListener(toggleListener)
        }
        return row
    }

    private fun createChecklistChoiceButton(
        text: String,
        selected: Boolean,
        selectedBackground: Color,
        selectedForeground: Color,
        normalBackground: Color,
        normalForeground: Color,
        enabled: Boolean,
        onClick: () -> Unit,
    ): JButton {
        return JButton(text).apply {
            isFocusable = false
            isFocusPainted = false
            isContentAreaFilled = true
            font = JBUI.Fonts.smallFont()
            margin = JBUI.insets(0, 6, 0, 6)
            background = if (selected) selectedBackground else normalBackground
            foreground = if (selected) selectedForeground else normalForeground
            border = SpecUiStyle.roundedLineBorder(
                if (selected) selectedForeground else CHECKLIST_CHOICE_BORDER,
                JBUI.scale(8),
            )
            SpecUiStyle.applyRoundRect(this, arc = 8)
            preferredSize = JBUI.size(
                maxOf(JBUI.scale(48), getFontMetrics(font).stringWidth(text) + JBUI.scale(20)),
                JBUI.scale(22),
            )
            isEnabled = enabled
            cursor = if (enabled) {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            } else {
                Cursor.getDefaultCursor()
            }
            addActionListener {
                if (isEnabled) {
                    onClick()
                }
            }
        }
    }

    private fun onChecklistQuestionRowClicked(index: Int, fallbackDecision: SpecDetailClarificationQuestionDecision) {
        if (isClarificationChecklistReadOnly) {
            return
        }
        val state = clarificationState ?: return
        when (
            val plan = SpecDetailClarificationChecklistCoordinator.planRowClick(
                state = state,
                activeDetailIndex = activeChecklistDetailIndex,
                index = index,
                fallbackDecision = fallbackDecision,
                text = clarificationText(),
            )
        ) {
            is SpecDetailClarificationChecklistRowClickPlan.Apply -> {
                applyChecklistQuestionMutation(
                    phase = state.phase,
                    result = plan.result,
                )
            }

            is SpecDetailClarificationChecklistRowClickPlan.RequestConfirmDetail -> {
                val confirmedDetail = requestClarificationConfirmDetail(
                    question = plan.request.question,
                    initialDetail = plan.request.initialDetail,
                ) ?: return
                val result = SpecDetailClarificationChecklistCoordinator.applyConfirmedDetail(
                    state = state,
                    activeDetailIndex = activeChecklistDetailIndex,
                    index = index,
                    detail = confirmedDetail,
                    text = clarificationText(),
                ) ?: return
                applyChecklistQuestionMutation(
                    phase = state.phase,
                    result = result,
                )
            }

            null -> return
        }
    }

    private fun renderChecklistQuestionText(
        target: JTextPane,
        segments: List<SpecDetailClarificationInlineSegment>,
        fallbackText: String,
    ) {
        val doc = target.styledDocument
        try {
            doc.remove(0, doc.length)
            val baseFont = JBUI.Fonts.smallFont()
            val normalAttrs = SimpleAttributeSet().apply {
                StyleConstants.setBold(this, false)
                StyleConstants.setFontFamily(this, baseFont.family)
                StyleConstants.setFontSize(this, baseFont.size)
                StyleConstants.setForeground(this, TREE_TEXT)
            }
            val boldAttrs = SimpleAttributeSet(normalAttrs).apply {
                StyleConstants.setBold(this, true)
            }
            val codeAttrs = SimpleAttributeSet(normalAttrs).apply {
                StyleConstants.setFontFamily(this, "JetBrains Mono")
                StyleConstants.setBackground(this, CLARIFY_PREVIEW_QUESTION_CODE_BG)
                StyleConstants.setForeground(this, CLARIFY_PREVIEW_QUESTION_CODE_FG)
            }
            segments.forEach { segment ->
                val attrs = when {
                    segment.inlineCode -> codeAttrs
                    segment.bold -> boldAttrs
                    else -> normalAttrs
                }
                doc.insertString(doc.length, segment.text, attrs)
            }
            target.caretPosition = 0
        } catch (_: BadLocationException) {
            target.text = fallbackText
            target.caretPosition = 0
        }
    }

    private fun checklistIndicatorForeground(tone: SpecDetailClarificationChecklistRowTone): Color {
        return when (tone) {
            SpecDetailClarificationChecklistRowTone.CONFIRMED -> CHECKLIST_CONFIRM_TEXT
            SpecDetailClarificationChecklistRowTone.NOT_APPLICABLE -> CHECKLIST_NA_TEXT
            SpecDetailClarificationChecklistRowTone.DEFAULT -> TREE_FILE_TEXT
        }
    }

    private fun checklistRowColors(tone: SpecDetailClarificationChecklistRowTone): ChecklistRowColors {
        return when (tone) {
            SpecDetailClarificationChecklistRowTone.CONFIRMED -> ChecklistRowColors(
                background = CHECKLIST_ROW_BG_SELECTED,
                border = CHECKLIST_ROW_BORDER_SELECTED,
            )
            SpecDetailClarificationChecklistRowTone.NOT_APPLICABLE -> ChecklistRowColors(
                background = CHECKLIST_ROW_BG_NA,
                border = CHECKLIST_ROW_BORDER_NA,
            )
            SpecDetailClarificationChecklistRowTone.DEFAULT -> ChecklistRowColors(
                background = CHECKLIST_ROW_BG,
                border = CHECKLIST_ROW_BORDER,
            )
        }
    }

    private fun onChecklistQuestionDecisionChanged(index: Int, decision: SpecDetailClarificationQuestionDecision) {
        if (isClarificationChecklistReadOnly) {
            return
        }
        val state = clarificationState ?: return
        val result = SpecDetailClarificationChecklistCoordinator.applyDecision(
            state = state,
            activeDetailIndex = activeChecklistDetailIndex,
            index = index,
            decision = decision,
            text = clarificationText(),
        ) ?: return
        applyChecklistQuestionMutation(
            phase = state.phase,
            result = result,
        )
    }

    private fun onChecklistQuestionConfirmRequested(index: Int) {
        if (isClarificationChecklistReadOnly) {
            return
        }
        val state = clarificationState ?: return
        val request = SpecDetailClarificationChecklistCoordinator.prepareConfirmDetail(
            state = state,
            index = index,
        ) ?: return
        val confirmedDetail = requestClarificationConfirmDetail(
            question = request.question,
            initialDetail = request.initialDetail,
        ) ?: return
        val result = SpecDetailClarificationChecklistCoordinator.applyConfirmedDetail(
            state = state,
            activeDetailIndex = activeChecklistDetailIndex,
            index = index,
            detail = confirmedDetail,
            text = clarificationText(),
        ) ?: return
        applyChecklistQuestionMutation(
            phase = state.phase,
            result = result,
        )
    }

    private fun requestClarificationConfirmDetail(question: String, initialDetail: String): String? {
        if (GraphicsEnvironment.isHeadless()) {
            return initialDetail
        }
        val dialog = ClarificationQuestionConfirmDialog(
            question = question,
            initialDetail = initialDetail,
        )
        return if (dialog.showAndGet()) {
            dialog.confirmedDetail
        } else {
            null
        }
    }

    private fun onChecklistQuestionDetailChanged(index: Int, detail: String) {
        if (isClarificationChecklistReadOnly) {
            return
        }
        val state = clarificationState ?: return
        val result = SpecDetailClarificationChecklistCoordinator.applyConfirmedDetail(
            state = state,
            activeDetailIndex = activeChecklistDetailIndex,
            index = index,
            detail = detail,
            text = clarificationText(),
        ) ?: return
        applyChecklistQuestionMutation(
            phase = state.phase,
            result = result,
        )
    }

    private fun applyChecklistQuestionMutation(
        phase: SpecPhase,
        result: SpecDetailClarificationChecklistMutationResult,
    ) {
        clarificationState = result.state
        activeChecklistDetailIndex = result.activeDetailIndex
        if (result.questionListChanged) {
            renderClarificationQuestions(
                markdown = result.state.questionsMarkdown,
                structuredQuestions = result.state.structuredQuestions,
                questionDecisions = result.state.questionDecisions,
                questionDetails = result.state.questionDetails,
            )
        }
        inputArea.text = result.confirmedContext
        inputArea.caretPosition = 0
        updateClarificationPreview()
        persistClarificationDraftSnapshot(result.state)
        validationBannerPresenter.applyStatus(
            SpecDetailPreviewStatusCoordinator.clarificationHint(currentComposeActionMode(phase)),
        )
        currentWorkflow?.let(::updateButtonStates)
    }

    private fun applyClarificationInputSync(plan: SpecDetailClarificationInputSyncPlan?) {
        val syncPlan = plan ?: return
        inputArea.text = syncPlan.inputText
        inputArea.caretPosition = syncPlan.caretPosition.coerceIn(0, inputArea.text.length)
    }

    private fun clarificationText(): SpecDetailClarificationText {
        return SpecDetailClarificationText(
            confirmedTitle = SpecCodingBundle.message("spec.detail.clarify.confirmed.title"),
            notApplicableTitle = SpecCodingBundle.message("spec.detail.clarify.notApplicable.title"),
            detailPrefix = SpecCodingBundle.message("spec.detail.clarify.checklist.detail.exportPrefix"),
            confirmedSectionMarkers = clarificationConfirmedSectionMarkers(),
            notApplicableSectionMarkers = clarificationNotApplicableSectionMarkers(),
        )
    }

    private fun applyClarificationActionPlan(plan: SpecDetailClarificationActionPlan) {
        when (plan) {
            SpecDetailClarificationActionPlan.Ignore -> Unit
            is SpecDetailClarificationActionPlan.Validation -> {
                validationBannerPresenter.applyPreviewValidation(plan.banner)
            }

            is SpecDetailClarificationActionPlan.Confirm -> {
                setClarificationChecklistReadOnly(plan.setChecklistReadOnly)
                onClarificationConfirm(plan.input, plan.confirmedContext)
            }

            is SpecDetailClarificationActionPlan.Regenerate -> {
                onClarificationRegenerate(plan.input, plan.confirmedContext)
            }

            is SpecDetailClarificationActionPlan.Skip -> {
                onClarificationSkip(plan.input)
            }

            SpecDetailClarificationActionPlan.Cancel -> {
                exitClarificationMode(clearInput = false)
                onClarificationCancel()
            }
        }
    }

    private fun clarificationConfirmedSectionMarkers(): List<String> {
        return listOf(
            SpecCodingBundle.message("spec.detail.clarify.confirmed.title"),
            "Confirmed Clarification Points",
            "已确认澄清项",
        ).map(::normalizeComparableText).filter { it.isNotBlank() }
    }

    private fun clarificationNotApplicableSectionMarkers(): List<String> {
        return listOf(
            SpecCodingBundle.message("spec.detail.clarify.notApplicable.title"),
            "Not Applicable Clarification Points",
            "不适用澄清项",
        ).map(::normalizeComparableText).filter { it.isNotBlank() }
    }

    private fun normalizeComparableText(value: String): String {
        return value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lowercase()
            .replace(Regex("\\s+"), "")
    }

    private fun refreshInputAreaMode() {
        updateInputPlaceholder(currentWorkflow?.currentPhase)
        val checklistMode = clarificationState?.checklistMode == true
        val lockedPhase = currentWorkflow?.let(::currentReadOnlyRevisionLockedPhase)
        val showInputSection = !checklistMode
        if (::inputSectionContainer.isInitialized && inputSectionContainer.isVisible != showInputSection) {
            inputSectionContainer.isVisible = showInputSection
            inputSectionContainer.parent?.revalidate()
            inputSectionContainer.parent?.repaint()
        }
        refreshBottomSplitLayout(showInputSection)
        if (isEditing) {
            inputArea.isEnabled = false
            inputArea.isEditable = false
            inputArea.toolTipText = null
            syncComposerSectionState()
            refreshComposerSourcePanelState()
            refreshActionButtonCursors()
            return
        }
        if (lockedPhase != null) {
            inputArea.isEnabled = false
            inputArea.isEditable = false
            inputArea.toolTipText = SpecCodingBundle.message(
                "spec.detail.revision.locked.input",
                phaseStepperTitle(lockedPhase),
            )
            syncComposerSectionState()
            refreshComposerSourcePanelState()
            refreshActionButtonCursors()
            return
        }
        inputArea.isEnabled = true
        inputArea.isEditable = !checklistMode
        inputArea.toolTipText = if (checklistMode) {
            SpecCodingBundle.message("spec.detail.clarify.input.locked")
        } else {
            null
        }
        syncComposerSectionState()
        refreshComposerSourcePanelState()
        refreshActionButtonCursors()
    }

    private fun refreshComposerSourcePanelState() {
        val state = composerSourceState
        composerSourcePanel.updateState(
            workflowId = state.workflowId,
            assets = state.assets,
            selectedSourceIds = state.selectedSourceIds,
            editable = state.editable && !isEditing && currentWorkflow?.let(::currentReadOnlyRevisionLockedPhase) == null,
        )
    }

    private fun refreshComposerCodeContextPanelState() {
        val state = composerCodeContextState
        composerCodeContextPanel.updateState(
            workflowId = state.workflowId,
            codeContextPack = state.codeContextPack,
        )
    }

    private fun refreshBottomSplitLayout(showInputSection: Boolean) {
        if (!::bottomPanelContainer.isInitialized) {
            return
        }
        val targetTopInset = if (showInputSection) JBUI.scale(8) else JBUI.scale(2)
        bottomPanelContainer.border = JBUI.Borders.emptyTop(targetTopInset)
        isBottomCollapsedForChecklist = !showInputSection
        bottomPanelContainer.revalidate()
        bottomPanelContainer.repaint()
    }

    private fun syncComposerSectionState(forceReset: Boolean = false) {
        if (!::composerSection.isInitialized) {
            return
        }
        val contextKey = buildComposerContextKey()
        if (forceReset || contextKey != composerContextKey) {
            composerContextKey = contextKey
            composerManualOverride = null
            setComposerExpanded(desiredComposerExpanded())
            return
        }

        composerManualOverride?.let(::setComposerExpanded)
    }

    private fun buildComposerContextKey(): String {
        return listOf(
            currentWorkflow?.id.orEmpty(),
            currentWorkflow?.currentPhase?.name.orEmpty(),
            selectedPhase?.name.orEmpty(),
            currentWorkflow?.status?.name.orEmpty(),
            explicitRevisionPhase?.name.orEmpty(),
            isEditing.toString(),
            (clarificationState != null).toString(),
        ).joinToString("|")
    }

    private fun desiredComposerExpanded(): Boolean {
        val workflow = currentWorkflow ?: return false
        if (clarificationState != null || isEditing) {
            return true
        }
        if (workflow.status != WorkflowStatus.IN_PROGRESS) {
            return false
        }
        val activePhase = selectedPhase ?: workflow.currentPhase
        return activePhase == workflow.currentPhase
    }

    private fun setComposerExpanded(expanded: Boolean) {
        isComposerExpanded = expanded
        composerSection.setBodyVisible(expanded)
        refreshComposerSectionLayout()
        refreshCollapsibleToggleTexts()
    }

    private fun refreshComposerSectionLayout() {
        if (!::bottomPanelContainer.isInitialized) {
            return
        }
        bottomPanelContainer.revalidate()
        bottomPanelContainer.repaint()
    }

    private data class ChecklistRowColors(
        val background: Color,
        val border: Color,
    )

    private fun onClarificationInputEdited() {
        updateClarificationPreview()
        persistClarificationDraftSnapshot()
    }

    private fun updateClarificationPreview() {
        val state = clarificationState ?: return
        val plan = SpecDetailClarificationPreviewCoordinator.buildPlan(
            state = state,
            input = inputArea.text,
            text = clarificationText(),
            emptyText = SpecCodingBundle.message("spec.detail.clarify.preview.empty"),
        )
        when (plan) {
            is SpecDetailClarificationPreviewPlan.Markdown -> renderClarificationPreviewMarkdown(plan.content)
            is SpecDetailClarificationPreviewPlan.Checklist -> {
                renderChecklistPreview(
                    SpecDetailClarificationPreviewRenderCoordinator.buildPlan(plan),
                )
            }
        }
    }

    private fun renderClarificationPreviewMarkdown(content: String) {
        runCatching {
            MarkdownRenderer.render(clarificationPreviewPane, content)
            clarificationPreviewPane.caretPosition = 0
        }.onFailure {
            clarificationPreviewPane.text = content
            clarificationPreviewPane.caretPosition = 0
        }
    }

    private fun renderChecklistPreview(plan: SpecDetailClarificationPreviewRenderPlan) {
        runCatching {
            SpecDetailClarificationPreviewDocumentWriter.write(
                doc = clarificationPreviewPane.styledDocument,
                plan = plan,
                palette = clarificationPreviewDocumentPalette(),
            )
            clarificationPreviewPane.caretPosition = 0
        }.onFailure {
            renderClarificationPreviewMarkdown(plan.fallbackMarkdown)
        }
    }

    private fun clarificationPreviewDocumentPalette(): SpecDetailClarificationPreviewDocumentPalette {
        val baseFont = JBUI.Fonts.smallFont()
        return SpecDetailClarificationPreviewDocumentPalette(
            bodyForeground = TREE_TEXT,
            titleForeground = SECTION_TITLE_FG,
            mutedForeground = TREE_FILE_TEXT,
            questionCodeBackground = CLARIFY_PREVIEW_QUESTION_CODE_BG,
            questionCodeForeground = CLARIFY_PREVIEW_QUESTION_CODE_FG,
            detailChipBackground = CLARIFY_PREVIEW_DETAIL_BG,
            detailChipForeground = CLARIFY_PREVIEW_DETAIL_FG,
            baseFontFamily = baseFont.family,
            baseFontSize = baseFont.size,
        )
    }

    private fun persistClarificationDraftSnapshot(state: SpecDetailClarificationFormState? = clarificationState) {
        val snapshot = state ?: return
        val plan = SpecDetailClarificationContextCoordinator.resolveDraftAutosavePlan(
            state = snapshot,
            clarificationInput = inputArea.text,
            clarificationText = clarificationText(),
        )
        onClarificationDraftAutosave(
            plan.input,
            plan.confirmedContext,
            plan.questionsMarkdown,
            plan.structuredQuestions,
        )
    }

    private fun clarificationSectionsLayoutPlan(
        splitPaneHeight: Int = clarificationSplitPane.height,
    ): SpecDetailClarificationSectionsLayoutPlan {
        return SpecDetailClarificationSectionsLayoutCoordinator.buildPlan(
            questionsExpanded = isClarificationQuestionsExpanded,
            previewExpanded = isClarificationPreviewExpanded,
            previewContentVisible = isClarificationPreviewContentVisible,
            splitPaneHeight = splitPaneHeight,
            expandedResizeWeight = 0.58,
            expandedDividerSize = JBUI.scale(4),
            collapsedSectionHeight = JBUI.scale(36),
        )
    }

    private fun refreshClarificationSectionsLayout() {
        if (!::clarificationSplitPane.isInitialized) {
            return
        }
        val layoutPlan = clarificationSectionsLayoutPlan()
        if (::clarificationQuestionsSection.isInitialized) {
            clarificationQuestionsSection.setBodyVisible(layoutPlan.questionsBodyVisible)
            clarificationQuestionsSection.applyToggleState(
                expanded = layoutPlan.questionsToggle.expanded,
                enabled = layoutPlan.questionsToggle.enabled,
            )
        }
        if (::clarificationPreviewSection.isInitialized) {
            clarificationPreviewSection.setBodyVisible(layoutPlan.previewBodyVisible)
            clarificationPreviewSection.applyToggleState(
                expanded = layoutPlan.previewToggle.expanded,
                enabled = layoutPlan.previewToggle.enabled,
            )
        }
        if (layoutPlan.previewSectionVisible) {
            clarificationPreviewSection.root.isVisible = true
            if (layoutPlan.attachPreviewSection && clarificationSplitPane.bottomComponent == null) {
                clarificationSplitPane.bottomComponent = clarificationPreviewSection.root
            }
            clarificationSplitPane.resizeWeight = layoutPlan.resizeWeight
            clarificationSplitPane.dividerSize = layoutPlan.dividerSize
            SwingUtilities.invokeLater {
                if (!::clarificationSplitPane.isInitialized) {
                    return@invokeLater
                }
                clarificationSectionsLayoutPlan(
                    splitPaneHeight = clarificationSplitPane.height,
                ).dividerLocation?.let { target ->
                    clarificationSplitPane.dividerLocation = target
                    clarificationSplitPane.revalidate()
                    clarificationSplitPane.repaint()
                }
            }
        } else {
            clarificationPreviewSection.root.isVisible = false
            clarificationSplitPane.bottomComponent = null
            clarificationSplitPane.resizeWeight = layoutPlan.resizeWeight
            clarificationSplitPane.dividerSize = layoutPlan.dividerSize
        }
        clarificationSplitPane.revalidate()
        clarificationSplitPane.repaint()
    }

    private fun setClarificationPreviewVisible(visible: Boolean) {
        isClarificationPreviewContentVisible = visible
        refreshClarificationSectionsLayout()
    }

    private fun applyProcessTimelineCollapseState() {
        if (!::processTimelineSection.isInitialized) {
            return
        }
        processTimelineSection.setBodyVisible(isProcessTimelineExpanded)
        processTimelineSection.applyToggleState(
            expanded = isProcessTimelineExpanded,
            enabled = true,
        )
        processTimelineSection.root.revalidate()
        processTimelineSection.root.repaint()
    }

    private fun setProcessTimelineVisible(visible: Boolean) {
        if (!::processTimelineSection.isInitialized) {
            return
        }
        processTimelineSection.root.isVisible = visible
        if (visible) {
            applyProcessTimelineCollapseState()
        }
        processTimelineSection.root.revalidate()
        processTimelineSection.root.repaint()
    }

    private fun renderProcessTimeline() {
        if (!processTimelineModel.visible) {
            processTimelinePane.text = ""
            setProcessTimelineVisible(false)
            return
        }
        setProcessTimelineVisible(true)
        runCatching {
            MarkdownRenderer.render(processTimelinePane, processTimelineModel.markdown)
            processTimelinePane.caretPosition = 0
        }.onFailure {
            processTimelinePane.text = processTimelineModel.plainText
            processTimelinePane.caretPosition = 0
        }
    }

    fun showGenerating(progress: Double) {
        generatingPercent = (progress * 100).toInt().coerceIn(0, 100)
        isClarificationGenerating = false
        isGeneratingActive = true
        startGeneratingAnimation()
        updateGeneratingLabel()
        currentWorkflow?.let { updateButtonStates(it) }
    }

    fun showGenerationFailed() {
        val restorePlan = SpecDetailClarificationLifecycleCoordinator.restoreAfterFailure(
            state = currentClarificationLifecycleState(),
            workflow = currentWorkflow,
            selectedPhase = selectedPhase,
        )
        stopGeneratingAnimation()
        applyClarificationLifecycleState(
            restorePlan.lifecycleState,
            rerenderChecklistOnReadOnlyChange = true,
        )
        val workflow = currentWorkflow
        if (workflow == null) {
            validationBannerPresenter.applyStatus(restorePlan.statusPlan)
            return
        }
        updateButtonStates(workflow)
        if (restorePlan.restoreClarificationPreview) {
            applyPreviewSurfacePlan(requireNotNull(restorePlan.previewSurfacePlan))
            updateClarificationPreview()
        } else if (restorePlan.restoreDocumentPhase != null) {
            showDocumentPreview(restorePlan.restoreDocumentPhase, keepGeneratingIndicator = false)
        }
    }

    fun showValidationFailureInteractive(
        phase: SpecPhase,
        validation: ValidationResult,
    ) {
        applyClarificationLifecycleState(
            SpecDetailClarificationLifecycleCoordinator.stopGenerating(
                state = currentClarificationLifecycleState(),
            ),
            rerenderChecklistOnReadOnlyChange = true,
        )
        stopGeneratingAnimation()
        previewContentPresenter.apply(
            SpecDetailPreviewContentCoordinator.forValidationFailure(
                markdownContent = buildValidationPreviewMarkdown(phase, validation),
                validationMessage = buildValidationFailureLabel(validation),
            ),
        )
        applyPreviewSurfacePlan(
            SpecDetailPreviewSurfaceCoordinator.forPreview(
                hasClarificationState = clarificationState != null,
                isClarificationGenerating = isClarificationGenerating,
            ),
        )
        if (inputArea.text.isBlank()) {
            inputArea.text = buildValidationRepairTemplate(validation)
            inputArea.caretPosition = inputArea.text.length
        }
        currentWorkflow?.let { updateButtonStates(it) } ?: actionBarPresenter.disableAll()
    }

    private fun rebuildTree(workflow: SpecWorkflow) {
        treeRoot.removeAllChildren()
        for (phase in SpecPhase.entries) {
            val doc = workflow.documents[phase]
            val node = PhaseNode(phase, doc)
            treeRoot.add(DefaultMutableTreeNode(node))
        }
        treeModel.reload()
        documentTree.expandRow(0)
    }

    private fun showDocumentPreview(phase: SpecPhase, keepGeneratingIndicator: Boolean = true) {
        val workflow = currentWorkflow ?: return
        if (!keepGeneratingIndicator || !isGeneratingActive) {
            stopGeneratingAnimation()
        }
        previewContentPresenter.apply(
            SpecDetailPreviewContentCoordinator.forDocumentPreview(
                workflow = workflow,
                phase = phase,
                workbenchArtifactBinding = workbenchArtifactBinding,
                isGeneratingActive = isGeneratingActive,
                keepGeneratingIndicator = keepGeneratingIndicator,
                revisionLockedPhase = currentReadOnlyRevisionLockedPhase(workflow),
                isEditing = isEditing,
            ),
        )
    }

    private fun buildValidationIssuesMarkdown(
        phase: SpecPhase,
        validation: ValidationResult,
    ): String {
        return buildString {
            appendLine("## ${SpecCodingBundle.message("spec.detail.validation.issues.title", phase.displayName)}")
            appendLine()
            if (validation.errors.isNotEmpty()) {
                appendLine("### ${SpecCodingBundle.message("spec.detail.validation.issues.errors")}")
                validation.errors.forEach { appendLine("- $it") }
                appendLine()
            }
            if (validation.warnings.isNotEmpty()) {
                appendLine("### ${SpecCodingBundle.message("spec.detail.validation.issues.warnings")}")
                validation.warnings.forEach { appendLine("- $it") }
                appendLine()
            }
            if (validation.suggestions.isNotEmpty()) {
                appendLine("### ${SpecCodingBundle.message("spec.detail.validation.issues.suggestions")}")
                validation.suggestions.forEach { appendLine("- $it") }
                appendLine()
            }
            appendLine(SpecCodingBundle.message("spec.detail.validation.issues.next"))
        }.trimEnd()
    }

    private fun buildValidationPreviewMarkdown(
        phase: SpecPhase,
        validation: ValidationResult,
    ): String {
        val documentContent = currentWorkflow
            ?.documents
            ?.get(phase)
            ?.content
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            .orEmpty()
        val issuesMarkdown = buildValidationIssuesMarkdown(phase, validation)
        if (documentContent.isBlank()) {
            return issuesMarkdown
        }
        return buildString {
            appendLine(documentContent)
            appendLine()
            appendLine("---")
            appendLine()
            append(issuesMarkdown)
        }.trim()
    }

    private fun buildValidationFailureLabel(validation: ValidationResult): String {
        val firstError = validation.errors.firstOrNull()
        if (firstError.isNullOrBlank()) {
            return SpecCodingBundle.message("spec.detail.validation.failed")
        }
        return if (validation.errors.size > 1) {
            SpecCodingBundle.message(
                "spec.detail.validation.failed.withMore",
                firstError,
                validation.errors.size - 1,
            )
        } else {
            SpecCodingBundle.message("spec.detail.validation.failed.withReason", firstError)
        }
    }

    private fun buildValidationRepairTemplate(validation: ValidationResult): String {
        val issues = validation.errors
            .ifEmpty { listOf(SpecCodingBundle.message("common.unknown")) }
            .joinToString("\n") { "- $it" }
        return SpecCodingBundle.message("spec.detail.validation.repair.template", issues)
    }

    private fun startGeneratingAnimation() {
        if (generationAnimationTimer != null) return
        generationAnimationTimer = Timer(220) {
            generatingFrameIndex = (generatingFrameIndex + 1) % GENERATING_FRAMES.size
            updateGeneratingLabel()
        }.apply {
            isRepeats = true
            start()
        }
    }

    private fun stopGeneratingAnimation() {
        generationAnimationTimer?.stop()
        generationAnimationTimer = null
        generatingFrameIndex = 0
    }

    private fun updateGeneratingLabel() {
        val frame = GENERATING_FRAMES[generatingFrameIndex]
        validationBannerPresenter.applyStatus(
            SpecDetailPreviewStatusCoordinator.generating(
                mode = currentComposeActionMode(),
                progressPercent = generatingPercent,
                frame = frame,
                isClarificationGenerating = isClarificationGenerating,
            ),
        )
    }

    private fun updateButtonStates(workflow: SpecWorkflow) {
        refreshActionButtonPresentation(workflow)
        val actionState = SpecDetailPanelActionCoordinator.resolve(
            workflow = workflow,
            composeMode = currentComposeActionMode(),
            viewState = resolveDetailViewState(workflow),
            isEditing = isEditing,
            clarificationLifecycleState = currentClarificationLifecycleState(),
            revisionLockedDisabledReason = ::revisionLockedDisabledReason,
        )
        actionBarPresenter.apply(actionState)
        refreshInputAreaMode()
    }

    private fun resolveDetailViewState(workflow: SpecWorkflow): SpecDetailPanelViewState {
        return SpecDetailPanelViewState.resolve(
            workflow = workflow,
            selectedPhase = selectedPhase,
            preferredWorkbenchPhase = preferredWorkbenchPhase,
            explicitRevisionPhase = explicitRevisionPhase,
            workbenchArtifactBinding = workbenchArtifactBinding,
        )
    }

    fun clearInput() {
        if (clarificationState?.structuredQuestions?.isNotEmpty() == true) {
            applyClarificationInputSync(
                SpecDetailClarificationContextCoordinator.resolveInputSyncPlan(
                    state = clarificationState,
                    clarificationText = clarificationText(),
                ),
            )
            updateClarificationPreview()
            return
        }
        inputArea.text = ""
        inputArea.caretPosition = 0
        if (clarificationState != null) {
            updateClarificationPreview()
        }
    }

    internal fun currentPreviewTextForTest(): String {
        return previewPanePresenter.currentSourceText()
    }

    internal fun currentClarificationPreviewTextForTest(): String {
        return clarificationPreviewPane.text
    }

    internal fun currentValidationTextForTest(): String {
        return validationLabel.text
    }

    internal fun composerContainerPreferredHeightForTest(): Int {
        if (!::bottomPanelContainer.isInitialized) {
            return 0
        }
        return bottomPanelContainer.parent?.preferredSize?.height ?: 0
    }

    internal fun composerContainerMaximumHeightForTest(): Int {
        if (!::bottomPanelContainer.isInitialized) {
            return 0
        }
        return bottomPanelContainer.parent?.maximumSize?.height ?: 0
    }

    internal fun isValidationBannerVisibleForTest(): Boolean {
        return ::validationBannerPanel.isInitialized && validationBannerPanel.isVisible
    }

    internal fun currentDocumentMetaTextForTest(): String {
        return ""
    }

    internal fun currentInputTextForTest(): String {
        return inputArea.text
    }

    internal fun currentInputPlaceholderForTest(): String {
        return inputArea.emptyText.text.orEmpty()
    }

    internal fun documentViewportPreferredHeightForTest(): Int {
        return previewCardPanel.preferredSize.height
    }

    internal fun documentViewportMinimumHeightForTest(): Int {
        return previewCardPanel.minimumSize.height
    }

    internal fun setInputTextForTest(text: String) {
        inputArea.text = text
        inputArea.caretPosition = inputArea.text.length
    }

    internal fun selectPhaseForTest(phase: SpecPhase) {
        val workflow = currentWorkflow ?: return
        updateTreeSelection(phase)
        showDocumentPreview(phase, keepGeneratingIndicator = false)
        updateButtonStates(workflow)
    }

    internal fun selectedPhaseNameForTest(): String? = selectedPhase?.name

    internal fun togglePreviewChecklistForTest(lineIndex: Int) {
        previewPanePresenter.toggleLine(lineIndex)
    }

    internal fun areDocumentTabsVisibleForTest(): Boolean = false

    internal fun toggleClarificationQuestionForTest(index: Int) {
        val currentDecision = clarificationState
            ?.questionDecisions
            ?.get(index)
            ?: SpecDetailClarificationQuestionDecision.UNDECIDED
        val nextDecision = if (currentDecision == SpecDetailClarificationQuestionDecision.CONFIRMED) {
            SpecDetailClarificationQuestionDecision.UNDECIDED
        } else {
            SpecDetailClarificationQuestionDecision.CONFIRMED
        }
        onChecklistQuestionDecisionChanged(index, nextDecision)
    }

    internal fun markClarificationQuestionNotApplicableForTest(index: Int) {
        val currentDecision = clarificationState
            ?.questionDecisions
            ?.get(index)
            ?: SpecDetailClarificationQuestionDecision.UNDECIDED
        val nextDecision = if (currentDecision == SpecDetailClarificationQuestionDecision.NOT_APPLICABLE) {
            SpecDetailClarificationQuestionDecision.UNDECIDED
        } else {
            SpecDetailClarificationQuestionDecision.NOT_APPLICABLE
        }
        onChecklistQuestionDecisionChanged(index, nextDecision)
    }

    internal fun currentChecklistDecisionForTest(index: Int): String? {
        val state = clarificationState ?: return null
        return (state.questionDecisions[index] ?: SpecDetailClarificationQuestionDecision.UNDECIDED).name
    }

    internal fun currentChecklistDetailForTest(index: Int): String? {
        return clarificationState?.questionDetails?.get(index)
    }

    internal fun setClarificationQuestionDetailForTest(index: Int, detail: String) {
        onChecklistQuestionDetailChanged(index, detail)
    }

    internal fun activeChecklistDetailIndexForTest(): Int? {
        return activeChecklistDetailIndex
    }

    internal fun isClarificationChecklistReadOnlyForTest(): Boolean {
        return isClarificationChecklistReadOnly
    }

    internal fun isInputEditableForTest(): Boolean {
        return inputArea.isEditable
    }

    internal fun isInputSectionVisibleForTest(): Boolean {
        return ::inputSectionContainer.isInitialized && inputSectionContainer.isVisible
    }

    internal fun isBottomCollapsedForChecklistForTest(): Boolean {
        return isBottomCollapsedForChecklist
    }

    internal fun toggleComposerExpandedForTest() {
        if (::composerSection.isInitialized) {
            composerSection.clickToggleForTest()
        }
    }

    internal fun isComposerExpandedForTest(): Boolean {
        return isComposerExpanded
    }

    internal fun composerSourceChipLabelsForTest(): List<String> {
        return composerSourcePanel.selectedSourceLabelsForTest()
    }

    internal fun composerSourceMetaTextForTest(): String {
        return composerSourcePanel.metaTextForTest()
    }

    internal fun composerSourceHintTextForTest(): String {
        return composerSourcePanel.hintTextForTest()
    }

    internal fun isComposerSourceRestoreVisibleForTest(): Boolean {
        return composerSourcePanel.isRestoreVisibleForTest()
    }

    internal fun clickAddWorkflowSourcesForTest() {
        composerSourcePanel.clickAddForTest()
    }

    internal fun clickRestoreWorkflowSourcesForTest() {
        composerSourcePanel.clickRestoreForTest()
    }

    internal fun clickRemoveWorkflowSourceForTest(sourceId: String): Boolean {
        return composerSourcePanel.clickRemoveForTest(sourceId)
    }

    internal fun composerCodeContextSummaryChipLabelsForTest(): List<String> {
        return composerCodeContextPanel.summaryChipLabelsForTest()
    }

    internal fun composerCodeContextCandidateLabelsForTest(): List<String> {
        return composerCodeContextPanel.candidateFileLabelsForTest()
    }

    internal fun composerCodeContextMetaTextForTest(): String {
        return composerCodeContextPanel.metaTextForTest()
    }

    internal fun composerCodeContextHintTextForTest(): String {
        return composerCodeContextPanel.hintTextForTest()
    }

    internal fun toggleProcessTimelineExpandedForTest() {
        if (::processTimelineSection.isInitialized) {
            processTimelineSection.clickToggleForTest()
        }
    }

    internal fun toggleClarificationQuestionsExpandedForTest() {
        if (::clarificationQuestionsSection.isInitialized) {
            clarificationQuestionsSection.clickToggleForTest()
        }
    }

    internal fun toggleClarificationPreviewExpandedForTest() {
        if (::clarificationPreviewSection.isInitialized) {
            clarificationPreviewSection.clickToggleForTest()
        }
    }

    internal fun isProcessTimelineExpandedForTest(): Boolean {
        return isProcessTimelineExpanded
    }

    internal fun isClarificationQuestionsExpandedForTest(): Boolean {
        return isClarificationQuestionsExpanded
    }

    internal fun isClarificationPreviewExpandedForTest(): Boolean {
        return isClarificationPreviewExpanded
    }

    internal fun clarificationQuestionsToggleTextForTest(): String {
        return if (::clarificationQuestionsSection.isInitialized) {
            clarificationQuestionsSection.toggleTextForTest()
        } else {
            ""
        }
    }

    internal fun clarificationQuestionsToggleHasEnoughWidthForTest(): Boolean {
        return ::clarificationQuestionsSection.isInitialized &&
            clarificationQuestionsSection.toggleHasEnoughWidthForTest()
    }

    internal fun clarificationQuestionsToggleCanFitTextForTest(text: String): Boolean {
        return ::clarificationQuestionsSection.isInitialized &&
            clarificationQuestionsSection.toggleCanFitTextForTest(text)
    }

    internal fun isComposerToggleEnabledForTest(): Boolean {
        return ::composerSection.isInitialized && composerSection.isToggleEnabledForTest()
    }

    internal fun clickGenerateForTest() {
        actionBarButtons.generate.doClick()
    }

    internal fun clickEditForTest() {
        actionBarButtons.edit.doClick()
    }

    internal fun clickCancelEditForTest() {
        actionBarButtons.cancelEdit.doClick()
    }

    internal fun clickConfirmGenerateForTest() {
        actionBarButtons.confirmGenerate.doClick()
    }

    internal fun clickRegenerateClarificationForTest() {
        actionBarButtons.regenerateClarification.doClick()
    }

    internal fun clickSkipClarificationForTest() {
        actionBarButtons.skipClarification.doClick()
    }

    internal fun clickCancelClarificationForTest() {
        actionBarButtons.cancelClarification.doClick()
    }

    internal fun clickOpenEditorForTest() {
        actionBarButtons.openEditor.doClick()
    }

    internal fun isClarificationPreviewVisibleForTest(): Boolean {
        return isClarificationPreviewContentVisible
    }

    internal fun currentPreviewCardForTest(): String = activePreviewCard.name

    internal fun isClarifyingForTest(): Boolean = clarificationState != null

    internal fun clarificationQuestionsTextForTest(): String = clarificationQuestionsPane.text

    internal fun currentProcessTimelineTextForTest(): String {
        return processTimelinePane.text
    }

    internal fun isProcessTimelineVisibleForTest(): Boolean {
        return ::processTimelineSection.isInitialized && processTimelineSection.root.isVisible
    }

    internal fun hasLegacyDocumentModeButtonsForTest(): Boolean {
        val legacyLabels = setOf(
            SpecCodingBundle.message("spec.detail.view.preview"),
            SpecCodingBundle.message("spec.detail.view.clarify"),
        )
        return collectButtonTexts(this).any(legacyLabels::contains)
    }

    internal fun buttonStatesForTest(): Map<String, Any> {
        return actionBarButtons.stateSnapshotForTest() + mapOf(
            "inputEnabled" to inputArea.isEnabled,
            "inputEditable" to inputArea.isEditable,
            "inputTooltip" to inputArea.toolTipText.orEmpty(),
        )
    }

    internal fun documentToolbarActionCountForTest(): Int {
        return 0
    }

    internal fun visibleComposerActionOrderForTest(): List<String> {
        if (!::actionButtonPanel.isInitialized) {
            return emptyList()
        }
        return actionBarButtons.visibleComposerActionOrder(actionButtonPanel.components.asList())
    }

    private fun collectButtonTexts(component: Component): List<String> {
        val texts = mutableListOf<String>()
        if (component is JButton) {
            val text = component.text?.trim().orEmpty()
            if (text.isNotEmpty()) {
                texts += text
            }
        }
        if (component is java.awt.Container) {
            component.components.forEach { child ->
                texts += collectButtonTexts(child)
            }
        }
        return texts
    }

    private data class PhaseNode(val phase: SpecPhase, val document: SpecDocument?) {
        override fun toString(): String {
            val status = when {
                document?.validationResult?.valid == true -> SpecCodingBundle.message("spec.detail.tree.status.done")
                document != null -> SpecCodingBundle.message("spec.detail.tree.status.draft")
                else -> ""
            }
            return if (status.isBlank()) {
                SpecCodingBundle.message("spec.detail.tree.node.noStatus", phase.displayName.lowercase(), phase.outputFileName)
            } else {
                SpecCodingBundle.message("spec.detail.tree.node.withStatus", phase.displayName.lowercase(), phase.outputFileName, status)
            }
        }
    }

    private inner class PhaseTreeCellRenderer : JPanel(BorderLayout()), TreeCellRenderer {
        private val accentStrip = JPanel()
        private val headerRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        private val phaseLabel = JBLabel()
        private val statusInlineLabel = JBLabel()
        private val fileLabel = JBLabel()

        init {
            isOpaque = true
            accentStrip.isOpaque = true
            accentStrip.preferredSize = JBUI.size(2, 0)
            accentStrip.minimumSize = JBUI.size(2, 0)

            phaseLabel.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            phaseLabel.border = JBUI.Borders.empty()
            statusInlineLabel.font = JBUI.Fonts.smallFont().deriveFont(Font.PLAIN)
            statusInlineLabel.isOpaque = false
            fileLabel.font = JBUI.Fonts.smallFont()

            headerRow.isOpaque = false
            headerRow.add(phaseLabel)
            headerRow.add(statusInlineLabel)

            val content = JPanel(BorderLayout(0, JBUI.scale(3))).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 12, 6, 12)
                add(headerRow, BorderLayout.NORTH)
                add(fileLabel, BorderLayout.CENTER)
            }

            add(accentStrip, BorderLayout.WEST)
            add(content, BorderLayout.CENTER)
        }

        override fun getTreeCellRendererComponent(
            tree: JTree?,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ): Component {
            val phaseNode = (value as? DefaultMutableTreeNode)?.userObject as? PhaseNode
            if (phaseNode == null) {
                border = JBUI.Borders.empty()
                background = TREE_ROW_BG_NEUTRAL
                phaseLabel.text = ""
                fileLabel.text = ""
                statusInlineLabel.text = ""
                return this
            }

            val phase = phaseNode.phase
            val document = phaseNode.document
            val status = when {
                document?.validationResult?.valid == true -> PhaseDocStatus.DONE
                document != null -> PhaseDocStatus.DRAFT
                else -> PhaseDocStatus.PENDING
            }
            val badgeColor = phaseBadgeColor(phase, selected)

            phaseLabel.text = phase.displayName.lowercase()
            phaseLabel.foreground = badgeColor
            statusInlineLabel.text = "· ${status.badgeText}"
            statusInlineLabel.foreground = statusTextColor(status, selected)
            fileLabel.text = phase.outputFileName
            fileLabel.foreground = if (selected) TREE_FILE_TEXT_SELECTED else TREE_FILE_TEXT

            accentStrip.background = badgeColor
            background = phaseRowBackground(phase, selected)
            border = SpecUiStyle.roundedCardBorder(
                lineColor = phaseRowBorder(phase, selected),
                arc = JBUI.scale(10),
                top = 1,
                left = 1,
                bottom = 1,
                right = 1,
            )
            return this
        }
    }

    private enum class PhaseDocStatus(
        val badgeTextKey: String,
    ) {
        DONE(
            badgeTextKey = "spec.detail.tree.badge.done",
        ),
        DRAFT(
            badgeTextKey = "spec.detail.tree.badge.draft",
        ),
        PENDING(
            badgeTextKey = "spec.detail.tree.badge.pending",
        ),
        ;

        val badgeText: String
            get() = SpecCodingBundle.message(badgeTextKey)
    }

    private fun phaseBadgeColor(phase: SpecPhase, selected: Boolean): Color {
        return when (phase) {
            SpecPhase.SPECIFY -> if (selected) TREE_PHASE_SPECIFY_SELECTED else TREE_PHASE_SPECIFY
            SpecPhase.DESIGN -> if (selected) TREE_PHASE_DESIGN_SELECTED else TREE_PHASE_DESIGN
            SpecPhase.IMPLEMENT -> if (selected) TREE_PHASE_IMPLEMENT_SELECTED else TREE_PHASE_IMPLEMENT
        }
    }

    private fun phaseRowBackground(phase: SpecPhase, selected: Boolean): Color {
        return when (phase) {
            SpecPhase.SPECIFY -> if (selected) TREE_ROW_SPECIFY_BG_SELECTED else TREE_ROW_SPECIFY_BG
            SpecPhase.DESIGN -> if (selected) TREE_ROW_DESIGN_BG_SELECTED else TREE_ROW_DESIGN_BG
            SpecPhase.IMPLEMENT -> if (selected) TREE_ROW_IMPLEMENT_BG_SELECTED else TREE_ROW_IMPLEMENT_BG
        }
    }

    private fun phaseRowBorder(phase: SpecPhase, selected: Boolean): Color {
        return when (phase) {
            SpecPhase.SPECIFY -> if (selected) TREE_ROW_SPECIFY_BORDER_SELECTED else TREE_ROW_SPECIFY_BORDER
            SpecPhase.DESIGN -> if (selected) TREE_ROW_DESIGN_BORDER_SELECTED else TREE_ROW_DESIGN_BORDER
            SpecPhase.IMPLEMENT -> if (selected) TREE_ROW_IMPLEMENT_BORDER_SELECTED else TREE_ROW_IMPLEMENT_BORDER
        }
    }

    private fun statusTextColor(status: PhaseDocStatus, selected: Boolean): Color {
        return when (status) {
            PhaseDocStatus.DONE -> if (selected) TREE_STATUS_DONE_TEXT_SELECTED else TREE_STATUS_DONE_TEXT
            PhaseDocStatus.DRAFT -> if (selected) TREE_STATUS_DRAFT_TEXT_SELECTED else TREE_STATUS_DRAFT_TEXT
            PhaseDocStatus.PENDING -> if (selected) TREE_STATUS_PENDING_TEXT_SELECTED else TREE_STATUS_PENDING_TEXT
        }
    }

    companion object {
        private val GENERATING_FRAMES = listOf("◐", "◓", "◑", "◒")
        private val PANEL_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val PANEL_BORDER = JBColor(Color(205, 216, 234), Color(84, 92, 106))
        private val TREE_SECTION_BG = JBColor(Color(246, 249, 253), Color(60, 67, 78))
        private val TREE_SECTION_BORDER = JBColor(Color(214, 223, 236), Color(92, 103, 121))
        private val STEPPER_CHIP_TRACK_BG = JBColor(Color(246, 248, 251), Color(58, 65, 75))
        private val STEPPER_CHIP_TRACK_BORDER = JBColor(Color(200, 210, 223), Color(101, 113, 130))
        private val STEPPER_CHIP_DIVIDER = JBColor(Color(202, 213, 228), Color(90, 102, 120))
        private val STEPPER_CHIP_BASELINE = JBColor(Color(165, 178, 196), Color(116, 132, 154))
        private val STEPPER_CHIP_BASELINE_ACTIVE_START = JBColor(Color(73, 124, 191), Color(127, 167, 218))
        private val STEPPER_CHIP_BASELINE_ACTIVE_END = JBColor(Color(100, 149, 212), Color(149, 186, 232))
        private val STEPPER_CHIP_GLOW = JBColor(Color(104, 149, 210), Color(108, 150, 209))
        private val STEPPER_CHIP_BG_CURRENT = JBColor(Color(236, 243, 252), Color(77, 94, 118))
        private val STEPPER_CHIP_BG_SELECTED = JBColor(Color(242, 246, 251), Color(68, 79, 95))
        private val STEPPER_CHIP_BG_DONE = JBColor(Color(244, 248, 252), Color(64, 74, 88))
        private val STEPPER_CHIP_BG_HOVER = JBColor(Color(239, 245, 251), Color(72, 85, 102))
        private val STEPPER_CHIP_BG_PENDING = JBColor(Color(247, 250, 253), Color(60, 70, 84))
        private val STEPPER_CHIP_TEXT_CURRENT = JBColor(Color(36, 63, 99), Color(219, 230, 244))
        private val STEPPER_CHIP_TEXT_SELECTED = JBColor(Color(59, 80, 110), Color(206, 218, 234))
        private val STEPPER_CHIP_TEXT_DONE = JBColor(Color(86, 101, 122), Color(184, 196, 213))
        private val STEPPER_CHIP_TEXT_HOVER = JBColor(Color(53, 79, 113), Color(212, 223, 239))
        private val STEPPER_CHIP_TEXT_PENDING = JBColor(Color(111, 125, 145), Color(166, 178, 196))
        private val PREVIEW_COLUMN_BG = JBColor(Color(244, 249, 255), Color(55, 61, 71))
        private val PREVIEW_SECTION_BG = JBColor(Color(250, 253, 255), Color(49, 55, 64))
        private val PREVIEW_SECTION_BORDER = JBColor(Color(204, 217, 236), Color(83, 93, 109))
        private val COMPOSER_CARD_BG = JBColor(Color(241, 246, 253), Color(57, 64, 74))
        private val COMPOSER_CARD_BORDER = JBColor(Color(196, 210, 229), Color(86, 97, 113))
        private val COMPOSER_EDITOR_BG = JBColor(Color(251, 253, 255), Color(50, 57, 66))
        private val COMPOSER_EDITOR_BORDER = JBColor(Color(205, 217, 234), Color(79, 90, 105))
        private val COMPOSER_FOOTER_DIVIDER = JBColor(Color(211, 222, 237), Color(84, 95, 111))
        private val PROCESS_SECTION_BG = JBColor(Color(246, 251, 255), Color(55, 62, 73))
        private val PROCESS_SECTION_BORDER = JBColor(Color(199, 215, 237), Color(90, 101, 118))
        private val CLARIFICATION_CARD_BG = JBColor(Color(244, 249, 255), Color(56, 62, 72))
        private val CLARIFICATION_QUESTIONS_BG = JBColor(Color(249, 252, 255), Color(50, 56, 65))
        private val CLARIFICATION_QUESTIONS_BORDER = JBColor(Color(203, 216, 235), Color(84, 95, 110))
        private val CHECKLIST_ROW_BG = JBColor(Color(247, 251, 255), Color(59, 66, 76))
        private val CHECKLIST_ROW_BG_SELECTED = JBColor(Color(234, 244, 255), Color(73, 88, 109))
        private val CHECKLIST_ROW_BG_NA = JBColor(Color(250, 248, 243), Color(77, 74, 69))
        private val CHECKLIST_ROW_BORDER = JBColor(Color(210, 221, 238), Color(96, 108, 126))
        private val CHECKLIST_ROW_BORDER_SELECTED = JBColor(Color(159, 187, 224), Color(126, 152, 182))
        private val CHECKLIST_ROW_BORDER_NA = JBColor(Color(223, 210, 192), Color(126, 120, 110))
        private val CHECKLIST_CONFIRM_BG = JBColor(Color(223, 238, 255), Color(74, 95, 120))
        private val CHECKLIST_CONFIRM_TEXT = JBColor(Color(47, 91, 148), Color(192, 219, 255))
        private val CHECKLIST_NA_BG = JBColor(Color(243, 235, 223), Color(94, 88, 78))
        private val CHECKLIST_NA_TEXT = JBColor(Color(128, 101, 66), Color(232, 204, 166))
        private val CHECKLIST_CHOICE_BG = JBColor(Color(245, 249, 255), Color(68, 75, 86))
        private val CHECKLIST_CHOICE_BORDER = JBColor(Color(194, 207, 228), Color(103, 114, 130))
        private val CHECKLIST_DETAIL_BG = JBColor(Color(248, 252, 255), Color(64, 71, 81))
        private val CHECKLIST_DETAIL_BORDER = JBColor(Color(198, 211, 230), Color(98, 110, 127))
        private val CLARIFICATION_PREVIEW_BG = JBColor(Color(242, 248, 255), Color(60, 67, 78))
        private val CLARIFICATION_PREVIEW_BORDER = JBColor(Color(194, 210, 233), Color(92, 104, 121))
        private val CLARIFY_PREVIEW_QUESTION_CODE_BG = JBColor(Color(230, 239, 252), Color(73, 84, 101))
        private val CLARIFY_PREVIEW_QUESTION_CODE_FG = JBColor(Color(45, 74, 118), Color(206, 220, 240))
        private val CLARIFY_PREVIEW_DETAIL_BG = JBColor(Color(220, 236, 255), Color(84, 100, 123))
        private val CLARIFY_PREVIEW_DETAIL_FG = JBColor(Color(42, 70, 113), Color(222, 232, 246))
        private val STATUS_BG = JBColor(Color(235, 244, 255), Color(62, 68, 80))
        private val STATUS_BORDER = JBColor(Color(183, 199, 224), Color(98, 109, 125))
        private val GENERATING_FG = JBColor(Color(46, 90, 162), Color(171, 201, 248))
        private val TREE_TEXT = JBColor(Color(34, 54, 88), Color(214, 224, 238))
        private val TREE_ROW_BG_NEUTRAL = JBColor(Color(250, 252, 254), Color(64, 71, 82))
        private val TREE_ROW_SPECIFY_BG = JBColor(Color(248, 251, 255), Color(67, 74, 86))
        private val TREE_ROW_SPECIFY_BG_SELECTED = JBColor(Color(236, 243, 252), Color(79, 95, 120))
        private val TREE_ROW_SPECIFY_BORDER = JBColor(Color(219, 229, 242), Color(102, 118, 140))
        private val TREE_ROW_SPECIFY_BORDER_SELECTED = JBColor(Color(184, 201, 224), Color(128, 149, 179))
        private val TREE_ROW_DESIGN_BG = JBColor(Color(251, 249, 245), Color(70, 73, 80))
        private val TREE_ROW_DESIGN_BG_SELECTED = JBColor(Color(246, 239, 228), Color(84, 90, 103))
        private val TREE_ROW_DESIGN_BORDER = JBColor(Color(231, 221, 206), Color(111, 112, 121))
        private val TREE_ROW_DESIGN_BORDER_SELECTED = JBColor(Color(205, 184, 153), Color(140, 129, 109))
        private val TREE_ROW_IMPLEMENT_BG = JBColor(Color(247, 251, 248), Color(66, 74, 80))
        private val TREE_ROW_IMPLEMENT_BG_SELECTED = JBColor(Color(235, 243, 236), Color(78, 93, 102))
        private val TREE_ROW_IMPLEMENT_BORDER = JBColor(Color(214, 227, 218), Color(100, 117, 124))
        private val TREE_ROW_IMPLEMENT_BORDER_SELECTED = JBColor(Color(173, 202, 181), Color(123, 145, 128))
        private val TREE_FILE_TEXT = JBColor(Color(83, 97, 121), Color(184, 197, 218))
        private val TREE_FILE_TEXT_SELECTED = JBColor(Color(64, 78, 101), Color(217, 228, 243))
        private val TREE_PHASE_SPECIFY = JBColor(Color(93, 129, 176), Color(167, 197, 242))
        private val TREE_PHASE_SPECIFY_SELECTED = JBColor(Color(75, 112, 161), Color(198, 216, 246))
        private val TREE_PHASE_DESIGN = JBColor(Color(153, 126, 92), Color(226, 194, 149))
        private val TREE_PHASE_DESIGN_SELECTED = JBColor(Color(133, 109, 81), Color(242, 210, 165))
        private val TREE_PHASE_IMPLEMENT = JBColor(Color(85, 138, 106), Color(173, 217, 185))
        private val TREE_PHASE_IMPLEMENT_SELECTED = JBColor(Color(72, 121, 91), Color(201, 232, 209))
        private val TREE_STATUS_DONE_TEXT = JBColor(Color(108, 136, 115), Color(205, 232, 210))
        private val TREE_STATUS_DONE_TEXT_SELECTED = JBColor(Color(89, 122, 98), Color(220, 237, 223))
        private val TREE_STATUS_DRAFT_TEXT = JBColor(Color(144, 122, 95), Color(239, 213, 174))
        private val TREE_STATUS_DRAFT_TEXT_SELECTED = JBColor(Color(124, 102, 77), Color(246, 223, 187))
        private val TREE_STATUS_PENDING_TEXT = JBColor(Color(120, 132, 149), Color(196, 210, 230))
        private val TREE_STATUS_PENDING_TEXT_SELECTED = JBColor(Color(100, 113, 131), Color(215, 224, 239))
        private val DOCUMENT_TAB_BG_IDLE = JBColor(Color(246, 249, 253), Color(60, 67, 78))
        private val DOCUMENT_TAB_BG_AVAILABLE = JBColor(Color(242, 247, 255), Color(65, 74, 87))
        private val DOCUMENT_TAB_BG_CURRENT = JBColor(Color(234, 243, 255), Color(72, 87, 108))
        private val DOCUMENT_TAB_BG_SELECTED = JBColor(Color(224, 238, 255), Color(80, 98, 122))
        private val DOCUMENT_TAB_BORDER_IDLE = JBColor(Color(212, 222, 236), Color(92, 103, 121))
        private val DOCUMENT_TAB_BORDER_AVAILABLE = JBColor(Color(193, 209, 230), Color(102, 114, 133))
        private val DOCUMENT_TAB_BORDER_CURRENT = JBColor(Color(156, 190, 236), Color(120, 151, 191))
        private val DOCUMENT_TAB_BORDER_SELECTED = JBColor(Color(121, 170, 236), Color(138, 176, 222))
        private val DOCUMENT_TAB_TEXT_IDLE = JBColor(Color(120, 132, 149), Color(170, 182, 200))
        private val DOCUMENT_TAB_TEXT_AVAILABLE = JBColor(Color(69, 92, 126), Color(210, 222, 238))
        private val DOCUMENT_TAB_TEXT_CURRENT = JBColor(Color(45, 79, 128), Color(223, 233, 246))
        private val DOCUMENT_TAB_TEXT_SELECTED = JBColor(Color(34, 68, 113), Color(236, 242, 251))
        private val SECTION_TITLE_FG = JBColor(Color(36, 60, 101), Color(212, 223, 241))
        private val COLLAPSE_TOGGLE_TEXT_ACTIVE = JBColor(Color(86, 115, 158), Color(187, 205, 230))
        private val DETAIL_START_REVISION_ICON = IconLoader.getIcon("/icons/spec-workflow-start-revision.svg", SpecDetailPanel::class.java)
        private val DETAIL_SAVE_ICON = IconLoader.getIcon("/icons/spec-detail-save.svg", SpecDetailPanel::class.java)
        private const val DOCUMENT_VIEWPORT_HEIGHT = 360
        private const val CARD_PREVIEW = "preview"
        private const val CARD_EDIT = "edit"
        private const val CARD_CLARIFY = "clarify"
        private const val CLARIFY_QUESTIONS_CARD_MARKDOWN = "clarify.questions.markdown"
        private const val CLARIFY_QUESTIONS_CARD_CHECKLIST = "clarify.questions.checklist"
    }
}
