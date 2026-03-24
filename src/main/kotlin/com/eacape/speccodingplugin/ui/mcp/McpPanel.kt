package com.eacape.speccodingplugin.ui.mcp

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.mcp.*
import com.eacape.speccodingplugin.ui.RefreshFeedback
import com.intellij.icons.AllIcons
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.util.Locale
import java.util.UUID
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingConstants

/**
 * MCP 主面板
 * Tool Window 中的 MCP 标签页，管理 MCP Server 的配置和状态
 */
class McpPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val logger = thisLogger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var _isDisposed = false

    private val mcpHub by lazy { McpHub.getInstance(project) }
    private val mcpConfigStore by lazy { McpConfigStore.getInstance(project) }
    private val projectService: SpecCodingProjectService = project.getService(SpecCodingProjectService::class.java)
    private val settingsState = SpecCodingSettingsState.getInstance()
    private val llmRouter by lazy { LlmRouter.getInstance() }
    private val modelRegistry by lazy { ModelRegistry.getInstance() }
    private val aiJson = Json { ignoreUnknownKeys = true }

    private val statusLabel = JBLabel(SpecCodingBundle.message("toolwindow.status.ready"))
    private val guideLabel = JBLabel(SpecCodingBundle.message("mcp.panel.guide"))
    private val aiSetupBtn = JButton()
    private val manualAddBtn = JButton()
    private val refreshBtn = JButton()
    private val statusChipPanel = JPanel(BorderLayout())

    private var selectedServerId: String? = null
    private var aiSetupInProgress: Boolean = false
    private var aiSetupStopRequested: Boolean = false
    private var aiSetupJob: Job? = null

    @Volatile
    private var activeAiRequest: ActiveAiRequest? = null

    private val serverListPanel = McpServerListPanel(
        onServerSelected = ::onServerSelected,
        onDeleteServer = ::onDeleteServer
    )

    private val serverDetailPanel = McpServerDetailPanel(
        onStartServer = ::onStartServer,
        onStopServer = ::onStopServer,
        onRestartServer = ::onRestartServer,
        onEditServer = ::onEditServer,
        onRefreshLogs = ::onRefreshServerLogs,
        onClearLogs = ::onClearServerLogs,
        onCopyLogs = ::onCopyServerLogs,
    )

    init {
        border = JBUI.Borders.empty(4)
        setupUI()
        subscribeToEvents()
        loadServers()
    }

    private fun setupUI() {
        refreshToolbarActionButtonPresentation()
        listOf(manualAddBtn, refreshBtn).forEach { styleActionButton(it, primary = false) }
        styleActionButton(aiSetupBtn, primary = true)

        // 顶部工具栏
        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            statusLabel.font = JBUI.Fonts.smallFont()
            statusLabel.foreground = STATUS_TEXT_FG
            statusLabel.horizontalAlignment = SwingConstants.LEFT
            statusChipPanel.isOpaque = true
            statusChipPanel.background = STATUS_CHIP_BG
            statusChipPanel.border = SpecUiStyle.roundedCardBorder(
                lineColor = STATUS_CHIP_BORDER,
                arc = JBUI.scale(12),
                top = 3,
                left = 8,
                bottom = 3,
                right = 8,
            )
            statusChipPanel.add(statusLabel, BorderLayout.CENTER)
            add(statusChipPanel)
        }

        val actionsRow = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(aiSetupBtn)
            add(manualAddBtn)
            add(refreshBtn)
        }

        val topRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(titleRow, BorderLayout.WEST)
            add(actionsRow, BorderLayout.EAST)
        }

        guideLabel.foreground = GUIDE_TEXT_FG
        guideLabel.font = JBUI.Fonts.smallFont()
        guideLabel.border = JBUI.Borders.empty(0, 2, 0, 2)

        val guideRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(5)
            add(guideLabel, BorderLayout.WEST)
        }

        val toolbar = JPanel(BorderLayout()).apply {
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
            add(topRow, BorderLayout.NORTH)
            add(guideRow, BorderLayout.SOUTH)
        }

        val north = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(8)
            add(toolbar, BorderLayout.CENTER)
        }
        add(north, BorderLayout.NORTH)

        // 主体: 左右分割
        val listSection = createSectionContainer(serverListPanel).apply {
            minimumSize = JBUI.size(JBUI.scale(192), 0)
        }
        val detailSection = createSectionContainer(serverDetailPanel).apply {
            minimumSize = JBUI.size(JBUI.scale(360), 0)
        }
        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            listSection,
            detailSection,
        )
        splitPane.dividerLocation = JBUI.scale(252)
        splitPane.dividerSize = JBUI.scale(8)
        splitPane.isContinuousLayout = true
        splitPane.border = JBUI.Borders.empty()
        splitPane.background = PANEL_SECTION_BG
        SpecUiStyle.applyChatLikeSpecDivider(
            splitPane = splitPane,
            dividerSize = JBUI.scale(8),
        )
        add(splitPane, BorderLayout.CENTER)

        // 刷新按钮
        refreshBtn.addActionListener { refreshServers(showRefreshFeedback = true) }
        aiSetupBtn.addActionListener { onAiQuickSetup() }
        manualAddBtn.addActionListener { onAddServer() }
        setAiSetupInProgress(false)
        updateServerCountStatus()
    }

    private fun subscribeToEvents() {
        project.messageBus.connect(this).subscribe(
            McpHubListener.TOPIC,
            object : McpHubListener {
                override fun onServerStatusChanged(serverId: String, status: ServerStatus) {
                    invokeLaterSafe { refreshServers() }
                }

                override fun onToolsDiscovered(serverId: String, tools: List<McpTool>) {
                    invokeLaterSafe { refreshServers() }
                }

                override fun onServerRuntimeLogsChanged(serverId: String) {
                    invokeLaterSafe {
                        if (selectedServerId == serverId) {
                            serverDetailPanel.updateRuntimeLogs(mcpHub.getServerRuntimeLogs(serverId))
                        }
                    }
                }
            }
        )

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    invokeLaterSafe {
                        refreshLocalizedTexts()
                    }
                }
            }
        )
    }

    private fun loadServers() {
        scope.launch(Dispatchers.IO) {
            mcpHub.loadPersistedConfigs()
            invokeLaterSafe { refreshServers() }
        }
    }

    fun refreshServers(showRefreshFeedback: Boolean = false) {
        val servers = mcpHub.getAllServers()
        val items = servers.map { server ->
            McpServerListPanel.ServerListItem(
                serverId = server.config.id,
                name = server.config.name,
                status = server.status,
                toolCount = mcpHub.getServerTools(server.config.id).size
            )
        }
        serverListPanel.updateServers(items)
        updateServerCountStatus()
        if (showRefreshFeedback) {
            val successText = SpecCodingBundle.message("common.refresh.success")
            RefreshFeedback.flashButtonSuccess(refreshBtn, successText)
            RefreshFeedback.flashLabelSuccess(statusLabel, successText, STATUS_SUCCESS_FG)
        }
    }

    private fun updateServerCountStatus() {
        val countText = SpecCodingBundle.message("mcp.server.count", mcpHub.getAllServers().size)
        setToolbarStatus(countText, allowEllipsis = false)
    }

    private fun setToolbarStatus(message: String, allowEllipsis: Boolean = true) {
        val normalized = message
            .trim()
            .replace('\n', ' ')
            .replace(Regex("\\s{2,}"), " ")
        if (normalized.isEmpty()) {
            statusLabel.text = ""
            statusLabel.toolTipText = null
            return
        }
        if (!allowEllipsis || normalized.length <= STATUS_CHIP_MAX_TEXT_LENGTH) {
            statusLabel.text = normalized
            statusLabel.toolTipText = null
            return
        }
        statusLabel.text = normalized.take(STATUS_CHIP_MAX_TEXT_LENGTH - 1) + "..."
        statusLabel.toolTipText = normalized
    }

    private fun refreshLocalizedTexts() {
        refreshToolbarActionButtonPresentation()
        styleActionButton(manualAddBtn, primary = false)
        styleActionButton(refreshBtn, primary = false)
        setAiSetupInProgress(aiSetupInProgress)
        guideLabel.text = SpecCodingBundle.message("mcp.panel.guide")
        serverListPanel.refreshLocalizedTexts()
        serverDetailPanel.refreshLocalizedTexts()
        updateServerCountStatus()
        selectedServerId?.let(::onServerSelected) ?: serverDetailPanel.showEmpty()
    }

    // --- 回调方法 ---

    private fun onServerSelected(serverId: String) {
        selectedServerId = serverId
        refreshServerDetail(serverId)
    }

    private fun refreshServerDetail(serverId: String) {
        val server = mcpHub.getServer(serverId) ?: return
        val tools = mcpHub.getServerTools(serverId)
        val logs = mcpHub.getServerRuntimeLogs(serverId)
        serverDetailPanel.updateServer(server, tools, logs)
    }

    private fun onAddServer() {
        val dialog = createServerEditorDialog()
        if (!dialog.showAndGet()) return
        val config = dialog.result ?: return
        scope.launch(Dispatchers.IO) {
            val result = registerNewServer(config)
            invokeLaterSafe {
                if (result.isFailure) {
                    setToolbarStatus(result.exceptionOrNull()?.message ?: SpecCodingBundle.message("common.unknown"))
                    return@invokeLaterSafe
                }
                refreshServers()
                serverListPanel.setSelectedServer(config.id)
                onServerSelected(config.id)
            }
        }
    }

    private fun onEditServer(serverId: String) {
        val existing = mcpConfigStore.getById(serverId) ?: return
        val dialog = createServerEditorDialog(existing = existing)
        if (!dialog.showAndGet()) return
        val config = dialog.result ?: return
        scope.launch(Dispatchers.IO) {
            val result = updateExistingServer(existing, config)
            invokeLaterSafe {
                if (result.isFailure) {
                    setToolbarStatus(result.exceptionOrNull()?.message ?: SpecCodingBundle.message("common.unknown"))
                    return@invokeLaterSafe
                }
                refreshServers()
                onServerSelected(serverId)
            }
        }
    }

    private fun onDeleteServer(serverId: String) {
        val clearDetail = selectedServerId == serverId
        if (clearDetail) {
            selectedServerId = null
            serverDetailPanel.showEmpty()
        }
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val persistedConfig = mcpConfigStore.getById(serverId) ?: mcpHub.getServer(serverId)?.config
                persistedConfig?.let {
                    mcpConfigStore.delete(serverId).getOrThrow()
                }
                try {
                    mcpHub.unregisterServer(serverId).getOrThrow()
                } catch (error: Exception) {
                    persistedConfig?.let { rollback ->
                        mcpConfigStore.save(rollback).getOrThrow()
                    }
                    throw error
                }
            }
            invokeLaterSafe {
                if (result.isFailure) {
                    setToolbarStatus(result.exceptionOrNull()?.message ?: SpecCodingBundle.message("common.unknown"))
                    if (clearDetail) {
                        selectedServerId = serverId
                        onServerSelected(serverId)
                    }
                } else if (clearDetail) {
                    serverDetailPanel.showEmpty()
                }
                refreshServers()
            }
        }
    }

    private fun createServerEditorDialog(
        existing: McpServerConfig? = null,
        draft: McpServerConfig? = null,
    ): McpServerEditorDialog {
        return McpServerEditorDialog(
            existing = existing,
            draft = draft,
            idExists = { candidateId -> isServerIdTaken(candidateId, existing?.id) },
        )
    }

    private fun isServerIdTaken(serverId: String, excludingServerId: String? = null): Boolean {
        val normalizedId = serverId.trim()
        if (normalizedId.isEmpty()) return false
        if (excludingServerId != null && normalizedId == excludingServerId) return false
        return mcpHub.getServer(normalizedId) != null || mcpConfigStore.getById(normalizedId) != null
    }

    private fun registerNewServer(config: McpServerConfig): Result<Unit> {
        return runCatching {
            if (isServerIdTaken(config.id)) {
                throw IllegalArgumentException(SpecCodingBundle.message("mcp.dialog.validation.idExists"))
            }
            mcpHub.registerServer(config).getOrThrow()
            try {
                mcpConfigStore.save(config).getOrThrow()
            } catch (error: Exception) {
                mcpHub.unregisterServer(config.id).getOrThrow()
                throw error
            }
        }
    }

    private suspend fun updateExistingServer(
        previousConfig: McpServerConfig,
        newConfig: McpServerConfig,
    ): Result<Unit> {
        return runCatching {
            if (previousConfig.id != newConfig.id) {
                throw IllegalArgumentException("Changing MCP server id is not supported")
            }
            mcpHub.updateServerConfig(newConfig).getOrThrow()
            try {
                mcpConfigStore.save(newConfig).getOrThrow()
            } catch (error: Exception) {
                mcpHub.updateServerConfig(previousConfig).getOrThrow()
                throw error
            }
        }
    }

    private fun onAiQuickSetup() {
        if (_isDisposed) return
        if (aiSetupInProgress) {
            onCancelAiQuickSetup()
            return
        }
        val providers = availableAiProviders()
        val preferredProvider = settingsState.defaultProvider.trim().ifBlank { null } ?: providers.firstOrNull()
        val preferredModel = settingsState.selectedCliModel.trim().ifBlank { null }
        val modelsByProvider = providers.associateWith { providerId ->
            val providerScopedPreferredModel = if (providerId == preferredProvider) preferredModel else null
            availableModelsForProvider(providerId, providerScopedPreferredModel)
        }
        val dialog = McpAiQuickSetupDialog(
            providers = providers,
            modelsByProvider = modelsByProvider,
            preferredProviderId = preferredProvider,
            preferredModelId = preferredModel,
        )
        if (!dialog.showAndGet()) {
            return
        }
        val prompt = dialog.promptText.trim()
        if (prompt.isBlank()) {
            return
        }
        val selectedProvider = dialog.selectedProviderId
        val selectedModel = dialog.selectedModelId

        setAiSetupInProgress(true)
        aiSetupStopRequested = false
        setToolbarStatus(SpecCodingBundle.message("mcp.server.aiSetup.generating"))

        aiSetupJob = scope.launch(Dispatchers.IO) {
            val result = runCatching {
                suggestMcpConfig(
                    userPrompt = prompt,
                    preferredProviderId = selectedProvider,
                    preferredModelId = selectedModel,
                )
            }
            invokeLaterSafe {
                setAiSetupInProgress(false)
                aiSetupJob = null
                val cancelled = aiSetupStopRequested || result.exceptionOrNull() is CancellationException
                if (cancelled) {
                    setToolbarStatus(SpecCodingBundle.message("mcp.server.aiSetup.cancelled"))
                    return@invokeLaterSafe
                }
                result.onSuccess { draft ->
                    val deduplicatedDraft = ensureUniqueId(draft)
                    val editor = createServerEditorDialog(draft = deduplicatedDraft)
                    if (!editor.showAndGet()) {
                        setToolbarStatus(SpecCodingBundle.message("mcp.server.aiSetup.cancelled"))
                        return@onSuccess
                    }
                    val config = editor.result ?: return@onSuccess
                    scope.launch(Dispatchers.IO) {
                        val saveResult = registerNewServer(config)
                        invokeLaterSafe {
                            saveResult.onSuccess {
                                refreshServers()
                                serverListPanel.setSelectedServer(config.id)
                                onServerSelected(config.id)
                                setToolbarStatus(SpecCodingBundle.message("mcp.server.aiSetup.saved", config.name))
                            }.onFailure { error ->
                                setToolbarStatus(
                                    SpecCodingBundle.message(
                                        "mcp.server.aiSetup.failed",
                                        error.message ?: SpecCodingBundle.message("common.unknown"),
                                    )
                                )
                            }
                        }
                    }
                }.onFailure { error ->
                    setToolbarStatus(SpecCodingBundle.message(
                        "mcp.server.aiSetup.failed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    ))
                }
            }
        }
    }

    private fun setAiSetupInProgress(inProgress: Boolean) {
        aiSetupInProgress = inProgress
        aiSetupBtn.isEnabled = true
        aiSetupBtn.icon = if (inProgress) MCP_TOOLBAR_AI_SETUP_STOP_ICON else MCP_TOOLBAR_AI_SETUP_ICON
        val tooltip = if (inProgress) {
            SpecCodingBundle.message("mcp.server.aiSetup.tooltip.running")
        } else {
            SpecCodingBundle.message("mcp.server.aiSetup.tooltip")
        }
        aiSetupBtn.toolTipText = tooltip
        aiSetupBtn.accessibleContext?.accessibleName = tooltip
        aiSetupBtn.accessibleContext?.accessibleDescription = tooltip
        styleActionButton(aiSetupBtn, primary = !inProgress)
    }

    private fun onCancelAiQuickSetup() {
        if (!aiSetupInProgress) return
        aiSetupStopRequested = true
        setToolbarStatus(SpecCodingBundle.message("mcp.server.aiSetup.stopping"))
        activeAiRequest?.let { request ->
            llmRouter.cancel(providerId = request.providerId, requestId = request.requestId)
        }
        aiSetupJob?.cancel(CancellationException("MCP AI setup cancelled by user"))
    }

    private suspend fun suggestMcpConfig(
        userPrompt: String,
        preferredProviderId: String?,
        preferredModelId: String?,
    ): McpServerConfig {
        val aiPrompt = buildAiSetupPrompt(userPrompt)
        val candidates = buildAiRequestCandidates(preferredProviderId, preferredModelId)
            .take(AI_MAX_CANDIDATE_ATTEMPTS)
        var lastError: Throwable? = null
        candidates.forEach { candidate ->
            try {
                val response = requestAiDraft(
                    prompt = aiPrompt,
                    providerId = candidate.providerId,
                    modelId = candidate.modelId,
                )
                return parseAiSuggestion(response, userPrompt)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
            }
        }
        throw IllegalStateException(
            SpecCodingBundle.message("mcp.server.aiSetup.failed", lastError?.message ?: SpecCodingBundle.message("common.unknown")),
            lastError,
        )
    }

    private suspend fun requestAiDraft(
        prompt: String,
        providerId: String?,
        modelId: String?,
    ): String {
        val response = StringBuilder()
        val requestId = UUID.randomUUID().toString()
        val normalizedProviderId = providerId?.trim()?.ifBlank { null }
        activeAiRequest = ActiveAiRequest(providerId = normalizedProviderId, requestId = requestId)
        return try {
            withTimeout(AI_REQUEST_TIMEOUT_MS) {
                projectService.chat(
                    providerId = normalizedProviderId,
                    userInput = prompt,
                    modelId = modelId,
                    operationMode = OperationMode.PLAN,
                    planExecuteVerifySections = false,
                    requestId = requestId,
                ) { chunk ->
                    if (chunk.delta.isNotEmpty()) {
                        response.append(chunk.delta)
                    }
                }
            }
            response.toString()
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException(
                SpecCodingBundle.message("mcp.server.aiSetup.timeout", AI_REQUEST_TIMEOUT_SECONDS),
                error,
            )
        } finally {
            if (activeAiRequest?.requestId == requestId) {
                activeAiRequest = null
            }
        }
    }

    private fun buildAiSetupPrompt(userPrompt: String): String {
        return """
            You are generating MCP server configuration draft for IntelliJ plugin UI.
            User requirement:
            $userPrompt

            Return JSON only, no markdown, no explanation.
            Required schema:
            {
              "id": "lowercase letters, numbers, hyphen/underscore only",
              "name": "display name",
              "command": "command binary",
              "args": ["arg1", "arg2"],
              "env": {"KEY": "VALUE"},
              "transport": "STDIO",
              "autoStart": false,
              "trusted": false
            }

            Rules:
            - command must not be empty.
            - args/env can be empty.
            - transport must stay STDIO because the built-in MCP manager does not support SSE yet.
            - keep trusted=false by default unless user explicitly requests trusted startup.
        """.trimIndent()
    }

    private fun parseAiSuggestion(raw: String, userPrompt: String): McpServerConfig {
        val payload = extractJsonPayload(raw)
        val root = try {
            aiJson.parseToJsonElement(payload).jsonObject
        } catch (error: Exception) {
            throw IllegalStateException(
                SpecCodingBundle.message("mcp.server.aiSetup.invalidJson", raw.take(220)),
                error,
            )
        }

        val command = root.string("command")
        if (command.isBlank()) {
            throw IllegalStateException(SpecCodingBundle.message("mcp.server.aiSetup.missingCommand"))
        }

        val idSeed = root.string("id")
            .ifBlank { root.string("name") }
            .ifBlank { userPrompt }
        val id = normalizeServerId(idSeed)
        val name = root.string("name")
            .ifBlank { formatDisplayNameFromId(id) }

        val args = root.arrayOfStrings("args")
        val env = root.mapOfStrings("env")
        val transport = parseTransport(root.string("transport"))
        val autoStart = root.bool("autoStart")
        val trusted = root.bool("trusted")

        return McpServerConfig(
            id = id,
            name = name,
            command = command.trim(),
            args = args,
            env = env,
            transport = transport,
            autoStart = autoStart,
            trusted = trusted,
        )
    }

    private fun extractJsonPayload(raw: String): String {
        val fenced = JSON_FENCE_REGEX.find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (!fenced.isNullOrBlank()) {
            return fenced
        }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1)
        }
        return raw.trim()
    }

    private fun ensureUniqueId(config: McpServerConfig): McpServerConfig {
        val baseId = normalizeServerId(config.id)
        if (mcpConfigStore.getById(baseId) == null) {
            return config.copy(id = baseId)
        }
        var suffix = 2
        var candidate = "$baseId-$suffix"
        while (mcpConfigStore.getById(candidate) != null) {
            suffix += 1
            candidate = "$baseId-$suffix"
        }
        return config.copy(id = candidate)
    }

    private fun parseTransport(raw: String): TransportType {
        val normalized = raw.trim().uppercase(Locale.ROOT)
        val parsed = runCatching {
            TransportType.valueOf(normalized)
        }.getOrDefault(TransportType.STDIO)
        return if (parsed.isBuiltInRuntimeSupported()) parsed else TransportType.STDIO
    }

    private fun availableAiProviders(): List<String> {
        val providers = llmRouter.availableUiProviders()
            .ifEmpty { llmRouter.availableProviders() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (providers.isNotEmpty()) return providers
        val configured = settingsState.defaultProvider.trim().ifBlank { null }
        if (!configured.isNullOrBlank()) return listOf(configured)
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
        val configuredProvider = settingsState.defaultProvider.trim().ifBlank { null }
        val configuredModel = settingsState.selectedCliModel.trim().ifBlank { null }
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

    private fun normalizeServerId(seed: String): String {
        val normalized = seed
            .trim()
            .lowercase(Locale.ROOT)
            .replace(ID_NORMALIZE_REGEX, "-")
            .trim('-', '_')
        return normalized.ifBlank { "mcp-server" }
    }

    private fun formatDisplayNameFromId(id: String): String {
        return id
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
                }
            }
            .ifBlank { id }
    }

    private fun JsonObject.string(key: String): String {
        val value = this[key] as? JsonPrimitive ?: return ""
        return value.contentOrNull?.trim().orEmpty()
    }

    private fun JsonObject.bool(key: String): Boolean {
        val value = this[key] as? JsonPrimitive ?: return false
        return value.booleanOrNull ?: false
    }

    private fun JsonObject.arrayOfStrings(key: String): List<String> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array
            .mapNotNull { element ->
                (element as? JsonPrimitive)?.contentOrNull?.trim()
            }
            .filter { it.isNotEmpty() }
    }

    private fun JsonObject.mapOfStrings(key: String): Map<String, String> {
        val obj = this[key] as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (rawKey, value) ->
            val normalizedKey = rawKey.trim()
            if (normalizedKey.isEmpty()) return@mapNotNull null
            normalizedKey to jsonElementToString(value)
        }.toMap()
    }

    private fun jsonElementToString(element: JsonElement): String {
        val primitive = element as? JsonPrimitive
        return primitive?.contentOrNull ?: element.toString()
    }

    private fun onStartServer(serverId: String) {
        scope.launch {
            val result = mcpHub.startServer(serverId)
            invokeLaterSafe {
                if (result.isFailure) {
                    setToolbarStatus(
                        result.exceptionOrNull()?.message
                            ?: SpecCodingBundle.message("mcp.server.startFailed")
                    )
                }
                refreshServers()
                onServerSelected(serverId)
            }
        }
    }

    private fun onStopServer(serverId: String) {
        scope.launch(Dispatchers.IO) {
            val result = mcpHub.stopServer(serverId)
            invokeLaterSafe {
                if (result.isFailure) {
                    setToolbarStatus(result.exceptionOrNull()?.message ?: SpecCodingBundle.message("common.unknown"))
                }
                refreshServers()
                onServerSelected(serverId)
            }
        }
    }

    private fun onRestartServer(serverId: String) {
        scope.launch {
            mcpHub.restartServer(serverId)
            invokeLaterSafe {
                refreshServers()
                onServerSelected(serverId)
            }
        }
    }

    private fun onRefreshServerLogs(serverId: String) {
        if (selectedServerId == serverId) {
            refreshServerDetail(serverId)
        }
        setToolbarStatus(SpecCodingBundle.message("mcp.server.logs.refreshed"))
    }

    private fun onClearServerLogs(serverId: String) {
        mcpHub.clearServerRuntimeLogs(serverId)
        if (selectedServerId == serverId) {
            refreshServerDetail(serverId)
        }
        setToolbarStatus(SpecCodingBundle.message("mcp.server.logs.cleared"))
    }

    private fun onCopyServerLogs(serverId: String) {
        if (selectedServerId == serverId) {
            setToolbarStatus(SpecCodingBundle.message("mcp.server.logs.copied"))
        }
    }

    private fun invokeLaterSafe(action: () -> Unit) {
        if (_isDisposed) return
        invokeLater {
            if (!_isDisposed) action()
        }
    }

    override fun dispose() {
        _isDisposed = true
        aiSetupJob?.cancel()
        scope.cancel()
        logger.info("McpPanel disposed")
    }

    private fun refreshToolbarActionButtonPresentation() {
        configureToolbarActionButton(
            button = aiSetupBtn,
            icon = MCP_TOOLBAR_AI_SETUP_ICON,
            tooltip = SpecCodingBundle.message("mcp.server.aiSetup.tooltip"),
        )
        configureToolbarActionButton(
            button = manualAddBtn,
            icon = MCP_TOOLBAR_ADD_ICON,
            tooltip = SpecCodingBundle.message("mcp.server.add"),
        )
        configureToolbarActionButton(
            button = refreshBtn,
            icon = MCP_TOOLBAR_REFRESH_ICON,
            tooltip = SpecCodingBundle.message("mcp.server.refresh"),
        )
    }

    private fun configureToolbarActionButton(button: JButton, icon: javax.swing.Icon, tooltip: String) {
        button.text = ""
        button.icon = icon
        button.disabledIcon = IconLoader.getDisabledIcon(icon)
        button.iconTextGap = 0
        button.toolTipText = tooltip
        button.accessibleContext?.accessibleName = tooltip
        button.accessibleContext?.accessibleDescription = tooltip
    }

    private fun styleActionButton(button: JButton, primary: Boolean) {
        val iconOnly = button.icon != null && button.text.isNullOrBlank()
        val bg = if (primary) BUTTON_PRIMARY_BG else BUTTON_BG
        val border = if (primary) BUTTON_PRIMARY_BORDER else BUTTON_BORDER
        val fg = if (primary) BUTTON_PRIMARY_FG else BUTTON_FG
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.isOpaque = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = if (iconOnly) JBUI.emptyInsets() else JBUI.insets(1, 4, 1, 4)
        button.foreground = fg
        SpecUiStyle.applyRoundRect(button, arc = 10)
        installToolbarButtonCursorTracking(button)
        if (iconOnly) {
            installToolbarIconButtonStateTracking(button)
            applyToolbarIconButtonVisualState(button)
        } else {
            button.background = bg
            button.border = javax.swing.BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(border, JBUI.scale(10)),
                JBUI.Borders.empty(1, 6, 1, 6),
            )
        }
        button.preferredSize = if (iconOnly) {
            JBUI.size(JBUI.scale(28), JBUI.scale(28))
        } else {
            val textWidth = button.getFontMetrics(button.font).stringWidth(button.text ?: "")
            val insets = button.insets
            val lafWidth = button.preferredSize?.width ?: 0
            val width = maxOf(
                lafWidth,
                textWidth + insets.left + insets.right + JBUI.scale(14),
                JBUI.scale(72),
            )
            JBUI.size(width, JBUI.scale(28))
        }
        button.minimumSize = button.preferredSize
    }

    private fun installToolbarIconButtonStateTracking(button: JButton) {
        if (button.getClientProperty("mcp.toolbar.iconStyleInstalled") == true) return
        button.putClientProperty("mcp.toolbar.iconStyleInstalled", true)
        button.isRolloverEnabled = true
        button.addChangeListener { applyToolbarIconButtonVisualState(button) }
        button.addPropertyChangeListener("enabled") { applyToolbarIconButtonVisualState(button) }
    }

    private fun installToolbarButtonCursorTracking(button: JButton) {
        if (button.getClientProperty("mcp.toolbar.cursorTrackingInstalled") == true) return
        button.putClientProperty("mcp.toolbar.cursorTrackingInstalled", true)
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

    private fun applyToolbarIconButtonVisualState(button: JButton) {
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

    private fun createSectionContainer(content: JComponent): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = PANEL_SECTION_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = PANEL_SECTION_BORDER,
                arc = JBUI.scale(12),
                top = 4,
                left = 4,
                bottom = 4,
                right = 4,
            )
            add(content, BorderLayout.CENTER)
        }
    }

    companion object {
        private val JSON_FENCE_REGEX = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        private val ID_NORMALIZE_REGEX = Regex("[^a-z0-9_-]+")
        private const val AI_REQUEST_TIMEOUT_MS = 30_000L
        private const val AI_REQUEST_TIMEOUT_SECONDS = AI_REQUEST_TIMEOUT_MS / 1_000
        private const val AI_MAX_CANDIDATE_ATTEMPTS = 3
        private const val STATUS_CHIP_MAX_TEXT_LENGTH = 26
        private val MCP_TOOLBAR_AI_SETUP_ICON = IconLoader.getIcon("/icons/mcp-ai-generated.svg", McpPanel::class.java)
        private val MCP_TOOLBAR_AI_SETUP_STOP_ICON = AllIcons.Actions.Close
        private val MCP_TOOLBAR_ADD_ICON = AllIcons.General.Add
        private val MCP_TOOLBAR_REFRESH_ICON = AllIcons.Actions.Refresh

        private val TOOLBAR_BG = JBColor(Color(246, 249, 255), Color(57, 62, 70))
        private val TOOLBAR_BORDER = JBColor(Color(204, 216, 236), Color(87, 98, 114))
        private val ICON_BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val ICON_BUTTON_BG_HOVER = JBColor(Color(235, 246, 255), Color(77, 89, 106))
        private val ICON_BUTTON_BG_ACTIVE = JBColor(Color(227, 240, 255), Color(85, 99, 118))
        private val ICON_BUTTON_BG_DISABLED = JBColor(Color(247, 250, 254), Color(66, 72, 83))
        private val ICON_BUTTON_BORDER = JBColor(Color(178, 198, 226), Color(104, 116, 134))
        private val ICON_BUTTON_BORDER_HOVER = JBColor(Color(124, 167, 229), Color(124, 158, 205))
        private val ICON_BUTTON_BORDER_ACTIVE = JBColor(Color(89, 136, 208), Color(143, 182, 232))
        private val ICON_BUTTON_BORDER_DISABLED = JBColor(Color(198, 205, 216), Color(96, 106, 121))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val BUTTON_PRIMARY_BG = JBColor(Color(213, 228, 250), Color(77, 98, 128))
        private val BUTTON_PRIMARY_BORDER = JBColor(Color(154, 180, 219), Color(116, 137, 169))
        private val BUTTON_PRIMARY_FG = JBColor(Color(37, 57, 89), Color(223, 232, 246))
        private val STATUS_CHIP_BG = JBColor(Color(236, 244, 255), Color(66, 76, 91))
        private val STATUS_CHIP_BORDER = JBColor(Color(178, 198, 226), Color(99, 116, 140))
        private val STATUS_TEXT_FG = JBColor(Color(52, 72, 106), Color(201, 213, 232))
        private val STATUS_SUCCESS_FG = JBColor(Color(42, 128, 74), Color(131, 208, 157))
        private val GUIDE_TEXT_FG = JBColor(Color(86, 100, 122), Color(173, 186, 206))
        private val PANEL_SECTION_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val PANEL_SECTION_BORDER = JBColor(Color(204, 215, 233), Color(84, 92, 105))
    }

    private data class AiRequestCandidate(
        val providerId: String?,
        val modelId: String?,
    )

    private data class ActiveAiRequest(
        val providerId: String?,
        val requestId: String,
    )
}
