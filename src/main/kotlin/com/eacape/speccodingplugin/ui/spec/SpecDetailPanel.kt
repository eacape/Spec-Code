package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.CodeContextPack
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMarkdownSanitizer
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.toStageId
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
import javax.swing.Icon
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
    private lateinit var clarificationPreviewSection: JPanel
    private lateinit var clarificationQuestionsBodyContainer: JPanel
    private lateinit var clarificationPreviewBodyContainer: JPanel
    private lateinit var clarificationQuestionsToggleButton: JButton
    private lateinit var clarificationPreviewToggleButton: JButton
    private lateinit var processTimelineSection: JPanel
    private lateinit var processTimelineBodyContainer: JPanel
    private lateinit var processTimelineToggleButton: JButton
    private lateinit var composerSectionBodyContainer: JPanel
    private lateinit var composerSectionToggleButton: JButton
    private lateinit var inputSectionContainer: JPanel
    private lateinit var actionButtonPanel: JPanel
    private lateinit var bottomPanelContainer: JPanel
    private lateinit var mainSplitPane: JSplitPane
    private var isPhaseStepperEnabled: Boolean = true

    private val generateButton = JButton()
    private val nextPhaseButton = JButton()
    private val goBackButton = JButton()
    private val completeButton = JButton()
    private val pauseResumeButton = JButton()
    private val openEditorButton = JButton()
    private val historyDiffButton = JButton()
    private val editButton = JButton()
    private val saveButton = JButton()
    private val cancelEditButton = JButton()
    private val confirmGenerateButton = JButton()
    private val regenerateClarificationButton = JButton()
    private val skipClarificationButton = JButton()
    private val cancelClarificationButton = JButton()

    private var currentWorkflow: SpecWorkflow? = null
    private var selectedPhase: SpecPhase? = null
    private var previewSourceText: String = ""
    private var previewChecklistInteraction: PreviewChecklistInteraction? = null
    private var isPreviewChecklistSaving: Boolean = false
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
    private var activePreviewCard: String = CARD_PREVIEW
    private var clarificationState: ClarificationState? = null
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
    private val processTimelineEntries = mutableListOf<ProcessTimelineEntry>()
    private val composerTitleLabel = JBLabel()

    init {
        setupUI()
    }

    private data class ClarificationState(
        val phase: SpecPhase,
        val input: String,
        val questionsMarkdown: String,
        val structuredQuestions: List<String> = emptyList(),
        val questionDecisions: Map<Int, ClarificationQuestionDecision> = emptyMap(),
        val questionDetails: Map<Int, String> = emptyMap(),
    )

    private data class PreviewChecklistInteraction(
        val phase: SpecPhase,
        val content: String,
    )

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

    private enum class ClarificationQuestionDecision {
        UNDECIDED,
        CONFIRMED,
        NOT_APPLICABLE,
    }

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
        previewPane.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) {
                        togglePreviewChecklistAt(e)
                    }
                }

                override fun mouseExited(e: MouseEvent?) {
                    refreshPreviewChecklistCursor(null)
                }
            },
        )
        previewPane.addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    refreshPreviewChecklistCursor(e)
                }
            },
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
        switchPreviewCard(CARD_PREVIEW)
        processTimelineSection = createProcessTimelineSection()
        setProcessTimelineVisible(false)
        previewPanel.add(processTimelineSection, BorderLayout.NORTH)
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

        actionButtonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        setupButtons(actionButtonPanel)
        composerContent.add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(JBUI.scale(1), 0, 0, 0, COMPOSER_FOOTER_DIVIDER),
                    JBUI.Borders.empty(8, 2, 0, 2),
                )
                add(
                    JBScrollPane(actionButtonPanel).apply {
                        border = JBUI.Borders.empty(1, 3)
                        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                        viewport.isOpaque = false
                        isOpaque = false
                        SpecUiStyle.applySlimHorizontalScrollBar(this, height = 7)
                    },
                    BorderLayout.CENTER,
                )
            },
            BorderLayout.SOUTH,
        )
        composerTitleLabel.text = SpecCodingBundle.message("spec.detail.composer.title")
        styleClarificationSectionLabel(composerTitleLabel)
        val composerSection = createCollapsibleSection(
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
        composerSectionBodyContainer = composerSection.bodyContainer
        composerSectionToggleButton = composerSection.toggleButton
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

    private fun setupButtons(panel: JPanel) {
        applyActionButtonPresentation()
        styleActionButton(generateButton)
        styleActionButton(nextPhaseButton)
        styleActionButton(goBackButton)
        styleActionButton(completeButton)
        styleActionButton(pauseResumeButton)
        styleActionButton(openEditorButton)
        styleActionButton(historyDiffButton)
        styleActionButton(editButton)
        styleActionButton(saveButton)
        styleActionButton(cancelEditButton)
        styleActionButton(confirmGenerateButton)
        styleActionButton(regenerateClarificationButton)
        styleActionButton(skipClarificationButton)
        styleActionButton(cancelClarificationButton)
        saveButton.isVisible = false
        cancelEditButton.isVisible = false
        confirmGenerateButton.isVisible = false
        regenerateClarificationButton.isVisible = false
        skipClarificationButton.isVisible = false
        cancelClarificationButton.isVisible = false

        generateButton.addActionListener {
            val text = inputArea.text.trim()
            val phase = currentWorkflow?.currentPhase
            val allowBlank = phase == SpecPhase.DESIGN || phase == SpecPhase.IMPLEMENT
            val canReuseLastInput = canGenerateWithEmptyInput()
            if (text.isNotBlank() || allowBlank || canReuseLastInput) {
                onGenerate(text)
                clearInput()
            } else {
                showInputRequiredHint(phase)
            }
        }
        nextPhaseButton.addActionListener { onNextPhase() }
        goBackButton.addActionListener { onGoBack() }
        completeButton.addActionListener { onComplete() }
        pauseResumeButton.addActionListener { onPauseResume() }
        openEditorButton.addActionListener {
            val phase = selectedPhase
            if (phase != null) {
                onOpenInEditor(phase)
            } else {
                workbenchArtifactBinding?.fileName?.let(onOpenArtifactInEditor)
            }
        }
        historyDiffButton.addActionListener {
            selectedPhase?.let { onShowHistoryDiff(it) }
        }
        editButton.addActionListener {
            val workflow = currentWorkflow ?: return@addActionListener
            val phase = resolveEditablePhase(workflow) ?: return@addActionListener
            if (isReadOnlyRevisionLocked(workflow, phase)) {
                explicitRevisionPhase = phase
            }
            startEditing()
        }
        saveButton.addActionListener {
            saveEditing()
        }
        cancelEditButton.addActionListener {
            stopEditing(keepText = false)
        }
        confirmGenerateButton.addActionListener {
            val state = clarificationState ?: return@addActionListener
            if (state.structuredQuestions.isNotEmpty()) {
                val firstMissingDetail = state.questionDecisions.entries
                    .asSequence()
                    .filter { it.value == ClarificationQuestionDecision.CONFIRMED }
                    .mapNotNull { (index, _) ->
                        val question = state.structuredQuestions.getOrNull(index)?.trim().orEmpty()
                        if (question.isBlank()) {
                            null
                        } else {
                            question to state.questionDetails[index].orEmpty().trim()
                        }
                    }
                    .firstOrNull { (_, detail) -> detail.isBlank() }
                if (firstMissingDetail != null) {
                    setValidationMessage(
                        SpecCodingBundle.message(
                            "spec.detail.clarify.checklist.detail.required",
                            firstMissingDetail.first,
                        ),
                        JBColor(Color(213, 52, 52), Color(255, 140, 140)),
                    )
                    return@addActionListener
                }
            }
            val confirmed = resolveClarificationConfirmedContext(state)
            val allowBlank = state.phase == SpecPhase.DESIGN || state.phase == SpecPhase.IMPLEMENT
            if (confirmed.isBlank() && !allowBlank) {
                setValidationMessage(
                    SpecCodingBundle.message("spec.detail.clarify.detailsRequired"),
                    JBColor(Color(213, 52, 52), Color(255, 140, 140)),
                )
                return@addActionListener
            }
            setClarificationChecklistReadOnly(true)
            onClarificationConfirm(state.input, confirmed)
        }
        regenerateClarificationButton.addActionListener {
            val state = clarificationState ?: return@addActionListener
            onClarificationRegenerate(state.input, resolveClarificationConfirmedContext(state))
        }
        skipClarificationButton.addActionListener {
            val state = clarificationState ?: return@addActionListener
            onClarificationSkip(state.input)
        }
        cancelClarificationButton.addActionListener {
            exitClarificationMode(clearInput = false)
            onClarificationCancel()
        }

        panel.add(generateButton)
        panel.add(openEditorButton)
        panel.add(historyDiffButton)
        panel.add(editButton)
        panel.add(saveButton)
        panel.add(cancelEditButton)
        panel.add(confirmGenerateButton)
        panel.add(regenerateClarificationButton)
        panel.add(skipClarificationButton)
        panel.add(cancelClarificationButton)

        disableAllButtons()
    }

    private fun applyActionButtonPresentation() {
        configureIconActionButton(
            button = generateButton,
            icon = SpecWorkflowIcons.Execute,
            tooltip = ArtifactComposeActionUiText.actionLabel(currentComposeActionMode()),
        )
        configureIconActionButton(
            button = nextPhaseButton,
            icon = SpecWorkflowIcons.Advance,
            tooltip = SpecCodingBundle.message("spec.detail.nextPhase"),
        )
        configureIconActionButton(
            button = goBackButton,
            icon = SpecWorkflowIcons.Back,
            tooltip = SpecCodingBundle.message("spec.detail.goBack"),
        )
        configureIconActionButton(
            button = completeButton,
            icon = SpecWorkflowIcons.Complete,
            tooltip = SpecCodingBundle.message("spec.detail.complete"),
        )
        configureIconActionButton(
            button = openEditorButton,
            icon = SpecWorkflowIcons.OpenToolWindow,
            tooltip = SpecCodingBundle.message("spec.detail.openInEditor"),
        )
        configureIconActionButton(
            button = historyDiffButton,
            icon = SpecWorkflowIcons.History,
            tooltip = SpecCodingBundle.message("spec.detail.historyDiff"),
        )
        configureIconActionButton(
            button = editButton,
            icon = currentEditActionIcon(),
            tooltip = currentEditActionTooltip(),
        )
        configureIconActionButton(
            button = saveButton,
            icon = DETAIL_SAVE_ICON,
            tooltip = SpecCodingBundle.message("spec.detail.save"),
        )
        configureIconActionButton(
            button = cancelEditButton,
            icon = SpecWorkflowIcons.Close,
            tooltip = SpecCodingBundle.message("spec.detail.cancel"),
        )
        configureIconActionButton(
            button = confirmGenerateButton,
            icon = SpecWorkflowIcons.Execute,
            tooltip = ArtifactComposeActionUiText.clarificationConfirmLabel(currentComposeActionMode()),
        )
        configureIconActionButton(
            button = regenerateClarificationButton,
            icon = SpecWorkflowIcons.Refresh,
            tooltip = SpecCodingBundle.message("spec.detail.clarify.regenerate"),
        )
        configureIconActionButton(
            button = skipClarificationButton,
            icon = SpecWorkflowIcons.Forward,
            tooltip = SpecCodingBundle.message("spec.detail.clarify.skip"),
        )
        configureIconActionButton(
            button = cancelClarificationButton,
            icon = SpecWorkflowIcons.Close,
            tooltip = SpecCodingBundle.message("spec.detail.clarify.cancel"),
        )
        updatePauseResumeButtonPresentation(currentWorkflow?.status)
    }

    private fun currentComposeActionMode(phase: SpecPhase? = currentWorkflow?.currentPhase): ArtifactComposeActionMode {
        val workflow = currentWorkflow ?: return ArtifactComposeActionMode.GENERATE
        val resolvedPhase = phase ?: workflow.currentPhase
        return workflow.resolveComposeActionMode(resolvedPhase)
    }

    private fun currentEditActionIcon(): Icon {
        val workflow = currentWorkflow
        val phase = workflow?.let(::resolveDisplayedDocumentPhase)
        return if (workflow != null && phase != null && isReadOnlyRevisionLocked(workflow, phase)) {
            DETAIL_START_REVISION_ICON
        } else {
            SpecWorkflowIcons.Edit
        }
    }

    private fun currentEditActionTooltip(): String {
        val workflow = currentWorkflow
        val phase = workflow?.let(::resolveDisplayedDocumentPhase)
        return if (workflow != null && phase != null && isReadOnlyRevisionLocked(workflow, phase)) {
            SpecCodingBundle.message("spec.detail.revision.start")
        } else {
            SpecCodingBundle.message("spec.detail.edit")
        }
    }

    private fun configureIconActionButton(button: JButton, icon: Icon, tooltip: String) {
        SpecUiStyle.configureIconActionButton(
            button = button,
            icon = icon,
            tooltip = tooltip,
            accessibleName = tooltip,
        )
    }

    private fun setActionEnabled(
        button: JButton,
        enabled: Boolean,
        disabledReason: String? = null,
    ) {
        SpecUiStyle.setIconActionEnabled(
            button = button,
            enabled = enabled,
            disabledReason = disabledReason,
        )
    }

    private fun updatePauseResumeButtonPresentation(status: WorkflowStatus?) {
        val isPaused = status == WorkflowStatus.PAUSED
        val icon = if (isPaused) SpecWorkflowIcons.Resume else SpecWorkflowIcons.Pause
        configureIconActionButton(
            button = pauseResumeButton,
            icon = icon,
            tooltip = SpecCodingBundle.message(if (isPaused) "spec.detail.resume" else "spec.detail.pause"),
        )
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
            styleActionButton(button)
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
        return workbenchArtifactBinding?.documentPhase == null && !workbenchArtifactBinding?.fileName.isNullOrBlank()
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

        val questionsCollapsible = createCollapsibleSection(
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
        clarificationQuestionsBodyContainer = questionsCollapsible.bodyContainer
        clarificationQuestionsToggleButton = questionsCollapsible.toggleButton
        val questionsSection = questionsCollapsible.root

        val previewCollapsible = createCollapsibleSection(
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
        clarificationPreviewSection = previewCollapsible.root
        clarificationPreviewBodyContainer = previewCollapsible.bodyContainer
        clarificationPreviewToggleButton = previewCollapsible.toggleButton

        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = true
            background = CLARIFICATION_CARD_BG
            clarificationSplitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
                topComponent = questionsSection
                bottomComponent = clarificationPreviewSection
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

        val timelineCollapsible = createCollapsibleSection(
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
        processTimelineBodyContainer = timelineCollapsible.bodyContainer
        processTimelineToggleButton = timelineCollapsible.toggleButton
        return timelineCollapsible.root.apply {
            border = JBUI.Borders.emptyBottom(JBUI.scale(2))
            applyProcessTimelineCollapseState()
        }
    }

    private data class CollapsibleSectionWidgets(
        val root: JPanel,
        val bodyContainer: JPanel,
        val toggleButton: JButton,
    )

    private fun createCollapsibleSection(
        titleLabel: JBLabel,
        content: Component,
        expanded: Boolean,
        onToggle: (Boolean) -> Unit,
    ): CollapsibleSectionWidgets {
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            isVisible = expanded
            add(content, BorderLayout.CENTER)
        }
        val toggleButton = JButton().apply {
            styleCollapsibleToggleButton(this)
            addActionListener {
                val nextExpanded = !body.isVisible
                body.isVisible = nextExpanded
                onToggle(nextExpanded)
            }
        }
        val header = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.WEST)
            add(toggleButton, BorderLayout.EAST)
        }
        val root = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
        updateCollapseToggleButton(toggleButton, expanded = expanded, enabled = true)
        return CollapsibleSectionWidgets(root = root, bodyContainer = body, toggleButton = toggleButton)
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

    private fun styleCollapsibleToggleButton(button: JButton) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = false
        button.isOpaque = false
        button.isBorderPainted = false
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.foreground = TREE_FILE_TEXT
        button.margin = JBUI.insets(0, 6, 0, 6)
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private fun updateCollapseToggleButton(button: JButton, expanded: Boolean, enabled: Boolean) {
        val key = if (expanded && enabled) {
            "spec.detail.toggle.collapse"
        } else {
            "spec.detail.toggle.expand"
        }
        button.text = SpecCodingBundle.message(key)
        button.toolTipText = button.text
        button.isEnabled = enabled
        button.foreground = when {
            !enabled -> TREE_STATUS_PENDING_TEXT
            expanded -> COLLAPSE_TOGGLE_TEXT_ACTIVE
            else -> TREE_FILE_TEXT
        }
        button.cursor = if (enabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
        applyCollapseToggleButtonSize(button)
    }

    private fun applyCollapseToggleButtonSize(button: JButton) {
        val textWidth = button.getFontMetrics(button.font).stringWidth(button.text.orEmpty())
        val horizontalPadding = button.margin.left + button.margin.right + JBUI.scale(14)
        val targetWidth = maxOf(JBUI.scale(68), textWidth + horizontalPadding)
        val targetSize = JBUI.size(targetWidth, JBUI.scale(22))
        button.preferredSize = targetSize
        button.minimumSize = targetSize
    }

    private fun refreshCollapsibleToggleTexts() {
        if (::processTimelineToggleButton.isInitialized) {
            updateCollapseToggleButton(
                processTimelineToggleButton,
                expanded = isProcessTimelineExpanded,
                enabled = true,
            )
        }
        if (::clarificationQuestionsToggleButton.isInitialized) {
            updateCollapseToggleButton(
                clarificationQuestionsToggleButton,
                expanded = isClarificationQuestionsExpanded,
                enabled = true,
            )
        }
        if (::clarificationPreviewToggleButton.isInitialized) {
            updateCollapseToggleButton(
                clarificationPreviewToggleButton,
                expanded = isClarificationPreviewExpanded,
                enabled = isClarificationPreviewContentVisible,
            )
        }
        if (::composerSectionToggleButton.isInitialized) {
            updateCollapseToggleButton(
                composerSectionToggleButton,
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

    private fun switchPreviewCard(card: String) {
        activePreviewCard = card
        previewCardLayout.show(previewCardPanel, card)
        refreshActionButtonCursors()
    }

    private fun showInputRequiredHint(phase: SpecPhase?) {
        if (phase != SpecPhase.SPECIFY) return
        setValidationMessage(
            ArtifactComposeActionUiText.inputRequired(currentComposeActionMode(phase)),
            JBColor(Color(213, 52, 52), Color(255, 140, 140)),
        )
    }

    private fun setValidationMessage(text: String?, foreground: Color = JBColor.GRAY) {
        val message = text.orEmpty()
        validationLabel.text = message
        validationLabel.foreground = foreground
        if (::validationBannerPanel.isInitialized) {
            validationBannerPanel.isVisible = message.isNotBlank()
            validationBannerPanel.parent?.revalidate()
            validationBannerPanel.parent?.repaint()
        }
    }

    private fun clearValidationMessage() {
        setValidationMessage("", JBColor.GRAY)
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

    private fun styleActionButton(button: JButton) {
        val iconOnly = button.icon != null && button.text.isNullOrBlank()
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = if (iconOnly) JBUI.insets(0, 0, 0, 0) else JBUI.insets(1, 4, 1, 4)
        button.isOpaque = true
        button.foreground = BUTTON_FG
        SpecUiStyle.applyRoundRect(button, arc = 10)
        if (iconOnly) {
            SpecUiStyle.styleIconActionButton(button, size = 22, arc = 10)
        } else {
            button.background = BUTTON_BG
            button.border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
                JBUI.Borders.empty(1, 5, 1, 5),
            )
            val textWidth = button.getFontMetrics(button.font).stringWidth(button.text ?: "")
            val insets = button.insets
            val lafWidth = button.preferredSize?.width ?: 0
            val width = maxOf(
                lafWidth,
                textWidth + insets.left + insets.right + JBUI.scale(10),
                JBUI.scale(40),
            )
            button.preferredSize = JBUI.size(width, JBUI.scale(26))
            button.minimumSize = button.preferredSize
        }
        updateButtonCursor(button)
    }

    private fun updateButtonCursor(button: JButton) {
        button.cursor = if (button.isEnabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private fun refreshActionButtonCursors() {
        listOf(
            generateButton,
            nextPhaseButton,
            goBackButton,
            completeButton,
            pauseResumeButton,
            openEditorButton,
            historyDiffButton,
            editButton,
            saveButton,
            cancelEditButton,
            confirmGenerateButton,
            regenerateClarificationButton,
            skipClarificationButton,
            cancelClarificationButton,
        ).forEach(::updateButtonCursor)
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
        applyActionButtonPresentation()
        updateInputPlaceholder(currentWorkflow?.currentPhase)
        styleActionButton(generateButton)
        styleActionButton(nextPhaseButton)
        styleActionButton(goBackButton)
        styleActionButton(completeButton)
        styleActionButton(pauseResumeButton)
        styleActionButton(openEditorButton)
        styleActionButton(historyDiffButton)
        styleActionButton(editButton)
        styleActionButton(saveButton)
        styleActionButton(cancelEditButton)
        styleActionButton(confirmGenerateButton)
        styleActionButton(regenerateClarificationButton)
        styleActionButton(skipClarificationButton)
        styleActionButton(cancelClarificationButton)
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
        refreshClarificationSectionsLayout()
        setClarificationPreviewVisible(!isClarificationGenerating)
        updateClarificationPreview()
        refreshInputAreaMode()
        if (currentWorkflow == null) {
            setValidationMessage(SpecCodingBundle.message("spec.detail.noWorkflow"))
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
            clarificationState = null
            activeChecklistDetailIndex = null
            isClarificationChecklistReadOnly = false
            clearProcessTimeline()
            composerSourcePanel.clear()
        }
        if (previousWorkflowId != workflow.id || composerCodeContextState.codeContextPack?.phase != workflow.currentPhase) {
            composerCodeContextState = ComposerCodeContextState(workflowId = workflow.id)
        }
        if (clarificationState?.phase != null && clarificationState?.phase != workflow.currentPhase) {
            clarificationState = null
            activeChecklistDetailIndex = null
            isClarificationChecklistReadOnly = false
        }
        isGeneratingActive = false
        isClarificationGenerating = false
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
        applyActionButtonPresentation()
        setPhaseStepperEnabled(!isEditing)
        updateTreeSelection(selectedPhase, forceComposerReset = false)
        updateButtonStates(workflow)
        refreshComposerCodeContextPanelState()
        refreshInputAreaMode()
        syncComposerSectionState(forceReset = previousWorkflowId != workflow.id || followCurrentPhase)
        if (clarificationState == null) {
            setClarificationPreviewVisible(true)
            switchPreviewCard(CARD_PREVIEW)
            showActivePreview(keepGeneratingIndicator = false)
        } else {
            setClarificationPreviewVisible(!isClarificationGenerating)
            updateClarificationPreview()
            switchPreviewCard(CARD_CLARIFY)
        }
    }

    fun showEmpty() {
        isGeneratingActive = false
        isClarificationGenerating = false
        stopGeneratingAnimation()
        isEditing = false
        explicitRevisionPhase = null
        editingPhase = null
        preferredWorkbenchPhase = null
        workbenchArtifactBinding = null
        composerSourceState = ComposerSourceState()
        composerCodeContextState = ComposerCodeContextState()
        selectedPhase = null
        clarificationState = null
        activeChecklistDetailIndex = null
        isClarificationChecklistReadOnly = false
        clearProcessTimeline()
        treeRoot.removeAllChildren()
        treeModel.reload()
        setPhaseStepperEnabled(false)
        updateTreeSelection(null)
        setClarificationPreviewVisible(true)
        switchPreviewCard(CARD_PREVIEW)
        documentTree.isEnabled = true
        previewSourceText = ""
        previewChecklistInteraction = null
        isPreviewChecklistSaving = false
        previewPane.text = ""
        refreshPreviewChecklistCursor(null)
        clarificationQuestionsPane.text = ""
        clarificationChecklistPanel.removeAll()
        clarificationChecklistHintLabel.text = SpecCodingBundle.message("spec.detail.clarify.checklist.hint")
        clarificationPreviewPane.text = ""
        setValidationMessage(SpecCodingBundle.message("spec.detail.noWorkflow"))
        inputArea.isEnabled = true
        inputArea.isEditable = true
        inputArea.toolTipText = null
        composerSourcePanel.clear()
        composerCodeContextPanel.clear()
        updateInputPlaceholder(null)
        applyActionButtonPresentation()
        generateButton.isVisible = true
        nextPhaseButton.isVisible = true
        goBackButton.isVisible = true
        completeButton.isVisible = false
        pauseResumeButton.isVisible = false
        openEditorButton.isVisible = true
        historyDiffButton.isVisible = true
        editButton.isVisible = true
        saveButton.isVisible = false
        cancelEditButton.isVisible = false
        confirmGenerateButton.isVisible = false
        regenerateClarificationButton.isVisible = false
        skipClarificationButton.isVisible = false
        cancelClarificationButton.isVisible = false
        disableAllButtons()
        composerContextKey = null
        composerManualOverride = null
        if (::composerSectionBodyContainer.isInitialized) {
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
        if (isWorkbenchArtifactOnlyView()) {
            showWorkbenchArtifactPreview(keepGeneratingIndicator = keepGeneratingIndicator)
        } else {
            showDocumentPreview(selectedPhase ?: workflow.currentPhase, keepGeneratingIndicator = keepGeneratingIndicator)
        }
    }

    private fun showWorkbenchArtifactPreview(keepGeneratingIndicator: Boolean = true) {
        if (!keepGeneratingIndicator || !isGeneratingActive) {
            stopGeneratingAnimation()
        }
        val binding = workbenchArtifactBinding ?: return
        val artifactName = binding.fileName ?: binding.title
        val previewContent = binding.previewContent
        if (!previewContent.isNullOrBlank()) {
            renderPreviewMarkdown(previewContent)
        } else {
            renderPreviewMarkdown(
                binding.emptyStateMessage ?: SpecCodingBundle.message("spec.detail.workbench.missing", artifactName),
            )
        }
        if (isGeneratingActive && keepGeneratingIndicator) {
            updateGeneratingLabel()
            return
        }
        setValidationMessage(
            if (binding.available) {
                SpecCodingBundle.message("spec.detail.workbench.readOnly", artifactName)
            } else {
                binding.unavailableMessage ?: SpecCodingBundle.message("spec.detail.workbench.unavailable", artifactName)
            },
            if (binding.available) {
                JBColor.GRAY
            } else {
                JBColor(Color(213, 52, 52), Color(255, 140, 140))
            },
        )
    }

    private fun resolveDisplayedDocumentPhase(workflow: SpecWorkflow): SpecPhase? {
        if (isWorkbenchArtifactOnlyView()) {
            return null
        }
        return selectedPhase ?: preferredWorkbenchPhase ?: workflow.currentPhase
    }

    private fun stageProgressForPhase(workflow: SpecWorkflow, phase: SpecPhase): StageProgress {
        return workflow.stageStates[phase.toStageId()]?.status ?: when {
            phase == workflow.currentPhase -> StageProgress.IN_PROGRESS
            workflow.currentPhase.ordinal > phase.ordinal -> StageProgress.DONE
            else -> StageProgress.NOT_STARTED
        }
    }

    private fun requiresExplicitRevisionEntry(workflow: SpecWorkflow, phase: SpecPhase): Boolean {
        if (phase != SpecPhase.SPECIFY && phase != SpecPhase.DESIGN) {
            return false
        }
        return stageProgressForPhase(workflow, phase) == StageProgress.DONE
    }

    private fun isReadOnlyRevisionLocked(workflow: SpecWorkflow, phase: SpecPhase): Boolean {
        return requiresExplicitRevisionEntry(workflow, phase) && explicitRevisionPhase != phase
    }

    private fun currentReadOnlyRevisionLockedPhase(workflow: SpecWorkflow): SpecPhase? {
        val phase = resolveDisplayedDocumentPhase(workflow) ?: return null
        return phase.takeIf { isReadOnlyRevisionLocked(workflow, it) }
    }

    private fun revisionLockedHint(phase: SpecPhase): String {
        return SpecCodingBundle.message("spec.detail.revision.locked.banner", phaseStepperTitle(phase))
    }

    private fun revisionLockedDisabledReason(phase: SpecPhase): String {
        return SpecCodingBundle.message("spec.detail.revision.locked.action", phaseStepperTitle(phase))
    }

    private fun resolveEditablePhase(workflow: SpecWorkflow): SpecPhase? {
        if (isWorkbenchArtifactOnlyView()) {
            return null
        }
        return selectedPhase ?: preferredWorkbenchPhase ?: workflow.currentPhase
    }

    private fun startEditing() {
        if (isEditing || clarificationState != null) return
        val workflow = currentWorkflow ?: return
        val phase = resolveEditablePhase(workflow) ?: return
        if (isReadOnlyRevisionLocked(workflow, phase)) {
            return
        }
        val document = workflow.getDocument(phase)
        isEditing = true
        editingPhase = phase
        editorArea.text = document?.content.orEmpty()
        editorArea.caretPosition = 0
        switchPreviewCard(CARD_EDIT)
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
        switchPreviewCard(CARD_PREVIEW)
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
        saveButton.isEnabled = false
        cancelEditButton.isEnabled = false
        onSaveDocument(phase, normalized) { result ->
            saveButton.isEnabled = true
            cancelEditButton.isEnabled = true
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
        val generatingText = ArtifactComposeActionUiText.clarificationGenerating(currentComposeActionMode(phase))
        clarificationState = ClarificationState(
            phase = phase,
            input = input,
            questionsMarkdown = generatingText,
        )
        activeChecklistDetailIndex = null
        isClarificationChecklistReadOnly = false
        renderClarificationQuestions(
            markdown = generatingText,
            structuredQuestions = emptyList(),
            questionDecisions = emptyMap(),
            questionDetails = emptyMap(),
        )
        inputArea.text = suggestedDetails
        inputArea.caretPosition = 0
        updateInputPlaceholder(phase)
        refreshInputAreaMode()
        setClarificationPreviewVisible(false)
        switchPreviewCard(CARD_CLARIFY)
        updateClarificationPreview()
        persistClarificationDraftSnapshot()

        generatingPercent = 0
        isClarificationGenerating = true
        isGeneratingActive = true
        startGeneratingAnimation()
        updateGeneratingLabel()
        currentWorkflow?.let { updateButtonStates(it) } ?: disableAllButtons()
    }

    fun showClarificationDraft(
        phase: SpecPhase,
        input: String,
        questionsMarkdown: String,
        suggestedDetails: String = input,
        structuredQuestions: List<String> = emptyList(),
    ) {
        isGeneratingActive = false
        isClarificationGenerating = false
        stopGeneratingAnimation()
        val normalizedQuestions = structuredQuestions
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val inferredDecisions = inferQuestionDecisions(
            structuredQuestions = normalizedQuestions,
            confirmedContext = suggestedDetails,
        )
        val inferredDetails = inferQuestionDetails(
            structuredQuestions = normalizedQuestions,
            confirmedContext = suggestedDetails,
            questionDecisions = inferredDecisions,
        )
        clarificationState = ClarificationState(
            phase = phase,
            input = input,
            questionsMarkdown = questionsMarkdown,
            structuredQuestions = normalizedQuestions,
            questionDecisions = inferredDecisions,
            questionDetails = inferredDetails,
        )
        activeChecklistDetailIndex = inferredDecisions
            .entries
            .asSequence()
            .filter { it.value == ClarificationQuestionDecision.CONFIRMED }
            .map { it.key }
            .sorted()
            .firstOrNull()
        isClarificationChecklistReadOnly = false
        renderClarificationQuestions(
            markdown = questionsMarkdown,
            structuredQuestions = normalizedQuestions,
            questionDecisions = inferredDecisions,
            questionDetails = inferredDetails,
        )
        if (normalizedQuestions.isNotEmpty()) {
            syncClarificationInputFromSelection(clarificationState)
        } else {
            inputArea.text = suggestedDetails
            inputArea.caretPosition = 0
        }
        updateInputPlaceholder(phase)
        refreshInputAreaMode()
        setClarificationPreviewVisible(true)
        switchPreviewCard(CARD_CLARIFY)
        updateClarificationPreview()
        persistClarificationDraftSnapshot()
        setValidationMessage(
            ArtifactComposeActionUiText.clarificationHint(currentComposeActionMode(phase)),
            TREE_TEXT,
        )
        currentWorkflow?.let { updateButtonStates(it) } ?: disableAllButtons()
    }

    fun showProcessTimeline(entries: List<ProcessTimelineEntry>) {
        processTimelineEntries.clear()
        entries.forEach { entry ->
            val normalized = entry.text.trim()
            if (normalized.isNotBlank()) {
                processTimelineEntries += entry.copy(text = normalized)
            }
        }
        while (processTimelineEntries.size > MAX_PROCESS_TIMELINE_ENTRIES) {
            processTimelineEntries.removeAt(0)
        }
        renderProcessTimeline()
    }

    fun appendProcessTimelineEntry(
        text: String,
        state: ProcessTimelineState = ProcessTimelineState.INFO,
    ) {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return
        }
        val previous = processTimelineEntries.lastOrNull()
        if (previous != null && previous.text == normalized && previous.state == state) {
            return
        }
        processTimelineEntries += ProcessTimelineEntry(
            text = normalized,
            state = state,
        )
        while (processTimelineEntries.size > MAX_PROCESS_TIMELINE_ENTRIES) {
            processTimelineEntries.removeAt(0)
        }
        renderProcessTimeline()
    }

    fun clearProcessTimeline() {
        processTimelineEntries.clear()
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
            ?.takeIf { it.structuredQuestions.isNotEmpty() }
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

    fun exitClarificationMode(clearInput: Boolean = false) {
        isGeneratingActive = false
        isClarificationGenerating = false
        stopGeneratingAnimation()
        clarificationState = null
        activeChecklistDetailIndex = null
        isClarificationChecklistReadOnly = false
        clarificationQuestionsPane.text = ""
        clarificationPreviewPane.text = ""
        setClarificationPreviewVisible(true)
        updateInputPlaceholder(currentWorkflow?.currentPhase)
        if (clearInput) {
            inputArea.text = ""
        }
        if (isEditing) {
            switchPreviewCard(CARD_EDIT)
        } else {
            switchPreviewCard(CARD_PREVIEW)
        }
        refreshInputAreaMode()
        currentWorkflow?.let { updateButtonStates(it) } ?: disableAllButtons()
    }

    private fun renderClarificationQuestions(
        markdown: String,
        structuredQuestions: List<String>,
        questionDecisions: Map<Int, ClarificationQuestionDecision>,
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
        questionDecisions: Map<Int, ClarificationQuestionDecision>,
        questionDetails: Map<Int, String>,
    ) {
        clarificationChecklistPanel.removeAll()
        val confirmedIndexes = structuredQuestions.indices
            .filter { index -> questionDecisions[index] == ClarificationQuestionDecision.CONFIRMED }
        val resolvedActiveIndex = when {
            confirmedIndexes.isEmpty() -> null
            activeChecklistDetailIndex?.let { it in confirmedIndexes } == true -> activeChecklistDetailIndex
            else -> confirmedIndexes.first()
        }
        activeChecklistDetailIndex = resolvedActiveIndex
        val checklistEditable = !isClarificationChecklistReadOnly
        structuredQuestions.forEachIndexed { index, question ->
            val decision = questionDecisions[index] ?: ClarificationQuestionDecision.UNDECIDED
            clarificationChecklistPanel.add(
                createChecklistQuestionItem(
                    index = index,
                    question = question,
                    decision = decision,
                    editable = checklistEditable,
                ),
            )
            if (index < structuredQuestions.lastIndex) {
                clarificationChecklistPanel.add(Box.createVerticalStrut(JBUI.scale(1)))
            }
        }
        val confirmedCount = questionDecisions.values.count { it == ClarificationQuestionDecision.CONFIRMED }
        val notApplicableCount = questionDecisions.values.count { it == ClarificationQuestionDecision.NOT_APPLICABLE }
        val summary = SpecCodingBundle.message(
            "spec.detail.clarify.checklist.progress",
            confirmedCount,
            notApplicableCount,
            structuredQuestions.size,
        )
        val hint = SpecCodingBundle.message("spec.detail.clarify.checklist.hint")
        clarificationChecklistHintLabel.text = "$summary  ·  $hint"
        clarificationChecklistPanel.revalidate()
        clarificationChecklistPanel.repaint()
    }

    private fun createChecklistQuestionItem(
        index: Int,
        question: String,
        decision: ClarificationQuestionDecision,
        editable: Boolean,
    ): JPanel {
        val indicator = JBLabel(
            when (decision) {
                ClarificationQuestionDecision.CONFIRMED -> "✓"
                ClarificationQuestionDecision.NOT_APPLICABLE -> "∅"
                ClarificationQuestionDecision.UNDECIDED -> "•"
            },
        ).apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            foreground = when (decision) {
                ClarificationQuestionDecision.CONFIRMED -> CHECKLIST_CONFIRM_TEXT
                ClarificationQuestionDecision.NOT_APPLICABLE -> CHECKLIST_NA_TEXT
                ClarificationQuestionDecision.UNDECIDED -> TREE_FILE_TEXT
            }
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
        renderChecklistQuestionText(questionText, question)
        val confirmButton = createChecklistChoiceButton(
            text = SpecCodingBundle.message("spec.detail.clarify.checklist.choice.confirm"),
            selected = decision == ClarificationQuestionDecision.CONFIRMED,
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
            selected = decision == ClarificationQuestionDecision.NOT_APPLICABLE,
            selectedBackground = CHECKLIST_NA_BG,
            selectedForeground = CHECKLIST_NA_TEXT,
            normalBackground = CHECKLIST_CHOICE_BG,
            normalForeground = TREE_FILE_TEXT,
            enabled = editable,
        ) {
            onChecklistQuestionDecisionChanged(
                index = index,
                decision = if (decision == ClarificationQuestionDecision.NOT_APPLICABLE) {
                    ClarificationQuestionDecision.UNDECIDED
                } else {
                    ClarificationQuestionDecision.NOT_APPLICABLE
                },
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
        val rowColors = checklistRowColors(decision)
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

    private fun onChecklistQuestionRowClicked(index: Int, fallbackDecision: ClarificationQuestionDecision) {
        if (isClarificationChecklistReadOnly) {
            return
        }
        val state = clarificationState ?: return
        if (state.structuredQuestions.isEmpty() || index !in state.structuredQuestions.indices) {
            return
        }
        val currentDecision = state.questionDecisions[index] ?: fallbackDecision
        val nextDecision = when (currentDecision) {
            ClarificationQuestionDecision.UNDECIDED -> ClarificationQuestionDecision.CONFIRMED
            ClarificationQuestionDecision.CONFIRMED -> ClarificationQuestionDecision.UNDECIDED
            ClarificationQuestionDecision.NOT_APPLICABLE -> ClarificationQuestionDecision.CONFIRMED
        }
        if (nextDecision == ClarificationQuestionDecision.CONFIRMED) {
            onChecklistQuestionConfirmRequested(index)
            return
        }
        onChecklistQuestionDecisionChanged(index, nextDecision)
    }

    private fun renderChecklistQuestionText(target: JTextPane, question: String) {
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
            parseChecklistQuestionSegments(question).forEach { segment ->
                val attrs = when {
                    segment.inlineCode -> codeAttrs
                    segment.bold -> boldAttrs
                    else -> normalAttrs
                }
                doc.insertString(doc.length, segment.text, attrs)
            }
            target.caretPosition = 0
        } catch (_: BadLocationException) {
            target.text = question
            target.caretPosition = 0
        }
    }

    private fun parseChecklistQuestionSegments(question: String): List<ChecklistQuestionSegment> {
        val normalized = normalizeChecklistQuestionText(question)
        if (normalized.isBlank()) {
            return emptyList()
        }
        val segments = mutableListOf<ChecklistQuestionSegment>()
        var cursor = 0
        while (cursor < normalized.length) {
            if (
                cursor + 1 < normalized.length &&
                normalized[cursor] == '*' &&
                normalized[cursor + 1] == '*'
            ) {
                val end = normalized.indexOf("**", cursor + 2)
                if (end > cursor + 1) {
                    val boldText = normalized.substring(cursor + 2, end)
                    if (boldText.isNotEmpty()) {
                        segments += ChecklistQuestionSegment(
                            text = boldText,
                            bold = true,
                        )
                    }
                    cursor = end + 2
                    continue
                }
            }

            if (normalized[cursor] == '`') {
                val delimiterLength = countChecklistBacktickDelimiterLength(normalized, cursor)
                val delimiter = "`".repeat(delimiterLength)
                val end = normalized.indexOf(delimiter, cursor + delimiterLength)
                if (end >= cursor + delimiterLength) {
                    val codeText = normalized.substring(cursor + delimiterLength, end)
                    if (codeText.isNotEmpty()) {
                        segments += ChecklistQuestionSegment(
                            text = codeText,
                            bold = false,
                            inlineCode = true,
                        )
                    }
                    cursor = end + delimiterLength
                    continue
                }
            }

            val nextSpecial = findNextChecklistMarkdownSpecial(normalized, cursor + 1)
            segments += ChecklistQuestionSegment(
                text = normalized.substring(cursor, nextSpecial),
                bold = false,
            )
            cursor = nextSpecial
        }
        return segments
            .filter { it.text.isNotEmpty() }
            .mergeAdjacentChecklistSegments()
    }

    private fun normalizeChecklistQuestionText(question: String): String {
        return question
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun findNextChecklistMarkdownSpecial(text: String, from: Int): Int {
        for (index in from until text.length) {
            if (text[index] == '*' || text[index] == '`') {
                return index
            }
        }
        return text.length
    }

    private fun countChecklistBacktickDelimiterLength(text: String, start: Int): Int {
        var cursor = start
        while (cursor < text.length && text[cursor] == '`') {
            cursor++
        }
        return (cursor - start).coerceAtLeast(1)
    }

    private fun List<ChecklistQuestionSegment>.mergeAdjacentChecklistSegments(): List<ChecklistQuestionSegment> {
        if (isEmpty()) return this
        val merged = mutableListOf<ChecklistQuestionSegment>()
        forEach { segment ->
            val last = merged.lastOrNull()
            if (last != null && last.bold == segment.bold && last.inlineCode == segment.inlineCode) {
                merged[merged.lastIndex] = last.copy(text = last.text + segment.text)
            } else {
                merged += segment
            }
        }
        return merged
    }

    private fun checklistRowColors(decision: ClarificationQuestionDecision): ChecklistRowColors {
        return when (decision) {
            ClarificationQuestionDecision.CONFIRMED -> ChecklistRowColors(
                background = CHECKLIST_ROW_BG_SELECTED,
                border = CHECKLIST_ROW_BORDER_SELECTED,
            )
            ClarificationQuestionDecision.NOT_APPLICABLE -> ChecklistRowColors(
                background = CHECKLIST_ROW_BG_NA,
                border = CHECKLIST_ROW_BORDER_NA,
            )
            ClarificationQuestionDecision.UNDECIDED -> ChecklistRowColors(
                background = CHECKLIST_ROW_BG,
                border = CHECKLIST_ROW_BORDER,
            )
        }
    }

    private fun onChecklistQuestionDecisionChanged(index: Int, decision: ClarificationQuestionDecision) {
        if (isClarificationChecklistReadOnly) {
            return
        }
        val state = clarificationState ?: return
        if (state.structuredQuestions.isEmpty() || index !in state.structuredQuestions.indices) {
            return
        }
        val nextDecisions = state.questionDecisions.toMutableMap()
        val nextDetails = state.questionDetails.toMutableMap()
        if (decision == ClarificationQuestionDecision.UNDECIDED) {
            nextDecisions.remove(index)
            nextDetails.remove(index)
            if (activeChecklistDetailIndex == index) {
                activeChecklistDetailIndex = nextDecisions
                    .entries
                    .asSequence()
                    .filter { it.value == ClarificationQuestionDecision.CONFIRMED }
                    .map { it.key }
                    .sorted()
                    .firstOrNull()
            }
        } else {
            nextDecisions[index] = decision
            if (decision != ClarificationQuestionDecision.CONFIRMED) {
                nextDetails.remove(index)
                if (activeChecklistDetailIndex == index) {
                    activeChecklistDetailIndex = nextDecisions
                        .entries
                        .asSequence()
                        .filter { it.value == ClarificationQuestionDecision.CONFIRMED }
                        .map { it.key }
                        .sorted()
                        .firstOrNull()
                }
            } else {
                activeChecklistDetailIndex = index
            }
        }
        val nextState = state.copy(
            questionDecisions = nextDecisions,
            questionDetails = nextDetails,
        )
        clarificationState = nextState
        renderClarificationQuestions(
            markdown = nextState.questionsMarkdown,
            structuredQuestions = nextState.structuredQuestions,
            questionDecisions = nextState.questionDecisions,
            questionDetails = nextState.questionDetails,
        )
        syncClarificationInputFromSelection(nextState)
        updateClarificationPreview()
        persistClarificationDraftSnapshot(nextState)
        setValidationMessage(
            ArtifactComposeActionUiText.clarificationHint(currentComposeActionMode(state.phase)),
            TREE_TEXT,
        )
        currentWorkflow?.let { updateButtonStates(it) }
    }

    private fun onChecklistQuestionConfirmRequested(index: Int) {
        if (isClarificationChecklistReadOnly) {
            return
        }
        val state = clarificationState ?: return
        if (state.structuredQuestions.isEmpty() || index !in state.structuredQuestions.indices) {
            return
        }
        val currentDecision = state.questionDecisions[index] ?: ClarificationQuestionDecision.UNDECIDED
        if (currentDecision == ClarificationQuestionDecision.CONFIRMED) {
            val updatedDetail = requestClarificationConfirmDetail(
                question = state.structuredQuestions[index],
                initialDetail = state.questionDetails[index].orEmpty(),
            ) ?: return
            onChecklistQuestionDetailChanged(index, updatedDetail)
            return
        }

        val question = state.structuredQuestions[index]
        val existingDetail = state.questionDetails[index].orEmpty()
        val confirmedDetail = requestClarificationConfirmDetail(
            question = question,
            initialDetail = existingDetail,
        ) ?: return

        onChecklistQuestionDecisionChanged(index, ClarificationQuestionDecision.CONFIRMED)
        onChecklistQuestionDetailChanged(index, confirmedDetail)
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
        if (state.structuredQuestions.isEmpty() || index !in state.structuredQuestions.indices) {
            return
        }
        if (state.questionDecisions[index] != ClarificationQuestionDecision.CONFIRMED) {
            return
        }
        activeChecklistDetailIndex = index
        val normalized = detail
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        val nextDetails = state.questionDetails.toMutableMap()
        if (normalized.isBlank()) {
            nextDetails.remove(index)
        } else {
            nextDetails[index] = normalized
        }
        val nextState = state.copy(questionDetails = nextDetails)
        clarificationState = nextState
        syncClarificationInputFromSelection(nextState)
        updateClarificationPreview()
        persistClarificationDraftSnapshot(nextState)
        setValidationMessage(
            ArtifactComposeActionUiText.clarificationHint(currentComposeActionMode(state.phase)),
            TREE_TEXT,
        )
        currentWorkflow?.let { updateButtonStates(it) }
    }

    private fun resolveClarificationConfirmedContext(state: ClarificationState): String {
        if (state.structuredQuestions.isNotEmpty()) {
            return buildChecklistConfirmedContext(state)
        }
        return normalizeContent(inputArea.text)
    }

    private fun buildChecklistConfirmedContext(state: ClarificationState): String {
        val confirmedQuestionDetails = state.questionDecisions.entries
            .filter { it.value == ClarificationQuestionDecision.CONFIRMED }
            .sortedBy { it.key }
            .mapNotNull { entry ->
                val question = state.structuredQuestions.getOrNull(entry.key)?.trim().orEmpty()
                if (question.isBlank()) {
                    null
                } else {
                    val detail = state.questionDetails[entry.key]
                        ?.replace("\r\n", "\n")
                        ?.replace('\r', '\n')
                        ?.trim()
                        .orEmpty()
                    question to detail
                }
            }
        val notApplicableQuestions = state.questionDecisions.entries
            .filter { it.value == ClarificationQuestionDecision.NOT_APPLICABLE }
            .sortedBy { it.key }
            .mapNotNull { entry -> state.structuredQuestions.getOrNull(entry.key) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (confirmedQuestionDetails.isEmpty() && notApplicableQuestions.isEmpty()) {
            return ""
        }
        return buildString {
            if (confirmedQuestionDetails.isNotEmpty()) {
                appendLine("**${SpecCodingBundle.message("spec.detail.clarify.confirmed.title")}**")
                confirmedQuestionDetails.forEach { (question, detail) ->
                    appendLine("- $question")
                    if (detail.isNotBlank()) {
                        appendLine("  - ${SpecCodingBundle.message("spec.detail.clarify.checklist.detail.exportPrefix")}: $detail")
                    }
                }
            }
            if (notApplicableQuestions.isNotEmpty()) {
                if (confirmedQuestionDetails.isNotEmpty()) {
                    appendLine()
                }
                appendLine("**${SpecCodingBundle.message("spec.detail.clarify.notApplicable.title")}**")
                notApplicableQuestions.forEach { question ->
                    appendLine("- $question")
                }
            }
        }.trimEnd()
    }

    private fun syncClarificationInputFromSelection(state: ClarificationState?) {
        val currentState = state ?: clarificationState ?: return
        if (currentState.structuredQuestions.isEmpty()) {
            return
        }
        inputArea.text = buildChecklistConfirmedContext(currentState)
        inputArea.caretPosition = 0
    }

    private fun inferQuestionDecisions(
        structuredQuestions: List<String>,
        confirmedContext: String,
    ): Map<Int, ClarificationQuestionDecision> {
        if (structuredQuestions.isEmpty()) {
            return emptyMap()
        }
        val lines = confirmedContext
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
        val normalizedContext = normalizeComparableText(confirmedContext)
        if (normalizedContext.isBlank()) {
            return emptyMap()
        }
        val lineSections = mapContextSections(lines)
        return structuredQuestions.mapIndexedNotNull { index, question ->
            val normalizedQuestion = normalizeComparableText(question)
            if (normalizedQuestion.isBlank()) {
                return@mapIndexedNotNull null
            }
            val lineIndex = lines.indexOfFirst { line ->
                normalizeComparableText(line).contains(normalizedQuestion)
            }
            val normalizedLine = if (lineIndex >= 0) normalizeComparableText(lines[lineIndex]) else null
            val section = lineSections[lineIndex] ?: ClarificationContextSection.OTHER
            when {
                normalizedLine != null && normalizedLine.contains("[x]") ->
                    index to ClarificationQuestionDecision.CONFIRMED
                normalizedLine != null && (normalizedLine.contains("[ ]") || normalizedLine.contains("[]")) ->
                    index to ClarificationQuestionDecision.NOT_APPLICABLE
                section == ClarificationContextSection.NOT_APPLICABLE ->
                    index to ClarificationQuestionDecision.NOT_APPLICABLE
                section == ClarificationContextSection.CONFIRMED ->
                    index to ClarificationQuestionDecision.CONFIRMED
                normalizedContext.contains(normalizedQuestion) ->
                    index to ClarificationQuestionDecision.CONFIRMED
                else -> null
            }
        }.toMap()
    }

    private fun inferQuestionDetails(
        structuredQuestions: List<String>,
        confirmedContext: String,
        questionDecisions: Map<Int, ClarificationQuestionDecision>,
    ): Map<Int, String> {
        if (structuredQuestions.isEmpty() || questionDecisions.isEmpty()) {
            return emptyMap()
        }
        val lines = confirmedContext
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
        if (lines.isEmpty()) {
            return emptyMap()
        }
        return questionDecisions.entries
            .asSequence()
            .filter { it.value == ClarificationQuestionDecision.CONFIRMED }
            .mapNotNull { (index, _) ->
                val normalizedQuestion = normalizeComparableText(structuredQuestions.getOrNull(index).orEmpty())
                if (normalizedQuestion.isBlank()) {
                    return@mapNotNull null
                }
                val questionLineIndex = lines.indexOfFirst { line ->
                    normalizeComparableText(line).contains(normalizedQuestion)
                }
                if (questionLineIndex < 0) {
                    return@mapNotNull null
                }
                val detail = extractChecklistDetail(lines, questionLineIndex)
                if (detail.isBlank()) {
                    null
                } else {
                    index to detail
                }
            }
            .toMap()
    }

    private fun extractChecklistDetail(lines: List<String>, questionLineIndex: Int): String {
        for (lineIndex in (questionLineIndex + 1) until lines.size) {
            val rawLine = lines[lineIndex]
            val trimmed = rawLine.trim()
            if (trimmed.isBlank()) {
                continue
            }
            if (trimmed.startsWith("#")) {
                break
            }
            if (trimmed.startsWith("- ") && !DETAIL_LINE_REGEX.containsMatchIn(trimmed)) {
                break
            }
            val detailMatch = DETAIL_LINE_REGEX.find(trimmed)
            if (detailMatch != null) {
                return detailMatch.groupValues[2].trim()
            }
        }
        return ""
    }

    private fun mapContextSections(lines: List<String>): Map<Int, ClarificationContextSection> {
        val sectionByLine = mutableMapOf<Int, ClarificationContextSection>()
        var current = ClarificationContextSection.OTHER
        lines.forEachIndexed { index, line ->
            val normalized = normalizeComparableText(line)
            when {
                normalized.isBlank() -> Unit
                clarificationConfirmedSectionMarkers().any { marker -> normalized.contains(marker) } -> {
                    current = ClarificationContextSection.CONFIRMED
                }
                clarificationNotApplicableSectionMarkers().any { marker -> normalized.contains(marker) } -> {
                    current = ClarificationContextSection.NOT_APPLICABLE
                }
            }
            sectionByLine[index] = current
        }
        return sectionByLine
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

    private enum class ClarificationContextSection {
        CONFIRMED,
        NOT_APPLICABLE,
        OTHER,
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
        val checklistMode = clarificationState?.structuredQuestions?.isNotEmpty() == true
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
        if (!::composerSectionBodyContainer.isInitialized) {
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
        composerSectionBodyContainer.isVisible = expanded
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

    private data class ChecklistQuestionSegment(
        val text: String,
        val bold: Boolean,
        val inlineCode: Boolean = false,
    )

    private fun onClarificationInputEdited() {
        updateClarificationPreview()
        persistClarificationDraftSnapshot()
    }

    private fun updateClarificationPreview() {
        val state = clarificationState ?: return
        if (state.structuredQuestions.isNotEmpty()) {
            renderChecklistPreview(state)
            return
        }
        val content = inputArea.text.trim().ifBlank {
            SpecCodingBundle.message("spec.detail.clarify.preview.empty")
        }
        runCatching {
            MarkdownRenderer.render(clarificationPreviewPane, content)
            clarificationPreviewPane.caretPosition = 0
        }.onFailure {
            clarificationPreviewPane.text = content
            clarificationPreviewPane.caretPosition = 0
        }
    }

    private fun renderChecklistPreview(state: ClarificationState) {
        val doc = clarificationPreviewPane.styledDocument
        val confirmedQuestionDetails = state.questionDecisions.entries
            .filter { it.value == ClarificationQuestionDecision.CONFIRMED }
            .sortedBy { it.key }
            .mapNotNull { entry ->
                val question = state.structuredQuestions.getOrNull(entry.key)?.trim().orEmpty()
                if (question.isBlank()) {
                    null
                } else {
                    val detail = state.questionDetails[entry.key]
                        ?.replace("\r\n", "\n")
                        ?.replace('\r', '\n')
                        ?.lineSequence()
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.joinToString(" ")
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()
                        .orEmpty()
                    question to detail
                }
            }
        val notApplicableQuestions = state.questionDecisions.entries
            .filter { it.value == ClarificationQuestionDecision.NOT_APPLICABLE }
            .sortedBy { it.key }
            .mapNotNull { entry -> state.structuredQuestions.getOrNull(entry.key) }
            .map { it.trim() }
            .filter { it.isNotBlank() }

        runCatching {
            doc.remove(0, doc.length)
            fun appendNewline() {
                doc.insertString(doc.length, "\n", SimpleAttributeSet())
            }
            val baseFont = JBUI.Fonts.smallFont()
            val bodyAttrs = SimpleAttributeSet().apply {
                StyleConstants.setFontFamily(this, baseFont.family)
                StyleConstants.setFontSize(this, baseFont.size)
                StyleConstants.setForeground(this, TREE_TEXT)
            }
            val titleAttrs = SimpleAttributeSet(bodyAttrs).apply {
                StyleConstants.setBold(this, true)
                StyleConstants.setForeground(this, SECTION_TITLE_FG)
            }
            val questionBoldAttrs = SimpleAttributeSet(bodyAttrs).apply {
                StyleConstants.setBold(this, true)
            }
            val questionCodeAttrs = SimpleAttributeSet(bodyAttrs).apply {
                StyleConstants.setFontFamily(this, "JetBrains Mono")
                StyleConstants.setBackground(this, CLARIFY_PREVIEW_QUESTION_CODE_BG)
                StyleConstants.setForeground(this, CLARIFY_PREVIEW_QUESTION_CODE_FG)
            }
            val detailChipAttrs = SimpleAttributeSet(bodyAttrs).apply {
                StyleConstants.setBold(this, true)
                StyleConstants.setBackground(this, CLARIFY_PREVIEW_DETAIL_BG)
                StyleConstants.setForeground(this, CLARIFY_PREVIEW_DETAIL_FG)
            }
            val mutedAttrs = SimpleAttributeSet(bodyAttrs).apply {
                StyleConstants.setForeground(this, TREE_FILE_TEXT)
            }

            if (confirmedQuestionDetails.isEmpty() && notApplicableQuestions.isEmpty()) {
                doc.insertString(doc.length, SpecCodingBundle.message("spec.detail.clarify.preview.empty"), mutedAttrs)
                clarificationPreviewPane.caretPosition = 0
                return
            }

            if (confirmedQuestionDetails.isNotEmpty()) {
                doc.insertString(doc.length, SpecCodingBundle.message("spec.detail.clarify.confirmed.title"), titleAttrs)
                appendNewline()
                val detailPrefix = SpecCodingBundle.message("spec.detail.clarify.checklist.detail.exportPrefix")
                confirmedQuestionDetails.forEachIndexed { idx, (question, detail) ->
                    if (idx > 0) {
                        appendNewline()
                    }
                    doc.insertString(doc.length, "• ", bodyAttrs)
                    appendInlineMarkdownStyled(
                        doc = doc,
                        text = question,
                        plainAttrs = bodyAttrs,
                        boldAttrs = questionBoldAttrs,
                        codeAttrs = questionCodeAttrs,
                    )
                    if (detail.isNotBlank()) {
                        doc.insertString(doc.length, "  ", bodyAttrs)
                        doc.insertString(doc.length, " $detailPrefix: $detail ", detailChipAttrs)
                    }
                }
            }

            if (notApplicableQuestions.isNotEmpty()) {
                if (confirmedQuestionDetails.isNotEmpty()) {
                    appendNewline()
                    appendNewline()
                }
                doc.insertString(doc.length, SpecCodingBundle.message("spec.detail.clarify.notApplicable.title"), titleAttrs)
                appendNewline()
                notApplicableQuestions.forEachIndexed { idx, question ->
                    if (idx > 0) {
                        appendNewline()
                    }
                    doc.insertString(doc.length, "• ", bodyAttrs)
                    appendInlineMarkdownStyled(
                        doc = doc,
                        text = question,
                        plainAttrs = bodyAttrs,
                        boldAttrs = questionBoldAttrs,
                        codeAttrs = questionCodeAttrs,
                    )
                }
            }
            clarificationPreviewPane.caretPosition = 0
        }.onFailure {
            val fallbackContent = buildChecklistPreviewMarkdown(state).ifBlank {
                SpecCodingBundle.message("spec.detail.clarify.preview.empty")
            }
            clarificationPreviewPane.text = fallbackContent
            clarificationPreviewPane.caretPosition = 0
        }
    }

    private fun appendInlineMarkdownStyled(
        doc: javax.swing.text.StyledDocument,
        text: String,
        plainAttrs: SimpleAttributeSet,
        boldAttrs: SimpleAttributeSet,
        codeAttrs: SimpleAttributeSet,
    ) {
        val tokens = tokenizeInlineMarkdown(text)
        tokens.forEach { token ->
            val attrs = when (token) {
                is InlineMarkdownToken.Plain -> plainAttrs
                is InlineMarkdownToken.Bold -> boldAttrs
                is InlineMarkdownToken.Code -> codeAttrs
            }
            if (token.text.isNotEmpty()) {
                doc.insertString(doc.length, token.text, attrs)
            }
        }
    }

    private fun tokenizeInlineMarkdown(text: String): List<InlineMarkdownToken> {
        val tokens = mutableListOf<InlineMarkdownToken>()
        var pos = 0
        while (pos < text.length) {
            if (text[pos] == '`') {
                val end = text.indexOf('`', pos + 1)
                if (end > pos) {
                    tokens += InlineMarkdownToken.Code(text.substring(pos + 1, end))
                    pos = end + 1
                    continue
                }
            }

            if (pos + 1 < text.length && text[pos] == '*' && text[pos + 1] == '*') {
                val end = text.indexOf("**", pos + 2)
                if (end > pos) {
                    tokens += InlineMarkdownToken.Bold(text.substring(pos + 2, end))
                    pos = end + 2
                    continue
                }
            }

            val nextSpecial = findNextInlineMarkdownSpecial(text, pos + 1)
            tokens += InlineMarkdownToken.Plain(text.substring(pos, nextSpecial))
            pos = nextSpecial
        }
        return tokens
    }

    private fun findNextInlineMarkdownSpecial(text: String, from: Int): Int {
        for (i in from until text.length) {
            if (text[i] == '`' || text[i] == '*') {
                return i
            }
        }
        return text.length
    }

    private sealed class InlineMarkdownToken(open val text: String) {
        data class Plain(override val text: String) : InlineMarkdownToken(text)
        data class Bold(override val text: String) : InlineMarkdownToken(text)
        data class Code(override val text: String) : InlineMarkdownToken(text)
    }

    private fun buildChecklistPreviewMarkdown(state: ClarificationState): String {
        val confirmedQuestionDetails = state.questionDecisions.entries
            .filter { it.value == ClarificationQuestionDecision.CONFIRMED }
            .sortedBy { it.key }
            .mapNotNull { entry ->
                val question = state.structuredQuestions.getOrNull(entry.key)?.trim().orEmpty()
                if (question.isBlank()) {
                    null
                } else {
                    val detail = state.questionDetails[entry.key]
                        ?.replace("\r\n", "\n")
                        ?.replace('\r', '\n')
                        ?.lineSequence()
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.joinToString(" ")
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()
                        .orEmpty()
                    question to detail
                }
            }
        val notApplicableQuestions = state.questionDecisions.entries
            .filter { it.value == ClarificationQuestionDecision.NOT_APPLICABLE }
            .sortedBy { it.key }
            .mapNotNull { entry -> state.structuredQuestions.getOrNull(entry.key) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (confirmedQuestionDetails.isEmpty() && notApplicableQuestions.isEmpty()) {
            return ""
        }
        val detailPrefix = SpecCodingBundle.message("spec.detail.clarify.checklist.detail.exportPrefix")
        return buildString {
            if (confirmedQuestionDetails.isNotEmpty()) {
                appendLine("**${SpecCodingBundle.message("spec.detail.clarify.confirmed.title")}**")
                confirmedQuestionDetails.forEach { (question, detail) ->
                    if (detail.isNotBlank()) {
                        val escaped = detail.replace('`', '\'')
                        appendLine("- $question  `$detailPrefix: $escaped`")
                    } else {
                        appendLine("- $question")
                    }
                }
            }
            if (notApplicableQuestions.isNotEmpty()) {
                if (confirmedQuestionDetails.isNotEmpty()) {
                    appendLine()
                }
                appendLine("**${SpecCodingBundle.message("spec.detail.clarify.notApplicable.title")}**")
                notApplicableQuestions.forEach { question ->
                    appendLine("- $question")
                }
            }
        }.trimEnd()
    }

    private fun persistClarificationDraftSnapshot(state: ClarificationState? = clarificationState) {
        val snapshot = state ?: return
        onClarificationDraftAutosave(
            snapshot.input,
            resolveClarificationConfirmedContext(snapshot),
            snapshot.questionsMarkdown,
            snapshot.structuredQuestions,
        )
    }

    private fun refreshClarificationSectionsLayout() {
        if (!::clarificationSplitPane.isInitialized) {
            return
        }
        if (::clarificationQuestionsBodyContainer.isInitialized) {
            clarificationQuestionsBodyContainer.isVisible = isClarificationQuestionsExpanded
        }
        val previewBodyVisible = isClarificationPreviewContentVisible && isClarificationPreviewExpanded
        if (::clarificationPreviewBodyContainer.isInitialized) {
            clarificationPreviewBodyContainer.isVisible = previewBodyVisible
        }
        if (::clarificationQuestionsToggleButton.isInitialized) {
            updateCollapseToggleButton(
                clarificationQuestionsToggleButton,
                expanded = isClarificationQuestionsExpanded,
                enabled = true,
            )
        }
        if (::clarificationPreviewToggleButton.isInitialized) {
            updateCollapseToggleButton(
                clarificationPreviewToggleButton,
                expanded = isClarificationPreviewExpanded,
                enabled = isClarificationPreviewContentVisible,
            )
        }
        if (isClarificationPreviewContentVisible) {
            clarificationPreviewSection.isVisible = true
            if (clarificationSplitPane.bottomComponent == null) {
                clarificationSplitPane.bottomComponent = clarificationPreviewSection
            }
            clarificationSplitPane.resizeWeight = 0.58
            clarificationSplitPane.dividerSize = JBUI.scale(4)
            SwingUtilities.invokeLater {
                if (!::clarificationSplitPane.isInitialized) {
                    return@invokeLater
                }
                val total = clarificationSplitPane.height - clarificationSplitPane.dividerSize
                if (total <= 0) {
                    return@invokeLater
                }
                val collapsedSectionHeight = JBUI.scale(36)
                val minTop = collapsedSectionHeight
                val minBottom = collapsedSectionHeight
                val maxTop = (total - minBottom).coerceAtLeast(minTop)
                val target = when {
                    !isClarificationQuestionsExpanded && isClarificationPreviewExpanded -> minTop
                    isClarificationQuestionsExpanded && !isClarificationPreviewExpanded -> maxTop
                    !isClarificationQuestionsExpanded && !isClarificationPreviewExpanded -> minTop
                    else -> (total * 0.58).toInt()
                }.coerceIn(minTop, maxTop)
                clarificationSplitPane.dividerLocation = target
                clarificationSplitPane.revalidate()
                clarificationSplitPane.repaint()
            }
        } else {
            clarificationPreviewSection.isVisible = false
            clarificationSplitPane.bottomComponent = null
            clarificationSplitPane.resizeWeight = 1.0
            clarificationSplitPane.dividerSize = 0
        }
        clarificationSplitPane.revalidate()
        clarificationSplitPane.repaint()
    }

    private fun setClarificationPreviewVisible(visible: Boolean) {
        isClarificationPreviewContentVisible = visible
        refreshClarificationSectionsLayout()
    }

    private fun applyProcessTimelineCollapseState() {
        if (!::processTimelineBodyContainer.isInitialized) {
            return
        }
        processTimelineBodyContainer.isVisible = isProcessTimelineExpanded
        if (::processTimelineToggleButton.isInitialized) {
            updateCollapseToggleButton(
                processTimelineToggleButton,
                expanded = isProcessTimelineExpanded,
                enabled = true,
            )
        }
        if (::processTimelineSection.isInitialized) {
            processTimelineSection.revalidate()
            processTimelineSection.repaint()
        }
    }

    private fun setProcessTimelineVisible(visible: Boolean) {
        if (!::processTimelineSection.isInitialized) {
            return
        }
        processTimelineSection.isVisible = visible
        if (visible) {
            applyProcessTimelineCollapseState()
        }
        processTimelineSection.revalidate()
        processTimelineSection.repaint()
    }

    private fun renderProcessTimeline() {
        if (processTimelineEntries.isEmpty()) {
            processTimelinePane.text = ""
            setProcessTimelineVisible(false)
            return
        }
        setProcessTimelineVisible(true)
        val markdown = buildString {
            processTimelineEntries.forEach { entry ->
                appendLine("- ${processStatePrefix(entry.state)} ${entry.text}")
            }
        }.trimEnd()
        runCatching {
            MarkdownRenderer.render(processTimelinePane, markdown)
            processTimelinePane.caretPosition = 0
        }.onFailure {
            processTimelinePane.text = processTimelineEntries.joinToString("\n") { entry ->
                "${processStatePrefix(entry.state)} ${entry.text}"
            }
            processTimelinePane.caretPosition = 0
        }
    }

    private fun processStatePrefix(state: ProcessTimelineState): String {
        return when (state) {
            ProcessTimelineState.INFO -> "•"
            ProcessTimelineState.ACTIVE -> "→"
            ProcessTimelineState.DONE -> "✓"
            ProcessTimelineState.FAILED -> "✕"
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
        isGeneratingActive = false
        isClarificationGenerating = false
        stopGeneratingAnimation()
        setClarificationChecklistReadOnly(false)
        val workflow = currentWorkflow
        if (workflow == null) {
            setValidationMessage(SpecCodingBundle.message("spec.detail.noWorkflow"))
            return
        }
        updateButtonStates(workflow)
        if (clarificationState != null) {
            setClarificationPreviewVisible(!isClarificationGenerating)
            switchPreviewCard(CARD_CLARIFY)
            updateClarificationPreview()
        } else {
            val phase = selectedPhase ?: workflow.currentPhase
            showDocumentPreview(phase, keepGeneratingIndicator = false)
        }
    }

    fun showValidationFailureInteractive(
        phase: SpecPhase,
        validation: ValidationResult,
    ) {
        isGeneratingActive = false
        isClarificationGenerating = false
        stopGeneratingAnimation()
        setClarificationChecklistReadOnly(false)
        renderPreviewMarkdown(buildValidationPreviewMarkdown(phase, validation))
        switchPreviewCard(CARD_PREVIEW)
        setValidationMessage(
            buildValidationFailureLabel(validation),
            JBColor(java.awt.Color(244, 67, 54), java.awt.Color(239, 83, 80)),
        )
        if (inputArea.text.isBlank()) {
            inputArea.text = buildValidationRepairTemplate(validation)
            inputArea.caretPosition = inputArea.text.length
        }
        currentWorkflow?.let { updateButtonStates(it) } ?: disableAllButtons()
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
        if (!keepGeneratingIndicator || !isGeneratingActive) {
            stopGeneratingAnimation()
        }
        val doc = currentWorkflow?.documents?.get(phase)
        val workbenchBinding = workbenchArtifactBinding?.takeIf { it.documentPhase == phase }
        if (doc != null) {
            if (doc.content.isBlank() && !workbenchBinding?.emptyStateMessage.isNullOrBlank()) {
                renderPreviewMarkdown(workbenchBinding?.emptyStateMessage.orEmpty())
            } else {
                renderPreviewMarkdown(doc.content, interactivePhase = phase)
            }
            val vr = doc.validationResult
            if (isGeneratingActive && keepGeneratingIndicator) {
                updateGeneratingLabel()
            } else {
                if (vr != null) {
                    setValidationMessage(
                        if (vr.valid) {
                            SpecCodingBundle.message("spec.detail.validation.passed")
                        } else {
                            SpecCodingBundle.message("spec.detail.validation.failed")
                        },
                        if (vr.valid) {
                            JBColor(java.awt.Color(76, 175, 80), java.awt.Color(76, 175, 80))
                        } else {
                            JBColor(java.awt.Color(244, 67, 54), java.awt.Color(239, 83, 80))
                        },
                    )
                } else {
                    clearValidationMessage()
                }
            }
        } else {
            renderPreviewMarkdown(
                workbenchBinding?.emptyStateMessage
                    ?: SpecCodingBundle.message("spec.detail.noDocumentForPhase", phaseDisplayText(phase)),
            )
            if (isGeneratingActive && keepGeneratingIndicator) {
                updateGeneratingLabel()
            } else {
                setValidationMessage(workbenchBinding?.unavailableMessage, JBColor.GRAY)
            }
        }
        currentWorkflow
            ?.takeIf { !isGeneratingActive && !isEditing }
            ?.let { workflow ->
                if (currentReadOnlyRevisionLockedPhase(workflow) == phase) {
                    setValidationMessage(revisionLockedHint(phase), JBColor.GRAY)
                }
            }
    }

    private fun renderPreviewMarkdown(content: String, interactivePhase: SpecPhase? = null) {
        val normalizedRaw = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        val sanitized = SpecMarkdownSanitizer.sanitize(normalizedRaw)
        val displayContent = choosePreviewContent(rawContent = normalizedRaw, sanitizedContent = sanitized)
        previewSourceText = displayContent
        val workflow = currentWorkflow
        previewChecklistInteraction = if (
            interactivePhase != null &&
            displayContent == normalizedRaw &&
            (workflow == null || !isReadOnlyRevisionLocked(workflow, interactivePhase))
        ) {
            PreviewChecklistInteraction(
                phase = interactivePhase,
                content = normalizedRaw,
            )
        } else {
            null
        }
        refreshPreviewChecklistCursor(null)
        runCatching {
            MarkdownRenderer.render(previewPane, displayContent)
            previewPane.caretPosition = 0
        }.onFailure {
            previewPane.text = displayContent
            previewPane.caretPosition = 0
        }
        refreshPreviewChecklistCursor(null)
    }

    private fun togglePreviewChecklistAt(event: MouseEvent) {
        if (isEditing || clarificationState != null || isPreviewChecklistSaving) {
            return
        }
        val lineIndex = resolvePreviewChecklistLineIndex(event) ?: return
        togglePreviewChecklistLine(lineIndex)
    }

    private fun togglePreviewChecklistLine(lineIndex: Int) {
        val interaction = previewChecklistInteraction ?: return
        val updatedContent = toggleChecklistLine(interaction.content, lineIndex) ?: return
        if (updatedContent == interaction.content) {
            return
        }

        isPreviewChecklistSaving = true
        refreshPreviewChecklistCursor(null)
        onSaveDocument(interaction.phase, updatedContent) { result ->
            isPreviewChecklistSaving = false
            result.onSuccess { updated ->
                currentWorkflow = updated
                updateWorkflow(updated)
            }.onFailure {
                currentWorkflow?.let(::updateButtonStates)
            }
            refreshPreviewChecklistCursor(null)
        }
    }

    private fun refreshPreviewChecklistCursor(event: MouseEvent?) {
        val cursor = when {
            isPreviewChecklistSaving -> Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
            previewChecklistInteraction != null &&
                !isEditing &&
                clarificationState == null &&
                event != null &&
                resolvePreviewChecklistLineIndex(event) != null -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            else -> Cursor.getDefaultCursor()
        }
        if (previewPane.cursor != cursor) {
            previewPane.cursor = cursor
        }
    }

    private fun resolvePreviewChecklistLineIndex(event: MouseEvent): Int? {
        val documentLength = previewPane.document.length
        if (documentLength <= 0) {
            return null
        }
        val position = previewPane.viewToModel2D(event.point)
        if (position < 0) {
            return null
        }
        val safePosition = position.coerceIn(0, documentLength - 1)
        val paragraph = previewPane.styledDocument.getParagraphElement(safePosition)
        return MarkdownRenderer.extractChecklistLineIndex(paragraph.attributes)
    }

    private fun toggleChecklistLine(content: String, lineIndex: Int): String? {
        val lines = content.lines().toMutableList()
        if (lineIndex !in lines.indices) {
            return null
        }
        val match = PREVIEW_CHECKLIST_LINE_REGEX.matchEntire(lines[lineIndex]) ?: return null
        val toggledMarker = if (match.groupValues[2].equals("x", ignoreCase = true)) " " else "x"
        lines[lineIndex] = buildString {
            append(match.groupValues[1])
            append('[')
            append(toggledMarker)
            append(']')
            append(match.groupValues[3])
        }
        return lines.joinToString("\n")
    }

    private fun choosePreviewContent(rawContent: String, sanitizedContent: String): String {
        val raw = rawContent.trim()
        val sanitized = sanitizedContent.trim()
        if (raw.isBlank()) return sanitized
        if (sanitized.isBlank()) return raw

        if (shouldUseSanitizedPreview(raw)) {
            return sanitized
        }
        if (shouldPreferRawPreview(raw, sanitized)) {
            return raw
        }
        return sanitized
    }

    private fun shouldUseSanitizedPreview(rawContent: String): Boolean {
        if (TOOL_NOISE_MARKER_REGEX.containsMatchIn(rawContent)) return true
        val trimmed = rawContent.trimStart()
        if (trimmed.startsWith("{") && trimmed.contains("\"content\"")) return true
        val escapedNewlineCount = ESCAPED_NEWLINE_REGEX.findAll(rawContent).count()
        val realNewlineCount = rawContent.count { it == '\n' }
        return escapedNewlineCount >= 2 && escapedNewlineCount > realNewlineCount
    }

    private fun shouldPreferRawPreview(rawContent: String, sanitizedContent: String): Boolean {
        if (!CODE_FENCE_MARKER_REGEX.containsMatchIn(rawContent)) return false

        val rawLooksDocument = rawContent
            .lineSequence()
            .take(MAX_PREVIEW_DOC_SCAN_LINES)
            .any { line ->
                val trimmed = line.trim()
                HEADING_LINE_REGEX.matches(trimmed) || LIST_OR_CHECKBOX_LINE_REGEX.matches(trimmed)
            }
        if (!rawLooksDocument) return false

        val sanitizedLooksDocument = sanitizedContent
            .lineSequence()
            .take(MAX_PREVIEW_DOC_SCAN_LINES)
            .any { line ->
                val trimmed = line.trim()
                HEADING_LINE_REGEX.matches(trimmed) || LIST_OR_CHECKBOX_LINE_REGEX.matches(trimmed)
            }
        if (sanitizedLooksDocument) return false

        val ratio = sanitizedContent.length.toDouble() / rawContent.length.toDouble()
        return ratio <= PREVIEW_SANITIZE_COLLAPSE_RATIO
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
        val text = if (isClarificationGenerating) {
            ArtifactComposeActionUiText.clarificationGenerating(currentComposeActionMode())
        } else {
            ArtifactComposeActionUiText.activeProgress(currentComposeActionMode(), generatingPercent)
        }
        setValidationMessage("$text $frame", GENERATING_FG)
    }

    private fun updateButtonStates(workflow: SpecWorkflow) {
        applyActionButtonPresentation()
        val inProgress = workflow.status == WorkflowStatus.IN_PROGRESS
        val allowEditing = !isGeneratingActive
        val clarifying = clarificationState != null
        val clarificationLocked = clarifying && isClarificationChecklistReadOnly
        val composeMode = currentComposeActionMode()
        val revisionLockedPhase = currentReadOnlyRevisionLockedPhase(workflow)
        val standardModeEnabled = inProgress &&
            !isEditing &&
            !clarifying &&
            !isGeneratingActive &&
            revisionLockedPhase == null
        val artifactOnlyView = isWorkbenchArtifactOnlyView()
        val selectedDocumentAvailable = selectedPhase?.let { currentWorkflow?.documents?.containsKey(it) } == true
        val artifactOpenAvailable = artifactOnlyView && workbenchArtifactBinding?.available == true

        generateButton.isVisible = !clarifying
        nextPhaseButton.isVisible = !clarifying
        goBackButton.isVisible = false
        completeButton.isVisible = false
        pauseResumeButton.isVisible = false
        openEditorButton.isVisible = !clarifying
        historyDiffButton.isVisible = !clarifying && !artifactOnlyView
        editButton.isVisible = !clarifying && !isEditing && !artifactOnlyView
        saveButton.isVisible = !clarifying && isEditing
        cancelEditButton.isVisible = !clarifying && isEditing

        confirmGenerateButton.isVisible = clarifying
        regenerateClarificationButton.isVisible = clarifying
        skipClarificationButton.isVisible = clarifying
        cancelClarificationButton.isVisible = clarifying

        setActionEnabled(
            button = generateButton,
            enabled = standardModeEnabled,
            disabledReason = revisionLockedPhase?.let(::revisionLockedDisabledReason) ?: ArtifactComposeActionUiText.primaryActionDisabledReason(
                mode = composeMode,
                status = workflow.status,
                isGeneratingActive = isGeneratingActive,
                isEditing = isEditing,
            ),
        )
        setActionEnabled(button = nextPhaseButton, enabled = workflow.canProceedToNext() && standardModeEnabled)
        setActionEnabled(button = goBackButton, enabled = false)
        setActionEnabled(button = completeButton, enabled = false)
        setActionEnabled(button = pauseResumeButton, enabled = false)
        updatePauseResumeButtonPresentation(workflow.status)
        styleActionButton(pauseResumeButton)
        setActionEnabled(
            button = openEditorButton,
            enabled = !isEditing && !clarifying && (selectedDocumentAvailable || artifactOpenAvailable),
        )
        setActionEnabled(
            button = historyDiffButton,
            enabled = !artifactOnlyView && !isEditing && !clarifying && selectedDocumentAvailable,
        )
        setActionEnabled(
            button = editButton,
            enabled = !artifactOnlyView && !isEditing && allowEditing && !clarifying && resolveEditablePhase(workflow) != null,
        )
        setActionEnabled(button = saveButton, enabled = isEditing)
        setActionEnabled(button = cancelEditButton, enabled = isEditing)
        setActionEnabled(
            button = confirmGenerateButton,
            enabled = clarifying && inProgress && !isGeneratingActive && !clarificationLocked,
            disabledReason = ArtifactComposeActionUiText.clarificationConfirmDisabledReason(
                mode = composeMode,
                status = workflow.status,
                isGeneratingActive = isGeneratingActive,
                clarificationLocked = clarificationLocked,
            ),
        )
        setActionEnabled(
            button = regenerateClarificationButton,
            enabled = clarifying && inProgress && !isGeneratingActive && !clarificationLocked,
        )
        setActionEnabled(
            button = skipClarificationButton,
            enabled = clarifying && inProgress && !isGeneratingActive && !clarificationLocked,
        )
        setActionEnabled(
            button = cancelClarificationButton,
            enabled = clarifying && !isGeneratingActive && !clarificationLocked,
        )
        refreshInputAreaMode()
    }

    private fun disableAllButtons() {
        setActionEnabled(button = generateButton, enabled = false)
        setActionEnabled(button = nextPhaseButton, enabled = false)
        setActionEnabled(button = goBackButton, enabled = false)
        setActionEnabled(button = completeButton, enabled = false)
        setActionEnabled(button = pauseResumeButton, enabled = false)
        setActionEnabled(button = openEditorButton, enabled = false)
        setActionEnabled(button = historyDiffButton, enabled = false)
        setActionEnabled(button = editButton, enabled = false)
        setActionEnabled(button = saveButton, enabled = false)
        setActionEnabled(button = cancelEditButton, enabled = false)
        setActionEnabled(button = confirmGenerateButton, enabled = false)
        setActionEnabled(button = regenerateClarificationButton, enabled = false)
        setActionEnabled(button = skipClarificationButton, enabled = false)
        setActionEnabled(button = cancelClarificationButton, enabled = false)
        refreshActionButtonCursors()
    }

    fun clearInput() {
        if (clarificationState?.structuredQuestions?.isNotEmpty() == true) {
            syncClarificationInputFromSelection(clarificationState)
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
        return previewSourceText
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
        togglePreviewChecklistLine(lineIndex)
    }

    internal fun areDocumentTabsVisibleForTest(): Boolean = false

    internal fun toggleClarificationQuestionForTest(index: Int) {
        val currentDecision = clarificationState
            ?.questionDecisions
            ?.get(index)
            ?: ClarificationQuestionDecision.UNDECIDED
        val nextDecision = if (currentDecision == ClarificationQuestionDecision.CONFIRMED) {
            ClarificationQuestionDecision.UNDECIDED
        } else {
            ClarificationQuestionDecision.CONFIRMED
        }
        onChecklistQuestionDecisionChanged(index, nextDecision)
    }

    internal fun markClarificationQuestionNotApplicableForTest(index: Int) {
        val currentDecision = clarificationState
            ?.questionDecisions
            ?.get(index)
            ?: ClarificationQuestionDecision.UNDECIDED
        val nextDecision = if (currentDecision == ClarificationQuestionDecision.NOT_APPLICABLE) {
            ClarificationQuestionDecision.UNDECIDED
        } else {
            ClarificationQuestionDecision.NOT_APPLICABLE
        }
        onChecklistQuestionDecisionChanged(index, nextDecision)
    }

    internal fun currentChecklistDecisionForTest(index: Int): String? {
        val state = clarificationState ?: return null
        return (state.questionDecisions[index] ?: ClarificationQuestionDecision.UNDECIDED).name
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

    internal fun parseChecklistQuestionSegmentsForTest(question: String): List<Pair<String, Boolean>> {
        return parseChecklistQuestionSegments(question).map { it.text to it.bold }
    }

    internal fun parseChecklistQuestionSegmentsWithStyleForTest(
        question: String,
    ): List<Triple<String, Boolean, Boolean>> {
        return parseChecklistQuestionSegments(question).map { segment ->
            Triple(segment.text, segment.bold, segment.inlineCode)
        }
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
        if (::composerSectionToggleButton.isInitialized) {
            composerSectionToggleButton.doClick()
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
        if (::processTimelineToggleButton.isInitialized) {
            processTimelineToggleButton.doClick()
        }
    }

    internal fun toggleClarificationQuestionsExpandedForTest() {
        if (::clarificationQuestionsToggleButton.isInitialized) {
            clarificationQuestionsToggleButton.doClick()
        }
    }

    internal fun toggleClarificationPreviewExpandedForTest() {
        if (::clarificationPreviewToggleButton.isInitialized) {
            clarificationPreviewToggleButton.doClick()
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
        return if (::clarificationQuestionsToggleButton.isInitialized) {
            clarificationQuestionsToggleButton.text
        } else {
            ""
        }
    }

    internal fun clarificationQuestionsToggleHasEnoughWidthForTest(): Boolean {
        if (!::clarificationQuestionsToggleButton.isInitialized) {
            return false
        }
        val button = clarificationQuestionsToggleButton
        val textWidth = button.getFontMetrics(button.font).stringWidth(button.text.orEmpty())
        val horizontalPadding = button.margin.left + button.margin.right + JBUI.scale(14)
        return button.preferredSize.width >= textWidth + horizontalPadding
    }

    internal fun clarificationQuestionsToggleCanFitTextForTest(text: String): Boolean {
        if (!::clarificationQuestionsToggleButton.isInitialized) {
            return false
        }
        clarificationQuestionsToggleButton.text = text
        applyCollapseToggleButtonSize(clarificationQuestionsToggleButton)
        return clarificationQuestionsToggleHasEnoughWidthForTest()
    }

    internal fun clickGenerateForTest() {
        generateButton.doClick()
    }

    internal fun clickEditForTest() {
        editButton.doClick()
    }

    internal fun clickCancelEditForTest() {
        cancelEditButton.doClick()
    }

    internal fun clickConfirmGenerateForTest() {
        confirmGenerateButton.doClick()
    }

    internal fun clickOpenEditorForTest() {
        openEditorButton.doClick()
    }

    internal fun isClarificationPreviewVisibleForTest(): Boolean {
        return isClarificationPreviewContentVisible
    }

    internal fun isClarifyingForTest(): Boolean = clarificationState != null

    internal fun clarificationQuestionsTextForTest(): String = clarificationQuestionsPane.text

    internal fun currentProcessTimelineTextForTest(): String {
        return processTimelinePane.text
    }

    internal fun isProcessTimelineVisibleForTest(): Boolean {
        return ::processTimelineSection.isInitialized && processTimelineSection.isVisible
    }

    internal fun hasLegacyDocumentModeButtonsForTest(): Boolean {
        val legacyLabels = setOf(
            SpecCodingBundle.message("spec.detail.view.preview"),
            SpecCodingBundle.message("spec.detail.view.clarify"),
        )
        return collectButtonTexts(this).any(legacyLabels::contains)
    }

    internal fun buttonStatesForTest(): Map<String, Any> {
        return mapOf(
            "generateEnabled" to generateButton.isEnabled,
            "generateIconId" to SpecWorkflowIcons.debugId(generateButton.icon),
            "generateFocusable" to generateButton.isFocusable,
            "generateTooltip" to generateButton.toolTipText.orEmpty(),
            "generateAccessibleName" to (generateButton.accessibleContext?.accessibleName ?: ""),
            "generateAccessibleDescription" to (generateButton.accessibleContext?.accessibleDescription ?: ""),
            "nextEnabled" to nextPhaseButton.isEnabled,
            "nextIconId" to SpecWorkflowIcons.debugId(nextPhaseButton.icon),
            "nextFocusable" to nextPhaseButton.isFocusable,
            "goBackEnabled" to goBackButton.isEnabled,
            "goBackIconId" to SpecWorkflowIcons.debugId(goBackButton.icon),
            "goBackFocusable" to goBackButton.isFocusable,
            "completeEnabled" to completeButton.isEnabled,
            "completeIconId" to SpecWorkflowIcons.debugId(completeButton.icon),
            "completeFocusable" to completeButton.isFocusable,
            "completeVisible" to completeButton.isVisible,
            "pauseResumeEnabled" to pauseResumeButton.isEnabled,
            "pauseResumeIconId" to SpecWorkflowIcons.debugId(pauseResumeButton.icon),
            "pauseResumeText" to (pauseResumeButton.toolTipText ?: pauseResumeButton.text),
            "pauseResumeFocusable" to pauseResumeButton.isFocusable,
            "pauseResumeVisible" to pauseResumeButton.isVisible,
            "openEditorEnabled" to openEditorButton.isEnabled,
            "openEditorIconId" to SpecWorkflowIcons.debugId(openEditorButton.icon),
            "openEditorFocusable" to openEditorButton.isFocusable,
            "openEditorVisible" to openEditorButton.isVisible,
            "historyDiffEnabled" to historyDiffButton.isEnabled,
            "historyDiffIconId" to SpecWorkflowIcons.debugId(historyDiffButton.icon),
            "historyDiffFocusable" to historyDiffButton.isFocusable,
            "historyDiffVisible" to historyDiffButton.isVisible,
            "editEnabled" to editButton.isEnabled,
            "editVisible" to editButton.isVisible,
            "editTooltip" to editButton.toolTipText.orEmpty(),
            "editAccessibleName" to (editButton.accessibleContext?.accessibleName ?: ""),
            "saveVisible" to saveButton.isVisible,
            "cancelEditVisible" to cancelEditButton.isVisible,
            "inputEnabled" to inputArea.isEnabled,
            "inputEditable" to inputArea.isEditable,
            "inputTooltip" to inputArea.toolTipText.orEmpty(),
            "confirmGenerateEnabled" to confirmGenerateButton.isEnabled,
            "confirmGenerateIconId" to SpecWorkflowIcons.debugId(confirmGenerateButton.icon),
            "confirmGenerateFocusable" to confirmGenerateButton.isFocusable,
            "confirmGenerateTooltip" to confirmGenerateButton.toolTipText.orEmpty(),
            "confirmGenerateAccessibleName" to (confirmGenerateButton.accessibleContext?.accessibleName ?: ""),
            "confirmGenerateAccessibleDescription" to (confirmGenerateButton.accessibleContext?.accessibleDescription ?: ""),
            "regenerateClarificationEnabled" to regenerateClarificationButton.isEnabled,
            "regenerateClarificationIconId" to SpecWorkflowIcons.debugId(regenerateClarificationButton.icon),
            "regenerateClarificationFocusable" to regenerateClarificationButton.isFocusable,
            "skipClarificationEnabled" to skipClarificationButton.isEnabled,
            "skipClarificationIconId" to SpecWorkflowIcons.debugId(skipClarificationButton.icon),
            "skipClarificationFocusable" to skipClarificationButton.isFocusable,
            "cancelClarificationEnabled" to cancelClarificationButton.isEnabled,
            "cancelClarificationIconId" to SpecWorkflowIcons.debugId(cancelClarificationButton.icon),
            "cancelClarificationFocusable" to cancelClarificationButton.isFocusable,
        )
    }

    internal fun documentToolbarActionCountForTest(): Int {
        return 0
    }

    internal fun visibleComposerActionOrderForTest(): List<String> {
        if (!::actionButtonPanel.isInitialized) {
            return emptyList()
        }
        return actionButtonPanel.components.mapNotNull { component ->
            val button = component as? JButton ?: return@mapNotNull null
            if (!button.isVisible) {
                return@mapNotNull null
            }
            composerActionId(button)
        }
    }

    private fun composerActionId(button: JButton): String? {
        return when (button) {
            generateButton -> "generate"
            openEditorButton -> "openEditor"
            historyDiffButton -> "historyDiff"
            editButton -> "edit"
            saveButton -> "save"
            cancelEditButton -> "cancelEdit"
            confirmGenerateButton -> "confirmGenerate"
            regenerateClarificationButton -> "regenerateClarification"
            skipClarificationButton -> "skipClarification"
            cancelClarificationButton -> "cancelClarification"
            else -> null
        }
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

    private fun collectButtons(component: Component): List<JButton> {
        val buttons = mutableListOf<JButton>()
        if (component is JButton) {
            buttons += component
        }
        if (component is Container) {
            component.components.forEach { child ->
                buttons += collectButtons(child)
            }
        }
        return buttons
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

    private fun phaseDisplayText(phase: SpecPhase): String = phase.displayName.lowercase()

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
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
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
        private const val MAX_PROCESS_TIMELINE_ENTRIES = 18
        private const val DOCUMENT_VIEWPORT_HEIGHT = 360
        private const val CARD_PREVIEW = "preview"
        private const val CARD_EDIT = "edit"
        private const val CARD_CLARIFY = "clarify"
        private const val CLARIFY_QUESTIONS_CARD_MARKDOWN = "clarify.questions.markdown"
        private const val CLARIFY_QUESTIONS_CARD_CHECKLIST = "clarify.questions.checklist"
        private val DETAIL_LINE_REGEX = Regex("^-\\s*(detail|details|补充|说明)\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
        private val CODE_FENCE_MARKER_REGEX = Regex("```")
        private val HEADING_LINE_REGEX = Regex("""^\s{0,3}#{1,6}\s+\S+""")
        private val LIST_OR_CHECKBOX_LINE_REGEX = Regex("""^\s*(?:[-*]\s+\S+|\d+\.\s+\S+|-?\s*\[[ xX]\]\s+\S+)""")
        private val PREVIEW_CHECKLIST_LINE_REGEX = Regex("""^(\s*(?:[-*]|\d+[.)])\s*)\[( |x|X)](\s+.*)$""")
        private val TOOL_NOISE_MARKER_REGEX = Regex(
            pattern = """<tool_|"tool_(?:calls?|name|input)"|plan_file_path""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val ESCAPED_NEWLINE_REGEX = Regex("""\\n|\\r\\n""")
        private const val MAX_PREVIEW_DOC_SCAN_LINES = 60
        private const val PREVIEW_SANITIZE_COLLAPSE_RATIO = 0.85
    }
}
