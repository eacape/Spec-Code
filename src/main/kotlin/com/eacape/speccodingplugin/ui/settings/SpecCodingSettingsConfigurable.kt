package com.eacape.speccodingplugin.ui.settings

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.i18n.InterfaceLanguage
import com.eacape.speccodingplugin.i18n.LocaleManager
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.eacape.speccodingplugin.window.GlobalConfigSyncService
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.FlowLayout
import java.util.Locale
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings Configurable
 * Spec Coding 插件的设置页面
 */
class SpecCodingSettingsConfigurable : Configurable {

    private val settings = SpecCodingSettingsState.getInstance()
    private val globalConfigSyncService = GlobalConfigSyncService.getInstance()
    private val localeManager = LocaleManager.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 默认提供者 & 模型
    private val defaultProviderCombo = ComboBox<String>()
    private val defaultModelCombo = ComboBox<ModelInfo>()

    // 代理设置
    private val useProxyCheckBox = JBCheckBox()
    private val proxyHostField = JBTextField()
    private val proxyPortField = JBTextField()

    // 其他设置
    private val autoSaveCheckBox = JBCheckBox()
    private val maxHistorySizeField = JBTextField()
    private val chatOutputFontSizeField = JBTextField()

    // 引擎路径
    private val codexCliPathField = JBTextField()
    private val claudeCodeCliPathField = JBTextField()

    // CLI 探测
    private val detectCliButton = JButton()
    private val claudeCliStatusLabel = JBLabel("")
    private val codexCliStatusLabel = JBLabel("")

    // 操作模式
    private val defaultModeCombo = ComboBox(OperationMode.entries.toTypedArray())

    // 语言设置
    private val interfaceLanguageCombo = ComboBox(InterfaceLanguage.entries.toTypedArray())

    // 团队 Prompt 同步
    private val teamPromptRepoUrlField = JBTextField()
    private val teamPromptRepoBranchField = JBTextField()

    override fun getDisplayName(): String = SpecCodingBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        reset()

        // Provider ComboBox
        val router = LlmRouter.getInstance()
        defaultProviderCombo.removeAllItems()
        router.availableUiProviders().forEach { defaultProviderCombo.addItem(it) }
        defaultProviderCombo.renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
            label.text = when (value) {
                ClaudeCliLlmProvider.ID -> lowerUiText(SpecCodingBundle.message("statusbar.modelSelector.provider.claudeCli"))
                CodexCliLlmProvider.ID -> lowerUiText(SpecCodingBundle.message("statusbar.modelSelector.provider.codexCli"))
                else -> lowerUiText(value ?: "")
            }
        }
        defaultProviderCombo.selectedItem = settings.defaultProvider
        defaultProviderCombo.addActionListener { refreshModelCombo() }

        // Model ComboBox
        defaultModelCombo.renderer = SimpleListCellRenderer.create<ModelInfo> { label, value, _ ->
            label.text = lowerUiText(value?.name ?: "")
        }
        refreshModelCombo()

        // CLI 路径 placeholder 提示
        claudeCodeCliPathField.emptyText.text = SpecCodingBundle.message("settings.cli.claudePath.placeholder")
        codexCliPathField.emptyText.text = SpecCodingBundle.message("settings.cli.codexPath.placeholder")

        interfaceLanguageCombo.renderer = InterfaceLanguageCellRenderer.create()
        interfaceLanguageCombo.addActionListener {
            applyLanguageImmediately(reason = "settings-configurable-language-immediate")
        }
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = defaultProviderCombo,
            minWidth = JBUI.scale(72),
            maxWidth = JBUI.scale(200),
            height = defaultProviderCombo.preferredSize.height.takeIf { it > 0 } ?: JBUI.scale(24),
        )
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = defaultModelCombo,
            minWidth = JBUI.scale(96),
            maxWidth = JBUI.scale(280),
            height = defaultModelCombo.preferredSize.height.takeIf { it > 0 } ?: JBUI.scale(24),
        )
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = interfaceLanguageCombo,
            minWidth = JBUI.scale(92),
            maxWidth = JBUI.scale(220),
            height = interfaceLanguageCombo.preferredSize.height.takeIf { it > 0 } ?: JBUI.scale(24),
        )
        defaultModeCombo.renderer = SimpleListCellRenderer.create<OperationMode> { label, value, _ ->
            label.text = operationModeDisplayText(value ?: OperationMode.DEFAULT)
        }
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = defaultModeCombo,
            minWidth = JBUI.scale(110),
            maxWidth = JBUI.scale(180),
            height = defaultModeCombo.preferredSize.height.takeIf { it > 0 } ?: JBUI.scale(24),
        )
        detectCliButton.text = SpecCodingBundle.message("settings.cli.detectButton")
        detectCliButton.addActionListener { detectCliTools() }

        updateCliStatusLabels()

        val defaultProviderComboHost = ComboBoxAutoWidthSupport.createLeftAlignedHost(defaultProviderCombo)
        val defaultModelComboHost = ComboBoxAutoWidthSupport.createLeftAlignedHost(defaultModelCombo)
        val defaultModeComboHost = ComboBoxAutoWidthSupport.createLeftAlignedHost(defaultModeCombo)
        val interfaceLanguageComboHost = ComboBoxAutoWidthSupport.createLeftAlignedHost(interfaceLanguageCombo)

        val cliDetectPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        cliDetectPanel.isOpaque = false
        cliDetectPanel.add(detectCliButton)

        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.cliTools")}</b></html>"))
            .addLabeledComponent(SpecCodingBundle.message("settings.engine.claudePath"), claudeCodeCliPathField)
            .addLabeledComponent(SpecCodingBundle.message("settings.engine.codexPath"), codexCliPathField)
            .addComponent(cliDetectPanel)
            .addLabeledComponent(SpecCodingBundle.message("settings.cli.claudeLabel"), claudeCliStatusLabel)
            .addLabeledComponent(SpecCodingBundle.message("settings.cli.codexLabel"), codexCliStatusLabel)
            .addVerticalGap(10)
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.general")}</b></html>"))
            .addLabeledComponent(SpecCodingBundle.message("settings.general.defaultProvider"), defaultProviderComboHost)
            .addLabeledComponent(SpecCodingBundle.message("settings.general.defaultModel"), defaultModelComboHost)
            .addLabeledComponent(SpecCodingBundle.message("settings.general.interfaceLanguage"), interfaceLanguageComboHost)
            .addVerticalGap(10)
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.teamSync")}</b></html>"))
            .addLabeledComponent(SpecCodingBundle.message("settings.teamSync.promptRepoUrl"), teamPromptRepoUrlField)
            .addLabeledComponent(SpecCodingBundle.message("settings.teamSync.promptBranch"), teamPromptRepoBranchField)
            .addVerticalGap(10)
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.proxy")}</b></html>"))
            .addComponent(useProxyCheckBox.apply { text = SpecCodingBundle.message("settings.proxy.use") })
            .addLabeledComponent(SpecCodingBundle.message("settings.proxy.host"), proxyHostField)
            .addLabeledComponent(SpecCodingBundle.message("settings.proxy.port"), proxyPortField)
            .addVerticalGap(10)
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.other")}</b></html>"))
            .addComponent(autoSaveCheckBox.apply { text = SpecCodingBundle.message("settings.other.autoSave") })
            .addLabeledComponent(SpecCodingBundle.message("settings.other.maxHistorySize"), maxHistorySizeField)
            .addLabeledComponent(
                SpecCodingBundle.message("settings.other.chatOutputFontSize"),
                chatOutputFontSizeField,
            )
            .addVerticalGap(10)
            .addComponent(JBLabel("<html><b>${SpecCodingBundle.message("settings.section.operationMode")}</b></html>"))
            .addLabeledComponent(SpecCodingBundle.message("settings.operationMode.defaultMode"), defaultModeComboHost)
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply {
                border = JBUI.Borders.empty(10)
            }
    }

    override fun isModified(): Boolean {
        if ((defaultProviderCombo.selectedItem as? String) != settings.defaultProvider) return true
        val selectedModelId = (defaultModelCombo.selectedItem as? ModelInfo)?.id ?: ""
        if (selectedModelId != settings.selectedCliModel) return true
        if ((interfaceLanguageCombo.selectedItem as? InterfaceLanguage)?.code != settings.interfaceLanguage) return true
        if (teamPromptRepoUrlField.text != settings.teamPromptRepoUrl) return true
        if (teamPromptRepoBranchField.text != settings.teamPromptRepoBranch) return true
        if (useProxyCheckBox.isSelected != settings.useProxy) return true
        if (proxyHostField.text != settings.proxyHost) return true
        if (proxyPortField.text != settings.proxyPort.toString()) return true
        if (autoSaveCheckBox.isSelected != settings.autoSaveConversation) return true
        if (maxHistorySizeField.text != settings.maxHistorySize.toString()) return true
        if (
            chatOutputFontSizeField.text !=
            SpecCodingSettingsState.normalizeChatOutputFontSize(settings.chatOutputFontSize).toString()
        ) {
            return true
        }
        if (codexCliPathField.text != settings.codexCliPath) return true
        if (claudeCodeCliPathField.text != settings.claudeCodeCliPath) return true
        if ((defaultModeCombo.selectedItem as? OperationMode ?: OperationMode.DEFAULT).name != settings.defaultOperationMode) return true
        return false
    }

    override fun apply() {
        settings.defaultProvider = (defaultProviderCombo.selectedItem as? String) ?: settings.defaultProvider
        settings.selectedCliModel = (defaultModelCombo.selectedItem as? ModelInfo)?.id ?: settings.selectedCliModel
        val selectedLanguage = (interfaceLanguageCombo.selectedItem as? InterfaceLanguage) ?: InterfaceLanguage.AUTO
        val localeChanged = localeManager.setLanguage(selectedLanguage, reason = "settings-configurable-apply")
        settings.teamPromptRepoUrl = teamPromptRepoUrlField.text.trim()
        settings.teamPromptRepoBranch = teamPromptRepoBranchField.text.trim().ifBlank { "main" }

        settings.useProxy = useProxyCheckBox.isSelected
        settings.proxyHost = proxyHostField.text
        settings.proxyPort = proxyPortField.text.toIntOrNull() ?: 8080

        settings.autoSaveConversation = autoSaveCheckBox.isSelected
        settings.maxHistorySize = maxHistorySizeField.text.toIntOrNull() ?: 100
        settings.chatOutputFontSize = SpecCodingSettingsState.normalizeChatOutputFontSize(
            chatOutputFontSizeField.text.toIntOrNull() ?: settings.chatOutputFontSize,
        )

        settings.codexCliPath = codexCliPathField.text
        settings.claudeCodeCliPath = claudeCodeCliPathField.text

        settings.defaultOperationMode = (defaultModeCombo.selectedItem as? OperationMode ?: OperationMode.DEFAULT).name

        globalConfigSyncService.notifyGlobalConfigChanged(
            sourceProject = null,
            reason = if (localeChanged != null) "settings-configurable-apply-with-locale" else "settings-configurable-apply",
        )
    }

    private fun applyLanguageImmediately(reason: String) {
        val selectedLanguage = (interfaceLanguageCombo.selectedItem as? InterfaceLanguage) ?: InterfaceLanguage.AUTO
        val localeChanged = localeManager.setLanguage(selectedLanguage, reason = reason)
        if (localeChanged == null) {
            return
        }
        globalConfigSyncService.notifyGlobalConfigChanged(
            sourceProject = null,
            reason = "$reason-with-locale",
        )
    }

    override fun reset() {
        defaultProviderCombo.selectedItem = settings.defaultProvider
        refreshModelCombo()
        interfaceLanguageCombo.selectedItem = InterfaceLanguage.fromCode(settings.interfaceLanguage)
        teamPromptRepoUrlField.text = settings.teamPromptRepoUrl
        teamPromptRepoBranchField.text = settings.teamPromptRepoBranch

        useProxyCheckBox.isSelected = settings.useProxy
        proxyHostField.text = settings.proxyHost
        proxyPortField.text = settings.proxyPort.toString()

        autoSaveCheckBox.isSelected = settings.autoSaveConversation
        maxHistorySizeField.text = settings.maxHistorySize.toString()
        chatOutputFontSizeField.text = SpecCodingSettingsState.normalizeChatOutputFontSize(
            settings.chatOutputFontSize,
        ).toString()

        codexCliPathField.text = settings.codexCliPath
        claudeCodeCliPathField.text = settings.claudeCodeCliPath

        defaultModeCombo.selectedItem = resolveOperationMode(settings.defaultOperationMode)
    }

    override fun disposeUIResources() {
        scope.cancel()
    }

    private fun detectCliTools() {
        detectCliButton.isEnabled = false
        detectCliButton.text = SpecCodingBundle.message("settings.cli.detecting")

        val discoveryService = CliDiscoveryService.getInstance()
        scope.launch {
            discoveryService.discoverAll()
            javax.swing.SwingUtilities.invokeLater {
                updateCliStatusLabels()

                // 探测成功后回填路径到输入框
                if (discoveryService.claudeInfo.available && claudeCodeCliPathField.text.isBlank()) {
                    claudeCodeCliPathField.text = discoveryService.claudeInfo.path
                }
                if (discoveryService.codexInfo.available && codexCliPathField.text.isBlank()) {
                    codexCliPathField.text = discoveryService.codexInfo.path
                }

                // 刷新 provider combo
                val router = LlmRouter.getInstance()
                router.refreshProviders()
                val currentProvider = defaultProviderCombo.selectedItem as? String
                defaultProviderCombo.removeAllItems()
                router.availableUiProviders().forEach { defaultProviderCombo.addItem(it) }
                if (currentProvider != null && defaultProviderCombo.itemCount > 0) {
                    defaultProviderCombo.selectedItem = currentProvider
                }

                // 刷新 model registry & combo
                ModelRegistry.getInstance().refreshFromDiscovery()
                refreshModelCombo()

                detectCliButton.isEnabled = true
                detectCliButton.text = SpecCodingBundle.message("settings.cli.detectButton")
            }
        }
    }

    private fun updateCliStatusLabels() {
        val discoveryService = CliDiscoveryService.getInstance()

        val claudeInfo = discoveryService.claudeInfo
        if (claudeInfo.available) {
            val version = lowerUiText(claudeInfo.version ?: "unknown")
            claudeCliStatusLabel.text = SpecCodingBundle.message("settings.cli.claude.found", version)
            claudeCliStatusLabel.foreground = JBColor.GREEN.darker()
        } else {
            claudeCliStatusLabel.text = SpecCodingBundle.message("settings.cli.claude.notFound")
            claudeCliStatusLabel.foreground = JBColor.GRAY
        }

        val codexInfo = discoveryService.codexInfo
        if (codexInfo.available) {
            val version = lowerUiText(codexInfo.version ?: "unknown")
            codexCliStatusLabel.text = SpecCodingBundle.message("settings.cli.codex.found", version)
            codexCliStatusLabel.foreground = JBColor.GREEN.darker()
        } else {
            codexCliStatusLabel.text = SpecCodingBundle.message("settings.cli.codex.notFound")
            codexCliStatusLabel.foreground = JBColor.GRAY
        }
    }

    private fun refreshModelCombo() {
        val selectedProvider = defaultProviderCombo.selectedItem as? String ?: return
        val registry = ModelRegistry.getInstance()
        val models = registry.getModelsForProvider(selectedProvider)

        defaultModelCombo.removeAllItems()
        models.forEach { defaultModelCombo.addItem(it) }

        // 恢复之前选中的模型
        val savedModelId = settings.selectedCliModel
        val match = models.find { it.id == savedModelId }
        if (match != null) {
            defaultModelCombo.selectedItem = match
        } else if (defaultModelCombo.itemCount > 0) {
            defaultModelCombo.selectedIndex = 0
        }
    }

    private object InterfaceLanguageCellRenderer {
        fun create(): SimpleListCellRenderer<InterfaceLanguage> {
            return SimpleListCellRenderer.create<InterfaceLanguage> { label, value, _ ->
                val text = value?.let { SpecCodingBundle.messageOrDefault(it.labelKey, it.code) } ?: ""
                label.text = text.lowercase(Locale.ROOT)
            }
        }
    }

    private fun lowerUiText(text: String): String = text.lowercase(Locale.ROOT)

    private fun operationModeDisplayText(mode: OperationMode): String {
        return lowerUiText(
            SpecCodingBundle.message("operation.mode.${mode.name.lowercase(Locale.ROOT)}.title"),
        )
    }

    private fun resolveOperationMode(raw: String): OperationMode {
        return runCatching { OperationMode.valueOf(raw.trim().uppercase(Locale.ROOT)) }
            .getOrDefault(OperationMode.DEFAULT)
    }
}
