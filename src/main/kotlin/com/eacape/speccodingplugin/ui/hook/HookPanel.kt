package com.eacape.speccodingplugin.ui.hook

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.hook.HookDefinition
import com.eacape.speccodingplugin.hook.HookEvent
import com.eacape.speccodingplugin.hook.HookExecutionLog
import com.eacape.speccodingplugin.hook.HookManager
import com.eacape.speccodingplugin.hook.HookYamlCodec
import com.intellij.icons.AllIcons
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.llm.LlmMessage
import com.eacape.speccodingplugin.llm.LlmRequest
import com.eacape.speccodingplugin.llm.LlmRole
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.ui.RefreshFeedback
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

class HookPanel(
    private val project: Project,
    private val listHooksAction: () -> List<HookDefinition> = { HookManager.getInstance(project).listHooks() },
    private val setHookEnabledAction: (hookId: String, enabled: Boolean) -> Boolean = { hookId, enabled ->
        HookManager.getInstance(project).setHookEnabled(hookId, enabled)
    },
    private val deleteHookAction: (hookId: String) -> Boolean = { hookId ->
        HookManager.getInstance(project).deleteHook(hookId)
    },
    private val listLogsAction: (limit: Int) -> List<HookExecutionLog> = { limit ->
        HookManager.getInstance(project).getExecutionLogs(limit)
    },
    private val clearLogsAction: () -> Unit = { HookManager.getInstance(project).clearExecutionLogs() },
    private val runSynchronously: Boolean = false,
) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val llmRouter by lazy(LazyThreadSafetyMode.NONE) { LlmRouter.getInstance() }
    private val modelRegistry by lazy(LazyThreadSafetyMode.NONE) { ModelRegistry.getInstance() }
    private val settings by lazy(LazyThreadSafetyMode.NONE) { SpecCodingSettingsState.getInstance() }

    @Volatile
    private var isDisposed = false

    private val hooksModel = DefaultListModel<HookDefinition>()
    private val hooksList = JBList(hooksModel)
    private val logArea = JBTextArea()

    private val statusLabel = JBLabel("")
    private val guideLabel = JBLabel(SpecCodingBundle.message("hook.guide.quickStart"))
    private val openConfigButton = JButton()
    private val aiQuickConfigButton = JButton()
    private val refreshHooksButton = JButton()
    private val enableButton = JButton()
    private val disableButton = JButton()
    private val refreshLogButton = JButton()
    private val clearLogButton = JButton()
    private val statusChipPanel = JPanel(BorderLayout())

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    init {
        border = JBUI.Borders.empty(4)
        setupUi()
        subscribeToEvents()
        refreshData()
    }

    private fun setupUi() {
        applyActionButtonPresentation()
        listOf(
            openConfigButton,
            aiQuickConfigButton,
            refreshHooksButton,
            enableButton,
            disableButton,
            refreshLogButton,
            clearLogButton,
        )
            .forEach(::styleActionButton)

        val hookActionRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(openConfigButton)
            add(aiQuickConfigButton)
            add(refreshHooksButton)
            add(enableButton)
            add(disableButton)
        }
        hookActionRow.border = JBUI.Borders.emptyBottom(2)
        guideLabel.font = JBUI.Fonts.smallFont()
        guideLabel.foreground = STATUS_TEXT_FG

        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = STATUS_TEXT_FG

        statusChipPanel.isOpaque = true
        statusChipPanel.background = STATUS_CHIP_BG
        statusChipPanel.border = SpecUiStyle.roundedCardBorder(
            lineColor = STATUS_CHIP_BORDER,
            arc = JBUI.scale(12),
            top = 4,
            left = 10,
            bottom = 4,
            right = 10,
        )
        statusChipPanel.add(statusLabel, BorderLayout.CENTER)

        val metaRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(guideLabel, BorderLayout.CENTER)
            add(statusChipPanel, BorderLayout.EAST)
        }

        val toolbarCard = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = true
            background = TOOLBAR_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = TOOLBAR_BORDER,
                arc = JBUI.scale(14),
                top = 8,
                left = 10,
                bottom = 8,
                right = 10,
            )
            add(hookActionRow, BorderLayout.NORTH)
            add(metaRow, BorderLayout.SOUTH)
        }
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(8)
                add(toolbarCard, BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )

        hooksList.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        hooksList.cellRenderer = HookListRenderer()
        hooksList.addListSelectionListener { updateButtonState() }
        installHookListMouseInteractions()

        hooksList.background = PANEL_SECTION_BG
        hooksList.fixedCellHeight = JBUI.scale(34)

        logArea.isEditable = false
        logArea.lineWrap = true
        logArea.wrapStyleWord = true
        logArea.background = PANEL_SECTION_BG
        logArea.text = SpecCodingBundle.message("hook.log.empty")

        val hooksScroll = JBScrollPane(hooksList).apply {
            border = JBUI.Borders.empty()
        }
        val logsScroll = JBScrollPane(logArea).apply {
            border = JBUI.Borders.empty()
        }

        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            createSectionContainer(hooksScroll),
            createLogsSection(logsScroll),
        ).apply {
            dividerLocation = 320
            dividerSize = JBUI.scale(4)
            border = JBUI.Borders.empty()
            isContinuousLayout = true
            background = PANEL_SECTION_BG
            SpecUiStyle.applyChatLikeSpecDivider(
                splitPane = this,
                dividerSize = JBUI.scale(4),
            )
        }
        add(splitPane, BorderLayout.CENTER)

        openConfigButton.addActionListener { openHookConfig() }
        aiQuickConfigButton.addActionListener { quickSetupWithAi() }
        refreshHooksButton.addActionListener { refreshHooks(showRefreshFeedback = true) }
        refreshLogButton.addActionListener { refreshLogs(showRefreshFeedback = true) }
        enableButton.addActionListener { updateSelectedHookEnabled(true) }
        disableButton.addActionListener { updateSelectedHookEnabled(false) }
        clearLogButton.addActionListener { clearLogs() }

        updateButtonState()
    }

    private fun styleActionButton(button: JButton) {
        val iconOnly = button.icon != null && button.text.isNullOrBlank()
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = if (iconOnly) JBUI.emptyInsets() else JBUI.insets(1, 4, 1, 4)
        button.isOpaque = true
        button.foreground = BUTTON_FG
        SpecUiStyle.applyRoundRect(button, arc = 10)
        installHookButtonCursorTracking(button)
        if (iconOnly) {
            installHookIconButtonStateTracking(button)
            applyHookIconButtonVisualState(button)
        } else {
            button.background = BUTTON_BG
            button.border = javax.swing.BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
                JBUI.Borders.empty(1, 5, 1, 5),
            )
        }
        button.horizontalAlignment = SwingConstants.CENTER
        button.verticalAlignment = SwingConstants.CENTER
        button.horizontalTextPosition = SwingConstants.CENTER
        button.verticalTextPosition = SwingConstants.CENTER
        button.iconTextGap = 0
        val width = JBUI.scale(28)
        button.preferredSize = JBUI.size(width, JBUI.scale(28))
        button.minimumSize = button.preferredSize
    }

    private fun installHookIconButtonStateTracking(button: JButton) {
        if (button.getClientProperty("hook.toolbar.iconStyleInstalled") == true) return
        button.putClientProperty("hook.toolbar.iconStyleInstalled", true)
        button.isRolloverEnabled = true
        button.addChangeListener { applyHookIconButtonVisualState(button) }
        button.addPropertyChangeListener("enabled") { applyHookIconButtonVisualState(button) }
    }

    private fun installHookButtonCursorTracking(button: JButton) {
        if (button.getClientProperty("hook.toolbar.cursorTrackingInstalled") == true) return
        button.putClientProperty("hook.toolbar.cursorTrackingInstalled", true)
        updateHookButtonCursor(button)
        button.addPropertyChangeListener("enabled") { updateHookButtonCursor(button) }
    }

    private fun updateHookButtonCursor(button: JButton) {
        button.cursor = if (button.isEnabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private fun applyHookIconButtonVisualState(button: JButton) {
        val model = button.model
        val background = when {
            !button.isEnabled -> ICON_BUTTON_BG_DISABLED
            model.isPressed || model.isSelected -> ICON_BUTTON_BG_ACTIVE
            model.isRollover -> ICON_BUTTON_BG_HOVER
            else -> ICON_BUTTON_BG
        }
        val borderColor = when {
            !button.isEnabled -> ICON_BUTTON_BORDER_DISABLED
            model.isPressed || model.isSelected -> ICON_BUTTON_BORDER_ACTIVE
            model.isRollover -> ICON_BUTTON_BORDER_HOVER
            else -> ICON_BUTTON_BORDER
        }
        val borderThickness = if (model.isPressed || model.isSelected) JBUI.scale(2) else 1
        button.background = background
        button.border = javax.swing.BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(borderColor, JBUI.scale(10), thickness = borderThickness),
            JBUI.Borders.empty(4),
        )
    }

    private fun applyActionButtonPresentation() {
        applyButtonIcon(
            button = openConfigButton,
            icon = OPEN_CONFIG_ICON,
            tooltip = SpecCodingBundle.message("hook.action.openConfig"),
        )
        applyButtonIcon(
            button = aiQuickConfigButton,
            icon = AI_QUICK_CONFIG_ICON,
            tooltip = SpecCodingBundle.message("hook.action.aiQuickConfig"),
        )
        applyButtonIcon(
            button = refreshHooksButton,
            icon = REFRESH_HOOKS_ICON,
            tooltip = SpecCodingBundle.message("hook.action.refresh"),
        )
        applyButtonIcon(
            button = enableButton,
            icon = ENABLE_HOOK_ICON,
            tooltip = SpecCodingBundle.message("hook.action.enable"),
        )
        applyButtonIcon(
            button = disableButton,
            icon = DISABLE_HOOK_ICON,
            tooltip = SpecCodingBundle.message("hook.action.disable"),
        )
        applyButtonIcon(
            button = refreshLogButton,
            icon = REFRESH_LOG_ICON,
            tooltip = SpecCodingBundle.message("hook.log.refresh"),
        )
        applyButtonIcon(
            button = clearLogButton,
            icon = CLEAR_LOG_ICON,
            tooltip = SpecCodingBundle.message("hook.log.clear"),
        )
    }

    private fun applyButtonIcon(button: JButton, icon: Icon, tooltip: String) {
        button.text = ""
        button.icon = icon
        button.disabledIcon = IconLoader.getDisabledIcon(icon)
        button.toolTipText = tooltip
        button.accessibleContext.accessibleName = tooltip
        button.accessibleContext.accessibleDescription = tooltip
    }

    private fun createSectionContainer(content: JComponent): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = PANEL_SECTION_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = PANEL_SECTION_BORDER,
                arc = JBUI.scale(12),
                top = 6,
                left = 6,
                bottom = 6,
                right = 6,
            )
            add(content, BorderLayout.CENTER)
        }
    }

    private fun createLogsSection(logsScroll: JComponent): JPanel {
        val logsToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(refreshLogButton)
            add(clearLogButton)
        }
        val logsContent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(logsToolbar, BorderLayout.NORTH)
            add(logsScroll, BorderLayout.CENTER)
        }
        return createSectionContainer(logsContent)
    }

    private fun subscribeToEvents() {
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

    private fun refreshData() {
        refreshHooks()
        refreshLogs()
    }

    private fun refreshHooks(showRefreshFeedback: Boolean = false) {
        val selectedId = hooksList.selectedValue?.id
        runBackground {
            val hooks = listHooksAction().sortedBy { it.name }
            invokeLaterSafe {
                hooksModel.clear()
                hooks.forEach(hooksModel::addElement)

                if (selectedId != null) {
                    setSelectedHookById(selectedId)
                }
                if (hooksList.selectedIndex < 0 && hooksModel.size() > 0) {
                    hooksList.selectedIndex = 0
                }

                statusLabel.text = SpecCodingBundle.message("hook.status.count", hooks.size)
                hooksList.repaint()
                updateButtonState()
                if (showRefreshFeedback) {
                    val successText = SpecCodingBundle.message("common.refresh.success")
                    RefreshFeedback.flashButtonSuccess(refreshHooksButton, successText)
                    RefreshFeedback.flashLabelSuccess(statusLabel, successText, STATUS_SUCCESS_FG)
                }
            }
        }
    }

    private fun refreshLogs(showRefreshFeedback: Boolean = false) {
        runBackground {
            val logs = listLogsAction(200)
            invokeLaterSafe {
                logArea.text = if (logs.isEmpty()) {
                    SpecCodingBundle.message("hook.log.empty")
                } else {
                    logs
                        .sortedBy { it.timestamp }
                        .joinToString(separator = "\n") { log ->
                            SpecCodingBundle.message(
                                "hook.log.entry",
                                dateFormatter.format(Instant.ofEpochMilli(log.timestamp)),
                                log.hookName,
                                eventDisplayName(log.event),
                                if (log.success) {
                                    SpecCodingBundle.message("hook.log.result.success")
                                } else {
                                    SpecCodingBundle.message("hook.log.result.failed")
                                },
                                log.message,
                            )
                        }
                }
                logArea.caretPosition = logArea.document.length
                if (showRefreshFeedback) {
                    val successText = SpecCodingBundle.message("common.refresh.success")
                    RefreshFeedback.flashButtonSuccess(refreshLogButton, successText)
                    RefreshFeedback.flashLabelSuccess(statusLabel, successText, STATUS_SUCCESS_FG)
                }
            }
        }
    }

    private fun clearLogs() {
        runBackground {
            clearLogsAction()
            invokeLaterSafe {
                statusLabel.text = SpecCodingBundle.message("hook.status.logs.cleared")
                refreshLogs()
            }
        }
    }

    private fun openHookConfig() {
        runBackground {
            val result = runCatching {
                ensureHookConfigFile()
            }
            invokeLaterSafe {
                result
                    .onSuccess { target ->
                        val opened = openHookConfigInEditor(target.path)
                        if (opened) {
                            val feedback = if (target.created) {
                                SpecCodingBundle.message("hook.status.config.created", target.path.fileName.toString())
                            } else {
                                SpecCodingBundle.message("hook.status.config.opened", target.path.fileName.toString())
                            }
                            statusLabel.text = feedback
                            showHookFeedback(feedback, NotificationType.INFORMATION)
                            refreshHooks()
                        } else {
                            val feedback = SpecCodingBundle.message(
                                "hook.status.config.openFailed",
                                SpecCodingBundle.message("common.unknown"),
                            )
                            statusLabel.text = feedback
                            showHookFeedback(feedback, NotificationType.ERROR)
                        }
                    }
                    .onFailure { error ->
                        val feedback = SpecCodingBundle.message(
                            "hook.status.config.openFailed",
                            error.message ?: SpecCodingBundle.message("common.unknown"),
                        )
                        statusLabel.text = feedback
                        showHookFeedback(feedback, NotificationType.ERROR)
                    }
            }
        }
    }

    private fun quickSetupWithAi() {
        val aiInput = collectAiQuickSetupInput() ?: return
        if (aiInput.userIntent.isBlank()) {
            statusLabel.text = SpecCodingBundle.message("hook.status.ai.inputRequired")
            return
        }

        statusLabel.text = SpecCodingBundle.message("hook.status.ai.generating")
        val task: suspend () -> Unit = {
            val result = runCatching {
                val target = ensureHookConfigFile()
                val draft = buildHookConfigDraft(
                    userIntent = aiInput.userIntent,
                    preferredProviderId = aiInput.providerId,
                    preferredModelId = aiInput.modelId,
                )
                writeHookConfig(target.path, draft.yaml)
                HookConfigApplyResult(target = target, draft = draft)
            }
            invokeLaterSafe {
                result
                    .onSuccess { applied ->
                        val opened = openHookConfigInEditor(applied.target.path)
                        refreshData()
                        statusLabel.text = when {
                            !opened -> SpecCodingBundle.message(
                                "hook.status.config.openFailed",
                                SpecCodingBundle.message("common.unknown"),
                            )

                            applied.draft.source == HookConfigDraftSource.AI -> SpecCodingBundle.message(
                                "hook.status.ai.applied",
                                applied.target.path.fileName.toString(),
                            )

                            else -> SpecCodingBundle.message(
                                "hook.status.ai.applied.fallback",
                                applied.target.path.fileName.toString(),
                            )
                        }
                    }
                    .onFailure { error ->
                        statusLabel.text = SpecCodingBundle.message(
                            "hook.status.ai.failed",
                            error.message ?: SpecCodingBundle.message("common.unknown"),
                        )
                    }
            }
        }

        if (runSynchronously) {
            runBlocking { task() }
        } else {
            scope.launch(Dispatchers.IO) { task() }
        }
    }

    private fun collectAiQuickSetupInput(): HookAiQuickSetupInput? {
        val providers = availableAiProviders()
        val preferredProvider = settings.defaultProvider.trim().ifBlank { null } ?: providers.firstOrNull()
        val preferredModel = settings.selectedCliModel.trim().ifBlank { null }
        val modelsByProvider = providers.associateWith { providerId ->
            val providerScopedPreferredModel = if (providerId == preferredProvider) preferredModel else null
            availableModelsForProvider(providerId, providerScopedPreferredModel)
        }
        val dialog = HookAiQuickSetupDialog(
            providers = providers,
            modelsByProvider = modelsByProvider,
            preferredProviderId = preferredProvider,
            preferredModelId = preferredModel,
        )
        if (!dialog.showAndGet()) {
            return null
        }
        return HookAiQuickSetupInput(
            userIntent = dialog.promptText.trim(),
            providerId = dialog.selectedProviderId,
            modelId = dialog.selectedModelId,
        )
    }

    private suspend fun buildHookConfigDraft(
        userIntent: String,
        preferredProviderId: String?,
        preferredModelId: String?,
    ): HookConfigDraft {
        val aiDraft = generateHookConfigByAi(
            userIntent = userIntent,
            preferredProviderId = preferredProviderId,
            preferredModelId = preferredModelId,
        )
        if (!aiDraft.isNullOrBlank()) {
            return HookConfigDraft(
                yaml = aiDraft,
                source = HookConfigDraftSource.AI,
            )
        }

        return HookConfigDraft(
            yaml = createFallbackHookConfig(userIntent),
            source = HookConfigDraftSource.TEMPLATE,
        )
    }

    private suspend fun generateHookConfigByAi(
        userIntent: String,
        preferredProviderId: String?,
        preferredModelId: String?,
    ): String? {
        llmRouter.refreshProviders()
        modelRegistry.refreshFromDiscovery()

        val prompt = buildHookAiPrompt(userIntent)
        val candidates = buildAiRequestCandidates(
            preferredProviderId = preferredProviderId,
            preferredModelId = preferredModelId,
        )

        for (candidate in candidates) {
            val response = runCatching {
                llmRouter.generate(
                    providerId = candidate.providerId,
                    request = LlmRequest(
                        messages = listOf(
                            LlmMessage(role = LlmRole.SYSTEM, content = HOOK_AI_SYSTEM_PROMPT),
                            LlmMessage(role = LlmRole.USER, content = prompt),
                        ),
                        model = candidate.modelId,
                        temperature = 0.2,
                        maxTokens = 1_200,
                        metadata = mapOf("hookQuickSetup" to "true"),
                        workingDirectory = project.basePath,
                    ),
                )
            }.getOrNull() ?: continue

            val normalizedYaml = normalizeHookYaml(response.content)
            if (normalizedYaml.isBlank()) {
                continue
            }
            val parsedHooks = runCatching { HookYamlCodec.deserialize(normalizedYaml) }
                .getOrDefault(emptyList())
            if (parsedHooks.isNotEmpty()) {
                return normalizedYaml
            }
        }
        return null
    }

    private fun availableAiProviders(): List<String> {
        val providers = llmRouter.availableUiProviders()
            .ifEmpty { llmRouter.availableProviders() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (providers.isNotEmpty()) {
            return providers
        }
        val configured = settings.defaultProvider.trim().ifBlank { null }
        if (!configured.isNullOrBlank()) {
            return listOf(configured)
        }
        return listOf(llmRouter.defaultProviderId())
    }

    private fun availableModelsForProvider(providerId: String, preferredModelId: String?): List<ModelInfo> {
        val models = modelRegistry.getModelsForProvider(providerId).toMutableList()
        if (models.isEmpty() && !preferredModelId.isNullOrBlank()) {
            models += ModelInfo(
                id = preferredModelId,
                name = preferredModelId,
                provider = providerId,
                contextWindow = 0,
                capabilities = emptySet(),
            )
        }
        return models
    }

    private fun buildAiRequestCandidates(
        preferredProviderId: String?,
        preferredModelId: String?,
    ): List<AiRequestCandidate> {
        val configuredProvider = settings.defaultProvider.trim().ifBlank { null }
        val configuredModel = settings.selectedCliModel.trim().ifBlank { null }
        val providers = availableAiProviders()
        val primaryProvider = preferredProviderId?.trim()?.ifBlank { null }
            ?: configuredProvider
            ?: providers.firstOrNull()

        val candidates = mutableListOf<AiRequestCandidate>()
        fun add(providerId: String?, modelId: String?) {
            val normalizedProvider = providerId?.trim()?.ifBlank { null }
            val normalizedModel = modelId?.trim()?.ifBlank { null }
            val candidate = AiRequestCandidate(normalizedProvider, normalizedModel)
            if (!candidates.contains(candidate)) {
                candidates += candidate
            }
        }

        add(primaryProvider, preferredModelId)
        if (!preferredModelId.isNullOrBlank()) {
            add(primaryProvider, null)
        }
        add(configuredProvider, configuredModel)
        if (!configuredModel.isNullOrBlank()) {
            add(configuredProvider, null)
        }
        providers.firstOrNull()?.let { provider ->
            add(provider, null)
        }
        add(null, null)
        return candidates
    }

    private fun buildHookAiPrompt(userIntent: String): String {
        val normalizedIntent = userIntent
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        return buildString {
            appendLine("请根据下面的场景生成 hooks.yaml：")
            appendLine()
            appendLine("场景描述：")
            appendLine(normalizedIntent)
            appendLine()
            appendLine("要求：")
            appendLine("1. 只输出 YAML，不要 Markdown 代码块，不要解释。")
            appendLine("2. 必须包含根节点 version: 1 和 hooks。")
            appendLine("3. 每个 hook 必须包含 id/name/event/enabled/actions。")
            appendLine("4. event 只能是 FILE_SAVED、GIT_COMMIT、SPEC_STAGE_CHANGED、CHAT_MODE_CHANGED。")
            appendLine("5. action.type 只能是 RUN_COMMAND 或 SHOW_NOTIFICATION。")
            appendLine("6. RUN_COMMAND 必须包含 command；SHOW_NOTIFICATION 必须包含 message。")
            appendLine("7. 可选 conditions.filePattern 或 conditions.specStage。")
            appendLine("8. 输出 1-3 个最有价值的 hook。")
        }
    }

    private fun normalizeHookYaml(raw: String): String {
        var normalized = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

        CODE_FENCE_PATTERN.find(normalized)?.groupValues?.getOrNull(1)?.trim()?.let { fenced ->
            if (fenced.isNotBlank()) {
                normalized = fenced
            }
        }

        val lines = normalized.lines()
        val startIndex = lines.indexOfFirst { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("version:") || trimmed.startsWith("hooks:")
        }
        if (startIndex > 0) {
            normalized = lines.drop(startIndex).joinToString("\n").trim()
        }

        if (normalized.isBlank()) {
            return ""
        }
        return normalized.trimEnd() + "\n"
    }

    private fun createFallbackHookConfig(userIntent: String): String {
        val event = inferEventFromIntent(userIntent)
        val idSuffix = when (event) {
            HookEvent.FILE_SAVED -> "file-saved"
            HookEvent.GIT_COMMIT -> "git-commit"
            HookEvent.SPEC_STAGE_CHANGED -> "spec-stage"
            HookEvent.CHAT_MODE_CHANGED -> "chat-mode"
        }
        val displayName = when (event) {
            HookEvent.FILE_SAVED -> "Quick File Saved Hook"
            HookEvent.GIT_COMMIT -> "Quick Git Commit Hook"
            HookEvent.SPEC_STAGE_CHANGED -> "Quick Spec Stage Hook"
            HookEvent.CHAT_MODE_CHANGED -> "Quick Chat Mode Hook"
        }
        val baseMessage = when (event) {
            HookEvent.FILE_SAVED -> "Hook triggered on file save"
            HookEvent.GIT_COMMIT -> "Hook triggered on git commit"
            HookEvent.SPEC_STAGE_CHANGED -> "Hook triggered on spec stage change"
            HookEvent.CHAT_MODE_CHANGED -> "Hook triggered on chat mode change"
        }
        val intentSnippet = userIntent
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .take(FALLBACK_MESSAGE_MAX_LENGTH)
        val notificationMessage = if (intentSnippet.isBlank()) {
            baseMessage
        } else {
            "$baseMessage: $intentSnippet"
        }

        return buildString {
            appendLine("version: 1")
            appendLine("hooks:")
            appendLine("  - id: quick-$idSuffix")
            appendLine("    name: \"$displayName\"")
            appendLine("    event: ${event.name}")
            appendLine("    enabled: true")
            if (event == HookEvent.FILE_SAVED) {
                appendLine("    conditions:")
                appendLine("      filePattern: \"**/*\"")
            }
            appendLine("    actions:")
            appendLine("      - type: SHOW_NOTIFICATION")
            appendLine("        message: \"${escapeForYamlDoubleQuote(notificationMessage)}\"")
            appendLine("        level: INFO")
        }
    }

    private fun inferEventFromIntent(userIntent: String): HookEvent {
        val normalized = userIntent.trim().lowercase(Locale.ROOT)
        return when {
            normalized.contains("vibe") || normalized.contains("模式") || normalized.contains("mode") ->
                HookEvent.CHAT_MODE_CHANGED
            normalized.contains("commit") || normalized.contains("提交") -> HookEvent.GIT_COMMIT
            normalized.contains("spec") || normalized.contains("规格") || normalized.contains("阶段") || normalized.contains("stage") ->
                HookEvent.SPEC_STAGE_CHANGED

            else -> HookEvent.FILE_SAVED
        }
    }

    private fun escapeForYamlDoubleQuote(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    private fun writeHookConfig(path: Path, content: String) {
        Files.writeString(
            path,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun ensureHookConfigFile(): HookConfigTarget {
        val path = hookConfigPath()
        if (Files.exists(path)) {
            return HookConfigTarget(path = path, created = false)
        }

        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            path,
            DEFAULT_HOOK_CONFIG_TEMPLATE,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
        )
        return HookConfigTarget(path = path, created = true)
    }

    private fun hookConfigPath(): Path {
        val basePath = project.basePath
            ?: throw IllegalStateException(SpecCodingBundle.message("hook.error.projectBasePathUnavailable"))
        return Paths.get(basePath).resolve(".spec-coding").resolve("hooks.yaml")
    }

    private fun openHookConfigInEditor(path: Path): Boolean {
        val normalizedPath = path.toAbsolutePath().normalize()
        val normalizedFile = normalizedPath.toFile()
        val normalizedPathString = normalizedPath.toString().replace('\\', '/')
        val localFileSystem = LocalFileSystem.getInstance()
        val virtualFile = localFileSystem.refreshAndFindFileByIoFile(normalizedFile)
            ?: localFileSystem.findFileByIoFile(normalizedFile)
            ?: localFileSystem.refreshAndFindFileByPath(normalizedPathString)
            ?: localFileSystem.findFileByPath(normalizedPathString)
            ?: return false
        OpenFileDescriptor(project, virtualFile, 0, 0).navigate(true)
        return true
    }

    private fun installHookListMouseInteractions() {
        hooksList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1 || e.clickCount != 1) {
                        return
                    }
                    val index = hooksList.locationToIndex(e.point)
                    if (index < 0) {
                        return
                    }
                    val cellBounds = hooksList.getCellBounds(index, index) ?: return
                    if (!cellBounds.contains(e.point)) {
                        return
                    }
                    if (!isDeleteIconHit(index, e.point, cellBounds)) {
                        return
                    }
                    hooksList.selectedIndex = index
                    deleteSelectedHook(confirmRequired = true)
                    e.consume()
                }

                override fun mouseExited(e: MouseEvent) {
                    hooksList.cursor = Cursor.getDefaultCursor()
                }
            },
        )
        hooksList.addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val index = hooksList.locationToIndex(e.point)
                    if (index < 0) {
                        hooksList.cursor = Cursor.getDefaultCursor()
                        return
                    }
                    val cellBounds = hooksList.getCellBounds(index, index)
                    val hoverDeleteIcon = if (cellBounds == null || !cellBounds.contains(e.point)) {
                        false
                    } else {
                        isDeleteIconHit(index, e.point, cellBounds)
                    }
                    hooksList.cursor = if (hoverDeleteIcon) {
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    } else {
                        Cursor.getDefaultCursor()
                    }
                }
            },
        )
    }

    private fun isDeleteIconHit(index: Int, clickPoint: Point, cellBounds: Rectangle): Boolean {
        val hook = hooksModel.get(index)
        val rendererComponent = hooksList.cellRenderer.getListCellRendererComponent(
            hooksList,
            hook,
            index,
            index == hooksList.selectedIndex,
            false,
        )
        val renderer = rendererComponent as? HookListRenderer ?: return false
        renderer.setBounds(0, 0, cellBounds.width, cellBounds.height)
        renderer.doLayout()
        val iconBoundsInRenderer = renderer.deleteIconBounds()
        val iconBoundsInList = Rectangle(
            cellBounds.x + iconBoundsInRenderer.x,
            cellBounds.y + iconBoundsInRenderer.y,
            iconBoundsInRenderer.width,
            iconBoundsInRenderer.height,
        )
        return iconBoundsInList.contains(clickPoint)
    }

    private fun deleteSelectedHook(confirmRequired: Boolean) {
        val selected = hooksList.selectedValue ?: return
        if (confirmRequired) {
            val result = Messages.showYesNoDialog(
                project,
                SpecCodingBundle.message("hook.delete.confirm.message", selected.name),
                SpecCodingBundle.message("hook.delete.confirm.title"),
                Messages.getWarningIcon(),
            )
            if (result != Messages.YES) {
                return
            }
        }

        runBackground {
            val deleted = deleteHookAction(selected.id)
            invokeLaterSafe {
                if (deleted) {
                    val feedback = SpecCodingBundle.message("hook.status.deleted", selected.name)
                    statusLabel.text = feedback
                    showHookFeedback(feedback, NotificationType.INFORMATION)
                    refreshData()
                } else {
                    val feedback = SpecCodingBundle.message("hook.status.deleteFailed", selected.name)
                    statusLabel.text = feedback
                    showHookFeedback(feedback, NotificationType.ERROR)
                }
            }
        }
    }

    private fun updateSelectedHookEnabled(enabled: Boolean) {
        val selected = hooksList.selectedValue ?: return

        runBackground {
            val updated = setHookEnabledAction(selected.id, enabled)
            invokeLaterSafe {
                if (updated) {
                    val status = if (enabled) {
                        SpecCodingBundle.message("hook.enabled")
                    } else {
                        SpecCodingBundle.message("hook.disabled")
                    }
                    statusLabel.text = SpecCodingBundle.message("hook.status.updated", selected.name, status)
                    refreshHooks()
                } else {
                    statusLabel.text = SpecCodingBundle.message("hook.status.updateFailed", selected.name)
                }
            }
        }
    }

    private fun refreshLocalizedTexts() {
        guideLabel.text = SpecCodingBundle.message("hook.guide.quickStart")
        applyActionButtonPresentation()
        styleActionButton(openConfigButton)
        styleActionButton(aiQuickConfigButton)
        styleActionButton(refreshHooksButton)
        styleActionButton(enableButton)
        styleActionButton(disableButton)
        styleActionButton(refreshLogButton)
        styleActionButton(clearLogButton)
        hooksList.repaint()
        if (logArea.text == SpecCodingBundle.message("hook.log.empty")) {
            logArea.text = SpecCodingBundle.message("hook.log.empty")
        }
    }

    private fun updateButtonState() {
        val selected = hooksList.selectedValue
        val hasSelection = selected != null
        enableButton.isEnabled = hasSelection && selected?.enabled == false
        disableButton.isEnabled = hasSelection && selected?.enabled == true
    }

    private fun setSelectedHookById(hookId: String) {
        val index = (0 until hooksModel.size()).firstOrNull { hooksModel.get(it).id == hookId } ?: return
        hooksList.selectedIndex = index
    }

    private fun eventDisplayName(event: HookEvent): String {
        return when (event) {
            HookEvent.FILE_SAVED -> SpecCodingBundle.message("hook.event.fileSaved")
            HookEvent.GIT_COMMIT -> SpecCodingBundle.message("hook.event.gitCommit")
            HookEvent.SPEC_STAGE_CHANGED -> SpecCodingBundle.message("hook.event.specStageChanged")
            HookEvent.CHAT_MODE_CHANGED -> SpecCodingBundle.message("hook.event.chatModeChanged")
        }
    }

    private fun invokeLaterSafe(action: () -> Unit) {
        if (runSynchronously) {
            if (!isDisposed && !project.isDisposed) {
                action()
            }
            return
        }

        if (isDisposed) return
        invokeLater {
            if (!isDisposed && !project.isDisposed) {
                action()
            }
        }
    }

    private fun showHookFeedback(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SpecCoding.Notifications")
            .createNotification(content, type)
            .notify(project)
    }

    private fun runBackground(task: () -> Unit) {
        if (runSynchronously) {
            task()
            return
        }
        scope.launch(Dispatchers.IO) { task() }
    }

    internal fun hooksForTest(): List<HookDefinition> {
        return (0 until hooksModel.size()).map { hooksModel.get(it) }
    }

    internal fun logTextForTest(): String = logArea.text

    internal fun statusTextForTest(): String = statusLabel.text

    internal fun selectHookForTest(hookId: String?) {
        if (hookId == null) {
            hooksList.clearSelection()
            return
        }
        setSelectedHookById(hookId)
    }

    internal fun clickEnableForTest() {
        enableButton.doClick()
    }

    internal fun clickDisableForTest() {
        disableButton.doClick()
    }

    internal fun clickRefreshForTest() {
        refreshData()
    }

    internal fun clickOpenConfigForTest() {
        openConfigButton.doClick()
    }

    internal fun clickClearLogsForTest() {
        clearLogButton.doClick()
    }

    internal fun clickDeleteSelectedForTest() {
        deleteSelectedHook(confirmRequired = false)
    }

    override fun dispose() {
        isDisposed = true
        scope.cancel()
    }

    private inner class HookListRenderer : JPanel(BorderLayout(JBUI.scale(6), 0)), ListCellRenderer<HookDefinition> {
        private val nameLabel = JBLabel()
        private val eventChip = JBLabel()
        private val enabledChip = JBLabel()
        private val deleteIconLabel = JBLabel(AllIcons.Actions.GC)
        private val chipPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))

        init {
            isOpaque = true
            border = JBUI.Borders.empty(4, 8)
            nameLabel.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            deleteIconLabel.preferredSize = JBUI.size(16, 16)
            deleteIconLabel.minimumSize = deleteIconLabel.preferredSize
            chipPanel.isOpaque = false
            chipPanel.add(eventChip)
            chipPanel.add(enabledChip)
            chipPanel.add(deleteIconLabel)
            add(nameLabel, BorderLayout.CENTER)
            add(chipPanel, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out HookDefinition>?,
            value: HookDefinition?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val hook = value ?: return this
            val enabledText = if (hook.enabled) {
                SpecCodingBundle.message("hook.enabled")
            } else {
                SpecCodingBundle.message("hook.disabled")
            }
            val eventText = eventDisplayName(hook.event)
            nameLabel.text = hook.name
            toolTipText = SpecCodingBundle.message(
                "hook.list.item",
                hook.name,
                eventText,
                enabledText,
            )
            nameLabel.foreground = if (isSelected) ITEM_TEXT_SELECTED_FG else ITEM_TEXT_FG
            styleChip(
                label = eventChip,
                text = eventText,
                foreground = EVENT_CHIP_FG,
                background = EVENT_CHIP_BG,
                borderColor = EVENT_CHIP_BORDER,
            )
            if (hook.enabled) {
                styleChip(
                    label = enabledChip,
                    text = enabledText,
                    foreground = ENABLED_CHIP_FG,
                    background = ENABLED_CHIP_BG,
                    borderColor = ENABLED_CHIP_BORDER,
                )
            } else {
                styleChip(
                    label = enabledChip,
                    text = enabledText,
                    foreground = DISABLED_CHIP_FG,
                    background = DISABLED_CHIP_BG,
                    borderColor = DISABLED_CHIP_BORDER,
                )
            }
            styleDeleteIcon()
            background = if (isSelected) ITEM_SELECTED_BG else ITEM_BG
            border = javax.swing.BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(
                    if (isSelected) ITEM_SELECTED_BORDER else ITEM_BORDER,
                    JBUI.scale(10),
                ),
                JBUI.Borders.empty(4, 8, 4, 8),
            )
            return this
        }

        fun deleteIconBounds(): Rectangle {
            return SwingUtilities.convertRectangle(deleteIconLabel.parent, deleteIconLabel.bounds, this)
        }

        private fun styleChip(
            label: JBLabel,
            text: String,
            foreground: Color,
            background: Color,
            borderColor: Color,
        ) {
            label.text = text
            label.font = JBUI.Fonts.smallFont()
            label.foreground = foreground
            label.background = background
            label.isOpaque = true
            label.border = javax.swing.BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(borderColor, JBUI.scale(8)),
                JBUI.Borders.empty(1, 6, 1, 6),
            )
        }

        private fun styleDeleteIcon() {
            deleteIconLabel.toolTipText = SpecCodingBundle.message("hook.action.delete")
            deleteIconLabel.icon = AllIcons.Actions.GC
            deleteIconLabel.isOpaque = false
            deleteIconLabel.border = JBUI.Borders.empty(0, 1)
        }
    }

    private data class HookConfigTarget(
        val path: Path,
        val created: Boolean,
    )

    private data class HookConfigApplyResult(
        val target: HookConfigTarget,
        val draft: HookConfigDraft,
    )

    private data class HookConfigDraft(
        val yaml: String,
        val source: HookConfigDraftSource,
    )

    private data class HookAiQuickSetupInput(
        val userIntent: String,
        val providerId: String?,
        val modelId: String?,
    )

    private data class AiRequestCandidate(
        val providerId: String?,
        val modelId: String?,
    )

    private enum class HookConfigDraftSource {
        AI,
        TEMPLATE,
    }

    companion object {
        private val OPEN_CONFIG_ICON = IconLoader.getIcon("/icons/hook-config.svg", HookPanel::class.java)
        private val AI_QUICK_CONFIG_ICON = IconLoader.getIcon("/icons/spec-ai-change.svg", HookPanel::class.java)
        private val REFRESH_HOOKS_ICON = AllIcons.Actions.Refresh
        private val ENABLE_HOOK_ICON = IconLoader.getIcon("/icons/mcp-thread-running.svg", HookPanel::class.java)
        private val DISABLE_HOOK_ICON = IconLoader.getIcon("/icons/hook-status-disabled.svg", HookPanel::class.java)
        private val REFRESH_LOG_ICON = AllIcons.Actions.Refresh
        private val CLEAR_LOG_ICON = AllIcons.Actions.GC
        private val ITEM_BG = JBColor(Color(245, 249, 255), Color(58, 64, 74))
        private val ITEM_BORDER = JBColor(Color(202, 214, 234), Color(91, 101, 117))
        private val ITEM_SELECTED_BG = JBColor(Color(224, 237, 255), Color(74, 86, 103))
        private val ITEM_SELECTED_BORDER = JBColor(Color(145, 175, 219), Color(120, 140, 168))
        private val ITEM_TEXT_FG = JBColor(Color(47, 66, 100), Color(218, 227, 238))
        private val ITEM_TEXT_SELECTED_FG = JBColor(Color(33, 56, 92), Color(233, 239, 248))
        private val EVENT_CHIP_BG = JBColor(Color(234, 242, 255), Color(73, 83, 97))
        private val EVENT_CHIP_BORDER = JBColor(Color(178, 199, 227), Color(110, 124, 145))
        private val EVENT_CHIP_FG = JBColor(Color(58, 80, 117), Color(207, 218, 233))
        private val ENABLED_CHIP_BG = JBColor(Color(224, 245, 233), Color(67, 92, 77))
        private val ENABLED_CHIP_BORDER = JBColor(Color(150, 203, 174), Color(97, 130, 109))
        private val ENABLED_CHIP_FG = JBColor(Color(36, 107, 67), Color(188, 226, 204))
        private val DISABLED_CHIP_BG = JBColor(Color(246, 236, 236), Color(95, 78, 78))
        private val DISABLED_CHIP_BORDER = JBColor(Color(220, 182, 182), Color(130, 106, 106))
        private val DISABLED_CHIP_FG = JBColor(Color(126, 63, 63), Color(228, 201, 201))
        private val TOOLBAR_BG = JBColor(Color(246, 249, 255), Color(57, 62, 70))
        private val TOOLBAR_BORDER = JBColor(Color(204, 216, 236), Color(87, 98, 114))
        private val ICON_BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val ICON_BUTTON_BG_HOVER = JBColor(Color(235, 246, 255), Color(76, 88, 103))
        private val ICON_BUTTON_BG_ACTIVE = JBColor(Color(227, 240, 255), Color(85, 99, 118))
        private val ICON_BUTTON_BG_DISABLED = JBColor(Color(247, 250, 254), Color(66, 72, 83))
        private val ICON_BUTTON_BORDER = JBColor(Color(178, 198, 226), Color(104, 116, 134))
        private val ICON_BUTTON_BORDER_HOVER = JBColor(Color(124, 167, 229), Color(124, 158, 205))
        private val ICON_BUTTON_BORDER_ACTIVE = JBColor(Color(89, 136, 208), Color(143, 182, 232))
        private val ICON_BUTTON_BORDER_DISABLED = JBColor(Color(198, 205, 216), Color(96, 106, 121))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val STATUS_CHIP_BG = JBColor(Color(236, 244, 255), Color(66, 76, 91))
        private val STATUS_CHIP_BORDER = JBColor(Color(178, 198, 226), Color(99, 116, 140))
        private val STATUS_TEXT_FG = JBColor(Color(52, 72, 106), Color(201, 213, 232))
        private val STATUS_SUCCESS_FG = JBColor(Color(42, 128, 74), Color(131, 208, 157))
        private val PANEL_SECTION_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val PANEL_SECTION_BORDER = JBColor(Color(204, 215, 233), Color(84, 92, 105))
        private val CODE_FENCE_PATTERN = Regex("```(?:yaml|yml)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        private const val FALLBACK_MESSAGE_MAX_LENGTH = 80
        private const val HOOK_AI_SYSTEM_PROMPT = """
            你是 IntelliJ 插件 hooks.yaml 生成助手。
            你必须严格输出可解析的 YAML，且只输出 YAML。
            不要输出解释、注释、Markdown 代码块。
        """

        private val DEFAULT_HOOK_CONFIG_TEMPLATE = """
            # Spec Code Hook configuration tutorial
            # version is currently fixed to 1
            version: 1

            # hooks is a list of automation rules
            hooks:
              # id must be unique in this file
              - id: sample-notify
                # display name shown in UI
                name: Sample Notify
                # event enum:
                # FILE_SAVED | GIT_COMMIT | SPEC_STAGE_CHANGED | CHAT_MODE_CHANGED
                event: FILE_SAVED
                enabled: true

                # optional conditions:
                # filePattern supports glob patterns, e.g. "**/*.kt", "**/*.md"
                # specStage is used with SPEC_STAGE_CHANGED, e.g. "requirements"
                conditions:
                  filePattern: "**/*.md"

                actions:
                  # action.type enum: RUN_COMMAND | SHOW_NOTIFICATION
                  - type: SHOW_NOTIFICATION
                    # template variables: {{file.path}} {{spec.stage}} {{meta.xxx}}
                    message: "Hook triggered: {{file.path}}"
                    # level enum for SHOW_NOTIFICATION: INFO | WARNING | ERROR
                    level: INFO
                  # - type: RUN_COMMAND
                  #   command: "echo changed {{file.path}}"
                  #   timeoutMillis: 120000
        """.trimIndent() + "\n"
    }
}
