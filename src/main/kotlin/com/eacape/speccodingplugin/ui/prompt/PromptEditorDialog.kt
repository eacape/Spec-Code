package com.eacape.speccodingplugin.ui.prompt

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.llm.MockLlmProvider
import com.eacape.speccodingplugin.prompt.PromptScope
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.util.Locale
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.border.AbstractBorder

/**
 * Prompt 编辑器对话框
 * 新建场景内置 AI 生成草稿能力。
 */
class PromptEditorDialog(
    private val project: Project,
    private val existing: PromptTemplate? = null,
    private val existingPromptIds: Set<String> = emptySet(),
) : DialogWrapper(project, true) {

    private val projectService: SpecCodingProjectService = project.getService(SpecCodingProjectService::class.java)
    private val modelRegistry = ModelRegistry.getInstance()
    private val promptJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val nameField = JBTextField()
    private val contentPane = JTextPane()
    private val variablesLabel = JBLabel("")
    private val previewLabel = JBLabel("")

    private val aiRequirementArea = JBTextArea()
    private val aiGenerateButton = JButton()
    private val aiProviderCombo = ComboBox<String>()
    private val aiModelCombo = ComboBox<ModelInfo>()
    private val aiProgressBar = JProgressBar()
    private val aiStepLabels = listOf(JBLabel(), JBLabel(), JBLabel(), JBLabel())
    private val aiAnimationTimer = Timer(AI_ANIMATION_INTERVAL_MILLIS) {
        updateAiAnimationFrame()
    }

    private var aiAnimationTick = 0
    private var aiGenerating = false
    private var syncingAiSelectors = false

    var result: PromptTemplate? = null
        private set

    private val isCreateMode = existing == null

    init {
        title = if (isCreateMode) {
            SpecCodingBundle.message("prompt.editor.title.new")
        } else {
            SpecCodingBundle.message("prompt.editor.title.edit")
        }
        init()
        loadExisting()
        refreshLocalizedTexts()
    }

    private fun loadExisting() {
        if (existing != null) {
            nameField.text = existing.name
            contentPane.text = existing.content
        }
        updateHighlightAndPreview()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(10)
            preferredSize = if (isCreateMode) {
                JBUI.size(760, 620)
            } else {
                JBUI.size(720, 540)
            }
            isOpaque = false
        }

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        if (!isCreateMode) {
            stack.add(createEditHeaderCard())
            stack.add(Box.createVerticalStrut(JBUI.scale(2)))
        }
        if (isCreateMode) {
            stack.add(createAiDraftCard())
            stack.add(Box.createVerticalStrut(JBUI.scale(10)))
        }
        stack.add(createEditorCard())

        root.add(stack, BorderLayout.CENTER)
        root.add(createInfoPanel(), BorderLayout.SOUTH)
        return root
    }

    private fun createEditHeaderCard(): JPanel {
        val title = JBLabel(SpecCodingBundle.message("prompt.editor.title.edit")).apply {
            font = font.deriveFont(Font.BOLD, 14f)
            foreground = TITLE_FG
        }
        val subtitle = JBLabel(SpecCodingBundle.message("prompt.editor.edit.subtitle")).apply {
            font = JBUI.Fonts.smallFont()
            foreground = SUBTITLE_FG
            border = JBUI.Borders.emptyTop(0)
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(title)
            add(subtitle)
        }

        val card = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = HEADER_BG
            border = JBUI.Borders.compound(
                RoundedLineBorder(HEADER_BORDER, JBUI.scale(12)),
                JBUI.Borders.empty(2, 8),
            )
            add(content, BorderLayout.CENTER)
        }
        return lockPreferredHeight(card)
    }

    private fun createAiDraftCard(): JPanel {
        aiRequirementArea.lineWrap = true
        aiRequirementArea.wrapStyleWord = true
        aiRequirementArea.rows = 4
        aiRequirementArea.font = JBUI.Fonts.label().deriveFont(12f)
        aiRequirementArea.margin = JBUI.insets(6)
        aiRequirementArea.emptyText.text = SpecCodingBundle.message("prompt.editor.ai.subtitle")

        aiGenerateButton.isFocusable = false
        aiGenerateButton.addActionListener { requestAiDraft() }
        stylePrimaryButton(aiGenerateButton)

        setupAiGenerationSelectors()

        aiProgressBar.isIndeterminate = true
        aiProgressBar.isVisible = false
        aiProgressBar.preferredSize = JBUI.size(110, 6)

        val requirementScroll = JBScrollPane(aiRequirementArea).apply {
            preferredSize = JBUI.size(0, JBUI.scale(92))
            border = JBUI.Borders.customLine(BORDER_COLOR, 1)
        }

        val header = JBLabel(SpecCodingBundle.message("prompt.editor.ai.section")).apply {
            font = font.deriveFont(Font.BOLD, 13f)
            foreground = TITLE_FG
        }
        val stepsRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            aiStepLabels.forEachIndexed { index, label ->
                label.font = JBUI.Fonts.smallFont()
                label.border = JBUI.Borders.empty(2, 6)
                add(label)
                if (index < aiStepLabels.lastIndex) {
                    add(Box.createHorizontalStrut(JBUI.scale(6)))
                }
            }
        }

        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(header)
            add(stepsRow)
        }

        val titleColumn = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(titleRow)
        }

        val actionRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(titleColumn, BorderLayout.WEST)
            add(aiGenerateButton, BorderLayout.EAST)
        }

        val selectorRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
            add(
                JBLabel(SpecCodingBundle.message("prompt.editor.ai.provider")).apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = SUBTITLE_FG
                },
            )
            add(aiProviderCombo)
            add(
                JBLabel(SpecCodingBundle.message("prompt.editor.ai.model")).apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = SUBTITLE_FG
                },
            )
            add(aiModelCombo)
        }

        val progressRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
            add(aiProgressBar)
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(actionRow)
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(requirementScroll)
            add(selectorRow)
            add(progressRow)
        }

        return if (isCreateMode) {
            createCard(content)
        } else {
            createCard(content, top = 8, left = 10, bottom = 8, right = 10)
        }
    }

    private fun createEditorCard(): JPanel {
        val nameLabelText = SpecCodingBundle.message("prompt.editor.field.name")
        val nameLabel = JBLabel(nameLabelText).apply {
            val labelWidth = maxOf(
                JBUI.scale(34),
                getFontMetrics(font).stringWidth(nameLabelText) + JBUI.scale(6),
            )
            preferredSize = if (isCreateMode) {
                JBUI.size(labelWidth, 24)
            } else {
                JBUI.size(labelWidth, 20)
            }
            foreground = TITLE_FG
        }
        nameField.emptyText.text = SpecCodingBundle.message("prompt.editor.placeholder.name")
        nameField.font = JBUI.Fonts.label().deriveFont(13f)
        nameField.background = INPUT_BG
        nameField.border = JBUI.Borders.compound(
            RoundedLineBorder(BORDER_COLOR, JBUI.scale(10)),
            if (isCreateMode) JBUI.Borders.empty(4, 8) else JBUI.Borders.empty(2, 8),
        )
        nameField.putClientProperty("JComponent.roundRectArc", JBUI.scale(10))

        val nameRow = lockPreferredHeight(
            JPanel(BorderLayout(JBUI.scale(2), 0)).apply {
                isOpaque = false
                add(nameLabel, BorderLayout.WEST)
                add(nameField, BorderLayout.CENTER)
            },
        )

        contentPane.font = JBUI.Fonts.label().deriveFont(13f)
        contentPane.background = INPUT_BG
        contentPane.border = JBUI.Borders.empty(8)
        contentPane.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = updateHighlightAndPreview()
                override fun removeUpdate(e: DocumentEvent) = updateHighlightAndPreview()
                override fun changedUpdate(e: DocumentEvent) = Unit
            },
        )

        val editorScrollPane = JBScrollPane(contentPane).apply {
            preferredSize = JBUI.size(0, JBUI.scale(290))
            viewport.background = INPUT_BG
            border = JBUI.Borders.compound(
                RoundedLineBorder(BORDER_COLOR, JBUI.scale(12)),
                JBUI.Borders.empty(1),
            )
        }

        val hintLabel = JBLabel(SpecCodingBundle.message("prompt.editor.hint")).apply {
            foreground = SUBTITLE_FG
            font = JBUI.Fonts.smallFont()
            border = JBUI.Borders.emptyTop(5)
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(nameRow)
            add(Box.createVerticalStrut(if (isCreateMode) JBUI.scale(6) else JBUI.scale(2)))
            add(editorScrollPane)
            if (isCreateMode) {
                add(hintLabel)
            }
        }

        return if (isCreateMode) {
            createCard(content)
        } else {
            createCard(content, top = 4, left = 10, bottom = 6, right = 10)
        }
    }

    private fun createInfoPanel(): JPanel {
        variablesLabel.font = JBUI.Fonts.smallFont()
        variablesLabel.foreground = VARIABLES_FG

        previewLabel.font = JBUI.Fonts.smallFont()
        previewLabel.foreground = SUBTITLE_FG
        previewLabel.horizontalAlignment = SwingConstants.RIGHT

        val row = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(variablesLabel, BorderLayout.CENTER)
            add(previewLabel, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = FOOTER_BG
            border = JBUI.Borders.compound(
                RoundedLineBorder(FOOTER_BORDER, JBUI.scale(10)),
                JBUI.Borders.empty(6, 10),
            )
            add(row, BorderLayout.CENTER)
        }
    }

    private fun createCard(
        content: JComponent,
        top: Int = 10,
        left: Int = 10,
        bottom: Int = 10,
        right: Int = 10,
    ): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = CARD_BG
            border = JBUI.Borders.compound(
                RoundedLineBorder(CARD_BORDER, JBUI.scale(12)),
                JBUI.Borders.empty(top, left, bottom, right),
            )
            add(content, BorderLayout.CENTER)
        }
    }

    private fun <T : JComponent> lockPreferredHeight(component: T): T {
        component.maximumSize = Dimension(Int.MAX_VALUE, component.preferredSize.height)
        return component
    }

    private fun refreshLocalizedTexts() {
        if (!isCreateMode) {
            return
        }
        aiGenerateButton.text = SpecCodingBundle.message("prompt.editor.ai.generate")
        updatePrimaryButtonSize(aiGenerateButton)

        aiStepLabels[0].text = SpecCodingBundle.message("prompt.editor.ai.step.analyze")
        aiStepLabels[1].text = SpecCodingBundle.message("prompt.editor.ai.step.structure")
        aiStepLabels[2].text = SpecCodingBundle.message("prompt.editor.ai.step.content")
        aiStepLabels[3].text = SpecCodingBundle.message("prompt.editor.ai.step.polish")

        if (!aiGenerating) {
            setErrorText(null)
            applyAiStepStyle(activeIndex = -1)
        }
    }

    private fun stylePrimaryButton(button: JButton) {
        button.font = button.font.deriveFont(Font.BOLD, 12f)
        button.background = ACTION_BG
        button.foreground = ACTION_FG
        button.isOpaque = true
        button.isContentAreaFilled = true
        button.isFocusPainted = false
        button.border = JBUI.Borders.compound(
            RoundedLineBorder(ACTION_BORDER, JBUI.scale(20)),
            JBUI.Borders.empty(5, 14),
        )
        button.putClientProperty("JButton.buttonType", "roundRect")
        button.putClientProperty("JComponent.roundRectArc", JBUI.scale(20))
        updatePrimaryButtonSize(button)
    }

    private fun updatePrimaryButtonSize(button: JButton) {
        val textWidth = button.getFontMetrics(button.font).stringWidth(button.text ?: "")
        val insets = button.insets
        val width = maxOf(textWidth + insets.left + insets.right + JBUI.scale(14), JBUI.scale(84))
        button.preferredSize = JBUI.size(width, JBUI.scale(30))
        button.minimumSize = button.preferredSize
    }

    private fun setupAiGenerationSelectors() {
        aiProviderCombo.renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
            label.text = providerDisplayName(value)
        }
        aiModelCombo.renderer = SimpleListCellRenderer.create<ModelInfo> { label, value, _ ->
            label.text = value?.name.orEmpty()
        }
        styleAiSelectorCombo(aiProviderCombo, minWidth = 72, maxWidth = 180)
        styleAiSelectorCombo(aiModelCombo, minWidth = 96, maxWidth = 320)

        aiProviderCombo.addActionListener {
            if (syncingAiSelectors) return@addActionListener
            refreshAiModelCombo(preserveSelection = true)
            setErrorText(null)
        }
        aiModelCombo.addActionListener {
            if (syncingAiSelectors) return@addActionListener
            setErrorText(null)
        }

        refreshAiProviderCombo(preserveSelection = false)
    }

    private fun styleAiSelectorCombo(comboBox: ComboBox<*>, minWidth: Int, maxWidth: Int) {
        comboBox.putClientProperty("JComponent.roundRect", false)
        comboBox.putClientProperty("JComboBox.isBorderless", true)
        comboBox.putClientProperty("ComboBox.isBorderless", true)
        comboBox.putClientProperty("JComponent.outline", null)
        comboBox.background = INPUT_BG
        comboBox.foreground = TITLE_FG
        comboBox.border = JBUI.Borders.compound(
            RoundedLineBorder(BORDER_COLOR, JBUI.scale(10)),
            JBUI.Borders.empty(0, 6, 0, 6),
        )
        comboBox.isOpaque = true
        comboBox.font = JBUI.Fonts.smallFont()
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = comboBox,
            minWidth = JBUI.scale(minWidth),
            maxWidth = JBUI.scale(maxWidth),
            height = JBUI.scale(28),
        )
    }

    private fun refreshAiProviderCombo(preserveSelection: Boolean) {
        val settings = SpecCodingSettingsState.getInstance()
        val previousSelection = if (preserveSelection) aiProviderCombo.selectedItem as? String else null
        val providers = projectService.availableProviders()
            .mapNotNull { it.trim().ifBlank { null } }
            .distinct()

        syncingAiSelectors = true
        try {
            aiProviderCombo.removeAllItems()
            providers.forEach { aiProviderCombo.addItem(it) }

            val preferred = when {
                !previousSelection.isNullOrBlank() -> previousSelection
                settings.defaultProvider.isNotBlank() -> settings.defaultProvider
                else -> providers.firstOrNull()
            }
            val selectedProvider = providers.firstOrNull { it == preferred } ?: providers.firstOrNull()
            aiProviderCombo.selectedItem = selectedProvider
        } finally {
            syncingAiSelectors = false
        }

        refreshAiModelCombo(preserveSelection = preserveSelection)
    }

    private fun refreshAiModelCombo(preserveSelection: Boolean) {
        val settings = SpecCodingSettingsState.getInstance()
        val selectedProvider = selectedAiProviderId()
        val previousModelId = if (preserveSelection) {
            (aiModelCombo.selectedItem as? ModelInfo)?.id?.trim().orEmpty()
        } else {
            ""
        }

        syncingAiSelectors = true
        try {
            aiModelCombo.removeAllItems()

            if (selectedProvider.isNullOrBlank()) {
                aiModelCombo.isEnabled = false
                updateAiSelectorTooltips()
                return
            }

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

            models.forEach { aiModelCombo.addItem(it) }
            val preferredModelId = when {
                previousModelId.isNotBlank() -> previousModelId
                savedModelId.isNotBlank() -> savedModelId
                else -> ""
            }
            val selectedModel = models.firstOrNull { it.id == preferredModelId } ?: models.firstOrNull()
            if (selectedModel != null) {
                aiModelCombo.selectedItem = selectedModel
            }
            aiModelCombo.isEnabled = models.isNotEmpty()
            updateAiSelectorTooltips()
        } finally {
            syncingAiSelectors = false
        }
    }

    private fun updateAiSelectorTooltips() {
        aiProviderCombo.toolTipText = providerDisplayName(aiProviderCombo.selectedItem as? String)
        aiModelCombo.toolTipText = (aiModelCombo.selectedItem as? ModelInfo)?.name
    }

    private fun selectedAiProviderId(): String? {
        return (aiProviderCombo.selectedItem as? String)?.trim()?.ifBlank { null }
    }

    private fun selectedAiModelId(): String? {
        return (aiModelCombo.selectedItem as? ModelInfo)?.id?.trim()?.ifBlank { null }
    }

    private fun providerDisplayName(providerId: String?): String {
        return when (providerId) {
            ClaudeCliLlmProvider.ID -> SpecCodingBundle.message("statusbar.modelSelector.provider.claudeCli")
            CodexCliLlmProvider.ID -> SpecCodingBundle.message("statusbar.modelSelector.provider.codexCli")
            MockLlmProvider.ID -> SpecCodingBundle.message("toolwindow.model.mock")
            null -> ""
            else -> providerId
        }
    }

    private fun requestAiDraft() {
        if (!isCreateMode || aiGenerating) {
            return
        }
        val requirement = aiRequirementArea.text.trim()
        if (requirement.isBlank()) {
            setErrorText(SpecCodingBundle.message("prompt.editor.ai.requirementRequired"))
            return
        }
        val providerId = selectedAiProviderId()
        if (providerId.isNullOrBlank()) {
            setErrorText(SpecCodingBundle.message("prompt.editor.ai.providerRequired"))
            return
        }
        val modelId = selectedAiModelId()
        if (modelId.isNullOrBlank()) {
            setErrorText(SpecCodingBundle.message("prompt.editor.ai.modelRequired", providerDisplayName(providerId)))
            return
        }

        setErrorText(null)
        startAiAnimation()
        ApplicationManager.getApplication().executeOnPooledThread {
            val draftResult = runBlocking { generatePromptDraftWithAi(requirement, providerId, modelId) }
            SwingUtilities.invokeLater {
                stopAiAnimation()
                nameField.text = draftResult.draft.name
                contentPane.text = draftResult.draft.content
                updateHighlightAndPreview()

                if (draftResult.usedFallback) {
                    setErrorText(SpecCodingBundle.message("prompt.editor.ai.status.fallback", draftResult.reason.orEmpty()))
                } else {
                    setErrorText(null)
                }
            }
        }
    }

    private fun startAiAnimation() {
        aiGenerating = true
        aiGenerateButton.isEnabled = false
        aiProgressBar.isVisible = true
        aiAnimationTick = 0
        applyAiStepStyle(activeIndex = 0)
        aiAnimationTimer.start()
    }

    private fun stopAiAnimation() {
        aiAnimationTimer.stop()
        aiGenerating = false
        aiGenerateButton.isEnabled = true
        aiProgressBar.isVisible = false
        applyAiStepStyle(activeIndex = -1)
    }

    private fun updateAiAnimationFrame() {
        if (!aiGenerating) {
            return
        }
        val stepIndex = (aiAnimationTick / AI_STEP_FRAME_COUNT) % aiStepLabels.size
        applyAiStepStyle(activeIndex = stepIndex)
        aiAnimationTick += 1
    }

    private fun applyAiStepStyle(activeIndex: Int) {
        aiStepLabels.forEachIndexed { index, label ->
            if (index == activeIndex) {
                label.foreground = STEP_ACTIVE_FG
                label.background = STEP_ACTIVE_BG
                label.isOpaque = true
            } else {
                label.foreground = STEP_IDLE_FG
                label.background = STEP_IDLE_BG
                label.isOpaque = true
            }
        }
    }

    private suspend fun generatePromptDraftWithAi(
        requirement: String,
        providerId: String,
        modelId: String,
    ): PromptDraftResult {
        val fallback = buildFallbackPromptDraft(requirement)
        val prompt = buildString {
            appendLine("You are generating a reusable prompt template for an IDE coding assistant.")
            appendLine("Return ONLY strict JSON, no markdown fences, no explanation.")
            appendLine("Required JSON keys: name, content.")
            appendLine("Rules:")
            appendLine("1) name: concise and human-readable.")
            appendLine("2) content: a direct instruction template for the assistant.")
            appendLine("3) Keep content practical and implementation-oriented.")
            appendLine()
            appendLine("User requirement:")
            appendLine(requirement)
        }

        val responseText = StringBuilder()
        val chatResult = runCatching {
            projectService.chat(
                providerId = providerId,
                userInput = prompt,
                modelId = modelId,
                planExecuteVerifySections = false,
            ) { chunk ->
                if (chunk.delta.isNotBlank()) {
                    responseText.append(chunk.delta)
                }
            }
        }

        if (chatResult.isFailure) {
            return PromptDraftResult(
                draft = fallback,
                usedFallback = true,
                reason = chatResult.exceptionOrNull()?.message ?: SpecCodingBundle.message("common.unknown"),
            )
        }

        val rawResponse = responseText.toString().trim()
        if (rawResponse.isBlank()) {
            return PromptDraftResult(
                draft = fallback,
                usedFallback = true,
                reason = SpecCodingBundle.message("prompt.manager.ai.reason.emptyResponse"),
            )
        }

        val draft = runCatching { parsePromptDraft(rawResponse, requirement) }.getOrNull()
            ?: return PromptDraftResult(
                draft = fallback,
                usedFallback = true,
                reason = SpecCodingBundle.message("prompt.manager.ai.reason.invalidJson"),
            )

        return PromptDraftResult(
            draft = draft,
            usedFallback = false,
            reason = null,
        )
    }

    private fun parsePromptDraft(raw: String, requirement: String): GeneratedPromptDraft {
        val root = parsePromptJson(raw)
        val name = root["name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.ifBlank { null }
            ?: requirement
                .replace("\\s+".toRegex(), " ")
                .trim()
                .take(40)
                .ifBlank { SpecCodingBundle.message("prompt.manager.ai.defaultName") }

        val content = root["content"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.ifBlank { null }
            ?: throw IllegalArgumentException(SpecCodingBundle.message("prompt.manager.ai.reason.invalidJson"))

        return GeneratedPromptDraft(name = name, content = content)
    }

    private fun parsePromptJson(raw: String): JsonObject {
        val trimmed = raw.trim()
        val direct = runCatching { promptJson.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
        if (direct != null) {
            return direct
        }

        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            val candidate = trimmed.substring(firstBrace, lastBrace + 1)
            val extracted = runCatching { promptJson.parseToJsonElement(candidate) as? JsonObject }.getOrNull()
            if (extracted != null) {
                return extracted
            }
        }

        throw IllegalArgumentException(SpecCodingBundle.message("prompt.manager.ai.reason.invalidJson"))
    }

    private fun buildFallbackPromptDraft(requirement: String): GeneratedPromptDraft {
        val normalizedRequirement = requirement
            .replace("\\s+".toRegex(), " ")
            .trim()
            .ifBlank { SpecCodingBundle.message("prompt.manager.ai.defaultName") }

        val fallbackName = normalizedRequirement.take(40)
        val fallbackContent = buildString {
            appendLine("你是项目开发助手。")
            appendLine("围绕以下目标执行：")
            appendLine(normalizedRequirement)
            appendLine()
            appendLine("输出要求：")
            appendLine("1. 先澄清边界与约束。")
            appendLine("2. 再给出可执行步骤。")
            appendLine("3. 涉及代码时给出关键改动点。")
            appendLine("4. 最后附上验证建议。")
        }.trim()

        return GeneratedPromptDraft(name = fallbackName, content = fallbackContent)
    }

    private fun updateHighlightAndPreview() {
        SwingUtilities.invokeLater {
            val caretPos = contentPane.caretPosition
            PromptSyntaxHighlighter.highlight(contentPane)
            try {
                contentPane.caretPosition = caretPos.coerceAtMost(contentPane.document.length)
            } catch (_: Exception) {
                // ignore
            }

            val text = contentPane.text
            val vars = PromptSyntaxHighlighter.extractVariables(text)
            variablesLabel.text = if (vars.isNotEmpty()) {
                SpecCodingBundle.message(
                    "prompt.editor.variables.detected",
                    vars.joinToString(", ") { "{{$it}}" },
                )
            } else {
                SpecCodingBundle.message("prompt.editor.variables.none")
            }
            previewLabel.text = SpecCodingBundle.message("prompt.editor.characters", text.length)
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) {
            return ValidationInfo(SpecCodingBundle.message("prompt.editor.validation.nameRequired"), nameField)
        }
        if (contentPane.text.isBlank()) {
            return ValidationInfo(SpecCodingBundle.message("prompt.editor.validation.contentRequired"))
        }
        return null
    }

    override fun doOKAction() {
        val templateId = resolveTemplateId()
        val variables = PromptSyntaxHighlighter
            .extractVariables(contentPane.text)
            .associateWith { "" }

        val mergedVars = if (existing != null) {
            val merged = existing.variables.toMutableMap()
            variables.keys.forEach { key ->
                merged.putIfAbsent(key, "")
            }
            merged
        } else {
            variables
        }

        result = PromptTemplate(
            id = templateId,
            name = nameField.text.trim(),
            content = contentPane.text.trim(),
            variables = mergedVars,
            scope = PromptScope.PROJECT,
            tags = existing?.tags.orEmpty(),
        )

        super.doOKAction()
    }

    private fun resolveTemplateId(): String {
        existing?.id?.let { return it }

        val baseId = nameField.text
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "prompt" }

        if (baseId !in existingPromptIds) {
            return baseId
        }

        var index = 2
        var candidate = "$baseId-$index"
        while (candidate in existingPromptIds) {
            index += 1
            candidate = "$baseId-$index"
        }
        return candidate
    }

    override fun dispose() {
        aiAnimationTimer.stop()
        super.dispose()
    }

    private class RoundedLineBorder(
        private val lineColor: Color,
        private val arc: Int,
        private val thickness: Int = 1,
    ) : AbstractBorder() {
        override fun paintBorder(
            c: java.awt.Component?,
            g: Graphics?,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            val graphics = g as? Graphics2D ?: return
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g2.color = lineColor
                val safeThickness = thickness.coerceAtLeast(1)
                repeat(safeThickness) { index ->
                    val offset = index + 0.5f
                    val drawWidth = width - index * 2 - 1f
                    val drawHeight = height - index * 2 - 1f
                    if (drawWidth <= 0f || drawHeight <= 0f) return@repeat
                    val arcSize = (arc - index * 2).coerceAtLeast(2).toFloat()
                    g2.draw(
                        RoundRectangle2D.Float(
                            x + offset,
                            y + offset,
                            drawWidth,
                            drawHeight,
                            arcSize,
                            arcSize,
                        ),
                    )
                }
            } finally {
                g2.dispose()
            }
        }

        override fun getBorderInsets(c: java.awt.Component?): Insets = Insets(
            thickness,
            thickness,
            thickness,
            thickness,
        )

        override fun getBorderInsets(c: java.awt.Component?, insets: Insets): Insets {
            insets.set(
                thickness,
                thickness,
                thickness,
                thickness,
            )
            return insets
        }
    }

    private data class GeneratedPromptDraft(
        val name: String,
        val content: String,
    )

    private data class PromptDraftResult(
        val draft: GeneratedPromptDraft,
        val usedFallback: Boolean,
        val reason: String?,
    )

    companion object {
        private const val AI_ANIMATION_INTERVAL_MILLIS = 450
        private const val AI_STEP_FRAME_COUNT = 4

        private val CARD_BG = JBColor(Color(248, 251, 255), Color(58, 64, 74))
        private val CARD_BORDER = JBColor(Color(205, 218, 238), Color(92, 104, 121))
        private val BORDER_COLOR = JBColor(Color(189, 205, 230), Color(100, 112, 129))
        private val HEADER_BG = JBColor(Color(243, 248, 255), Color(54, 61, 72))
        private val HEADER_BORDER = JBColor(Color(195, 211, 236), Color(91, 103, 120))
        private val INPUT_BG = JBColor(Color(252, 254, 255), Color(56, 62, 73))
        private val FOOTER_BG = JBColor(Color(245, 249, 255), Color(56, 63, 74))
        private val FOOTER_BORDER = JBColor(Color(200, 214, 235), Color(89, 102, 121))

        private val TITLE_FG = JBColor(Color(43, 64, 96), Color(214, 225, 241))
        private val SUBTITLE_FG = JBColor(Color(101, 116, 137), Color(176, 190, 210))
        private val VARIABLES_FG = JBColor(Color(82, 95, 165), Color(177, 189, 236))

        private val ACTION_BG = JBColor(Color(218, 232, 252), Color(79, 99, 130))
        private val ACTION_BORDER = JBColor(Color(161, 186, 223), Color(113, 132, 162))
        private val ACTION_FG = JBColor(Color(38, 57, 88), Color(222, 232, 246))

        private val STEP_IDLE_BG = JBColor(Color(236, 243, 252), Color(71, 79, 92))
        private val STEP_IDLE_FG = JBColor(Color(96, 112, 139), Color(173, 185, 204))
        private val STEP_ACTIVE_BG = JBColor(Color(209, 226, 250), Color(86, 108, 140))
        private val STEP_ACTIVE_FG = JBColor(Color(43, 66, 103), Color(226, 235, 247))
    }
}
