package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.ContextConfig
import com.eacape.speccodingplugin.context.ContextCollector
import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.context.ContextTrimmer
import com.eacape.speccodingplugin.context.ContextType
import com.eacape.speccodingplugin.core.Operation
import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.OperationModeManager
import com.eacape.speccodingplugin.core.OperationRequest
import com.eacape.speccodingplugin.core.OperationResult
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.engine.CliSlashCommandInfo
import com.eacape.speccodingplugin.hook.HookEvent
import com.eacape.speccodingplugin.hook.HookManager
import com.eacape.speccodingplugin.hook.HookTriggerContext
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.LlmMessage
import com.eacape.speccodingplugin.llm.LlmRequest
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.llm.LlmRole
import com.eacape.speccodingplugin.llm.MockLlmProvider
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.persistence.ChatPersistenceCoordinator
import com.eacape.speccodingplugin.prompt.PromptInterpolator
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.eacape.speccodingplugin.rollback.WorkspaceChangesetCollector
import com.eacape.speccodingplugin.session.ConversationMessage
import com.eacape.speccodingplugin.session.ConversationRole
import com.eacape.speccodingplugin.session.ConversationSession
import com.eacape.speccodingplugin.session.WorkflowChatActionRouter
import com.eacape.speccodingplugin.session.WorkflowChatActionIntent
import com.eacape.speccodingplugin.session.WorkflowChatBinding
import com.eacape.speccodingplugin.session.WorkflowChatContextAssembler
import com.eacape.speccodingplugin.session.WorkflowChatEntrySource
import com.eacape.speccodingplugin.session.WorkflowChatExecutionContext
import com.eacape.speccodingplugin.session.WorkflowChatExecutionContextResolver
import com.eacape.speccodingplugin.session.WORKFLOW_CHAT_MODE_KEY
import com.eacape.speccodingplugin.session.canonicalizeWorkflowChatModeKey
import com.eacape.speccodingplugin.session.displayWorkflowChatCommand
import com.eacape.speccodingplugin.session.isWorkflowChatCommand
import com.eacape.speccodingplugin.session.normalizedOrNull
import com.eacape.speccodingplugin.session.resolvedWorkflowChatBinding
import com.eacape.speccodingplugin.session.workflowChatCommandArgs
import com.eacape.speccodingplugin.skill.SkillExecutor
import com.eacape.speccodingplugin.skill.SkillContext
import com.eacape.speccodingplugin.spec.DocumentRevisionConflictException
import com.eacape.speccodingplugin.spec.ExecutionLivePhase
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecGenerationProgress
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecTaskDependencyRules
import com.eacape.speccodingplugin.spec.SpecTaskExecutionService
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgressListener
import com.eacape.speccodingplugin.spec.TaskExecutionSessionMetadataCodec
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.attachActiveExecutionRuns
import com.eacape.speccodingplugin.spec.resolveExecutionLaunchRestorePayload
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import com.eacape.speccodingplugin.terminal.IdeTerminalCommandExecutor
import com.eacape.speccodingplugin.ui.chat.ChatSpecSidebarPanel
import com.eacape.speccodingplugin.ui.chat.WorkflowChatExecutionLaunchMessagePanel
import com.eacape.speccodingplugin.ui.chat.ChatMessagePanel
import com.eacape.speccodingplugin.ui.chat.ChatMessagesListPanel
import com.eacape.speccodingplugin.ui.chat.SpecCardMetadata
import com.eacape.speccodingplugin.ui.chat.SpecCardMetadataCodec
import com.eacape.speccodingplugin.ui.chat.SpecCardMessagePanel
import com.eacape.speccodingplugin.ui.chat.SpecCardPanelSnapshot
import com.eacape.speccodingplugin.ui.chat.SpecCardSaveConflictException
import com.eacape.speccodingplugin.ui.chat.TraceEventMetadataCodec
import com.eacape.speccodingplugin.ui.completion.CompletionProvider
import com.eacape.speccodingplugin.ui.history.HistoryPanel
import com.eacape.speccodingplugin.ui.history.HistorySessionOpenListener
import com.eacape.speccodingplugin.ui.input.ContextPreviewPanel
import com.eacape.speccodingplugin.ui.input.ImageAttachmentPreviewPanel
import com.eacape.speccodingplugin.ui.input.SmartInputField
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.eacape.speccodingplugin.ui.spec.EditSpecWorkflowDialog
import com.eacape.speccodingplugin.ui.spec.SpecIconActionPresentation
import com.eacape.speccodingplugin.ui.spec.SpecSimpleMessageDialog
import com.eacape.speccodingplugin.ui.spec.SpecTaskCompletionDialogs
import com.eacape.speccodingplugin.ui.spec.SpecToolWindowOpenRequest
import com.eacape.speccodingplugin.ui.spec.SpecToolWindowControlListener
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowChangedEvent
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowChangedListener
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowIcons
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.eacape.speccodingplugin.ui.actions.SpecWorkflowActionSupport
import com.eacape.speccodingplugin.window.WindowSessionIsolationService
import com.eacape.speccodingplugin.window.WindowRuntimeState
import com.eacape.speccodingplugin.window.WindowStateStore
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Paths
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.JComponent
import javax.swing.ImageIcon
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.DefaultEditorKit

/**
 * 改进版 Chat Tool Window Panel
 *
 * 新增功能：
 * - 会话历史管理
 * - 斜杠命令支持
 * - 更好的错误处理
 * - 协程作用域管理
 */
class ImprovedChatPanel(
    private val project: Project,
) : JBPanel<ImprovedChatPanel>(BorderLayout()), Disposable {

    private val logger = thisLogger()
    private val projectService: SpecCodingProjectService = project.getService(SpecCodingProjectService::class.java)
    private val chatPersistenceCoordinator = ChatPersistenceCoordinator.getInstance(project)
    private val windowStateStore = WindowStateStore.getInstance(project)
    private val sessionIsolationService = WindowSessionIsolationService.getInstance(project)
    private val modeManager = OperationModeManager.getInstance(project)
    private val skillExecutor: SkillExecutor = SkillExecutor.getInstance(project)
    private val specEngine = SpecEngine.getInstance(project)
    private val specTasksService = SpecTasksService.getInstance(project)
    private val specTaskExecutionService = SpecTaskExecutionService.getInstance(project)
    private val workflowChatActionRouter = WorkflowChatActionRouter.getInstance(project)
    private val workflowChatContextAssembler = WorkflowChatContextAssembler.getInstance(project)
    private val workflowChatExecutionContextResolver = WorkflowChatExecutionContextResolver.getInstance(project)
    private val contextCollector by lazy { ContextCollector.getInstance(project) }
    private val completionProvider by lazy { CompletionProvider.getInstance(project) }
    private val operationModeSelector = OperationModeSelector(project)
    private val llmRouter = LlmRouter.getInstance()
    private val modelRegistry = ModelRegistry.getInstance()
    private val taskCoordinator = SwingPanelTaskCoordinator(
        isDisposed = { project.isDisposed || _isDisposed },
    )
    private val workflowCommandPermissionCoordinator = ImprovedChatPanelWorkflowCommandPermissionCoordinator(
        checkOperation = modeManager::checkOperation,
        currentModeDisplayName = { modeManager.getCurrentMode().displayName },
        requestConfirmation = { title, message ->
            Messages.showYesNoDialog(
                project,
                message,
                title,
                Messages.getQuestionIcon(),
            ) == Messages.YES
        },
    )
    private val shellCommandRuntimeFacade = ImprovedChatPanelShellCommandRuntimeFacade.create(
        workingDirectory = project.basePath?.let(::File),
        authorizeCommandExecution = workflowCommandPermissionCoordinator::authorize,
        captureBeforeSnapshot = ::captureWorkspaceSnapshot,
        sanitizeDisplayOutput = { output -> sanitizeDisplayText(output, dropGarbledLines = false) },
        showRunningStatus = { statusMessage ->
            taskCoordinator.invokeLater {
                showStatus(statusMessage)
            }
        },
        executeInIdeTerminal = { command, workingDirectory ->
            IdeTerminalCommandExecutor.execute(
                project = project,
                command = command,
                workingDirectory = workingDirectory,
            )
        },
    )
    private val discoveryListener: () -> Unit = {
        llmRouter.refreshProviders()
        modelRegistry.refreshFromDiscovery()
        taskCoordinator.invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            refreshProviderCombo(preserveSelection = true)
        }
    }

    // UI Components
    private val modelLabel = JBLabel(SpecCodingBundle.message("toolwindow.model.label"))
    private val providerComboBox = ComboBox<String>()
    private val modelComboBox = ComboBox<ModelInfo>()
    private data class SpecWorkflowOption(
        val workflowId: String,
        val title: String,
        val updatedAt: Long,
        val currentStage: StageId,
    )

    private val specWorkflowComboBox = ComboBox<SpecWorkflowOption>()
    private val interactionModeComboBox = ComboBox(ChatInteractionMode.entries.toTypedArray())
    private val statusLabel = JBLabel()
    private val messagesPanel = ChatMessagesListPanel()
    private val conversationHostPanel = JPanel(BorderLayout())
    private val conversationScrollPane by lazy { messagesPanel.getScrollPane() }
    private val specSidebarPanel = ChatSpecSidebarPanel(
        loadWorkflow = { workflowId -> specEngine.loadWorkflow(workflowId) },
        listWorkflows = { specEngine.listWorkflows() },
        onOpenDocument = ::openSpecWorkflowDocument,
        onEditWorkflow = ::editSpecWorkflowMetadata,
    )
    private val chatSplitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
    private val contentSplitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT)
    private val specSidebarToggleButton = JButton()
    private val composerAccessoryStrip = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
    private val contextPreviewPanel = ContextPreviewPanel(
        project = project,
        onStateChanged = ::refreshComposerAccessoryStripVisibility,
    )
    private val imageAttachmentPreviewPanel = ImageAttachmentPreviewPanel(
        onRemove = ::removeTransientClipboardImageIfNeeded,
        onStateChanged = ::refreshComposerPreviewVisibility,
    )
    private val composerHintLabel = JBLabel(SpecCodingBundle.message("toolwindow.composer.hint"))
    private val workflowBindingStrip = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
    private lateinit var composerContainer: JPanel
    private val workflowBindingChip = JButton()
    private val taskBindingChip = JButton()
    private val executeTaskBindingButton = JButton()
    private val retryTaskBindingButton = JButton()
    private val completeTaskBindingButton = JButton()
    private val clearTaskBindingButton = JButton()
    private val taskBindingOverflowButton = JButton()
    private val specModeComboForeground = JBColor(
        Color(0x4A4F59), // #4A4F59
        Color(0xC6CBD3),
    )
    private lateinit var inputField: SmartInputField
    private val compactButton = JButton()
    private val sendButton = JButton()
    private var currentAssistantPanel: ChatMessagePanel? = null
    private var activeTaskExecutionPanel: ActiveTaskExecutionPanel? = null
    private val taskExecutionPanelsByRunId = mutableMapOf<String, ActiveTaskExecutionPanel>()
    private val pendingTaskExecutionLiveProgressLock = Any()
    private val pendingTaskExecutionLiveProgressByRunId = ConcurrentHashMap<String, TaskExecutionLiveProgress>()
    private val taskExecutionLiveProgressDispatchQueued = AtomicBoolean(false)
    private val taskExecutionLiveProgressFlushTimer = Timer(TASK_EXECUTION_UI_BATCH_INTERVAL_MILLIS) {
        flushPendingTaskExecutionLiveProgress()
    }.apply {
        isRepeats = false
    }
    private var latestObservedTaskLiveProgress: TaskExecutionLiveProgress? = null
    @Volatile
    private var latestWorkflowBindingUiSnapshot: WorkflowBindingUiSnapshot? = null
    private var workflowBindingRefreshJob: Job? = null
    private val workflowBindingRefreshGeneration = AtomicLong(0)
    private var refreshSpecWorkflowComboJob: Job? = null
    @Volatile
    private var cachedSpecWorkflowOptions: List<SpecWorkflowOption> = emptyList()
    private var lastWorkflowErrorDialogSnapshot: WorkflowErrorDialogSnapshot? = null
    private var statusAutoHideTimer: Timer? = null
    private var dividerPersistTimer: Timer? = null
    private var composerDividerPersistTimer: Timer? = null
    private var pasteKeyDispatcher: KeyEventDispatcher? = null
    private val sessionCreationMutex = Mutex()
    private val transientClipboardImagePaths = mutableSetOf<String>()
    private var composerInputState = ImprovedChatPanelComposerInputState()
    private val userMessageRawContent = mutableMapOf<ChatMessagePanel, String>()
    private val renderedTaskExecutionSessionMessageIds = linkedSetOf<String>()
    private var composerAutoCollapseScheduled = false
    private var suppressComposerAutoCollapse = false
    private var suppressComposerDividerSync = false
    private var composerDividerReady = false

    // Session state
    private val conversationHistory = mutableListOf<LlmMessage>()
    private var compactedConversationSummary: String? = null
    private var compactedConversationCutoff: Int = 0
    @Volatile
    private var currentSessionId: String? = null
    private var executionState = ImprovedChatPanelExecutionState()
    private var suppressSpecWorkflowSelectionEvents = false
    private var suppressInteractionModeSelectionEvents = false
    @Volatile
    private var activeOperationJob: Job? = null
    @Volatile
    private var activeLlmRequest: ActiveLlmRequest? = null
    private val stopRequested = AtomicBoolean(false)
    private var activeSpecWorkflowId: String? = null
    private var activeWorkflowChatBinding: WorkflowChatBinding? = null
    private var specSidebarVisible = false
    private var specSidebarDividerLocation = SPEC_SIDEBAR_DEFAULT_DIVIDER
    private var chatComposerDividerProportion = CHAT_COMPOSER_DEFAULT_DIVIDER_PROPORTION
    @Volatile
    private var _isDisposed = false

    private val isGenerating: Boolean
        get() = executionState.isGenerating

    private val isFinalizingResponse: Boolean
        get() = executionState.isFinalizingResponse

    private val isRestoringSession: Boolean
        get() = executionState.isRestoringSession

    private data class ActiveLlmRequest(
        val providerId: String,
        val requestId: String,
    )

    private data class TaskExecutionContext(
        val providerId: String,
        val modelId: String,
        val operationMode: OperationMode,
    )

    private data class ActiveTaskExecutionPanel(
        val sessionId: String,
        val workflowId: String,
        val taskId: String,
        val runId: String,
        val panel: ChatMessagePanel,
        val seenEventKeys: MutableSet<String> = linkedSetOf(),
        var finished: Boolean = false,
    )

    private data class BoundTaskBindingState(
        val binding: WorkflowChatBinding,
        val taskId: String,
        val task: StructuredTask?,
        val workflowTasks: List<StructuredTask>,
        val liveProgress: TaskExecutionLiveProgress?,
    )

    private data class WorkflowBindingUiSnapshot(
        val binding: WorkflowChatBinding,
        val executionContext: WorkflowChatExecutionContext,
        val state: BoundTaskBindingState,
    )

    private enum class TaskBindingActionKind {
        EXECUTE,
        RETRY,
        COMPLETE,
        STOP,
    }

    private data class WorkflowErrorDialogSnapshot(
        val title: String,
        val message: String,
    )

    private enum class ChatInteractionMode(
        val key: String,
        val messageKey: String,
    ) {
        VIBE("vibe", "toolwindow.chat.mode.vibe"),
        SPEC(WORKFLOW_CHAT_MODE_KEY, "toolwindow.chat.mode.spec");

        companion object {
            fun fromPersistedKeyOrDefault(value: String?): ChatInteractionMode {
                val normalized = canonicalizeWorkflowChatModeKey(value).orEmpty()
                if (normalized.isBlank()) {
                    return VIBE
                }
                return entries.firstOrNull { mode -> mode.key.equals(normalized, ignoreCase = true) } ?: VIBE
            }
        }
    }

    private var lastInteractionMode: ChatInteractionMode = ChatInteractionMode.VIBE
    private val taskExecutionLiveProgressListener = TaskExecutionLiveProgressListener { progress ->
        handleTaskExecutionLiveProgress(progress)
    }

    init {
        inputField = SmartInputField(
            placeholder = SpecCodingBundle.message("toolwindow.input.placeholder"),
            onSend = { handleSendOrStop() },
            onTrigger = { trigger ->
                val selectedProviderId = providerComboBox.selectedItem as? String
                ApplicationManager.getApplication().executeOnPooledThread {
                    val items = completionProvider.getCompletions(
                        trigger = trigger,
                        selectedProviderId = selectedProviderId,
                    )
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed && !_isDisposed) {
                            inputField.showCompletions(items)
                        }
                    }
                }
            },
            onTriggerDismiss = { inputField.completionPopup.hide() },
            onCompletionSelect = { item ->
                item.contextItem?.let { contextPreviewPanel.addItem(it) }
            },
            onPasteIntercept = {
                if (tryPasteImageAttachmentsFromClipboard()) {
                    true
                } else {
                    tryPasteCollapsedTextMarkerFromClipboard()
                }
            },
        )

        border = JBUI.Borders.empty(12)
        setupUI()
        Disposer.register(this, specSidebarPanel)
        CliDiscoveryService.getInstance().addDiscoveryListener(discoveryListener)
        subscribeToHistoryOpenEvents()
        subscribeToToolWindowControlEvents()
        subscribeToSpecWorkflowEvents()
        subscribeToWorkflowChatRefreshEvents()
        subscribeToLocaleEvents()
        specTaskExecutionService.addLiveProgressListener(taskExecutionLiveProgressListener)
        refreshLocalizedTexts()
        restoreWindowStateIfNeeded()
    }

    private fun restoreWindowStateIfNeeded() {
        val restoredSessionId = sessionIsolationService.restorePersistedSessionId() ?: return
        loadSession(restoredSessionId)
    }

    fun requestNewSessionFromTitleAction() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            if (isRestoringSession) {
                showBusyStatus(autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS)
                return@invokeLater
            }
            if (isGenerating) {
                showBusyStatus(autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS)
                return@invokeLater
            }
            if (currentInteractionMode() != ChatInteractionMode.VIBE) {
                interactionModeComboBox.selectedItem = ChatInteractionMode.VIBE
            }
            startNewSession()
            showStatus(
                text = SpecCodingBundle.message("toolwindow.session.new"),
                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
            )
        }
    }

    fun requestOpenHistoryFromTitleAction() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            if (isRestoringSession) {
                showBusyStatus(autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS)
                return@invokeLater
            }
            if (isGenerating) {
                showBusyStatus(autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS)
                return@invokeLater
            }
            openHistoryPanel()
        }
    }

    private fun subscribeToHistoryOpenEvents() {
        project.messageBus.connect(this).subscribe(
            HistorySessionOpenListener.TOPIC,
            object : HistorySessionOpenListener {
                override fun onSessionOpenRequested(sessionId: String) {
                    loadSession(sessionId)
                }
            },
        )
    }

    private fun subscribeToToolWindowControlEvents() {
        project.messageBus.connect(this).subscribe(
            ChatToolWindowControlListener.TOPIC,
            object : ChatToolWindowControlListener {
                override fun onNewSessionRequested() {
                    requestNewSessionFromTitleAction()
                }

                override fun onOpenHistoryRequested() {
                    requestOpenHistoryFromTitleAction()
                }

                override fun onOpenWorkflowChatRequested(request: WorkflowChatOpenRequest) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        openWorkflowChatBinding(request)
                    }
                }
            },
        )
    }

    private fun subscribeToSpecWorkflowEvents() {
        project.messageBus.connect(this).subscribe(
            SpecWorkflowChangedListener.TOPIC,
            object : SpecWorkflowChangedListener {
                override fun onWorkflowChanged(event: SpecWorkflowChangedEvent) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) return@invokeLater
                        val workflowId = event.workflowId?.trim()?.ifBlank { null }
                        val previousSource = resolvedActiveWorkflowChatBinding()?.source ?: WorkflowChatEntrySource.SPEC_PAGE
                        val isWorkflowSelected =
                            event.reason == SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED &&
                                currentInteractionMode() == ChatInteractionMode.SPEC &&
                                workflowId != null
                        if (isWorkflowSelected) {
                            startNewSession()
                            setExplicitWorkflowChatBinding(
                                buildWorkflowChatBinding(
                                    workflowId = workflowId,
                                    source = previousSource,
                                ),
                            )
                            if (specSidebarVisible) {
                                specSidebarPanel.focusWorkflow(workflowId)
                            }
                        } else {
                            workflowId?.let { activeSpecWorkflowId = it }
                            if (specSidebarVisible) {
                                if (workflowId != null && specSidebarPanel.hasFocusedWorkflow(workflowId)) {
                                    specSidebarPanel.focusWorkflow(workflowId)
                                } else {
                                    specSidebarPanel.refreshCurrentWorkflow()
                                }
                            }
                        }

                        refreshSpecWorkflowComboBox(selectWorkflowId = workflowId)
                        updateWorkflowBindingUi()
                    }
                }
            },
        )
    }

    private fun subscribeToWorkflowChatRefreshEvents() {
        project.messageBus.connect(this).subscribe(
            WorkflowChatRefreshListener.TOPIC,
            object : WorkflowChatRefreshListener {
                override fun onWorkflowChatRefreshRequested(event: WorkflowChatRefreshEvent) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) return@invokeLater
                        val binding = resolvedActiveWorkflowChatBinding() ?: return@invokeLater
                        if (binding.workflowId != event.workflowId.trim()) {
                            return@invokeLater
                        }
                        activeSpecWorkflowId = event.workflowId.trim()
                        refreshSpecWorkflowComboBox(selectWorkflowId = event.workflowId)
                        if (specSidebarVisible && specSidebarPanel.hasFocusedWorkflow(event.workflowId)) {
                            specSidebarPanel.refreshCurrentWorkflow()
                        }
                        updateWorkflowBindingUi()
                    }
                }
            },
        )
    }

    private fun subscribeToLocaleEvents() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) return@invokeLater
                        refreshLocalizedTexts()
                    }
                }
            },
        )
    }

    private fun refreshLocalizedTexts() {
        modelLabel.text = SpecCodingBundle.message("toolwindow.model.label")
        composerHintLabel.text = SpecCodingBundle.message("toolwindow.composer.hint")
        composerHintLabel.toolTipText = composerHintLabel.text
        inputField.setPlaceholderText(SpecCodingBundle.message("toolwindow.input.placeholder"))
        inputField.toolTipText = composerHintLabel.text
        operationModeSelector.refreshLocalizedTexts()
        refreshActionButtonTexts()
        updateSpecSidebarToggleButtonTexts()
        specSidebarPanel.refreshLocalizedTexts()
        interactionModeComboBox.toolTipText = SpecCodingBundle.message("toolwindow.chat.mode.tooltip")
        providerComboBox.repaint()
        modelComboBox.repaint()
        interactionModeComboBox.repaint()
        updateProviderComboSize()
        updateModelComboSize()
        updateInteractionModeComboSize()
        refreshHistoryContentTitle()
        updateComboTooltips()
        updateWorkflowBindingUi()
        if (isGenerating || isRestoringSession) {
            showBusyStatus()
        }
    }

    private fun setupUI() {
        val shellBorderColor = JBColor(
            Color(218, 224, 233),
            Color(78, 84, 93),
        )
        val composerSurfaceColor = JBColor(
            Color(252, 252, 253),
            Color(43, 45, 49),
        )

        // Model and interaction setup
        providerComboBox.renderer = createCompactComboRenderer { value ->
            providerDisplayName(value as? String)
        }
        modelComboBox.renderer = createCompactComboRenderer { value ->
            toUiLowercase((value as? ModelInfo)?.name ?: "")
        }
        providerComboBox.addActionListener {
            updateProviderComboSize()
            refreshModelCombo()
            updateComboTooltips()
        }
        modelComboBox.addActionListener {
            updateModelComboSize()
            updateComboTooltips()
        }
        refreshProviderCombo(preserveSelection = false)

        specWorkflowComboBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val label = component as? JLabel ?: return component
                val option = value as? SpecWorkflowOption
                val title = option?.title?.trim().orEmpty()
                label.font = specWorkflowComboBox.font
                label.text = if (index == -1) {
                    if (ComboBoxAutoWidthSupport.isIntrinsicMeasurementInProgress(specWorkflowComboBox)) {
                        title
                    } else {
                        val maxTextWidth =
                            (specWorkflowComboBox.preferredSize?.width ?: 0) - JBUI.scale(SPEC_WORKFLOW_COMBO_TEXT_OVERHEAD_PX)
                        ellipsizeToWidth(title, maxWidth = maxTextWidth, metrics = label.getFontMetrics(label.font))
                    }
                } else {
                    title
                }
                if (!isSelected) {
                    label.foreground = specModeComboForeground
                }
                return label
            }
        }
        specWorkflowComboBox.addActionListener {
            if (suppressSpecWorkflowSelectionEvents) return@addActionListener
            if (currentInteractionMode() != ChatInteractionMode.SPEC) return@addActionListener
            val option = specWorkflowComboBox.selectedItem as? SpecWorkflowOption ?: return@addActionListener
            switchActiveSpecWorkflowFromUser(option.workflowId)
            updateSpecWorkflowComboSize()
            updateComboTooltips()
        }
        specWorkflowComboBox.isVisible = false

        interactionModeComboBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val label = component as? JLabel ?: return component
                val mode = value as? ChatInteractionMode
                label.font = interactionModeComboBox.font
                label.text = if (mode == null) "" else SpecCodingBundle.message(mode.messageKey)
                if (!isSelected) {
                    label.foreground = specModeComboForeground
                }
                return label
            }
        }
        val restoredInteractionMode = ChatInteractionMode.fromPersistedKeyOrDefault(
            windowStateStore.snapshot().chatInteractionMode,
        )
        interactionModeComboBox.selectedItem = restoredInteractionMode
        lastInteractionMode = restoredInteractionMode
        windowStateStore.updateChatInteractionMode(restoredInteractionMode.key)
        interactionModeComboBox.addActionListener {
            updateInteractionModeComboSize()
            onInteractionModeChanged()
        }
        interactionModeComboBox.toolTipText = SpecCodingBundle.message("toolwindow.chat.mode.tooltip")
        configureCompactCombo(interactionModeComboBox, INTERACTION_MODE_COMBO_MIN_WIDTH)
        configureCompactCombo(specWorkflowComboBox, SPEC_WORKFLOW_COMBO_MIN_WIDTH)
        configureCompactCombo(providerComboBox, PROVIDER_COMBO_MIN_WIDTH)
        configureCompactCombo(modelComboBox, MODEL_COMBO_MIN_WIDTH)
        val italicSmallFont = JBUI.Fonts.smallFont().deriveFont(Font.ITALIC)
        specWorkflowComboBox.font = italicSmallFont
        interactionModeComboBox.font = italicSmallFont
        installCompactComboAutoWidth(
            comboBox = interactionModeComboBox,
            minWidth = INTERACTION_MODE_COMBO_MIN_WIDTH,
            maxWidth = INTERACTION_MODE_COMBO_MAX_WIDTH,
        )
        installCompactComboAutoWidth(
            comboBox = specWorkflowComboBox,
            minWidth = SPEC_WORKFLOW_COMBO_MIN_WIDTH,
            maxWidth = SPEC_WORKFLOW_COMBO_MAX_WIDTH,
        )
        installCompactComboAutoWidth(
            comboBox = providerComboBox,
            minWidth = PROVIDER_COMBO_MIN_WIDTH,
            maxWidth = PROVIDER_COMBO_MAX_WIDTH,
        )
        installCompactComboAutoWidth(
            comboBox = modelComboBox,
            minWidth = MODEL_COMBO_MIN_WIDTH,
            maxWidth = MODEL_COMBO_MAX_WIDTH,
        )
        modelLabel.font = JBUI.Fonts.smallFont()
        modelLabel.foreground = JBColor.GRAY
        composerHintLabel.foreground = JBColor.GRAY
        composerHintLabel.font = JBUI.Fonts.smallFont()
        composerHintLabel.toolTipText = composerHintLabel.text
        statusLabel.foreground = JBColor.GRAY
        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.isVisible = false
        configureWorkflowBindingStrip()
        configureActionButtons()
        configureCompactButton()
        configureClipboardImagePaste()
        installGlobalPasteKeyDispatcher()
        installComposerAutoCollapseListener()
        logPasteDiagnostic("paste diagnostics active; panel=${this::class.java.simpleName}")
        updateProviderComboSize()
        updateModelComboSize()
        updateInteractionModeComboSize()
        updateComboTooltips()

        sendButton.addActionListener { handleSendOrStop() }
        compactButton.addActionListener { handleCompactContextAction() }

        // Conversation area fills the top
        conversationHostPanel.isOpaque = true
        conversationScrollPane.border = JBUI.Borders.empty()
        messagesPanel.isOpaque = true
        messagesPanel.background = JBColor(
            java.awt.Color(247, 248, 250),
            java.awt.Color(34, 36, 39),
        )
        conversationHostPanel.background = messagesPanel.background
        conversationHostPanel.border = JBUI.Borders.customLine(shellBorderColor, 1, 1, 0, 1)
        conversationScrollPane.viewport.isOpaque = true
        conversationScrollPane.viewport.background = messagesPanel.background

        chatSplitPane.leftComponent = conversationScrollPane
        chatSplitPane.rightComponent = specSidebarPanel
        specSidebarPanel.minimumSize = Dimension(JBUI.scale(SPEC_SIDEBAR_MIN_WIDTH), 0)
        configureSplitPaneDivider(chatSplitPane, SPEC_SIDEBAR_DIVIDER_SIZE)
        chatSplitPane.resizeWeight = 0.74
        chatSplitPane.isContinuousLayout = true
        chatSplitPane.isOpaque = false
        chatSplitPane.border = JBUI.Borders.empty()
        chatSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) { _ ->
            val location = chatSplitPane.dividerLocation
            if (location > 0) {
                specSidebarDividerLocation = location
                scheduleDividerPersist()
            }
        }
        configureSpecSidebarToggleButton()
        val restoredWindowState = windowStateStore.snapshot()
        specSidebarDividerLocation = restoredWindowState.chatSpecSidebarDividerLocation
            .takeIf { it > 0 }
            ?: SPEC_SIDEBAR_DEFAULT_DIVIDER
        chatComposerDividerProportion = resolveInitialComposerDividerProportion(restoredWindowState)
        if (chatComposerDividerProportion != restoredWindowState.chatComposerDividerProportion &&
            restoredWindowState.chatComposerDividerProportion > 0f
        ) {
            windowStateStore.updateChatComposerDividerProportion(chatComposerDividerProportion)
        }
        applySpecSidebarVisibility(restoredWindowState.chatSpecSidebarVisible, persist = false)
        applyInteractionModeUi(currentInteractionMode())
        if (currentInteractionMode() == ChatInteractionMode.SPEC) {
            refreshSpecWorkflowComboBox(selectWorkflowId = resolveMostRecentSpecWorkflowIdOrNull())
        }

        // Composer area
        val inputScroll = JScrollPane(inputField)
        inputScroll.border = JBUI.Borders.empty()
        inputScroll.preferredSize = JBDimension(0, CHAT_COMPOSER_INPUT_PREFERRED_HEIGHT)
        inputScroll.minimumSize = JBDimension(0, CHAT_COMPOSER_INPUT_MIN_HEIGHT)

        val composerMetaRow = JPanel(BorderLayout(JBUI.scale(8), 0))
        composerMetaRow.isOpaque = false
        composerMetaRow.border = JBUI.Borders.emptyTop(5)
        val composerMetaActions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        composerMetaActions.isOpaque = false
        composerMetaActions.add(specWorkflowComboBox)
        composerMetaActions.add(interactionModeComboBox)
        composerMetaRow.add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(composerHintLabel, BorderLayout.WEST)
            },
            BorderLayout.CENTER,
        )
        composerMetaRow.add(composerMetaActions, BorderLayout.EAST)

        val controlsLeftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        controlsLeftPanel.isOpaque = false
        controlsLeftPanel.add(providerComboBox)
        controlsLeftPanel.add(modelLabel)
        controlsLeftPanel.add(modelComboBox)
        controlsLeftPanel.add(operationModeSelector)

        val controlsRightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 2))
        controlsRightPanel.isOpaque = false
        controlsRightPanel.add(statusLabel)
        controlsRightPanel.add(specSidebarToggleButton)
        controlsRightPanel.add(compactButton)
        controlsRightPanel.add(sendButton)

        val controlsRow = JPanel(BorderLayout())
        controlsRow.isOpaque = false
        controlsRow.border = JBUI.Borders.empty(7, COMPOSER_CONTROLS_LEFT_INSET, 0, 0)
        controlsRow.add(controlsLeftPanel, BorderLayout.CENTER)
        controlsRow.add(controlsRightPanel, BorderLayout.EAST)

        composerContainer = JPanel(BorderLayout())
        composerContainer.isOpaque = true
        composerContainer.background = composerSurfaceColor
        composerContainer.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(
                shellBorderColor,
                1,
                1,
                1,
                1,
            ),
            JBUI.Borders.empty(8, 10),
        )
        composerContainer.add(imageAttachmentPreviewPanel, BorderLayout.NORTH)
        composerContainer.add(inputScroll, BorderLayout.CENTER)
        composerContainer.add(composerMetaRow, BorderLayout.SOUTH)
        composerAccessoryStrip.isOpaque = false
        composerAccessoryStrip.border = JBUI.Borders.emptyBottom(2)
        composerAccessoryStrip.isVisible = false
        composerAccessoryStrip.add(workflowBindingStrip)
        composerAccessoryStrip.add(contextPreviewPanel)

        val bottomPanel = JPanel(BorderLayout(0, JBUI.scale(8)))
        bottomPanel.isOpaque = true
        bottomPanel.background = composerSurfaceColor
        bottomPanel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(shellBorderColor, 1, 1, 1, 1),
            JBUI.Borders.empty(8, 10, 8, 10),
        )
        bottomPanel.minimumSize = Dimension(0, JBUI.scale(CHAT_COMPOSER_MIN_HEIGHT))
        bottomPanel.add(composerAccessoryStrip, BorderLayout.NORTH)
        bottomPanel.add(composerContainer, BorderLayout.CENTER)
        bottomPanel.add(controlsRow, BorderLayout.SOUTH)

        configureContentSplitPane(bottomPanel)
        add(contentSplitPane, BorderLayout.CENTER)
    }

    private fun configureWorkflowBindingStrip() {
        workflowBindingStrip.isOpaque = false
        workflowBindingStrip.border = JBUI.Borders.empty()
        workflowBindingStrip.isVisible = false
        configureWorkflowBindingChip(taskBindingChip, clearAction = false)
        taskBindingChip.addActionListener { handleTaskBindingChipClicked() }
        taskBindingChip.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) = maybeShowTaskBindingOverflowMenu(event)

                override fun mouseReleased(event: MouseEvent) = maybeShowTaskBindingOverflowMenu(event)
            },
        )
        workflowBindingStrip.add(taskBindingChip)
        updateWorkflowBindingUi()
    }

    private fun maybeShowTaskBindingOverflowMenu(event: MouseEvent) {
        if (!event.isPopupTrigger) {
            return
        }
        showTaskBindingOverflowMenu(
            anchor = event.component as? JComponent ?: taskBindingChip,
            x = event.x,
            y = event.y,
        )
        event.consume()
    }

    private fun configureWorkflowBindingChip(button: JButton, clearAction: Boolean) {
        button.icon = null
        button.isFocusable = true
        button.isFocusPainted = false
        button.isBorderPainted = false
        button.isContentAreaFilled = false
        button.isOpaque = false
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.horizontalAlignment = JButton.LEFT
        button.iconTextGap = 0
        button.margin = if (clearAction) JBUI.insets(0) else JBUI.insets(0)
        button.font = JBUI.Fonts.smallFont()
        button.foreground = JBColor(Color(0x3F4A59), Color(0xC7D0DB))
        button.border = JBUI.Borders.empty()
    }

    private fun configureWorkflowBindingActionButton(button: JButton, icon: Icon) {
        SpecUiStyle.styleIconActionButton(button, size = 24, arc = 10)
        SpecUiStyle.configureIconActionButton(
            button = button,
            icon = icon,
            tooltip = "",
            accessibleName = "",
        )
        button.isVisible = false
    }

    private fun configureWorkflowBindingOverflowButton() {
        taskBindingOverflowButton.text = "..."
        taskBindingOverflowButton.font = JBUI.Fonts.smallFont()
        taskBindingOverflowButton.isFocusable = false
        taskBindingOverflowButton.isFocusPainted = false
        taskBindingOverflowButton.isBorderPainted = false
        taskBindingOverflowButton.isContentAreaFilled = true
        taskBindingOverflowButton.isOpaque = true
        taskBindingOverflowButton.background = JBColor(Color(0xEFF3F8), Color(0x343B45))
        taskBindingOverflowButton.foreground = JBColor(Color(0x52606F), Color(0xC8D1DB))
        taskBindingOverflowButton.margin = JBUI.insets(2, 7, 2, 7)
        SpecUiStyle.applyRoundRect(taskBindingOverflowButton, arc = 10)
        taskBindingOverflowButton.isVisible = false
    }

    private fun refreshComposerAccessoryStripVisibility() {
        composerAccessoryStrip.isVisible =
            workflowBindingStrip.isVisible || contextPreviewPanel.isVisible
        composerAccessoryStrip.revalidate()
        composerAccessoryStrip.repaint()
    }

    private fun refreshComposerPreviewVisibility() {
        refreshComposerAccessoryStripVisibility()
        if (this::composerContainer.isInitialized) {
            composerContainer.revalidate()
            composerContainer.repaint()
        }
    }

    private fun handleTaskExecutionLiveProgress(progress: TaskExecutionLiveProgress) {
        synchronized(pendingTaskExecutionLiveProgressLock) {
            pendingTaskExecutionLiveProgressByRunId.compute(progress.runId) { _, current ->
                if (shouldReplacePendingTaskExecutionLiveProgress(current, progress)) progress else current
            }
        }
        requestTaskExecutionLiveProgressFlush(
            immediate = progress.phase == ExecutionLivePhase.WAITING_CONFIRMATION ||
                progress.phase == ExecutionLivePhase.TERMINAL,
        )
    }

    private fun requestTaskExecutionLiveProgressFlush(immediate: Boolean = false) {
        if (!taskExecutionLiveProgressDispatchQueued.compareAndSet(false, true) && !immediate) {
            return
        }
        ApplicationManager.getApplication().invokeLater {
            taskExecutionLiveProgressDispatchQueued.set(false)
            if (project.isDisposed || _isDisposed || !hasPendingTaskExecutionLiveProgress()) {
                return@invokeLater
            }
            if (immediate) {
                taskExecutionLiveProgressFlushTimer.stop()
                flushPendingTaskExecutionLiveProgress()
            } else if (taskExecutionLiveProgressFlushTimer.isRunning) {
                taskExecutionLiveProgressFlushTimer.restart()
            } else {
                taskExecutionLiveProgressFlushTimer.start()
            }
        }
    }

    private fun flushPendingTaskExecutionLiveProgress() {
        val pending = synchronized(pendingTaskExecutionLiveProgressLock) {
            orderedTaskExecutionLiveProgressBatch(pendingTaskExecutionLiveProgressByRunId.values)
                .also { pendingTaskExecutionLiveProgressByRunId.clear() }
        }
        if (pending.isEmpty()) {
            return
        }
        var workflowBindingDirty = false
        pending.forEach { progress ->
            if (!isRelevantTaskExecutionLiveProgress(progress)) {
                return@forEach
            }
            rememberObservedTaskLiveProgress(progress)
            syncActiveTaskExecutionPanel(progress)
            workflowBindingDirty = true
        }
        if (workflowBindingDirty) {
            updateWorkflowBindingUi()
        }
    }

    private fun hasPendingTaskExecutionLiveProgress(): Boolean {
        return synchronized(pendingTaskExecutionLiveProgressLock) {
            pendingTaskExecutionLiveProgressByRunId.isNotEmpty()
        }
    }

    private fun rememberObservedTaskLiveProgress(progress: TaskExecutionLiveProgress) {
        val current = latestObservedTaskLiveProgress
        if (progress.phase == ExecutionLivePhase.TERMINAL) {
            if (current?.runId == progress.runId ||
                (current?.workflowId == progress.workflowId && current.taskId == progress.taskId)
            ) {
                latestObservedTaskLiveProgress = null
            }
            return
        }
        latestObservedTaskLiveProgress = progress
    }

    private fun isRelevantTaskExecutionLiveProgress(progress: TaskExecutionLiveProgress): Boolean {
        val activePanel = activeTaskExecutionPanel
        if (activePanel != null &&
            activePanel.sessionId == currentSessionId &&
            activePanel.runId == progress.runId
        ) {
            return true
        }
        if (currentInteractionMode() != ChatInteractionMode.SPEC) {
            return false
        }
        val binding = resolvedActiveWorkflowChatBinding() ?: return false
        if (binding.workflowId != progress.workflowId) {
            return false
        }
        val currentExecutionContext = resolvedCurrentWorkflowChatExecutionContext(binding, cachedOnly = true)
        return currentExecutionContext == null ||
            currentExecutionContext.runId == progress.runId ||
            currentExecutionContext.taskId == progress.taskId
    }

    private fun syncActiveTaskExecutionPanel(progress: TaskExecutionLiveProgress) {
        val sessionId = currentSessionId?.trim()?.ifBlank { null } ?: return
        val tracked = taskExecutionPanelsByRunId[progress.runId]
            ?.takeIf { state -> state.sessionId == sessionId }
        val state = when {
            tracked != null -> {
                activeTaskExecutionPanel = tracked
                tracked
            }

            activeTaskExecutionPanel == null -> {
                val binding = resolvedActiveWorkflowChatBinding() ?: return
                if (binding.workflowId != progress.workflowId) {
                    return
                }
                val currentExecutionContext = resolvedCurrentWorkflowChatExecutionContext(binding, cachedOnly = true)
                if (currentExecutionContext != null &&
                    currentExecutionContext.runId != progress.runId &&
                    currentExecutionContext.taskId != progress.taskId
                ) {
                    return
                }
                createActiveTaskExecutionPanel(sessionId, progress)
            }

            else -> {
                val existing = activeTaskExecutionPanel ?: return
                when {
                    existing.sessionId == sessionId && existing.runId == progress.runId -> existing
                    resolvedActiveWorkflowChatBinding()?.workflowId == progress.workflowId -> {
                        retireSupersededTaskExecutionPanel(existing, progress.runId)
                        createActiveTaskExecutionPanel(sessionId, progress)
                    }

                    else -> return
                }
            }
        }
        val wasNearBottom = messagesPanel.isNearBottom()
        val newEvents = collectNewTaskExecutionTraceEvents(state, progress)
        if (newEvents.isNotEmpty()) {
            state.panel.appendStreamContent(text = "", events = newEvents)
        }
        if (!state.finished &&
            (progress.phase == ExecutionLivePhase.WAITING_CONFIRMATION || progress.phase == ExecutionLivePhase.TERMINAL)
        ) {
            state.panel.finishMessage()
            state.finished = true
            if (activeTaskExecutionPanel?.runId == state.runId) {
                activeTaskExecutionPanel = null
            }
        }
        if (newEvents.isNotEmpty() && wasNearBottom) {
            messagesPanel.scrollToBottom()
        }
    }

    private fun createActiveTaskExecutionPanel(
        sessionId: String,
        progress: TaskExecutionLiveProgress,
    ): ActiveTaskExecutionPanel {
        val mode = currentInteractionMode()
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            onWorkflowFileOpen = ::handleWorkflowFileOpen,
            onWorkflowCommandExecute = ::handleWorkflowCommandExecute,
            workflowSectionsEnabled = workflowSectionRenderingEnabledFor(mode),
            timelineExpandButtonsVisible = timelineExpandButtonsVisibleFor(mode),
            startedAtMillis = progress.startedAt.toEpochMilli(),
            captureElapsedAutomatically = false,
        )
        messagesPanel.addMessage(panel)
        return ActiveTaskExecutionPanel(
            sessionId = sessionId,
            workflowId = progress.workflowId,
            taskId = progress.taskId,
            runId = progress.runId,
            panel = panel,
        ).also { state ->
            activeTaskExecutionPanel = state
            taskExecutionPanelsByRunId[progress.runId] = state
        }
    }

    private fun retireSupersededTaskExecutionPanel(
        existing: ActiveTaskExecutionPanel,
        nextRunId: String,
    ) {
        if (existing.runId == nextRunId || existing.finished) {
            return
        }
        existing.panel.finishMessage()
        existing.finished = true
    }

    private fun removeTaskExecutionPanelReference(panel: ChatMessagePanel) {
        val matchedRunId = taskExecutionPanelsByRunId.entries
            .firstOrNull { (_, state) -> state.panel === panel }
            ?.key
            ?: return
        taskExecutionPanelsByRunId.remove(matchedRunId)
        if (activeTaskExecutionPanel?.runId == matchedRunId) {
            activeTaskExecutionPanel = null
        }
    }

    private fun collectNewTaskExecutionTraceEvents(
        state: ActiveTaskExecutionPanel,
        progress: TaskExecutionLiveProgress,
    ): List<ChatStreamEvent> {
        val candidates = progress.recentEvents
            .mapNotNull(::sanitizeStreamEvent)
            .ifEmpty {
                listOfNotNull(buildFallbackTaskExecutionTraceEvent(progress))
            }
        return candidates.filter { event ->
            state.seenEventKeys.add(taskExecutionTraceEventKey(progress.runId, event))
        }
    }

    private fun buildFallbackTaskExecutionTraceEvent(progress: TaskExecutionLiveProgress): ChatStreamEvent? {
        val detail = progress.lastDetail?.trim()?.takeIf(String::isNotBlank) ?: return null
        val kind = if (progress.phase == ExecutionLivePhase.STREAMING) {
            ChatTraceKind.OUTPUT
        } else {
            ChatTraceKind.TASK
        }
        val status = resolveFallbackTaskExecutionTraceStatus(progress)
        return sanitizeStreamEvent(
            ChatStreamEvent(
                kind = kind,
                detail = detail,
                status = status,
            ),
        )
    }

    private fun taskExecutionTraceEventKey(runId: String, event: ChatStreamEvent): String {
        return buildString {
            append(runId)
            append('|')
            append(event.sequence ?: "")
            append('|')
            append(event.id.orEmpty())
            append('|')
            append(event.kind.name)
            append('|')
            append(event.status.name)
            append('|')
            append(event.detail)
        }
    }

    private fun resolvedActiveWorkflowChatBinding(): WorkflowChatBinding? {
        val explicitBinding = activeWorkflowChatBinding?.normalizedOrNull()
        val activeWorkflowId = activeSpecWorkflowId?.trim()?.ifBlank { null }
        return when {
            explicitBinding != null && (activeWorkflowId == null || explicitBinding.workflowId == activeWorkflowId) ->
                explicitBinding

            !activeWorkflowId.isNullOrBlank() -> WorkflowChatBinding(
                workflowId = activeWorkflowId,
                focusedStage = explicitBinding?.focusedStage,
                source = explicitBinding?.source ?: WorkflowChatEntrySource.MODE_SWITCH,
                actionIntent = explicitBinding?.actionIntent ?: WorkflowChatActionIntent.DISCUSS,
            )

            else -> explicitBinding
        }
    }

    private fun resolvedCurrentWorkflowChatExecutionContext(
        binding: WorkflowChatBinding? = resolvedActiveWorkflowChatBinding(),
        cachedOnly: Boolean = false,
        preferCachedSnapshot: Boolean = true,
    ): WorkflowChatExecutionContext? {
        val normalizedBinding = binding?.normalizedOrNull() ?: return null
        latestObservedTaskLiveProgress
            ?.takeIf { progress ->
                progress.workflowId == normalizedBinding.workflowId &&
                    progress.phase != ExecutionLivePhase.TERMINAL
            }
            ?.let { progress ->
                val status = when (progress.phase) {
                    ExecutionLivePhase.QUEUED -> TaskExecutionRunStatus.QUEUED
                    ExecutionLivePhase.WAITING_CONFIRMATION -> TaskExecutionRunStatus.WAITING_CONFIRMATION
                    ExecutionLivePhase.PREPARING_CONTEXT,
                    ExecutionLivePhase.REQUEST_DISPATCHED,
                    ExecutionLivePhase.STREAMING,
                    ExecutionLivePhase.CANCELLING,
                    -> TaskExecutionRunStatus.RUNNING
                    ExecutionLivePhase.TERMINAL -> null
                }
                if (status != null) {
                    return WorkflowChatExecutionContext(
                        workflowId = progress.workflowId,
                        runId = progress.runId,
                        taskId = progress.taskId,
                        status = status,
                        enteredAt = progress.startedAt.toString(),
                    )
                }
            }
        if (preferCachedSnapshot) {
            latestWorkflowBindingUiSnapshot
                ?.takeIf { snapshot -> snapshot.binding == normalizedBinding }
                ?.let { snapshot -> return snapshot.executionContext }
        }
        if (cachedOnly) {
            return null
        }
        return workflowChatExecutionContextResolver.resolve(normalizedBinding)
    }

    private fun setExplicitWorkflowChatBinding(
        binding: WorkflowChatBinding?,
        updateActiveWorkflowId: Boolean = true,
    ) {
        activeWorkflowChatBinding = binding?.normalizedOrNull()
        if (updateActiveWorkflowId) {
            activeSpecWorkflowId = activeWorkflowChatBinding?.workflowId
        }
        updateWorkflowBindingUi()
    }

    private fun updateWorkflowBindingUi() {
        val binding = resolvedActiveWorkflowChatBinding()
        val specMode = currentInteractionMode() == ChatInteractionMode.SPEC
        applyWorkflowBindingBaseState(specMode)
        if (!specMode || binding == null) {
            workflowBindingRefreshJob?.cancel()
            latestWorkflowBindingUiSnapshot = null
            refreshWorkflowBindingUiPresentation(visible = false)
            return
        }
        val requestGeneration = workflowBindingRefreshGeneration.incrementAndGet()
        workflowBindingRefreshJob?.cancel()
        workflowBindingRefreshJob = taskCoordinator.launchIo {
            val snapshot = buildWorkflowBindingUiSnapshot(binding)
            taskCoordinator.invokeLater {
                if (project.isDisposed || _isDisposed) {
                    return@invokeLater
                }
                if (workflowBindingRefreshGeneration.get() != requestGeneration) {
                    return@invokeLater
                }
                latestWorkflowBindingUiSnapshot = snapshot
                if (snapshot == null) {
                    refreshWorkflowBindingUiPresentation(visible = false)
                    return@invokeLater
                }
                applyWorkflowBindingSnapshot(snapshot)
            }
        }
    }

    private fun applyWorkflowBindingBaseState(specMode: Boolean) {
        val visible = specMode
        workflowBindingStrip.isVisible = visible
        workflowBindingChip.isVisible = false
        workflowBindingChip.text = ""
        workflowBindingChip.accessibleContext.accessibleName = ""
        workflowBindingChip.toolTipText = null
        workflowBindingChip.accessibleContext.accessibleDescription = null
        taskBindingChip.isVisible = false
        taskBindingChip.text = ""
        taskBindingChip.toolTipText = null
        taskBindingChip.accessibleContext.accessibleName = ""
        taskBindingChip.accessibleContext.accessibleDescription = null
        executeTaskBindingButton.isVisible = false
        retryTaskBindingButton.isVisible = false
        completeTaskBindingButton.isVisible = false
        taskBindingOverflowButton.isVisible = false
        clearTaskBindingButton.toolTipText = null
        clearTaskBindingButton.isVisible = false
    }

    private fun buildWorkflowBindingUiSnapshot(binding: WorkflowChatBinding): WorkflowBindingUiSnapshot? {
        val executionContext = resolvedCurrentWorkflowChatExecutionContext(
            binding = binding,
            preferCachedSnapshot = false,
        ) ?: return null
        val state = resolveBoundTaskBindingState(binding, executionContext.taskId)
        return WorkflowBindingUiSnapshot(
            binding = binding,
            executionContext = executionContext,
            state = state,
        )
    }

    private fun applyWorkflowBindingSnapshot(snapshot: WorkflowBindingUiSnapshot) {
        val executionContext = snapshot.executionContext
        val state = snapshot.state
        workflowBindingStrip.isVisible = true
        taskBindingChip.isVisible = true
        taskBindingChip.text = buildTaskBindingChipText(executionContext.taskId, state)
        taskBindingChip.toolTipText = buildTaskBindingTooltip(executionContext.taskId, state)
        taskBindingChip.accessibleContext.accessibleName = taskBindingChip.text
        taskBindingChip.accessibleContext.accessibleDescription = taskBindingChip.toolTipText
        state.liveProgress
            ?.takeIf { progress ->
                progress.phase != ExecutionLivePhase.TERMINAL &&
                    progress.phase != ExecutionLivePhase.WAITING_CONFIRMATION
            }
            ?.let(::syncActiveTaskExecutionPanel)
        refreshWorkflowBindingUiPresentation(visible = true)
    }

    private fun refreshWorkflowBindingUiPresentation(visible: Boolean) {
        workflowBindingStrip.isVisible = visible
        workflowBindingStrip.revalidate()
        workflowBindingStrip.repaint()
        refreshComposerAccessoryStripVisibility()
        refreshActionButtonTexts()
    }

    private fun buildTaskBindingChipText(taskId: String, state: BoundTaskBindingState?): String {
        return SpecCodingBundle.message(
            "toolwindow.workflow.binding.task.summary",
            taskId,
            resolveBoundTaskStatusText(state),
        )
    }

    private fun buildTaskBindingTooltip(taskId: String, state: BoundTaskBindingState?): String {
        val task = state?.task
        if (task == null) {
            return SpecCodingBundle.message("toolwindow.workflow.binding.task.tooltip.missing", taskId)
        }
        val liveProgress = state.liveProgress?.takeIf { it.phase != ExecutionLivePhase.TERMINAL }
        val activeRun = task.activeExecutionRun
        val runId = liveProgress?.runId ?: activeRun?.runId
        val runStatus = when {
            liveProgress != null -> liveProgress.phase.name
            activeRun != null -> activeRun.status.name
            else -> null
        }
        return if (runId == null || runStatus == null) {
            SpecCodingBundle.message(
                "toolwindow.workflow.binding.task.tooltip.state",
                taskId,
                resolveBoundTaskStatusText(state),
            )
        } else {
            SpecCodingBundle.message(
                "toolwindow.workflow.binding.task.tooltip.run",
                taskId,
                resolveBoundTaskStatusText(state),
                runId,
                runStatus,
            )
        }
    }

    private fun resolveBoundTaskBindingState(): BoundTaskBindingState? {
        val binding = resolvedActiveWorkflowChatBinding() ?: return null
        val taskId = resolvedCurrentWorkflowChatExecutionContext(binding, cachedOnly = true)?.taskId
            ?: return null
        latestWorkflowBindingUiSnapshot
            ?.takeIf { snapshot ->
                snapshot.binding == binding && snapshot.executionContext.taskId == taskId
            }
            ?.let { snapshot -> return snapshot.state }
        return resolveBoundTaskBindingState(binding, taskId)
    }

    private fun resolveBoundTaskBindingState(
        binding: WorkflowChatBinding,
        taskId: String,
    ): BoundTaskBindingState {
        val workflow = specEngine.loadWorkflow(binding.workflowId).getOrNull()
        val workflowTasks = if (workflow == null) {
            emptyList()
        } else {
            specTasksService.parse(binding.workflowId)
                .attachActiveExecutionRuns(workflow.taskExecutionRuns)
        }
        val task = workflowTasks.firstOrNull { candidate -> candidate.id == taskId }
        val serviceLiveProgress = specTaskExecutionService.getTaskLiveProgress(binding.workflowId, taskId)
        val observedLiveProgress = latestObservedTaskLiveProgress
            ?.takeIf { progress ->
                progress.workflowId == binding.workflowId &&
                    progress.taskId == taskId &&
                    progress.phase != ExecutionLivePhase.TERMINAL
            }
            ?.takeIf { progress ->
                serviceLiveProgress != null ||
                    task?.activeExecutionRun?.runId == progress.runId ||
                    task?.hasExecutionInFlight == true ||
                    (task?.awaitingCompletionConfirmation == true &&
                        progress.phase == ExecutionLivePhase.WAITING_CONFIRMATION)
            }
        return BoundTaskBindingState(
            binding = binding,
            taskId = taskId,
            task = task,
            workflowTasks = workflowTasks,
            liveProgress = listOfNotNull(observedLiveProgress, serviceLiveProgress)
                .maxByOrNull(TaskExecutionLiveProgress::lastUpdatedAt),
        )
    }

    private fun resolveBoundTaskStatusText(state: BoundTaskBindingState?): String {
        val liveProgress = state?.liveProgress?.takeIf { it.phase != ExecutionLivePhase.TERMINAL }
        return when {
            liveProgress?.phase == ExecutionLivePhase.CANCELLING ->
                SpecCodingBundle.message("toolwindow.workflow.binding.task.status.cancelling")

            state?.task?.awaitingCompletionConfirmation == true ||
                liveProgress?.phase == ExecutionLivePhase.WAITING_CONFIRMATION ->
                SpecCodingBundle.message("toolwindow.workflow.binding.task.status.waitingConfirmation")

            liveProgress != null ->
                SpecCodingBundle.message("toolwindow.workflow.binding.task.status.inProgress")

            else -> formatTaskStatus(state?.task?.displayStatus)
        }
    }

    private fun resolvePrimaryTaskBindingAction(state: BoundTaskBindingState?): TaskBindingActionKind? {
        val task = state?.task ?: return null
        val liveProgress = state.liveProgress?.takeIf { it.phase != ExecutionLivePhase.TERMINAL }
        return when {
            liveProgress?.phase == ExecutionLivePhase.CANCELLING -> TaskBindingActionKind.STOP
            task.awaitingCompletionConfirmation || liveProgress?.phase == ExecutionLivePhase.WAITING_CONFIRMATION ->
                TaskBindingActionKind.COMPLETE

            task.hasExecutionInFlight ||
                liveProgress?.phase == ExecutionLivePhase.QUEUED ||
                liveProgress?.phase == ExecutionLivePhase.PREPARING_CONTEXT ||
                liveProgress?.phase == ExecutionLivePhase.REQUEST_DISPATCHED ||
                liveProgress?.phase == ExecutionLivePhase.STREAMING ->
                TaskBindingActionKind.STOP

            task.status == TaskStatus.PENDING -> TaskBindingActionKind.EXECUTE
            task.status == TaskStatus.BLOCKED -> TaskBindingActionKind.RETRY
            else -> null
        }
    }

    private fun buildTaskBindingActionPresentation(
        state: BoundTaskBindingState,
        actionKind: TaskBindingActionKind,
    ): SpecIconActionPresentation {
        val task = state.task
        val taskId = state.taskId
        return when (actionKind) {
            TaskBindingActionKind.EXECUTE -> when (task?.status) {
                TaskStatus.PENDING -> {
                    val blockedReason = boundTaskExecutionBlockedReason(state, retry = false)
                    SpecIconActionPresentation(
                        icon = SpecWorkflowIcons.Execute,
                        tooltip = blockedReason
                            ?: SpecCodingBundle.message("spec.toolwindow.tasks.execute.start.tooltip", taskId),
                        accessibleName = SpecCodingBundle.message("toolwindow.workflow.binding.execute"),
                        enabled = blockedReason == null,
                        disabledReason = blockedReason,
                    )
                }

                null -> SpecIconActionPresentation(
                    icon = SpecWorkflowIcons.Execute,
                    tooltip = SpecCodingBundle.message("toolwindow.workflow.binding.execute.unavailable"),
                    accessibleName = SpecCodingBundle.message("toolwindow.workflow.binding.execute"),
                    enabled = false,
                    disabledReason = SpecCodingBundle.message("toolwindow.workflow.binding.execute.unavailable"),
                )

                else -> SpecIconActionPresentation(
                    icon = SpecWorkflowIcons.Execute,
                    tooltip = SpecCodingBundle.message("toolwindow.workflow.binding.execute.unavailable"),
                    accessibleName = SpecCodingBundle.message("toolwindow.workflow.binding.execute"),
                    enabled = false,
                    disabledReason = SpecCodingBundle.message(
                        "toolwindow.workflow.binding.execute.unavailable.state",
                        taskId,
                        resolveBoundTaskStatusText(state),
                    ),
                )
            }

            TaskBindingActionKind.RETRY -> when (task?.status) {
                TaskStatus.BLOCKED -> {
                    val blockedReason = boundTaskExecutionBlockedReason(state, retry = true)
                    SpecIconActionPresentation(
                        icon = SpecWorkflowIcons.Refresh,
                        tooltip = blockedReason
                            ?: SpecCodingBundle.message("spec.toolwindow.tasks.execute.resume.tooltip", taskId),
                        accessibleName = SpecCodingBundle.message("toolwindow.workflow.binding.retry"),
                        enabled = blockedReason == null,
                        disabledReason = blockedReason,
                    )
                }

                null -> SpecIconActionPresentation(
                    icon = SpecWorkflowIcons.Refresh,
                    tooltip = SpecCodingBundle.message("toolwindow.workflow.binding.retry.unavailable"),
                    accessibleName = SpecCodingBundle.message("toolwindow.workflow.binding.retry"),
                    enabled = false,
                    disabledReason = SpecCodingBundle.message("toolwindow.workflow.binding.retry.unavailable"),
                )

                else -> SpecIconActionPresentation(
                    icon = SpecWorkflowIcons.Refresh,
                    tooltip = SpecCodingBundle.message("toolwindow.workflow.binding.retry.unavailable"),
                    accessibleName = SpecCodingBundle.message("toolwindow.workflow.binding.retry"),
                    enabled = false,
                    disabledReason = SpecCodingBundle.message(
                        "toolwindow.workflow.binding.retry.unavailable.state",
                        taskId,
                        resolveBoundTaskStatusText(state),
                    ),
                )
            }

            TaskBindingActionKind.COMPLETE -> when {
                task?.awaitingCompletionConfirmation == true ||
                    state.liveProgress?.phase == ExecutionLivePhase.WAITING_CONFIRMATION ->
                    SpecIconActionPresentation(
                        icon = SpecWorkflowIcons.Complete,
                        tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.complete.tooltip", taskId),
                        accessibleName = SpecCodingBundle.message("toolwindow.workflow.binding.complete"),
                        enabled = true,
                    )

                task == null -> SpecIconActionPresentation(
                    icon = SpecWorkflowIcons.Complete,
                    tooltip = SpecCodingBundle.message("toolwindow.workflow.binding.complete.unavailable"),
                    accessibleName = SpecCodingBundle.message("toolwindow.workflow.binding.complete"),
                    enabled = false,
                    disabledReason = SpecCodingBundle.message("toolwindow.workflow.binding.complete.unavailable"),
                )

                else -> SpecIconActionPresentation(
                    icon = SpecWorkflowIcons.Complete,
                    tooltip = SpecCodingBundle.message("toolwindow.workflow.binding.complete.unavailable"),
                    accessibleName = SpecCodingBundle.message("toolwindow.workflow.binding.complete"),
                    enabled = false,
                    disabledReason = SpecCodingBundle.message(
                        "toolwindow.workflow.binding.complete.unavailable.state",
                        taskId,
                        resolveBoundTaskStatusText(state),
                    ),
                )
            }

            TaskBindingActionKind.STOP -> when {
                task == null -> SpecIconActionPresentation(
                    icon = SpecWorkflowIcons.Close,
                    tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.stop.tooltip", taskId),
                    accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.stop"),
                    enabled = false,
                    disabledReason = SpecCodingBundle.message("toolwindow.workflow.binding.execute.unavailable"),
                )

                else -> SpecIconActionPresentation(
                    icon = SpecWorkflowIcons.Close,
                    tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.stop.tooltip", taskId),
                    accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.stop"),
                    enabled = true,
                )
            }
        }
    }

    private fun showTaskBindingOverflowMenu(anchor: JComponent, x: Int = 0, y: Int = anchor.height) {
        val state = resolveBoundTaskBindingState() ?: return
        val popup = JPopupMenu()
        popup.add(createTaskBindingMenuItem(
            text = SpecCodingBundle.message("toolwindow.workflow.binding.open"),
            icon = AllIcons.Actions.MenuOpen,
            tooltip = SpecCodingBundle.message("toolwindow.workflow.binding.open.tooltip", state.taskId),
        ) {
            handleTaskBindingChipClicked()
        })
        popup.show(anchor, x, y)
    }

    private fun createTaskBindingMenuItem(
        text: String,
        icon: Icon,
        tooltip: String,
        action: () -> Unit,
    ): JMenuItem {
        return JMenuItem(text, icon).apply {
            toolTipText = tooltip
            addActionListener { action() }
        }
    }

    private fun formatTaskStatus(status: TaskStatus?): String {
        return when (status) {
            TaskStatus.PENDING -> SpecCodingBundle.message("toolwindow.workflow.binding.task.status.pending")
            TaskStatus.BLOCKED -> SpecCodingBundle.message("toolwindow.workflow.binding.task.status.blocked")
            TaskStatus.IN_PROGRESS -> SpecCodingBundle.message("toolwindow.workflow.binding.task.status.inProgress")
            TaskStatus.COMPLETED -> SpecCodingBundle.message("toolwindow.workflow.binding.task.status.completed")
            TaskStatus.CANCELLED -> SpecCodingBundle.message("toolwindow.workflow.binding.task.status.cancelled")
            null -> SpecCodingBundle.message("toolwindow.workflow.binding.task.status.unknown")
        }
    }

    private fun applyUnavailableTaskBindingActionButtons() {
        listOf(
            executeTaskBindingButton to "toolwindow.workflow.binding.execute.unavailable",
            retryTaskBindingButton to "toolwindow.workflow.binding.retry.unavailable",
            completeTaskBindingButton to "toolwindow.workflow.binding.complete.unavailable",
        ).forEach { (button, messageKey) ->
            applyTaskBindingActionPresentation(
                button = button,
                presentation = SpecIconActionPresentation(
                    icon = button.icon ?: SpecWorkflowIcons.Execute,
                    tooltip = SpecCodingBundle.message(messageKey),
                    accessibleName = SpecCodingBundle.message(messageKey),
                    enabled = false,
                    disabledReason = SpecCodingBundle.message(messageKey),
                ),
            )
        }
    }

    private fun applyTaskBindingActionPresentation(
        button: JButton,
        presentation: SpecIconActionPresentation,
    ) {
        SpecUiStyle.applyIconActionPresentation(button, presentation)
    }

    private fun boundTaskExecutionBlockedReason(
        state: BoundTaskBindingState,
        retry: Boolean,
    ): String? {
        val task = state.task ?: return null
        val constraint = SpecTaskDependencyRules.executionConstraint(task, state.workflowTasks)
        if (constraint.executable) {
            return null
        }
        val messageKey = if (retry) {
            "toolwindow.workflow.binding.retry.dependenciesBlocked"
        } else {
            "toolwindow.workflow.binding.execute.dependenciesBlocked"
        }
        return SpecCodingBundle.message(
            messageKey,
            state.taskId,
            constraint.unmetDependencyIds.joinToString(", "),
        )
    }

    private fun resolveWorkflowBindingLabel(workflowId: String): String {
        return cachedWorkflowOption(workflowId)
            ?.title
            ?.trim()
            ?.ifBlank { workflowId }
            ?: workflowId
    }

    private fun handleWorkflowBindingChipClicked() {
        val binding = resolvedActiveWorkflowChatBinding() ?: return
        openSpecWorkflowRequest(
            SpecToolWindowOpenRequest(
                workflowId = binding.workflowId,
                focusedStage = binding.focusedStage,
            ),
        )
    }

    private fun handleTaskBindingChipClicked() {
        val binding = resolvedActiveWorkflowChatBinding() ?: return
        val taskId = resolvedCurrentWorkflowChatExecutionContext(binding, cachedOnly = true)?.taskId ?: return
        openSpecWorkflowRequest(
            SpecToolWindowOpenRequest(
                workflowId = binding.workflowId,
                taskId = taskId,
                focusedStage = binding.focusedStage ?: StageId.IMPLEMENT,
            ),
        )
    }

    private fun openSpecWorkflowRequest(request: SpecToolWindowOpenRequest) {
        openSpecTab()
        project.messageBus.syncPublisher(SpecToolWindowControlListener.TOPIC)
            .onOpenWorkflowRequested(request)
    }

    private fun clearActiveTaskBinding() {
        val state = resolveBoundTaskBindingState() ?: return
        showStatus(
            SpecCodingBundle.message("toolwindow.workflow.binding.status.taskCleared", state.taskId),
            autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
        )
    }

    private fun performTaskBindingAction(
        actionKind: TaskBindingActionKind,
        supplementalInstruction: String? = null,
    ) {
        val state = resolveBoundTaskBindingState()
        when (actionKind) {
            TaskBindingActionKind.EXECUTE -> {
                clearComposerInput()
                val taskId = state?.taskId ?: return
                handleWorkflowTaskExecutionRequested(
                    taskId = taskId,
                    retry = false,
                    supplementalInstruction = supplementalInstruction,
                )
            }

            TaskBindingActionKind.RETRY -> {
                clearComposerInput()
                val taskId = state?.taskId ?: return
                handleWorkflowTaskExecutionRequested(
                    taskId = taskId,
                    retry = true,
                    supplementalInstruction = supplementalInstruction,
                )
            }

            TaskBindingActionKind.COMPLETE -> {
                clearComposerInput()
                val taskId = state?.taskId ?: return
                handleWorkflowTaskCompletionRequested(taskId)
            }

            TaskBindingActionKind.STOP -> {
                requestBoundTaskExecutionStop()
            }
        }
    }

    private fun requestBoundTaskExecutionStop() {
        val state = resolveBoundTaskBindingState() ?: return
        val runId = state.liveProgress?.runId ?: state.task?.activeExecutionRun?.runId ?: return
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.execution.cancel.progress", state.taskId),
            task = {
                specTaskExecutionService.cancelExecutionRun(
                    workflowId = state.binding.workflowId,
                    runId = runId,
                )
            },
            onSuccess = {
                publishSpecWorkflowChanged(state.binding.workflowId, reason = "workflow_chat_task_execution_stop_requested")
                updateWorkflowBindingUi()
                showStatus(
                    SpecCodingBundle.message("spec.toolwindow.tasks.execution.cancel.requested", state.taskId),
                    autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                )
            },
            onFailure = { error ->
                handleWorkflowTaskActionFailure(error)
            },
        )
    }

    private fun handleBoundTaskExecutionRequested(
        retry: Boolean,
        supplementalInstruction: String? = null,
    ) {
        val taskId = resolveBoundTaskBindingState()?.taskId ?: return
        handleWorkflowTaskExecutionRequested(taskId, retry, supplementalInstruction)
    }

    private fun handleWorkflowTaskExecutionRequested(
        taskId: String,
        retry: Boolean,
        supplementalInstruction: String? = null,
    ) {
        val sessionId = currentSessionId?.trim()?.ifBlank { null } ?: return
        val binding = resolvedActiveWorkflowChatBinding() ?: return
        showWorkflowAttachmentBoundaryIfNeeded()
        val executionContext = resolveWorkflowChatTaskExecutionContext() ?: return
        val progressKey = if (retry) {
            "spec.toolwindow.tasks.retry.progress"
        } else {
            "spec.toolwindow.tasks.execute.progress"
        }
        val cancellationHandleRef =
            AtomicReference<SpecTaskExecutionService.TaskExecutionCancellationHandle?>()
        val cancelRequested = AtomicBoolean(false)
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message(progressKey, taskId),
            task = {
                val onRequestRegistered: (SpecTaskExecutionService.TaskExecutionCancellationHandle) -> Unit = { handle ->
                    cancellationHandleRef.set(handle)
                    if (cancelRequested.get()) {
                        specTaskExecutionService.cancelExecutionRun(
                            workflowId = handle.workflowId,
                            runId = handle.runId,
                        )
                    }
                }
                if (retry) {
                    workflowChatActionRouter.retryTask(
                        sessionId = sessionId,
                        taskId = taskId,
                        providerId = executionContext.providerId,
                        modelId = executionContext.modelId,
                        operationMode = executionContext.operationMode,
                        supplementalInstruction = supplementalInstruction,
                        onRequestRegistered = onRequestRegistered,
                    )
                } else {
                    workflowChatActionRouter.executeTask(
                        sessionId = sessionId,
                        taskId = taskId,
                        providerId = executionContext.providerId,
                        modelId = executionContext.modelId,
                        operationMode = executionContext.operationMode,
                        supplementalInstruction = supplementalInstruction,
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
                syncTaskExecutionSessionMessages(
                    sessionId = sessionId,
                    runId = result.run.runId,
                    requestId = result.requestId,
                )
                showStatus(
                    SpecCodingBundle.message(statusKey, taskId, result.sessionTitle),
                    autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                )
                publishSpecWorkflowChanged(binding.workflowId, reason = if (retry) {
                    "workflow_chat_retry_task"
                } else {
                    "workflow_chat_execute_task"
                })
                updateWorkflowBindingUi()
            },
            onCancelRequested = {
                if (cancelRequested.compareAndSet(false, true)) {
                    cancellationHandleRef.get()?.let { handle ->
                        specTaskExecutionService.cancelExecutionRun(
                            workflowId = handle.workflowId,
                            runId = handle.runId,
                        )
                    }
                    showStatus(
                        SpecCodingBundle.message("spec.toolwindow.tasks.execution.cancel.requested", taskId),
                        autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                    )
                }
            },
            onCancelled = {
                cancellationHandleRef.get()?.let { handle ->
                    finishActiveTaskExecutionPanel(handle.runId)
                    syncTaskExecutionSessionMessages(
                        sessionId = sessionId,
                        runId = handle.runId,
                        requestId = handle.requestId,
                    )
                }
                showStatus(
                    SpecCodingBundle.message("spec.toolwindow.tasks.execution.cancelled", taskId),
                    autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                )
                publishSpecWorkflowChanged(binding.workflowId, reason = "workflow_chat_task_execution_cancelled")
                updateWorkflowBindingUi()
            },
            onFailure = { error ->
                handleWorkflowTaskActionFailure(error)
            },
        )
    }

    private fun handleBoundTaskCompletionRequested() {
        val taskId = resolveBoundTaskBindingState()?.taskId ?: return
        handleWorkflowTaskCompletionRequested(taskId)
    }

    private fun handleWorkflowTaskCompletionRequested(taskId: String) {
        val sessionId = currentSessionId?.trim()?.ifBlank { null } ?: return
        val binding = resolvedActiveWorkflowChatBinding() ?: return
        showWorkflowAttachmentBoundaryIfNeeded()
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.complete.progress"),
            task = {
                workflowChatActionRouter.previewCompleteTask(sessionId, taskId)
            },
            onSuccess = { plan ->
                val dialogResult = SpecTaskCompletionDialogs.showCompletionConfirmation(
                    taskId = taskId,
                    initialRelatedFiles = plan.suggestedRelatedFiles,
                    initialVerificationResult = plan.existingVerificationResult,
                ) ?: return@runBackground
                SpecWorkflowActionSupport.runBackground(
                    project = project,
                    title = SpecCodingBundle.message("spec.toolwindow.tasks.complete.progress"),
                    task = {
                        workflowChatActionRouter.completeTask(
                            sessionId = sessionId,
                            taskId = taskId,
                            planId = plan.planId,
                            relatedFiles = dialogResult.relatedFiles,
                            verificationResult = dialogResult.verificationResult,
                        )
                    },
                    onSuccess = { completedTask ->
                        setExplicitWorkflowChatBinding(
                            binding.copy(actionIntent = WorkflowChatActionIntent.COMPLETE_TASK),
                            updateActiveWorkflowId = false,
                        )
                        val message = SpecCodingBundle.message("spec.toolwindow.tasks.complete.updated", completedTask.id)
                        appendWorkflowChatSystemMessage(sessionId, message)
                        showStatus(message, autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS)
                        publishSpecWorkflowChanged(binding.workflowId, reason = "workflow_chat_complete_task")
                        updateWorkflowBindingUi()
                    },
                    onFailure = { error ->
                        handleWorkflowTaskActionFailure(error)
                    },
                )
            },
            onFailure = { error ->
                handleWorkflowTaskActionFailure(error)
            },
        )
    }

    private fun handleWorkflowTaskActionFailure(error: Throwable) {
        showWorkflowTaskErrorDialog(
            error.message ?: SpecCodingBundle.message("common.unknown"),
        )
    }

    private fun showWorkflowTaskErrorDialog(message: String) {
        val title = SpecCodingBundle.message("toolwindow.workflow.error.dialog.title")
        lastWorkflowErrorDialogSnapshot = WorkflowErrorDialogSnapshot(
            title = title,
            message = message,
        )
        val application = ApplicationManager.getApplication()
        val showDialog = Runnable {
            if (project.isDisposed || _isDisposed) {
                return@Runnable
            }
            hideStatus()
            if (!application.isUnitTestMode) {
                SpecSimpleMessageDialog(
                    project = project,
                    dialogTitle = title,
                    messageText = message,
                    closeButtonText = SpecCodingBundle.message("spec.action.gate.close"),
                ).show()
            }
        }
        if (application.isUnitTestMode) {
            if (SwingUtilities.isEventDispatchThread()) {
                showDialog.run()
            } else {
                application.invokeAndWait(showDialog)
            }
            return
        }
        application.invokeLater(showDialog)
    }

    private fun resolveWorkflowChatTaskExecutionContext(): TaskExecutionContext? {
        val providerId = providerComboBox.selectedItem as? String
        if (providerId.isNullOrBlank()) {
            showStatus(
                SpecCodingBundle.message("spec.toolwindow.tasks.execute.providerRequired"),
                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
            )
            return null
        }
        val modelId = (modelComboBox.selectedItem as? ModelInfo)?.id?.trim().orEmpty()
        if (modelId.isBlank()) {
            showStatus(
                SpecCodingBundle.message(
                    "spec.toolwindow.tasks.execute.modelRequired",
                    providerDisplayName(providerId),
                ),
                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
            )
            return null
        }
        return TaskExecutionContext(
            providerId = providerId,
            modelId = modelId,
            operationMode = modeManager.getCurrentMode(),
        )
    }

    private fun showWorkflowAttachmentBoundaryIfNeeded() {
        if (imageAttachmentPreviewPanel.getImagePaths().isEmpty()) {
            return
        }
        showStatus(
            SpecCodingBundle.message("toolwindow.workflow.attachment.boundary"),
            autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
        )
    }

    private fun appendWorkflowChatSystemMessage(sessionId: String, content: String) {
        addSystemMessage(content)
        appendToConversationHistory(LlmMessage(LlmRole.SYSTEM, content))
        persistMessageAsync(sessionId, ConversationRole.SYSTEM, content)
    }

    private fun openWorkflowChatBinding(request: WorkflowChatOpenRequest) {
        val binding = request.binding.normalizedOrNull() ?: return
        if (request.preferNewSession) {
            startNewSession()
        }
        setInteractionMode(
            mode = ChatInteractionMode.SPEC,
            resetSessionOnSpecSwitch = false,
            emitHook = true,
        )
        setExplicitWorkflowChatBinding(binding)
        refreshSpecWorkflowComboBox(selectWorkflowId = binding.workflowId)
        val providerId = providerComboBox.selectedItem as? String
        taskCoordinator.launchIo {
            val sessionId = ensureActiveSpecSessionAsync(
                titleSeed = buildWorkflowChatSessionTitle(binding),
                providerId = providerId,
                specTaskId = binding.workflowId,
                workflowBinding = binding,
            )
            sessionId?.let { id ->
                chatPersistenceCoordinator.persistWorkflowChatBinding(id, binding)
                    .onFailure { error ->
                        logger.warn("Failed to persist workflow chat binding for session $id", error)
                    }
            }
        }
        if (specSidebarVisible) {
            specSidebarPanel.focusWorkflow(binding.workflowId)
        }
        showStatus(
            SpecCodingBundle.message("toolwindow.workflow.binding.status.workflowBound", binding.workflowId),
            autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
        )
    }

    private fun buildWorkflowChatSessionTitle(binding: WorkflowChatBinding): String {
        return resolveWorkflowBindingLabel(binding.workflowId)
    }

    private fun switchActiveSpecWorkflowFromUser(workflowId: String) {
        val normalizedId = workflowId.trim()
        if (normalizedId.isBlank()) return
        if (currentInteractionMode() != ChatInteractionMode.SPEC) return
        if (normalizedId == activeSpecWorkflowId?.trim()?.ifBlank { null }) {
            return
        }
        publishSpecWorkflowChanged(normalizedId, reason = SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED)
    }

    private fun refreshSpecWorkflowComboBox(selectWorkflowId: String?) {
        if (project.isDisposed || _isDisposed) return
        if (currentInteractionMode() != ChatInteractionMode.SPEC) {
            refreshSpecWorkflowComboJob?.cancel()
            cachedSpecWorkflowOptions = emptyList()
            specWorkflowComboBox.isVisible = false
            return
        }

        val targetWorkflowId = selectWorkflowId?.trim()?.ifBlank { null }
        refreshSpecWorkflowComboJob?.cancel()
        refreshSpecWorkflowComboJob = taskCoordinator.launchIo {
            val options = loadSpecWorkflowOptions()

            taskCoordinator.invokeLater {
                if (project.isDisposed || _isDisposed) return@invokeLater
                if (currentInteractionMode() != ChatInteractionMode.SPEC) {
                    specWorkflowComboBox.isVisible = false
                    return@invokeLater
                }
                cachedSpecWorkflowOptions = options

                suppressSpecWorkflowSelectionEvents = true
                try {
                    val previousSelection = (specWorkflowComboBox.selectedItem as? SpecWorkflowOption)?.workflowId
                    specWorkflowComboBox.removeAllItems()
                    options.forEach { option -> specWorkflowComboBox.addItem(option) }

                    val candidateSelection = targetWorkflowId
                        ?: activeSpecWorkflowId?.trim()?.ifBlank { null }
                        ?: previousSelection?.trim()?.ifBlank { null }
                    val selectedId = candidateSelection
                        ?.takeIf { desired -> options.any { it.workflowId == desired } }
                        ?: options.firstOrNull()?.workflowId
                    val selectedOption = options.firstOrNull { it.workflowId == selectedId }
                    if (selectedOption != null) {
                        specWorkflowComboBox.selectedItem = selectedOption
                        activeSpecWorkflowId = selectedOption.workflowId
                    }

                    specWorkflowComboBox.isVisible = options.isNotEmpty()
                    specWorkflowComboBox.isEnabled = options.size > 1
                } finally {
                    suppressSpecWorkflowSelectionEvents = false
                }

                updateSpecWorkflowComboSize()
                updateComboTooltips()
                updateWorkflowBindingUi()
            }
        }
    }

    private fun loadSpecWorkflowOptions(): List<SpecWorkflowOption> {
        return runCatching { specEngine.listWorkflowMetadata() }
            .getOrDefault(emptyList())
            .map { meta ->
                SpecWorkflowOption(
                    workflowId = meta.workflowId,
                    title = meta.title?.ifBlank { meta.workflowId } ?: meta.workflowId,
                    updatedAt = meta.updatedAt,
                    currentStage = meta.currentStage,
                )
            }
            .sortedWith(
                compareByDescending<SpecWorkflowOption> { it.updatedAt }
                    .thenBy { it.title }
                    .thenBy { it.workflowId },
            )
    }

    private fun handleSendOrStop() {
        if (project.isDisposed || _isDisposed) {
            return
        }
        if (isRestoringSession) {
            showBusyStatus()
            return
        }
        if (isFinalizingResponse) {
            showBusyStatus()
            return
        }
        val sendAction = resolveComposerSendAction()
        if (sendAction.kind == ComposerSendActionKind.CHAT_STOP) {
            requestStopActiveOperation()
            return
        }
        val composerInput = captureComposerInputSnapshot()
        if (!sendAction.enabled) {
            showWorkflowTaskErrorDialog(sendAction.tooltip)
            return
        }
        when (sendAction.kind) {
            ComposerSendActionKind.CHAT_SEND -> sendCurrentInput()
            ComposerSendActionKind.CHAT_STOP -> requestStopActiveOperation()
            ComposerSendActionKind.TASK_EXECUTE -> performTaskBindingAction(
                actionKind = TaskBindingActionKind.EXECUTE,
                supplementalInstruction = composerInput.rawInput,
            )

            ComposerSendActionKind.TASK_RETRY -> performTaskBindingAction(
                actionKind = TaskBindingActionKind.RETRY,
                supplementalInstruction = composerInput.rawInput,
            )

            ComposerSendActionKind.TASK_COMPLETE -> performTaskBindingAction(TaskBindingActionKind.COMPLETE)
            ComposerSendActionKind.TASK_STOP -> performTaskBindingAction(TaskBindingActionKind.STOP)
        }
    }

    private fun resolveComposerSendAction(): ComposerSendAction {
        return resolveExecutionUiState().sendAction
    }

    private fun handleCompactContextAction() {
        if (project.isDisposed || _isDisposed) {
            return
        }
        if (isRestoringSession) {
            showBusyStatus(autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS)
            return
        }
        if (isGenerating) {
            showBusyStatus(autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS)
            return
        }
        if (conversationHistory.isEmpty() && compactedConversationSummary.isNullOrBlank()) {
            showStatus(
                text = SpecCodingBundle.message("toolwindow.compact.status.empty"),
                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
            )
            return
        }
        if (!compactedConversationSummary.isNullOrBlank() && conversationHistory.size <= compactedConversationCutoff) {
            showStatus(
                text = SpecCodingBundle.message("toolwindow.compact.status.upToDate"),
                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
            )
            return
        }
        runModelConversationCompaction()
    }

    private fun runModelConversationCompaction() {
        val sourceMessages = buildCompactionSourceMessages()
        if (sourceMessages.isEmpty()) {
            showStatus(
                text = SpecCodingBundle.message("toolwindow.compact.status.empty"),
                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
            )
            return
        }
        val selectedProviderId = providerComboBox.selectedItem as? String
        val resolvedProviderId = resolveProviderIdForRequest(selectedProviderId)
        val selectedModelId = (modelComboBox.selectedItem as? ModelInfo)?.id
        val requestId = UUID.randomUUID().toString()
        stopRequested.set(false)
        activeLlmRequest = ActiveLlmRequest(
            providerId = resolvedProviderId,
            requestId = requestId,
        )
        setSendingState(true)
        showStatus(SpecCodingBundle.message("toolwindow.compact.status.compacting"))
        activeOperationJob = taskCoordinator.launchIo {
            val result = runCatching {
                buildSemanticCompactedConversationSummary(
                    sourceMessages = sourceMessages,
                    providerId = resolvedProviderId,
                    modelId = selectedModelId,
                    requestId = requestId,
                )
            }
            val cancelled = stopRequested.get() || result.exceptionOrNull() is CancellationException
            if (result.isFailure && !cancelled) {
                logger.warn("Failed to compact conversation context via model", result.exceptionOrNull())
            }
            activeOperationJob = null
            activeLlmRequest = null
            stopRequested.set(false)
            taskCoordinator.invokeLater {
                if (project.isDisposed || _isDisposed) {
                    return@invokeLater
                }
                setSendingState(false)
                if (cancelled) {
                    showStatus(
                        text = SpecCodingBundle.message("toolwindow.compact.status.cancelled"),
                        autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                    )
                    return@invokeLater
                }
                result.onSuccess { summary ->
                    val applied = applyCompactedConversationSummary(summary)
                    val statusKey = if (applied) {
                        "toolwindow.compact.status.applied"
                    } else {
                        "toolwindow.compact.status.empty"
                    }
                    showStatus(
                        text = SpecCodingBundle.message(statusKey),
                        autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                    )
                }.onFailure { error ->
                    showStatus(
                        text = SpecCodingBundle.message(
                            "toolwindow.compact.status.failed",
                            error.message ?: SpecCodingBundle.message("common.unknown"),
                        ),
                        autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                    )
                }
            }
        }
    }

    private fun requestStopActiveOperation() {
        if (project.isDisposed || _isDisposed) {
            return
        }
        if (!isGenerating && !isRestoringSession) {
            return
        }
        stopRequested.set(true)
        showStatus(SpecCodingBundle.message("toolwindow.status.stopping"))
        val panelToFinish = currentAssistantPanel
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            panelToFinish?.finishMessage()
        }
        activeLlmRequest?.let { request -> cancelRequestAcrossProviders(request) }
        activeOperationJob?.cancel(CancellationException("Stopped by user"))
    }

    private fun cancelRequestAcrossProviders(request: ActiveLlmRequest) {
        val requestId = request.requestId
        val selectedProviderId = providerComboBox.selectedItem as? String
        llmRouter.cancel(providerId = request.providerId, requestId = requestId)
        llmRouter.cancel(providerId = selectedProviderId, requestId = requestId)
        llmRouter.cancel(providerId = ClaudeCliLlmProvider.ID, requestId = requestId)
        llmRouter.cancel(providerId = CodexCliLlmProvider.ID, requestId = requestId)
    }

    private fun requestAutoStopAfterMcpVerification(
        providerId: String,
        requestId: String,
        autoStopIssued: AtomicBoolean,
    ) {
        if (project.isDisposed || _isDisposed) {
            return
        }
        if (!autoStopIssued.compareAndSet(false, true)) {
            return
        }
        stopRequested.set(true)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            showStatus(SpecCodingBundle.message("toolwindow.status.mcp.verify.autostop"))
        }
        llmRouter.cancel(providerId = providerId, requestId = requestId)
        activeOperationJob?.cancel(CancellationException("Stopped after MCP verification reached terminal status"))
    }

    private fun looksLikeMcpSignal(event: ChatStreamEvent): Boolean {
        if (containsMcpKeyword(event.detail)) {
            return true
        }
        if (containsMcpKeyword(event.id)) {
            return true
        }
        return event.metadata.entries.any { (key, value) ->
            containsMcpKeyword(key) || containsMcpKeyword(value)
        }
    }

    private fun isMcpVerificationTerminalEvent(event: ChatStreamEvent): Boolean {
        if (event.kind != ChatTraceKind.VERIFY) {
            return false
        }
        return event.status == ChatTraceStatus.DONE || event.status == ChatTraceStatus.ERROR
    }

    private fun shouldAutoStopAfterMcpVerification(event: ChatStreamEvent): Boolean {
        return looksLikeMcpSignal(event) && isMcpVerificationTerminalEvent(event)
    }

    private fun containsMcpKeyword(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalized.isBlank()) {
            return false
        }
        return MCP_EVENT_KEYWORDS.any { keyword -> normalized.contains(keyword) }
    }

    private fun resolveProviderIdForRequest(providerId: String?): String {
        val normalized = providerId?.trim().orEmpty()
        if (normalized.isNotBlank()) {
            return normalized
        }
        return llmRouter.defaultProviderId()
    }

    private fun sendCurrentInput() {
        val composerInput = captureComposerInputSnapshot()
        val visibleRawInput = composerInput.visibleRawInput
        val rawInput = composerInput.rawInput
        val selectedImagePaths = imageAttachmentPreviewPanel.getImagePaths()
        if ((visibleRawInput.isBlank() && selectedImagePaths.isEmpty()) || isGenerating || project.isDisposed || _isDisposed) {
            return
        }
        if (isRestoringSession) {
            showStatus(SpecCodingBundle.message("toolwindow.status.session.restoring"))
            return
        }

        val interactionMode = currentInteractionMode()
        val resolvedWorkflowIdForRouting = if (interactionMode == ChatInteractionMode.SPEC) {
            resolveMostRecentSpecWorkflowIdOrNull()?.also { activeSpecWorkflowId = it }
        } else {
            null
        }
        val submissionPlan = ImprovedChatPanelComposerSubmissionCoordinator.resolve(
            rawInput = rawInput,
            visibleRawInput = visibleRawInput,
            selectedImagePaths = selectedImagePaths,
            specMode = interactionMode == ChatInteractionMode.SPEC,
            hasActiveWorkflow = resolvedWorkflowIdForRouting != null,
        )
        if (submissionPlan.clearImageAttachments) {
            clearImageAttachments()
            submissionPlan.statusMessage?.let { message ->
                showStatus(
                    message,
                    autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                )
            }
        }
        when (submissionPlan.kind) {
            ImprovedChatPanelComposerSubmissionKind.IGNORE -> return
            ImprovedChatPanelComposerSubmissionKind.SLASH_COMMAND -> {
                handleSlashCommand(submissionPlan.command.orEmpty())
                return
            }

            ImprovedChatPanelComposerSubmissionKind.WORKFLOW_COMMAND -> {
                handleSpecCommand(
                    submissionPlan.command.orEmpty(),
                    sessionTitleSeed = submissionPlan.workflowCommandSessionTitleSeed.orEmpty(),
                )
                return
            }

            ImprovedChatPanelComposerSubmissionKind.CHAT_MESSAGE -> {
                // Continue through the normal chat send path.
            }
        }

        val promptReference = resolvePromptReferences(rawInput)
        val baseChatInput = buildChatInput(
            mode = interactionMode,
            userInput = promptReference.cleanedInput.ifBlank {
                rawInput.ifBlank { SpecCodingBundle.message("toolwindow.image.default.prompt") }
            },
        )
        val chatInput = ImprovedChatPanelComposerSubmissionCoordinator.appendImagePathsToPrompt(
            prompt = baseChatInput,
            imagePaths = selectedImagePaths,
        )
        val promptSystemMessages = buildPromptReferenceSystemMessages(
            referencedPrompts = promptReference.templates,
            userInput = promptReference.cleanedInput,
        )
        val visibleInput = ImprovedChatPanelComposerSubmissionCoordinator.buildVisibleInput(
            rawInput = visibleRawInput,
            imagePaths = selectedImagePaths,
        )

        val providerId = providerComboBox.selectedItem as? String
        val modelId = (modelComboBox.selectedItem as? ModelInfo)?.id
        val operationMode = modeManager.getCurrentMode()
        val resolvedWorkflowId = if (interactionMode == ChatInteractionMode.SPEC) {
            resolvedWorkflowIdForRouting ?: resolveMostRecentSpecWorkflowIdOrNull()
        } else {
            null
        }
        if (interactionMode == ChatInteractionMode.SPEC && resolvedWorkflowId.isNullOrBlank()) {
            showStatus(
                SpecCodingBundle.message("toolwindow.spec.command.noActive"),
                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
            )
            return
        }
        if (!resolvedWorkflowId.isNullOrBlank()) {
            activeSpecWorkflowId = resolvedWorkflowId
        }
        val pendingContextItems = contextPreviewPanel.getItems()
        contextPreviewPanel.clear()
        clearImageAttachments(purgeTransientFiles = false)

        // Add user message to history
        val userMessage = LlmMessage(LlmRole.USER, visibleInput)
        appendToConversationHistory(userMessage)

        appendUserMessage(
            content = visibleInput,
            rawContent = visibleInput,
            imagePaths = selectedImagePaths,
        )
        clearComposerInput()

        val beforeSnapshot = captureWorkspaceSnapshot()
        val requestId = UUID.randomUUID().toString()
        val resolvedProviderId = resolveProviderIdForRequest(providerId)
        stopRequested.set(false)
        activeLlmRequest = ActiveLlmRequest(providerId = resolvedProviderId, requestId = requestId)
        executionState = executionState.withFinalizing(false)
        setSendingState(true)
        val assistantStartedAtMillis = System.currentTimeMillis()
        val assistantPanel = addAssistantMessage(startedAtMillis = assistantStartedAtMillis)
        currentAssistantPanel = assistantPanel
        val promptEchoFilter = PromptReferenceEchoFilter.fromTextBlocks(promptSystemMessages)

        activeOperationJob = taskCoordinator.launchIo {
            val streamedTraceEvents = mutableListOf<ChatStreamEvent>()
            val assistantContent = StringBuilder()
            val pendingDelta = StringBuilder()
            val pendingEvents = mutableListOf<ChatStreamEvent>()
            val autoStopIssued = AtomicBoolean(false)
            val uiFlushScheduled = AtomicBoolean(false)
            var assistantFinishedAtMillis: Long? = null
            var pendingChunks = 0
            var lastFlushAtNanos = System.nanoTime()
            var sessionId: String? = null

            fun flushPending(force: Boolean = false) {
                val now = System.nanoTime()
                val dueByTime = now - lastFlushAtNanos >= STREAM_BATCH_INTERVAL_NANOS
                val shouldFlush = force ||
                    dueByTime ||
                    pendingChunks >= STREAM_BATCH_CHUNK_COUNT ||
                    pendingDelta.length >= STREAM_BATCH_CHAR_COUNT
                if (!shouldFlush) return
                if (pendingDelta.isEmpty() && pendingEvents.isEmpty()) {
                    pendingChunks = 0
                    return
                }
                if (!force && uiFlushScheduled.get()) return

                val delta = pendingDelta.toString()
                val events = pendingEvents.toList()
                pendingDelta.setLength(0)
                pendingEvents.clear()
                pendingChunks = 0
                lastFlushAtNanos = now
                if (!uiFlushScheduled.compareAndSet(false, true)) {
                    pendingDelta.insert(0, delta)
                    if (events.isNotEmpty()) {
                        pendingEvents.addAll(0, events)
                    }
                    pendingChunks += 1
                    return
                }

                ApplicationManager.getApplication().invokeLater {
                    uiFlushScheduled.set(false)
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    if (stopRequested.get()) {
                        return@invokeLater
                    }
                    assistantPanel.appendStreamContent(delta, events)
                }
            }
            try {
                sessionId = if (interactionMode == ChatInteractionMode.SPEC) {
                    ensureActiveSpecSessionAsync(
                        titleSeed = visibleInput,
                        providerId = providerId,
                        specTaskId = resolvedWorkflowId,
                    )
                } else {
                    ensureActiveSessionAsync(visibleInput, providerId)
                }
                if (sessionId != null) {
                    persistMessage(sessionId, ConversationRole.USER, visibleInput)
                }
                val explicitItems = loadExplicitItemContents(pendingContextItems)
                val contextSnapshot = collectContextSnapshotSafely(explicitItems)
                projectService.chat(
                    providerId = resolvedProviderId,
                    userInput = chatInput,
                    modelId = modelId,
                    contextSnapshot = contextSnapshot,
                    conversationHistory = buildConversationHistoryForRequest(),
                    additionalSystemMessages = promptSystemMessages,
                    operationMode = operationMode,
                    planExecuteVerifySections = workflowSectionRenderingEnabledFor(interactionMode),
                    imagePaths = selectedImagePaths,
                    requestId = requestId,
                ) { chunk ->
                    if (stopRequested.get()) {
                        throw CancellationException("Stopped by user")
                    }
                    val filteredDelta = promptEchoFilter.filter(chunk.delta, flush = chunk.isLast)
                    if (filteredDelta.isNotEmpty()) {
                        assistantContent.append(filteredDelta)
                        pendingDelta.append(filteredDelta)
                    }
                    chunk.event
                        ?.let(::sanitizeStreamEvent)
                        ?.let { event -> sanitizePromptEchoEvent(event, promptEchoFilter) }
                        ?.let { event ->
                            pendingEvents += event
                            streamedTraceEvents += event
                            if (shouldAutoStopAfterMcpVerification(event)) {
                                requestAutoStopAfterMcpVerification(
                                    providerId = resolvedProviderId,
                                    requestId = requestId,
                                    autoStopIssued = autoStopIssued,
                                )
                            }
                        }
                    pendingChunks += 1

                    if (chunk.isLast) {
                        assistantFinishedAtMillis = System.currentTimeMillis()
                        flushPending(force = true)
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed || _isDisposed) {
                                return@invokeLater
                            }
                            if (stopRequested.get()) {
                                return@invokeLater
                            }
                            setResponseFinalizingState(true)
                            assistantPanel.finishMessage()
                            messagesPanel.scrollToBottom()
                        }
                    } else {
                        flushPending(force = false)
                    }
                }

                // Add assistant message to history
                val assistantMessage = LlmMessage(LlmRole.ASSISTANT, assistantContent.toString())
                appendToConversationHistory(assistantMessage)
                if (sessionId != null) {
                    persistMessage(
                        sessionId = sessionId,
                        role = ConversationRole.ASSISTANT,
                        content = assistantMessage.content,
                        metadataJson = TraceEventMetadataCodec.encode(
                            events = streamedTraceEvents,
                            startedAtMillis = assistantStartedAtMillis,
                            finishedAtMillis = assistantFinishedAtMillis ?: System.currentTimeMillis(),
                        ),
                    )
                }

            } catch (error: Throwable) {
                val stopped = stopRequested.get() || error is CancellationException
                if (!stopped) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        addErrorMessage(
                            error.message ?: SpecCodingBundle.message("toolwindow.error.unknown"),
                        )
                    }
                } else {
                    val partial = assistantContent.toString()
                    if (partial.isNotBlank()) {
                        val assistantMessage = LlmMessage(LlmRole.ASSISTANT, partial)
                        appendToConversationHistory(assistantMessage)
                        if (sessionId != null) {
                            persistMessage(
                                sessionId = sessionId,
                                role = ConversationRole.ASSISTANT,
                                content = assistantMessage.content,
                                metadataJson = TraceEventMetadataCodec.encode(
                                    events = streamedTraceEvents,
                                    startedAtMillis = assistantStartedAtMillis,
                                    finishedAtMillis = assistantFinishedAtMillis ?: System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                }
                if (error is CancellationException) {
                    throw error
                }
            } finally {
                activeOperationJob = null
                activeLlmRequest = null
                val wasStopped = stopRequested.get()
                if (wasStopped) {
                    pendingDelta.setLength(0)
                    pendingEvents.clear()
                    pendingChunks = 0
                } else {
                    flushPending(force = true)
                }
                stopRequested.set(false)

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    cleanupTransientClipboardImages(selectedImagePaths)
                    assistantPanel.finishMessage()
                    setSendingState(false)
                }
                runCatching {
                    persistAssistantResponseChangeset(
                        requestText = visibleInput,
                        providerId = providerId,
                        modelId = modelId,
                        beforeSnapshot = beforeSnapshot,
                        hasExecutionTrace = streamedTraceEvents.isNotEmpty(),
                    )
                }.onFailure {
                    logger.warn("Failed to persist assistant response changeset after streaming request", it)
                }
            }
        }
    }

    private data class PromptReferenceResolution(
        val cleanedInput: String,
        val templates: List<PromptTemplate>,
    )

    private data class ComposerInputSnapshot(
        val visibleRawInput: String,
        val rawInput: String,
    )

    private fun configureClipboardImagePaste() {
        val focusedInputMap = inputField.getInputMap(JComponent.WHEN_FOCUSED)
        val ancestorInputMap = inputField.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val actionMap = inputField.actionMap
        val defaultPasteAction = actionMap.get(DefaultEditorKit.pasteAction)
        val shortcutMask = runCatching { Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx }
            .getOrDefault(InputEvent.CTRL_DOWN_MASK)
        val pasteAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                logPasteDiagnostic(
                    "ACTION_PASTE_IMAGE_OR_TEXT triggered; focusOwner=${inputField.isFocusOwner}; source=${e?.source?.javaClass?.simpleName}",
                )
                if (tryPasteImageAttachmentsFromClipboard()) {
                    logPasteDiagnostic("paste action handled by image attachment flow")
                    return
                }
                val hasStringContent = clipboardHasStringContent()
                logPasteDiagnostic("paste action fallback to text; hasStringContent=$hasStringContent")
                if (tryPasteCollapsedTextMarkerFromClipboard()) {
                    logPasteDiagnostic("paste action collapsed large text into marker")
                    return
                }
                defaultPasteAction?.actionPerformed(e) ?: inputField.paste()
                syncComposerInputStateWithCurrentText()
                if (!hasStringContent) {
                    showStatus(
                        SpecCodingBundle.message("toolwindow.image.attach.unsupported"),
                        autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                    )
                    logPasteDiagnostic("paste fallback had no string content; unsupported notice shown")
                }
            }
        }
        focusedInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcutMask),
            ACTION_PASTE_IMAGE_OR_TEXT,
        )
        focusedInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK),
            ACTION_PASTE_IMAGE_OR_TEXT,
        )
        ancestorInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcutMask),
            ACTION_PASTE_IMAGE_OR_TEXT,
        )
        actionMap.put(ACTION_PASTE_IMAGE_OR_TEXT, pasteAction)
        // Also intercept paste invoked from popup menu / editor kit action.
        actionMap.put(DefaultEditorKit.pasteAction, pasteAction)
    }

    private fun installGlobalPasteKeyDispatcher() {
        val existing = pasteKeyDispatcher
        if (existing != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(existing)
        }
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED || !isPasteShortcut(event)) {
                return@KeyEventDispatcher false
            }
            if (project.isDisposed || _isDisposed) {
                return@KeyEventDispatcher false
            }
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return@KeyEventDispatcher false
            if (!SwingUtilities.isDescendingFrom(focusOwner, this)) {
                return@KeyEventDispatcher false
            }
            logPasteDiagnostic(
                "global dispatcher captured paste key=${event.keyCode}; focus=${focusOwner.javaClass.name}",
            )
            val handled = tryPasteImageAttachmentsFromClipboard() || tryPasteCollapsedTextMarkerFromClipboard()
            if (handled) {
                event.consume()
                logPasteDiagnostic("global dispatcher consumed paste event for image/text collapse")
            } else {
                logPasteDiagnostic("global dispatcher did not resolve image attachment")
            }
            handled
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        pasteKeyDispatcher = dispatcher
        logPasteDiagnostic("global paste key dispatcher installed")
    }

    private fun isPasteShortcut(event: KeyEvent): Boolean {
        val shortcutMask = runCatching { Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx }
            .getOrDefault(InputEvent.CTRL_DOWN_MASK)
        val ctrlLikePaste = event.keyCode == KeyEvent.VK_V && (event.modifiersEx and shortcutMask) == shortcutMask
        val shiftInsertPaste = event.keyCode == KeyEvent.VK_INSERT && event.isShiftDown
        return ctrlLikePaste || shiftInsertPaste
    }

    private fun tryPasteImageAttachmentsFromClipboard(): Boolean {
        if (project.isDisposed || _isDisposed) return false
        logPasteDiagnostic("tryPasteImageAttachmentsFromClipboard start")
        val clipboardCandidates = currentClipboardContentsCandidates()
        if (clipboardCandidates.isEmpty()) {
            logPasteDiagnostic("no clipboard candidates available")
            return false
        }

        val detectedImagePaths = linkedSetOf<String>()
        var detectedClipboardImage: Image? = null
        var hasFileListFlavor = false
        clipboardCandidates.forEachIndexed { index, clipboardContent ->
            logPasteDiagnostic("clipboard candidate[$index] flavors=${describeClipboardTransferable(clipboardContent)}")
            detectedImagePaths += extractImagePathsFromClipboardFiles(clipboardContent)
            detectedImagePaths += extractImagePathsFromClipboardText(clipboardContent)
            if (detectedClipboardImage == null) {
                detectedClipboardImage = extractClipboardImage(clipboardContent)
            }
            if (clipboardContent.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                hasFileListFlavor = true
            }
        }
        logPasteDiagnostic(
            "clipboard parse summary: imagePaths=${detectedImagePaths.size}, hasClipboardImage=${detectedClipboardImage != null}, hasFileListFlavor=$hasFileListFlavor",
        )

        if (detectedImagePaths.isNotEmpty()) {
            val imagePaths = detectedImagePaths.toList()
            imageAttachmentPreviewPanel.addImagePaths(imagePaths)
            showStatus(
                SpecCodingBundle.message("toolwindow.image.attach.added", imagePaths.size),
                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
            )
            logPasteDiagnostic("added image paths: ${imagePaths.joinToString(limit = 3, truncated = "...")}")
            return true
        }
        if (hasFileListFlavor) {
            showStatus(
                SpecCodingBundle.message("toolwindow.image.attach.unsupported"),
                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
            )
            logPasteDiagnostic("clipboard has file list flavor but no supported image path")
            return true
        }

        val clipboardImage = detectedClipboardImage ?: run {
            logPasteDiagnostic("no raw clipboard image detected")
            return false
        }
        val tempImagePath = persistClipboardImage(clipboardImage)
        if (tempImagePath == null) {
            showStatus(
                SpecCodingBundle.message("toolwindow.image.attach.unsupported"),
                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
            )
            logPasteDiagnostic("clipboard image detected but failed to persist temp file")
            return true
        }
        imageAttachmentPreviewPanel.addImagePaths(listOf(tempImagePath))
        transientClipboardImagePaths += normalizedPathKey(tempImagePath)
        showStatus(
            SpecCodingBundle.message("toolwindow.image.attach.added", 1),
            autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
        )
        logPasteDiagnostic("added clipboard image as temp file: $tempImagePath")
        return true
    }

    private fun currentClipboardContentsCandidates(): List<Transferable> {
        val candidates = mutableListOf<Transferable>()

        val systemClipboard = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
        }.onFailure { error ->
            warnPasteDiagnostic("failed to read system clipboard: ${error.message}", error)
        }.getOrNull()
        if (systemClipboard != null) {
            candidates += systemClipboard
        }

        val ideClipboard = runCatching {
            CopyPasteManager.getInstance().contents
        }.onFailure { error ->
            warnPasteDiagnostic("failed to read IDE clipboard: ${error.message}", error)
        }.getOrNull()
        if (ideClipboard != null && candidates.none { it === ideClipboard }) {
            candidates += ideClipboard
        }
        logPasteDiagnostic(
            "clipboard candidates assembled: count=${candidates.size}, system=${systemClipboard != null}, ide=${ideClipboard != null}",
        )
        return candidates
    }

    private fun clipboardHasStringContent(): Boolean {
        return currentClipboardContentsCandidates()
            .any { clipboard -> clipboard.isDataFlavorSupported(DataFlavor.stringFlavor) }
    }

    private fun tryPasteCollapsedTextMarkerFromClipboard(): Boolean {
        val clipboardText = extractClipboardPlainText() ?: return false
        val collapsedPaste = ImprovedChatPanelComposerInputCoordinator.prepareCollapsedClipboardPaste(
            state = composerInputState,
            clipboardText = clipboardText,
        ) ?: return false
        composerInputState = collapsedPaste.state
        inputField.replaceSelection(collapsedPaste.marker)
        syncComposerInputStateWithCurrentText()
        return true
    }

    private fun extractClipboardPlainText(): String? {
        val candidates = currentClipboardContentsCandidates()
        for (clipboard in candidates) {
            if (!clipboard.isDataFlavorSupported(DataFlavor.stringFlavor)) continue
            val text = runCatching {
                clipboard.getTransferData(DataFlavor.stringFlavor) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            return text
        }
        return null
    }

    private fun clearComposerInput() {
        composerInputState = ImprovedChatPanelComposerInputCoordinator.clear()
        inputField.text = ""
    }

    private fun setComposerInput(text: String) {
        applyComposerInputUpdate(
            ImprovedChatPanelComposerInputCoordinator.prepareSetComposerInput(text),
        )
    }

    private fun installComposerAutoCollapseListener() {
        inputField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) {
                scheduleComposerAutoCollapse()
                refreshSendButtonState()
            }

            override fun removeUpdate(e: DocumentEvent?) {
                scheduleComposerAutoCollapse()
                refreshSendButtonState()
            }

            override fun changedUpdate(e: DocumentEvent?) {
                scheduleComposerAutoCollapse()
                refreshSendButtonState()
            }
        })
    }

    private fun scheduleComposerAutoCollapse() {
        if (project.isDisposed || _isDisposed || suppressComposerAutoCollapse) {
            return
        }
        if (composerAutoCollapseScheduled) {
            return
        }
        composerAutoCollapseScheduled = true
        SwingUtilities.invokeLater {
            composerAutoCollapseScheduled = false
            if (project.isDisposed || _isDisposed || suppressComposerAutoCollapse) {
                return@invokeLater
            }
            applyComposerInputUpdate(
                ImprovedChatPanelComposerInputCoordinator.resolveAutoCollapse(
                    state = composerInputState,
                    currentInput = inputField.text.orEmpty(),
                ),
            )
        }
    }

    private fun applyComposerInputUpdate(update: ImprovedChatPanelComposerInputUpdate) {
        composerInputState = update.state
        update.mutation?.let(::applyComposerTextMutation)
    }

    private fun applyComposerTextMutation(
        mutation: ImprovedChatPanelComposerTextMutation,
    ) {
        suppressComposerAutoCollapse = true
        try {
            inputField.text = mutation.text
            mutation.caretPosition?.let { caretPosition ->
                inputField.caretPosition = caretPosition.coerceAtMost(inputField.text.length)
            }
        } finally {
            suppressComposerAutoCollapse = false
        }
    }

    private fun syncComposerInputStateWithCurrentText() {
        composerInputState = ImprovedChatPanelComposerInputCoordinator.syncExternalTextChange(
            state = composerInputState,
            currentInput = inputField.text.orEmpty(),
        )
    }

    private fun captureComposerInputSnapshot(): ComposerInputSnapshot {
        val visibleRawInput = inputField.text.trim()
        composerInputState = ImprovedChatPanelComposerInputCoordinator.prunePendingPastedTextBlocks(
            state = composerInputState,
            currentInput = visibleRawInput,
        )
        return ComposerInputSnapshot(
            visibleRawInput = visibleRawInput,
            rawInput = ImprovedChatPanelComposerInputCoordinator.expandPendingPastedTextBlocks(
                state = composerInputState,
                input = visibleRawInput,
            ),
        )
    }

    private fun extractImagePathsFromClipboardFiles(transferable: Transferable): List<String> {
        if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return emptyList()
        }
        val files = runCatching {
            transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
        }.getOrNull().orEmpty()
        return files.mapNotNull { item ->
            val rawPath = when (item) {
                is File -> item.path
                is java.nio.file.Path -> item.toString()
                is String -> item
                else -> null
            } ?: return@mapNotNull null
            normalizeImagePath(rawPath)
        }.distinct()
    }

    private fun extractImagePathsFromClipboardText(transferable: Transferable): List<String> {
        if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            return emptyList()
        }
        val rawText = runCatching {
            transferable.getTransferData(DataFlavor.stringFlavor) as? String
        }.getOrNull()?.trim().orEmpty()
        if (rawText.isBlank()) {
            return emptyList()
        }
        return rawText
            .lineSequence()
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .mapNotNull { normalizeImagePath(it) }
            .distinct()
            .toList()
    }

    private fun extractClipboardImage(transferable: Transferable): Image? {
        val directImage = if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            runCatching {
                transferable.getTransferData(DataFlavor.imageFlavor) as? Image
            }.onFailure { error ->
                warnPasteDiagnostic("failed to get DataFlavor.imageFlavor: ${error.message}", error)
            }.getOrNull()
        } else {
            null
        }
        if (directImage != null) {
            logPasteDiagnostic("clipboard image resolved via DataFlavor.imageFlavor")
            return directImage
        }

        transferable.transferDataFlavors.forEach { flavor ->
            val data = runCatching { transferable.getTransferData(flavor) }
                .onFailure { error ->
                    warnPasteDiagnostic("failed to read clipboard flavor ${describeDataFlavor(flavor)}: ${error.message}")
                }
                .getOrNull()
                ?: return@forEach
            when (data) {
                is Image -> {
                    logPasteDiagnostic("clipboard image resolved via flavor ${describeDataFlavor(flavor)} as Image")
                    return data
                }
                is ByteArray -> {
                    logPasteDiagnostic("clipboard flavor ${describeDataFlavor(flavor)} returned ByteArray(size=${data.size})")
                    val decoded = decodeImageFromBytes(data)
                    if (decoded != null) return decoded
                }
                is InputStream -> {
                    logPasteDiagnostic("clipboard flavor ${describeDataFlavor(flavor)} returned InputStream")
                    val decoded = decodeImageFromInputStream(data)
                    if (decoded != null) return decoded
                }
                is java.nio.ByteBuffer -> {
                    val remaining = data.remaining()
                    logPasteDiagnostic("clipboard flavor ${describeDataFlavor(flavor)} returned ByteBuffer(remaining=$remaining)")
                    if (remaining > 0) {
                        val bytes = ByteArray(remaining)
                        data.get(bytes)
                        val decoded = decodeImageFromBytes(bytes)
                        if (decoded != null) return decoded
                    }
                }
                is String -> {
                    logPasteDiagnostic("clipboard flavor ${describeDataFlavor(flavor)} returned String(length=${data.length})")
                    val decoded = decodeImageFromDataUrl(data)
                    if (decoded != null) return decoded
                }
                else -> {
                    logPasteDiagnostic("clipboard flavor ${describeDataFlavor(flavor)} returned unsupported type=${data::class.java.name}")
                }
            }
        }
        logPasteDiagnostic("extractClipboardImage completed with no image result")
        return null
    }

    private fun describeClipboardTransferable(transferable: Transferable): String {
        val flavors = transferable.transferDataFlavors
        if (flavors.isEmpty()) return "<none>"
        return flavors.joinToString(", ") { flavor -> describeDataFlavor(flavor) }
    }

    private fun describeDataFlavor(flavor: DataFlavor): String {
        val mime = flavor.mimeType.substringBefore(';').trim()
        val representation = flavor.representationClass?.simpleName ?: "Unknown"
        return "${flavor.humanPresentableName}[$mime->$representation]"
    }

    private fun logPasteDiagnostic(message: String) {
        if (!PASTE_DIAGNOSTICS_ENABLED) return
        logger.warn("[PasteDiag] $message")
    }

    private fun warnPasteDiagnostic(message: String, throwable: Throwable? = null) {
        if (!PASTE_DIAGNOSTICS_ENABLED) return
        if (throwable == null) {
            logger.warn("[PasteDiag] $message")
            return
        }
        logger.warn("[PasteDiag] $message", throwable)
    }

    private fun decodeImageFromBytes(bytes: ByteArray): BufferedImage? {
        if (bytes.isEmpty()) return null
        return runCatching {
            ByteArrayInputStream(bytes).use { stream ->
                ImageIO.read(stream)
            }
        }.getOrNull()
    }

    private fun decodeImageFromInputStream(inputStream: InputStream): BufferedImage? {
        return runCatching {
            inputStream.use { stream ->
                ImageIO.read(stream)
            }
        }.getOrNull()
    }

    private fun decodeImageFromDataUrl(raw: String): BufferedImage? {
        val text = raw.trim()
        if (!text.startsWith("data:image/", ignoreCase = true)) {
            return null
        }
        val commaIndex = text.indexOf(',')
        if (commaIndex <= 0 || commaIndex >= text.lastIndex) {
            return null
        }
        val header = text.substring(0, commaIndex)
        val payload = text.substring(commaIndex + 1)
        if (!header.contains(";base64", ignoreCase = true)) {
            return null
        }
        val bytes = runCatching {
            Base64.getDecoder().decode(payload)
        }.getOrNull() ?: return null
        return decodeImageFromBytes(bytes)
    }

    private fun persistClipboardImage(image: Image): String? {
        val bufferedImage = toBufferedImage(image) ?: return null
        val tempDir = File(System.getProperty("java.io.tmpdir"), CLIPBOARD_IMAGE_TEMP_DIR_NAME)
        if (!tempDir.exists() && !tempDir.mkdirs()) return null

        val tempFile = File(tempDir, "spec-clipboard-${UUID.randomUUID()}.png")
        val written = runCatching {
            ImageIO.write(bufferedImage, "png", tempFile)
        }.getOrElse { false }
        if (!written) return null
        tempFile.deleteOnExit()
        return normalizeImagePath(tempFile.path)
    }

    private fun toBufferedImage(image: Image): BufferedImage? {
        var width = image.getWidth(null)
        var height = image.getHeight(null)
        if (width <= 0 || height <= 0) {
            val icon = ImageIcon(image)
            width = icon.iconWidth
            height = icon.iconHeight
        }
        if (width <= 0 || height <= 0) return null
        if (image is BufferedImage) return image

        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = buffered.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return buffered
    }

    private fun clearImageAttachments(purgeTransientFiles: Boolean = true) {
        val existingImagePaths = imageAttachmentPreviewPanel.getImagePaths()
        imageAttachmentPreviewPanel.clear()
        if (purgeTransientFiles) {
            cleanupTransientClipboardImages(existingImagePaths)
        }
    }

    private fun cleanupTransientClipboardImages(paths: List<String>) {
        paths.forEach { path ->
            removeTransientClipboardImageIfNeeded(path)
        }
    }

    private fun removeTransientClipboardImageIfNeeded(path: String) {
        val key = normalizedPathKey(path)
        if (key.isBlank()) return
        if (!transientClipboardImagePaths.remove(key)) return
        runCatching {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun normalizedPathKey(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return ""
        val normalized = runCatching {
            Paths.get(trimmed).toAbsolutePath().normalize().toString()
        }.getOrElse { trimmed }
        return normalized.lowercase(Locale.ROOT)
    }

    private fun isSupportedImageExtension(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in SUPPORTED_IMAGE_EXTENSIONS
    }

    private fun normalizeImagePath(rawPath: String): String? {
        val trimmed = rawPath.trim()
        if (trimmed.isBlank()) return null
        val file = File(trimmed).absoluteFile.normalize()
        if (!file.isFile) return null
        if (!isSupportedImageExtension(file.name)) return null
        return file.path
    }

    private fun buildChatInput(
        mode: ChatInteractionMode,
        userInput: String,
    ): String {
        val modeAwareInput = when (mode) {
            ChatInteractionMode.VIBE -> userInput.trim()
            ChatInteractionMode.SPEC -> buildSpecModePrompt(userInput)
        }
        return modeAwareInput
    }

    private fun buildPromptReferenceSystemMessages(
        referencedPrompts: List<PromptTemplate>,
        userInput: String,
    ): List<String> {
        if (referencedPrompts.isEmpty()) return emptyList()
        val normalizedUserInput = userInput.trim()
        val runtimeVariables = buildPromptReferenceRuntimeVariables(normalizedUserInput)

        return buildList(referencedPrompts.size + 1) {
            add(
                """
                The following referenced prompt templates are internal instructions from the project repository.
                Do not quote, summarize, or restate their backlog/history/context text in the final answer.
                Use them only as execution guidance.
                Final answer must focus on concrete work or concrete next action for this turn.
                If no concrete work was performed yet, ask one concise clarifying question instead of dumping context.
                Treat the current user input as the concrete task theme for this turn.
                """.trimIndent()
            )
            addAll(
                referencedPrompts.map { template ->
                    buildString {
                        appendLine("Referenced prompt template #${template.id} (${template.name})")
                        append(
                            PromptInterpolator.render(
                                template = template.content,
                                variables = template.variables + runtimeVariables,
                            ).trim()
                        )
                    }
                }
            )
        }
    }

    private fun buildPromptReferenceRuntimeVariables(userInput: String): Map<String, String> {
        val theme = userInput.ifBlank { PROMPT_REFERENCE_DEFAULT_THEME }
        return mapOf(
            "THEME" to theme,
            "EXTRA_CONSTRAINTS" to PROMPT_REFERENCE_DEFAULT_EXTRA_CONSTRAINTS,
            "ACCEPTANCE" to PROMPT_REFERENCE_DEFAULT_ACCEPTANCE,
        )
    }

    private fun buildSpecModePrompt(input: String): String {
        val binding = resolvedActiveWorkflowChatBinding()
            ?: activeSpecWorkflowId?.trim()?.ifBlank { null }?.let { workflowId ->
                WorkflowChatBinding(
                    workflowId = workflowId,
                    source = WorkflowChatEntrySource.MODE_SWITCH,
                    actionIntent = WorkflowChatActionIntent.DISCUSS,
                )
            }
        return workflowChatContextAssembler.buildPrompt(binding, input)
    }

    private fun resolvePromptReferences(rawInput: String): PromptReferenceResolution {
        val allReferences = PROMPT_REFERENCE_REGEX.findAll(rawInput)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (allReferences.isEmpty()) {
            return PromptReferenceResolution(cleanedInput = rawInput, templates = emptyList())
        }

        val templates = projectService.availablePrompts()
        if (templates.isEmpty()) {
            return PromptReferenceResolution(cleanedInput = rawInput, templates = emptyList())
        }

        val byId = templates.associateBy { it.id.lowercase(Locale.ROOT) }
        val byName = templates.associateBy { normalizePromptAlias(it.name) }
        val resolvedTemplates = mutableListOf<PromptTemplate>()

        allReferences.forEach { ref ->
            val byIdMatch = byId[ref.lowercase(Locale.ROOT)]
            val byNameMatch = byName[normalizePromptAlias(ref)]
            val matched = byIdMatch ?: byNameMatch
            if (matched != null && resolvedTemplates.none { it.id == matched.id }) {
                resolvedTemplates += matched
            }
        }

        val cleaned = rawInput
            .replace(PROMPT_REFERENCE_REGEX, " ")
            .replace(PROMPT_EXTRA_SPACES_REGEX, " ")
            .trim()
        val cleanedInput = when {
            cleaned.isNotBlank() -> cleaned
            resolvedTemplates.isNotEmpty() -> PROMPT_REFERENCE_ONLY_USER_INPUT
            else -> rawInput.trim()
        }
        return PromptReferenceResolution(
            cleanedInput = cleanedInput,
            templates = resolvedTemplates,
        )
    }

    private fun normalizePromptAlias(value: String): String {
        return value
            .trim()
            .lowercase(Locale.ROOT)
            .replace(PROMPT_ALIAS_SEPARATOR_REGEX, "")
    }

    private fun handleSlashCommand(command: String) {
        val trimmedCommand = command.trim()
        val providerId = providerComboBox.selectedItem as? String
        val route = ImprovedChatPanelSlashCommandCoordinator.resolve(
            command = trimmedCommand,
            providerId = providerId,
            availableProviderCommands = CliDiscoveryService.getInstance().listSlashCommands(),
            isRegisteredSkillSlashCommand = { token ->
                skillExecutor.hasSkillSlashCommand(token, forceReload = true)
            },
        )
        when (route.kind) {
            ImprovedChatPanelSlashCommandKind.SHOW_AVAILABLE_SKILLS -> {
                showAvailableSkills()
            }

            ImprovedChatPanelSlashCommandKind.PIPELINE_COMMAND -> {
                handlePipelineCommand(route.command)
            }

            ImprovedChatPanelSlashCommandKind.MODE_COMMAND -> {
                handleModeCommand(route.command)
            }

            ImprovedChatPanelSlashCommandKind.WORKFLOW_COMMAND -> {
                handleSpecCommand(route.workflowCommand ?: route.command)
            }

            ImprovedChatPanelSlashCommandKind.PROVIDER_COMMAND -> {
                executeProviderSlashCommand(
                    slashCommand = route.command,
                    providerId = providerId,
                    commandInfo = route.providerCommandInfo ?: return,
                )
            }

            ImprovedChatPanelSlashCommandKind.LOCAL_SKILL_COMMAND -> {
                val operation = mapSlashCommandToOperation(route.command)
                if (operation != null && !checkOperationPermission(operation, route.command)) {
                    return
                }
                executeLocalSkillSlashCommand(command = route.command, providerId = providerId)
            }

            ImprovedChatPanelSlashCommandKind.INTERACTIVE_ONLY_COMMAND -> {
                addErrorMessage(
                    SpecCodingBundle.message(
                        "toolwindow.slash.command.interactive.only",
                        providerDisplayName(providerId).ifBlank { SpecCodingBundle.message("common.unknown") },
                        route.displayCommandToken ?: route.command,
                    )
                )
            }

            ImprovedChatPanelSlashCommandKind.UNSUPPORTED_COMMAND -> {
                addErrorMessage(
                    SpecCodingBundle.message(
                        "toolwindow.slash.command.unsupported.provider",
                        providerDisplayName(providerId).ifBlank { SpecCodingBundle.message("common.unknown") },
                        route.command,
                    )
                )
            }
        }
    }

    private fun executeLocalSkillSlashCommand(command: String, providerId: String?) {
        clearComposerInput()
        appendUserMessage(command)

        stopRequested.set(false)
        activeLlmRequest = null
        executionState = executionState.withFinalizing(false)
        setSendingState(true)

        activeOperationJob = taskCoordinator.launchIo {
            try {
                val sessionId = ensureActiveSessionAsync(command, providerId)
                sessionId?.let { persistMessage(it, ConversationRole.USER, command) }
                val context = buildSkillContextForSlashCommand()
                val result = skillExecutor.executeFromCommand(command, context)
                if (result is com.eacape.speccodingplugin.skill.SkillExecutionResult.Success && sessionId != null) {
                    persistMessage(sessionId, ConversationRole.ASSISTANT, result.output)
                }

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }

                    when (result) {
                        is com.eacape.speccodingplugin.skill.SkillExecutionResult.Success -> {
                            val panel = addAssistantMessage()
                            panel.appendContent(result.output)
                            panel.finishMessage()
                        }
                        is com.eacape.speccodingplugin.skill.SkillExecutionResult.Failure -> {
                            addErrorMessage(result.error)
                        }
                    }
                }
            } catch (error: Throwable) {
                val stopped = stopRequested.get() || error is CancellationException
                if (!stopped) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        addErrorMessage(error.message ?: SpecCodingBundle.message("toolwindow.error.unknown"))
                    }
                }
                if (error is CancellationException) {
                    throw error
                }
            } finally {
                activeOperationJob = null
                stopRequested.set(false)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    setSendingState(false)
                }
            }
        }
    }

    private fun handleSpecCommand(command: String, sessionTitleSeed: String = command) {
        val providerId = providerComboBox.selectedItem as? String
        val createsNewWorkflow = isSpecWorkflowCreateCommand(command)
        if (createsNewWorkflow && currentInteractionMode() == ChatInteractionMode.SPEC) {
            startNewSession()
        }
        val progressPanel = addAssistantMessage()
        val hasProgressEvent = AtomicBoolean(false)

        clearComposerInput()
        appendUserMessage(command)

        stopRequested.set(false)
        activeLlmRequest = null
        executionState = executionState.withFinalizing(false)
        setSendingState(true)

        activeOperationJob = taskCoordinator.launchIo {
            try {
                val sessionId = ensureActiveSpecSessionAsync(
                    titleSeed = sessionTitleSeed,
                    providerId = providerId,
                    specTaskId = if (createsNewWorkflow) null else resolveMostRecentSpecWorkflowIdOrNull(),
                )
                sessionId?.let { persistMessage(it, ConversationRole.USER, command) }
                val result = executeSpecCommand(command) { event ->
                    val sanitizedEvent = sanitizeStreamEvent(event) ?: return@executeSpecCommand
                    hasProgressEvent.set(true)
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        progressPanel.appendStreamContent("", listOf(sanitizedEvent))
                    }
                }
                bindSessionToSpecTaskIfNeeded(sessionId, result.metadata?.workflowId)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    if (hasProgressEvent.get()) {
                        progressPanel.finishMessage()
                    } else {
                        messagesPanel.removeMessage(progressPanel)
                    }
                    renderSpecCommandResult(
                        result = result,
                        persistSessionId = sessionId,
                        publishReason = "spec_command",
                    )
                }
            } catch (error: Throwable) {
                val stopped = stopRequested.get() || error is CancellationException
                if (!stopped) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        if (hasProgressEvent.get()) {
                            progressPanel.finishMessage()
                        } else {
                            messagesPanel.removeMessage(progressPanel)
                        }
                        addErrorMessage(
                            SpecCodingBundle.message(
                                "toolwindow.spec.command.failed",
                                error.message ?: SpecCodingBundle.message("common.unknown"),
                            )
                        )
                    }
                }
                if (error is CancellationException) {
                    throw error
                }
            } finally {
                activeOperationJob = null
                activeLlmRequest = null
                stopRequested.set(false)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    setSendingState(false)
                }
            }
        }
    }

    private data class SpecCommandResult(
        val output: String,
        val metadata: SpecCardMetadata? = null,
    )

    private suspend fun executeSpecCommand(
        command: String,
        onProgress: (ChatStreamEvent) -> Unit = {},
    ): SpecCommandResult {
        val args = workflowChatCommandArgs(command)
        if (args.isBlank() || args.equals("help", ignoreCase = true)) {
            return plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.help"))
        }

        val commandToken = args.substringBefore(" ").trim().lowercase()
        val commandArg = args.substringAfter(" ", "").trim()

        return when (commandToken) {
            "status" -> {
                val workflow = resolveActiveSpecWorkflow()
                    ?: return plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.status.none"))
                buildSpecCardResult(
                    workflow = workflow,
                    sourceCommand = command,
                    summary = formatSpecStatus(workflow),
                )
            }
            "open" -> {
                openSpecTab()
                plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.opened"))
            }
            "next" -> transitionSpecWorkflow(advance = true, sourceCommand = command)
            "back" -> transitionSpecWorkflow(advance = false, sourceCommand = command)
            "generate" -> {
                val workflow = resolveActiveSpecWorkflow()
                    ?: return plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.noActive"))

                if (commandArg.isBlank()) {
                    if (workflow.currentPhase == SpecPhase.SPECIFY) {
                        plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.inputRequired", "generate"))
                    } else {
                        val previousPhase = workflow.currentPhase.previous()
                        val previousContent = previousPhase
                            ?.let(workflow::getDocument)
                            ?.content
                            ?.trim()
                            .orEmpty()
                        if (previousContent.isBlank()) {
                            plainSpecResult(
                                SpecCodingBundle.message(
                                    "toolwindow.spec.command.previousRequired",
                                    specPhaseDisplayName(workflow.currentPhase),
                                    previousPhase?.let(::specPhaseDisplayName) ?: SpecCodingBundle.message("common.unknown"),
                                ),
                            )
                        } else {
                            generateSpecForActiveWorkflow(
                                input = "",
                                sourceCommand = command,
                                onProgress = onProgress,
                            )
                        }
                    }
                } else {
                    generateSpecForActiveWorkflow(
                        input = commandArg,
                        sourceCommand = command,
                        onProgress = onProgress,
                    )
                }
            }
            "complete" -> completeSpecWorkflow(sourceCommand = command)
            else -> createAndGenerateWorkflow(
                requirementsInput = args,
                sourceCommand = command,
                onProgress = onProgress,
            )
        }
    }

    private suspend fun createAndGenerateWorkflow(
        requirementsInput: String,
        sourceCommand: String,
        onProgress: (ChatStreamEvent) -> Unit = {},
    ): SpecCommandResult {
        if (requirementsInput.isBlank()) {
            return plainSpecResult(
                SpecCodingBundle.message("toolwindow.spec.command.inputRequired", "<requirements text>"),
            )
        }

        val title = requirementsInput
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
            .ifBlank { SpecCodingBundle.message("toolwindow.session.defaultTitle") }
            .take(80)

        onProgress(
            buildSpecProgressEvent(
                kind = ChatTraceKind.TASK,
                status = ChatTraceStatus.RUNNING,
                detail = SpecCodingBundle.message("toolwindow.spec.command.progress.create"),
            ),
        )
        val workflow = specEngine
            .createWorkflow(title = title, description = requirementsInput)
            .getOrElse { throw it }
        onProgress(
            buildSpecProgressEvent(
                kind = ChatTraceKind.TASK,
                status = ChatTraceStatus.DONE,
                detail = SpecCodingBundle.message("toolwindow.spec.command.progress.create"),
            ),
        )

        activeSpecWorkflowId = workflow.id

        val generationSummary = runSpecGeneration(workflow.id, requirementsInput, onProgress)
        val summary = buildString {
            appendLine(SpecCodingBundle.message("toolwindow.spec.command.created", workflow.id))
            if (generationSummary.isNotBlank()) {
                append(generationSummary)
            }
        }.trimEnd()
        val refreshed = specEngine.loadWorkflow(workflow.id).getOrNull() ?: workflow
        return buildSpecCardResult(
            workflow = refreshed,
            sourceCommand = sourceCommand,
            summary = summary,
        )
    }

    private suspend fun generateSpecForActiveWorkflow(
        input: String,
        sourceCommand: String,
        onProgress: (ChatStreamEvent) -> Unit = {},
    ): SpecCommandResult {
        val workflow = resolveActiveSpecWorkflow()
            ?: return plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.noActive"))
        val summary = runSpecGeneration(workflow.id, input, onProgress)
        val refreshed = specEngine.loadWorkflow(workflow.id).getOrNull() ?: workflow
        return buildSpecCardResult(
            workflow = refreshed,
            sourceCommand = sourceCommand,
            summary = summary,
        )
    }

    private suspend fun runSpecGeneration(
        workflowId: String,
        input: String,
        onProgress: (ChatStreamEvent) -> Unit = {},
    ): String {
        val lines = mutableListOf<String>()
        val workflow = specEngine.loadWorkflow(workflowId).getOrElse { throw it }
        val phaseName = specPhaseDisplayName(workflow.currentPhase)
        val generationTaskDetail = SpecCodingBundle.message(
            "toolwindow.spec.command.progress.generate",
            phaseName,
        )

        updateStatusLabel(
            SpecCodingBundle.message("toolwindow.spec.command.generating", phaseName),
        )
        onProgress(
            buildSpecProgressEvent(
                kind = ChatTraceKind.TASK,
                status = ChatTraceStatus.RUNNING,
                detail = generationTaskDetail,
            ),
        )

        specEngine.generateCurrentPhase(workflowId, input).collect { progress ->
            when (progress) {
                is SpecGenerationProgress.Started -> {
                    val progressPhaseName = specPhaseDisplayName(progress.phase)
                    updateStatusLabel(
                        SpecCodingBundle.message("toolwindow.spec.command.generating", progressPhaseName),
                    )
                }

                is SpecGenerationProgress.Generating -> {
                    val percent = (progress.progress * 100).toInt().coerceIn(0, 100)
                    val progressPhaseName = specPhaseDisplayName(progress.phase)
                    updateStatusLabel(
                        SpecCodingBundle.message(
                            "toolwindow.spec.command.generating.percent",
                            progressPhaseName,
                            percent,
                        )
                    )
                    onProgress(
                        buildSpecProgressEvent(
                            kind = ChatTraceKind.OUTPUT,
                            status = ChatTraceStatus.INFO,
                            detail = SpecCodingBundle.message(
                                "toolwindow.spec.command.generating.percent",
                                progressPhaseName,
                                percent,
                            ),
                        ),
                    )
                }

                is SpecGenerationProgress.Completed -> {
                    val validationPassedLabel = SpecCodingBundle.message("toolwindow.spec.command.validation.pass")
                        .removePrefix("- ")
                        .removePrefix("-")
                        .trim()
                    lines += SpecCodingBundle.message(
                        "toolwindow.spec.command.generated",
                        workflowId,
                        specPhaseDisplayName(progress.document.phase),
                    )
                    lines += SpecCodingBundle.message("toolwindow.spec.command.validation.pass")
                    onProgress(
                        buildSpecProgressEvent(
                            kind = ChatTraceKind.TASK,
                            status = ChatTraceStatus.DONE,
                            detail = generationTaskDetail,
                        ),
                    )
                    onProgress(
                        buildSpecProgressEvent(
                            kind = ChatTraceKind.VERIFY,
                            status = ChatTraceStatus.DONE,
                            detail = validationPassedLabel,
                        ),
                    )
                }

                is SpecGenerationProgress.ValidationFailed -> {
                    val validationFailedLabel = SpecCodingBundle.message("toolwindow.spec.command.validation.fail")
                        .removePrefix("- ")
                        .removePrefix("-")
                        .trim()
                    lines += SpecCodingBundle.message(
                        "toolwindow.spec.command.generated",
                        workflowId,
                        specPhaseDisplayName(progress.document.phase),
                    )
                    lines += SpecCodingBundle.message("toolwindow.spec.command.validation.fail")
                    onProgress(
                        buildSpecProgressEvent(
                            kind = ChatTraceKind.TASK,
                            status = ChatTraceStatus.DONE,
                            detail = generationTaskDetail,
                        ),
                    )
                    onProgress(
                        buildSpecProgressEvent(
                            kind = ChatTraceKind.VERIFY,
                            status = ChatTraceStatus.ERROR,
                            detail = validationFailedLabel,
                        ),
                    )
                }

                is SpecGenerationProgress.Failed -> {
                    onProgress(
                        buildSpecProgressEvent(
                            kind = ChatTraceKind.TASK,
                            status = ChatTraceStatus.ERROR,
                            detail = progress.error,
                        ),
                    )
                    throw IllegalStateException(progress.error)
                }
            }
        }

        if (lines.isEmpty()) {
            lines += SpecCodingBundle.message("toolwindow.spec.command.generated", workflowId, phaseName)
        }
        return lines.joinToString("\n")
    }

    private fun transitionSpecWorkflow(
        advance: Boolean,
        sourceCommand: String,
    ): SpecCommandResult {
        val current = resolveActiveSpecWorkflow()
            ?: return plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.noActive"))
        val updated = if (advance) {
            specEngine.proceedToNextPhase(current.id)
        } else {
            specEngine.goBackToPreviousPhase(current.id)
        }.getOrElse { throw it }

        activeSpecWorkflowId = updated.id
        val summaryKey = if (advance) {
            "toolwindow.spec.command.phaseAdvanced"
        } else {
            "toolwindow.spec.command.phaseBack"
        }
        val summary = SpecCodingBundle.message(
            summaryKey,
            updated.id,
            updated.currentPhase.displayName,
        )
        return buildSpecCardResult(
            workflow = updated,
            sourceCommand = sourceCommand,
            summary = summary,
        )
    }

    private fun completeSpecWorkflow(sourceCommand: String): SpecCommandResult {
        val workflow = resolveActiveSpecWorkflow()
            ?: return plainSpecResult(SpecCodingBundle.message("toolwindow.spec.command.noActive"))
        val completed = specEngine.completeWorkflow(workflow.id).getOrElse { throw it }
        activeSpecWorkflowId = completed.id
        val summary = SpecCodingBundle.message("toolwindow.spec.command.completed", completed.id)
        return buildSpecCardResult(
            workflow = completed,
            sourceCommand = sourceCommand,
            summary = summary,
        )
    }

    private fun formatSpecStatus(): String {
        val workflow = resolveActiveSpecWorkflow()
            ?: return SpecCodingBundle.message("toolwindow.spec.command.status.none")
        return formatSpecStatus(workflow)
    }

    private fun formatSpecStatus(workflow: SpecWorkflow): String {
        return SpecCodingBundle.message(
            "toolwindow.spec.command.status.entry",
            workflow.id,
            workflow.currentPhase.displayName,
            workflowStatusDisplayName(workflow),
            workflow.title.ifBlank { workflow.id },
        )
    }

    private fun workflowStatusDisplayName(workflow: SpecWorkflow): String {
        return workflowStatusDisplayName(workflow.status)
    }

    private fun workflowStatusDisplayName(status: WorkflowStatus): String {
        return when (status) {
            WorkflowStatus.IN_PROGRESS ->
                SpecCodingBundle.message("spec.workflow.status.inProgress")
            WorkflowStatus.PAUSED ->
                SpecCodingBundle.message("spec.workflow.status.paused")
            WorkflowStatus.COMPLETED ->
                SpecCodingBundle.message("spec.workflow.status.completed")
            WorkflowStatus.FAILED ->
                SpecCodingBundle.message("spec.workflow.status.failed")
        }
    }

    private fun specPhaseDisplayName(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> SpecCodingBundle.message("spec.phase.specify")
            SpecPhase.DESIGN -> SpecCodingBundle.message("spec.phase.design")
            SpecPhase.IMPLEMENT -> SpecCodingBundle.message("spec.phase.implement")
        }
    }

    private fun buildSpecProgressEvent(
        kind: ChatTraceKind,
        status: ChatTraceStatus,
        detail: String,
    ): ChatStreamEvent {
        return ChatStreamEvent(
            kind = kind,
            status = status,
            detail = detail,
        )
    }

    private fun buildSpecCardResult(
        workflow: SpecWorkflow,
        sourceCommand: String,
        summary: String? = null,
    ): SpecCommandResult {
        val document = workflow.getCurrentDocument()
        if (document == null) {
            val fallback = if (!summary.isNullOrBlank()) {
                listOf(summary, formatSpecStatus(workflow)).joinToString("\n")
            } else {
                formatSpecStatus(workflow)
            }
            return plainSpecResult(fallback)
        }

        val title = workflow.title.ifBlank { workflow.id }
        val displaySourceCommand = displayWorkflowChatCommand(sourceCommand) ?: sourceCommand
        val metadata = SpecCardMetadata(
            workflowId = workflow.id,
            phase = workflow.currentPhase,
            status = workflow.status,
            title = title,
            revision = document.metadata.updatedAt,
            sourceCommand = displaySourceCommand,
        )
        return SpecCommandResult(
            output = buildSpecCardMarkdown(
                metadata = metadata,
                previewContent = document.content,
                summary = summary,
            ),
            metadata = metadata,
        )
    }

    private fun buildSpecCardMarkdown(
        metadata: SpecCardMetadata,
        previewContent: String,
        summary: String?,
    ): String {
        val preview = buildSpecCardPreview(previewContent)
        return buildString {
            appendLine(
                "## " + SpecCodingBundle.message(
                    "toolwindow.spec.card.title",
                    metadata.title.ifBlank { metadata.workflowId },
                ),
            )
            appendLine("- ${SpecCodingBundle.message("toolwindow.spec.card.workflow")}: `${metadata.workflowId}`")
            appendLine("- ${SpecCodingBundle.message("toolwindow.spec.card.phase")}: **${metadata.phase.displayName}**")
            appendLine("- ${SpecCodingBundle.message("toolwindow.spec.card.status")}: **${workflowStatusDisplayName(metadata.status)}**")
            appendLine("- ${SpecCodingBundle.message("toolwindow.spec.card.command")}: `${metadata.sourceCommand}`")
            if (!summary.isNullOrBlank()) {
                appendLine()
                appendLine("### ${SpecCodingBundle.message("toolwindow.spec.card.summary")}")
                appendLine(summary.trim())
            }
            appendLine()
            appendLine("### ${SpecCodingBundle.message("toolwindow.spec.card.preview")}")
            appendLine(preview.ifBlank { SpecCodingBundle.message("toolwindow.spec.card.empty") })
        }.trimEnd()
    }

    private fun buildSpecCardPreview(content: String): String {
        if (content.isBlank()) return ""
        val normalizedLines = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .take(SPEC_CARD_PREVIEW_MAX_LINES)
            .toList()
        val normalized = normalizedLines.joinToString("\n").trim()
        if (normalized.isBlank()) return ""
        return if (normalized.length <= SPEC_CARD_PREVIEW_MAX_CHARS) {
            normalized
        } else {
            normalized.take(SPEC_CARD_PREVIEW_MAX_CHARS).trimEnd() + "..."
        }
    }

    private fun plainSpecResult(text: String): SpecCommandResult = SpecCommandResult(output = text)

    private fun resolveActiveSpecWorkflow(): SpecWorkflow? {
        val explicitId = activeSpecWorkflowId
        if (!explicitId.isNullOrBlank()) {
            val workflow = specEngine.loadWorkflow(explicitId).getOrNull()
            if (workflow != null) {
                return workflow
            }
        }

        val latestId = runCatching {
            specEngine.listWorkflows()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .sorted()
                .lastOrNull()
        }.getOrNull() ?: return null
        val workflow = specEngine.loadWorkflow(latestId).getOrNull() ?: return null
        activeSpecWorkflowId = workflow.id
        return workflow
    }

    private fun openSpecTab() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ChatToolWindowFactory.TOOL_WINDOW_ID)
                ?: return@invokeLater
            toolWindow.activate(null)
            ChatToolWindowFactory.ensurePrimaryContents(project, toolWindow)
            ChatToolWindowFactory.selectSpecContent(toolWindow, project)
        }
    }

    private fun openHistoryPanel() {
        if (project.isDisposed || _isDisposed || isGenerating || isRestoringSession) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Spec Code") ?: return@invokeLater
            val contentManager = toolWindow.contentManager
            val historyTitle = SpecCodingBundle.message("history.tab.title")
            val existing = contentManager.contents.firstOrNull {
                it.getUserData(HISTORY_CONTENT_DATA_KEY) == HISTORY_CONTENT_KEY
            }

            if (existing != null) {
                existing.displayName = historyTitle
                contentManager.setSelectedContent(existing)
                toolWindow.activate(null)
                return@invokeLater
            }

            val historyPanel = HistoryPanel(project)
            val historyContent = ContentFactory.getInstance().createContent(historyPanel, historyTitle, false).apply {
                putUserData(HISTORY_CONTENT_DATA_KEY, HISTORY_CONTENT_KEY)
            }
            Disposer.register(historyContent, historyPanel)
            contentManager.addContent(historyContent)
            contentManager.setSelectedContent(historyContent)

            contentManager.addContentManagerListener(object : ContentManagerListener {
                override fun selectionChanged(event: ContentManagerEvent) {
                    if (contentManager.selectedContent != historyContent && contentManager.contents.contains(historyContent)) {
                        contentManager.removeContentManagerListener(this)
                        contentManager.removeContent(historyContent, true)
                    }
                }

                override fun contentRemoved(event: ContentManagerEvent) {
                    if (event.content == historyContent) {
                        contentManager.removeContentManagerListener(this)
                    }
                }
            })
            toolWindow.activate(null)
        }
    }

    private fun refreshHistoryContentTitle() {
        if (project.isDisposed || _isDisposed) return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Spec Code") ?: return
        val historyContent = toolWindow.contentManager.contents.firstOrNull {
            it.getUserData(HISTORY_CONTENT_DATA_KEY) == HISTORY_CONTENT_KEY
        } ?: return
        historyContent.displayName = SpecCodingBundle.message("history.tab.title")
    }

    private fun updateStatusLabel(text: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            showStatus(text)
        }
    }

    private fun handlePipelineCommand(command: String) {
        val operation = Operation.WRITE_FILE
        if (!checkOperationPermission(operation, command)) {
            return
        }

        val parsedStages = skillExecutor.parsePipelineStages(command)
        if (parsedStages.isNullOrEmpty()) {
            addErrorMessage(SpecCodingBundle.message("toolwindow.pipeline.invalid"))
            return
        }

        val providerId = providerComboBox.selectedItem as? String

        clearComposerInput()
        appendUserMessage(command)

        stopRequested.set(false)
        activeLlmRequest = null
        executionState = executionState.withFinalizing(false)
        setSendingState(true)

        activeOperationJob = taskCoordinator.launchIo {
            try {
                val sessionId = ensureActiveSessionAsync(command, providerId)
                sessionId?.let { persistMessage(it, ConversationRole.USER, command) }
                val autoContext = contextCollector.collectContext()
                val selectedCode = autoContext.items
                    .firstOrNull { it.type == ContextType.SELECTED_CODE }
                    ?.content
                val currentFile = autoContext.items
                    .firstOrNull { it.type == ContextType.CURRENT_FILE }
                    ?.filePath

                val context = SkillContext(
                    selectedCode = selectedCode,
                    currentFile = currentFile,
                )

                val result = skillExecutor.executePipelineFromCommand(command, context)
                if (result is com.eacape.speccodingplugin.skill.SkillExecutionResult.Success && sessionId != null) {
                    persistMessage(sessionId, ConversationRole.ASSISTANT, result.output)
                }

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }

                    when (result) {
                        is com.eacape.speccodingplugin.skill.SkillExecutionResult.Success -> {
                            val panel = addAssistantMessage()
                            panel.appendContent(result.output)
                            panel.finishMessage()
                        }

                        is com.eacape.speccodingplugin.skill.SkillExecutionResult.Failure -> {
                            addErrorMessage(result.error)
                        }
                    }
                }
            } catch (error: Throwable) {
                val stopped = stopRequested.get() || error is CancellationException
                if (!stopped) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        addErrorMessage(error.message ?: SpecCodingBundle.message("toolwindow.error.unknown"))
                    }
                }
                if (error is CancellationException) {
                    throw error
                }
            } finally {
                activeOperationJob = null
                stopRequested.set(false)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    setSendingState(false)
                }
            }
        }
    }

    private fun showAvailableSkills() {
        val snapshot = skillExecutor.discoverAvailableSkills(forceReload = true)
        val skills = snapshot.skills
        val lines = buildString {
            appendLine(SpecCodingBundle.message("toolwindow.skill.available.header", skills.size))
            appendLine(SpecCodingBundle.message("toolwindow.skill.available.roots"))
            snapshot.roots.forEach { root ->
                val scopeLabel = when (root.scope) {
                    com.eacape.speccodingplugin.skill.SkillScope.GLOBAL ->
                        SpecCodingBundle.message("toolwindow.skill.available.scope.global")

                    com.eacape.speccodingplugin.skill.SkillScope.PROJECT ->
                        SpecCodingBundle.message("toolwindow.skill.available.scope.project")
                }
                val rootStatus = if (root.exists) {
                    SpecCodingBundle.message("toolwindow.skill.available.root.exists")
                } else {
                    SpecCodingBundle.message("toolwindow.skill.available.root.missing")
                }
                appendLine(
                    SpecCodingBundle.message(
                        "toolwindow.skill.available.root.item",
                        scopeLabel,
                        root.label,
                        root.path,
                        rootStatus,
                    ),
                )
            }
            skills.forEach { skill ->
                val sourceLabel = when (skill.sourceType) {
                    com.eacape.speccodingplugin.skill.SkillSourceType.BUILTIN ->
                        SpecCodingBundle.message("toolwindow.skill.available.source.builtin")

                    com.eacape.speccodingplugin.skill.SkillSourceType.YAML ->
                        SpecCodingBundle.message("toolwindow.skill.available.source.yaml")

                    com.eacape.speccodingplugin.skill.SkillSourceType.MARKDOWN ->
                        SpecCodingBundle.message("toolwindow.skill.available.source.markdown")
                }
                val sourceWithScope = skill.scope?.let { scope ->
                    val scopeText = when (scope) {
                        com.eacape.speccodingplugin.skill.SkillScope.GLOBAL ->
                            SpecCodingBundle.message("toolwindow.skill.available.scope.global")

                        com.eacape.speccodingplugin.skill.SkillScope.PROJECT ->
                            SpecCodingBundle.message("toolwindow.skill.available.scope.project")
                    }
                    "$sourceLabel / $scopeText"
                } ?: sourceLabel
                appendLine(
                    SpecCodingBundle.message(
                        "toolwindow.skill.available.item.ext",
                        skill.slashCommand,
                        skill.description,
                        sourceWithScope,
                    )
                )
            }
        }.trimEnd()
        addSystemMessage(lines)
    }

    private fun handleModeCommand(command: String): Boolean {
        if (!command.startsWith("/mode")) {
            return false
        }

        val tokens = command.removePrefix("/").split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size == 1) {
            addSystemMessage(
                SpecCodingBundle.message(
                    "toolwindow.mode.current",
                    modeManager.getCurrentMode().displayName,
                )
            )
            return true
        }

        val requestedMode = tokens[1].uppercase()
        val mode = OperationMode.entries.firstOrNull { it.name == requestedMode }
        if (mode == null) {
            addErrorMessage(SpecCodingBundle.message("toolwindow.mode.invalid", tokens[1]))
            return true
        }

        modeManager.switchMode(mode)
        operationModeSelector.setSelectedMode(mode)
        addSystemMessage(SpecCodingBundle.message("toolwindow.mode.switched", mode.displayName))
        return true
    }

    private fun mapSlashCommandToOperation(command: String): Operation? {
        val normalized = command
            .removePrefix("/")
            .trim()
            .split(Regex("\\s+"), limit = 2)
            .firstOrNull()
            ?.lowercase()
            ?: return null

        return when (normalized) {
            "review", "explain", "skills" -> Operation.ANALYZE_CODE
            "refactor", "test", "fix", "pipeline" -> Operation.WRITE_FILE
            else -> null
        }
    }

    private fun checkOperationPermission(operation: Operation, command: String): Boolean {
        val request = OperationRequest(
            operation = operation,
            description = "Slash command: $command",
            details = mapOf("command" to command),
        )

        return when (val result = modeManager.checkOperation(request)) {
            is OperationResult.Allowed -> true
            is OperationResult.Denied -> {
                addErrorMessage(
                    SpecCodingBundle.message(
                        "toolwindow.mode.operation.denied",
                        modeManager.getCurrentMode().displayName,
                        result.reason,
                    )
                )
                false
            }

            is OperationResult.RequiresConfirmation -> {
                val confirmed = command.split(Regex("\\s+")).any { it.equals("--confirm", ignoreCase = true) }
                if (confirmed) {
                    addSystemMessage(
                        SpecCodingBundle.message(
                            "toolwindow.mode.confirmation.accepted",
                            operation.name,
                        )
                    )
                    true
                } else {
                    addErrorMessage(
                        SpecCodingBundle.message(
                            "toolwindow.mode.confirmation.required",
                            modeManager.getCurrentMode().displayName,
                            operation.name,
                        )
                    )
                    false
                }
            }
        }
    }

    private fun refreshProviderCombo(preserveSelection: Boolean) {
        val settings = SpecCodingSettingsState.getInstance()
        val previousSelection = providerComboBox.selectedItem as? String
        val providers = (llmRouter.availableUiProviders().ifEmpty { llmRouter.availableProviders() })
            .ifEmpty { listOf(MockLlmProvider.ID) }

        providerComboBox.removeAllItems()
        providers.forEach { providerComboBox.addItem(it) }

        val preferred = when {
            preserveSelection && !previousSelection.isNullOrBlank() -> previousSelection
            settings.defaultProvider.isNotBlank() -> settings.defaultProvider
            else -> llmRouter.defaultProviderId()
        }
        val selected = providers.firstOrNull { it == preferred } ?: providers.firstOrNull()
        providerComboBox.selectedItem = selected
        updateProviderComboSize()

        refreshModelCombo()
    }

    private fun refreshModelCombo() {
        val selectedProvider = providerComboBox.selectedItem as? String
        modelComboBox.removeAllItems()
        if (selectedProvider.isNullOrBlank()) {
            return
        }

        val settings = SpecCodingSettingsState.getInstance()
        val models = modelRegistry.getModelsForProvider(selectedProvider).toMutableList()
        val savedModelId = settings.selectedCliModel.trim()
        if (models.isEmpty() && savedModelId.isNotBlank()) {
            models += ModelInfo(
                id = savedModelId,
                name = savedModelId,
                provider = selectedProvider,
                contextWindow = 0,
                capabilities = emptySet(),
            )
        }

        models.forEach { modelComboBox.addItem(it) }

        val selected = models.firstOrNull { it.id == savedModelId } ?: models.firstOrNull()
        if (selected != null) {
            modelComboBox.selectedItem = selected
        }
        updateModelComboSize()
    }

    private fun startNewSession() {
        if (isGenerating || isRestoringSession) {
            return
        }
        conversationHistory.clear()
        clearCompactedConversationState()
        userMessageRawContent.clear()
        renderedTaskExecutionSessionMessageIds.clear()
        currentSessionId = null
        activeSpecWorkflowId = null
        activeWorkflowChatBinding = null
        sessionIsolationService.clearActiveSession()
        specSidebarPanel.clearFocusedWorkflow()
        messagesPanel.clearAll()
        contextPreviewPanel.clear()
        clearImageAttachments()
        clearComposerInput()
        currentAssistantPanel = null
        activeTaskExecutionPanel = null
        taskExecutionPanelsByRunId.clear()
        synchronized(pendingTaskExecutionLiveProgressLock) {
            pendingTaskExecutionLiveProgressByRunId.clear()
        }
        taskExecutionLiveProgressFlushTimer.stop()
        latestObservedTaskLiveProgress = null
        updateWorkflowBindingUi()
    }

    private fun loadSession(sessionId: String) {
        if (sessionId.isBlank() || project.isDisposed || _isDisposed) {
            return
        }
        setSessionRestoringState(true)
        showStatus(SpecCodingBundle.message("toolwindow.status.session.restoring"))

        taskCoordinator.launchIo {
            val loaded = runCatching {
                val restoreSnapshot = chatPersistenceCoordinator
                    .loadSessionRestoreSnapshot(
                        sessionId = sessionId,
                        fetchLimit = SESSION_LOAD_FETCH_LIMIT,
                        restoredMessageLimit = MAX_RESTORED_MESSAGES,
                    )
                    .getOrThrow()
                val session = restoreSnapshot.session
                val messages = restoreSnapshot.messages
                val activeExecutionLaunchRunIds = resolveActiveExecutionLaunchRunIds(messages)
                Triple(session, messages, activeExecutionLaunchRunIds)
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || _isDisposed) {
                    return@invokeLater
                }
                if (loaded.isFailure) {
                    setSessionRestoringState(false)
                    addErrorMessage(
                        loaded.exceptionOrNull()?.message ?: SpecCodingBundle.message("toolwindow.error.unknown"),
                    )
                    return@invokeLater
                }

                val (session, messages, activeExecutionLaunchRunIds) = loaded.getOrThrow()
                if (session == null) {
                    currentSessionId = null
                    sessionIsolationService.clearActiveSession()
                    addErrorMessage(SpecCodingBundle.message("toolwindow.error.session.notFound", sessionId))
                    setSessionRestoringState(false)
                    return@invokeLater
                }

                setInteractionMode(
                    mode = inferInteractionModeForSession(session, messages),
                    resetSessionOnSpecSwitch = false,
                    emitHook = false,
                )
                val restoredBinding = session.resolvedWorkflowChatBinding()
                activeSpecWorkflowId = restoredBinding?.workflowId
                    ?: session.specTaskId?.trim()?.ifBlank { null }
                activeWorkflowChatBinding = restoredBinding
                restoreSessionMessages(messages, activeExecutionLaunchRunIds)
                currentSessionId = session.id
                sessionIsolationService.activateSession(session.id)
                activeSpecWorkflowId?.let { workflowId ->
                    if (specSidebarVisible) {
                        specSidebarPanel.focusWorkflow(workflowId)
                    }
                }
                refreshSpecWorkflowComboBox(selectWorkflowId = activeSpecWorkflowId)
                updateWorkflowBindingUi()
                showStatus(
                    text = SpecCodingBundle.message("toolwindow.status.session.loaded.short"),
                    tooltip = SpecCodingBundle.message("toolwindow.status.session.loaded", session.title),
                    autoHideMillis = STATUS_SESSION_LOADED_AUTO_HIDE_MILLIS,
                )
                setSessionRestoringState(false)
            }
        }
    }

    private fun restoreSessionMessages(
        messages: List<com.eacape.speccodingplugin.session.ConversationMessage>,
        activeExecutionLaunchRunIds: Set<String> = emptySet(),
    ) {
        conversationHistory.clear()
        clearCompactedConversationState()
        userMessageRawContent.clear()
        renderedTaskExecutionSessionMessageIds.clear()
        messagesPanel.clearAll()
        contextPreviewPanel.clear()
        clearImageAttachments()
        currentAssistantPanel = null
        activeTaskExecutionPanel = null
        taskExecutionPanelsByRunId.clear()
        synchronized(pendingTaskExecutionLiveProgressLock) {
            pendingTaskExecutionLiveProgressByRunId.clear()
        }
        taskExecutionLiveProgressFlushTimer.stop()
        latestObservedTaskLiveProgress = null
        val interactionMode = currentInteractionMode()
        val continueHandler = continueHandlerFor(interactionMode)
        val workflowSectionsEnabled = workflowSectionRenderingEnabledFor(interactionMode)

        messages.forEach { message ->
            appendRestoredConversationMessage(
                message = message,
                interactionMode = interactionMode,
                continueHandler = continueHandler,
                workflowSectionsEnabled = workflowSectionsEnabled,
                fromSessionRestore = true,
                activeExecutionLaunchRunIds = activeExecutionLaunchRunIds,
            )
        }

        if (messages.isEmpty()) {
            addSystemMessage(SpecCodingBundle.message("toolwindow.system.emptySessionLoaded"))
        }
        refreshContinueActions(interactionMode)
        messagesPanel.scrollToBottom()
    }

    private fun inferInteractionModeForSession(
        session: ConversationSession,
        messages: List<ConversationMessage>,
    ): ChatInteractionMode {
        if (session.resolvedWorkflowChatBinding() != null || !session.specTaskId.isNullOrBlank()) {
            return ChatInteractionMode.SPEC
        }

        val hasSpecCardMessage = messages.any { message ->
            message.role == ConversationRole.ASSISTANT && SpecCardMetadataCodec.decode(message.metadataJson) != null
        }
        if (hasSpecCardMessage) {
            return ChatInteractionMode.SPEC
        }

        val hasSpecSlashCommand = messages.any { message ->
            message.role == ConversationRole.USER &&
                isWorkflowChatCommand(message.content)
        }
        return if (hasSpecSlashCommand) {
            ChatInteractionMode.SPEC
        } else {
            ChatInteractionMode.VIBE
        }
    }

    private suspend fun ensureActiveSpecSessionAsync(
        titleSeed: String,
        providerId: String?,
        specTaskId: String?,
        workflowBinding: WorkflowChatBinding? = null,
    ): String? {
        currentSessionId?.let { return it }
        return sessionCreationMutex.withLock {
            currentSessionId?.let { return@withLock it }
            sessionIsolationService.activeSessionId()?.let {
                currentSessionId = it
                return@withLock it
            }

            val normalizedSpecTaskId = specTaskId?.trim()?.ifBlank { null }
            val workflowTitle = cachedWorkflowOption(normalizedSpecTaskId)
                ?.title
                ?.trim()
                ?.ifBlank { null }
            val title = (workflowTitle ?: titleSeed.lines().firstOrNull().orEmpty().trim())
                .ifBlank { SpecCodingBundle.message("toolwindow.session.defaultTitle") }
                .take(80)
            val effectiveBinding = workflowBinding?.normalizedOrNull()
                ?: resolvedActiveWorkflowChatBinding()
                    ?.takeIf { binding ->
                        normalizedSpecTaskId == null || binding.workflowId == normalizedSpecTaskId
                    }
                ?: normalizedSpecTaskId?.let { workflowId ->
                    buildWorkflowChatBinding(
                        workflowId = workflowId,
                        source = WorkflowChatEntrySource.MODE_SWITCH,
                    )
                }

            val created = withContext(Dispatchers.IO) {
                chatPersistenceCoordinator.createSession(
                    title = title,
                    providerId = providerId,
                    specTaskId = normalizedSpecTaskId,
                    workflowChatBinding = effectiveBinding,
                )
            }

            created.fold(
                onSuccess = { session ->
                    currentSessionId = session.id
                    sessionIsolationService.activateSession(session.id)
                    session.id
                },
                onFailure = { error ->
                    logger.warn("Failed to create spec session", error)
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed && !_isDisposed) {
                            addErrorMessage(
                                SpecCodingBundle.message(
                                    "toolwindow.error.session.createFailed.fallback",
                                    error.message ?: SpecCodingBundle.message("common.unknown"),
                                )
                            )
                        }
                    }
                    null
                },
            )
        }
    }

    private fun resolveMostRecentSpecWorkflowIdOrNull(): String? {
        val explicitId = activeSpecWorkflowId?.trim()?.ifBlank { null }
        if (!explicitId.isNullOrBlank()) {
            return explicitId
        }
        (specWorkflowComboBox.selectedItem as? SpecWorkflowOption)?.workflowId?.let { workflowId ->
            return workflowId
        }
        cachedSpecWorkflowOptions.firstOrNull()?.workflowId?.let { workflowId ->
            return workflowId
        }
        return null
    }

    private fun cachedWorkflowOption(workflowId: String?): SpecWorkflowOption? {
        val normalizedWorkflowId = workflowId?.trim()?.ifBlank { null } ?: return null
        val selectedOption = specWorkflowComboBox.selectedItem as? SpecWorkflowOption
        if (selectedOption?.workflowId == normalizedWorkflowId) {
            return selectedOption
        }
        return cachedSpecWorkflowOptions.firstOrNull { option -> option.workflowId == normalizedWorkflowId }
    }

    private fun buildWorkflowChatBinding(
        workflowId: String,
        source: WorkflowChatEntrySource,
        actionIntent: WorkflowChatActionIntent = WorkflowChatActionIntent.DISCUSS,
        focusedStage: StageId? = null,
    ): WorkflowChatBinding {
        val normalizedWorkflowId = workflowId.trim()
        val resolvedFocusedStage = focusedStage
            ?: resolvedActiveWorkflowChatBinding()
                ?.takeIf { binding -> binding.workflowId == normalizedWorkflowId }
                ?.focusedStage
            ?: cachedWorkflowOption(normalizedWorkflowId)?.currentStage
        return WorkflowChatBinding(
            workflowId = normalizedWorkflowId,
            focusedStage = resolvedFocusedStage,
            source = source,
            actionIntent = actionIntent,
        )
    }

    private fun bindSessionToSpecTaskIfNeeded(sessionId: String?, workflowId: String?) {
        val normalizedSessionId = sessionId?.trim()?.ifBlank { null } ?: return
        val normalizedWorkflowId = workflowId?.trim()?.ifBlank { null } ?: return
        val existingBinding = chatPersistenceCoordinator.resolveWorkflowChatBinding(normalizedSessionId)
        val desiredBinding = resolvedActiveWorkflowChatBinding()
            ?.takeIf { binding -> binding.workflowId == normalizedWorkflowId }
            ?: buildWorkflowChatBinding(
                workflowId = normalizedWorkflowId,
                source = existingBinding?.source ?: WorkflowChatEntrySource.MODE_SWITCH,
                actionIntent = existingBinding?.actionIntent ?: WorkflowChatActionIntent.DISCUSS,
                focusedStage = existingBinding?.focusedStage,
            )
        if (existingBinding == desiredBinding) {
            return
        }
        chatPersistenceCoordinator.persistWorkflowChatBinding(
            normalizedSessionId,
            desiredBinding,
        )
            .onFailure { error ->
                logger.warn("Failed to bind session to spec workflow: $normalizedWorkflowId", error)
            }
    }

    private suspend fun ensureActiveSessionAsync(firstUserInput: String, providerId: String?): String? {
        currentSessionId?.let { return it }
        return sessionCreationMutex.withLock {
            currentSessionId?.let { return@withLock it }
            sessionIsolationService.activeSessionId()?.let {
                currentSessionId = it
                return@withLock it
            }

            val title = firstUserInput.lines()
                .firstOrNull()
                .orEmpty()
                .trim()
                .ifBlank { SpecCodingBundle.message("toolwindow.session.defaultTitle") }
                .take(80)

            val created = withContext(Dispatchers.IO) {
                chatPersistenceCoordinator.createSession(
                    title = title,
                    providerId = providerId,
                )
            }

            created.fold(
                onSuccess = { session ->
                    currentSessionId = session.id
                    sessionIsolationService.activateSession(session.id)
                    session.id
                },
                onFailure = { error ->
                    logger.warn("Failed to create session", error)
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed && !_isDisposed) {
                            addErrorMessage(
                                SpecCodingBundle.message(
                                    "toolwindow.error.session.createFailed.fallback",
                                    error.message ?: SpecCodingBundle.message("common.unknown"),
                                )
                            )
                        }
                    }
                    null
                },
            )
        }
    }

    private fun persistMessage(
        sessionId: String,
        role: ConversationRole,
        content: String,
        metadataJson: String? = null,
    ) {
        val result = chatPersistenceCoordinator.persistMessage(
            sessionId = sessionId,
            role = role,
            content = content,
            metadataJson = metadataJson,
        )
        if (result.isFailure) {
            logger.warn("Failed to persist message for session: $sessionId", result.exceptionOrNull())
        }
    }

    private fun persistMessageAsync(
        sessionId: String,
        role: ConversationRole,
        content: String,
        metadataJson: String? = null,
    ) {
        taskCoordinator.launchIo {
            persistMessage(
                sessionId = sessionId,
                role = role,
                content = content,
                metadataJson = metadataJson,
            )
        }
    }

    private fun loadExplicitItemContents(items: List<ContextItem>): List<ContextItem> {
        return items.map { item ->
            if (item.content.isNotBlank() || item.filePath == null) {
                return@map item
            }
            val vf = LocalFileSystem.getInstance().findFileByPath(item.filePath)
                ?: return@map item
            val content = runCatching {
                ReadAction.compute<String, Throwable> {
                    String(vf.contentsToByteArray(), Charsets.UTF_8)
                }
            }.getOrElse { error ->
                logger.warn("Failed to read explicit context file: ${item.filePath}", error)
                return@map item
            }
            item.copy(content = content, tokenEstimate = content.length / 4)
        }
    }

    private fun collectContextSnapshotSafely(explicitItems: List<ContextItem>) =
        runCatching {
            contextCollector.collectForItems(
                explicitItems = explicitItems,
                config = CHAT_CONTEXT_CONFIG,
            )
        }.getOrElse { error ->
            logger.warn("Failed to collect auto context; fallback to explicit context only", error)
            ContextTrimmer.trim(explicitItems, CHAT_CONTEXT_CONFIG.tokenBudget)
        }

    private fun appendUserMessage(
        content: String,
        rawContent: String? = null,
        imagePaths: List<String> = emptyList(),
    ): ChatMessagePanel {
        val panel = ChatMessagePanel(
            ChatMessagePanel.MessageRole.USER, content,
            onDelete = ::handleDeleteMessage,
            attachedImagePaths = imagePaths,
        )
        panel.finishMessage()
        messagesPanel.addMessage(panel)
        if (rawContent != null) {
            userMessageRawContent[panel] = rawContent
        } else {
            userMessageRawContent.remove(panel)
        }
        return panel
    }

    private fun appendRestoredConversationMessage(
        message: ConversationMessage,
        interactionMode: ChatInteractionMode = currentInteractionMode(),
        continueHandler: ((ChatMessagePanel) -> Unit)? = continueHandlerFor(interactionMode),
        workflowSectionsEnabled: Boolean = workflowSectionRenderingEnabledFor(interactionMode),
        fromSessionRestore: Boolean = false,
        activeExecutionLaunchRunIds: Set<String> = emptySet(),
        executionMetadata: TaskExecutionSessionMetadataCodec.DecodedMetadata =
            TaskExecutionSessionMetadataCodec.decode(message.metadataJson),
    ) {
        val renderDecision = ImprovedChatPanelMessageRenderCoordinator.planRestoredMessage(
            message = message,
            fromSessionRestore = fromSessionRestore,
            activeExecutionLaunchRunIds = activeExecutionLaunchRunIds,
            executionMetadata = executionMetadata,
            sanitizeTraceEvent = ::sanitizeStreamEvent,
            buildSpecCardFallbackMarkdown = { metadata ->
                buildSpecCardMarkdown(
                    metadata = metadata,
                    previewContent = "",
                    summary = null,
                )
            },
        )
        when (val plan = renderDecision.plan) {
            is ImprovedChatPanelMessageRenderPlan.UserPlain -> {
                appendUserMessage(
                    content = plan.visibleContent,
                    rawContent = plan.rawContent,
                )
            }

            is ImprovedChatPanelMessageRenderPlan.UserExecutionLaunch -> {
                addExecutionLaunchMessage(
                    visibleContent = plan.visibleContent,
                    payload = plan.payload,
                    rawContent = plan.rawContent,
                )
            }

            is ImprovedChatPanelMessageRenderPlan.AssistantSpecCard -> {
                addSpecCardMessage(
                    cardMarkdown = plan.cardMarkdown,
                    metadata = plan.metadata,
                )
            }

            is ImprovedChatPanelMessageRenderPlan.AssistantPanel -> {
                val timelineExpandButtonsVisible = timelineExpandButtonsVisibleFor(interactionMode)
                val panel = ChatMessagePanel(
                    ChatMessagePanel.MessageRole.ASSISTANT,
                    plan.content,
                    onDelete = ::handleDeleteMessage,
                    onRegenerate = ::handleRegenerateMessage,
                    onContinue = continueHandler,
                    onWorkflowFileOpen = ::handleWorkflowFileOpen,
                    onWorkflowCommandExecute = ::handleWorkflowCommandExecute,
                    workflowSectionsEnabled = workflowSectionsEnabled,
                    timelineExpandButtonsVisible = timelineExpandButtonsVisible,
                    startedAtMillis = plan.startedAtMillis,
                    finishedAtMillis = plan.finishedAtMillis,
                    captureElapsedAutomatically = false,
                )
                if (plan.traceEvents.isNotEmpty()) {
                    panel.appendStreamContent(text = "", events = plan.traceEvents)
                }
                panel.finishMessage()
                messagesPanel.addMessage(panel)
            }

            is ImprovedChatPanelMessageRenderPlan.SystemMessage -> {
                addSystemMessage(plan.content)
            }

            is ImprovedChatPanelMessageRenderPlan.ToolMessage -> {
                addToolMessage(plan.content)
            }
        }
        renderDecision.historyMessage?.let(::appendToConversationHistory)
        if (renderDecision.marksTaskExecutionMessageRendered) {
            renderedTaskExecutionSessionMessageIds += message.id
        }
    }

    private fun resolveActiveExecutionLaunchRunIds(messages: List<ConversationMessage>): Set<String> {
        if (messages.isEmpty()) {
            return emptySet()
        }
        return messages.asSequence()
            .filter { message -> message.role == ConversationRole.USER }
            .mapNotNull { message ->
                val executionMetadata = TaskExecutionSessionMetadataCodec.decode(message.metadataJson)
                if (executionMetadata.resolveExecutionLaunchRestorePayload(message.content) == null) {
                    return@mapNotNull null
                }
                val workflowId = executionMetadata.workflowId?.trim()?.ifBlank { null } ?: return@mapNotNull null
                val runId = executionMetadata.runId?.trim()?.ifBlank { null } ?: return@mapNotNull null
                val progress = runCatching {
                    specTaskExecutionService.getLiveProgress(workflowId, runId)
                }.getOrNull() ?: return@mapNotNull null
                runId.takeIf { progress.phase != ExecutionLivePhase.TERMINAL }
            }
            .toSet()
    }

    private fun syncTaskExecutionSessionMessages(
        sessionId: String,
        runId: String?,
        requestId: String?,
    ) {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isBlank()) {
            return
        }
        if (currentSessionId != normalizedSessionId) {
            return
        }
        val normalizedRunId = runId?.trim()?.ifBlank { null }
        val normalizedRequestId = requestId?.trim()?.ifBlank { null }
        if (normalizedRunId == null && normalizedRequestId == null) {
            return
        }
        val messages = chatPersistenceCoordinator.loadRecentMessages(
            sessionId = normalizedSessionId,
            fetchLimit = SESSION_LOAD_FETCH_LIMIT,
            restoredMessageLimit = MAX_RESTORED_MESSAGES,
        )
        val candidates = messages.mapNotNull { message ->
            val executionMetadata = TaskExecutionSessionMetadataCodec.decode(message.metadataJson)
            if (!executionMetadata.matchesTaskExecutionMessage(normalizedRunId, normalizedRequestId)) {
                return@mapNotNull null
            }
            if (message.id in renderedTaskExecutionSessionMessageIds) {
                return@mapNotNull null
            }
            message to executionMetadata
        }
        if (candidates.isEmpty()) {
            return
        }
        val interactionMode = currentInteractionMode()
        val continueHandler = continueHandlerFor(interactionMode)
        val workflowSectionsEnabled = workflowSectionRenderingEnabledFor(interactionMode)
        val wasNearBottom = messagesPanel.isNearBottom()
        candidates.forEach { (message, executionMetadata) ->
            if (message.role == ConversationRole.ASSISTANT && normalizedRunId != null && executionMetadata.runId == normalizedRunId) {
                removeActiveTaskExecutionPanel(normalizedRunId)
            } else if (message.role == ConversationRole.SYSTEM && normalizedRunId != null && executionMetadata.runId == normalizedRunId) {
                finishActiveTaskExecutionPanel(normalizedRunId)
            }
            appendRestoredConversationMessage(
                message = message,
                interactionMode = interactionMode,
                continueHandler = continueHandler,
                workflowSectionsEnabled = workflowSectionsEnabled,
                executionMetadata = executionMetadata,
            )
        }
        if (wasNearBottom) {
            messagesPanel.scrollToBottom()
        }
    }

    private fun removeActiveTaskExecutionPanel(runId: String) {
        val state = activeTaskExecutionPanel ?: return
        if (state.runId != runId) {
            return
        }
        messagesPanel.removeMessage(state.panel)
        activeTaskExecutionPanel = null
    }

    private fun finishActiveTaskExecutionPanel(runId: String) {
        val state = activeTaskExecutionPanel ?: return
        if (state.runId != runId) {
            return
        }
        if (!state.finished) {
            state.panel.finishMessage()
            state.finished = true
        }
    }

    private fun addExecutionLaunchMessage(
        visibleContent: String,
        payload: com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchRestorePayload,
        rawContent: String?,
    ): WorkflowChatExecutionLaunchMessagePanel {
        val panel = WorkflowChatExecutionLaunchMessagePanel(
            payload = payload,
            visibleContent = visibleContent,
            rawPromptContent = rawContent,
            onDeleteMessage = ::handleDeleteMessage,
        )
        messagesPanel.addMessage(panel)
        rawContent?.takeIf(String::isNotBlank)?.let { userMessageRawContent[panel] = it } ?: userMessageRawContent.remove(panel)
        return panel
    }

    private fun addAssistantMessage(
        startedAtMillis: Long? = null,
        finishedAtMillis: Long? = null,
        captureElapsedAutomatically: Boolean = true,
    ): ChatMessagePanel {
        val mode = currentInteractionMode()
        val panel = ChatMessagePanel(
            ChatMessagePanel.MessageRole.ASSISTANT,
            onDelete = ::handleDeleteMessage,
            onRegenerate = ::handleRegenerateMessage,
            onContinue = continueHandlerFor(mode),
            onWorkflowFileOpen = ::handleWorkflowFileOpen,
            onWorkflowCommandExecute = ::handleWorkflowCommandExecute,
            workflowSectionsEnabled = workflowSectionRenderingEnabledFor(mode),
            timelineExpandButtonsVisible = timelineExpandButtonsVisibleFor(mode),
            startedAtMillis = startedAtMillis,
            finishedAtMillis = finishedAtMillis,
            captureElapsedAutomatically = captureElapsedAutomatically,
        )
        messagesPanel.addMessage(panel)
        return panel
    }

    private fun addSpecCardMessage(
        cardMarkdown: String,
        metadata: SpecCardMetadata,
    ): SpecCardMessagePanel {
        val initialState = resolveSpecCardInitialState(metadata, cardMarkdown)
        val panel = SpecCardMessagePanel(
            metadata = initialState.metadata,
            cardMarkdown = cardMarkdown,
            initialDocumentContent = initialState.documentContent,
            onDeleteMessage = ::handleDeleteMessage,
            onContinueMessage = continueHandlerFor(currentInteractionMode()),
            onOpenSpecTab = ::openSpecTab,
            onOpenDocument = ::openSpecWorkflowDocument,
            onFocusSpecSidebar = ::focusSpecSidebarForCard,
            onSaveDocument = ::saveSpecCardDocument,
            onAdvancePhase = ::advanceSpecCardPhase,
        )
        messagesPanel.addMessage(panel)
        return panel
    }

    private data class SpecCardInitialState(
        val metadata: SpecCardMetadata,
        val documentContent: String,
    )

    private fun resolveSpecCardInitialState(
        metadata: SpecCardMetadata,
        cardMarkdown: String,
    ): SpecCardInitialState {
        val workflow = specEngine.loadWorkflow(metadata.workflowId).getOrNull()
        val fallbackContent = extractSpecCardPreviewFromMarkdown(cardMarkdown)
        if (workflow == null) {
            return SpecCardInitialState(metadata = metadata, documentContent = fallbackContent)
        }

        val documentAtPhase = workflow.getDocument(metadata.phase)
        if (documentAtPhase != null) {
            val refreshedMetadata = metadata.copy(
                status = workflow.status,
                title = workflow.title.ifBlank { metadata.title.ifBlank { metadata.workflowId } },
                revision = documentAtPhase.metadata.updatedAt,
            )
            val content = documentAtPhase.content.ifBlank { fallbackContent }
            return SpecCardInitialState(metadata = refreshedMetadata, documentContent = content)
        }

        val currentDocument = workflow.getCurrentDocument()
        if (currentDocument != null) {
            val refreshedMetadata = metadata.copy(
                phase = currentDocument.phase,
                status = workflow.status,
                title = workflow.title.ifBlank { metadata.title.ifBlank { metadata.workflowId } },
                revision = currentDocument.metadata.updatedAt,
            )
            val content = currentDocument.content.ifBlank { fallbackContent }
            return SpecCardInitialState(metadata = refreshedMetadata, documentContent = content)
        }

        return SpecCardInitialState(metadata = metadata, documentContent = fallbackContent)
    }

    private fun extractSpecCardPreviewFromMarkdown(cardMarkdown: String): String {
        val normalizedLines = cardMarkdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
        val previewHeaderIndex = normalizedLines.indexOfFirst { line ->
            line.trimStart().startsWith("### ") &&
                (line.contains("preview", ignoreCase = true) || line.contains("预览"))
        }
        if (previewHeaderIndex < 0 || previewHeaderIndex >= normalizedLines.lastIndex) {
            return cardMarkdown.trim()
        }
        return normalizedLines.subList(previewHeaderIndex + 1, normalizedLines.size)
            .joinToString("\n")
            .trim()
    }

    private fun focusSpecSidebarForCard(metadata: SpecCardMetadata) {
        focusSpecSidebar(metadata.workflowId, metadata.phase)
    }

    private fun openSpecWorkflowDocument(metadata: SpecCardMetadata) {
        openSpecWorkflowDocument(metadata.workflowId, metadata.phase)
    }

    private fun openSpecWorkflowDocument(workflowId: String, phase: SpecPhase) {
        val basePath = project.basePath ?: return
        val path = "$basePath/.spec-coding/specs/$workflowId/${phase.outputFileName}"
        val normalizedPath = path.replace('\\', '/')
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalizedPath)
        if (vf == null) {
            addErrorMessage(SpecCodingBundle.message("chat.workflow.action.fileNotFound", normalizedPath))
            return
        }
        OpenFileDescriptor(project, vf, 0, 0).navigate(true)
    }

    private fun editSpecWorkflowMetadata(workflowId: String) {
        val normalizedId = workflowId.trim()
        if (normalizedId.isBlank() || project.isDisposed || _isDisposed) {
            return
        }

        taskCoordinator.launchIo {
            val workflowResult = specEngine.loadWorkflow(normalizedId)
            val workflow = workflowResult.getOrNull()
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || _isDisposed) {
                    return@invokeLater
                }
                if (workflow == null) {
                    showStatus(
                        SpecCodingBundle.message(
                            "toolwindow.spec.sidebar.loadFailed",
                            workflowResult.exceptionOrNull()?.message ?: SpecCodingBundle.message("common.unknown"),
                        ),
                        autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                    )
                    return@invokeLater
                }

                val dialog = EditSpecWorkflowDialog(
                    initialTitle = workflow.title.ifBlank { workflow.id },
                    initialDescription = workflow.description,
                )
                if (!dialog.showAndGet()) {
                    return@invokeLater
                }
                val title = dialog.resultTitle ?: return@invokeLater
                val description = dialog.resultDescription ?: ""

                taskCoordinator.launchIo {
                    val updateResult = specEngine.updateWorkflowMetadata(
                        workflowId = normalizedId,
                        title = title,
                        description = description,
                    )
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        updateResult.onSuccess { updated ->
                            activeSpecWorkflowId = updated.id
                            if (specSidebarVisible) {
                                if (specSidebarPanel.hasFocusedWorkflow(updated.id)) {
                                    specSidebarPanel.focusWorkflow(updated.id)
                                } else {
                                    specSidebarPanel.refreshCurrentWorkflow()
                                }
                            }
                            publishSpecWorkflowChanged(updated.id, reason = "workflow_metadata_updated")
                        }.onFailure { error ->
                            showStatus(
                                SpecCodingBundle.message(
                                    "spec.workflow.error",
                                    error.message ?: SpecCodingBundle.message("common.unknown"),
                                ),
                                autoHideMillis = STATUS_SHORT_HINT_AUTO_HIDE_MILLIS,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun buildSkillContextForSlashCommand(): SkillContext {
        val autoContext = contextCollector.collectContext()
        val selectedCode = autoContext.items
            .firstOrNull { it.type == ContextType.SELECTED_CODE }
            ?.content
        val currentFile = autoContext.items
            .firstOrNull { it.type == ContextType.CURRENT_FILE }
            ?.filePath
        return SkillContext(
            selectedCode = selectedCode,
            currentFile = currentFile,
        )
    }

    private fun executeProviderSlashCommand(
        slashCommand: String,
        providerId: String?,
        commandInfo: CliSlashCommandInfo,
    ) {
        val shellPlan = ImprovedChatPanelProviderSlashCommandCoordinator.buildExecutionPlan(
            slashCommand = slashCommand,
            providerId = providerId,
            commandInfo = commandInfo,
            claudeExecutablePath = CliDiscoveryService.getInstance().claudeInfo.path.ifBlank { "claude" },
            codexExecutablePath = CliDiscoveryService.getInstance().codexInfo.path.ifBlank { "codex" },
        ) ?: run {
            addErrorMessage(
                SpecCodingBundle.message(
                    "toolwindow.slash.command.unsupported.provider",
                    providerDisplayName(providerId).ifBlank { SpecCodingBundle.message("common.unknown") },
                    slashCommand,
                )
            )
            return
        }
        clearComposerInput()
        appendUserMessage(slashCommand)
        taskCoordinator.launchIo {
            val sessionId = ensureActiveSessionAsync(slashCommand, providerId)
            sessionId?.let { persistMessage(it, ConversationRole.USER, slashCommand) }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || _isDisposed) {
                    return@invokeLater
                }
                executeShellCommand(
                    command = shellPlan.shellCommand,
                    requestDescription = shellPlan.requestDescription,
                )
            }
        }
    }

    private fun publishSpecWorkflowChanged(workflowId: String?, reason: String) {
        runCatching {
            project.messageBus.syncPublisher(SpecWorkflowChangedListener.TOPIC)
                .onWorkflowChanged(
                    SpecWorkflowChangedEvent(
                        workflowId = workflowId,
                        reason = reason,
                    ),
                )
        }.onFailure { error ->
            logger.warn("Failed to publish spec workflow changed event", error)
        }
    }

    private fun renderSpecCommandResult(
        result: SpecCommandResult,
        persistSessionId: String?,
        publishReason: String,
    ) {
        result.metadata?.workflowId?.let { workflowId ->
            publishSpecWorkflowChanged(workflowId, reason = publishReason)
        }
        if (result.metadata != null) {
            if (specSidebarVisible) {
                specSidebarPanel.focusWorkflow(result.metadata.workflowId, result.metadata.phase)
            }
            addSpecCardMessage(
                cardMarkdown = result.output,
                metadata = result.metadata,
            )
        } else {
            val panel = addAssistantMessage()
            panel.appendContent(result.output)
            panel.finishMessage()
        }
        if (persistSessionId != null) {
            persistMessageAsync(
                sessionId = persistSessionId,
                role = ConversationRole.ASSISTANT,
                content = result.output,
                metadataJson = result.metadata?.let(SpecCardMetadataCodec::encode),
            )
        }
        appendToConversationHistory(LlmMessage(LlmRole.ASSISTANT, result.output))
    }

    private fun saveSpecCardDocument(
        metadata: SpecCardMetadata,
        editedContent: String,
        forceSave: Boolean,
    ): Result<SpecCardPanelSnapshot> {
        return runCatching {
            val normalized = editedContent
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
            require(normalized.isNotBlank()) { "Document content cannot be blank" }

            val updatedWorkflow = specEngine.updateDocumentContent(
                workflowId = metadata.workflowId,
                phase = metadata.phase,
                content = normalized,
                expectedRevision = if (forceSave) null else metadata.revision,
            ).getOrElse { error ->
                val conflict = error as? DocumentRevisionConflictException
                if (conflict != null) {
                    val latestContent = specEngine.loadWorkflow(metadata.workflowId)
                        .getOrNull()
                        ?.getDocument(metadata.phase)
                        ?.content
                        .orEmpty()
                    throw SpecCardSaveConflictException(
                        latestContent = latestContent,
                        expectedRevision = conflict.expectedRevision,
                        actualRevision = conflict.actualRevision,
                    )
                }
                throw error
            }

            activeSpecWorkflowId = updatedWorkflow.id

            val updatedDocument = updatedWorkflow.getDocument(metadata.phase)
                ?: throw IllegalStateException("Document not found after save: ${metadata.phase.name}")
            val refreshedMetadata = metadata.copy(
                status = updatedWorkflow.status,
                title = updatedWorkflow.title.ifBlank { metadata.title.ifBlank { metadata.workflowId } },
                revision = updatedDocument.metadata.updatedAt,
            )
            val markdown = buildSpecCardMarkdown(
                metadata = refreshedMetadata,
                previewContent = updatedDocument.content,
                summary = SpecCodingBundle.message(
                    if (forceSave) {
                        "toolwindow.spec.card.save.summary.force"
                    } else {
                        "toolwindow.spec.card.save.summary"
                    }
                ),
            )
            publishSpecWorkflowChanged(
                metadata.workflowId,
                reason = if (forceSave) "spec_card_force_save" else "spec_card_save",
            )

            SpecCardPanelSnapshot(
                metadata = refreshedMetadata,
                cardMarkdown = markdown,
                documentContent = updatedDocument.content,
            )
        }
    }

    private fun advanceSpecCardPhase(metadata: SpecCardMetadata) {
        if (isGenerating || isRestoringSession || project.isDisposed || _isDisposed) {
            return
        }
        activeSpecWorkflowId = metadata.workflowId
        val sessionId = currentSessionId
        stopRequested.set(false)
        activeLlmRequest = null
        executionState = executionState.withFinalizing(false)
        setSendingState(true)

        activeOperationJob = taskCoordinator.launchIo {
            try {
                val result = transitionSpecWorkflow(
                    advance = true,
                    sourceCommand = "/workflow next",
                )
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    renderSpecCommandResult(
                        result = result,
                        persistSessionId = sessionId,
                        publishReason = "spec_card_next",
                    )
                }
            } catch (error: Throwable) {
                val stopped = stopRequested.get() || error is CancellationException
                if (!stopped) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        addErrorMessage(
                            SpecCodingBundle.message(
                                "toolwindow.spec.command.failed",
                                error.message ?: SpecCodingBundle.message("common.unknown"),
                            )
                        )
                    }
                }
                if (error is CancellationException) {
                    throw error
                }
            } finally {
                activeOperationJob = null
                activeLlmRequest = null
                stopRequested.set(false)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    setSendingState(false)
                }
            }
        }
    }

    private fun addSystemMessage(content: String) {
        val panel = ChatMessagePanel(ChatMessagePanel.MessageRole.SYSTEM, content)
        panel.finishMessage()
        messagesPanel.addMessage(panel)
    }

    private fun addToolMessage(content: String) {
        val panel = ChatMessagePanel(ChatMessagePanel.MessageRole.SYSTEM, content)
        panel.finishMessage()
        messagesPanel.addMessage(panel)
    }

    private fun addErrorMessage(content: String) {
        val normalized = sanitizeDisplayText(content, dropGarbledLines = true)
            .ifBlank { SpecCodingBundle.message("toolwindow.error.unknown") }
        val panel = ChatMessagePanel(ChatMessagePanel.MessageRole.ERROR, normalized)
        panel.finishMessage()
        messagesPanel.addMessage(panel)
    }

    private fun sanitizeStreamEvent(event: ChatStreamEvent): ChatStreamEvent? {
        val normalizedDetail = sanitizeDisplayText(event.detail, dropGarbledLines = true)
        if (normalizedDetail.isBlank()) return null
        if (isNonInformativeTraceDetail(event.kind, normalizedDetail)) return null
        return if (normalizedDetail == event.detail) {
            event
        } else {
            event.copy(detail = normalizedDetail)
        }
    }

    private fun sanitizePromptEchoEvent(
        event: ChatStreamEvent,
        promptEchoFilter: PromptReferenceEchoFilter,
    ): ChatStreamEvent? {
        val filteredDetail = promptEchoFilter.filter(event.detail, flush = true)
        if (filteredDetail.isBlank()) {
            return null
        }
        return if (filteredDetail == event.detail) {
            event
        } else {
            event.copy(detail = filteredDetail)
        }
    }

    private fun isNonInformativeTraceDetail(kind: ChatTraceKind, detail: String): Boolean {
        val normalized = detail.trim().lowercase(Locale.ROOT)
        return when (kind) {
            ChatTraceKind.VERIFY -> normalized in NON_INFORMATIVE_VERIFY_TRACE_DETAILS
            ChatTraceKind.TASK -> normalized in NON_INFORMATIVE_TASK_TRACE_DETAILS
            else -> false
        }
    }

    private fun sanitizeDisplayText(content: String, dropGarbledLines: Boolean): String {
        if (content.isBlank()) return ""
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\uFFFD', ' ')
            .replace(UI_CONTROL_CHAR_REGEX, "")
        val lines = normalized.lineSequence()
            .map { it.trimEnd() }
            .map { line -> MojibakeTextSupport.repairLineIfNeeded(line) ?: line }
            .filter { line -> line.isNotBlank() || !dropGarbledLines }
            .filter { line -> !dropGarbledLines || !looksLikeGarbledLine(line) }
            .filter { line -> !dropGarbledLines || !looksLikePlaceholderLine(line) }
            .toList()
        return lines.joinToString("\n").trim()
    }

    private fun looksLikeGarbledLine(line: String): Boolean {
        return MojibakeTextSupport.looksLikeGarbledLine(line)
    }

    private fun looksLikePlaceholderLine(line: String): Boolean {
        val normalized = line.trim()
        if (normalized.isBlank()) return true
        if (normalized.length > UI_PLACEHOLDER_MAX_LENGTH) return false
        return UI_PLACEHOLDER_LINE_REGEX.matches(normalized)
    }

    private fun setSendingState(sending: Boolean) {
        executionState = executionState.withSending(sending)
        refreshSendButtonState()
        inputField.isEnabled = true
        providerComboBox.isEnabled = true
        modelComboBox.isEnabled = true
        interactionModeComboBox.isEnabled = true
        if (sending) {
            showBusyStatus()
        } else if (isRestoringSession) {
            showBusyStatus()
        } else {
            hideStatus()
        }
    }

    private fun setResponseFinalizingState(finalizing: Boolean) {
        val updatedState = executionState.withFinalizing(finalizing)
        if (updatedState == executionState) return
        executionState = updatedState
        refreshSendButtonState()
        if (isGenerating && !isRestoringSession) {
            showBusyStatus()
        }
    }

    private fun showBusyStatus(autoHideMillis: Int? = null) {
        val text = currentBusyStatusText() ?: return
        showStatus(text = text, autoHideMillis = autoHideMillis)
    }

    private fun currentBusyStatusText(): String? {
        return resolveExecutionUiState().busyStatusText
    }

    private fun setSessionRestoringState(restoring: Boolean) {
        executionState = executionState.withRestoring(restoring)
        refreshSendButtonState()
    }

    private fun refreshSendButtonState() {
        val uiState = resolveExecutionUiState()
        sendButton.isEnabled = uiState.sendEnabled
        compactButton.isEnabled = uiState.compactEnabled
        refreshActionButtonTexts(uiState.sendAction)
    }

    private fun resolveExecutionUiState(): ImprovedChatPanelExecutionUiState {
        return ImprovedChatPanelExecutionStateCoordinator.resolve(executionState)
    }

    private fun showStatus(
        text: String,
        tooltip: String? = null,
        autoHideMillis: Int? = null,
    ) {
        statusAutoHideTimer?.stop()
        statusAutoHideTimer = null
        statusLabel.text = text
        statusLabel.toolTipText = tooltip
        statusLabel.isVisible = text.isNotBlank()
        if (!statusLabel.isVisible) return
        if (autoHideMillis == null || autoHideMillis <= 0) return

        val timer = Timer(autoHideMillis) {
            if (!isGenerating && !isRestoringSession) {
                hideStatus()
            }
        }
        timer.isRepeats = false
        statusAutoHideTimer = timer
        timer.start()
    }

    private fun hideStatus() {
        statusAutoHideTimer?.stop()
        statusAutoHideTimer = null
        statusLabel.text = ""
        statusLabel.toolTipText = null
        statusLabel.isVisible = false
    }

    private fun refreshActionButtonTexts(sendAction: ComposerSendAction = resolveComposerSendAction()) {
        val tooltip = when {
            isRestoringSession -> SpecCodingBundle.message("toolwindow.status.session.restoring")
            isFinalizingResponse -> SpecCodingBundle.message("toolwindow.status.finalizing")
            else -> sendAction.tooltip
        }
        sendButton.toolTipText = tooltip
        sendButton.accessibleContext.accessibleName = when {
            isRestoringSession || isFinalizingResponse -> tooltip
            else -> sendAction.accessibleName
        }
        sendButton.icon = if (sendAction.kind == ComposerSendActionKind.CHAT_STOP ||
            sendAction.kind == ComposerSendActionKind.TASK_STOP
        ) {
            CHAT_STOP_ICON
        } else {
            CHAT_SEND_ICON
        }
        val compactTooltip = SpecCodingBundle.message("toolwindow.compact.tooltip")
        compactButton.toolTipText = compactTooltip
        compactButton.accessibleContext.accessibleName = compactTooltip
    }

    private fun configureActionButtons() {
        sendButton.icon = CHAT_SEND_ICON
        sendButton.text = ""
        sendButton.isFocusable = false
        sendButton.isFocusPainted = false
        sendButton.isBorderPainted = false
        sendButton.isOpaque = false
        sendButton.isRolloverEnabled = true
        sendButton.margin = JBUI.emptyInsets()
        sendButton.preferredSize = ACTION_ICON_BUTTON_SIZE
        sendButton.minimumSize = ACTION_ICON_BUTTON_SIZE
        sendButton.maximumSize = ACTION_ICON_BUTTON_SIZE
        sendButton.putClientProperty("JButton.buttonType", "toolbar")
        refreshActionButtonTexts()
    }

    private fun configureCompactButton() {
        compactButton.icon = CHAT_COMPACT_ICON
        compactButton.text = ""
        compactButton.isFocusable = false
        compactButton.isFocusPainted = false
        compactButton.isBorderPainted = false
        compactButton.isOpaque = false
        compactButton.isRolloverEnabled = true
        compactButton.margin = JBUI.emptyInsets()
        compactButton.preferredSize = ACTION_ICON_BUTTON_SIZE
        compactButton.minimumSize = ACTION_ICON_BUTTON_SIZE
        compactButton.maximumSize = ACTION_ICON_BUTTON_SIZE
        compactButton.putClientProperty("JButton.buttonType", "toolbar")
        refreshActionButtonTexts()
    }

    private fun configureSpecSidebarToggleButton() {
        specSidebarToggleButton.icon = SPEC_SIDEBAR_TOGGLE_OPEN_ICON
        specSidebarToggleButton.isFocusable = false
        specSidebarToggleButton.isFocusPainted = false
        specSidebarToggleButton.isBorderPainted = false
        specSidebarToggleButton.isContentAreaFilled = false
        specSidebarToggleButton.isOpaque = false
        specSidebarToggleButton.isRolloverEnabled = true
        specSidebarToggleButton.margin = JBUI.emptyInsets()
        specSidebarToggleButton.preferredSize = ACTION_ICON_BUTTON_SIZE
        specSidebarToggleButton.minimumSize = ACTION_ICON_BUTTON_SIZE
        specSidebarToggleButton.maximumSize = ACTION_ICON_BUTTON_SIZE
        specSidebarToggleButton.text = ""
        specSidebarToggleButton.putClientProperty("JButton.buttonType", "toolbar")
        specSidebarToggleButton.addActionListener { toggleSpecSidebar() }
        updateSpecSidebarToggleButtonTexts()
    }

    private fun updateSpecSidebarToggleButtonTexts() {
        val tooltipKey = if (specSidebarVisible) {
            "toolwindow.spec.sidebar.toggle.hide.tooltip"
        } else {
            "toolwindow.spec.sidebar.toggle.show.tooltip"
        }
        val tooltip = SpecCodingBundle.message(tooltipKey)
        specSidebarToggleButton.icon = if (specSidebarVisible) {
            AllIcons.Actions.Close
        } else {
            SPEC_SIDEBAR_TOGGLE_OPEN_ICON
        }
        specSidebarToggleButton.toolTipText = tooltip
        specSidebarToggleButton.accessibleContext.accessibleName = tooltip
    }

    private fun currentInteractionMode(): ChatInteractionMode {
        return interactionModeComboBox.selectedItem as? ChatInteractionMode ?: ChatInteractionMode.VIBE
    }

    private fun continueHandlerFor(mode: ChatInteractionMode): ((ChatMessagePanel) -> Unit)? {
        return if (mode == ChatInteractionMode.SPEC) ::handleContinueMessage else null
    }

    private fun workflowSectionRenderingEnabledFor(mode: ChatInteractionMode): Boolean {
        return mode == ChatInteractionMode.SPEC
    }

    private fun timelineExpandButtonsVisibleFor(mode: ChatInteractionMode): Boolean {
        return true
    }

    private fun refreshContinueActions(mode: ChatInteractionMode) {
        val handler = continueHandlerFor(mode)
        val workflowSectionsEnabled = workflowSectionRenderingEnabledFor(mode)
        val timelineExpandButtonsVisible = timelineExpandButtonsVisibleFor(mode)
        messagesPanel.getAllMessages()
            .filter { message -> message.role == ChatMessagePanel.MessageRole.ASSISTANT }
            .forEach { message ->
                message.updateContinueAction(handler)
                message.setWorkflowSectionsEnabled(workflowSectionsEnabled)
                message.setTimelineExpandButtonsVisible(timelineExpandButtonsVisible)
            }
    }

    private fun onInteractionModeChanged() {
        if (suppressInteractionModeSelectionEvents) {
            return
        }
        setInteractionMode(
            mode = currentInteractionMode(),
            resetSessionOnSpecSwitch = true,
            emitHook = true,
        )
    }

    private fun setInteractionMode(
        mode: ChatInteractionMode,
        resetSessionOnSpecSwitch: Boolean,
        emitHook: Boolean,
    ) {
        if (currentInteractionMode() != mode) {
            suppressInteractionModeSelectionEvents = true
            try {
                interactionModeComboBox.selectedItem = mode
            } finally {
                suppressInteractionModeSelectionEvents = false
            }
        }
        updateInteractionModeComboSize()

        val previousMode = lastInteractionMode
        if (mode == previousMode) {
            applyInteractionModeUi(mode)
            windowStateStore.updateChatInteractionMode(mode.key)
            return
        }

        lastInteractionMode = mode
        if (mode == ChatInteractionMode.SPEC && resetSessionOnSpecSwitch) {
            val workflowId = resolveMostRecentSpecWorkflowIdOrNull()
            startNewSession()
            activeSpecWorkflowId = workflowId
            refreshSpecWorkflowComboBox(selectWorkflowId = workflowId)
        }
        applyInteractionModeUi(mode)
        windowStateStore.updateChatInteractionMode(mode.key)
        if (emitHook) {
            emitChatModeChangedHook(previousMode = previousMode, currentMode = mode)
        }
    }

    private fun emitChatModeChangedHook(
        previousMode: ChatInteractionMode,
        currentMode: ChatInteractionMode,
    ) {
        HookManager.getInstance(project).trigger(
            event = HookEvent.CHAT_MODE_CHANGED,
            triggerContext = HookTriggerContext(
                specStage = currentMode.name,
                metadata = mapOf(
                    "projectName" to project.name,
                    "previousMode" to previousMode.name,
                    "currentMode" to currentMode.name,
                ),
            ),
        )
    }

    private fun applyInteractionModeUi(mode: ChatInteractionMode) {
        refreshContinueActions(mode)
        specSidebarToggleButton.isVisible = mode == ChatInteractionMode.SPEC
        specWorkflowComboBox.isVisible = mode == ChatInteractionMode.SPEC && specWorkflowComboBox.itemCount > 0
        updateWorkflowBindingUi()
        if (mode != ChatInteractionMode.SPEC) {
            applySpecSidebarVisibility(visible = false, persist = false)
            return
        }
        applySpecSidebarVisibility(visible = windowStateStore.snapshot().chatSpecSidebarVisible, persist = false)
    }

    private fun configureContentSplitPane(bottomPanel: JPanel) {
        conversationHostPanel.minimumSize = Dimension(0, JBUI.scale(CHAT_OUTPUT_MIN_HEIGHT))
        contentSplitPane.topComponent = conversationHostPanel
        contentSplitPane.bottomComponent = bottomPanel
        contentSplitPane.resizeWeight = chatComposerDividerProportion.toDouble()
        contentSplitPane.isContinuousLayout = true
        contentSplitPane.isOpaque = false
        contentSplitPane.border = JBUI.Borders.empty()
        configureSplitPaneDivider(contentSplitPane, CHAT_COMPOSER_DIVIDER_SIZE, showGrip = false)
        contentSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) { _ ->
            if (!composerDividerReady || suppressComposerDividerSync) {
                return@addPropertyChangeListener
            }
            val availableHeight = contentSplitPane.height - contentSplitPane.dividerSize
            if (availableHeight <= 0) {
                return@addPropertyChangeListener
            }
            chatComposerDividerProportion = (contentSplitPane.dividerLocation.toFloat() / availableHeight.toFloat())
                .coerceIn(CHAT_COMPOSER_MIN_PROPORTION, CHAT_COMPOSER_MAX_PROPORTION)
            contentSplitPane.resizeWeight = chatComposerDividerProportion.toDouble()
            scheduleComposerDividerPersist()
        }
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || _isDisposed) {
                return@invokeLater
            }
            suppressComposerDividerSync = true
            composerDividerReady = false
            try {
                contentSplitPane.setDividerLocation(chatComposerDividerProportion.toDouble())
            } finally {
                suppressComposerDividerSync = false
                composerDividerReady = true
            }
        }
    }

    private fun configureSplitPaneDivider(splitPane: JSplitPane, dividerSize: Int, showGrip: Boolean = true) {
        splitPane.ui = SidebarGripSplitPaneUI(showGrip)
        splitPane.dividerSize = JBUI.scale(dividerSize)
        splitPane.isOneTouchExpandable = false
        (splitPane.ui as? BasicSplitPaneUI)?.divider?.let { divider ->
            val horizontal = splitPane.orientation == JSplitPane.HORIZONTAL_SPLIT
            divider.cursor = Cursor.getPredefinedCursor(if (horizontal) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR)
            divider.background = JBColor(
                Color(236, 240, 246),
                Color(74, 80, 89),
            )
            divider.border = if (horizontal) {
                JBUI.Borders.customLine(
                    JBColor(
                        Color(217, 223, 232),
                        Color(87, 94, 105),
                    ),
                    0,
                    1,
                    0,
                    1,
                )
            } else {
                JBUI.Borders.customLine(
                    JBColor(
                        Color(217, 223, 232),
                        Color(87, 94, 105),
                    ),
                    1,
                    0,
                    1,
                    0,
                )
            }
        }
    }

    private fun scheduleDividerPersist() {
        if (!specSidebarVisible) return
        dividerPersistTimer?.stop()
        val timer = Timer(SPEC_SIDEBAR_DIVIDER_PERSIST_DEBOUNCE_MILLIS) {
            dividerPersistTimer = null
            if (!project.isDisposed && !_isDisposed && specSidebarVisible) {
                windowStateStore.updateChatSpecSidebar(true, specSidebarDividerLocation)
            }
        }
        timer.isRepeats = false
        dividerPersistTimer = timer
        timer.start()
    }

    private fun scheduleComposerDividerPersist() {
        composerDividerPersistTimer?.stop()
        val timer = Timer(CHAT_COMPOSER_DIVIDER_PERSIST_DEBOUNCE_MILLIS) {
            composerDividerPersistTimer = null
            if (!project.isDisposed && !_isDisposed) {
                windowStateStore.updateChatComposerDividerProportion(chatComposerDividerProportion)
            }
        }
        timer.isRepeats = false
        composerDividerPersistTimer = timer
        timer.start()
    }

    private fun toggleSpecSidebar() {
        if (!specSidebarVisible) {
            val targetWorkflowId = activeSpecWorkflowId
                ?: specSidebarPanel.currentFocusedWorkflowId()
                ?: resolveMostRecentSpecWorkflowIdOrNull()
            if (!targetWorkflowId.isNullOrBlank()) {
                specSidebarPanel.focusWorkflow(targetWorkflowId)
            } else {
                specSidebarPanel.refreshCurrentWorkflow()
            }
        }
        applySpecSidebarVisibility(!specSidebarVisible, persist = true)
    }

    private fun focusSpecSidebar(workflowId: String, phase: SpecPhase?) {
        if (workflowId.isBlank()) return
        activeSpecWorkflowId = workflowId
        specSidebarPanel.focusWorkflow(workflowId, preferredPhase = phase)
        applySpecSidebarVisibility(visible = true, persist = true)
    }

    private fun applySpecSidebarVisibility(visible: Boolean, persist: Boolean) {
        if (specSidebarVisible == visible && conversationHostPanel.componentCount > 0) {
            if (persist) {
                windowStateStore.updateChatSpecSidebar(specSidebarVisible, specSidebarDividerLocation)
            }
            return
        }

        if (!visible) {
            val currentDivider = chatSplitPane.dividerLocation
            if (currentDivider > 0) {
                specSidebarDividerLocation = currentDivider
            }
        }

        conversationHostPanel.removeAll()
        if (visible) {
            if (specSidebarPanel.currentFocusedWorkflowId().isNullOrBlank()) {
                val defaultWorkflowId = activeSpecWorkflowId ?: resolveMostRecentSpecWorkflowIdOrNull()
                if (!defaultWorkflowId.isNullOrBlank()) {
                    activeSpecWorkflowId = defaultWorkflowId
                    specSidebarPanel.focusWorkflow(defaultWorkflowId)
                } else {
                    specSidebarPanel.refreshCurrentWorkflow()
                }
            }
            if (chatSplitPane.leftComponent !== conversationScrollPane) {
                chatSplitPane.leftComponent = conversationScrollPane
            }
            if (chatSplitPane.rightComponent !== specSidebarPanel) {
                chatSplitPane.rightComponent = specSidebarPanel
            }
            conversationHostPanel.add(chatSplitPane, BorderLayout.CENTER)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || _isDisposed || !specSidebarVisible) {
                    return@invokeLater
                }
                chatSplitPane.dividerLocation = specSidebarDividerLocation
                    .coerceAtLeast(SPEC_SIDEBAR_MIN_DIVIDER)
            }
        } else {
            conversationHostPanel.add(conversationScrollPane, BorderLayout.CENTER)
        }
        specSidebarVisible = visible
        updateSpecSidebarToggleButtonTexts()
        conversationHostPanel.revalidate()
        conversationHostPanel.repaint()
        if (persist) {
            windowStateStore.updateChatSpecSidebar(specSidebarVisible, specSidebarDividerLocation)
        }
    }

    private fun configureCompactCombo(comboBox: ComboBox<*>, width: Int) {
        val size = JBDimension(width, 28)
        comboBox.preferredSize = size
        comboBox.minimumSize = size
        comboBox.maximumSize = size
        comboBox.putClientProperty("JComponent.roundRect", false)
        comboBox.putClientProperty("JComboBox.isBorderless", true)
        comboBox.putClientProperty("ComboBox.isBorderless", true)
        comboBox.putClientProperty("JComponent.outline", null)
        comboBox.background = JBColor(Color(248, 250, 252), Color(46, 50, 56))
        comboBox.border = JBUI.Borders.empty(0)
        comboBox.isOpaque = false
        comboBox.font = JBUI.Fonts.smallFont()
    }

    private fun updateProviderComboSize() {
        updateCompactComboSize(providerComboBox)
    }

    private fun updateModelComboSize() {
        updateCompactComboSize(modelComboBox)
    }

    private fun updateInteractionModeComboSize() {
        updateCompactComboSize(interactionModeComboBox)
    }

    private fun installCompactComboAutoWidth(
        comboBox: ComboBox<*>,
        minWidth: Int,
        maxWidth: Int,
    ) {
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = comboBox,
            minWidth = JBUI.scale(minWidth),
            maxWidth = JBUI.scale(maxWidth),
            height = JBUI.scale(COMPACT_COMBO_HEIGHT_PX),
        )
    }

    private fun updateCompactComboSize(comboBox: ComboBox<*>) {
        ComboBoxAutoWidthSupport.refreshSelectedItemAutoWidth(comboBox)
    }

    private fun createCompactComboRenderer(textProvider: (Any?) -> String): DefaultListCellRenderer {
        return object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val label = component as? JLabel ?: return component
                label.text = textProvider(value)
                label.border = if (index == -1) {
                    JBUI.Borders.empty(1, COMPACT_COMBO_SELECTED_LEFT_INSET)
                } else {
                    JBUI.Borders.empty(
                        COMPACT_COMBO_POPUP_VERTICAL_INSET,
                        COMPACT_COMBO_POPUP_HORIZONTAL_INSET,
                        COMPACT_COMBO_POPUP_VERTICAL_INSET,
                        COMPACT_COMBO_POPUP_HORIZONTAL_INSET,
                    )
                }
                return component
            }
        }
    }

    private fun updateSpecWorkflowComboSize() {
        if (currentInteractionMode() != ChatInteractionMode.SPEC) {
            return
        }
        ComboBoxAutoWidthSupport.refreshSelectedItemAutoWidth(specWorkflowComboBox)
    }

    private fun ellipsizeToWidth(text: String, maxWidth: Int, metrics: FontMetrics): String {
        if (maxWidth <= 0) return ""
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return ""
        if (metrics.stringWidth(normalized) <= maxWidth) return normalized
        val ellipsis = "…"
        val ellipsisWidth = metrics.stringWidth(ellipsis)
        val available = (maxWidth - ellipsisWidth).coerceAtLeast(0)
        var low = 0
        var high = normalized.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            val width = metrics.stringWidth(normalized.substring(0, mid))
            if (width <= available) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        val prefix = normalized.substring(0, low).trimEnd()
        return if (prefix.isEmpty()) ellipsis else prefix + ellipsis
    }

    private fun updateComboTooltips() {
        providerComboBox.toolTipText = providerDisplayName(providerComboBox.selectedItem as? String)
        modelComboBox.toolTipText = (modelComboBox.selectedItem as? ModelInfo)?.name
        interactionModeComboBox.toolTipText = SpecCodingBundle.message("toolwindow.chat.mode.tooltip")
        val selectedWorkflow = specWorkflowComboBox.selectedItem as? SpecWorkflowOption
        val workflowId = selectedWorkflow?.workflowId?.trim().orEmpty()
        val workflowTitle = selectedWorkflow?.title?.trim().orEmpty()
        specWorkflowComboBox.toolTipText = workflowId.ifBlank { null }?.let { id ->
            if (workflowTitle.isBlank() || workflowTitle == id) {
                SpecCodingBundle.message("toolwindow.spec.sidebar.workflow", id)
            } else {
                "$workflowTitle\n${SpecCodingBundle.message("toolwindow.spec.sidebar.workflow", id)}"
            }
        }
    }

    private fun providerDisplayName(providerId: String?): String {
        return when (providerId) {
            ClaudeCliLlmProvider.ID -> "claude"
            CodexCliLlmProvider.ID -> "codex"
            MockLlmProvider.ID -> toUiLowercase(SpecCodingBundle.message("toolwindow.model.mock"))
            else -> toUiLowercase(providerId.orEmpty())
        }
    }

    private fun toUiLowercase(value: String): String = value.lowercase(Locale.ROOT)

    private fun handleContinueMessage(panel: ChatMessagePanel) {
        if (isGenerating || isRestoringSession || project.isDisposed || _isDisposed) return
        val focus = if (currentInteractionMode() == ChatInteractionMode.SPEC) {
            resolveMostRecentSpecWorkflowIdOrNull()
                ?.let { workflowId ->
                    sanitizeContinueFocus(
                        "读 .spec-coding/specs/$workflowId/tasks.md，执行第一个未完成任务(- [ ])并更新勾选",
                    )
                }
                ?: resolveContinueFocus(panel)
        } else {
            resolveContinueFocus(panel)
        }
        val continuePrompt = if (!focus.isNullOrBlank()) {
            SpecCodingBundle.message("toolwindow.continue.dynamicPrompt", focus)
        } else {
            SpecCodingBundle.message("toolwindow.continue.defaultPrompt")
        }
        setComposerInput(continuePrompt)
        sendCurrentInput()
    }

    private fun resolveContinueFocus(targetPanel: ChatMessagePanel): String? {
        val allMessages = messagesPanel.getAllMessages()
        val targetIndex = allMessages.indexOf(targetPanel)
        if (targetIndex < 0) return null

        val fromUser = resolveUserContinueFocus(allMessages, targetIndex)

        val fromAssistant = extractAssistantContinueFocus(targetPanel.getContent())
            ?.let(::unwrapGeneratedContinuePrompt)
            ?.let(::sanitizeContinueFocus)
            ?.takeUnless(::isGenericContinueInstruction)
            ?.takeUnless(::isCodeLikeContinueFocus)

        return fromUser ?: fromAssistant
    }

    private fun resolveUserContinueFocus(
        allMessages: List<ChatMessagePanel>,
        beforeIndexExclusive: Int,
    ): String? {
        for (index in beforeIndexExclusive - 1 downTo 0) {
            val candidate = allMessages[index]
            if (candidate.role != ChatMessagePanel.MessageRole.USER) continue

            val focus = candidate.getContent()
                .let(::stripStagePrefix)
                .let(::unwrapGeneratedContinuePrompt)
                .let(::stripStagePrefix)
                .let(::sanitizeContinueFocus)
                ?: continue
            if (isGenericContinueInstruction(focus)) continue
            return focus
        }
        return null
    }

    private fun extractAssistantContinueFocus(content: String): String? {
        if (content.isBlank()) return null
        val lines = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        val heading = lines.firstOrNull { line ->
            line.startsWith("## ") ||
                line.startsWith("### ") ||
                line.startsWith("- ") ||
                line.startsWith("* ") ||
                CONTINUE_ORDERED_LINE_REGEX.matches(line)
        }

        return (heading ?: lines.firstOrNull())
            ?.removePrefix("## ")
            ?.removePrefix("### ")
            ?.removePrefix("- ")
            ?.removePrefix("* ")
            ?.replace(CONTINUE_ORDERED_PREFIX_REGEX, "")
            ?.trim()
    }

    private fun stripStagePrefix(content: String): String {
        return content.trim().replace(CONTINUE_STAGE_PREFIX_REGEX, "").trim()
    }

    private fun unwrapGeneratedContinuePrompt(content: String): String {
        var current = content.trim()
        repeat(CONTINUE_PROMPT_UNWRAP_MAX_DEPTH) {
            val next = extractGeneratedContinueFocus(current)?.trim()
            if (next.isNullOrBlank() || next == current) return current
            current = next
        }
        return current
    }

    private fun extractGeneratedContinueFocus(content: String): String? {
        val normalized = content.trim()
        CONTINUE_DYNAMIC_ZH_REGEX.matchEntire(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        CONTINUE_DYNAMIC_EN_REGEX.matchEntire(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }

        if (normalized.startsWith("请承接上一轮输出继续执行")) {
            return normalized
                .substringAfter("重点", "")
                .substringAfter("：", "")
                .substringAfter(":", "")
                .replace(Regex("""。?\s*避免重复已完成内容.*$"""), "")
                .trim()
                .ifBlank { null }
        }
        if (normalized.lowercase(Locale.ROOT).startsWith("continue from the previous result with focus")) {
            return normalized
                .substringAfter("focus", "")
                .substringAfter(":", "")
                .substringAfter("：", "")
                .replace(Regex("""\.?\s*avoid repeating completed work.*$""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { null }
        }
        return null
    }

    private fun sanitizeContinueFocus(content: String): String? {
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(CONTINUE_FOCUS_MAX_LENGTH)
            .ifBlank { null }
    }

    private fun isCodeLikeContinueFocus(content: String): Boolean {
        val normalized = content.trim()
        if (normalized.isBlank()) return true
        if (normalized.length > 64) return false
        if (normalized.contains(' ')) return false
        if (UI_CJK_REGEX.containsMatchIn(normalized)) return false
        return CONTINUE_CODE_LIKE_FOCUS_REGEX.matches(normalized)
    }

    private fun isGenericContinueInstruction(content: String): Boolean {
        val normalized = content.lowercase(Locale.ROOT)
        return normalized.contains("plan/execute/verify") ||
            normalized.contains("continue with the current") ||
            normalized.contains("continue from the previous result with focus") ||
            normalized.contains("avoid repeating completed work") ||
            normalized.contains("继续按当前") ||
            normalized.contains("继续执行下一步") ||
            normalized.contains("请承接上一轮输出继续执行")
    }

    private fun handleWorkflowCommandExecute(command: String) {
        val normalizedCommand = command.trim()
        if (normalizedCommand.isBlank() || project.isDisposed || _isDisposed) {
            return
        }
        if (isGenerating || isRestoringSession) {
            showBusyStatus()
            return
        }

        if (normalizedCommand.startsWith("/")) {
            handleSlashCommand(normalizedCommand)
            return
        }

        executeShellCommand(
            command = normalizedCommand,
            requestDescription = "Workflow quick action command: $normalizedCommand",
            target = ImprovedChatPanelShellCommandExecutionTarget.IdeTerminal(
                projectBasePath = project.basePath,
                userHome = System.getProperty("user.home"),
                composerText = inputField.text.orEmpty(),
                composerCaret = inputField.caretPosition,
                currentComposerText = { inputField.text.orEmpty() },
            ),
        )
    }

    private fun executeShellCommand(
        command: String,
        requestDescription: String,
        target: ImprovedChatPanelShellCommandExecutionTarget = ImprovedChatPanelShellCommandExecutionTarget.Background,
    ) {
        if (project.isDisposed || _isDisposed) {
            return
        }
        applyShellCommandExecutionPlan(
            shellCommandRuntimeFacade.prepareExecution(
                ImprovedChatPanelShellCommandExecutionRequest(
                    command = command,
                    requestDescription = requestDescription,
                    target = target,
                ),
            ),
        )
    }

    private fun applyShellCommandExecutionPlan(
        plan: ImprovedChatPanelShellCommandExecutionPlan,
    ) {
        when (plan) {
            ImprovedChatPanelShellCommandExecutionPlan.NoOp -> Unit

            is ImprovedChatPanelShellCommandExecutionPlan.PermissionDenied -> {
                addErrorMessage(plan.errorMessage)
            }

            is ImprovedChatPanelShellCommandExecutionPlan.LaunchInBackground -> {
                plan.preExecutionSystemMessage?.let(::addSystemMessage)
                taskCoordinator.launchIo {
                    runWorkflowShellCommandInBackground(plan.dispatchRequest)
                }
            }

            is ImprovedChatPanelShellCommandExecutionPlan.ApplyImmediateResult -> {
                plan.preExecutionSystemMessage?.let(::addSystemMessage)
                plan.restorePlan?.let(::applyComposerRestorePlan)
                plan.launchError?.let { error ->
                    logger.warn("Failed to open IDE terminal for workflow command", error)
                }
                applyWorkflowCommandFeedback(
                    feedback = plan.feedback,
                    persistAsync = plan.persistAsync,
                    operationRequest = plan.operationRequest,
                )
            }
        }
    }

    private fun applyComposerRestorePlan(restorePlan: ImprovedChatPanelComposerRestorePlan) {
        suppressComposerAutoCollapse = true
        try {
            inputField.text = restorePlan.text
            inputField.caretPosition = restorePlan.caret.coerceIn(0, inputField.text.length)
        } finally {
            suppressComposerAutoCollapse = false
        }
        syncComposerInputStateWithCurrentText()
    }

    private fun renderWorkflowCommandFeedback(feedback: ImprovedChatPanelWorkflowCommandFeedback) {
        feedback.statusMessage?.let(::showStatus)
        feedback.conversationMessage?.let { message ->
            when (feedback.conversationMessageKind) {
                ImprovedChatPanelWorkflowCommandFeedbackMessageKind.SYSTEM -> addSystemMessage(message)
                ImprovedChatPanelWorkflowCommandFeedbackMessageKind.ERROR -> addErrorMessage(message)
                null -> Unit
            }
        }
        if (feedback.shouldHideStatus && !isGenerating && !isRestoringSession) {
            hideStatus()
        }
    }

    private fun persistWorkflowCommandFeedback(
        feedback: ImprovedChatPanelWorkflowCommandFeedback,
        async: Boolean,
    ) {
        val message = feedback.conversationMessage ?: return
        currentSessionId?.let { sessionId ->
            if (async) {
                persistMessageAsync(
                    sessionId = sessionId,
                    role = ConversationRole.TOOL,
                    content = message,
                )
            } else {
                persistMessage(
                    sessionId = sessionId,
                    role = ConversationRole.TOOL,
                    content = message,
                )
            }
        }
    }

    private fun handleWorkflowCommandStop(command: String) {
        if (project.isDisposed || _isDisposed) {
            return
        }

        val stopPlan = shellCommandRuntimeFacade.prepareStop(command) ?: return
        applyWorkflowCommandFeedback(
            feedback = stopPlan.immediateFeedback,
            persistAsync = stopPlan.persistAsync,
        )
        if (!stopPlan.shouldAttemptStop) {
            return
        }

        taskCoordinator.launchIo {
            val stopOutcomePlan = shellCommandRuntimeFacade.performStop(stopPlan)
            if (stopOutcomePlan != null) {
                applyWorkflowCommandFeedback(
                    feedback = stopOutcomePlan.feedback,
                    persistAsync = stopOutcomePlan.persistAsync,
                    renderOnUiThread = true,
                )
            }
        }
    }

    private fun runWorkflowShellCommandInBackground(
        dispatchRequest: ImprovedChatPanelShellCommandDispatchRequest,
    ) {
        val backgroundResult = shellCommandRuntimeFacade.executeInBackground(
            ImprovedChatPanelWorkflowCommandBackgroundRequest(
                dispatchRequest = dispatchRequest,
                shouldHideStatus = !isGenerating && !isRestoringSession,
            ),
        )
        backgroundResult.changesetPlan?.let { changesetPlan ->
            persistWorkflowCommandChangeset(
                command = changesetPlan.command,
                execution = changesetPlan.execution,
                beforeSnapshot = changesetPlan.beforeSnapshot,
            )
        }
        applyWorkflowCommandFeedback(
            feedback = backgroundResult.feedback,
            persistAsync = backgroundResult.persistAsync,
            operationRequest = backgroundResult.operationRequest,
            renderOnUiThread = true,
        )
    }

    private fun applyWorkflowCommandFeedback(
        feedback: ImprovedChatPanelWorkflowCommandFeedback,
        persistAsync: Boolean,
        operationRequest: OperationRequest? = null,
        renderOnUiThread: Boolean = false,
    ) {
        feedback.operationRecordedSuccess?.let { success ->
            operationRequest?.let { request ->
                modeManager.recordOperation(request, success = success)
            }
        }
        if (renderOnUiThread) {
            taskCoordinator.invokeLater {
                renderWorkflowCommandFeedback(feedback)
            }
        } else {
            renderWorkflowCommandFeedback(feedback)
        }
        persistWorkflowCommandFeedback(feedback, async = persistAsync)
    }

    private fun captureWorkspaceSnapshot(): WorkspaceChangesetCollector.Snapshot? {
        val basePath = project.basePath ?: return null
        val root = runCatching { Paths.get(basePath).toAbsolutePath().normalize() }.getOrNull() ?: return null
        return runCatching {
            WorkspaceChangesetCollector.capture(root)
        }.onFailure {
            logger.debug("Failed to capture workspace snapshot", it)
        }.getOrNull()
    }

    private fun persistWorkflowCommandChangeset(
        command: String,
        execution: ImprovedChatPanelWorkflowCommandExecutionResult,
        beforeSnapshot: WorkspaceChangesetCollector.Snapshot?,
    ) {
        chatPersistenceCoordinator.persistWorkflowCommandChangeset(
            command = command,
            beforeSnapshot = beforeSnapshot,
            success = execution.success,
            exitCode = execution.exitCode,
            timedOut = execution.timedOut,
            stoppedByUser = execution.stoppedByUser,
            outputTruncated = execution.outputTruncated,
        ).onFailure {
            logger.warn("Failed to persist workflow command changeset", it)
        }
    }

    private fun persistAssistantResponseChangeset(
        requestText: String,
        providerId: String?,
        modelId: String?,
        beforeSnapshot: WorkspaceChangesetCollector.Snapshot?,
        hasExecutionTrace: Boolean,
    ) {
        chatPersistenceCoordinator.persistAssistantResponseChangeset(
            requestText = requestText,
            providerId = providerId,
            modelId = modelId,
            beforeSnapshot = beforeSnapshot,
            hasExecutionTrace = hasExecutionTrace,
        ).onFailure {
            logger.warn("Failed to persist assistant response changeset", it)
        }
    }

    private fun handleWorkflowFileOpen(fileAction: com.eacape.speccodingplugin.ui.chat.WorkflowQuickActionParser.FileAction) {
        val basePath = project.basePath ?: return
        val normalized = fileAction.path.replace('\\', '/')
        val resolvedPath = if (normalized.startsWith("/") || normalized.matches(Regex("^[A-Za-z]:/.*"))) {
            normalized
        } else {
            "$basePath/$normalized"
        }

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedPath)
        if (vf == null) {
            addErrorMessage(SpecCodingBundle.message("chat.workflow.action.fileNotFound", fileAction.path))
            return
        }

        val line = fileAction.line?.coerceAtLeast(1)?.minus(1) ?: 0
        OpenFileDescriptor(project, vf, line, 0).navigate(true)
    }

    private fun handleDeleteMessage(panel: ChatMessagePanel) {
        if (isGenerating) return
        userMessageRawContent.remove(panel)
        removeTaskExecutionPanelReference(panel)
        messagesPanel.removeMessage(panel)
        // 同步删除对话历史中对应的消息
        rebuildConversationHistory()
    }

    private fun handleRegenerateMessage(panel: ChatMessagePanel) {
        if (isGenerating || isRestoringSession) return

        // 找到对应的 user 消息（前一条）
        val allMessages = messagesPanel.getAllMessages()
        val index = allMessages.indexOf(panel)
        if (index <= 0) return

        val userPanel = allMessages[index - 1]
        if (userPanel.role != ChatMessagePanel.MessageRole.USER) return

        val rawUserInput = userMessageRawContent[userPanel] ?: userPanel.getContent()
        val interactionMode = currentInteractionMode()
        val promptReference = resolvePromptReferences(rawUserInput)
        val userInput = buildChatInput(
            mode = interactionMode,
            userInput = promptReference.cleanedInput.ifBlank { rawUserInput },
        )
        val promptSystemMessages = buildPromptReferenceSystemMessages(
            referencedPrompts = promptReference.templates,
            userInput = promptReference.cleanedInput,
        )

        // 删除当前 assistant 消息
        messagesPanel.removeMessage(panel)
        rebuildConversationHistory()

        // 重新发送
        val providerId = providerComboBox.selectedItem as? String
        val modelId = (modelComboBox.selectedItem as? ModelInfo)?.id
        val operationMode = modeManager.getCurrentMode()
        val beforeSnapshot = captureWorkspaceSnapshot()
        val requestId = UUID.randomUUID().toString()
        val resolvedProviderId = resolveProviderIdForRequest(providerId)
        stopRequested.set(false)
        activeLlmRequest = ActiveLlmRequest(providerId = resolvedProviderId, requestId = requestId)
        executionState = executionState.withFinalizing(false)
        setSendingState(true)
        val assistantStartedAtMillis = System.currentTimeMillis()
        val assistantPanel = addAssistantMessage(startedAtMillis = assistantStartedAtMillis)
        currentAssistantPanel = assistantPanel
        val promptEchoFilter = PromptReferenceEchoFilter.fromTextBlocks(promptSystemMessages)

        activeOperationJob = taskCoordinator.launchIo {
            val streamedTraceEvents = mutableListOf<ChatStreamEvent>()
            val assistantContent = StringBuilder()
            val pendingDelta = StringBuilder()
            val pendingEvents = mutableListOf<ChatStreamEvent>()
            val autoStopIssued = AtomicBoolean(false)
            val uiFlushScheduled = AtomicBoolean(false)
            var assistantFinishedAtMillis: Long? = null
            var pendingChunks = 0
            var lastFlushAtNanos = System.nanoTime()

            fun flushPending(force: Boolean = false) {
                val now = System.nanoTime()
                val dueByTime = now - lastFlushAtNanos >= STREAM_BATCH_INTERVAL_NANOS
                val shouldFlush = force ||
                    dueByTime ||
                    pendingChunks >= STREAM_BATCH_CHUNK_COUNT ||
                    pendingDelta.length >= STREAM_BATCH_CHAR_COUNT
                if (!shouldFlush) return
                if (pendingDelta.isEmpty() && pendingEvents.isEmpty()) {
                    pendingChunks = 0
                    return
                }
                if (!force && uiFlushScheduled.get()) return

                val delta = pendingDelta.toString()
                val events = pendingEvents.toList()
                pendingDelta.setLength(0)
                pendingEvents.clear()
                pendingChunks = 0
                lastFlushAtNanos = now
                if (!uiFlushScheduled.compareAndSet(false, true)) {
                    pendingDelta.insert(0, delta)
                    if (events.isNotEmpty()) {
                        pendingEvents.addAll(0, events)
                    }
                    pendingChunks += 1
                    return
                }

                ApplicationManager.getApplication().invokeLater {
                    uiFlushScheduled.set(false)
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    if (stopRequested.get()) {
                        return@invokeLater
                    }
                    assistantPanel.appendStreamContent(delta, events)
                }
            }
            try {
                projectService.chat(
                    providerId = resolvedProviderId,
                    userInput = userInput,
                    modelId = modelId,
                    conversationHistory = buildConversationHistoryForRequest(),
                    additionalSystemMessages = promptSystemMessages,
                    operationMode = operationMode,
                    planExecuteVerifySections = workflowSectionRenderingEnabledFor(interactionMode),
                    requestId = requestId,
                ) { chunk ->
                    if (stopRequested.get()) {
                        throw CancellationException("Stopped by user")
                    }
                    val filteredDelta = promptEchoFilter.filter(chunk.delta, flush = chunk.isLast)
                    if (filteredDelta.isNotEmpty()) {
                        assistantContent.append(filteredDelta)
                        pendingDelta.append(filteredDelta)
                    }
                    chunk.event
                        ?.let(::sanitizeStreamEvent)
                        ?.let { event -> sanitizePromptEchoEvent(event, promptEchoFilter) }
                        ?.let { event ->
                            pendingEvents += event
                            streamedTraceEvents += event
                            if (shouldAutoStopAfterMcpVerification(event)) {
                                requestAutoStopAfterMcpVerification(
                                    providerId = resolvedProviderId,
                                    requestId = requestId,
                                    autoStopIssued = autoStopIssued,
                                )
                            }
                        }
                    pendingChunks += 1

                    if (chunk.isLast) {
                        assistantFinishedAtMillis = System.currentTimeMillis()
                        flushPending(force = true)
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed || _isDisposed) {
                                return@invokeLater
                            }
                            if (stopRequested.get()) {
                                return@invokeLater
                            }
                            setResponseFinalizingState(true)
                            assistantPanel.finishMessage()
                            messagesPanel.scrollToBottom()
                        }
                    } else {
                        flushPending(force = false)
                    }
                }
                val assistantMessage = LlmMessage(LlmRole.ASSISTANT, assistantContent.toString())
                appendToConversationHistory(assistantMessage)
                currentSessionId?.let { sessionId ->
                    persistMessage(
                        sessionId = sessionId,
                        role = ConversationRole.ASSISTANT,
                        content = assistantMessage.content,
                        metadataJson = TraceEventMetadataCodec.encode(
                            events = streamedTraceEvents,
                            startedAtMillis = assistantStartedAtMillis,
                            finishedAtMillis = assistantFinishedAtMillis ?: System.currentTimeMillis(),
                        ),
                    )
                }
            } catch (error: Throwable) {
                val stopped = stopRequested.get() || error is CancellationException
                if (!stopped) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || _isDisposed) {
                            return@invokeLater
                        }
                        addErrorMessage(error.message ?: SpecCodingBundle.message("toolwindow.error.unknown"))
                    }
                } else {
                    val partial = assistantContent.toString()
                    if (partial.isNotBlank()) {
                        val assistantMessage = LlmMessage(LlmRole.ASSISTANT, partial)
                        appendToConversationHistory(assistantMessage)
                        currentSessionId?.let { sessionId ->
                            persistMessage(
                                sessionId = sessionId,
                                role = ConversationRole.ASSISTANT,
                                content = assistantMessage.content,
                                metadataJson = TraceEventMetadataCodec.encode(
                                    events = streamedTraceEvents,
                                    startedAtMillis = assistantStartedAtMillis,
                                    finishedAtMillis = assistantFinishedAtMillis ?: System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                }
                if (error is CancellationException) {
                    throw error
                }
            } finally {
                activeOperationJob = null
                activeLlmRequest = null
                val wasStopped = stopRequested.get()
                if (wasStopped) {
                    pendingDelta.setLength(0)
                    pendingEvents.clear()
                    pendingChunks = 0
                } else {
                    flushPending(force = true)
                }
                stopRequested.set(false)

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    assistantPanel.finishMessage()
                    setSendingState(false)
                }
                runCatching {
                    persistAssistantResponseChangeset(
                        requestText = userInput,
                        providerId = providerId,
                        modelId = modelId,
                        beforeSnapshot = beforeSnapshot,
                        hasExecutionTrace = streamedTraceEvents.isNotEmpty(),
                    )
                }.onFailure {
                    logger.warn("Failed to persist assistant response changeset after regenerate request", it)
                }
            }
        }
    }

    private fun buildConversationHistoryForRequest(): List<LlmMessage> {
        val summary = compactedConversationSummary?.trim().orEmpty()
        if (summary.isBlank()) {
            return conversationHistory.toList()
        }
        val cutoff = compactedConversationCutoff.coerceIn(0, conversationHistory.size)
        val incrementalMessages = conversationHistory.drop(cutoff)
        val tailMessages = if (incrementalMessages.size > COMPACT_REQUEST_TAIL_MAX_MESSAGES) {
            incrementalMessages.takeLast(COMPACT_REQUEST_TAIL_MAX_MESSAGES)
        } else {
            incrementalMessages
        }
        return buildList(1 + tailMessages.size) {
            add(LlmMessage(LlmRole.SYSTEM, summary))
            addAll(tailMessages)
        }
    }

    private fun buildCompactionSourceMessages(): List<LlmMessage> {
        val summary = compactedConversationSummary?.trim().orEmpty()
        val cutoff = compactedConversationCutoff.coerceIn(0, conversationHistory.size)
        val incrementalMessages = conversationHistory.drop(cutoff)
            .filter { it.content.isNotBlank() }
        if (summary.isBlank()) {
            return if (conversationHistory.isEmpty()) {
                emptyList()
            } else {
                conversationHistory.toList()
            }
        }
        if (incrementalMessages.isEmpty()) {
            return emptyList()
        }
        return buildList(1 + incrementalMessages.size) {
            add(LlmMessage(LlmRole.SYSTEM, summary))
            addAll(incrementalMessages)
        }
    }

    private suspend fun buildSemanticCompactedConversationSummary(
        sourceMessages: List<LlmMessage>,
        providerId: String,
        modelId: String?,
        requestId: String,
    ): String {
        val transcript = buildCompactionTranscript(sourceMessages)
        if (transcript.isBlank()) {
            return ""
        }
        val workingDirectory = project.basePath?.trim()?.ifBlank { null }
        val request = LlmRequest(
            messages = listOf(
                LlmMessage(
                    role = LlmRole.SYSTEM,
                    content = COMPACT_MODEL_SYSTEM_PROMPT,
                ),
                LlmMessage(
                    role = LlmRole.USER,
                    content = buildCompactionModelUserPrompt(transcript),
                ),
            ),
            model = modelId,
            metadata = linkedMapOf<String, String>().apply {
                put("requestId", requestId)
                put("operation_mode", OperationMode.PLAN.name)
            },
            workingDirectory = workingDirectory,
        )
        val response = llmRouter.generate(providerId = providerId, request = request)
        val normalized = sanitizeDisplayText(response.content, dropGarbledLines = true)
        return compactConversationContent(normalized, COMPACT_SUMMARY_MAX_CHARS)
    }

    private fun buildCompactionModelUserPrompt(transcript: String): String {
        return buildString {
            appendLine("Please compact this conversation transcript for future follow-up turns.")
            appendLine("Return only plain text summary; no markdown headers, no code fences.")
            appendLine("Focus on: user goals, constraints, decisions, files/paths, commands, unresolved items.")
            appendLine("Transcript:")
            append(transcript)
        }
    }

    private fun buildCompactionTranscript(messages: List<LlmMessage>): String {
        if (messages.isEmpty()) {
            return ""
        }
        val normalizedMessages = if (messages.size <= COMPACT_MODEL_SOURCE_MAX_MESSAGES) {
            messages
        } else {
            val headCount = COMPACT_MODEL_SOURCE_HEAD_MESSAGES.coerceAtMost(COMPACT_MODEL_SOURCE_MAX_MESSAGES - 1)
            val tailCount = (COMPACT_MODEL_SOURCE_MAX_MESSAGES - headCount - 1).coerceAtLeast(0)
            buildList {
                addAll(messages.take(headCount))
                add(LlmMessage(LlmRole.SYSTEM, "... ${messages.size - headCount - tailCount} earlier messages omitted ..."))
                if (tailCount > 0) {
                    addAll(messages.takeLast(tailCount))
                }
            }
        }
        return normalizedMessages.mapNotNull { message ->
            val roleLabel = when (message.role) {
                LlmRole.USER -> "User"
                LlmRole.ASSISTANT -> "Assistant"
                LlmRole.SYSTEM -> "System"
            }
            val compactedContent = compactConversationContent(
                content = message.content,
                maxLength = COMPACT_MODEL_SOURCE_ENTRY_MAX_CHARS,
            )
            if (compactedContent.isBlank()) {
                null
            } else {
                "$roleLabel: $compactedContent"
            }
        }.joinToString(separator = "\n")
    }

    private fun applyCompactedConversationSummary(summary: String): Boolean {
        val normalized = summary.trim()
        if (normalized.isBlank()) {
            return false
        }
        compactedConversationSummary = normalized
        compactedConversationCutoff = conversationHistory.size
        return true
    }

    private fun compactConversationContent(content: String, maxLength: Int): String {
        if (content.isBlank()) {
            return ""
        }
        val normalized = COMPACT_CODE_BLOCK_REGEX
            .replace(content, " [code omitted] ")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace(COMPACT_WHITESPACE_REGEX, " ")
            .trim()
        if (normalized.length <= maxLength) {
            return normalized
        }
        return normalized.take(maxLength).trimEnd()
    }

    private fun clearCompactedConversationState() {
        compactedConversationSummary = null
        compactedConversationCutoff = 0
    }

    private fun rebuildConversationHistory() {
        conversationHistory.clear()
        for (panel in messagesPanel.getAllMessages()) {
            val role = when (panel.role) {
                ChatMessagePanel.MessageRole.USER -> LlmRole.USER
                ChatMessagePanel.MessageRole.ASSISTANT -> LlmRole.ASSISTANT
                ChatMessagePanel.MessageRole.SYSTEM -> LlmRole.SYSTEM
                ChatMessagePanel.MessageRole.ERROR -> continue
            }
            val content = if (panel.role == ChatMessagePanel.MessageRole.USER) {
                userMessageRawContent[panel] ?: panel.getContent()
            } else {
                panel.getContent()
            }
            appendToConversationHistory(LlmMessage(role, content))
        }
        clearCompactedConversationState()
    }

    private fun appendToConversationHistory(message: LlmMessage) {
        conversationHistory.add(message)
        trimConversationHistoryIfNeeded()
    }

    private fun trimConversationHistoryIfNeeded() {
        if (conversationHistory.size <= MAX_CONVERSATION_HISTORY) {
            return
        }
        val overflow = conversationHistory.size - MAX_CONVERSATION_HISTORY
        repeat(overflow) {
            conversationHistory.removeAt(0)
            if (compactedConversationCutoff > 0) {
                compactedConversationCutoff -= 1
            }
        }
    }

    override fun dispose() {
        _isDisposed = true
        pasteKeyDispatcher?.let { dispatcher ->
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
        }
        pasteKeyDispatcher = null
        statusAutoHideTimer?.stop()
        statusAutoHideTimer = null
        dividerPersistTimer?.stop()
        dividerPersistTimer = null
        composerDividerPersistTimer?.stop()
        composerDividerPersistTimer = null
        shellCommandRuntimeFacade.dispose()
        clearImageAttachments()
        composerInputState = ImprovedChatPanelComposerInputCoordinator.clear()
        userMessageRawContent.clear()
        CliDiscoveryService.getInstance().removeDiscoveryListener(discoveryListener)
        specTaskExecutionService.removeLiveProgressListener(taskExecutionLiveProgressListener)
        taskExecutionLiveProgressFlushTimer.stop()
        synchronized(pendingTaskExecutionLiveProgressLock) {
            pendingTaskExecutionLiveProgressByRunId.clear()
        }
        activeTaskExecutionPanel = null
        latestObservedTaskLiveProgress = null
        taskCoordinator.dispose()
    }

    private class SidebarGripSplitPaneUI(
        private val showGrip: Boolean = true,
    ) : BasicSplitPaneUI() {
        override fun createDefaultDivider(): BasicSplitPaneDivider {
            return object : BasicSplitPaneDivider(this) {
                override fun paint(g: Graphics) {
                    super.paint(g)
                    if (!showGrip) {
                        return
                    }
                    val g2 = g as? Graphics2D ?: return
                    val horizontal = splitPane?.orientation == JSplitPane.HORIZONTAL_SPLIT
                    val w = width
                    val h = height
                    if (w <= 0 || h <= 0) return

                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    if (horizontal) {
                        paintVerticalGrip(g2, w, h)
                    } else {
                        paintHorizontalGrip(g2, w, h)
                    }
                }

                private fun paintVerticalGrip(g2: Graphics2D, w: Int, h: Int) {
                    val trackWidth = JBUI.scale(3)
                    val trackHeight = JBUI.scale(30)
                    val trackX = (w - trackWidth) / 2
                    val trackY = (h - trackHeight) / 2
                    g2.color = JBColor(
                        Color(225, 232, 242),
                        Color(92, 99, 111),
                    )
                    g2.fillRoundRect(trackX, trackY, trackWidth, trackHeight, trackWidth, trackWidth)

                    val dotSize = JBUI.scale(2)
                    val dotStep = JBUI.scale(5)
                    val dotX = (w - dotSize) / 2
                    val dotStart = trackY + JBUI.scale(5)
                    val dotEnd = trackY + trackHeight - dotSize - JBUI.scale(4)
                    g2.color = JBColor(
                        Color(152, 166, 186),
                        Color(167, 179, 196),
                    )
                    var y = dotStart
                    while (y <= dotEnd) {
                        g2.fillOval(dotX, y, dotSize, dotSize)
                        y += dotStep
                    }
                }

                private fun paintHorizontalGrip(g2: Graphics2D, w: Int, h: Int) {
                    val trackWidth = JBUI.scale(30)
                    val trackHeight = JBUI.scale(3)
                    val trackX = (w - trackWidth) / 2
                    val trackY = (h - trackHeight) / 2
                    g2.color = JBColor(
                        Color(225, 232, 242),
                        Color(92, 99, 111),
                    )
                    g2.fillRoundRect(trackX, trackY, trackWidth, trackHeight, trackHeight, trackHeight)

                    val dotSize = JBUI.scale(2)
                    val dotStep = JBUI.scale(5)
                    val dotY = (h - dotSize) / 2
                    val dotStart = trackX + JBUI.scale(5)
                    val dotEnd = trackX + trackWidth - dotSize - JBUI.scale(4)
                    g2.color = JBColor(
                        Color(152, 166, 186),
                        Color(167, 179, 196),
                    )
                    var x = dotStart
                    while (x <= dotEnd) {
                        g2.fillOval(x, dotY, dotSize, dotSize)
                        x += dotStep
                    }
                }
            }
        }
    }

    private fun isSpecWorkflowCreateCommand(command: String): Boolean {
        val args = workflowChatCommandArgs(command)
        if (args.isBlank() || args.equals("help", ignoreCase = true)) {
            return false
        }
        val token = args.substringBefore(" ").trim().lowercase(Locale.ROOT)
        return token !in setOf("status", "open", "next", "back", "generate", "complete")
    }

    internal fun workflowBindingSnapshotForTest(): Map<String, String> {
        val sendAction = resolveComposerSendAction()
        return mapOf(
            "mode" to currentInteractionMode().name,
            "workflowId" to resolvedActiveWorkflowChatBinding()?.workflowId.orEmpty(),
            "taskId" to resolvedCurrentWorkflowChatExecutionContext()?.taskId.orEmpty(),
            "workflowChipVisible" to (workflowBindingChip.parent != null && workflowBindingChip.isVisible).toString(),
            "workflowChipText" to workflowBindingChip.text.orEmpty(),
            "workflowChipTooltip" to workflowBindingChip.toolTipText.orEmpty(),
            "taskChipVisible" to taskBindingChip.isVisible.toString(),
            "taskChipText" to taskBindingChip.text.orEmpty(),
            "taskChipTooltip" to taskBindingChip.toolTipText.orEmpty(),
            "taskChipIconId" to SpecWorkflowIcons.debugId(taskBindingChip.icon),
            "taskChipBorderPainted" to taskBindingChip.isBorderPainted.toString(),
            "taskChipContentFilled" to taskBindingChip.isContentAreaFilled.toString(),
            "taskChipOpaque" to taskBindingChip.isOpaque.toString(),
            "executeTaskVisible" to executeTaskBindingButton.isVisible.toString(),
            "executeTaskEnabled" to executeTaskBindingButton.isEnabled.toString(),
            "executeTaskIconId" to SpecWorkflowIcons.debugId(executeTaskBindingButton.icon),
            "executeTaskTooltip" to executeTaskBindingButton.toolTipText.orEmpty(),
            "retryTaskVisible" to retryTaskBindingButton.isVisible.toString(),
            "retryTaskEnabled" to retryTaskBindingButton.isEnabled.toString(),
            "retryTaskIconId" to SpecWorkflowIcons.debugId(retryTaskBindingButton.icon),
            "retryTaskTooltip" to retryTaskBindingButton.toolTipText.orEmpty(),
            "completeTaskVisible" to completeTaskBindingButton.isVisible.toString(),
            "completeTaskEnabled" to completeTaskBindingButton.isEnabled.toString(),
            "completeTaskIconId" to SpecWorkflowIcons.debugId(completeTaskBindingButton.icon),
            "completeTaskTooltip" to completeTaskBindingButton.toolTipText.orEmpty(),
            "taskMoreVisible" to taskBindingOverflowButton.isVisible.toString(),
            "taskMoreTooltip" to taskBindingOverflowButton.toolTipText.orEmpty(),
            "taskClearVisible" to clearTaskBindingButton.isVisible.toString(),
            "taskClearTooltip" to clearTaskBindingButton.toolTipText.orEmpty(),
            "sendActionKind" to sendAction.kind.name,
            "sendTooltip" to sendButton.toolTipText.orEmpty(),
            "sendEnabled" to sendButton.isEnabled.toString(),
            "composerAccessoryVisible" to composerAccessoryStrip.isVisible.toString(),
            "contextSummaryVisible" to contextPreviewPanel.isVisible.toString(),
            "imageSummaryVisible" to imageAttachmentPreviewPanel.isVisible.toString(),
            "imagePreviewEmbeddedInComposer" to isImageAttachmentPreviewEmbeddedInComposerForTest().toString(),
            "sessionId" to currentSessionId.orEmpty(),
        )
    }

    internal fun isImageAttachmentPreviewEmbeddedInComposerForTest(): Boolean {
        return this::composerContainer.isInitialized &&
            imageAttachmentPreviewPanel.parent === composerContainer
    }

    internal fun statusSnapshotForTest(): Map<String, String> {
        return mapOf(
            "text" to statusLabel.text.orEmpty(),
            "tooltip" to statusLabel.toolTipText.orEmpty(),
            "visible" to statusLabel.isVisible.toString(),
        )
    }

    internal fun workflowErrorDialogSnapshotForTest(): Map<String, String> {
        val snapshot = lastWorkflowErrorDialogSnapshot
        return mapOf(
            "title" to snapshot?.title.orEmpty(),
            "message" to snapshot?.message.orEmpty(),
            "visible" to (snapshot != null).toString(),
        )
    }

    internal fun liveTaskExecutionSnapshotForTest(): Map<String, String> {
        val state = activeTaskExecutionPanel
        val labels = state?.panel
            ?.let(::collectComponentTextsForSnapshot)
            .orEmpty()
        return mapOf(
            "visible" to (state != null).toString(),
            "runId" to state?.runId.orEmpty(),
            "taskId" to state?.taskId.orEmpty(),
            "eventCount" to state?.seenEventKeys?.size?.toString().orEmpty(),
            "finished" to (state?.finished ?: false).toString(),
            "content" to state?.panel?.getContent().orEmpty(),
            "labels" to labels.joinToString(" | "),
        )
    }

    internal fun executionTracePanelsSnapshotForTest(): Map<String, String> {
        val tracePanels = messagesPanel.getAllMessages()
            .filter { panel -> panel.hasTraceForTest() }
        return mapOf(
            "count" to tracePanels.size.toString(),
            "unfinishedCount" to tracePanels.count { panel -> !panel.isFinishedForTest() }.toString(),
            "contents" to tracePanels.joinToString(" || ") { panel -> panel.getContent() },
        )
    }

    internal fun executionLaunchSnapshotForTest(): Map<String, String> {
        val panel = messagesPanel.getAllMessages()
            .filterIsInstance<WorkflowChatExecutionLaunchMessagePanel>()
            .lastOrNull()
        val labels = panel?.let(::collectComponentTextsForSnapshot).orEmpty()
        return panel?.snapshotForTest()
            ?.plus("labels" to labels.joinToString(" | "))
            ?: mapOf(
                "visible" to "false",
                "kind" to "",
                "workflowId" to "",
                "taskId" to "",
                "runId" to "",
                "taskTitle" to "",
                "focusedStage" to "",
                "trigger" to "",
                "sectionKinds" to "",
                "rawPromptDebugAvailable" to "false",
                "userNoteVisible" to "false",
                "systemContextExpanded" to "false",
                "debugEntryVisible" to "false",
                "rawPromptVisible" to "false",
                "rawPromptInspectInvocations" to "0",
                "labels" to "",
                "content" to "",
            )
    }

    internal fun syncTaskExecutionSessionMessagesForTest(
        sessionId: String,
        runId: String?,
        requestId: String?,
    ) {
        syncTaskExecutionSessionMessages(sessionId, runId, requestId)
    }

    internal fun messageCountForTest(): Int = messagesPanel.messageCount()

    internal fun addImageAttachmentsForTest(paths: List<String>) {
        imageAttachmentPreviewPanel.addImagePaths(paths)
    }

    internal fun setComposerInputForTest(text: String) {
        setComposerInput(text)
    }

    internal fun showWorkflowAttachmentBoundaryForTest() {
        showWorkflowAttachmentBoundaryIfNeeded()
    }

    internal fun clearTaskBindingForTest() {
        clearActiveTaskBinding()
    }

    internal fun clickTaskBindingChipForTest() {
        taskBindingChip.doClick()
    }

    internal fun clickExecuteBoundTaskForTest() {
        handleBoundTaskExecutionRequested(retry = false)
    }

    internal fun clickRetryBoundTaskForTest() {
        handleBoundTaskExecutionRequested(retry = true)
    }

    internal fun clickCompleteBoundTaskForTest() {
        handleBoundTaskCompletionRequested()
    }

    internal fun clickSendForTest() {
        handleSendOrStop()
    }

    internal fun applyTaskLiveProgressForTest(progress: TaskExecutionLiveProgress) {
        handleTaskExecutionLiveProgress(progress)
    }

    companion object {
        internal fun resolveInitialComposerDividerProportion(snapshot: WindowRuntimeState): Float {
            val restored = snapshot.chatComposerDividerProportion
            return restored.takeIf {
                it in CHAT_COMPOSER_RESTORE_MIN_PROPORTION..CHAT_COMPOSER_MAX_PROPORTION
            } ?: CHAT_COMPOSER_DEFAULT_DIVIDER_PROPORTION
        }

        internal fun shouldReplacePendingTaskExecutionLiveProgress(
            current: TaskExecutionLiveProgress?,
            incoming: TaskExecutionLiveProgress,
        ): Boolean {
            return current == null || incoming.lastUpdatedAt >= current.lastUpdatedAt
        }

        internal fun orderedTaskExecutionLiveProgressBatch(
            pending: Collection<TaskExecutionLiveProgress>,
        ): List<TaskExecutionLiveProgress> {
            return pending.sortedWith(
                compareBy<TaskExecutionLiveProgress> { it.lastUpdatedAt }.thenBy { it.runId },
            )
        }

        internal fun resolveFallbackTaskExecutionTraceStatus(
            progress: TaskExecutionLiveProgress,
        ): ChatTraceStatus {
            return when (progress.phase) {
                ExecutionLivePhase.QUEUED,
                ExecutionLivePhase.PREPARING_CONTEXT,
                ExecutionLivePhase.REQUEST_DISPATCHED,
                ExecutionLivePhase.STREAMING,
                ExecutionLivePhase.CANCELLING,
                -> ChatTraceStatus.RUNNING

                ExecutionLivePhase.WAITING_CONFIRMATION -> ChatTraceStatus.INFO
                ExecutionLivePhase.TERMINAL -> if (looksLikeFailedTaskExecutionDetail(progress.lastDetail)) {
                    ChatTraceStatus.ERROR
                } else {
                    ChatTraceStatus.DONE
                }
            }
        }

        private fun looksLikeFailedTaskExecutionDetail(detail: String?): Boolean {
            val normalized = detail?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (normalized.isBlank()) {
                return false
            }
            return FAILED_TASK_EXECUTION_DETAIL_KEYWORDS.any(normalized::contains) ||
                (normalized.contains("exit") && normalized.contains("code"))
        }

        private val CHAT_CONTEXT_CONFIG = ContextConfig(
            includeCurrentFile = false,
            includeSelectedCode = false,
            includeContainingScope = false,
            preferGraphRelatedContext = false,
        )
        private val MCP_EVENT_KEYWORDS = listOf(
            "mcp",
            "mcp__",
            "mcp-",
            "model context protocol",
            "modelcontextprotocol",
        )
        private val FAILED_TASK_EXECUTION_DETAIL_KEYWORDS = listOf(
            "failed",
            "failure",
            "error",
            "exception",
            "timed out",
            "timeout",
            "失败",
            "错误",
            "异常",
            "超时",
            "未成功",
        )
        private const val HISTORY_CONTENT_KEY = "SpecCoding.HistoryContent"
        private val HISTORY_CONTENT_DATA_KEY = Key.create<String>(HISTORY_CONTENT_KEY)
        private val CHAT_COMPACT_ICON = IconLoader.getIcon("/icons/chat-compact.svg", ImprovedChatPanel::class.java)
        private val CHAT_SEND_ICON = IconLoader.getIcon("/icons/chat-send.svg", ImprovedChatPanel::class.java)
        private val CHAT_STOP_ICON = IconLoader.getIcon("/icons/chat-stop.svg", ImprovedChatPanel::class.java)
        private val SPEC_SIDEBAR_TOGGLE_OPEN_ICON = IconLoader.getIcon("/icons/spec-sidebar-split-vertically.svg", ImprovedChatPanel::class.java)
        private val ACTION_ICON_BUTTON_SIZE = JBDimension(28, 24)
        private const val COMPOSER_CONTROLS_LEFT_INSET = 2
        private const val COMPACT_COMBO_HEIGHT_PX = 28
        private const val COMPACT_COMBO_SELECTED_LEFT_INSET = 4
        private const val COMPACT_COMBO_POPUP_HORIZONTAL_INSET = 6
        private const val COMPACT_COMBO_POPUP_VERTICAL_INSET = 4
        private const val INTERACTION_MODE_COMBO_MIN_WIDTH = 60
        private const val INTERACTION_MODE_COMBO_MAX_WIDTH = 132
        private const val PROVIDER_COMBO_MIN_WIDTH = 56
        private const val PROVIDER_COMBO_MAX_WIDTH = 132
        private const val MODEL_COMBO_MIN_WIDTH = 72
        private const val MODEL_COMBO_MAX_WIDTH = 240
        private const val SPEC_WORKFLOW_COMBO_MIN_WIDTH = 120
        private const val SPEC_WORKFLOW_COMBO_MAX_WIDTH = 180
        private const val SPEC_WORKFLOW_COMBO_TEXT_OVERHEAD_PX = 56
        private const val MAX_CONVERSATION_HISTORY = 240
        private const val MAX_RESTORED_MESSAGES = 240
        private const val SESSION_LOAD_FETCH_LIMIT = 5000
        private const val STREAM_BATCH_CHUNK_COUNT = 4
        private const val STREAM_BATCH_CHAR_COUNT = 240
        private const val STREAM_BATCH_INTERVAL_NANOS = 120_000_000L
        private const val TASK_EXECUTION_UI_BATCH_INTERVAL_MILLIS = 180
        private const val ACTION_PASTE_IMAGE_OR_TEXT = "specCoding.pasteImageOrText"
        private const val PASTE_DIAGNOSTICS_ENABLED = true
        private const val CLIPBOARD_IMAGE_TEMP_DIR_NAME = "Spec Code"
        private const val SPEC_CARD_PREVIEW_MAX_LINES = 18
        private const val SPEC_CARD_PREVIEW_MAX_CHARS = 1800
        private const val SPEC_SIDEBAR_DEFAULT_DIVIDER = 760
        private const val SPEC_SIDEBAR_MIN_WIDTH = 220
        private const val SPEC_SIDEBAR_MIN_DIVIDER = 320
        private const val SPEC_SIDEBAR_DIVIDER_SIZE = 8
        private const val SPEC_SIDEBAR_DIVIDER_PERSIST_DEBOUNCE_MILLIS = 140
        private const val CHAT_OUTPUT_MIN_HEIGHT = 180
        private const val CHAT_COMPOSER_INPUT_MIN_HEIGHT = 68
        private const val CHAT_COMPOSER_INPUT_PREFERRED_HEIGHT = 76
        private const val CHAT_COMPOSER_MIN_HEIGHT = 148
        private const val CHAT_COMPOSER_DEFAULT_DIVIDER_PROPORTION = 0.80f
        private const val CHAT_COMPOSER_RESTORE_MIN_PROPORTION = 0.60f
        private const val CHAT_COMPOSER_MIN_PROPORTION = 0.25f
        private const val CHAT_COMPOSER_MAX_PROPORTION = 0.85f
        private const val CHAT_COMPOSER_DIVIDER_SIZE = 6
        private const val CHAT_COMPOSER_DIVIDER_PERSIST_DEBOUNCE_MILLIS = 140
        private const val STATUS_SESSION_LOADED_AUTO_HIDE_MILLIS = 2200
        private const val STATUS_SHORT_HINT_AUTO_HIDE_MILLIS = 1800
        private const val COMPACT_REQUEST_TAIL_MAX_MESSAGES = 20
        private const val COMPACT_MODEL_SOURCE_MAX_MESSAGES = 36
        private const val COMPACT_MODEL_SOURCE_HEAD_MESSAGES = 6
        private const val COMPACT_MODEL_SOURCE_ENTRY_MAX_CHARS = 680
        private const val COMPACT_SUMMARY_MAX_CHARS = 2600
        private val COMPACT_MODEL_SYSTEM_PROMPT = """
            You are a context-compaction assistant for an IDE chat session.
            Produce a semantic summary that will be used as context for future turns.
            Preserve:
            - user goals and acceptance criteria
            - technical decisions and constraints
            - key files/paths/classes/functions
            - commands/actions already attempted and outcomes
            - unresolved questions, blockers, and next steps
            Remove redundant chatter and repeated details.
            Output plain text only (no markdown headings, no code fences).
            Keep the summary concise and factual.
        """.trimIndent()
        private const val CONTINUE_FOCUS_MAX_LENGTH = 84
        private val CONTINUE_STAGE_PREFIX_REGEX = Regex("""^\[[^\]]+]\s*""")
        private val CONTINUE_ORDERED_PREFIX_REGEX = Regex("""^\d+\.\s*""")
        private val CONTINUE_ORDERED_LINE_REGEX = Regex("""^\d+\.\s+.+""")
        private val COMPACT_CODE_BLOCK_REGEX = Regex("""```[\s\S]*?```""")
        private val COMPACT_WHITESPACE_REGEX = Regex("""\s+""")
        private val UI_CONTROL_CHAR_REGEX = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F]""")
        private val UI_BOX_DRAWING_REGEX = Regex("""[\u2500-\u259F]""")
        private val UI_CJK_REGEX = Regex("""\p{IsHan}""")
        private val UI_SUSPICIOUS_CHAR_REGEX = Regex("""[\u00C0-\u024F\u2500-\u259F]""")
        private const val UI_GARBLED_MIN_COUNT = 4
        private const val UI_GARBLED_MIN_RATIO = 0.15
        private const val UI_PLACEHOLDER_MAX_LENGTH = 4
        private val UI_PLACEHOLDER_LINE_REGEX = Regex("""^[\p{P}\p{S}]+$""")
        private val NON_INFORMATIVE_VERIFY_TRACE_DETAILS = setOf(
            "result: done",
            "result: complete",
            "result: completed",
            "done",
            "completed",
            "success",
        )
        private val NON_INFORMATIVE_TASK_TRACE_DETAILS = setOf(
            "done",
            "completed",
            "success",
            "result: done",
        )
        private const val CONTINUE_PROMPT_UNWRAP_MAX_DEPTH = 8
        private val CONTINUE_CODE_LIKE_FOCUS_REGEX = Regex("""^[a-z0-9_.:+\-/]{2,64}$""", RegexOption.IGNORE_CASE)
        private val CONTINUE_DYNAMIC_ZH_REGEX = Regex(
            """^请承接上一轮输出继续执行，?\s*重点[:：]\s*(.+?)(?:。?\s*避免重复已完成内容.*)?$"""
        )
        private val CONTINUE_DYNAMIC_EN_REGEX = Regex(
            """^continue from the previous result with focus[:：]\s*(.+?)(?:\.?\s*avoid repeating completed work.*)?$""",
            RegexOption.IGNORE_CASE,
        )
        private val PROMPT_REFERENCE_REGEX = Regex("""(?<!\S)#([\p{L}\p{N}_.-]+)""")
        private const val PROMPT_REFERENCE_DEFAULT_THEME =
            "继续当前最小闭环整改，并直接给出本轮实际改动与验证结果；如果主题仍不明确，先用一句话确认。"
        private const val PROMPT_REFERENCE_DEFAULT_EXTRA_CONSTRAINTS =
            "不要复述项目背景、历史问题池、报告摘要或源码清单。"
        private const val PROMPT_REFERENCE_DEFAULT_ACCEPTANCE =
            "最终正文只保留本轮实际修改、验证结果、剩余风险和下一步。"
        private const val PROMPT_REFERENCE_ONLY_USER_INPUT =
            "Follow the referenced prompt template and provide only the final answer. Do not repeat the template text."
        private val PROMPT_EXTRA_SPACES_REGEX = Regex("""\s{2,}""")
        private val PROMPT_ALIAS_SEPARATOR_REGEX = Regex("""[\s_-]+""")
        private val SUPPORTED_IMAGE_EXTENSIONS = setOf(
            "png",
            "jpg",
            "jpeg",
            "webp",
            "gif",
            "bmp",
        )
    }

    private fun collectComponentTextsForSnapshot(component: java.awt.Component): List<String> {
        val values = mutableListOf<String>()
        when (component) {
            is JLabel -> component.text?.trim()?.takeIf(String::isNotBlank)?.let(values::add)
            is JButton -> component.text?.trim()?.takeIf(String::isNotBlank)?.let(values::add)
        }
        val container = component as? java.awt.Container ?: return values
        container.components.forEach { child ->
            values += collectComponentTextsForSnapshot(child)
        }
        return values
    }

    private fun TaskExecutionSessionMetadataCodec.DecodedMetadata.isTaskExecutionMessage(): Boolean {
        return !runId.isNullOrBlank() || !requestId.isNullOrBlank()
    }

    private fun TaskExecutionSessionMetadataCodec.DecodedMetadata.matchesTaskExecutionMessage(
        expectedRunId: String?,
        expectedRequestId: String?,
    ): Boolean {
        if (!isTaskExecutionMessage()) {
            return false
        }
        return when {
            expectedRunId != null && runId == expectedRunId -> true
            expectedRequestId != null && requestId == expectedRequestId -> true
            else -> false
        }
    }
}
