package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.CodeGraphRenderer
import com.eacape.speccodingplugin.context.CodeGraphService
import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.OperationModeManager
import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.llm.MockLlmProvider
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.session.WorkflowChatActionIntent
import com.eacape.speccodingplugin.session.WorkflowChatBinding
import com.eacape.speccodingplugin.session.WorkflowChatEntrySource
import com.eacape.speccodingplugin.spec.*
import com.eacape.speccodingplugin.ui.ChatToolWindowControlListener
import com.eacape.speccodingplugin.ui.ChatToolWindowFactory
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.eacape.speccodingplugin.ui.RefreshFeedback
import com.eacape.speccodingplugin.ui.WorkflowChatRefreshEvent
import com.eacape.speccodingplugin.ui.WorkflowChatRefreshListener
import com.eacape.speccodingplugin.ui.history.HistorySessionOpenListener
import com.eacape.speccodingplugin.ui.WorkflowChatOpenRequest
import com.eacape.speccodingplugin.ui.actions.SpecWorkflowActionSupport
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.eacape.speccodingplugin.window.GlobalConfigChangedEvent
import com.eacape.speccodingplugin.window.GlobalConfigSyncListener
import com.eacape.speccodingplugin.window.WindowSessionIsolationService
import com.eacape.speccodingplugin.ui.worktree.NewWorktreeDialog
import com.eacape.speccodingplugin.worktree.WorktreeManager
import com.eacape.speccodingplugin.worktree.WorktreeStatus
import com.intellij.CommonBundle
import com.intellij.openapi.components.service
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.awt.CardLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.FlowLayout
import java.awt.Font
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.Timer

class SpecWorkflowPanel(
    private val project: Project,
    private val sourceFileChooser: (Project, WorkflowSourceImportConstraints) -> List<Path> = ::chooseWorkflowSourceFiles,
    private val sourceImportConstraints: WorkflowSourceImportConstraints = WorkflowSourceImportConstraints(),
    private val warningDialogPresenter: (Project, String, String) -> Unit = { dialogProject, message, title ->
        Messages.showWarningDialog(dialogProject, message, title)
    },
) : JBPanel<SpecWorkflowPanel>(BorderLayout()), Disposable {

    private enum class DocumentWorkspaceView {
        DOCUMENT,
        STRUCTURED_TASKS,
    }

    private val logger = thisLogger()
    private val specEngine = SpecEngine.getInstance(project)
    private val specDeltaService = SpecDeltaService.getInstance(project)
    private val specTasksService = SpecTasksService.getInstance(project)
    private val specTaskExecutionService = SpecTaskExecutionService.getInstance(project)
    private val specTaskCompletionService = SpecTaskCompletionService.getInstance(project)
    private val specCodeContextService = project.service<SpecCodeContextService>()
    private val specRelatedFilesService = SpecRelatedFilesService.getInstance(project)
    private val specVerificationService = SpecVerificationService.getInstance(project)
    private val specRequirementsQuickFixService = SpecRequirementsQuickFixService(project)
    private val specTasksQuickFixService = SpecTasksQuickFixService(project)
    private val sessionManager = SessionManager.getInstance(project)
    private val sessionIsolationService = WindowSessionIsolationService.getInstance(project)
    private val artifactService = SpecArtifactService(project)
    private val codeGraphService = CodeGraphService.getInstance(project)
    private val worktreeManager = WorktreeManager.getInstance(project)
    private val llmRouter = LlmRouter.getInstance()
    private val modelRegistry = ModelRegistry.getInstance()
    private val modeManager = OperationModeManager.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val discoveryListener: () -> Unit = {
        llmRouter.refreshProviders()
        modelRegistry.refreshFromDiscovery()
        invokeLaterSafe {
            refreshProviderCombo(preserveSelection = true)
        }
    }

    @Volatile
    private var _isDisposed = false

    private val phaseIndicator = SpecPhaseIndicatorPanel()
    private val listPanel: SpecWorkflowListPanel
    private val detailPanel: SpecDetailPanel
    private val overviewPanel = SpecWorkflowOverviewPanel(
        onStageSelected = ::onOverviewStageSelected,
        onWorkbenchActionRequested = ::onWorkbenchActionRequested,
        onTemplateCloneRequested = ::onTemplateCloneRequested,
    )
    private val tasksPanel = SpecWorkflowTasksPanel(
        onTransitionStatus = ::onTaskStatusTransitionRequested,
        onCancelExecution = ::onTaskExecutionCancelRequested,
        onExecuteTask = ::onTaskExecutionRequested,
        onOpenWorkflowChat = ::onTaskWorkflowChatRequested,
        onUpdateDependsOn = ::onTaskDependsOnUpdateRequested,
        onCompleteWithRelatedFiles = ::onTaskCompleteRequested,
        onUpdateVerificationResult = ::onTaskVerificationResultUpdateRequested,
        suggestRelatedFiles = { taskId, existingRelatedFiles ->
            specRelatedFilesService.suggestRelatedFiles(taskId, existingRelatedFiles)
        },
        onTaskSelected = ::onStructuredTaskSelectionChanged,
        showHeader = false,
    )
    private val detailTasksPanel = SpecWorkflowTasksPanel(
        onTransitionStatus = ::onTaskStatusTransitionRequested,
        onCancelExecution = ::onTaskExecutionCancelRequested,
        onExecuteTask = ::onTaskExecutionRequested,
        onOpenWorkflowChat = ::onTaskWorkflowChatRequested,
        onUpdateDependsOn = ::onTaskDependsOnUpdateRequested,
        onCompleteWithRelatedFiles = ::onTaskCompleteRequested,
        onUpdateVerificationResult = ::onTaskVerificationResultUpdateRequested,
        suggestRelatedFiles = { taskId, existingRelatedFiles ->
            specRelatedFilesService.suggestRelatedFiles(taskId, existingRelatedFiles)
        },
        onTaskSelected = ::onStructuredTaskSelectionChanged,
        showHeader = false,
        fixedViewportHeight = DOCUMENT_WORKSPACE_VIEWPORT_HEIGHT,
    )
    private val verifyDeltaPanel = SpecWorkflowVerifyDeltaPanel(
        onRunVerifyRequested = ::onRunVerificationRequested,
        onOpenVerificationRequested = ::onOpenVerificationRequested,
        onCompareBaselineRequested = ::onCompareDeltaBaselineRequested,
        onPinBaselineRequested = ::onPinDeltaBaselineRequested,
        showHeader = false,
    )
    private val gateDetailsPanel = SpecWorkflowGateDetailsPanel(
        project = project,
        showHeader = false,
        onClarifyThenFillRequested = ::startRequirementsClarifyThenFill,
        onRepairRequirementsRequested = ::repairRequirementsArtifactFromGate,
        onRepairTasksRequested = ::repairTasksArtifactFromGate,
    )
    private val statusLabel = JBLabel("")
    private val statusChipPanel = JPanel(BorderLayout())
    private val modelLabel = JBLabel(SpecCodingBundle.message("toolwindow.model.label"))
    private val providerComboBox = ComboBox<String>()
    private val modelComboBox = ComboBox<ModelInfo>()
    private val createWorktreeButton = JButton()
    private val mergeWorktreeButton = JButton()
    private val switchWorkflowButton = JButton()
    private val createWorkflowButton = JButton()
    private val deltaButton = JButton()
    private val codeGraphButton = JButton()
    private val archiveButton = JButton()
    private val backToListButton = JButton()
    private val refreshButton = JButton()
    private val documentWorkspaceViewButtons = linkedMapOf<DocumentWorkspaceView, JButton>()
    private lateinit var centerContentPanel: JPanel
    private lateinit var listSectionContainer: JPanel
    private lateinit var workspacePanelContainer: JPanel
    private lateinit var mainSplitPane: JSplitPane
    private val workspaceCardLayout = CardLayout()
    private val workspaceCardPanel = JPanel(workspaceCardLayout)
    private lateinit var documentWorkspaceViewLabel: JBLabel
    private val workspaceSummaryTitleLabel = JBLabel()
    private val workspaceSummaryMetaLabel = JBLabel()
    private val workspaceSummaryFocusLabel = JBLabel()
    private val workspaceSummaryHintLabel = JBLabel()
    private val workspaceStageMetric = createWorkspaceSummaryMetric()
    private val workspaceGateMetric = createWorkspaceSummaryMetric()
    private val workspaceTasksMetric = createWorkspaceSummaryMetric()
    private val workspaceVerifyMetric = createWorkspaceSummaryMetric()
    private lateinit var overviewSection: SpecCollapsibleWorkspaceSection
    private lateinit var tasksSection: SpecCollapsibleWorkspaceSection
    private lateinit var gateSection: SpecCollapsibleWorkspaceSection
    private lateinit var verifySection: SpecCollapsibleWorkspaceSection
    private lateinit var documentsSection: SpecCollapsibleWorkspaceSection
    private lateinit var documentWorkspaceViewTabsPanel: JPanel
    private lateinit var documentWorkspaceViewSwitcherPanel: JPanel
    private lateinit var documentWorkspaceViewCardPanel: JPanel
    private val workspaceSectionItems = mutableMapOf<SpecWorkflowWorkspaceSectionId, JPanel>()
    private val workspaceSectionOverrides = mutableMapOf<SpecWorkflowWorkspaceSectionId, Boolean>()
    private var workspaceSectionPresetToken: String? = null
    private var currentOverviewState: SpecWorkflowOverviewState? = null
    private var currentWorkbenchState: SpecWorkflowStageWorkbenchState? = null
    private var currentVerifyDeltaState: SpecWorkflowVerifyDeltaState? = null
    private var currentGateResult: GateResult? = null
    private var currentStructuredTasks: List<StructuredTask> = emptyList()
    private var currentTaskLiveProgressByTaskId: Map<String, TaskExecutionLiveProgress> = emptyMap()
    private var currentWorkflowSources: List<WorkflowSourceAsset> = emptyList()
    private val composerSelectedSourceIdsByWorkflowId = mutableMapOf<String, LinkedHashSet<String>>()
    private val pendingClarificationRetryByWorkflowId = mutableMapOf<String, ClarificationRetryPayload>()
    private var activeGenerationJob: Job? = null
    private var activeGenerationRequest: ActiveGenerationRequest? = null
    private var pendingDocumentReloadJob: Job? = null
    private val liveProgressRefreshDispatchQueued = AtomicBoolean(false)
    @Volatile
    private var liveProgressRefreshPending = false
    private val liveProgressListener = TaskExecutionLiveProgressListener { progress ->
        if (progress.workflowId == selectedWorkflowId) {
            requestLiveProgressPresentationRefresh()
        }
    }
    private val liveProgressEventCoalesceTimer = Timer(LIVE_PROGRESS_EVENT_COALESCE_MILLIS) {
        flushPendingLiveProgressPresentationRefresh()
    }.apply {
        isRepeats = false
    }
    private val liveProgressRefreshTimer = Timer(1_000) {
        flushPendingLiveProgressPresentationRefresh(force = true)
    }.apply {
        isRepeats = true
    }

    private var selectedWorkflowId: String? = null
    private var highlightedWorkflowId: String? = null
    private var currentWorkflow: SpecWorkflow? = null
    private var focusedStage: StageId? = null
    private var selectedDocumentWorkspaceView: DocumentWorkspaceView = DocumentWorkspaceView.DOCUMENT
    private var selectedStructuredTaskId: String? = null
    private var isSynchronizingStructuredTaskSelection: Boolean = false
    private var pendingOpenWorkflowRequest: SpecToolWindowOpenRequest? = null
    private var isWorkspaceMode: Boolean = false
    private var detailDividerLocation: Int = 210
    private var workflowSwitcherPopup: SpecWorkflowSwitcherPopup? = null

    init {
        border = JBUI.Borders.empty(8)

        listPanel = SpecWorkflowListPanel(
            onWorkflowFocused = ::onWorkflowFocusedByUser,
            onOpenWorkflow = ::onWorkflowOpenedByUser,
            onCreateWorkflow = ::onCreateWorkflow,
            onEditWorkflow = ::onEditWorkflow,
            onDeleteWorkflow = ::onDeleteWorkflow,
            showCreateButton = false,
        )

        detailPanel = SpecDetailPanel(
            onGenerate = ::onGenerate,
            canGenerateWithEmptyInput = ::canGenerateWithEmptyInput,
            onAddWorkflowSourcesRequested = ::onAddWorkflowSourcesRequested,
            onRemoveWorkflowSourceRequested = ::onRemoveWorkflowSourceRequested,
            onRestoreWorkflowSourcesRequested = ::onRestoreWorkflowSourcesRequested,
            onClarificationConfirm = ::onClarificationConfirm,
            onClarificationRegenerate = ::onClarificationRegenerate,
            onClarificationSkip = ::onClarificationSkip,
            onClarificationCancel = ::onClarificationCancel,
            onNextPhase = ::onNextPhase,
            onGoBack = ::onGoBack,
            onComplete = ::onComplete,
            onPauseResume = ::onPauseResume,
            onOpenInEditor = ::onOpenInEditor,
            onOpenArtifactInEditor = ::onOpenArtifactInEditor,
            onShowHistoryDiff = ::onShowHistoryDiff,
            onSaveDocument = ::onSaveDocument,
            onClarificationDraftAutosave = ::onClarificationDraftAutosave,
        )

        setupUI()
        CliDiscoveryService.getInstance().addDiscoveryListener(discoveryListener)
        subscribeToLocaleEvents()
        subscribeToGlobalConfigEvents()
        subscribeToToolWindowControlEvents()
        subscribeToWorkflowEvents()
        subscribeToDocumentFileEvents()
        specTaskExecutionService.addLiveProgressListener(liveProgressListener)
        refreshWorkflows()
    }

    private fun setupUI() {
        refreshButton.addActionListener { refreshWorkflows(showRefreshFeedback = true) }
        createWorktreeButton.isEnabled = false
        mergeWorktreeButton.isEnabled = false
        switchWorkflowButton.isEnabled = false
        createWorktreeButton.isVisible = false
        mergeWorktreeButton.isVisible = false
        deltaButton.isEnabled = false
        codeGraphButton.isEnabled = true
        archiveButton.isEnabled = false
        codeGraphButton.isVisible = false
        archiveButton.isVisible = false
        backToListButton.isEnabled = false
        switchWorkflowButton.addActionListener { onSwitchWorkflowRequested() }
        createWorkflowButton.addActionListener { onCreateWorkflow() }
        createWorktreeButton.addActionListener { onCreateWorktree() }
        mergeWorktreeButton.addActionListener { onMergeWorktree() }
        deltaButton.addActionListener { onShowDelta() }
        codeGraphButton.addActionListener { onShowCodeGraph() }
        archiveButton.addActionListener { onArchiveWorkflow() }
        backToListButton.addActionListener { onBackToWorkflowListRequested() }

        configureToolbarIconButton(
            button = backToListButton,
            icon = SpecWorkflowIcons.Back,
            tooltipKey = "spec.workflow.backToList",
        )
        styleToolbarButton(backToListButton)

        applyToolbarButtonPresentation()
        styleToolbarButton(switchWorkflowButton)
        styleToolbarButton(createWorkflowButton)
        styleToolbarButton(refreshButton)
        styleToolbarButton(createWorktreeButton)
        styleToolbarButton(mergeWorktreeButton)
        styleToolbarButton(deltaButton)
        styleToolbarButton(codeGraphButton)
        styleToolbarButton(archiveButton)

        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = STATUS_TEXT_FG
        setupGenerationControls()

        val controlsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(providerComboBox)
            add(modelLabel)
            add(modelComboBox)
            add(statusChipPanel)
        }
        val actionsRow = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
            isOpaque = false
            add(switchWorkflowButton)
            add(createWorkflowButton)
            add(refreshButton)
            add(deltaButton)
        }
        val toolbarRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(1, 0)
            add(controlsRow, BorderLayout.CENTER)
            add(actionsRow, BorderLayout.EAST)
        }
        statusChipPanel.isOpaque = true
        statusChipPanel.background = STATUS_CHIP_BG
        statusChipPanel.border = SpecUiStyle.roundedCardBorder(
            lineColor = STATUS_CHIP_BORDER,
            arc = JBUI.scale(10),
            top = 1,
            left = 6,
            bottom = 1,
            right = 6,
        )
        statusChipPanel.removeAll()
        statusChipPanel.add(statusLabel, BorderLayout.CENTER)
        val toolbarCard = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = TOOLBAR_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = TOOLBAR_BORDER,
                arc = JBUI.scale(12),
                top = 4,
                left = 6,
                bottom = 4,
                right = 6,
            )
            add(toolbarRow, BorderLayout.CENTER)
        }
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(3)
                add(toolbarCard, BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )

        listSectionContainer = createSectionContainer(
            listPanel,
            backgroundColor = LIST_SECTION_BG,
            borderColor = LIST_SECTION_BORDER,
        )
        workspacePanelContainer = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = true
            background = DETAIL_COLUMN_BG
            add(buildWorkspaceCardPanel(), BorderLayout.CENTER)
        }

        mainSplitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            listSectionContainer,
            workspacePanelContainer,
        ).apply {
            dividerLocation = detailDividerLocation
            resizeWeight = 0.26
            isContinuousLayout = true
            border = JBUI.Borders.empty()
            background = PANEL_SECTION_BG
            SpecUiStyle.applyChatLikeSpecDivider(
                splitPane = this,
                dividerSize = JBUI.scale(4),
            )
        }
        mainSplitPane.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    clampDividerLocation(mainSplitPane)
                }
            },
        )
        mainSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            if (isWorkspaceMode && mainSplitPane.dividerLocation > 0) {
                detailDividerLocation = mainSplitPane.dividerLocation
            }
        }
        centerContentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        add(centerContentPanel, BorderLayout.CENTER)
        setStatusText(null)
        showWorkspaceEmptyState()
    }

    private fun clampDividerLocation(split: JSplitPane) {
        if (!isWorkspaceMode || split.parent == null) return
        val total = split.width - split.dividerSize
        if (total <= 0) return
        val minLeft = JBUI.scale(120)
        val minRight = JBUI.scale(240)
        val maxLeft = (total - minRight).coerceAtLeast(minLeft)
        val current = split.dividerLocation
        val clamped = current.coerceIn(minLeft, maxLeft)
        if (clamped != current) {
            split.dividerLocation = clamped
        }
        detailDividerLocation = clamped
    }

    private fun showWorkflowListOnlyMode() {
        isWorkspaceMode = false
        reparentToCenter(listSectionContainer)
    }

    private fun showWorkflowWorkspaceMode() {
        isWorkspaceMode = true
        reparentToCenter(workspacePanelContainer)
    }

    private fun reparentToCenter(component: Component) {
        detachFromParent(component)
        if (centerContentPanel.componentCount == 1 && centerContentPanel.getComponent(0) === component) {
            return
        }
        centerContentPanel.removeAll()
        centerContentPanel.add(component, BorderLayout.CENTER)
        centerContentPanel.revalidate()
        centerContentPanel.repaint()
    }

    private fun detachFromParent(component: Component) {
        component.parent?.remove(component)
    }

    private fun createWorkspaceSummaryMetric(): WorkspaceSummaryMetric {
        val titleLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont().deriveFont(10.5f)
            foreground = WORKSPACE_SUMMARY_LABEL_FG
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        val valueLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD, 10.5f)
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        val root = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)).apply {
            isOpaque = false
            isVisible = false
            add(titleLabel)
            add(valueLabel)
        }
        return WorkspaceSummaryMetric(
            root = root,
            titleLabel = titleLabel,
            valueLabel = valueLabel,
        )
    }

    private fun workspaceChipColors(tone: WorkspaceChipTone): WorkspaceChipColors {
        return when (tone) {
            WorkspaceChipTone.INFO -> WorkspaceChipColors(
                foreground = WORKSPACE_INFO_CHIP_FG,
            )

            WorkspaceChipTone.SUCCESS -> WorkspaceChipColors(
                foreground = WORKSPACE_SUCCESS_CHIP_FG,
            )

            WorkspaceChipTone.WARNING -> WorkspaceChipColors(
                foreground = WORKSPACE_WARNING_CHIP_FG,
            )

            WorkspaceChipTone.ERROR -> WorkspaceChipColors(
                foreground = WORKSPACE_ERROR_CHIP_FG,
            )

            WorkspaceChipTone.MUTED -> WorkspaceChipColors(
                foreground = WORKSPACE_MUTED_CHIP_FG,
            )
        }
    }

    private fun buildWorkspaceCardPanel(): JPanel {
        val sectionsStack = object : JPanel(), Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

            override fun getScrollableUnitIncrement(
                visibleRect: Rectangle,
                orientation: Int,
                direction: Int,
            ): Int = JBUI.scale(WORKSPACE_SCROLL_UNIT_INCREMENT)

            override fun getScrollableBlockIncrement(
                visibleRect: Rectangle,
                orientation: Int,
                direction: Int,
            ): Int {
                val unit = getScrollableUnitIncrement(visibleRect, orientation, direction)
                return if (orientation == SwingConstants.VERTICAL) {
                    (visibleRect.height - unit).coerceAtLeast(unit)
                } else {
                    (visibleRect.width - unit).coerceAtLeast(unit)
                }
            }

            override fun getScrollableTracksViewportWidth(): Boolean = true

            override fun getScrollableTracksViewportHeight(): Boolean = false
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }

        val summaryCard = buildWorkspaceSummaryCard()
        sectionsStack.add(createWorkspaceStackItem(summaryCard))
        sectionsStack.add(Box.createVerticalStrut(JBUI.scale(6)))

        overviewSection = createWorkspaceSection(
            id = SpecWorkflowWorkspaceSectionId.OVERVIEW,
            titleProvider = { SpecCodingBundle.message("spec.toolwindow.section.overview") },
            content = overviewPanel,
        )
        tasksSection = createWorkspaceSection(
            id = SpecWorkflowWorkspaceSectionId.TASKS,
            titleProvider = { SpecCodingBundle.message("spec.toolwindow.section.tasks") },
            content = tasksPanel,
            maxExpandedBodyHeight = SCROLLABLE_WORKSPACE_SECTION_MAX_HEIGHT,
        )
        gateSection = createWorkspaceSection(
            id = SpecWorkflowWorkspaceSectionId.GATE,
            titleProvider = { SpecCodingBundle.message("spec.toolwindow.section.gate") },
            content = gateDetailsPanel,
            maxExpandedBodyHeight = SCROLLABLE_WORKSPACE_SECTION_MAX_HEIGHT,
        )
        verifySection = createWorkspaceSection(
            id = SpecWorkflowWorkspaceSectionId.VERIFY,
            titleProvider = { SpecCodingBundle.message("spec.toolwindow.section.verify") },
            content = verifyDeltaPanel,
            maxExpandedBodyHeight = SCROLLABLE_WORKSPACE_SECTION_MAX_HEIGHT,
        )
        documentsSection = createWorkspaceSection(
            id = SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            titleProvider = { SpecCodingBundle.message("spec.toolwindow.section.documents") },
            content = buildDocumentWorkspaceContent(),
        )

        workspaceSectionItems.clear()
        listOf(
            SpecWorkflowWorkspaceSectionId.OVERVIEW to overviewSection,
            SpecWorkflowWorkspaceSectionId.TASKS to tasksSection,
            SpecWorkflowWorkspaceSectionId.GATE to gateSection,
            SpecWorkflowWorkspaceSectionId.VERIFY to verifySection,
            SpecWorkflowWorkspaceSectionId.DOCUMENTS to documentsSection,
        ).forEachIndexed { index, (sectionId, section) ->
            val item = createWorkspaceSectionItem(
                content = section,
                addBottomGap = index < 4,
            )
            workspaceSectionItems[sectionId] = item
            sectionsStack.add(item)
        }

        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            val workspaceScrollPane = JBScrollPane(sectionsStack).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                viewport.isOpaque = false
                isOpaque = false
                SpecUiStyle.applyFastVerticalScrolling(
                    scrollPane = this,
                    unitIncrement = WORKSPACE_SCROLL_UNIT_INCREMENT,
                    blockIncrement = WORKSPACE_SCROLL_BLOCK_INCREMENT,
                )
            }
            add(
                workspaceScrollPane,
                BorderLayout.CENTER,
            )
        }

        workspaceCardPanel.apply {
            isOpaque = false
            add(buildWorkspaceEmptyState(), WORKSPACE_CARD_EMPTY)
            add(contentPanel, WORKSPACE_CARD_CONTENT)
        }
        return workspaceCardPanel
    }

    private fun buildDocumentWorkspaceContent(): JPanel {
        documentWorkspaceViewButtons.clear()
        documentWorkspaceViewLabel = JBLabel(SpecCodingBundle.message("spec.toolwindow.documents.view.label")).apply {
            foreground = DOCUMENT_WORKSPACE_VIEW_LABEL_FG
            font = JBUI.Fonts.smallFont().deriveFont(DOCUMENT_WORKSPACE_VIEW_LABEL_FONT_SIZE)
        }
        documentWorkspaceViewSwitcherPanel = JPanel(
            FlowLayout(
                FlowLayout.LEFT,
                JBUI.scale(DOCUMENT_WORKSPACE_VIEW_SWITCHER_GAP),
                0,
            ),
        ).apply {
            isOpaque = true
            background = DOCUMENT_WORKSPACE_VIEW_GROUP_BG
            border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(
                    DOCUMENT_WORKSPACE_VIEW_GROUP_BORDER,
                    JBUI.scale(DOCUMENT_WORKSPACE_VIEW_GROUP_ARC),
                ),
                JBUI.Borders.empty(
                    DOCUMENT_WORKSPACE_VIEW_GROUP_INSET,
                    DOCUMENT_WORKSPACE_VIEW_GROUP_INSET,
                ),
            )
        }
        documentWorkspaceViewTabsPanel = JPanel(
            FlowLayout(
                FlowLayout.LEFT,
                JBUI.scale(DOCUMENT_WORKSPACE_VIEW_ROW_GAP),
                0,
            ),
        ).apply {
            isOpaque = false
            add(documentWorkspaceViewLabel)
            add(documentWorkspaceViewSwitcherPanel)
        }
        documentWorkspaceViewSwitcherPanel.add(
            createDocumentWorkspaceViewButton(
                view = DocumentWorkspaceView.DOCUMENT,
                labelKey = "spec.toolwindow.documents.view.document",
                tooltipKey = "spec.toolwindow.documents.view.document.tooltip",
            ),
        )
        documentWorkspaceViewSwitcherPanel.add(
            createDocumentWorkspaceViewButton(
                view = DocumentWorkspaceView.STRUCTURED_TASKS,
                labelKey = "spec.toolwindow.documents.view.structuredTasks",
                tooltipKey = "spec.toolwindow.documents.view.structuredTasks.tooltip",
            ),
        )
        documentWorkspaceViewCardPanel = JPanel(CardLayout()).apply {
            isOpaque = false
            add(detailPanel, DOCUMENT_WORKSPACE_CARD_DOCUMENT)
            add(detailTasksPanel, DOCUMENT_WORKSPACE_CARD_STRUCTURED_TASKS)
        }
        val container = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(documentWorkspaceViewTabsPanel, BorderLayout.NORTH)
            add(documentWorkspaceViewCardPanel, BorderLayout.CENTER)
        }
        updateDocumentWorkspaceViewPresentation(null)
        return container
    }

    private fun createDocumentWorkspaceViewButton(
        view: DocumentWorkspaceView,
        labelKey: String,
        tooltipKey: String,
    ): JButton {
        return JButton().apply {
            isFocusable = false
            isFocusPainted = false
            isOpaque = true
            isBorderPainted = true
            isContentAreaFilled = true
            isRolloverEnabled = true
            margin = JBUI.emptyInsets()
            installToolbarButtonCursorTracking(this)
            addActionListener {
                selectedDocumentWorkspaceView = view
                updateDocumentWorkspaceViewPresentation(currentWorkbenchState)
            }
            model.addChangeListener {
                refreshDocumentWorkspaceViewButtonStyle(this)
            }
            putClientProperty("documentWorkspaceView", view)
            text = SpecCodingBundle.message(labelKey)
            toolTipText = SpecCodingBundle.message(tooltipKey)
            font = JBUI.Fonts.smallFont()
        }
            .also { documentWorkspaceViewButtons[view] = it }
    }

    private fun createWorkspaceSectionItem(
        content: Component,
        addBottomGap: Boolean,
    ): JPanel {
        return createWorkspaceStackItem(
            component = createSectionContainer(
                content,
                padding = WORKSPACE_SECTION_CARD_PADDING,
                backgroundColor = DETAIL_SECTION_BG,
                borderColor = DETAIL_SECTION_BORDER,
            ),
            addBottomGap = addBottomGap,
        )
    }

    private fun createWorkspaceStackItem(
        component: Component,
        addBottomGap: Boolean = false,
    ): JPanel {
        return object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension {
                val preferred = preferredSize
                return Dimension(Int.MAX_VALUE, preferred.height)
            }
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            if (addBottomGap) {
                border = JBUI.Borders.emptyBottom(6)
            }
            add(component, BorderLayout.CENTER)
        }
    }

    private fun buildWorkspaceSummaryCard(): JPanel {
        workspaceSummaryTitleLabel.font = JBUI.Fonts.label().deriveFont(Font.BOLD, 13f)
        workspaceSummaryTitleLabel.foreground = WORKSPACE_SUMMARY_TITLE_FG
        workspaceSummaryMetaLabel.font = JBUI.Fonts.smallFont()
        workspaceSummaryMetaLabel.foreground = WORKSPACE_SUMMARY_META_FG
        workspaceSummaryFocusLabel.font = JBUI.Fonts.label().deriveFont(Font.BOLD, 12.5f)
        workspaceSummaryFocusLabel.foreground = WORKSPACE_SUMMARY_TITLE_FG
        workspaceSummaryHintLabel.font = JBUI.Fonts.smallFont()
        workspaceSummaryHintLabel.foreground = WORKSPACE_SUMMARY_META_FG

        val titleStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(workspaceSummaryTitleLabel)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(workspaceSummaryMetaLabel)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(workspaceSummaryFocusLabel)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(workspaceSummaryHintLabel)
        }
        val headerRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(backToListButton)
                },
                BorderLayout.WEST,
            )
            add(titleStack, BorderLayout.CENTER)
        }
        val chipRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(workspaceStageMetric.root)
            add(workspaceGateMetric.root)
            add(workspaceTasksMetric.root)
            add(workspaceVerifyMetric.root)
        }

        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            name = "workspaceSummaryCard"
            isOpaque = true
            background = WORKSPACE_SUMMARY_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = WORKSPACE_SUMMARY_BORDER,
                arc = JBUI.scale(16),
                top = 8,
                left = 10,
                bottom = 8,
                right = 10,
            )
            add(
                JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                    isOpaque = false
                    add(headerRow, BorderLayout.NORTH)
                    add(chipRow, BorderLayout.SOUTH)
                },
                BorderLayout.CENTER,
            )
        }
    }

    private fun buildWorkspaceEmptyState(): JPanel {
        val titleLabel = JBLabel(SpecCodingBundle.message("spec.detail.noWorkflow")).apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD, 13f)
            foreground = WORKSPACE_EMPTY_TITLE_FG
        }
        val descriptionLabel = JBLabel(
            "<html>${SpecCodingBundle.message("spec.toolwindow.overview.empty")}</html>",
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = WORKSPACE_EMPTY_DESCRIPTION_FG
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = true
            background = DETAIL_SECTION_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = DETAIL_SECTION_BORDER,
                arc = JBUI.scale(16),
                top = 18,
                left = 18,
                bottom = 18,
                right = 18,
            )
            add(titleLabel, BorderLayout.NORTH)
            add(descriptionLabel, BorderLayout.CENTER)
        }
    }

    private fun createWorkspaceSection(
        id: SpecWorkflowWorkspaceSectionId,
        titleProvider: () -> String,
        content: Component,
        maxExpandedBodyHeight: Int? = null,
    ): SpecCollapsibleWorkspaceSection {
        return SpecCollapsibleWorkspaceSection(
            titleProvider = titleProvider,
            content = content,
            expandedInitially = true,
            maxExpandedBodyHeight = maxExpandedBodyHeight,
            onExpandedChanged = { expanded ->
                workspaceSectionOverrides[id] = expanded
            },
        )
    }

    private fun showWorkspaceEmptyState() {
        showWorkflowListOnlyMode()
        backToListButton.isEnabled = false
        workspaceCardLayout.show(workspaceCardPanel, WORKSPACE_CARD_EMPTY)
        workspaceSectionOverrides.clear()
        workspaceSectionPresetToken = null
        currentOverviewState = null
        currentWorkbenchState = null
        currentVerifyDeltaState = null
        currentGateResult = null
        currentStructuredTasks = emptyList()
        currentTaskLiveProgressByTaskId = emptyMap()
        liveProgressRefreshTimer.stop()
        focusedStage = null
        workspaceSummaryTitleLabel.text = ""
        workspaceSummaryMetaLabel.text = ""
        workspaceSummaryFocusLabel.text = ""
        workspaceSummaryHintLabel.text = ""
        clearWorkspaceMetric(workspaceStageMetric)
        clearWorkspaceMetric(workspaceGateMetric)
        clearWorkspaceMetric(workspaceTasksMetric)
        clearWorkspaceMetric(workspaceVerifyMetric)
        if (::overviewSection.isInitialized) {
            workspaceSections().values.forEach { section ->
                section.setSummary(null)
                section.setExpanded(true, notify = false)
            }
        }
        workspaceSectionItems.values.forEach { item ->
            item.isVisible = true
        }
    }

    private fun showWorkspaceContent() {
        showWorkflowWorkspaceMode()
        backToListButton.isEnabled = true
        workspaceCardLayout.show(workspaceCardPanel, WORKSPACE_CARD_CONTENT)
    }


    private fun styleToolbarButton(button: JButton) {
        val iconOnly = button.icon != null && button.text.isNullOrBlank()
        button.isFocusable = false
        button.isFocusPainted = false
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = if (iconOnly) JBUI.insets(0, 0, 0, 0) else JBUI.insets(1, 4, 1, 4)
        button.foreground = BUTTON_FG
        if (iconOnly) {
            installToolbarButtonCursorTracking(button)
            button.putClientProperty("JButton.buttonType", "toolbar")
            button.isContentAreaFilled = false
            button.isOpaque = false
            button.border = JBUI.Borders.empty(1)
            val size = JBUI.size(JBUI.scale(22), JBUI.scale(22))
            button.preferredSize = size
            button.minimumSize = size
        } else {
            button.isContentAreaFilled = true
            button.isOpaque = true
            SpecUiStyle.applyRoundRect(button, arc = 10)
            installToolbarButtonCursorTracking(button)
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
    }

    private fun installToolbarButtonCursorTracking(button: JButton) {
        if (button.getClientProperty("spec.toolbar.cursorTrackingInstalled") == true) return
        button.putClientProperty("spec.toolbar.cursorTrackingInstalled", true)
        updateToolbarButtonCursor(button)
        button.addPropertyChangeListener("enabled") { updateToolbarButtonCursor(button) }
    }

    private fun updateToolbarButtonCursor(button: JButton) {
        button.cursor = if (button.isEnabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private fun applyToolbarButtonPresentation() {
        configureToolbarIconButton(
            button = switchWorkflowButton,
            icon = SpecWorkflowIcons.SwitchWorkflow,
            tooltipKey = "spec.workflow.switch",
        )
        configureToolbarIconButton(
            button = createWorkflowButton,
            icon = SpecWorkflowIcons.Add,
            tooltipKey = "spec.workflow.new",
        )
        configureToolbarIconButton(
            button = refreshButton,
            icon = SpecWorkflowIcons.Refresh,
            tooltipKey = "spec.workflow.refresh",
        )
        configureToolbarIconButton(
            button = createWorktreeButton,
            icon = SpecWorkflowIcons.Branch,
            tooltipKey = "spec.workflow.createWorktree",
        )
        configureToolbarIconButton(
            button = mergeWorktreeButton,
            icon = SpecWorkflowIcons.Complete,
            tooltipKey = "spec.workflow.mergeWorktree",
        )
        configureToolbarIconButton(
            button = deltaButton,
            icon = SpecWorkflowIcons.History,
            tooltipKey = "spec.workflow.delta",
        )
        configureToolbarIconButton(
            button = codeGraphButton,
            icon = SpecWorkflowIcons.OpenToolWindow,
            tooltipKey = "spec.workflow.codeGraph",
        )
        configureToolbarIconButton(
            button = archiveButton,
            icon = SpecWorkflowIcons.Save,
            tooltipKey = "spec.workflow.archive",
        )
    }

    private fun configureToolbarIconButton(button: JButton, icon: Icon, tooltipKey: String) {
        val tooltip = SpecCodingBundle.message(tooltipKey)
        SpecUiStyle.configureIconActionButton(
            button = button,
            icon = icon,
            tooltip = tooltip,
            accessibleName = tooltip,
        )
    }

    private fun setupGenerationControls() {
        modelLabel.font = JBUI.Fonts.smallFont()
        modelLabel.foreground = STATUS_TEXT_FG

        providerComboBox.renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
            label.text = providerDisplayName(value)
        }
        modelComboBox.renderer = SimpleListCellRenderer.create<ModelInfo> { label, value, _ ->
            label.text = toUiLowercase(value?.name ?: "")
        }
        providerComboBox.addActionListener {
            refreshModelCombo(preserveSelection = true)
        }
        modelComboBox.addActionListener {
            updateSelectorTooltips()
        }
        configureToolbarCombo(providerComboBox, minWidth = 56, maxWidth = 160)
        configureToolbarCombo(modelComboBox, minWidth = 72, maxWidth = 260)
        refreshProviderCombo(preserveSelection = false)
    }

    private fun configureToolbarCombo(comboBox: ComboBox<*>, minWidth: Int, maxWidth: Int) {
        comboBox.putClientProperty("JComponent.roundRect", false)
        comboBox.putClientProperty("JComboBox.isBorderless", true)
        comboBox.putClientProperty("ComboBox.isBorderless", true)
        comboBox.putClientProperty("JComponent.outline", null)
        comboBox.background = BUTTON_BG
        comboBox.foreground = BUTTON_FG
        comboBox.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
            JBUI.Borders.empty(0, 5, 0, 5),
        )
        comboBox.isOpaque = true
        comboBox.font = JBUI.Fonts.smallFont()
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = comboBox,
            minWidth = JBUI.scale(minWidth),
            maxWidth = JBUI.scale(maxWidth),
            height = JBUI.scale(24),
        )
    }

    private fun refreshProviderCombo(preserveSelection: Boolean) {
        val settings = SpecCodingSettingsState.getInstance()
        val previousSelection = if (preserveSelection) providerComboBox.selectedItem as? String else null
        val providers = llmRouter.availableUiProviders()
            .ifEmpty { llmRouter.availableProviders() }
            .ifEmpty { listOf(MockLlmProvider.ID) }

        providerComboBox.removeAllItems()
        providers.forEach { providerComboBox.addItem(it) }

        val preferred = when {
            !previousSelection.isNullOrBlank() -> previousSelection
            settings.defaultProvider.isNotBlank() -> settings.defaultProvider
            else -> llmRouter.defaultProviderId()
        }
        val selectedProvider = providers.firstOrNull { it == preferred } ?: providers.firstOrNull()
        providerComboBox.selectedItem = selectedProvider
        refreshModelCombo(preserveSelection = preserveSelection)
    }

    private fun refreshModelCombo(preserveSelection: Boolean) {
        val selectedProvider = providerComboBox.selectedItem as? String
        val previousModelId = if (preserveSelection) {
            (modelComboBox.selectedItem as? ModelInfo)?.id?.trim().orEmpty()
        } else {
            ""
        }
        modelComboBox.removeAllItems()

        if (selectedProvider.isNullOrBlank()) {
            modelComboBox.isEnabled = false
            updateSelectorTooltips()
            return
        }

        val settings = SpecCodingSettingsState.getInstance()
        val savedModelId = settings.selectedCliModel.trim()
        val models = modelRegistry.getModelsForProvider(selectedProvider)
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .toMutableList()

        if (models.isEmpty() && savedModelId.isNotBlank() && selectedProvider == settings.defaultProvider) {
            models += ModelInfo(
                id = savedModelId,
                name = savedModelId,
                provider = selectedProvider,
                contextWindow = 0,
                capabilities = emptySet(),
            )
        }

        models.forEach { modelComboBox.addItem(it) }
        val preferredModelId = when {
            previousModelId.isNotBlank() -> previousModelId
            savedModelId.isNotBlank() -> savedModelId
            else -> ""
        }
        val selectedModel = models.firstOrNull { it.id == preferredModelId } ?: models.firstOrNull()
        if (selectedModel != null) {
            modelComboBox.selectedItem = selectedModel
        }
        modelComboBox.isEnabled = models.isNotEmpty()
        updateSelectorTooltips()
    }

    private fun updateSelectorTooltips() {
        providerComboBox.toolTipText = providerDisplayName(providerComboBox.selectedItem as? String)
        modelComboBox.toolTipText = (modelComboBox.selectedItem as? ModelInfo)?.name
    }

    internal fun syncToolbarSelectionFromSettings() {
        refreshProviderCombo(preserveSelection = false)
    }

    private fun providerDisplayName(providerId: String?): String {
        return when (providerId) {
            ClaudeCliLlmProvider.ID -> "claude"
            CodexCliLlmProvider.ID -> "codex"
            MockLlmProvider.ID -> toUiLowercase(SpecCodingBundle.message("toolwindow.model.mock"))
            null -> ""
            else -> toUiLowercase(providerId)
        }
    }

    private fun toUiLowercase(value: String): String = value.lowercase(Locale.ROOT)

    private fun setStatusText(text: String?) {
        val value = text?.trim().orEmpty()
        statusLabel.text = value
        statusChipPanel.isVisible = value.isNotEmpty()
        statusChipPanel.revalidate()
        statusChipPanel.repaint()
    }

    private fun createSectionContainer(
        content: Component,
        padding: Int = 2,
        backgroundColor: Color = PANEL_SECTION_BG,
        borderColor: Color = PANEL_SECTION_BORDER,
    ): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = backgroundColor
            border = SpecUiStyle.roundedCardBorder(
                lineColor = borderColor,
                arc = JBUI.scale(14),
                top = padding,
                left = padding,
                bottom = padding,
                right = padding,
            )
            add(content, BorderLayout.CENTER)
        }
    }

    private fun updateDocumentWorkspaceViewPresentation(workbenchState: SpecWorkflowStageWorkbenchState?) {
        if (!::documentWorkspaceViewTabsPanel.isInitialized || !::documentWorkspaceViewCardPanel.isInitialized) {
            return
        }
        val supportsStructuredTasksView = supportsStructuredTasksDocumentWorkspaceView(workbenchState)
        documentWorkspaceViewTabsPanel.isVisible = supportsStructuredTasksView
        val effectiveView = if (supportsStructuredTasksView) {
            selectedDocumentWorkspaceView
        } else {
            DocumentWorkspaceView.DOCUMENT
        }
        (documentWorkspaceViewCardPanel.layout as CardLayout).show(
            documentWorkspaceViewCardPanel,
            when (effectiveView) {
                DocumentWorkspaceView.DOCUMENT -> DOCUMENT_WORKSPACE_CARD_DOCUMENT
                DocumentWorkspaceView.STRUCTURED_TASKS -> DOCUMENT_WORKSPACE_CARD_STRUCTURED_TASKS
            },
        )
        documentWorkspaceViewButtons.values.forEach(::refreshDocumentWorkspaceViewButtonStyle)
        if (supportsStructuredTasksView && effectiveView == DocumentWorkspaceView.STRUCTURED_TASKS) {
            syncStructuredTaskSelection(selectedStructuredTaskId)
        }
        documentWorkspaceViewTabsPanel.revalidate()
        documentWorkspaceViewTabsPanel.repaint()
        documentWorkspaceViewCardPanel.revalidate()
        documentWorkspaceViewCardPanel.repaint()
    }

    private fun refreshDocumentWorkspaceViewButtonStyle(button: JButton) {
        val view = button.getClientProperty("documentWorkspaceView") as? DocumentWorkspaceView ?: return
        val supportsStructuredTasksView = supportsStructuredTasksDocumentWorkspaceView(currentWorkbenchState)
        val effectiveView = if (supportsStructuredTasksView) {
            selectedDocumentWorkspaceView
        } else {
            DocumentWorkspaceView.DOCUMENT
        }
        val selected = view == effectiveView
        val enabled = view != DocumentWorkspaceView.STRUCTURED_TASKS || supportsStructuredTasksView
        val hovered = enabled && button.model.isRollover && !selected
        applyDocumentWorkspaceViewButtonStyle(
            button = button,
            selected = selected,
            enabled = enabled,
            hovered = hovered,
        )
    }

    private fun applyDocumentWorkspaceViewButtonStyle(
        button: JButton,
        selected: Boolean,
        enabled: Boolean,
        hovered: Boolean,
    ) {
        button.isEnabled = enabled
        button.background = when {
            !enabled -> DOCUMENT_WORKSPACE_VIEW_GROUP_BG
            selected -> DOCUMENT_WORKSPACE_VIEW_SELECTED_BG
            hovered -> DOCUMENT_WORKSPACE_VIEW_HOVER_BG
            else -> DOCUMENT_WORKSPACE_VIEW_IDLE_BG
        }
        button.foreground = when {
            !enabled -> DOCUMENT_WORKSPACE_VIEW_DISABLED_FG
            selected -> DOCUMENT_WORKSPACE_VIEW_SELECTED_FG
            hovered -> DOCUMENT_WORKSPACE_VIEW_HOVER_FG
            else -> DOCUMENT_WORKSPACE_VIEW_IDLE_FG
        }
        button.border = BorderFactory.createCompoundBorder(
            if (selected || hovered) {
                SpecUiStyle.roundedLineBorder(
                    if (selected) DOCUMENT_WORKSPACE_VIEW_SELECTED_BORDER else DOCUMENT_WORKSPACE_VIEW_HOVER_BORDER,
                    JBUI.scale(DOCUMENT_WORKSPACE_VIEW_BUTTON_ARC),
                )
            } else {
                JBUI.Borders.empty()
            },
            JBUI.Borders.empty(
                DOCUMENT_WORKSPACE_VIEW_BUTTON_VERTICAL_PADDING,
                DOCUMENT_WORKSPACE_VIEW_BUTTON_HORIZONTAL_PADDING,
            ),
        )
        button.font = JBUI.Fonts.smallFont().deriveFont(
            if (selected) Font.BOLD else Font.PLAIN,
            DOCUMENT_WORKSPACE_VIEW_BUTTON_FONT_SIZE,
        )
        val labelFont = JBUI.Fonts.smallFont().deriveFont(
            Font.BOLD,
            DOCUMENT_WORKSPACE_VIEW_BUTTON_FONT_SIZE,
        )
        val width = documentWorkspaceViewButtonTargetWidth(labelFont)
        val size = JBUI.size(width, JBUI.scale(DOCUMENT_WORKSPACE_VIEW_BUTTON_HEIGHT))
        button.preferredSize = size
        button.minimumSize = size
        button.maximumSize = size
    }

    private fun documentWorkspaceViewButtonTargetWidth(labelFont: Font): Int {
        val scaledMinWidth = JBUI.scale(DOCUMENT_WORKSPACE_VIEW_BUTTON_MIN_WIDTH)
        val scaledTextPadding = JBUI.scale(
            DOCUMENT_WORKSPACE_VIEW_BUTTON_HORIZONTAL_PADDING * 2 +
                DOCUMENT_WORKSPACE_VIEW_BUTTON_EXTRA_WIDTH_PADDING,
        )
        return maxOf(
            documentWorkspaceViewButtons.values.maxOfOrNull { candidate ->
                candidate.getFontMetrics(labelFont).stringWidth(candidate.text.orEmpty()) + scaledTextPadding
            } ?: scaledMinWidth,
            scaledMinWidth,
        )
    }

    private fun supportsStructuredTasksDocumentWorkspaceView(
        workbenchState: SpecWorkflowStageWorkbenchState?,
    ): Boolean {
        val binding = workbenchState?.artifactBinding ?: return false
        return workbenchState.focusedStage in setOf(StageId.TASKS, StageId.IMPLEMENT) &&
            binding.documentPhase == SpecPhase.IMPLEMENT &&
            binding.fileName == StageId.TASKS.artifactFileName
    }

    private fun onStructuredTaskSelectionChanged(taskId: String?) {
        if (isSynchronizingStructuredTaskSelection) {
            return
        }
        syncStructuredTaskSelection(taskId)
    }

    private fun syncStructuredTaskSelection(taskId: String?) {
        selectedStructuredTaskId = taskId?.takeIf { it.isNotBlank() }
        val selectedTaskId = selectedStructuredTaskId
        val previous = isSynchronizingStructuredTaskSelection
        isSynchronizingStructuredTaskSelection = true
        try {
            if (selectedTaskId == null) {
                tasksPanel.clearTaskSelection()
                detailTasksPanel.clearTaskSelection()
                return
            }
            if (tasksPanel.selectedTaskId() != selectedTaskId) {
                tasksPanel.selectTask(selectedTaskId)
            }
            if (detailTasksPanel.selectedTaskId() != selectedTaskId) {
                detailTasksPanel.selectTask(selectedTaskId)
            }
        } finally {
            isSynchronizingStructuredTaskSelection = previous
        }
    }

    private fun refreshWorkspacePresentation() {
        val workflow = currentWorkflow
        val overviewState = currentOverviewState
        val verifyDeltaState = currentVerifyDeltaState
        if (workflow == null || overviewState == null || verifyDeltaState == null) {
            if (selectedWorkflowId == null) {
                showWorkspaceEmptyState()
            }
            return
        }
        updateWorkspacePresentation(
            workflow = workflow,
            overviewState = overviewState,
            tasks = currentStructuredTasks,
            liveProgressByTaskId = currentTaskLiveProgressByTaskId,
            verifyDeltaState = verifyDeltaState,
            gateResult = currentGateResult,
        )
    }

    private fun refreshCurrentLiveProgressPresentation() {
        val workflow = currentWorkflow ?: return
        val workflowId = selectedWorkflowId ?: return
        if (workflow.id != workflowId) {
            return
        }
        val updatedLiveProgress = buildTaskLiveProgressByTaskId(workflowId)
        currentTaskLiveProgressByTaskId = updatedLiveProgress
        tasksPanel.updateLiveProgress(
            tasks = currentStructuredTasks,
            liveProgressByTaskId = updatedLiveProgress,
        )
        detailTasksPanel.updateLiveProgress(
            tasks = currentStructuredTasks,
            liveProgressByTaskId = updatedLiveProgress,
        )
        refreshWorkspacePresentation()
    }

    private fun requestLiveProgressPresentationRefresh() {
        liveProgressRefreshPending = true
        if (!liveProgressRefreshDispatchQueued.compareAndSet(false, true)) {
            return
        }
        invokeLaterSafe {
            liveProgressRefreshDispatchQueued.set(false)
            if (project.isDisposed || _isDisposed || !liveProgressRefreshPending) {
                return@invokeLaterSafe
            }
            if (liveProgressEventCoalesceTimer.isRunning) {
                liveProgressEventCoalesceTimer.restart()
            } else {
                liveProgressEventCoalesceTimer.start()
            }
        }
    }

    private fun flushPendingLiveProgressPresentationRefresh(force: Boolean = false) {
        if (!force && !liveProgressRefreshPending) {
            return
        }
        liveProgressRefreshPending = false
        refreshCurrentLiveProgressPresentation()
    }

    private fun decorateTasksWithExecutionState(
        workflow: SpecWorkflow,
        tasks: List<StructuredTask>,
        liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
    ): List<StructuredTask> {
        if (tasks.isEmpty()) {
            return emptyList()
        }
        val activeRunsByTaskId = workflow.taskExecutionRuns
            .asSequence()
            .filter { run -> !run.status.isTerminal() }
            .sortedBy(TaskExecutionRun::startedAt)
            .associateBy(TaskExecutionRun::taskId)
        return tasks.map { task ->
            task.copy(activeExecutionRun = activeRunsByTaskId[task.id])
        }
    }

    private fun buildTaskLiveProgressByTaskId(workflowId: String): Map<String, TaskExecutionLiveProgress> {
        return runCatching {
            specTaskExecutionService.listLiveProgress(workflowId)
        }.getOrElse { error ->
            logger.debug("Unable to load live execution progress for workflow $workflowId", error)
            emptyList()
        }.associateBy(TaskExecutionLiveProgress::taskId)
    }

    private fun updateLiveProgressRefreshTimer(
        tasks: List<StructuredTask>,
        liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
    ) {
        val hasLiveProgress = liveProgressByTaskId.isNotEmpty() || tasks.any(StructuredTask::hasExecutionInFlight)
        if (hasLiveProgress) {
            if (!liveProgressRefreshTimer.isRunning) {
                liveProgressRefreshTimer.start()
            }
        } else {
            liveProgressRefreshPending = false
            liveProgressRefreshDispatchQueued.set(false)
            liveProgressEventCoalesceTimer.stop()
            liveProgressRefreshTimer.stop()
        }
    }

    private fun updateWorkspacePresentation(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        tasks: List<StructuredTask>,
        liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
        verifyDeltaState: SpecWorkflowVerifyDeltaState,
        gateResult: GateResult?,
    ) {
        val previousWorkbenchState = currentWorkbenchState
        val workbenchState = resolveWorkbenchState(
            workflow = workflow,
            state = SpecWorkflowStageWorkbenchBuilder.build(
                workflow = workflow,
                overviewState = overviewState,
                tasks = tasks,
                liveProgressByTaskId = liveProgressByTaskId,
                verifyDeltaState = verifyDeltaState,
                gateResult = gateResult,
                focusedStage = focusedStage,
            ),
        )
        focusedStage = workbenchState.focusedStage
        currentOverviewState = overviewState
        currentWorkbenchState = workbenchState
        currentVerifyDeltaState = verifyDeltaState
        currentGateResult = gateResult
        currentStructuredTasks = tasks
        currentTaskLiveProgressByTaskId = liveProgressByTaskId

        showWorkspaceContent()
        overviewPanel.updateOverview(
            state = overviewState,
            workbenchState = workbenchState,
        )
        val guidance = SpecWorkflowStageGuidanceBuilder.build(
            state = overviewState,
            workbenchState = workbenchState,
        )
        workspaceSummaryTitleLabel.text = workflow.title.ifBlank { workflow.id }
        val nextStageText = overviewState.nextStage
            ?.let(SpecWorkflowOverviewPresenter::stageLabel)
            ?: SpecCodingBundle.message("spec.toolwindow.overview.nextStage.none")
        workspaceSummaryMetaLabel.text = buildString {
            append(SpecCodingBundle.message("spec.toolwindow.overview.secondary.workflow"))
            append(": ")
            append(workflow.id)
            append(" | ")
            append(SpecCodingBundle.message("spec.toolwindow.overview.secondary.template"))
            append(": ")
            append(SpecWorkflowOverviewPresenter.templateLabel(workflow.template))
            append(" | ")
            append(SpecCodingBundle.message("spec.toolwindow.overview.secondary.next"))
            append(": ")
            append(nextStageText)
        }
        workspaceSummaryFocusLabel.text = guidance.headline
        workspaceSummaryHintLabel.text = guidance.summary

        updateWorkspaceMetric(
            metric = workspaceStageMetric,
            title = if (workbenchState.focusedStage == workbenchState.currentStage) {
                SpecCodingBundle.message("spec.toolwindow.overview.currentStage")
            } else {
                SpecCodingBundle.message("spec.toolwindow.overview.secondary.focus")
            },
            value = buildStageChipText(workbenchState),
            tone = when (workflow.status) {
                WorkflowStatus.COMPLETED -> WorkspaceChipTone.SUCCESS
                WorkflowStatus.PAUSED -> WorkspaceChipTone.WARNING
                WorkflowStatus.FAILED -> WorkspaceChipTone.ERROR
                WorkflowStatus.IN_PROGRESS -> WorkspaceChipTone.INFO
            },
        )
        updateWorkspaceMetric(
            metric = workspaceGateMetric,
            title = SpecCodingBundle.message("spec.toolwindow.section.gate"),
            value = buildGateChipText(gateResult),
            tone = when (gateResult?.status) {
                GateStatus.ERROR -> WorkspaceChipTone.ERROR
                GateStatus.WARNING -> WorkspaceChipTone.WARNING
                GateStatus.PASS -> WorkspaceChipTone.SUCCESS
                null -> WorkspaceChipTone.MUTED
            },
        )
        updateWorkspaceMetric(
            metric = workspaceTasksMetric,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.title"),
            value = buildTasksChipText(tasks),
            tone = when {
                tasks.any { it.status == TaskStatus.BLOCKED } -> WorkspaceChipTone.WARNING
                tasks.isNotEmpty() && tasks.all { it.status == TaskStatus.COMPLETED } -> WorkspaceChipTone.SUCCESS
                else -> WorkspaceChipTone.INFO
            },
        )
        updateLiveProgressRefreshTimer(tasks, liveProgressByTaskId)
        updateWorkspaceMetric(
            metric = workspaceVerifyMetric,
            title = SpecCodingBundle.message("spec.toolwindow.section.verify"),
            value = buildVerifyChipText(verifyDeltaState),
            tone = when (verifyDeltaState.verificationHistory.firstOrNull()?.conclusion) {
                VerificationConclusion.FAIL -> WorkspaceChipTone.ERROR
                VerificationConclusion.WARN -> WorkspaceChipTone.WARNING
                VerificationConclusion.PASS -> WorkspaceChipTone.SUCCESS
                null -> WorkspaceChipTone.MUTED
            },
        )

        overviewSection.setSummary(
            buildOverviewSectionSummary(overviewState, workbenchState, nextStageText),
        )
        tasksSection.setSummary(buildTasksSectionSummary(tasks))
        gateSection.setSummary(buildGateSectionSummary(gateResult))
        verifySection.setSummary(buildVerifySectionSummary(verifyDeltaState))
        documentsSection.setSummary(
            buildDocumentsSectionSummary(workbenchState),
        )
        detailPanel.updateWorkbenchState(
            state = workbenchState,
            syncSelection = previousWorkbenchState?.focusedStage != workbenchState.focusedStage ||
                previousWorkbenchState?.currentStage != workbenchState.currentStage,
        )
        syncWorkbenchTaskSelection(
            previousWorkbenchState = previousWorkbenchState,
            workbenchState = workbenchState,
        )
        updateDocumentWorkspaceViewPresentation(workbenchState)
        applyWorkspaceSectionVisibility(workbenchState)
        applyWorkspaceSectionPreset(workflow, workbenchState)
    }

    private fun syncWorkbenchTaskSelection(
        previousWorkbenchState: SpecWorkflowStageWorkbenchState?,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ) {
        if (workbenchState.focusedStage != StageId.IMPLEMENT) {
            return
        }
        val taskId = workbenchState.implementationFocus?.taskId ?: return
        val shouldSyncSelection = previousWorkbenchState?.focusedStage != workbenchState.focusedStage ||
            previousWorkbenchState?.implementationFocus?.taskId != taskId
        if (shouldSyncSelection) {
            syncStructuredTaskSelection(taskId)
        }
    }

    private fun applyWorkspaceSectionPreset(
        workflow: SpecWorkflow,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ) {
        val token = "${workflow.id}:${workflow.currentStage.name}:${workbenchState.focusedStage.name}:${workflow.status.name}"
        if (workspaceSectionPresetToken != token) {
            workspaceSectionPresetToken = token
            workspaceSectionOverrides.clear()
            val expanded = SpecWorkflowWorkspaceLayout.defaultExpandedSections(
                currentStage = workbenchState.focusedStage,
                status = workflow.status,
            )
            workspaceSections().forEach { (sectionId, section) ->
                section.setExpanded(expanded.contains(sectionId), notify = false)
            }
            return
        }

        workspaceSections().forEach { (sectionId, section) ->
            workspaceSectionOverrides[sectionId]?.let { expanded ->
                section.setExpanded(expanded, notify = false)
            }
        }
    }

    private fun applyWorkspaceSectionVisibility(workbenchState: SpecWorkflowStageWorkbenchState) {
        val visibleSections = workbenchState.visibleSections
        val supportsEmbeddedTasksView = supportsStructuredTasksDocumentWorkspaceView(workbenchState)
        workspaceSectionItems.forEach { (sectionId, item) ->
            item.isVisible = visibleSections.contains(sectionId) &&
                !(supportsEmbeddedTasksView && sectionId == SpecWorkflowWorkspaceSectionId.TASKS)
        }
        workspaceCardPanel.revalidate()
        workspaceCardPanel.repaint()
    }

    private fun workspaceSections(): Map<SpecWorkflowWorkspaceSectionId, SpecCollapsibleWorkspaceSection> {
        if (!::overviewSection.isInitialized) {
            return emptyMap()
        }
        return linkedMapOf(
            SpecWorkflowWorkspaceSectionId.OVERVIEW to overviewSection,
            SpecWorkflowWorkspaceSectionId.TASKS to tasksSection,
            SpecWorkflowWorkspaceSectionId.GATE to gateSection,
            SpecWorkflowWorkspaceSectionId.VERIFY to verifySection,
            SpecWorkflowWorkspaceSectionId.DOCUMENTS to documentsSection,
        )
    }

    private fun buildStageChipText(workbenchState: SpecWorkflowStageWorkbenchState): String {
        val stageLabel = SpecWorkflowOverviewPresenter.stageLabel(workbenchState.focusedStage)
        val checksText = "${workbenchState.progress.completedCheckCount}/${workbenchState.progress.totalCheckCount}"
        val progressText = "${workbenchState.progress.stepIndex}/${workbenchState.progress.totalSteps}"
        val stageStatus = SpecWorkflowOverviewPresenter.progressLabel(workbenchState.progress.stageStatus)
        return if (workbenchState.focusedStage == workbenchState.currentStage) {
            "$stageLabel / $checksText / $progressText / $stageStatus"
        } else {
            "$stageLabel / $checksText / $progressText"
        }
    }

    private fun buildGateChipText(gateResult: GateResult?): String {
        return when (gateResult?.status) {
            GateStatus.PASS -> SpecCodingBundle.message("spec.toolwindow.gate.status.pass")
            GateStatus.WARNING -> SpecCodingBundle.message("spec.toolwindow.gate.status.warning")
            GateStatus.ERROR -> SpecCodingBundle.message("spec.toolwindow.gate.status.error")
            null -> SpecCodingBundle.message("spec.toolwindow.gate.status.unavailable")
        }
    }

    private fun buildTasksChipText(tasks: List<StructuredTask>): String {
        if (tasks.isEmpty()) {
            return "0/0"
        }
        val completed = tasks.count { it.status == TaskStatus.COMPLETED }
        return "$completed/${tasks.size}"
    }

    private fun buildOverviewSectionSummary(
        overviewState: SpecWorkflowOverviewState,
        workbenchState: SpecWorkflowStageWorkbenchState,
        nextStageText: String,
    ): String {
        return buildString {
            append(SpecWorkflowOverviewPresenter.stageLabel(workbenchState.focusedStage))
            append(" | ")
            append(workbenchState.progress.completedCheckCount)
            append("/")
            append(workbenchState.progress.totalCheckCount)
            append(" ")
            append(SpecCodingBundle.message("spec.toolwindow.overview.checks.short"))
            append(" | ")
            append(SpecCodingBundle.message("spec.toolwindow.overview.secondary.next"))
            append(": ")
            append(nextStageText)
        }
    }

    private fun buildTasksSectionSummary(tasks: List<StructuredTask>): String {
        if (tasks.isEmpty()) {
            return SpecCodingBundle.message("spec.toolwindow.tasks.emptyForWorkflow")
        }
        val completed = tasks.count { it.status == TaskStatus.COMPLETED }
        val blocked = tasks.count { it.status == TaskStatus.BLOCKED }
        return SpecCodingBundle.message(
            "spec.toolwindow.tasks.summary",
            tasks.size,
            completed,
            blocked,
        )
    }

    private fun buildGateSectionSummary(gateResult: GateResult?): String {
        if (gateResult == null) {
            return SpecCodingBundle.message("spec.toolwindow.gate.summary.none")
        }
        return SpecCodingBundle.message(
            "spec.toolwindow.gate.summary",
            gateResult.aggregation.errorCount,
            gateResult.aggregation.warningCount,
            gateResult.aggregation.totalViolationCount,
        )
    }

    private fun buildVerifyChipText(state: SpecWorkflowVerifyDeltaState): String {
        val runCount = state.verificationHistory.size
        return "$runCount / ${verificationStatusText(state)}"
    }

    private fun buildVerifySectionSummary(state: SpecWorkflowVerifyDeltaState): String {
        val latest = state.verificationHistory.firstOrNull()?.conclusion?.name
            ?: SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.pending")
        return when {
            !state.verifyEnabled -> SpecCodingBundle.message(
                "spec.toolwindow.verifyDelta.summary.disabled",
                state.baselineChoices.size,
            )

            state.verificationHistory.isEmpty() -> SpecCodingBundle.message(
                "spec.toolwindow.verifyDelta.summary.noRuns",
                state.baselineChoices.size,
            )

            else -> SpecCodingBundle.message(
                "spec.toolwindow.verifyDelta.summary",
                state.verificationHistory.size,
                latest,
                state.baselineChoices.size,
            )
        }
    }

    private fun buildDocumentsSectionSummary(workbenchState: SpecWorkflowStageWorkbenchState): String {
        return buildString {
            append(SpecWorkflowOverviewPresenter.stageLabel(workbenchState.focusedStage))
            append(" | ")
            append(workbenchState.artifactBinding.fileName ?: workbenchState.artifactBinding.title)
        }
    }

    private fun resolveWorkbenchState(
        workflow: SpecWorkflow,
        state: SpecWorkflowStageWorkbenchState,
    ): SpecWorkflowStageWorkbenchState {
        val binding = state.artifactBinding
        val resolvedBinding = when {
            binding.documentPhase != null -> binding.copy(
                available = workflow.documents.containsKey(binding.documentPhase),
                previewContent = workflow.documents[binding.documentPhase]?.content,
            )

            !binding.fileName.isNullOrBlank() -> {
                val path = runCatching {
                    artifactService.locateArtifact(workflow.id, binding.fileName)
                }.getOrNull()
                val available = path?.let(Files::exists) == true
                val previewContent = if (available) {
                    path?.let { artifactPath ->
                        runCatching { Files.readString(artifactPath, StandardCharsets.UTF_8) }.getOrNull()
                    }
                } else {
                    null
                }
                binding.copy(
                    available = available,
                    previewContent = previewContent,
                )
            }

            else -> binding
        }
        return state.copy(artifactBinding = resolvedBinding)
    }

    private fun updateWorkspaceMetric(
        metric: WorkspaceSummaryMetric,
        title: String,
        value: String,
        tone: WorkspaceChipTone,
    ) {
        val colors = workspaceChipColors(tone)
        metric.titleLabel.text = "$title:"
        metric.valueLabel.text = value
        metric.valueLabel.foreground = colors.foreground
        metric.root.isVisible = value.isNotBlank()
    }

    private fun clearWorkspaceMetric(metric: WorkspaceSummaryMetric) {
        metric.titleLabel.text = ""
        metric.valueLabel.text = ""
        metric.root.isVisible = false
    }

    private fun phaseLabel(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> SpecCodingBundle.message("spec.detail.step.requirements")
            SpecPhase.DESIGN -> SpecCodingBundle.message("spec.detail.step.design")
            SpecPhase.IMPLEMENT -> SpecCodingBundle.message("spec.detail.step.taskList")
        }
    }

    private fun workspaceWorkflowStatusText(status: WorkflowStatus): String {
        return when (status) {
            WorkflowStatus.IN_PROGRESS -> SpecCodingBundle.message("spec.workflow.status.inProgress")
            WorkflowStatus.PAUSED -> SpecCodingBundle.message("spec.workflow.status.paused")
            WorkflowStatus.COMPLETED -> SpecCodingBundle.message("spec.workflow.status.completed")
            WorkflowStatus.FAILED -> SpecCodingBundle.message("spec.workflow.status.failed")
        }
    }

    private fun verificationStatusText(state: SpecWorkflowVerifyDeltaState): String {
        if (!state.verifyEnabled) {
            return SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.disabled")
        }
        return when (state.verificationHistory.firstOrNull()?.conclusion) {
            VerificationConclusion.PASS -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.pass")
            VerificationConclusion.WARN -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.warn")
            VerificationConclusion.FAIL -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.fail")
            null -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.pending")
        }
    }

    private fun clearOpenedWorkflowUi(resetHighlight: Boolean = false) {
        selectedWorkflowId = null
        currentWorkflow = null
        currentWorkflowSources = emptyList()
        focusedStage = null
        currentWorkbenchState = null
        selectedDocumentWorkspaceView = DocumentWorkspaceView.DOCUMENT
        selectedStructuredTaskId = null
        phaseIndicator.reset()
        overviewPanel.showEmpty()
        tasksPanel.showEmpty()
        detailTasksPanel.showEmpty()
        gateDetailsPanel.showEmpty()
        verifyDeltaPanel.showEmpty()
        detailPanel.showEmpty()
        updateDocumentWorkspaceViewPresentation(null)
        createWorktreeButton.isEnabled = false
        mergeWorktreeButton.isEnabled = false
        deltaButton.isEnabled = false
        archiveButton.isEnabled = false
        if (resetHighlight) {
            highlightedWorkflowId = null
            listPanel.setSelectedWorkflow(null)
        }
        showWorkspaceEmptyState()
    }

    private fun resolveDeleteRefreshTarget(workflowId: String): WorkflowRefreshTarget {
        val remainingItems = listPanel.currentItems()
            .filterNot { item -> item.workflowId == workflowId }
        val remainingIds = remainingItems.asSequence()
            .map { item -> item.workflowId }
            .toSet()
        val preservedSelectedWorkflowId = selectedWorkflowId
            ?.takeIf { candidate -> candidate != workflowId && candidate in remainingIds }
        if (preservedSelectedWorkflowId != null) {
            return WorkflowRefreshTarget(selectWorkflowId = preservedSelectedWorkflowId, preserveListMode = false)
        }
        if (selectedWorkflowId == workflowId) {
            return WorkflowRefreshTarget(
                selectWorkflowId = remainingItems.firstOrNull()?.workflowId,
                preserveListMode = false,
            )
        }
        return WorkflowRefreshTarget(selectWorkflowId = null, preserveListMode = true)
    }

    fun refreshWorkflows(
        selectWorkflowId: String? = null,
        showRefreshFeedback: Boolean = false,
        preserveListMode: Boolean = false,
    ) {
        scope.launch(Dispatchers.IO) {
            val items = specEngine.listWorkflowMetadata().mapNotNull { meta ->
                specEngine.loadWorkflow(meta.workflowId).getOrNull()?.let { wf ->
                    SpecWorkflowListPanel.WorkflowListItem(
                        workflowId = wf.id,
                        title = wf.title.ifBlank { wf.id },
                        description = wf.description,
                        currentPhase = wf.currentPhase,
                        currentStageLabel = SpecWorkflowOverviewPresenter.stageLabel(wf.currentStage),
                        status = wf.status,
                        updatedAt = wf.updatedAt,
                        changeIntent = wf.changeIntent,
                        baselineWorkflowId = wf.baselineWorkflowId,
                    )
                }
            }
            invokeLaterSafe {
                workflowSwitcherPopup?.cancel()
                listPanel.updateWorkflows(items)
                switchWorkflowButton.isEnabled = items.isNotEmpty()
                setStatusText(null)
                val workflowIds = items.asSequence().map { it.workflowId }.toSet()
                if (pendingOpenWorkflowRequest?.workflowId?.let { it !in workflowIds } == true) {
                    pendingOpenWorkflowRequest = null
                }
                val validHighlightedWorkflowId = highlightedWorkflowId?.takeIf(workflowIds::contains)
                val targetOpenedWorkflowId = selectWorkflowId?.takeIf(workflowIds::contains)
                    ?: selectedWorkflowId?.takeIf(workflowIds::contains)
                    ?: items.firstOrNull()?.workflowId?.takeIf {
                        !preserveListMode && validHighlightedWorkflowId == null
                    }
                val targetHighlightedWorkflowId = targetOpenedWorkflowId
                    ?: if (preserveListMode) {
                        validHighlightedWorkflowId ?: items.firstOrNull()?.workflowId
                    } else {
                        validHighlightedWorkflowId
                    }
                highlightedWorkflowId = targetHighlightedWorkflowId
                listPanel.setSelectedWorkflow(targetHighlightedWorkflowId)
                if (targetOpenedWorkflowId != null) {
                    selectWorkflow(targetOpenedWorkflowId)
                } else {
                    clearOpenedWorkflowUi(resetHighlight = targetHighlightedWorkflowId == null)
                }
                if (showRefreshFeedback) {
                    val successText = SpecCodingBundle.message("common.refresh.success")
                    RefreshFeedback.flashButtonSuccess(refreshButton, successText)
                    RefreshFeedback.flashLabelSuccess(statusLabel, successText, STATUS_SUCCESS_FG)
                }
            }
        }
    }

    private fun onWorkflowFocusedByUser(workflowId: String) {
        highlightedWorkflowId = workflowId
        if (selectedWorkflowId != null && selectedWorkflowId != workflowId) {
            clearOpenedWorkflowUi(resetHighlight = false)
        }
    }

    private fun onWorkflowOpenedByUser(workflowId: String) {
        highlightedWorkflowId = workflowId
        selectWorkflow(workflowId)
        publishWorkflowSelection(workflowId)
    }

    private fun onBackToWorkflowListRequested() {
        clearOpenedWorkflowUi(resetHighlight = false)
    }

    private fun publishWorkflowSelection(workflowId: String) {
        runCatching {
            project.messageBus.syncPublisher(SpecWorkflowChangedListener.TOPIC).onWorkflowChanged(
                SpecWorkflowChangedEvent(
                    workflowId = workflowId,
                    reason = SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED,
                ),
            )
        }.onFailure { error ->
            logger.warn("Failed to publish workflow selection event: $workflowId", error)
        }
    }

    private fun selectWorkflow(workflowId: String) {
        val previousSelectedWorkflowId = selectedWorkflowId
        selectedWorkflowId = workflowId
        if (previousSelectedWorkflowId != workflowId) {
            focusedStage = null
            selectedDocumentWorkspaceView = DocumentWorkspaceView.DOCUMENT
            selectedStructuredTaskId = null
        }
        overviewPanel.showLoading()
        verifyDeltaPanel.showLoading()
        tasksPanel.showLoading()
        detailTasksPanel.showLoading()
        gateDetailsPanel.showLoading()
        showWorkspaceContent()
        scope.launch(Dispatchers.IO) {
            val wf = specEngine.reloadWorkflow(workflowId).getOrNull()
            val uiSnapshot = wf?.let(::buildWorkflowUiSnapshot)
            val tasksResult = runCatching { specTasksService.parse(workflowId) }
            val sourcesResult = runCatching { specEngine.listWorkflowSources(workflowId).getOrThrow() }
            val codeContextResult = wf?.let { workflow ->
                runCatching {
                    specCodeContextService.buildCodeContextPack(
                        workflow = workflow,
                        phase = workflow.currentPhase,
                    )
                }
            }
            val liveProgressByTaskId = wf?.let { buildTaskLiveProgressByTaskId(it.id) }.orEmpty()
            currentWorkflow = wf
            invokeLaterSafe {
                if (selectedWorkflowId != workflowId) {
                    return@invokeLaterSafe
                }
                if (wf != null) {
                    syncClarificationRetryFromWorkflow(wf)
                    phaseIndicator.updatePhase(wf)
                    val snapshot = uiSnapshot ?: buildWorkflowUiSnapshot(wf)
                    overviewPanel.updateOverview(snapshot.overviewState)
                    verifyDeltaPanel.updateState(snapshot.verifyDeltaState)
                    gateDetailsPanel.updateGateResult(
                        workflowId = workflowId,
                        gateResult = snapshot.gateResult,
                        refreshedAtMillis = snapshot.refreshedAtMillis,
                    )
                    detailPanel.updateWorkflow(wf)
                    applyAutoCodeContextToDetailPanel(wf, codeContextResult)
                    sourcesResult
                        .onSuccess { sources ->
                            applyWorkflowSourcesToDetailPanel(
                                workflow = wf,
                                assets = sources,
                                preserveSelection = previousSelectedWorkflowId == workflowId,
                            )
                        }
                        .onFailure { error ->
                            applyWorkflowSourcesToDetailPanel(
                                workflow = wf,
                                assets = emptyList(),
                                preserveSelection = false,
                            )
                            val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                            setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                        }
                    tasksResult.onSuccess { tasks ->
                        val decoratedTasks = decorateTasksWithExecutionState(
                            workflow = wf,
                            tasks = tasks,
                            liveProgressByTaskId = liveProgressByTaskId,
                        )
                        tasksPanel.updateTasks(
                            workflowId = workflowId,
                            tasks = decoratedTasks,
                            liveProgressByTaskId = liveProgressByTaskId,
                            refreshedAtMillis = System.currentTimeMillis(),
                        )
                        detailTasksPanel.updateTasks(
                            workflowId = workflowId,
                            tasks = decoratedTasks,
                            liveProgressByTaskId = liveProgressByTaskId,
                            refreshedAtMillis = System.currentTimeMillis(),
                        )
                        updateWorkspacePresentation(
                            workflow = wf,
                            overviewState = snapshot.overviewState,
                            tasks = decoratedTasks,
                            liveProgressByTaskId = liveProgressByTaskId,
                            verifyDeltaState = snapshot.verifyDeltaState,
                            gateResult = snapshot.gateResult,
                        )
                    }.onFailure { error ->
                        tasksPanel.updateTasks(
                            workflowId = workflowId,
                            tasks = emptyList(),
                            liveProgressByTaskId = emptyMap(),
                            refreshedAtMillis = System.currentTimeMillis(),
                        )
                        detailTasksPanel.updateTasks(
                            workflowId = workflowId,
                            tasks = emptyList(),
                            liveProgressByTaskId = emptyMap(),
                            refreshedAtMillis = System.currentTimeMillis(),
                        )
                        updateWorkspacePresentation(
                            workflow = wf,
                            overviewState = snapshot.overviewState,
                            tasks = emptyList(),
                            liveProgressByTaskId = emptyMap(),
                            verifyDeltaState = snapshot.verifyDeltaState,
                            gateResult = snapshot.gateResult,
                        )
                        val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                        setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                    }
                    if (previousSelectedWorkflowId != workflowId) {
                        restorePendingClarificationState(workflowId)
                    }
                    applyPendingOpenWorkflowRequestIfNeeded(workflowId)
                    createWorktreeButton.isEnabled = true
                    mergeWorktreeButton.isEnabled = true
                    deltaButton.isEnabled = true
                    archiveButton.isEnabled = wf.status == WorkflowStatus.COMPLETED
                } else {
                    clearOpenedWorkflowUi(resetHighlight = false)
                }
            }
        }
    }

    private fun onCreateWorkflow() {
        val workflowOptions = listPanel.workflowOptionsForCreate()
        scope.launch(Dispatchers.IO) {
            val defaultTemplate = runCatching {
                SpecProjectConfigService(project).load().defaultTemplate
            }.getOrElse { error ->
                logger.warn("Failed to load default workflow template for create dialog", error)
                WorkflowTemplate.FULL_SPEC
            }

            invokeLaterSafe {
                val dialog = NewSpecWorkflowDialog(
                    workflowOptions = workflowOptions,
                    defaultTemplate = defaultTemplate,
                )
                if (!dialog.showAndGet()) {
                    return@invokeLaterSafe
                }
                val title = dialog.resultTitle ?: return@invokeLaterSafe
                val desc = dialog.resultDescription ?: ""
                val template = dialog.resultTemplate
                val verifyEnabled = dialog.resultVerifyEnabled
                val changeIntent = dialog.resultChangeIntent
                val baselineWorkflowId = dialog.resultBaselineWorkflowId
                scope.launch(Dispatchers.IO) {
                    specEngine.createWorkflow(
                        title = title,
                        description = desc,
                        template = template,
                        verifyEnabled = verifyEnabled,
                        changeIntent = changeIntent,
                        baselineWorkflowId = baselineWorkflowId,
                    ).onSuccess { wf ->
                        invokeLaterSafe {
                            highlightedWorkflowId = wf.id
                            refreshWorkflows(selectWorkflowId = wf.id)
                            publishWorkflowSelection(wf.id)
                        }
                    }.onFailure { e ->
                        logger.warn("Failed to create workflow", e)
                        invokeLaterSafe {
                            val message = compactErrorMessage(e, SpecCodingBundle.message("common.unknown"))
                            setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                        }
                    }
                }
            }
        }
    }

    private fun onEditWorkflow(workflowId: String) {
        scope.launch(Dispatchers.IO) {
            val workflow = specEngine.loadWorkflow(workflowId).getOrNull()
            if (workflow == null) {
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message("spec.workflow.error", SpecCodingBundle.message("common.unknown")))
                }
                return@launch
            }

            invokeLaterSafe {
                val dialog = EditSpecWorkflowDialog(
                    initialTitle = workflow.title.ifBlank { workflow.id },
                    initialDescription = workflow.description,
                )
                if (!dialog.showAndGet()) {
                    return@invokeLaterSafe
                }
                val title = dialog.resultTitle ?: return@invokeLaterSafe
                val description = dialog.resultDescription ?: ""

                scope.launch(Dispatchers.IO) {
                    val result = specEngine.updateWorkflowMetadata(
                        workflowId = workflowId,
                        title = title,
                        description = description,
                    )

                    invokeLaterSafe {
                        result.onSuccess { updated ->
                            setStatusText(null)
                            val reopenWorkspace = selectedWorkflowId == workflowId
                            highlightedWorkflowId = workflowId
                            if (reopenWorkspace) {
                                currentWorkflow = updated
                                phaseIndicator.updatePhase(updated)
                                detailPanel.updateWorkflow(updated)
                                archiveButton.isEnabled = updated.status == WorkflowStatus.COMPLETED
                            }
                            refreshWorkflows(selectWorkflowId = workflowId.takeIf { reopenWorkspace })
                        }.onFailure { error ->
                            val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                            setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                        }
                    }
                }
            }
        }
    }

    private fun onDeleteWorkflow(workflowId: String) {
        val refreshTarget = resolveDeleteRefreshTarget(workflowId)
        scope.launch(Dispatchers.IO) {
            specEngine.deleteWorkflow(workflowId).onSuccess {
                invokeLaterSafe {
                    if (pendingOpenWorkflowRequest?.workflowId == workflowId) {
                        pendingOpenWorkflowRequest = null
                    }
                    refreshWorkflows(
                        selectWorkflowId = refreshTarget.selectWorkflowId,
                        preserveListMode = refreshTarget.preserveListMode,
                    )
                }
            }.onFailure { error ->
                invokeLaterSafe {
                    val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                    setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                }
            }
        }
    }

    private fun onCreateWorktree() {
        val workflow = currentWorkflow
        if (workflow == null) {
            setStatusText(SpecCodingBundle.message("spec.workflow.worktree.selectFirst"))
            return
        }

        val dialog = NewWorktreeDialog(
            specTaskId = workflow.id,
            specTitle = workflow.title.ifBlank { workflow.id },
            baseBranch = worktreeManager.suggestBaseBranch(),
        )
        if (!dialog.showAndGet()) {
            return
        }

        val specTaskId = dialog.resultSpecTaskId ?: workflow.id
        val shortName = dialog.resultShortName ?: return
        val baseBranch = dialog.resultBaseBranch ?: "main"

        scope.launch(Dispatchers.IO) {
            val created = worktreeManager.createWorktree(specTaskId, shortName, baseBranch)
            created.onSuccess { binding ->
                val switched = worktreeManager.switchWorktree(binding.id)
                invokeLaterSafe {
                    if (switched.isSuccess) {
                        setStatusText(SpecCodingBundle.message("spec.workflow.worktree.created", binding.branchName))
                    } else {
                        val message = compactErrorMessage(
                            switched.exceptionOrNull(),
                            SpecCodingBundle.message("common.unknown"),
                        )
                        setStatusText(SpecCodingBundle.message("spec.workflow.worktree.switchFailed", message))
                    }
                }
            }.onFailure { error ->
                invokeLaterSafe {
                    val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                    setStatusText(SpecCodingBundle.message(
                        "spec.workflow.worktree.createFailed",
                        message,
                    ))
                }
            }
        }
    }

    private fun onMergeWorktree() {
        val workflow = currentWorkflow
        if (workflow == null) {
            setStatusText(SpecCodingBundle.message("spec.workflow.worktree.selectFirst"))
            return
        }

        scope.launch(Dispatchers.IO) {
            val binding = worktreeManager.listBindings(includeInactive = true)
                .firstOrNull { it.specTaskId == workflow.id && it.status == WorktreeStatus.ACTIVE }
                ?: worktreeManager.listBindings(includeInactive = true)
                    .firstOrNull { it.specTaskId == workflow.id }

            if (binding == null) {
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message("spec.workflow.worktree.noBinding"))
                }
                return@launch
            }

            val mergeResult = worktreeManager.mergeWorktree(binding.id, binding.baseBranch)
            invokeLaterSafe {
                mergeResult.onSuccess {
                    setStatusText(SpecCodingBundle.message("spec.workflow.worktree.merged", it.targetBranch))
                }.onFailure { error ->
                    val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                    setStatusText(SpecCodingBundle.message(
                        "spec.workflow.worktree.mergeFailed",
                        message,
                    ))
                }
            }
        }
    }

    private fun canGenerateWithEmptyInput(): Boolean {
        val workflowId = selectedWorkflowId ?: return false
        return pendingClarificationRetryByWorkflowId[workflowId]
            ?.input
            ?.isNotBlank() == true
    }

    private fun applyAutoCodeContextToDetailPanel(
        workflow: SpecWorkflow?,
        codeContextResult: Result<CodeContextPack>?,
    ) {
        if (workflow == null) {
            detailPanel.updateAutoCodeContext(workflowId = null, codeContextPack = null)
            return
        }

        val pack = codeContextResult?.getOrElse { error ->
            CodeContextPack(
                phase = workflow.currentPhase,
                strategy = CodeContextCollectionStrategy.forPhase(workflow.currentPhase),
                degradationReasons = listOf(
                    "Automatic code context collection failed: ${compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))}",
                ),
            )
        } ?: CodeContextPack(
            phase = workflow.currentPhase,
            strategy = CodeContextCollectionStrategy.forPhase(workflow.currentPhase),
            degradationReasons = listOf(
                "Automatic code context collection was skipped for this workflow refresh.",
            ),
        )
        detailPanel.updateAutoCodeContext(
            workflowId = workflow.id,
            codeContextPack = pack,
        )
    }

    private fun applyWorkflowSourcesToDetailPanel(
        workflow: SpecWorkflow?,
        assets: List<WorkflowSourceAsset>,
        preserveSelection: Boolean,
        preferredSourceIds: Set<String> = emptySet(),
    ) {
        val workflowId = workflow?.id
        currentWorkflowSources = assets.sortedBy(WorkflowSourceAsset::sourceId)
        if (workflowId == null) {
            detailPanel.updateWorkflowSources(
                workflowId = null,
                assets = emptyList(),
                selectedSourceIds = emptySet(),
                editable = false,
            )
            return
        }

        val existingSelection = composerSelectedSourceIdsByWorkflowId[workflowId]
        val resolvedSelection = when {
            !preserveSelection || existingSelection == null -> {
                currentWorkflowSources.mapTo(linkedSetOf<String>(), WorkflowSourceAsset::sourceId)
            }

            else -> existingSelection.filterTo(linkedSetOf<String>()) { candidate ->
                currentWorkflowSources.any { asset -> asset.sourceId == candidate }
            }
        }
        preferredSourceIds.forEach { preferredSourceId ->
            if (currentWorkflowSources.any { asset -> asset.sourceId == preferredSourceId }) {
                resolvedSelection += preferredSourceId
            }
        }
        composerSelectedSourceIdsByWorkflowId[workflowId] = resolvedSelection
        detailPanel.updateWorkflowSources(
            workflowId = workflowId,
            assets = currentWorkflowSources,
            selectedSourceIds = resolvedSelection,
            editable = workflow.currentStage in COMPOSER_SOURCE_EDITABLE_STAGES,
        )
    }

    private fun onAddWorkflowSourcesRequested() {
        val workflow = currentWorkflow ?: return
        if (workflow.currentStage !in COMPOSER_SOURCE_EDITABLE_STAGES) {
            return
        }

        val selectedPaths = sourceFileChooser(project, sourceImportConstraints)
        if (selectedPaths.isEmpty()) {
            return
        }

        val validation = WorkflowSourceImportSupport.validate(selectedPaths, sourceImportConstraints)
        if (validation.acceptedPaths.isEmpty()) {
            showWorkflowSourceValidation(validation.rejectedFiles)
            setStatusText(
                SpecCodingBundle.message(
                    "spec.detail.sources.status.rejected",
                    validation.rejectedFiles.size,
                ),
            )
            return
        }

        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.detail.sources.importing"),
            task = {
                validation.acceptedPaths.map { sourcePath ->
                    specEngine.importWorkflowSource(
                        workflowId = workflow.id,
                        importedFromStage = workflow.currentStage,
                        importedFromEntry = WORKFLOW_SOURCE_ENTRY_SPEC_COMPOSER,
                        sourcePath = sourcePath,
                    ).getOrThrow()
                }
            },
            onSuccess = { importedAssets ->
                if (selectedWorkflowId != workflow.id) {
                    return@runBackground
                }
                val mergedAssets = (currentWorkflowSources + importedAssets)
                    .associateBy(WorkflowSourceAsset::sourceId)
                    .values
                    .sortedBy(WorkflowSourceAsset::sourceId)
                applyWorkflowSourcesToDetailPanel(
                    workflow = workflow,
                    assets = mergedAssets,
                    preserveSelection = true,
                    preferredSourceIds = importedAssets.mapTo(linkedSetOf<String>(), WorkflowSourceAsset::sourceId),
                )
                if (validation.rejectedFiles.isNotEmpty()) {
                    showWorkflowSourceValidation(validation.rejectedFiles)
                }
                val statusKey = if (validation.rejectedFiles.isEmpty()) {
                    "spec.detail.sources.status.imported"
                } else {
                    "spec.detail.sources.status.importedPartial"
                }
                setStatusText(
                    SpecCodingBundle.message(
                        statusKey,
                        importedAssets.size,
                        validation.rejectedFiles.size,
                    ),
                )
            },
            onFailure = { error ->
                val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
            },
        )
    }

    private fun onRemoveWorkflowSourceRequested(sourceId: String) {
        val workflow = currentWorkflow ?: return
        val selection = composerSelectedSourceIdsByWorkflowId.getOrPut(workflow.id) {
            currentWorkflowSources.mapTo(linkedSetOf<String>(), WorkflowSourceAsset::sourceId)
        }
        if (!selection.remove(sourceId)) {
            return
        }
        detailPanel.updateWorkflowSources(
            workflowId = workflow.id,
            assets = currentWorkflowSources,
            selectedSourceIds = selection,
            editable = workflow.currentStage in COMPOSER_SOURCE_EDITABLE_STAGES,
        )
        setStatusText(
            SpecCodingBundle.message(
                "spec.detail.sources.status.removed",
                sourceId,
            ),
        )
    }

    private fun onRestoreWorkflowSourcesRequested() {
        val workflow = currentWorkflow ?: return
        applyWorkflowSourcesToDetailPanel(
            workflow = workflow,
            assets = currentWorkflowSources,
            preserveSelection = false,
        )
        setStatusText(
            SpecCodingBundle.message(
                "spec.detail.sources.status.restored",
                currentWorkflowSources.size,
            ),
        )
    }

    private fun showWorkflowSourceValidation(rejectedFiles: List<RejectedWorkflowSourceFile>) {
        if (rejectedFiles.isEmpty()) {
            return
        }
        val lines = rejectedFiles
            .take(MAX_SOURCE_IMPORT_VALIDATION_LINES)
            .map { rejectedFile ->
                val fileName = rejectedFile.path.fileName?.toString().orEmpty().ifBlank { rejectedFile.path.toString() }
                val reason = when (rejectedFile.reason) {
                    RejectedWorkflowSourceFile.Reason.NOT_A_FILE ->
                        SpecCodingBundle.message("spec.detail.sources.validation.notFile")

                    RejectedWorkflowSourceFile.Reason.UNSUPPORTED_EXTENSION ->
                        SpecCodingBundle.message(
                            "spec.detail.sources.validation.unsupported",
                            WorkflowSourceImportSupport.formatAllowedExtensions(sourceImportConstraints),
                        )

                    RejectedWorkflowSourceFile.Reason.FILE_TOO_LARGE ->
                        SpecCodingBundle.message(
                            "spec.detail.sources.validation.tooLarge",
                            WorkflowSourceImportSupport.formatFileSize(sourceImportConstraints.maxFileSizeBytes),
                        )
                }
                "- $fileName: $reason"
            }
            .toMutableList()
        val remaining = rejectedFiles.size - MAX_SOURCE_IMPORT_VALIDATION_LINES
        if (remaining > 0) {
            lines += SpecCodingBundle.message("spec.detail.sources.validation.more", remaining)
        }
        warningDialogPresenter(
            project,
            lines.joinToString(separator = "\n"),
            SpecCodingBundle.message("spec.detail.sources.validation.title"),
        )
    }

    private fun onGenerate(input: String) {
        val workflow = resolveSelectedWorkflowForClarification() ?: return
        val pendingRetry = pendingClarificationRetryByWorkflowId[workflow.id]
        if (pendingRetry?.followUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR) {
            val effectiveInput = input.ifBlank { pendingRetry.input }
            val seededContext = when {
                input.isNotBlank() -> input
                pendingRetry.confirmedContext.isNotBlank() -> pendingRetry.confirmedContext
                else -> effectiveInput
            }
            val shouldResumeWithConfirmedContext = pendingRetry.confirmed &&
                input.isBlank() &&
                pendingRetry.confirmedContext.isNotBlank()
            if (shouldResumeWithConfirmedContext) {
                detailPanel.appendProcessTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                    state = SpecDetailPanel.ProcessTimelineState.INFO,
                )
                runRequirementsRepairAfterClarification(
                    workflowId = workflow.id,
                    pendingRetry = pendingRetry,
                    input = effectiveInput,
                    confirmedContext = pendingRetry.confirmedContext,
                )
                return
            }
            val clarificationRound = (pendingRetry.clarificationRound) + 1
            if (pendingRetry.questionsMarkdown.isBlank()) {
                detailPanel.clearProcessTimeline()
            }
            detailPanel.appendProcessTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.clarify.round", clarificationRound),
                state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
            )
            detailPanel.appendProcessTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                state = SpecDetailPanel.ProcessTimelineState.INFO,
            )
            rememberClarificationRetry(
                workflowId = workflow.id,
                input = effectiveInput,
                confirmedContext = seededContext,
                clarificationRound = clarificationRound,
                lastError = pendingRetry.lastError,
                confirmed = false,
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = pendingRetry.requirementsRepairSections,
            )
            requestRequirementsRepairClarification(
                workflow = workflow,
                input = effectiveInput,
                suggestedDetails = seededContext,
                pendingRetry = pendingClarificationRetryByWorkflowId[workflow.id],
                clarificationRound = clarificationRound,
            )
            return
        }

        val context = resolveGenerationContext() ?: return
        val clarificationRound = (pendingRetry?.clarificationRound ?: 0) + 1
        val effectiveInput = input.ifBlank { pendingRetry?.input.orEmpty() }
        val seededContext = when {
            input.isNotBlank() -> input
            !pendingRetry?.confirmedContext.isNullOrBlank() -> pendingRetry?.confirmedContext.orEmpty()
            else -> effectiveInput
        }
        val shouldResumeWithConfirmedContext = pendingRetry?.confirmed == true &&
            input.isBlank() &&
            pendingRetry.confirmedContext.isNotBlank()
        if (shouldResumeWithConfirmedContext) {
            detailPanel.appendProcessTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                state = SpecDetailPanel.ProcessTimelineState.INFO,
            )
            runGeneration(
                workflowId = context.workflowId,
                input = effectiveInput,
                options = context.options.copy(
                    confirmedContext = pendingRetry.confirmedContext,
                    clarificationWriteback = pendingRetry.toWritebackPayload(),
                ),
            )
            return
        }
        if (pendingRetry == null) {
            detailPanel.clearProcessTimeline()
        }
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message("spec.workflow.process.clarify.round", clarificationRound),
            state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
        )
        if (pendingRetry != null) {
            detailPanel.appendProcessTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                state = SpecDetailPanel.ProcessTimelineState.INFO,
            )
        }
        rememberClarificationRetry(
            workflowId = context.workflowId,
            input = effectiveInput,
            confirmedContext = seededContext,
            clarificationRound = clarificationRound,
            lastError = pendingRetry?.lastError,
            confirmed = false,
            followUp = ClarificationFollowUp.GENERATION,
            requirementsRepairSections = emptyList(),
        )
        requestClarificationDraft(
            context = context,
            input = effectiveInput,
            options = context.options.copy(
                confirmedContext = pendingRetry?.confirmedContext,
            ),
            suggestedDetails = seededContext,
            seedQuestionsMarkdown = pendingRetry?.questionsMarkdown,
            seedStructuredQuestions = pendingRetry?.structuredQuestions.orEmpty(),
            clarificationRound = clarificationRound,
        )
    }

    private fun onClarificationConfirm(
        input: String,
        confirmedContext: String,
    ) {
        val workflow = resolveSelectedWorkflowForClarification()
        if (workflow == null) {
            detailPanel.unlockClarificationChecklistInteractions()
            return
        }
        val pendingRetry = pendingClarificationRetryByWorkflowId[workflow.id]
        detailPanel.lockClarificationChecklistInteractions()
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message("spec.workflow.process.clarify.confirmed"),
            state = SpecDetailPanel.ProcessTimelineState.DONE,
        )
        rememberClarificationRetry(
            workflowId = workflow.id,
            input = input,
            confirmedContext = confirmedContext,
            clarificationRound = pendingRetry?.clarificationRound,
            confirmed = true,
            followUp = pendingRetry?.followUp,
            requirementsRepairSections = pendingRetry?.requirementsRepairSections.orEmpty(),
        )
        val refreshedRetry = pendingClarificationRetryByWorkflowId[workflow.id]
        if (refreshedRetry?.followUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR) {
            runRequirementsRepairAfterClarification(
                workflowId = workflow.id,
                pendingRetry = refreshedRetry,
                input = input,
                confirmedContext = confirmedContext,
            )
            return
        }
        val context = resolveGenerationContext()
        if (context == null) {
            detailPanel.unlockClarificationChecklistInteractions()
            return
        }
        runGeneration(
            workflowId = context.workflowId,
            input = input,
            options = context.options.copy(
                confirmedContext = confirmedContext,
                clarificationWriteback = refreshedRetry.toWritebackPayload(confirmedContext = confirmedContext),
            ),
        )
    }

    private fun onClarificationRegenerate(
        input: String,
        currentDraft: String,
    ) {
        val workflow = resolveSelectedWorkflowForClarification() ?: return
        val pendingRetry = pendingClarificationRetryByWorkflowId[workflow.id]
        val clarificationRound = (pendingRetry?.clarificationRound ?: 0) + 1
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message("spec.workflow.process.clarify.regenerate", clarificationRound),
            state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
        )
        if (pendingRetry?.followUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR) {
            requestRequirementsRepairClarification(
                workflow = workflow,
                input = input,
                suggestedDetails = currentDraft,
                pendingRetry = pendingRetry,
                clarificationRound = clarificationRound,
            )
            return
        }
        val context = resolveGenerationContext() ?: return
        requestClarificationDraft(
            context = context,
            input = input,
            options = context.options.copy(confirmedContext = currentDraft),
            suggestedDetails = currentDraft,
            seedQuestionsMarkdown = pendingRetry?.questionsMarkdown,
            seedStructuredQuestions = pendingRetry?.structuredQuestions.orEmpty(),
            clarificationRound = clarificationRound,
        )
    }

    private fun onClarificationSkip(input: String) {
        val workflow = resolveSelectedWorkflowForClarification() ?: return
        val pendingRetry = pendingClarificationRetryByWorkflowId[workflow.id]
        if (pendingRetry?.followUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR) {
            rememberClarificationRetry(
                workflowId = workflow.id,
                input = input,
                confirmedContext = "",
                clarificationRound = pendingRetry.clarificationRound,
                confirmed = false,
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = pendingRetry.requirementsRepairSections,
            )
            detailPanel.appendProcessTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.clarify.skipped"),
                state = SpecDetailPanel.ProcessTimelineState.INFO,
            )
            setStatusText(
                ArtifactComposeActionUiText.clarificationSkippedProceed(
                    workflow.resolveComposeActionMode(workflow.currentPhase),
                ),
            )
            runRequirementsRepairAfterClarification(
                workflowId = workflow.id,
                pendingRetry = pendingClarificationRetryByWorkflowId[workflow.id] ?: pendingRetry,
                input = input,
                confirmedContext = "",
            )
            return
        }
        val context = resolveGenerationContext() ?: return
        clearClarificationRetry(context.workflowId)
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message("spec.workflow.process.clarify.skipped"),
            state = SpecDetailPanel.ProcessTimelineState.INFO,
        )
        setStatusText(
            ArtifactComposeActionUiText.clarificationSkippedProceed(
                context.options.composeActionMode ?: ArtifactComposeActionMode.GENERATE,
            ),
        )
        runGeneration(
            workflowId = context.workflowId,
            input = input,
            options = context.options,
        )
    }

    private fun onClarificationCancel() {
        cancelActiveGenerationRequest("Clarification cancelled by user")
        selectedWorkflowId?.let { workflowId ->
            clearClarificationRetry(workflowId)
        }
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message("spec.workflow.process.clarify.cancelled"),
            state = SpecDetailPanel.ProcessTimelineState.INFO,
        )
        setStatusText(
            ArtifactComposeActionUiText.clarificationCancelled(
                currentWorkflow?.resolveComposeActionMode(currentWorkflow?.currentPhase ?: SpecPhase.SPECIFY)
                    ?: ArtifactComposeActionMode.GENERATE,
            ),
        )
    }

    private fun onClarificationDraftAutosave(
        input: String,
        confirmedContext: String,
        questionsMarkdown: String,
        structuredQuestions: List<String>,
    ) {
        val workflowId = selectedWorkflowId ?: return
        val activeWorkflowId = currentWorkflow?.id
        if (activeWorkflowId != null && activeWorkflowId != workflowId) {
            return
        }
        rememberClarificationRetry(
            workflowId = workflowId,
            input = input,
            confirmedContext = confirmedContext,
            questionsMarkdown = questionsMarkdown,
            structuredQuestions = structuredQuestions,
            clarificationRound = pendingClarificationRetryByWorkflowId[workflowId]?.clarificationRound,
            followUp = pendingClarificationRetryByWorkflowId[workflowId]?.followUp,
            requirementsRepairSections = pendingClarificationRetryByWorkflowId[workflowId]?.requirementsRepairSections.orEmpty(),
            persist = false,
        )
    }

    private fun resolveSelectedWorkflowForClarification(): SpecWorkflow? {
        val workflowId = selectedWorkflowId ?: return null
        val workflow = currentWorkflow?.takeIf { it.id == workflowId }
        if (workflow == null) {
            setStatusText(SpecCodingBundle.message("spec.workflow.error", SpecCodingBundle.message("common.unknown")))
        }
        return workflow
    }

    private fun resolveOptionalGenerationContext(workflow: SpecWorkflow): GenerationContext? {
        val selectedProvider = (providerComboBox.selectedItem as? String)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val selectedModel = (modelComboBox.selectedItem as? ModelInfo)
            ?.id
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        return GenerationContext(
            workflowId = workflow.id,
            phase = workflow.currentPhase,
            options = GenerationOptions(
                providerId = selectedProvider,
                model = selectedModel,
                workflowSourceUsage = resolveComposerSourceUsage(workflow.id),
                composeActionMode = workflow.resolveComposeActionMode(workflow.currentPhase),
            ),
        )
    }

    private fun repairTasksArtifactFromGate(workflowId: String): Boolean {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.editor.fixTasks.progress"),
            task = {
                specTasksQuickFixService.repairTasksArtifact(
                    workflowId = workflowId,
                    trigger = SpecTasksQuickFixService.TRIGGER_GATE_QUICK_FIX,
                )
            },
            onSuccess = { result ->
                SpecWorkflowActionSupport.rememberWorkflow(project, result.workflowId)
                when {
                    !result.changed -> {
                        SpecWorkflowActionSupport.showInfo(
                            project = project,
                            title = SpecCodingBundle.message("spec.action.editor.fixTasks.none.title"),
                            message = SpecCodingBundle.message("spec.action.editor.fixTasks.none.message"),
                        )
                    }

                    result.issuesAfter.isNotEmpty() -> {
                        val firstIssue = result.issuesAfter.first()
                        SpecWorkflowActionSupport.showInfo(
                            project = project,
                            title = SpecCodingBundle.message("spec.action.editor.fixTasks.partial.title"),
                            message = SpecCodingBundle.message(
                                "spec.action.editor.fixTasks.partial.message",
                                result.issuesAfter.size,
                                firstIssue.line,
                                firstIssue.message,
                            ),
                        )
                        SpecWorkflowActionSupport.openFile(project, result.tasksDocumentPath, firstIssue.line)
                    }

                    else -> {
                        SpecWorkflowActionSupport.notifySuccess(
                            project = project,
                            message = SpecCodingBundle.message(
                                "spec.action.editor.fixTasks.success.message",
                                result.issuesBefore.size,
                            ),
                        )
                        SpecWorkflowActionSupport.openFile(project, result.tasksDocumentPath)
                    }
                }
                refreshWorkflows(selectWorkflowId = result.workflowId)
            },
        )
        return true
    }

    private fun repairRequirementsArtifactFromGate(workflowId: String): Boolean {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.editor.fixRequirements.progress"),
            task = {
                specRequirementsQuickFixService.repairRequirementsArtifact(
                    workflowId = workflowId,
                    trigger = SpecRequirementsQuickFixService.TRIGGER_GATE_QUICK_FIX,
                )
            },
            onSuccess = { result ->
                SpecWorkflowActionSupport.rememberWorkflow(project, result.workflowId)
                when {
                    result.issuesBefore.isEmpty() -> {
                        SpecWorkflowActionSupport.showInfo(
                            project = project,
                            title = SpecCodingBundle.message("spec.action.editor.fixRequirements.none.title"),
                            message = SpecCodingBundle.message("spec.action.editor.fixRequirements.none.message"),
                        )
                    }

                    result.issuesAfter.isNotEmpty() -> {
                        SpecWorkflowActionSupport.showInfo(
                            project = project,
                            title = SpecCodingBundle.message("spec.action.editor.fixRequirements.partial.title"),
                            message = SpecCodingBundle.message(
                                "spec.action.editor.fixRequirements.partial.message",
                                result.issuesAfter.size,
                            ),
                        )
                        SpecWorkflowActionSupport.openFile(project, result.requirementsDocumentPath, 1)
                    }

                    else -> {
                        SpecWorkflowActionSupport.notifySuccess(
                            project = project,
                            message = SpecCodingBundle.message(
                                "spec.action.editor.fixRequirements.success.message",
                                result.issuesBefore.size,
                            ),
                        )
                        SpecWorkflowActionSupport.openFile(project, result.requirementsDocumentPath)
                    }
                }
                refreshWorkflows(selectWorkflowId = result.workflowId)
            },
        )
        return true
    }

    private fun startRequirementsClarifyThenFill(
        workflowId: String,
        missingSections: List<RequirementsSectionId>,
    ): Boolean {
        val workflow = currentWorkflow?.takeIf { it.id == workflowId } ?: return false
        val normalizedSections = missingSections.distinct()
        if (normalizedSections.isEmpty()) {
            return false
        }
        showWorkspaceContent()
        focusStage(StageId.REQUIREMENTS)

        val previous = pendingClarificationRetryByWorkflowId[workflowId]
            ?.takeIf { retry ->
                retry.followUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR &&
                    retry.requirementsRepairSections == normalizedSections
            }
        val input = previous?.input?.takeIf(String::isNotBlank)
            ?: buildRequirementsRepairClarificationInput(normalizedSections)
        val suggestedDetails = previous?.confirmedContext?.takeIf(String::isNotBlank)
            ?: buildRequirementsRepairSuggestedDetails(normalizedSections)
        val clarificationRound = (previous?.clarificationRound ?: 0) + 1

        if (previous == null) {
            detailPanel.clearProcessTimeline()
        }
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message("spec.workflow.process.clarify.round", clarificationRound),
            state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
        )
        if (previous != null) {
            detailPanel.appendProcessTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                state = SpecDetailPanel.ProcessTimelineState.INFO,
            )
        }
        rememberClarificationRetry(
            workflowId = workflowId,
            input = input,
            confirmedContext = suggestedDetails,
            questionsMarkdown = previous?.questionsMarkdown,
            structuredQuestions = previous?.structuredQuestions,
            clarificationRound = clarificationRound,
            lastError = previous?.lastError,
            confirmed = false,
            followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
            requirementsRepairSections = normalizedSections,
        )
        requestRequirementsRepairClarification(
            workflow = workflow,
            input = input,
            suggestedDetails = suggestedDetails,
            pendingRetry = pendingClarificationRetryByWorkflowId[workflowId],
            clarificationRound = clarificationRound,
        )
        return true
    }

    private fun requestRequirementsRepairClarification(
        workflow: SpecWorkflow,
        input: String,
        suggestedDetails: String,
        pendingRetry: ClarificationRetryPayload?,
        clarificationRound: Int,
    ) {
        val context = resolveOptionalGenerationContext(workflow)
        val unavailableReason = when {
            workflow.currentPhase != SpecPhase.SPECIFY ->
                SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.manualFallback.phase")

            context == null -> RequirementsSectionAiSupport.unavailableReason(providerHint = null)
                ?: SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.manualFallback.model")

            else -> null
        }
        if (unavailableReason != null) {
            showRequirementsRepairClarificationFallback(
                workflowId = workflow.id,
                phase = workflow.currentPhase,
                input = input,
                suggestedDetails = suggestedDetails,
                clarificationRound = clarificationRound,
                reason = unavailableReason,
            )
            return
        }
        val resolvedContext = context ?: return
        requestClarificationDraft(
            context = resolvedContext,
            input = input,
            options = resolvedContext.options.copy(
                confirmedContext = pendingRetry?.confirmedContext,
            ),
            suggestedDetails = suggestedDetails,
            seedQuestionsMarkdown = pendingRetry?.questionsMarkdown,
            seedStructuredQuestions = pendingRetry?.structuredQuestions.orEmpty(),
            clarificationRound = clarificationRound,
        )
    }

    private fun showRequirementsRepairClarificationFallback(
        workflowId: String,
        phase: SpecPhase,
        input: String,
        suggestedDetails: String,
        clarificationRound: Int,
        reason: String,
    ) {
        val markdown = buildClarificationMarkdown(
            draft = null,
            error = IllegalStateException(reason),
        )
        rememberClarificationRetry(
            workflowId = workflowId,
            input = input,
            confirmedContext = suggestedDetails,
            questionsMarkdown = markdown,
            structuredQuestions = emptyList(),
            clarificationRound = clarificationRound,
            lastError = reason,
            confirmed = false,
            followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
            requirementsRepairSections = pendingClarificationRetryByWorkflowId[workflowId]?.requirementsRepairSections.orEmpty(),
        )
        detailPanel.showClarificationDraft(
            phase = phase,
            input = input,
            questionsMarkdown = markdown,
            suggestedDetails = suggestedDetails,
            structuredQuestions = emptyList(),
        )
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message("spec.workflow.process.clarify.prepare"),
            state = SpecDetailPanel.ProcessTimelineState.DONE,
        )
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.manualFallback.timeline"),
            state = SpecDetailPanel.ProcessTimelineState.INFO,
        )
        setStatusText(
            SpecCodingBundle.message(
                "spec.toolwindow.gate.quickFix.clarify.manualFallback.status",
                reason,
            ),
        )
    }

    private fun runRequirementsRepairAfterClarification(
        workflowId: String,
        pendingRetry: ClarificationRetryPayload,
        input: String,
        confirmedContext: String?,
    ) {
        val requestedSections = pendingRetry.requirementsRepairSections
        if (requestedSections.isEmpty()) {
            detailPanel.unlockClarificationChecklistInteractions()
            setStatusText(SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.noop.message"))
            return
        }

        val unavailableReason = RequirementsSectionAiSupport.unavailableReason()
        if (unavailableReason != null) {
            detailPanel.unlockClarificationChecklistInteractions()
            detailPanel.exitClarificationMode(clearInput = false)
            setStatusText(
                SpecCodingBundle.message(
                    "spec.toolwindow.gate.quickFix.clarify.manualFallback.status",
                    unavailableReason,
                ),
            )
            runCatching {
                SpecWorkflowActionSupport.openFile(
                    project,
                    artifactService.locateArtifact(workflowId, StageId.REQUIREMENTS),
                )
            }
            SpecWorkflowActionSupport.showInfo(
                project,
                SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.manualContinue.title"),
                SpecCodingBundle.message(
                    "spec.toolwindow.gate.quickFix.clarify.manualContinue.message",
                    RequirementsSectionSupport.describeSections(requestedSections),
                    unavailableReason,
                ),
            )
            return
        }

        RequirementsSectionRepairUiSupport.previewAndApply(
            project = project,
            workflowId = workflowId,
            missingSections = requestedSections,
            confirmedContextOverride = confirmedContext,
            previewTitle = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.progress.preview"),
            applyTitle = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.progress.apply"),
            onPreviewCancelled = {
                detailPanel.unlockClarificationChecklistInteractions()
                setStatusText(SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.previewCancelled"))
            },
            onNoop = {
                clearClarificationRetry(workflowId)
                detailPanel.exitClarificationMode(clearInput = true)
                detailPanel.unlockClarificationChecklistInteractions()
                reloadCurrentWorkflow(followCurrentPhase = true)
            },
            onApplied = {
                clearClarificationRetry(workflowId)
                detailPanel.exitClarificationMode(clearInput = true)
                detailPanel.unlockClarificationChecklistInteractions()
                reloadCurrentWorkflow(followCurrentPhase = true) {
                    focusStage(StageId.REQUIREMENTS)
                }
            },
            onFailure = { error ->
                detailPanel.unlockClarificationChecklistInteractions()
                rememberClarificationRetry(
                    workflowId = workflowId,
                    input = input,
                    confirmedContext = confirmedContext ?: pendingRetry.confirmedContext,
                    clarificationRound = pendingRetry.clarificationRound,
                    lastError = compactErrorMessage(error, SpecCodingBundle.message("common.unknown")),
                    confirmed = pendingRetry.confirmed,
                    followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                    requirementsRepairSections = requestedSections,
                )
                setStatusText(
                    SpecCodingBundle.message(
                        "spec.workflow.error",
                        compactErrorMessage(error, SpecCodingBundle.message("common.unknown")),
                    ),
                )
            },
        )
    }

    private fun buildRequirementsRepairClarificationInput(
        missingSections: List<RequirementsSectionId>,
    ): String {
        return buildString {
            append("Repair requirements.md by filling the missing top-level sections: ")
            append(RequirementsSectionSupport.describeSections(missingSections))
            append(".")
        }
    }

    private fun onSwitchWorkflowRequested() {
        val currentItems = listPanel.currentItems()
        if (currentItems.isEmpty()) {
            return
        }
        workflowSwitcherPopup?.cancel()
        workflowSwitcherPopup = SpecWorkflowSwitcherPopup(
            items = currentItems,
            initialSelectionWorkflowId = selectedWorkflowId ?: highlightedWorkflowId,
            onOpenWorkflow = { workflowId ->
                workflowSwitcherPopup = null
                onWorkflowOpenedByUser(workflowId)
            },
            onEditWorkflow = { workflowId ->
                workflowSwitcherPopup = null
                onEditWorkflow(workflowId)
            },
            onDeleteWorkflow = { workflowId ->
                workflowSwitcherPopup = null
                onDeleteWorkflow(workflowId)
            },
        ).also { popup ->
            popup.showUnderneathOf(switchWorkflowButton)
        }
    }

    private fun buildRequirementsRepairSuggestedDetails(
        missingSections: List<RequirementsSectionId>,
    ): String {
        return buildString {
            appendLine("## ${SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.context.title")}")
            appendLine(
                SpecCodingBundle.message(
                    "spec.toolwindow.gate.quickFix.missingSections",
                    RequirementsSectionSupport.describeSections(missingSections),
                ),
            )
            appendLine()
            appendLine(SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.context.hint"))
        }.trim()
    }

    private fun requestClarificationDraft(
        context: GenerationContext,
        input: String,
        options: GenerationOptions = context.options,
        suggestedDetails: String = input,
        seedQuestionsMarkdown: String? = null,
        seedStructuredQuestions: List<String> = emptyList(),
        clarificationRound: Int = 1,
    ) {
        cancelActiveGenerationRequest("Superseded by new clarification request")
        val requestOptions = withGenerationRequestId(
            workflowId = context.workflowId,
            phase = context.phase,
            options = options,
        )
        val activeRequest = ActiveGenerationRequest(
            workflowId = context.workflowId,
            providerId = requestOptions.providerId,
            requestId = requestOptions.requestId.orEmpty(),
        )
        activeGenerationRequest = activeRequest
        activeGenerationJob = scope.launch(Dispatchers.IO) {
            try {
                val safeSuggestedDetails = suggestedDetails.ifBlank { input }
                val fallbackPhase = context.phase
                val composeMode = requestOptions.composeActionMode ?: ArtifactComposeActionMode.GENERATE
                invokeLaterSafe {
                    if (selectedWorkflowId != context.workflowId) {
                        return@invokeLaterSafe
                    }
                    detailPanel.showClarificationGenerating(
                        phase = fallbackPhase,
                        input = input,
                        suggestedDetails = safeSuggestedDetails,
                    )
                    seedQuestionsMarkdown
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            detailPanel.appendProcessTimelineEntry(
                                text = SpecCodingBundle.message("spec.workflow.process.clarify.lastRoundReused"),
                                state = SpecDetailPanel.ProcessTimelineState.INFO,
                            )
                        }
                    detailPanel.appendProcessTimelineEntry(
                        text = SpecCodingBundle.message("spec.workflow.process.clarify.prepare"),
                        state = SpecDetailPanel.ProcessTimelineState.DONE,
                    )
                    detailPanel.appendProcessTimelineEntry(
                        text = SpecCodingBundle.message("spec.workflow.process.clarify.request", clarificationRound),
                        state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
                    )
                    setStatusText(ArtifactComposeActionUiText.clarificationGenerating(composeMode))
                }
                val draftResult = try {
                    Result.success(
                        specEngine.draftCurrentPhaseClarification(
                            workflowId = context.workflowId,
                            input = input,
                            options = requestOptions,
                        ).getOrThrow(),
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    Result.failure(error)
                }

                val draft = draftResult.getOrNull()
                val draftError = draftResult.exceptionOrNull()
                if (draft == null) {
                    logger.warn("Failed to draft clarification for workflow=${context.workflowId}", draftError)
                }
                invokeLaterSafe {
                    if (selectedWorkflowId != context.workflowId) {
                        return@invokeLaterSafe
                    }
                    val markdown = buildClarificationMarkdown(draft, draftError)
                    val structuredQuestions = if (draft != null) {
                        draft.questions
                    } else {
                        seedStructuredQuestions
                    }
                    detailPanel.showClarificationDraft(
                        phase = draft?.phase ?: fallbackPhase,
                        input = input,
                        questionsMarkdown = markdown,
                        suggestedDetails = safeSuggestedDetails,
                        structuredQuestions = structuredQuestions,
                    )
                    val errorText = compactErrorMessage(draftError, SpecCodingBundle.message("common.unknown"))
                    rememberClarificationRetry(
                        workflowId = context.workflowId,
                        input = input,
                        confirmedContext = safeSuggestedDetails,
                        questionsMarkdown = markdown,
                        structuredQuestions = structuredQuestions,
                        clarificationRound = clarificationRound,
                        lastError = draftError?.let { errorText },
                    )
                    if (draft == null) {
                        detailPanel.appendProcessTimelineEntry(
                            text = SpecCodingBundle.message("spec.workflow.process.clarify.failed", errorText),
                            state = SpecDetailPanel.ProcessTimelineState.FAILED,
                        )
                        setStatusText(SpecCodingBundle.message("spec.workflow.error", errorText))
                    } else {
                        detailPanel.appendProcessTimelineEntry(
                            text = SpecCodingBundle.message("spec.workflow.process.clarify.ready"),
                            state = SpecDetailPanel.ProcessTimelineState.DONE,
                        )
                        setStatusText(null)
                    }
                }
            } catch (cancel: CancellationException) {
                if (isActiveGenerationRequest(activeRequest)) {
                    handleGenerationInterrupted(
                        workflowId = context.workflowId,
                        input = input,
                        options = requestOptions,
                        processMessageKey = "spec.workflow.process.clarify.failed",
                    )
                }
                throw cancel
            } finally {
                clearActiveGenerationRequest(activeRequest)
            }
        }
    }

    private fun runGeneration(
        workflowId: String,
        input: String,
        options: GenerationOptions,
    ) {
        cancelActiveGenerationRequest("Superseded by new generation request")
        val requestOptions = withGenerationRequestId(
            workflowId = workflowId,
            phase = currentWorkflow?.currentPhase ?: SpecPhase.SPECIFY,
            options = options,
        )
        val composeMode = requestOptions.composeActionMode ?: ArtifactComposeActionMode.GENERATE
        val activeRequest = ActiveGenerationRequest(
            workflowId = workflowId,
            providerId = requestOptions.providerId,
            requestId = requestOptions.requestId.orEmpty(),
        )
        activeGenerationRequest = activeRequest
        activeGenerationJob = scope.launch(Dispatchers.IO) {
            try {
                var modelCallRecorded = false
                var normalizeRecorded = false
                specEngine.generateCurrentPhase(workflowId, input, requestOptions).collect { progress ->
                    invokeLaterSafe {
                        when (progress) {
                            is SpecGenerationProgress.Started -> {
                                detailPanel.appendProcessTimelineEntry(
                                    text = ArtifactComposeActionUiText.processPrepare(composeMode),
                                    state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
                                )
                                detailPanel.showGenerating(0.0)
                            }
                            is SpecGenerationProgress.Generating -> {
                                if (!modelCallRecorded) {
                                    detailPanel.appendProcessTimelineEntry(
                                        text = ArtifactComposeActionUiText.processCall(
                                            composeMode,
                                            (progress.progress * 100).toInt().coerceIn(0, 100),
                                        ),
                                        state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
                                    )
                                    modelCallRecorded = true
                                }
                                if (progress.progress >= 0.5 && !normalizeRecorded) {
                                    detailPanel.appendProcessTimelineEntry(
                                        text = ArtifactComposeActionUiText.processNormalize(composeMode),
                                        state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
                                    )
                                    normalizeRecorded = true
                                }
                                detailPanel.showGenerating(progress.progress)
                            }
                            is SpecGenerationProgress.Completed -> {
                                detailPanel.appendProcessTimelineEntry(
                                    text = ArtifactComposeActionUiText.processValidate(composeMode),
                                    state = SpecDetailPanel.ProcessTimelineState.DONE,
                                )
                                detailPanel.appendProcessTimelineEntry(
                                    text = ArtifactComposeActionUiText.processSave(composeMode),
                                    state = SpecDetailPanel.ProcessTimelineState.DONE,
                                )
                                detailPanel.appendProcessTimelineEntry(
                                    text = ArtifactComposeActionUiText.processCompleted(composeMode),
                                    state = SpecDetailPanel.ProcessTimelineState.DONE,
                                )
                                clearClarificationRetry(workflowId)
                                detailPanel.exitClarificationMode(clearInput = true)
                                reloadCurrentWorkflow()
                            }
                            is SpecGenerationProgress.ValidationFailed -> {
                                val firstValidationError = progress.validation.errors.firstOrNull()
                                    ?: SpecCodingBundle.message("common.unknown")
                                detailPanel.appendProcessTimelineEntry(
                                    text = ArtifactComposeActionUiText.processValidate(composeMode),
                                    state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
                                )
                                detailPanel.appendProcessTimelineEntry(
                                    text = ArtifactComposeActionUiText.processValidationFailed(
                                        composeMode,
                                        firstValidationError,
                                    ),
                                    state = SpecDetailPanel.ProcessTimelineState.FAILED,
                                )
                                rememberClarificationRetry(
                                    workflowId = workflowId,
                                    input = input,
                                    confirmedContext = requestOptions.confirmedContext,
                                    clarificationRound = pendingClarificationRetryByWorkflowId[workflowId]?.clarificationRound,
                                    lastError = firstValidationError,
                                )
                                setStatusText(buildValidationFailureStatus(progress.validation))
                                reloadCurrentWorkflow { updated ->
                                    detailPanel.showValidationFailureInteractive(
                                        phase = updated.currentPhase,
                                        validation = progress.validation,
                                    )
                                }
                            }
                            is SpecGenerationProgress.Failed -> {
                                detailPanel.appendProcessTimelineEntry(
                                    text = ArtifactComposeActionUiText.processFailed(composeMode, progress.error),
                                    state = SpecDetailPanel.ProcessTimelineState.FAILED,
                                )
                                rememberClarificationRetry(
                                    workflowId = workflowId,
                                    input = input,
                                    confirmedContext = requestOptions.confirmedContext,
                                    clarificationRound = pendingClarificationRetryByWorkflowId[workflowId]?.clarificationRound,
                                    lastError = progress.error,
                                )
                                detailPanel.showGenerationFailed()
                                setStatusText(SpecCodingBundle.message("spec.workflow.error", progress.error))
                            }
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                if (isActiveGenerationRequest(activeRequest)) {
                    handleGenerationInterrupted(
                        workflowId = workflowId,
                        input = input,
                        options = requestOptions,
                        processMessageKey = when (composeMode) {
                            ArtifactComposeActionMode.GENERATE -> "spec.workflow.process.generate.failed"
                            ArtifactComposeActionMode.REVISE -> "spec.workflow.process.revise.failed"
                        },
                    )
                }
                throw cancel
            } finally {
                clearActiveGenerationRequest(activeRequest)
            }
        }
    }

    private fun handleGenerationInterrupted(
        workflowId: String,
        input: String,
        options: GenerationOptions,
        processMessageKey: String,
    ) {
        val interruptedMessage = SpecCodingBundle.message("spec.workflow.generation.interrupted")
        invokeLaterSafe {
            if (selectedWorkflowId != workflowId) {
                return@invokeLaterSafe
            }
            detailPanel.appendProcessTimelineEntry(
                text = SpecCodingBundle.message(processMessageKey, interruptedMessage),
                state = SpecDetailPanel.ProcessTimelineState.FAILED,
            )
            rememberClarificationRetry(
                workflowId = workflowId,
                input = input,
                confirmedContext = options.confirmedContext,
                clarificationRound = pendingClarificationRetryByWorkflowId[workflowId]?.clarificationRound,
                lastError = interruptedMessage,
            )
            detailPanel.showGenerationFailed()
            setStatusText(SpecCodingBundle.message("spec.workflow.error", interruptedMessage))
        }
    }

    private fun withGenerationRequestId(
        workflowId: String,
        phase: SpecPhase,
        options: GenerationOptions,
    ): GenerationOptions {
        val existing = options.requestId?.trim().orEmpty()
        if (existing.isNotBlank()) {
            return options.copy(requestId = existing)
        }
        val phaseToken = phase.name.lowercase(Locale.ROOT)
        val randomToken = UUID.randomUUID().toString().substring(0, 8)
        val requestId = "spec-$workflowId-$phaseToken-${System.currentTimeMillis()}-$randomToken"
        return options.copy(requestId = requestId)
    }

    private fun cancelActiveGenerationRequest(reason: String) {
        val activeRequest = activeGenerationRequest
        if (activeRequest != null && activeRequest.requestId.isNotBlank()) {
            cancelRequestAcrossProviders(
                providerId = activeRequest.providerId,
                requestId = activeRequest.requestId,
            )
        }
        activeGenerationJob?.cancel(CancellationException(reason))
        activeGenerationJob = null
        activeGenerationRequest = null
    }

    private fun cancelRequestAcrossProviders(
        providerId: String?,
        requestId: String,
    ) {
        llmRouter.cancel(providerId = providerId, requestId = requestId)
        llmRouter.cancel(providerId = ClaudeCliLlmProvider.ID, requestId = requestId)
        llmRouter.cancel(providerId = CodexCliLlmProvider.ID, requestId = requestId)
    }

    private fun isActiveGenerationRequest(request: ActiveGenerationRequest): Boolean {
        val active = activeGenerationRequest ?: return false
        return active.workflowId == request.workflowId && active.requestId == request.requestId
    }

    private fun clearActiveGenerationRequest(request: ActiveGenerationRequest) {
        if (!isActiveGenerationRequest(request)) {
            return
        }
        activeGenerationJob = null
        activeGenerationRequest = null
    }

    private fun resolveGenerationContext(): GenerationContext? {
        val wfId = selectedWorkflowId ?: return null
        val workflow = currentWorkflow?.takeIf { it.id == wfId }
        if (workflow == null) {
            setStatusText(SpecCodingBundle.message("spec.workflow.error", SpecCodingBundle.message("common.unknown")))
            return null
        }
        val providerId = providerComboBox.selectedItem as? String
        if (providerId.isNullOrBlank()) {
            setStatusText(SpecCodingBundle.message("spec.workflow.generation.providerRequired"))
            return null
        }
        val modelId = (modelComboBox.selectedItem as? ModelInfo)?.id?.trim().orEmpty()
        if (modelId.isBlank()) {
            setStatusText(SpecCodingBundle.message("spec.workflow.generation.modelRequired", providerDisplayName(providerId)))
            return null
        }
        return GenerationContext(
            workflowId = wfId,
            phase = workflow.currentPhase,
            options = GenerationOptions(
                providerId = providerId,
                model = modelId,
                workflowSourceUsage = resolveComposerSourceUsage(wfId),
                composeActionMode = workflow.resolveComposeActionMode(workflow.currentPhase),
            ),
        )
    }

    private fun resolveComposerSourceUsage(workflowId: String): WorkflowSourceUsage {
        val knownSourceIds = currentWorkflowSources
            .mapTo(linkedSetOf<String>(), WorkflowSourceAsset::sourceId)
        if (knownSourceIds.isEmpty()) {
            return WorkflowSourceUsage()
        }
        val selectedSourceIds = composerSelectedSourceIdsByWorkflowId[workflowId]
            ?.filterTo(linkedSetOf()) { candidate -> candidate in knownSourceIds }
            ?.takeIf { it.isNotEmpty() }
            ?: knownSourceIds
        return WorkflowSourceUsage(selectedSourceIds = selectedSourceIds.toList())
    }

    private fun buildClarificationMarkdown(
        draft: SpecClarificationDraft?,
        error: Throwable? = null,
    ): String {
        if (draft == null) {
            val base = SpecCodingBundle.message("spec.workflow.clarify.noQuestions")
            val reason = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
            return buildString {
                appendLine(base)
                appendLine()
                appendLine("```text")
                appendLine(reason)
                appendLine("```")
            }.trimEnd()
        }
        if (draft.rawContent.isNotBlank()) {
            return draft.rawContent
        }
        if (draft.questions.isNotEmpty()) {
            return buildString {
                appendLine("## ${SpecCodingBundle.message("spec.detail.clarify.questions.title")}")
                draft.questions.forEachIndexed { index, q ->
                    appendLine("${index + 1}. $q")
                }
            }.trimEnd()
        }
        return SpecCodingBundle.message("spec.workflow.clarify.noQuestions")
    }

    private data class GenerationContext(
        val workflowId: String,
        val phase: SpecPhase,
        val options: GenerationOptions,
    )

    private data class TaskExecutionContext(
        val providerId: String,
        val modelId: String,
        val operationMode: OperationMode,
    )

    private data class ClarificationRetryPayload(
        val input: String,
        val confirmedContext: String,
        val questionsMarkdown: String,
        val structuredQuestions: List<String>,
        val clarificationRound: Int,
        val lastError: String?,
        val confirmed: Boolean,
        val followUp: ClarificationFollowUp,
        val requirementsRepairSections: List<RequirementsSectionId>,
    )

    private data class ActiveGenerationRequest(
        val workflowId: String,
        val providerId: String?,
        val requestId: String,
    )

    private data class WorkflowRefreshTarget(
        val selectWorkflowId: String?,
        val preserveListMode: Boolean,
    )

    private fun ClarificationRetryPayload?.toWritebackPayload(
        confirmedContext: String? = null,
    ): ConfirmedClarificationPayload? {
        val context = normalizeRetryText(confirmedContext ?: this?.confirmedContext.orEmpty())
        if (context.isBlank()) {
            return null
        }
        return ConfirmedClarificationPayload(
            confirmedContext = context,
            questionsMarkdown = this?.questionsMarkdown.orEmpty(),
            structuredQuestions = this?.structuredQuestions.orEmpty(),
            clarificationRound = this?.clarificationRound ?: 1,
        )
    }

    private fun rememberClarificationRetry(
        workflowId: String,
        input: String,
        confirmedContext: String?,
        questionsMarkdown: String? = null,
        structuredQuestions: List<String>? = null,
        clarificationRound: Int? = null,
        lastError: String? = null,
        confirmed: Boolean? = null,
        followUp: ClarificationFollowUp? = null,
        requirementsRepairSections: List<RequirementsSectionId>? = null,
        persist: Boolean = true,
    ) {
        val previous = pendingClarificationRetryByWorkflowId[workflowId]
        val normalizedInput = normalizeRetryText(input)
        val normalizedContext = confirmedContext?.let { normalizeRetryText(it) }
        val normalizedQuestions = questionsMarkdown?.let { normalizeRetryText(it) }
        val normalizedError = lastError
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val mergedInput = normalizedInput.ifBlank { previous?.input.orEmpty() }
        val mergedContext = when {
            normalizedContext != null -> normalizedContext
            else -> previous?.confirmedContext.orEmpty()
        }
        val mergedQuestions = normalizedQuestions
            ?: previous?.questionsMarkdown
            .orEmpty()
        val mergedStructuredQuestions = structuredQuestions
            ?.map { normalizeRetryText(it) }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: previous?.structuredQuestions
            .orEmpty()
        val mergedRound = clarificationRound
            ?: previous?.clarificationRound
            ?: 1
        val mergedError = normalizedError ?: previous?.lastError
        val mergedConfirmed = confirmed ?: previous?.confirmed ?: false
        val mergedFollowUp = followUp ?: previous?.followUp ?: ClarificationFollowUp.GENERATION
        val mergedRequirementsRepairSections = requirementsRepairSections
            ?.distinct()
            ?: previous?.requirementsRepairSections
            .orEmpty()
        if (mergedInput.isBlank() && mergedContext.isBlank() && mergedQuestions.isBlank() && mergedStructuredQuestions.isEmpty()) {
            pendingClarificationRetryByWorkflowId.remove(workflowId)
            if (persist) {
                persistClarificationRetryState(workflowId, null)
            }
            return
        }
        val payload = ClarificationRetryPayload(
            input = mergedInput,
            confirmedContext = mergedContext,
            questionsMarkdown = mergedQuestions,
            structuredQuestions = mergedStructuredQuestions,
            clarificationRound = mergedRound,
            lastError = mergedError,
            confirmed = mergedConfirmed,
            followUp = if (
                mergedFollowUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR &&
                mergedRequirementsRepairSections.isNotEmpty()
            ) {
                ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR
            } else {
                ClarificationFollowUp.GENERATION
            },
            requirementsRepairSections = if (
                mergedFollowUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR &&
                mergedRequirementsRepairSections.isNotEmpty()
            ) {
                mergedRequirementsRepairSections
            } else {
                emptyList()
            },
        )
        pendingClarificationRetryByWorkflowId[workflowId] = payload
        if (persist) {
            persistClarificationRetryState(workflowId, payload)
        }
    }

    private fun normalizeRetryText(value: String): String {
        return value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    private fun clearClarificationRetry(workflowId: String, persist: Boolean = true) {
        pendingClarificationRetryByWorkflowId.remove(workflowId)
        if (persist) {
            persistClarificationRetryState(workflowId, null)
        }
    }

    private fun syncClarificationRetryFromWorkflow(workflow: SpecWorkflow) {
        val workflowId = workflow.id
        val state = workflow.clarificationRetryState
        if (state == null) {
            pendingClarificationRetryByWorkflowId.remove(workflowId)
            return
        }
        pendingClarificationRetryByWorkflowId[workflowId] = state.toPayload()
    }

    private fun persistClarificationRetryState(
        workflowId: String,
        payload: ClarificationRetryPayload?,
    ) {
        scope.launch(Dispatchers.IO) {
            specEngine.saveClarificationRetryState(
                workflowId = workflowId,
                state = payload?.toState(),
            ).onFailure { error ->
                logger.warn("Failed to persist clarification retry state for workflow=$workflowId", error)
            }
        }
    }

    private fun ClarificationRetryPayload.toState(): ClarificationRetryState {
        return ClarificationRetryState(
            input = input,
            confirmedContext = confirmedContext,
            questionsMarkdown = questionsMarkdown,
            structuredQuestions = structuredQuestions,
            clarificationRound = clarificationRound,
            lastError = lastError,
            confirmed = confirmed,
            followUp = followUp,
            requirementsRepairSections = requirementsRepairSections,
        )
    }

    private fun ClarificationRetryState.toPayload(): ClarificationRetryPayload {
        return ClarificationRetryPayload(
            input = input,
            confirmedContext = confirmedContext,
            questionsMarkdown = questionsMarkdown,
            structuredQuestions = structuredQuestions,
            clarificationRound = clarificationRound,
            lastError = lastError,
            confirmed = confirmed,
            followUp = followUp,
            requirementsRepairSections = requirementsRepairSections,
        )
    }

    private fun restorePendingClarificationState(workflowId: String) {
        val payload = pendingClarificationRetryByWorkflowId[workflowId] ?: return
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message(
                "spec.workflow.process.retryRestored",
                payload.clarificationRound,
            ),
            state = SpecDetailPanel.ProcessTimelineState.INFO,
        )
        payload.lastError
            ?.takeIf { it.isNotBlank() }
            ?.let { error ->
                detailPanel.appendProcessTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.retryLastError", error),
                    state = SpecDetailPanel.ProcessTimelineState.FAILED,
                )
            }
    }

    private fun onShowDelta() {
        val targetWorkflow = currentWorkflow
        if (targetWorkflow == null) {
            setStatusText(SpecCodingBundle.message("spec.delta.error.noCurrentWorkflow"))
            return
        }

        scope.launch(Dispatchers.IO) {
            val candidateWorkflows = specEngine.listWorkflows()
                .filter { it != targetWorkflow.id }
                .mapNotNull { workflowId ->
                    specEngine.loadWorkflow(workflowId).getOrNull()?.let { workflow ->
                        SpecBaselineSelectDialog.WorkflowOption(
                            workflowId = workflow.id,
                            title = workflow.title.ifBlank { workflow.id },
                            description = workflow.description,
                        )
                    }
                }

            if (candidateWorkflows.isEmpty()) {
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message("spec.delta.emptyCandidates"))
                }
                return@launch
            }

            invokeLaterSafe {
                val selectDialog = SpecBaselineSelectDialog(
                    workflowOptions = candidateWorkflows,
                    currentWorkflowId = targetWorkflow.id,
                )
                if (!selectDialog.showAndGet()) {
                    return@invokeLaterSafe
                }

                val baselineWorkflowId = selectDialog.selectedBaselineWorkflowId
                if (baselineWorkflowId.isNullOrBlank()) {
                    setStatusText(SpecCodingBundle.message("spec.delta.selectBaseline.required"))
                    return@invokeLaterSafe
                }

                scope.launch(Dispatchers.IO) {
                    val result = specDeltaService.compareByWorkflowId(
                        baselineWorkflowId = baselineWorkflowId,
                        targetWorkflowId = targetWorkflow.id,
                    )

                    invokeLaterSafe {
                        result.onSuccess { delta ->
                            showDeltaDialog(targetWorkflow, delta)
                            setStatusText(SpecCodingBundle.message("spec.delta.generated"))
                        }.onFailure { error ->
                            setStatusText(SpecCodingBundle.message(
                                "spec.workflow.error",
                                error.message ?: SpecCodingBundle.message("common.unknown"),
                            ))
                        }
                    }
                }
            }
        }
    }

    private fun onRunVerificationRequested(workflowId: String) {
        SpecWorkflowActionSupport.runVerificationWorkflow(
            project = project,
            verificationService = specVerificationService,
            tasksService = specTasksService,
            workflowId = workflowId,
            onCompleted = { result ->
                reloadCurrentWorkflow()
                setStatusText(result.summary)
            },
        )
    }

    private fun onOpenVerificationRequested(workflowId: String) {
        val path = artifactService.locateArtifact(workflowId, StageId.VERIFY)
        if (!Files.exists(path) || !SpecWorkflowActionSupport.openFile(project, path)) {
            setStatusText(SpecCodingBundle.message("spec.action.verify.document.unavailable.title"))
        }
    }

    private fun onCompareDeltaBaselineRequested(
        workflowId: String,
        choice: SpecWorkflowDeltaBaselineChoice,
    ) {
        val targetWorkflow = currentWorkflow?.takeIf { workflow -> workflow.id == workflowId } ?: return
        scope.launch(Dispatchers.IO) {
            val result = when (choice) {
                is SpecWorkflowReferenceBaselineChoice -> specDeltaService.compareByWorkflowId(
                    baselineWorkflowId = choice.workflowId,
                    targetWorkflowId = workflowId,
                )

                is SpecWorkflowPinnedDeltaBaselineChoice -> specDeltaService.compareByDeltaBaseline(
                    workflowId = workflowId,
                    baselineId = choice.baseline.baselineId,
                )
            }
            invokeLaterSafe {
                result.onSuccess { delta ->
                    showDeltaDialog(targetWorkflow, delta)
                    setStatusText(SpecCodingBundle.message("spec.delta.generated"))
                }.onFailure { error ->
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.workflow.error",
                            error.message ?: SpecCodingBundle.message("common.unknown"),
                        ),
                    )
                }
            }
        }
    }

    private fun onPinDeltaBaselineRequested(workflowId: String) {
        scope.launch(Dispatchers.IO) {
            val snapshot = specEngine.listWorkflowSnapshots(workflowId).firstOrNull()
            if (snapshot == null) {
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message("spec.toolwindow.verifyDelta.pin.unavailable"))
                }
                return@launch
            }
            val label = SpecCodingBundle.message(
                "spec.toolwindow.verifyDelta.pin.autoLabel",
                snapshot.snapshotId,
            )
            val result = specEngine.pinDeltaBaseline(
                workflowId = workflowId,
                snapshotId = snapshot.snapshotId,
                label = label,
            )
            invokeLaterSafe {
                result.onSuccess { baseline ->
                    reloadCurrentWorkflow()
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.toolwindow.verifyDelta.pin.saved",
                            baseline.label ?: baseline.baselineId,
                        ),
                    )
                }.onFailure { error ->
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.workflow.error",
                            error.message ?: SpecCodingBundle.message("common.unknown"),
                        ),
                    )
                }
            }
        }
    }

    private fun showDeltaDialog(
        targetWorkflow: SpecWorkflow,
        delta: SpecWorkflowDelta,
    ) {
        SpecDeltaDialog(
            project = project,
            delta = delta,
            onOpenHistoryDiff = { phase ->
                onShowHistoryDiffForWorkflow(
                    workflowId = targetWorkflow.id,
                    phase = phase,
                    currentDoc = targetWorkflow.documents[phase],
                )
            },
            onExportReport = { format -> specDeltaService.exportReport(delta, format) },
            onReportExported = { export ->
                setStatusText(SpecCodingBundle.message("spec.delta.export.done", export.fileName))
            },
        ).show()
    }

    private fun onArchiveWorkflow() {
        val workflow = currentWorkflow
        if (workflow == null) {
            setStatusText(SpecCodingBundle.message("spec.workflow.archive.selectFirst"))
            return
        }
        if (workflow.status != WorkflowStatus.COMPLETED) {
            setStatusText(SpecCodingBundle.message("spec.workflow.archive.onlyCompleted"))
            return
        }
        if (!SpecWorkflowActionSupport.confirmArchive(project, workflow.id)) {
            return
        }

        scope.launch(Dispatchers.IO) {
            val result = specEngine.archiveWorkflow(workflow.id)
            invokeLaterSafe {
                result.onSuccess {
                    if (selectedWorkflowId == workflow.id) {
                        clearOpenedWorkflowUi(resetHighlight = false)
                    }
                    refreshWorkflows()
                    setStatusText(SpecCodingBundle.message("spec.workflow.archive.done", workflow.id))
                }.onFailure { error ->
                    setStatusText(SpecCodingBundle.message(
                        "spec.workflow.archive.failed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    ))
                }
            }
        }
    }

    private fun onTaskStatusTransitionRequested(taskId: String, to: TaskStatus) {
        val workflowId = selectedWorkflowId ?: return
        val auditContext = buildTaskAuditContext(taskId, "STATUS_${to.name}")
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.status.progress"),
            task = {
                specTasksService.transitionStatus(
                    workflowId = workflowId,
                    taskId = taskId,
                    to = to,
                    auditContext = auditContext,
                )
            },
            onSuccess = {
                setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.status.updated", taskId, to.name))
                publishWorkflowChatRefresh(workflowId, taskId, "spec_task_status_transition")
                reloadCurrentWorkflow()
            },
        )
    }

    private fun onTaskDependsOnUpdateRequested(taskId: String, dependsOn: List<String>) {
        val workflowId = selectedWorkflowId ?: return
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.progress"),
            task = {
                specTasksService.updateDependsOn(
                    workflowId = workflowId,
                    taskId = taskId,
                    dependsOn = dependsOn,
                )
            },
            onSuccess = {
                setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.updated", taskId))
                reloadCurrentWorkflow()
            },
        )
    }

    private fun onTaskCompleteRequested(
        taskId: String,
        files: List<String>,
        verificationResult: TaskVerificationResult?,
    ) {
        val workflowId = selectedWorkflowId ?: return
        val auditContext = buildTaskAuditContext(taskId, "COMPLETE")
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.complete.progress"),
            task = {
                specTaskCompletionService.completeTask(
                    workflowId = workflowId,
                    taskId = taskId,
                    relatedFiles = files,
                    verificationResult = verificationResult,
                    auditContext = auditContext,
                    completionRunSummary = "Completed from spec workflow task action.",
                )
            },
            onSuccess = {
                setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.complete.updated", taskId))
                publishWorkflowChatRefresh(workflowId, taskId, "spec_task_completed")
                reloadCurrentWorkflow()
            },
        )
    }

    private fun onTaskExecutionRequested(taskId: String, retry: Boolean) {
        val workflowId = selectedWorkflowId ?: return
        val executionContext = resolveTaskExecutionContext() ?: return
        val reusableSessionId = resolveReusableWorkflowChatSessionId(workflowId)
        val actionName = if (retry) "RETRY_EXECUTION" else "EXECUTE_WITH_AI"
        val auditContext = buildTaskAuditContext(taskId, actionName)
        val progressKey = if (retry) {
            "spec.toolwindow.tasks.retry.progress"
        } else {
            "spec.toolwindow.tasks.execute.progress"
        }
        val cancellationHandleRef =
            AtomicReference<SpecTaskExecutionService.TaskExecutionCancellationHandle?>()
        val cancelRequested = AtomicBoolean(false)
        val chatSessionOpened = AtomicBoolean(false)
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message(progressKey, taskId),
            task = {
                val onRequestRegistered: (SpecTaskExecutionService.TaskExecutionCancellationHandle) -> Unit = { handle ->
                    cancellationHandleRef.set(handle)
                    if (chatSessionOpened.compareAndSet(false, true)) {
                        openWorkflowChatExecutionSession(
                            sessionId = handle.sessionId,
                            workflowId = handle.workflowId,
                        )
                    }
                    if (cancelRequested.get()) {
                        specTaskExecutionService.cancelExecutionRun(
                            workflowId = handle.workflowId,
                            runId = handle.runId,
                        )
                    }
                }
                val previousRunId = if (retry) {
                    specTaskExecutionService.listRuns(workflowId, taskId)
                        .firstOrNull { run -> run.status.isTerminal() }
                        ?.runId
                } else {
                    null
                }
                if (retry) {
                    specTaskExecutionService.retryAiExecution(
                        workflowId = workflowId,
                        taskId = taskId,
                        providerId = executionContext.providerId,
                        modelId = executionContext.modelId,
                        operationMode = executionContext.operationMode,
                        previousRunId = previousRunId,
                        sessionId = reusableSessionId,
                        auditContext = auditContext,
                        onRequestRegistered = onRequestRegistered,
                    )
                } else {
                    specTaskExecutionService.startAiExecution(
                        workflowId = workflowId,
                        taskId = taskId,
                        providerId = executionContext.providerId,
                        modelId = executionContext.modelId,
                        operationMode = executionContext.operationMode,
                        sessionId = reusableSessionId,
                        auditContext = auditContext,
                        onRequestRegistered = onRequestRegistered,
                    )
                }
            },
            onSuccess = { result ->
                val statusKey = if (retry) {
                    "spec.toolwindow.tasks.retry.updated"
                } else {
                    "spec.toolwindow.tasks.execute.updated"
                }
                setStatusText(SpecCodingBundle.message(statusKey, taskId, result.sessionTitle))
                publishWorkflowChatRefresh(workflowId, taskId, "spec_task_execution_updated")
                reloadCurrentWorkflow()
            },
            onCancelRequested = {
                if (cancelRequested.compareAndSet(false, true)) {
                    cancellationHandleRef.get()?.let { handle ->
                        specTaskExecutionService.cancelExecutionRun(
                            workflowId = handle.workflowId,
                            runId = handle.runId,
                        )
                    }
                    invokeLaterSafe {
                        setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.execution.cancel.requested", taskId))
                    }
                }
            },
            onCancelled = {
                setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.execution.cancelled", taskId))
                publishWorkflowChatRefresh(workflowId, taskId, "spec_task_execution_cancelled")
                reloadCurrentWorkflow()
            },
            onFailure = { error ->
                val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                publishWorkflowChatRefresh(workflowId, taskId, "spec_task_execution_failed")
                reloadCurrentWorkflow()
                Messages.showErrorDialog(
                    project,
                    message,
                    SpecCodingBundle.message(progressKey, taskId),
                )
            },
        )
    }

    private fun resolveReusableWorkflowChatSessionId(workflowId: String): String? {
        val normalizedWorkflowId = workflowId.trim().ifBlank { return null }
        val preferredSessionId = sessionIsolationService.activeSessionId()
        return sessionManager.findReusableWorkflowChatSession(
            workflowId = normalizedWorkflowId,
            preferredSessionId = preferredSessionId,
        )?.id
    }

    private fun openWorkflowChatExecutionSession(sessionId: String, workflowId: String) {
        val normalizedSessionId = sessionId.trim().ifBlank { return }
        val normalizedWorkflowId = workflowId.trim().ifBlank { return }
        invokeLaterSafe {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(ChatToolWindowFactory.TOOL_WINDOW_ID)
                ?: return@invokeLaterSafe
            ChatToolWindowFactory.ensurePrimaryContents(project, toolWindow)
            if (!ChatToolWindowFactory.selectChatContent(toolWindow, project)) {
                return@invokeLaterSafe
            }
            toolWindow.activate(null)
            project.messageBus.syncPublisher(HistorySessionOpenListener.TOPIC)
                .onSessionOpenRequested(normalizedSessionId)
            publishWorkflowChatRefresh(
                workflowId = normalizedWorkflowId,
                reason = "spec_task_execution_session_opened",
            )
        }
    }

    private fun onTaskExecutionCancelRequested(taskId: String) {
        val workflowId = selectedWorkflowId ?: return
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.execution.cancel.progress", taskId),
            task = {
                specTaskExecutionService.cancelExecution(
                    workflowId = workflowId,
                    taskId = taskId,
                )
            },
            onSuccess = {
                setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.execution.cancelled", taskId))
                publishWorkflowChatRefresh(workflowId, taskId, "spec_task_execution_cancelled")
                reloadCurrentWorkflow()
            },
        )
    }

    private fun onTaskWorkflowChatRequested(workflowId: String, taskId: String) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ChatToolWindowFactory.TOOL_WINDOW_ID) ?: return
        val request = WorkflowChatOpenRequest(
            binding = WorkflowChatBinding(
                workflowId = workflowId,
                focusedStage = StageId.IMPLEMENT,
                source = WorkflowChatEntrySource.TASK_PANEL,
                actionIntent = WorkflowChatActionIntent.DISCUSS,
            ),
        )
        ChatToolWindowFactory.ensurePrimaryContents(project, toolWindow)
        if (!ChatToolWindowFactory.selectChatContent(toolWindow, project)) {
            return
        }
        toolWindow.activate(null)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            project.messageBus.syncPublisher(ChatToolWindowControlListener.TOPIC)
                .onOpenWorkflowChatRequested(request)
        }
    }

    private fun onTaskVerificationResultUpdateRequested(taskId: String, verificationResult: TaskVerificationResult?) {
        val workflowId = selectedWorkflowId ?: return
        val existingTask = currentStructuredTasks.firstOrNull { task -> task.id == taskId }
        val auditContext = buildTaskAuditContext(taskId, "UPDATE_VERIFICATION_RESULT")
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.verification.progress"),
            task = {
                when {
                    verificationResult != null -> specTasksService.updateVerificationResult(
                        workflowId = workflowId,
                        taskId = taskId,
                        verificationResult = verificationResult,
                        auditContext = auditContext,
                    )

                    existingTask?.verificationResult != null -> specTasksService.clearVerificationResult(
                        workflowId = workflowId,
                        taskId = taskId,
                        auditContext = auditContext,
                    )
                }
            },
            onSuccess = {
                val statusKey = if (verificationResult == null) {
                    "spec.toolwindow.tasks.verification.cleared"
                } else {
                    "spec.toolwindow.tasks.verification.updated"
                }
                setStatusText(SpecCodingBundle.message(statusKey, taskId))
                publishWorkflowChatRefresh(workflowId, taskId, "spec_task_verification_updated")
                reloadCurrentWorkflow()
            },
        )
    }

    private fun buildTaskAuditContext(taskId: String, action: String): Map<String, String> {
        val task = currentStructuredTasks.firstOrNull { candidate -> candidate.id == taskId }
        val workbenchState = currentWorkbenchState
        val binding = workbenchState?.artifactBinding
        val summary = binding?.previewContent
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull { line -> line.isNotEmpty() && !line.startsWith("#") && !line.startsWith("```") }
            ?.take(180)
            .orEmpty()

        return linkedMapOf<String, String>().apply {
            put("triggerSource", "SPEC_PAGE_TASK_BUTTON")
            put("taskAction", action)
            put("currentStage", currentWorkflow?.currentStage?.name ?: "")
            put("focusedStage", workbenchState?.focusedStage?.name ?: "")
            put("documentBinding", binding?.fileName ?: binding?.title.orEmpty())
            put("documentSummary", summary)
            put("taskLifecycleStatus", task?.status?.name ?: "")
            put("taskDisplayStatus", task?.displayStatus?.name ?: "")
            put("taskExecutionRunId", task?.activeExecutionRun?.runId ?: "")
            put("taskExecutionRunStatus", task?.activeExecutionRun?.status?.name ?: "")
            put("dependsOn", task?.dependsOn?.joinToString(", ").orEmpty())
        }.filterValues { value -> value.isNotBlank() }
    }

    private fun resolveTaskExecutionContext(): TaskExecutionContext? {
        val providerId = providerComboBox.selectedItem as? String
        if (providerId.isNullOrBlank()) {
            setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.execute.providerRequired"))
            return null
        }
        val modelId = (modelComboBox.selectedItem as? ModelInfo)?.id?.trim().orEmpty()
        if (modelId.isBlank()) {
            setStatusText(
                SpecCodingBundle.message(
                    "spec.toolwindow.tasks.execute.modelRequired",
                    providerDisplayName(providerId),
                ),
            )
            return null
        }
        return TaskExecutionContext(
            providerId = providerId,
            modelId = modelId,
            operationMode = modeManager.getCurrentMode(),
        )
    }

    private fun onAdvanceStageRequested() {
        val workflowId = selectedWorkflowId ?: return
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.advance.preview"),
            task = {
                specEngine.previewStageTransition(
                    workflowId = workflowId,
                    transitionType = StageTransitionType.ADVANCE,
                ).getOrThrow()
            },
            onSuccess = { preview ->
                when (preview.gateResult.status) {
                    GateStatus.ERROR -> SpecWorkflowActionSupport.showGateBlocked(project, workflowId, preview.gateResult)
                    GateStatus.WARNING -> {
                        if (!SpecWorkflowActionSupport.confirmWarnings(project, workflowId, preview.gateResult)) {
                            return@runBackground
                        }
                        executeAdvanceStage(workflowId)
                    }

                    GateStatus.PASS -> executeAdvanceStage(workflowId)
                }
            },
        )
    }

    private fun onJumpStageRequested() {
        val workflowMeta = currentWorkflow?.toWorkflowMeta() ?: return
        val targets = SpecWorkflowActionSupport.jumpTargets(workflowMeta)
        if (targets.isEmpty()) {
            SpecWorkflowActionSupport.showInfo(
                project,
                SpecCodingBundle.message("spec.action.jump.none.title"),
                SpecCodingBundle.message("spec.action.jump.none.message"),
            )
            return
        }
        SpecWorkflowActionSupport.chooseStage(
            project = project,
            stages = targets,
            title = SpecCodingBundle.message("spec.action.jump.stage.popup.title"),
            workflowMeta = workflowMeta,
            onChosen = { targetStage -> previewAndJumpToStage(workflowMeta.workflowId, targetStage) },
        )
    }

    private fun onRollbackStageRequested() {
        val workflowMeta = currentWorkflow?.toWorkflowMeta() ?: return
        val targets = SpecWorkflowActionSupport.rollbackTargets(workflowMeta)
        if (targets.isEmpty()) {
            SpecWorkflowActionSupport.showInfo(
                project,
                SpecCodingBundle.message("spec.action.rollback.none.title"),
                SpecCodingBundle.message("spec.action.rollback.none.message"),
            )
            return
        }
        SpecWorkflowActionSupport.chooseStage(
            project = project,
            stages = targets,
            title = SpecCodingBundle.message("spec.action.rollback.stage.popup.title"),
            workflowMeta = workflowMeta,
            onChosen = { targetStage -> executeRollbackStage(workflowMeta.workflowId, targetStage) },
        )
    }

    private fun onTemplateCloneRequested(targetTemplate: WorkflowTemplate) {
        val workflow = currentWorkflow ?: return
        if (workflow.template == targetTemplate) {
            return
        }
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.template.clone.preview"),
            task = {
                specEngine.previewTemplateSwitch(
                    workflowId = workflow.id,
                    toTemplate = targetTemplate,
                ).getOrThrow()
            },
            onSuccess = { preview ->
                val summary = buildTemplateClonePreviewSummary(workflow, preview)
                val hasBlockingImpact = preview.artifactImpacts.any { impact ->
                    impact.strategy == TemplateSwitchArtifactStrategy.BLOCK_SWITCH
                }
                if (hasBlockingImpact) {
                    Messages.showErrorDialog(
                        project,
                        summary,
                        SpecCodingBundle.message("spec.action.template.clone.confirm.title"),
                    )
                    return@runBackground
                }
                val choice = Messages.showDialog(
                    project,
                    summary,
                    SpecCodingBundle.message("spec.action.template.clone.confirm.title"),
                    arrayOf(
                        SpecCodingBundle.message("spec.action.template.clone.confirm.continue"),
                        CommonBundle.getCancelButtonText(),
                    ),
                    0,
                    Messages.getQuestionIcon(),
                )
                if (choice != 0) {
                    return@runBackground
                }
                val cloneDialog = EditSpecWorkflowDialog(
                    initialTitle = suggestedClonedWorkflowTitle(workflow, preview.toTemplate),
                    initialDescription = workflow.description,
                    dialogTitle = SpecCodingBundle.message("spec.action.template.clone.dialog.title"),
                )
                if (!cloneDialog.showAndGet()) {
                    return@runBackground
                }
                val clonedTitle = cloneDialog.resultTitle ?: return@runBackground
                executeTemplateClone(
                    workflowId = workflow.id,
                    previewId = preview.previewId,
                    title = clonedTitle,
                    description = cloneDialog.resultDescription,
                    targetTemplate = preview.toTemplate,
                )
            },
        )
    }

    private fun previewAndJumpToStage(workflowId: String, targetStage: StageId) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.jump.preview"),
            task = {
                specEngine.previewStageTransition(
                    workflowId = workflowId,
                    transitionType = StageTransitionType.JUMP,
                    targetStage = targetStage,
                ).getOrThrow()
            },
            onSuccess = { preview ->
                when (preview.gateResult.status) {
                    GateStatus.ERROR -> SpecWorkflowActionSupport.showGateBlocked(project, workflowId, preview.gateResult)
                    GateStatus.WARNING -> {
                        if (!SpecWorkflowActionSupport.confirmWarnings(project, workflowId, preview.gateResult)) {
                            return@runBackground
                        }
                        executeJumpStage(workflowId, targetStage)
                    }

                    GateStatus.PASS -> executeJumpStage(workflowId, targetStage)
                }
            },
        )
    }

    private fun executeAdvanceStage(workflowId: String) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.advance.executing"),
            task = { specEngine.advanceWorkflow(workflowId) { true }.getOrThrow() },
            onSuccess = { result ->
                handleStageTransitionCompleted(
                    workflowId = workflowId,
                    successMessage = SpecCodingBundle.message(
                        "spec.action.advance.success",
                        SpecWorkflowActionSupport.stageLabel(result.targetStage),
                    ),
                )
            },
        )
    }

    private fun executeJumpStage(workflowId: String, targetStage: StageId) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.jump.executing"),
            task = { specEngine.jumpToStage(workflowId, targetStage) { true }.getOrThrow() },
            onSuccess = { result ->
                handleStageTransitionCompleted(
                    workflowId = workflowId,
                    successMessage = SpecCodingBundle.message(
                        "spec.action.jump.success",
                        SpecWorkflowActionSupport.stageLabel(result.targetStage),
                    ),
                )
            },
        )
    }

    private fun executeRollbackStage(workflowId: String, targetStage: StageId) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.rollback.executing"),
            task = { specEngine.rollbackToStage(workflowId, targetStage).getOrThrow() },
            onSuccess = { meta ->
                handleStageTransitionCompleted(
                    workflowId = workflowId,
                    successMessage = SpecCodingBundle.message(
                        "spec.action.rollback.success",
                        SpecWorkflowActionSupport.stageLabel(meta.currentStage),
                    ),
                )
            },
        )
    }

    private fun executeTemplateClone(
        workflowId: String,
        previewId: String,
        title: String,
        description: String?,
        targetTemplate: WorkflowTemplate,
    ) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.template.clone.executing"),
            task = {
                specEngine.cloneWorkflowWithTemplate(
                    workflowId = workflowId,
                    previewId = previewId,
                    title = title,
                    description = description,
                ).getOrThrow()
            },
            onSuccess = { result ->
                SpecWorkflowActionSupport.notifySuccess(
                    project,
                    SpecCodingBundle.message(
                        "spec.action.template.clone.success",
                        result.workflow.title.ifBlank { result.workflow.id },
                        SpecWorkflowOverviewPresenter.templateLabel(targetTemplate),
                    ),
                )
                highlightedWorkflowId = result.workflow.id
                publishWorkflowSelection(result.workflow.id)
                refreshWorkflows(selectWorkflowId = result.workflow.id)
            },
        )
    }

    private fun handleStageTransitionCompleted(workflowId: String, successMessage: String) {
        SpecWorkflowActionSupport.notifySuccess(project, successMessage)
        focusedStage = null
        publishWorkflowSelection(workflowId)
        refreshWorkflows(selectWorkflowId = workflowId)
    }

    private fun buildTemplateClonePreviewSummary(
        workflow: SpecWorkflow,
        preview: TemplateSwitchPreview,
    ): String {
        val lines = mutableListOf<String>()
        lines += SpecCodingBundle.message("spec.action.template.clone.summary.workflow", workflow.id)
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.templates",
            SpecWorkflowOverviewPresenter.templateLabel(preview.fromTemplate),
            SpecWorkflowOverviewPresenter.templateLabel(preview.toTemplate),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.stage",
            SpecWorkflowActionSupport.stageLabel(preview.currentStage),
            SpecWorkflowActionSupport.stageLabel(preview.resultingStage),
        )
        if (preview.currentStageChanged) {
            lines += SpecCodingBundle.message("spec.action.template.clone.summary.stageChanged")
        }
        lines += ""
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.addedStages",
            formatStageList(preview.addedActiveStages),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.deactivatedStages",
            formatStageList(preview.deactivatedStages),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.gateAddedStages",
            formatStageList(preview.gateAddedStages),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.gateRemovedStages",
            formatStageList(preview.gateRemovedStages),
        )
        lines += ""
        lines += SpecCodingBundle.message("spec.action.template.clone.summary.artifacts")
        preview.artifactImpacts.forEach { impact ->
            lines += SpecCodingBundle.message(
                "spec.action.template.clone.summary.artifact",
                impact.fileName,
                SpecWorkflowActionSupport.stageLabel(impact.stageId),
                templateSwitchStrategyLabel(impact.strategy),
            )
        }
        if (preview.artifactImpacts.any { impact -> impact.strategy == TemplateSwitchArtifactStrategy.BLOCK_SWITCH }) {
            lines += ""
            lines += SpecCodingBundle.message("spec.action.template.clone.summary.blocked")
        }
        lines += ""
        lines += SpecCodingBundle.message("spec.action.template.clone.summary.note")
        return lines.joinToString("\n")
    }

    private fun formatStageList(stages: List<StageId>): String {
        if (stages.isEmpty()) {
            return SpecCodingBundle.message("spec.action.template.clone.summary.none")
        }
        return stages.joinToString(", ") { stage ->
            SpecWorkflowActionSupport.stageLabel(stage)
        }
    }

    private fun templateSwitchStrategyLabel(strategy: TemplateSwitchArtifactStrategy): String {
        return when (strategy) {
            TemplateSwitchArtifactStrategy.REUSE_EXISTING ->
                SpecCodingBundle.message("spec.action.template.clone.strategy.reuse")
            TemplateSwitchArtifactStrategy.GENERATE_SKELETON ->
                SpecCodingBundle.message("spec.action.template.clone.strategy.generate")
            TemplateSwitchArtifactStrategy.BLOCK_SWITCH ->
                SpecCodingBundle.message("spec.action.template.clone.strategy.block")
        }
    }

    private fun suggestedClonedWorkflowTitle(
        workflow: SpecWorkflow,
        targetTemplate: WorkflowTemplate,
    ): String {
        val baseTitle = workflow.title.ifBlank { workflow.id }
        return "$baseTitle (${SpecWorkflowOverviewPresenter.templateLabel(targetTemplate)})"
    }

    private fun onShowCodeGraph() {
        setStatusText(SpecCodingBundle.message("code.graph.status.generating"))
        scope.launch(Dispatchers.Default) {
            val result = codeGraphService.buildFromActiveEditor()
            invokeLaterSafe {
                result.onSuccess { snapshot ->
                    if (snapshot.edges.isEmpty()) {
                        setStatusText(SpecCodingBundle.message("code.graph.status.empty"))
                        return@onSuccess
                    }

                    val summary = CodeGraphRenderer.renderSummary(snapshot)
                    val mermaid = CodeGraphRenderer.renderMermaid(snapshot)
                    CodeGraphDialog(summary = summary, mermaid = mermaid).show()
                    setStatusText(SpecCodingBundle.message(
                        "code.graph.status.generated",
                        snapshot.nodes.size,
                        snapshot.edges.size,
                    ))
                }.onFailure { error ->
                    setStatusText(SpecCodingBundle.message(
                        "code.graph.status.failed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    ))
                }
            }
        }
    }

    private fun onNextPhase() {
        val wfId = selectedWorkflowId ?: return
        scope.launch(Dispatchers.IO) {
            specEngine.proceedToNextPhase(wfId).onSuccess {
                invokeLaterSafe {
                    detailPanel.clearInput()
                    reloadCurrentWorkflow(followCurrentPhase = true)
                }
            }.onFailure { e ->
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message(
                        "spec.workflow.error",
                        e.message ?: SpecCodingBundle.message("common.unknown")
                    ))
                }
            }
        }
    }

    private fun onGoBack() {
        val wfId = selectedWorkflowId ?: return
        scope.launch(Dispatchers.IO) {
            specEngine.goBackToPreviousPhase(wfId).onSuccess {
                invokeLaterSafe {
                    detailPanel.clearInput()
                    reloadCurrentWorkflow(followCurrentPhase = true)
                }
            }.onFailure { e ->
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message(
                        "spec.workflow.error",
                        e.message ?: SpecCodingBundle.message("common.unknown")
                    ))
                }
            }
        }
    }

    private fun subscribeToLocaleEvents() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    invokeLaterSafe {
                        refreshLocalizedTexts()
                    }
                }
            },
        )
    }

    private fun subscribeToGlobalConfigEvents() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            GlobalConfigSyncListener.TOPIC,
            object : GlobalConfigSyncListener {
                override fun onGlobalConfigChanged(event: GlobalConfigChangedEvent) {
                    invokeLaterSafe {
                        syncToolbarSelectionFromSettings()
                    }
                }
            },
        )
    }

    private fun subscribeToToolWindowControlEvents() {
        project.messageBus.connect(this).subscribe(
            SpecToolWindowControlListener.TOPIC,
            object : SpecToolWindowControlListener {
                override fun onCreateWorkflowRequested() {
                    invokeLaterSafe {
                        if (project.isDisposed || _isDisposed) return@invokeLaterSafe
                        onCreateWorkflow()
                    }
                }

                override fun onSelectWorkflowRequested(workflowId: String) {
                    invokeLaterSafe {
                        if (project.isDisposed || _isDisposed) return@invokeLaterSafe
                        openWorkflowFromRequest(SpecToolWindowOpenRequest(workflowId = workflowId))
                    }
                }

                override fun onOpenWorkflowRequested(request: SpecToolWindowOpenRequest) {
                    invokeLaterSafe {
                        if (project.isDisposed || _isDisposed) return@invokeLaterSafe
                        openWorkflowFromRequest(request)
                    }
                }
            },
        )
    }

    private fun subscribeToWorkflowEvents() {
        project.messageBus.connect(this).subscribe(
            SpecWorkflowChangedListener.TOPIC,
            object : SpecWorkflowChangedListener {
                override fun onWorkflowChanged(event: SpecWorkflowChangedEvent) {
                    if (event.reason == SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED) {
                        return
                    }
                    refreshWorkflows(selectWorkflowId = event.workflowId)
                }
            },
        )
    }

    private fun subscribeToDocumentFileEvents() {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val workflowId = selectedWorkflowId ?: return
                    val basePath = project.basePath ?: return
                    if (!containsCurrentWorkflowDocumentChange(events, basePath, workflowId)) {
                        return
                    }
                    scheduleDocumentReload(workflowId)
                }
            },
        )
    }

    private fun scheduleDocumentReload(workflowId: String) {
        pendingDocumentReloadJob?.cancel()
        pendingDocumentReloadJob = scope.launch {
            delay(DOCUMENT_RELOAD_DEBOUNCE_MILLIS)
            if (_isDisposed || project.isDisposed || selectedWorkflowId != workflowId) {
                return@launch
            }
            reloadCurrentWorkflow()
        }
    }

    private fun containsCurrentWorkflowDocumentChange(
        events: List<VFileEvent>,
        basePath: String,
        workflowId: String,
    ): Boolean {
        if (events.isEmpty()) return false
        val normalizedBasePath = basePath
            .replace('\\', '/')
            .trimEnd('/')
            .lowercase(Locale.ROOT)
        val targetPrefix = "$normalizedBasePath/.spec-coding/specs/$workflowId/"
        return events.any { event ->
            val normalizedPath = event.path
                .replace('\\', '/')
                .lowercase(Locale.ROOT)
            if (!normalizedPath.startsWith(targetPrefix)) {
                return@any false
            }
            val fileName = normalizedPath.substringAfterLast('/')
            SPEC_DOCUMENT_FILE_NAMES.contains(fileName)
        }
    }

    private fun refreshLocalizedTexts() {
        workflowSwitcherPopup?.cancel()
        listPanel.refreshLocalizedTexts()
        detailPanel.refreshLocalizedTexts()
        overviewPanel.refreshLocalizedTexts()
        verifyDeltaPanel.refreshLocalizedTexts()
        tasksPanel.refreshLocalizedTexts()
        detailTasksPanel.refreshLocalizedTexts()
        gateDetailsPanel.refreshLocalizedTexts()
        if (::overviewSection.isInitialized) {
            overviewSection.refreshLocalizedTexts()
            tasksSection.refreshLocalizedTexts()
            gateSection.refreshLocalizedTexts()
            verifySection.refreshLocalizedTexts()
            documentsSection.refreshLocalizedTexts()
        }
        if (::documentWorkspaceViewTabsPanel.isInitialized) {
            if (::documentWorkspaceViewLabel.isInitialized) {
                documentWorkspaceViewLabel.text = SpecCodingBundle.message("spec.toolwindow.documents.view.label")
            }
            documentWorkspaceViewButtons.forEach { (view, button) ->
                when (view) {
                    DocumentWorkspaceView.DOCUMENT -> {
                        button.text = SpecCodingBundle.message("spec.toolwindow.documents.view.document")
                        button.toolTipText = SpecCodingBundle.message("spec.toolwindow.documents.view.document.tooltip")
                    }

                    DocumentWorkspaceView.STRUCTURED_TASKS -> {
                        button.text = SpecCodingBundle.message("spec.toolwindow.documents.view.structuredTasks")
                        button.toolTipText = SpecCodingBundle.message("spec.toolwindow.documents.view.structuredTasks.tooltip")
                    }
                }
            }
            updateDocumentWorkspaceViewPresentation(currentWorkbenchState)
        }
        applyToolbarButtonPresentation()
        modelLabel.text = SpecCodingBundle.message("toolwindow.model.label")
        styleToolbarButton(switchWorkflowButton)
        styleToolbarButton(createWorkflowButton)
        styleToolbarButton(refreshButton)
        styleToolbarButton(createWorktreeButton)
        styleToolbarButton(mergeWorktreeButton)
        styleToolbarButton(deltaButton)
        styleToolbarButton(codeGraphButton)
        styleToolbarButton(archiveButton)
        refreshProviderCombo(preserveSelection = true)
        refreshWorkspacePresentation()
        setStatusText(null)
    }

    private fun onComplete() {
        val wfId = selectedWorkflowId ?: return
        scope.launch(Dispatchers.IO) {
            specEngine.completeWorkflow(wfId)
                .onSuccess { workflow ->
                    invokeLaterSafe {
                        reloadCurrentWorkflow()
                        refreshWorkflows()
                        setStatusText(SpecCodingBundle.message("toolwindow.spec.command.completed", workflow.id))
                    }
                }
                .onFailure { error ->
                    val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                    invokeLaterSafe {
                        setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                    }
                }
        }
    }

    private fun onPauseResume() {
        val wfId = selectedWorkflowId ?: return
        val isPaused = currentWorkflow?.status == WorkflowStatus.PAUSED
        scope.launch(Dispatchers.IO) {
            val result = if (isPaused)
                specEngine.resumeWorkflow(wfId)
            else
                specEngine.pauseWorkflow(wfId)
            result.onSuccess {
                invokeLaterSafe {
                    reloadCurrentWorkflow()
                    refreshWorkflows()
                }
            }
        }
    }

    private fun onOpenInEditor(phase: SpecPhase) {
        val wfId = selectedWorkflowId ?: return
        val basePath = project.basePath ?: return
        val filePath = "$basePath/.spec-coding/specs/$wfId/${phase.outputFileName}"
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    private fun onOpenArtifactInEditor(fileName: String) {
        val workflowId = selectedWorkflowId ?: return
        val path = runCatching { artifactService.locateArtifact(workflowId, fileName) }.getOrNull() ?: return
        if (!Files.exists(path) || !SpecWorkflowActionSupport.openFile(project, path)) {
            setStatusText(SpecCodingBundle.message("spec.action.verify.document.unavailable.title"))
        }
    }

    private fun onShowHistoryDiff(phase: SpecPhase) {
        val workflow = currentWorkflow ?: return
        onShowHistoryDiffForWorkflow(
            workflowId = workflow.id,
            phase = phase,
            currentDoc = workflow.documents[phase],
        )
    }

    private fun onSaveDocument(
        phase: SpecPhase,
        content: String,
        onDone: (Result<SpecWorkflow>) -> Unit,
    ) {
        val wfId = selectedWorkflowId
        if (wfId.isNullOrBlank()) {
            onDone(Result.failure(IllegalStateException(SpecCodingBundle.message("spec.detail.noWorkflow"))))
            return
        }
        scope.launch(Dispatchers.IO) {
            val result = specEngine.updateDocumentContent(
                workflowId = wfId,
                phase = phase,
                content = content,
            )
            currentWorkflow = result.getOrNull() ?: currentWorkflow
            invokeLaterSafe {
                result.onSuccess { wf ->
                    phaseIndicator.updatePhase(wf)
                    archiveButton.isEnabled = wf.status == WorkflowStatus.COMPLETED
                    setStatusText(null)
                }.onFailure { error ->
                    val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                    setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                }
                onDone(result)
            }
        }
    }

    private fun onShowHistoryDiffForWorkflow(
        workflowId: String,
        phase: SpecPhase,
        currentDoc: SpecDocument?,
    ) {
        if (currentDoc == null) {
            setStatusText(SpecCodingBundle.message("spec.history.noCurrentDocument"))
            return
        }

        scope.launch(Dispatchers.IO) {
            val history = specEngine.listDocumentHistory(workflowId, phase)
            val snapshots = history.mapNotNull { entry ->
                specEngine.loadDocumentSnapshot(workflowId, phase, entry.snapshotId).getOrNull()?.let { snapshotDoc ->
                    SpecHistoryDiffDialog.SnapshotVersion(
                        snapshotId = entry.snapshotId,
                        createdAt = entry.createdAt,
                        document = snapshotDoc,
                    )
                }
            }

            invokeLaterSafe {
                if (snapshots.isEmpty()) {
                    setStatusText(SpecCodingBundle.message("spec.history.noSnapshot"))
                    return@invokeLaterSafe
                }

                SpecHistoryDiffDialog(
                    phase = phase,
                    currentDocument = currentDoc,
                    snapshots = snapshots,
                    onDeleteSnapshot = { snapshot ->
                        specEngine.deleteDocumentSnapshot(workflowId, phase, snapshot.snapshotId).isSuccess
                    },
                    onPruneSnapshots = { keepLatest ->
                        specEngine.pruneDocumentHistory(workflowId, phase, keepLatest).getOrElse { -1 }
                    },
                    onExportSummary = { content ->
                        exportHistoryDiffSummary(workflowId, phase, content)
                    },
                ).show()

                setStatusText(SpecCodingBundle.message(
                    "spec.history.diff.opened",
                    phase.displayName,
                    snapshots.size,
                ))
            }
        }
    }

    private fun exportHistoryDiffSummary(
        workflowId: String,
        phase: SpecPhase,
        content: String,
    ): Result<String> {
        return runCatching {
            val basePath = project.basePath
                ?: throw IllegalStateException(SpecCodingBundle.message("history.error.projectBasePathUnavailable"))
            val exportDir = Path.of(basePath, ".spec-coding", "exports")
            Files.createDirectories(exportDir)

            val safeWorkflowId = workflowId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val fileName = "spec-history-diff-${safeWorkflowId}-${phase.name.lowercase()}-${System.currentTimeMillis()}.md"
            val target = exportDir.resolve(fileName)
            Files.writeString(target, content, StandardCharsets.UTF_8)
            fileName
        }
    }

    private fun reloadCurrentWorkflow(
        followCurrentPhase: Boolean = false,
        onUpdated: ((SpecWorkflow) -> Unit)? = null,
    ) {
        val wfId = selectedWorkflowId ?: return
        if (followCurrentPhase) {
            focusedStage = null
        }
        invokeLaterSafe {
            showWorkspaceContent()
            overviewPanel.showLoading()
            verifyDeltaPanel.showLoading()
            tasksPanel.showLoading()
            detailTasksPanel.showLoading()
            gateDetailsPanel.showLoading()
        }
        scope.launch(Dispatchers.IO) {
            val wf = specEngine.reloadWorkflow(wfId).getOrNull()
            val uiSnapshot = wf?.let(::buildWorkflowUiSnapshot)
            val tasksResult = runCatching { specTasksService.parse(wfId) }
            val codeContextResult = wf?.let { workflow ->
                runCatching {
                    specCodeContextService.buildCodeContextPack(
                        workflow = workflow,
                        phase = workflow.currentPhase,
                    )
                }
            }
            val liveProgressByTaskId = wf?.let { buildTaskLiveProgressByTaskId(it.id) }.orEmpty()
            currentWorkflow = wf
            invokeLaterSafe {
                if (wf != null) {
                    syncClarificationRetryFromWorkflow(wf)
                    phaseIndicator.updatePhase(wf)
                    val snapshot = uiSnapshot ?: buildWorkflowUiSnapshot(wf)
                    overviewPanel.updateOverview(snapshot.overviewState)
                    verifyDeltaPanel.updateState(snapshot.verifyDeltaState)
                    gateDetailsPanel.updateGateResult(
                        workflowId = wf.id,
                        gateResult = snapshot.gateResult,
                        refreshedAtMillis = snapshot.refreshedAtMillis,
                    )
                    detailPanel.updateWorkflow(wf, followCurrentPhase = followCurrentPhase)
                    applyAutoCodeContextToDetailPanel(wf, codeContextResult)
                    tasksResult.onSuccess { tasks ->
                        val decoratedTasks = decorateTasksWithExecutionState(
                            workflow = wf,
                            tasks = tasks,
                            liveProgressByTaskId = liveProgressByTaskId,
                        )
                        tasksPanel.updateTasks(
                            workflowId = wf.id,
                            tasks = decoratedTasks,
                            liveProgressByTaskId = liveProgressByTaskId,
                            refreshedAtMillis = System.currentTimeMillis(),
                        )
                        detailTasksPanel.updateTasks(
                            workflowId = wf.id,
                            tasks = decoratedTasks,
                            liveProgressByTaskId = liveProgressByTaskId,
                            refreshedAtMillis = System.currentTimeMillis(),
                        )
                        updateWorkspacePresentation(
                            workflow = wf,
                            overviewState = snapshot.overviewState,
                            tasks = decoratedTasks,
                            liveProgressByTaskId = liveProgressByTaskId,
                            verifyDeltaState = snapshot.verifyDeltaState,
                            gateResult = snapshot.gateResult,
                        )
                    }.onFailure { error ->
                        tasksPanel.updateTasks(
                            workflowId = wf.id,
                            tasks = emptyList(),
                            liveProgressByTaskId = emptyMap(),
                            refreshedAtMillis = System.currentTimeMillis(),
                        )
                        detailTasksPanel.updateTasks(
                            workflowId = wf.id,
                            tasks = emptyList(),
                            liveProgressByTaskId = emptyMap(),
                            refreshedAtMillis = System.currentTimeMillis(),
                        )
                        updateWorkspacePresentation(
                            workflow = wf,
                            overviewState = snapshot.overviewState,
                            tasks = emptyList(),
                            liveProgressByTaskId = emptyMap(),
                            verifyDeltaState = snapshot.verifyDeltaState,
                            gateResult = snapshot.gateResult,
                        )
                        val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                        setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                    }
                    applyPendingOpenWorkflowRequestIfNeeded(wf.id)
                    archiveButton.isEnabled = wf.status == WorkflowStatus.COMPLETED
                    onUpdated?.invoke(wf)
                } else {
                    clearOpenedWorkflowUi(resetHighlight = false)
                }
            }
        }
    }

    private enum class WorkspaceChipTone {
        INFO,
        SUCCESS,
        WARNING,
        ERROR,
        MUTED,
    }

    private data class WorkspaceChipColors(
        val foreground: Color,
    )

    private data class WorkspaceSummaryMetric(
        val root: JPanel,
        val titleLabel: JBLabel,
        val valueLabel: JBLabel,
    )

    private data class WorkflowUiSnapshot(
        val overviewState: SpecWorkflowOverviewState,
        val verifyDeltaState: SpecWorkflowVerifyDeltaState,
        val gateResult: GateResult?,
        val refreshedAtMillis: Long,
    )

    private fun buildWorkflowUiSnapshot(workflow: SpecWorkflow): WorkflowUiSnapshot {
        val refreshedAtMillis = System.currentTimeMillis()
        val gatePreview = if (workflow.status == WorkflowStatus.COMPLETED || workflow.currentStage == StageId.ARCHIVE) {
            null
        } else {
            specEngine.previewStageTransition(
                workflowId = workflow.id,
                transitionType = StageTransitionType.ADVANCE,
            ).getOrElse { error ->
                logger.debug("Unable to preview advance gate for workflow ${workflow.id}", error)
                null
            }
        }
        return WorkflowUiSnapshot(
            overviewState = SpecWorkflowOverviewPresenter.buildState(
                workflow = workflow,
                gatePreview = gatePreview,
                refreshedAtMillis = refreshedAtMillis,
            ),
            verifyDeltaState = buildVerifyDeltaState(
                workflow = workflow,
                refreshedAtMillis = refreshedAtMillis,
            ),
            gateResult = gatePreview?.gateResult,
            refreshedAtMillis = refreshedAtMillis,
        )
    }

    private fun buildVerifyDeltaState(
        workflow: SpecWorkflow,
        refreshedAtMillis: Long,
    ): SpecWorkflowVerifyDeltaState {
        val verificationHistory = runCatching {
            specVerificationService.listRunHistory(workflow.id)
        }.getOrElse { error ->
            logger.debug("Unable to load verification history for workflow ${workflow.id}", error)
            emptyList()
        }
        val baselineChoices = buildList {
            workflow.baselineWorkflowId
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != workflow.id }
                ?.let { baselineWorkflowId ->
                    val baselineTitle = specEngine.loadWorkflow(baselineWorkflowId).getOrNull()
                        ?.title
                        ?.ifBlank { baselineWorkflowId }
                        ?: baselineWorkflowId
                    add(
                        SpecWorkflowReferenceBaselineChoice(
                            workflowId = baselineWorkflowId,
                            title = baselineTitle,
                        ),
                    )
                }
            addAll(
                runCatching {
                    specEngine.listDeltaBaselines(workflow.id)
                }.getOrElse { error ->
                    logger.debug("Unable to load delta baselines for workflow ${workflow.id}", error)
                    emptyList()
                }.map(::SpecWorkflowPinnedDeltaBaselineChoice),
            )
        }
        val canPinBaseline = runCatching {
            specEngine.listWorkflowSnapshots(workflow.id).isNotEmpty()
        }.getOrElse { error ->
            logger.debug("Unable to inspect workflow snapshots for ${workflow.id}", error)
            false
        }
        val deltaSummary = baselineChoices.firstOrNull()?.let { preferredChoice ->
            runCatching {
                when (preferredChoice) {
                    is SpecWorkflowReferenceBaselineChoice -> specDeltaService.compareByWorkflowId(
                        baselineWorkflowId = preferredChoice.workflowId,
                        targetWorkflowId = workflow.id,
                    )

                    is SpecWorkflowPinnedDeltaBaselineChoice -> specDeltaService.compareByDeltaBaseline(
                        workflowId = workflow.id,
                        baselineId = preferredChoice.baseline.baselineId,
                    )
                }.getOrThrow()
            }.map(::buildPreferredDeltaSummary).getOrElse { error ->
                logger.debug("Unable to build delta summary for workflow ${workflow.id}", error)
                null
            }
        }
        return SpecWorkflowVerifyDeltaState(
            workflowId = workflow.id,
            verifyEnabled = workflow.verifyEnabled || workflow.stageStates[StageId.VERIFY]?.active == true,
            verificationDocumentAvailable = hasVerificationArtifact(workflow.id),
            verificationHistory = verificationHistory,
            baselineChoices = baselineChoices,
            deltaSummary = deltaSummary,
            preferredBaselineChoiceId = baselineChoices.firstOrNull()?.stableId,
            canPinBaseline = canPinBaseline,
            refreshedAtMillis = refreshedAtMillis,
        )
    }

    private fun hasVerificationArtifact(workflowId: String): Boolean {
        return runCatching {
            Files.exists(artifactService.locateArtifact(workflowId, StageId.VERIFY))
        }.getOrDefault(false)
    }

    private fun buildValidationFailureStatus(validation: ValidationResult): String {
        val firstError = validation.errors.firstOrNull()
        if (firstError.isNullOrBlank()) {
            return SpecCodingBundle.message("spec.workflow.validation.failed.unknown")
        }
        return if (validation.errors.size > 1) {
            SpecCodingBundle.message(
                "spec.workflow.validation.failed.more",
                firstError,
                validation.errors.size - 1,
            )
        } else {
            SpecCodingBundle.message("spec.workflow.validation.failed", firstError)
        }
    }

    private fun compactErrorMessage(error: Throwable?, fallback: String, maxLength: Int = 220): String {
        val compact = generateSequence(error) { it.cause }
            .mapNotNull { throwable ->
                throwable.message
                    ?.replace('\n', ' ')
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
            .firstOrNull { candidate -> isMeaningfulErrorMessage(candidate) }
            ?: fallback
        if (compact.length <= maxLength) {
            return compact
        }
        return compact.take((maxLength - 3).coerceAtLeast(0)).trimEnd() + "..."
    }

    private fun isMeaningfulErrorMessage(message: String): Boolean {
        val normalized = message.trim()
        if (normalized.isBlank()) {
            return false
        }
        if (normalized.lowercase(Locale.ROOT) in PLACEHOLDER_ERROR_MESSAGES) {
            return false
        }
        if (normalized.length <= 3 && PLACEHOLDER_SYMBOLS_REGEX.matches(normalized)) {
            return false
        }
        return ERROR_TEXT_CONTENT_REGEX.containsMatchIn(normalized)
    }

    private fun invokeLaterSafe(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!_isDisposed && !project.isDisposed) action()
        }
    }

    internal fun isListModeForTest(): Boolean {
        return centerContentPanel.componentCount == 1 && centerContentPanel.getComponent(0) === listSectionContainer
    }

    internal fun isDetailModeForTest(): Boolean {
        return centerContentPanel.componentCount == 1 && centerContentPanel.getComponent(0) === workspacePanelContainer
    }

    internal fun selectedWorkflowIdForTest(): String? = selectedWorkflowId

    internal fun highlightedWorkflowIdForTest(): String? = highlightedWorkflowId

    internal fun workflowIdsForTest(): List<String> {
        return listPanel.itemsForTest().map { it.workflowId }
    }

    internal fun openWorkflowForTest(workflowId: String) {
        highlightedWorkflowId = workflowId
        listPanel.setSelectedWorkflow(workflowId)
        onWorkflowOpenedByUser(workflowId)
    }

    internal fun clickBackToListForTest() {
        backToListButton.doClick()
    }

    internal fun clickSwitchWorkflowForTest() {
        switchWorkflowButton.doClick()
    }

    internal fun isSwitchWorkflowPopupVisibleForTest(): Boolean {
        return workflowSwitcherPopup?.isVisibleForTest() == true
    }

    internal fun switchWorkflowPopupVisibleWorkflowIdsForTest(): List<String> {
        return workflowSwitcherPopup?.visibleWorkflowIdsForTest().orEmpty()
    }

    internal fun filterSwitchWorkflowPopupForTest(query: String) {
        workflowSwitcherPopup?.applySearchForTest(query)
    }

    internal fun confirmSwitchWorkflowPopupSelectionForTest() {
        workflowSwitcherPopup?.confirmSelectionForTest()
    }

    internal fun selectedSwitchWorkflowPopupSelectionForTest(): String? {
        return workflowSwitcherPopup?.selectedWorkflowIdForTest()
    }

    internal fun isBackButtonInlineForTest(): Boolean {
        return javax.swing.SwingUtilities.isDescendingFrom(backToListButton, workspaceCardPanel)
    }

    internal fun visibleWorkspaceSectionIdsForTest(): Set<SpecWorkflowWorkspaceSectionId> {
        return workspaceSectionItems
            .filterValues { it.isVisible }
            .keys
            .toCollection(linkedSetOf())
    }

    private fun focusStage(stageId: StageId) {
        if (focusedStage == stageId) {
            return
        }
        if (!detailPanel.allowStageFocusChange(stageId)) {
            return
        }
        focusedStage = stageId
        val workflow = currentWorkflow ?: return
        val overviewState = currentOverviewState ?: buildWorkflowUiSnapshot(workflow).overviewState
        val verifyState = currentVerifyDeltaState ?: buildVerifyDeltaState(
            workflow = workflow,
            refreshedAtMillis = System.currentTimeMillis(),
        )
        updateWorkspacePresentation(
            workflow = workflow,
            overviewState = overviewState,
            tasks = currentStructuredTasks,
            liveProgressByTaskId = currentTaskLiveProgressByTaskId,
            verifyDeltaState = verifyState,
            gateResult = currentGateResult,
        )
    }

    internal fun focusStageForTest(stageId: StageId) {
        focusStage(stageId)
    }

    private fun openWorkflowFromRequest(request: SpecToolWindowOpenRequest) {
        val normalizedWorkflowId = request.workflowId.trim().ifBlank { return }
        pendingOpenWorkflowRequest = request.copy(
            workflowId = normalizedWorkflowId,
            taskId = request.taskId?.trim()?.ifBlank { null },
        )
        if (selectedWorkflowId == normalizedWorkflowId && currentWorkflow?.id == normalizedWorkflowId) {
            applyPendingOpenWorkflowRequestIfNeeded(normalizedWorkflowId)
            if (pendingOpenWorkflowRequest == null) {
                return
            }
        }
        refreshWorkflows(selectWorkflowId = normalizedWorkflowId)
    }

    private fun applyPendingOpenWorkflowRequestIfNeeded(workflowId: String) {
        val request = pendingOpenWorkflowRequest ?: return
        if (request.workflowId != workflowId) {
            return
        }
        request.focusedStage?.let(::focusStage)
        request.taskId?.let { taskId ->
            syncStructuredTaskSelection(taskId)
            if (supportsStructuredTasksDocumentWorkspaceView(currentWorkbenchState)) {
                selectedDocumentWorkspaceView = DocumentWorkspaceView.STRUCTURED_TASKS
                updateDocumentWorkspaceViewPresentation(currentWorkbenchState)
            }
        }
        pendingOpenWorkflowRequest = null
    }

    private fun publishWorkflowChatRefresh(
        workflowId: String,
        taskId: String? = null,
        reason: String,
        focusedStage: StageId? = StageId.IMPLEMENT,
    ) {
        runCatching {
            project.messageBus.syncPublisher(WorkflowChatRefreshListener.TOPIC)
                .onWorkflowChatRefreshRequested(
                    WorkflowChatRefreshEvent(
                        workflowId = workflowId,
                        taskId = taskId,
                        focusedStage = focusedStage,
                        reason = reason,
                    ),
                )
        }.onFailure { error ->
            logger.warn("Failed to publish workflow chat refresh event", error)
        }
    }

    private fun onOverviewStageSelected(stageId: StageId) {
        focusStage(stageId)
    }

    private fun onWorkbenchActionRequested(action: SpecWorkflowWorkbenchAction) {
        when (action.kind) {
            SpecWorkflowWorkbenchActionKind.ADVANCE -> onAdvanceStageRequested()
            SpecWorkflowWorkbenchActionKind.JUMP -> {
                val workflowId = currentWorkflow?.id
                val targetStage = action.targetStage
                if (workflowId != null && targetStage != null) {
                    previewAndJumpToStage(workflowId, targetStage)
                } else {
                    onJumpStageRequested()
                }
            }

            SpecWorkflowWorkbenchActionKind.ROLLBACK -> {
                val workflowId = currentWorkflow?.id
                val targetStage = action.targetStage
                if (workflowId != null && targetStage != null) {
                    executeRollbackStage(workflowId, targetStage)
                } else {
                    onRollbackStageRequested()
                }
            }

            SpecWorkflowWorkbenchActionKind.START_TASK -> {
                val taskId = action.taskId ?: return
                syncStructuredTaskSelection(taskId)
                if (!tasksPanel.requestExecutionForTask(taskId)) {
                    setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.execute.failed", taskId))
                }
            }

            SpecWorkflowWorkbenchActionKind.RESUME_TASK -> {
                val taskId = action.taskId ?: return
                syncStructuredTaskSelection(taskId)
                if (!tasksPanel.requestExecutionForTask(taskId)) {
                    setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.execute.failed", taskId))
                }
            }

            SpecWorkflowWorkbenchActionKind.COMPLETE_TASK -> {
                val taskId = action.taskId ?: return
                syncStructuredTaskSelection(taskId)
                if (!tasksPanel.requestCompletionForTask(taskId)) {
                    setStatusText(SpecCodingBundle.message("spec.toolwindow.tasks.complete.failed", taskId))
                }
            }

            SpecWorkflowWorkbenchActionKind.STOP_TASK_EXECUTION -> {
                val taskId = action.taskId ?: return
                onTaskExecutionCancelRequested(taskId)
            }

            SpecWorkflowWorkbenchActionKind.OPEN_TASK_CHAT -> {
                val workflowId = currentWorkflow?.id ?: return
                val taskId = action.taskId ?: return
                onTaskWorkflowChatRequested(workflowId, taskId)
            }

            SpecWorkflowWorkbenchActionKind.RUN_VERIFY -> {
                val workflowId = currentWorkflow?.id ?: return
                onRunVerificationRequested(workflowId)
            }

            SpecWorkflowWorkbenchActionKind.PREVIEW_VERIFY_PLAN -> {
                val workflowId = currentWorkflow?.id ?: return
                onPreviewVerificationPlanRequested(workflowId)
            }

            SpecWorkflowWorkbenchActionKind.OPEN_VERIFICATION -> {
                val workflowId = currentWorkflow?.id ?: return
                onOpenVerificationRequested(workflowId)
            }

            SpecWorkflowWorkbenchActionKind.SHOW_DELTA -> onShowDelta()

            SpecWorkflowWorkbenchActionKind.COMPLETE_WORKFLOW -> onComplete()

            SpecWorkflowWorkbenchActionKind.ARCHIVE_WORKFLOW -> onArchiveWorkflow()
        }
    }

    private fun onPreviewVerificationPlanRequested(workflowId: String) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.verify.preview"),
            task = {
                val plan = specVerificationService.preview(workflowId)
                val scopeTasks = specTasksService.parse(workflowId).sortedBy(StructuredTask::id)
                SpecWorkflowActionSupport.verificationPlanSummary(plan, scopeTasks)
            },
            onSuccess = { summary ->
                SpecWorkflowActionSupport.showInfo(
                    project = project,
                    title = SpecCodingBundle.message("spec.action.verify.confirm.title"),
                    message = summary,
                )
            },
        )
    }

    private fun buildPreferredDeltaSummary(delta: SpecWorkflowDelta): String {
        return SpecCodingBundle.message(
            "spec.delta.summary",
            delta.baselineWorkflowId,
            delta.targetWorkflowId,
            delta.count(SpecDeltaStatus.ADDED),
            delta.count(SpecDeltaStatus.MODIFIED),
            delta.count(SpecDeltaStatus.REMOVED),
            delta.count(SpecDeltaStatus.UNCHANGED),
        )
    }

    internal fun focusedStageForTest(): StageId? = currentWorkbenchState?.focusedStage

    internal fun currentPrimaryActionKindForTest(): SpecWorkflowWorkbenchActionKind? =
        currentWorkbenchState?.primaryAction?.kind

    internal fun currentOverflowActionKindsForTest(): List<SpecWorkflowWorkbenchActionKind> =
        currentWorkbenchState?.overflowActions?.map { it.kind }.orEmpty()

    internal fun overviewSnapshotForTest(): Map<String, String> = overviewPanel.snapshotForTest()

    internal fun clickOverviewPrimaryActionForTest() {
        overviewPanel.clickPrimaryActionForTest()
    }

    internal fun clickOverviewStageForTest(stageId: StageId) {
        overviewPanel.clickStageForTest(stageId)
    }

    internal fun tasksSnapshotForTest(): Map<String, String> = tasksPanel.snapshotForTest()

    internal fun detailTasksSnapshotForTest(): Map<String, String> = detailTasksPanel.snapshotForTest()

    internal fun documentWorkspaceViewForTest(): String =
        if (::documentWorkspaceViewTabsPanel.isInitialized && documentWorkspaceViewTabsPanel.isVisible) {
            selectedDocumentWorkspaceView.name
        } else {
            DocumentWorkspaceView.DOCUMENT.name
        }

    internal fun isDocumentWorkspaceViewTabsVisibleForTest(): Boolean =
        ::documentWorkspaceViewTabsPanel.isInitialized && documentWorkspaceViewTabsPanel.isVisible

    internal fun clickDocumentWorkspaceViewForTest(view: String) {
        val targetView = runCatching { DocumentWorkspaceView.valueOf(view) }.getOrNull() ?: return
        documentWorkspaceViewButtons[targetView]?.doClick()
    }

    internal fun documentWorkspaceViewButtonsForTest(): List<String> =
        DocumentWorkspaceView.entries.mapNotNull { view ->
            documentWorkspaceViewButtons[view]?.text?.takeIf(String::isNotBlank)?.let { label ->
                "${view.name}:$label"
            }
        }

    internal fun documentWorkspaceViewLabelForTest(): String =
        if (::documentWorkspaceViewLabel.isInitialized) {
            documentWorkspaceViewLabel.text.orEmpty()
        } else {
            ""
        }

    internal fun documentWorkspaceViewSwitcherHeightForTest(): Int =
        if (::documentWorkspaceViewSwitcherPanel.isInitialized) {
            documentWorkspaceViewSwitcherPanel.preferredSize.height
        } else {
            0
        }

    internal fun documentWorkspaceViewButtonHeightsForTest(): Map<String, Int> =
        DocumentWorkspaceView.entries.associate { view ->
            view.name to (documentWorkspaceViewButtons[view]?.preferredSize?.height ?: 0)
        }

    internal fun documentWorkspaceViewButtonWidthsForTest(): Map<String, Int> =
        DocumentWorkspaceView.entries.associate { view ->
            view.name to (documentWorkspaceViewButtons[view]?.preferredSize?.width ?: 0)
        }

    internal fun selectTaskForTest(taskId: String): Boolean {
        syncStructuredTaskSelection(taskId)
        return tasksPanel.selectedTaskId() == taskId
    }

    internal fun requestExecutionForTaskForTest(taskId: String): Boolean = tasksPanel.requestExecutionForTask(taskId)

    internal fun requestExecutionForDetailTaskForTest(taskId: String): Boolean =
        detailTasksPanel.requestExecutionForTask(taskId)

    internal fun clickOpenWorkflowChatForSelectedTaskForTest() {
        tasksPanel.clickOpenWorkflowChatForTest()
    }

    internal fun toolbarSnapshotForTest(): Map<String, String> {
        fun snapshot(button: JButton) = mapOf(
            "text" to button.text.orEmpty(),
            "iconId" to SpecWorkflowIcons.debugId(button.icon),
            "tooltip" to button.toolTipText.orEmpty(),
            "focusable" to button.isFocusable.toString(),
            "accessibleName" to button.accessibleContext.accessibleName.orEmpty(),
            "accessibleDescription" to button.accessibleContext.accessibleDescription.orEmpty(),
            "enabled" to button.isEnabled.toString(),
            "visible" to button.isVisible.toString(),
        )

        return buildMap {
            snapshot(backToListButton).forEach { (key, value) -> put("back.$key", value) }
            snapshot(switchWorkflowButton).forEach { (key, value) -> put("switch.$key", value) }
            snapshot(createWorkflowButton).forEach { (key, value) -> put("create.$key", value) }
            snapshot(refreshButton).forEach { (key, value) -> put("refresh.$key", value) }
            snapshot(deltaButton).forEach { (key, value) -> put("delta.$key", value) }
            snapshot(codeGraphButton).forEach { (key, value) -> put("codeGraph.$key", value) }
            snapshot(archiveButton).forEach { (key, value) -> put("archive.$key", value) }
        }
    }

    internal fun selectedProviderIdForTest(): String? = providerComboBox.selectedItem as? String

    internal fun selectedModelIdForTest(): String? = (modelComboBox.selectedItem as? ModelInfo)?.id

    internal fun selectToolbarModelForTest(providerId: String, modelId: String) {
        providerComboBox.selectedItem = providerId
        val targetModel = (0 until modelComboBox.itemCount)
            .map { index -> modelComboBox.getItemAt(index) }
            .firstOrNull { it.id == modelId }
        if (targetModel != null) {
            modelComboBox.selectedItem = targetModel
        }
    }

    internal fun selectedDocumentPhaseForTest(): String? = detailPanel.selectedPhaseNameForTest()

    internal fun currentDocumentPreviewTextForTest(): String = detailPanel.currentPreviewTextForTest()

    internal fun currentDocumentMetaTextForTest(): String = detailPanel.currentDocumentMetaTextForTest()

    internal fun composerSourceChipLabelsForTest(): List<String> = detailPanel.composerSourceChipLabelsForTest()

    internal fun composerSourceMetaTextForTest(): String = detailPanel.composerSourceMetaTextForTest()

    internal fun composerSourceHintTextForTest(): String = detailPanel.composerSourceHintTextForTest()

    internal fun composerCodeContextSummaryChipLabelsForTest(): List<String> =
        detailPanel.composerCodeContextSummaryChipLabelsForTest()

    internal fun composerCodeContextCandidateLabelsForTest(): List<String> =
        detailPanel.composerCodeContextCandidateLabelsForTest()

    internal fun composerCodeContextMetaTextForTest(): String = detailPanel.composerCodeContextMetaTextForTest()

    internal fun composerCodeContextHintTextForTest(): String = detailPanel.composerCodeContextHintTextForTest()

    internal fun currentStatusTextForTest(): String = statusLabel.text.orEmpty()

    internal fun isComposerSourceRestoreVisibleForTest(): Boolean = detailPanel.isComposerSourceRestoreVisibleForTest()

    internal fun clickAddWorkflowSourcesForTest() {
        detailPanel.clickAddWorkflowSourcesForTest()
    }

    internal fun clickRestoreWorkflowSourcesForTest() {
        detailPanel.clickRestoreWorkflowSourcesForTest()
    }

    internal fun clickRemoveWorkflowSourceForTest(sourceId: String): Boolean {
        return detailPanel.clickRemoveWorkflowSourceForTest(sourceId)
    }

    internal fun isClarifyingForTest(): Boolean = detailPanel.isClarifyingForTest()

    internal fun pendingOpenWorkflowRequestForTest(): SpecToolWindowOpenRequest? = pendingOpenWorkflowRequest

    internal fun deleteWorkflowForTest(workflowId: String) {
        onDeleteWorkflow(workflowId)
    }

    internal fun currentClarificationQuestionsTextForTest(): String = detailPanel.clarificationQuestionsTextForTest()

    internal fun startRequirementsClarifyThenFillForTest(
        workflowId: String,
        missingSections: List<RequirementsSectionId>,
    ): Boolean = startRequirementsClarifyThenFill(workflowId, missingSections)

    internal fun workspaceSummarySnapshotForTest(): Map<String, String> {
        return mapOf(
            "stageTitle" to workspaceStageMetric.titleLabel.text.orEmpty(),
            "stageValue" to workspaceStageMetric.valueLabel.text.orEmpty(),
            "gateTitle" to workspaceGateMetric.titleLabel.text.orEmpty(),
            "gateValue" to workspaceGateMetric.valueLabel.text.orEmpty(),
            "tasksTitle" to workspaceTasksMetric.titleLabel.text.orEmpty(),
            "tasksValue" to workspaceTasksMetric.valueLabel.text.orEmpty(),
            "verifyTitle" to workspaceVerifyMetric.titleLabel.text.orEmpty(),
            "verifyValue" to workspaceVerifyMetric.valueLabel.text.orEmpty(),
            "focusTitle" to workspaceSummaryFocusLabel.text.orEmpty(),
            "focusHint" to workspaceSummaryHintLabel.text.orEmpty(),
        )
    }

    override fun dispose() {
        _isDisposed = true
        pendingDocumentReloadJob?.cancel()
        CliDiscoveryService.getInstance().removeDiscoveryListener(discoveryListener)
        liveProgressEventCoalesceTimer.stop()
        liveProgressRefreshTimer.stop()
        specTaskExecutionService.removeLiveProgressListener(liveProgressListener)
        workflowSwitcherPopup?.cancel()
        cancelActiveGenerationRequest("Spec workflow panel disposed")
        scope.cancel()
    }

    companion object {
        private val TOOLBAR_BG = JBColor(Color(248, 250, 254), Color(58, 64, 74))
        private val TOOLBAR_BORDER = JBColor(Color(212, 222, 239), Color(89, 100, 117))
        private val WORKSPACE_SUMMARY_BG = JBColor(Color(245, 249, 255), Color(56, 62, 72))
        private val WORKSPACE_SUMMARY_BORDER = JBColor(Color(201, 214, 235), Color(86, 96, 110))
        private val WORKSPACE_SUMMARY_TITLE_FG = JBColor(Color(42, 59, 94), Color(214, 223, 236))
        private val WORKSPACE_SUMMARY_META_FG = JBColor(Color(94, 110, 139), Color(160, 171, 188))
        private val WORKSPACE_SUMMARY_LABEL_FG = JBColor(Color(112, 124, 143), Color(172, 182, 196))
        private val WORKSPACE_EMPTY_TITLE_FG = JBColor(Color(57, 72, 104), Color(214, 223, 236))
        private val WORKSPACE_EMPTY_DESCRIPTION_FG = JBColor(Color(101, 117, 145), Color(166, 176, 193))
        private val WORKSPACE_INFO_CHIP_FG = JBColor(Color(48, 74, 112), Color(210, 220, 235))
        private val WORKSPACE_SUCCESS_CHIP_FG = JBColor(Color(42, 118, 71), Color(177, 225, 194))
        private val WORKSPACE_WARNING_CHIP_FG = JBColor(Color(140, 96, 28), Color(239, 210, 146))
        private val WORKSPACE_ERROR_CHIP_FG = JBColor(Color(152, 52, 52), Color(244, 182, 182))
        private val WORKSPACE_MUTED_CHIP_FG = JBColor(Color(98, 109, 126), Color(173, 181, 194))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val STATUS_CHIP_BG = JBColor(Color(236, 244, 255), Color(66, 76, 91))
        private val STATUS_CHIP_BORDER = JBColor(Color(178, 198, 226), Color(99, 116, 140))
        private val STATUS_TEXT_FG = JBColor(Color(52, 72, 106), Color(201, 213, 232))
        private val STATUS_SUCCESS_FG = JBColor(Color(42, 128, 74), Color(131, 208, 157))
        private val PANEL_SECTION_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val PANEL_SECTION_BORDER = JBColor(Color(204, 215, 233), Color(84, 92, 105))
        private val DETAIL_COLUMN_BG = JBColor(Color(244, 249, 255), Color(56, 62, 72))
        private val LIST_SECTION_BG = JBColor(Color(242, 248, 255), Color(59, 66, 77))
        private val LIST_SECTION_BORDER = JBColor(Color(198, 212, 234), Color(89, 100, 117))
        private val PHASE_SECTION_BG = JBColor(Color(240, 246, 255), Color(62, 69, 80))
        private val DETAIL_SECTION_BG = JBColor(Color(249, 252, 255), Color(50, 56, 65))
        private val DETAIL_SECTION_BORDER = JBColor(Color(204, 217, 236), Color(84, 94, 109))
        private val DOCUMENT_WORKSPACE_VIEW_LABEL_FG = JBColor(Color(112, 124, 143), Color(172, 182, 196))
        private val DOCUMENT_WORKSPACE_VIEW_GROUP_BG = JBColor(Color(242, 247, 255), Color(57, 63, 73))
        private val DOCUMENT_WORKSPACE_VIEW_GROUP_BORDER = JBColor(Color(202, 215, 236), Color(89, 100, 116))
        private val DOCUMENT_WORKSPACE_VIEW_SELECTED_BG = JBColor(Color(233, 242, 255), Color(71, 80, 95))
        private val DOCUMENT_WORKSPACE_VIEW_SELECTED_BORDER = JBColor(Color(174, 196, 229), Color(112, 126, 148))
        private val DOCUMENT_WORKSPACE_VIEW_SELECTED_FG = JBColor(Color(43, 67, 105), Color(214, 224, 238))
        private val DOCUMENT_WORKSPACE_VIEW_IDLE_BG = DOCUMENT_WORKSPACE_VIEW_GROUP_BG
        private val DOCUMENT_WORKSPACE_VIEW_IDLE_FG = JBColor(Color(89, 103, 130), Color(177, 188, 203))
        private val DOCUMENT_WORKSPACE_VIEW_HOVER_BG = JBColor(Color(247, 250, 255), Color(63, 71, 82))
        private val DOCUMENT_WORKSPACE_VIEW_HOVER_BORDER = JBColor(Color(198, 213, 237), Color(96, 108, 125))
        private val DOCUMENT_WORKSPACE_VIEW_HOVER_FG = JBColor(Color(71, 89, 122), Color(192, 202, 216))
        private val DOCUMENT_WORKSPACE_VIEW_DISABLED_FG = JBColor(Color(146, 156, 171), Color(124, 132, 145))
        private const val DOCUMENT_WORKSPACE_VIEW_LABEL_FONT_SIZE = 10.5f
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_FONT_SIZE = 10.5f
        private const val DOCUMENT_WORKSPACE_VIEW_ROW_GAP = 6
        private const val DOCUMENT_WORKSPACE_VIEW_SWITCHER_GAP = 2
        private const val DOCUMENT_WORKSPACE_VIEW_GROUP_INSET = 2
        private const val DOCUMENT_WORKSPACE_VIEW_GROUP_ARC = 11
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_ARC = 10
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_HEIGHT = 22
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_MIN_WIDTH = 52
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_HORIZONTAL_PADDING = 10
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_VERTICAL_PADDING = 2
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_EXTRA_WIDTH_PADDING = 16
        private const val LIVE_PROGRESS_EVENT_COALESCE_MILLIS = 180
        private const val WORKSPACE_SECTION_CARD_PADDING = 12
        private val SCROLLABLE_WORKSPACE_SECTION_MAX_HEIGHT = JBUI.scale(320)
        private const val WORKSPACE_SCROLL_UNIT_INCREMENT = 24
        private const val WORKSPACE_SCROLL_BLOCK_INCREMENT = 96
        private const val DOCUMENT_WORKSPACE_CARD_DOCUMENT = "document"
        private const val DOCUMENT_WORKSPACE_CARD_STRUCTURED_TASKS = "structuredTasks"
        private const val DOCUMENT_WORKSPACE_VIEWPORT_HEIGHT = 360
        private val COMPOSER_SOURCE_EDITABLE_STAGES = setOf(
            StageId.REQUIREMENTS,
            StageId.DESIGN,
            StageId.TASKS,
        )
        private const val WORKFLOW_SOURCE_ENTRY_SPEC_COMPOSER = "SPEC_COMPOSER"
        private const val MAX_SOURCE_IMPORT_VALIDATION_LINES = 6
        private val PLACEHOLDER_ERROR_MESSAGES = setOf("-", "--", "...", "null", "none", "unknown")
        private val PLACEHOLDER_SYMBOLS_REGEX = Regex("""^[\p{Punct}\s]+$""")
        private val ERROR_TEXT_CONTENT_REGEX = Regex("""[A-Za-z0-9\p{IsHan}]""")
        private const val DOCUMENT_RELOAD_DEBOUNCE_MILLIS = 300L
        private const val WORKSPACE_CARD_EMPTY = "empty"
        private const val WORKSPACE_CARD_CONTENT = "content"
        private val SPEC_DOCUMENT_FILE_NAMES = (SpecPhase.entries
            .map { it.outputFileName } + listOfNotNull(StageId.VERIFY.artifactFileName))
            .toSet()

        private fun chooseWorkflowSourceFiles(
            project: Project,
            constraints: WorkflowSourceImportConstraints,
        ): List<Path> {
            val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor().apply {
                title = SpecCodingBundle.message("spec.detail.sources.chooser.title")
                description = SpecCodingBundle.message(
                    "spec.detail.sources.chooser.description",
                    WorkflowSourceImportSupport.formatAllowedExtensions(constraints),
                    WorkflowSourceImportSupport.formatFileSize(constraints.maxFileSizeBytes),
                )
            }
            return FileChooser.chooseFiles(descriptor, project, null)
                .map { virtualFile -> Path.of(virtualFile.path) }
        }
    }
}
