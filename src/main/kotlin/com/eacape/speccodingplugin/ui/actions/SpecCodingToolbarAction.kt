package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.eacape.speccodingplugin.window.GlobalConfigSyncService
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 编辑器工具栏 Action
 * 在编辑器右上角提供 Spec Code 快捷入口：
 * - 显示 CLI 状态
 * - 快速切换模型
 * - 打开 Tool Window
 * - 打开 Settings
 */
class SpecCodingToolbarAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val component = e.inputEvent?.component ?: return

        val items = buildMenuItems()
        val step = object : BaseListPopupStep<ToolbarMenuItem>(
            SpecCodingBundle.message("toolbar.popup.title"),
            items,
        ) {
            override fun getTextFor(value: ToolbarMenuItem): String = value.label

            override fun isSpeedSearchEnabled(): Boolean = true

            override fun onChosen(selectedValue: ToolbarMenuItem, finalChoice: Boolean): PopupStep<*>? {
                if (!finalChoice && selectedValue is ToolbarMenuItem.ModelSwitch) {
                    return createModelSubStep(selectedValue.provider)
                }
                if (finalChoice) {
                    invokeLater {
                        when (selectedValue) {
                            is ToolbarMenuItem.OpenChat -> {
                                val tw = ToolWindowManager.getInstance(project).getToolWindow("Spec Code")
                                tw?.show()
                            }
                            is ToolbarMenuItem.OpenSettings -> {
                                ShowSettingsUtil.getInstance().showSettingsDialog(
                                    project,
                                    "com.eacape.speccodingplugin.settings",
                                )
                            }
                            is ToolbarMenuItem.DetectCli -> {
                                CliDiscoveryService.getInstance().discoverAllAsync()
                            }
                            is ToolbarMenuItem.ModelSwitch -> {
                                // 选择 provider 后进入子菜单
                            }
                            is ToolbarMenuItem.Separator -> {}
                            is ToolbarMenuItem.StatusInfo -> {}
                        }
                    }
                }
                return PopupStep.FINAL_CHOICE
            }

            override fun hasSubstep(selectedValue: ToolbarMenuItem): Boolean {
                return selectedValue is ToolbarMenuItem.ModelSwitch
            }

            override fun isMnemonicsNavigationEnabled(): Boolean = true
        }

        JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(component)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
        e.presentation.text = SpecCodingBundle.message("toolbar.action.text")
        e.presentation.description = SpecCodingBundle.message("toolbar.action.description")

        // 显示当前模型名称
        val settings = SpecCodingSettingsState.getInstance()
        val modelId = settings.selectedCliModel
        val model = if (modelId.isNotBlank()) ModelRegistry.getInstance().getModel(modelId) else null
        e.presentation.text = if (model != null) {
            "Spec Code: ${model.name}"
        } else {
            "Spec Code"
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun buildMenuItems(): List<ToolbarMenuItem> {
        val items = mutableListOf<ToolbarMenuItem>()
        val discovery = CliDiscoveryService.getInstance()
        val settings = SpecCodingSettingsState.getInstance()

        // CLI 状态信息
        val claudeStatus = if (discovery.claudeInfo.available) {
            "Claude CLI: v${discovery.claudeInfo.version ?: "?"}"
        } else {
            "Claude CLI: ${SpecCodingBundle.message("settings.cli.claude.notFound")}"
        }
        val codexStatus = if (discovery.codexInfo.available) {
            "Codex CLI: v${discovery.codexInfo.version ?: "?"}"
        } else {
            "Codex CLI: ${SpecCodingBundle.message("settings.cli.codex.notFound")}"
        }
        items.add(ToolbarMenuItem.StatusInfo(claudeStatus))
        items.add(ToolbarMenuItem.StatusInfo(codexStatus))
        items.add(ToolbarMenuItem.Separator("---"))

        // 模型切换
        val registry = ModelRegistry.getInstance()
        val providers = registry.getModelsByProvider()
        for (provider in providers.keys) {
            val displayName = when (provider) {
                ClaudeCliLlmProvider.ID -> SpecCodingBundle.message("statusbar.modelSelector.provider.claudeCli")
                CodexCliLlmProvider.ID -> SpecCodingBundle.message("statusbar.modelSelector.provider.codexCli")
                else -> provider
            }
            items.add(ToolbarMenuItem.ModelSwitch(
                label = "${SpecCodingBundle.message("toolbar.switchModel")} ($displayName) →",
                provider = provider,
            ))
        }

        if (providers.isEmpty()) {
            items.add(ToolbarMenuItem.DetectCli(SpecCodingBundle.message("toolbar.detectCli")))
        }

        items.add(ToolbarMenuItem.Separator("---"))
        items.add(ToolbarMenuItem.OpenChat(SpecCodingBundle.message("toolbar.openChat")))
        items.add(ToolbarMenuItem.OpenSettings(SpecCodingBundle.message("toolbar.openSettings")))

        return items
    }

    private fun createModelSubStep(provider: String): PopupStep<ModelInfo> {
        val registry = ModelRegistry.getInstance()
        val models = registry.getModelsForProvider(provider)
        val settings = SpecCodingSettingsState.getInstance()

        return object : BaseListPopupStep<ModelInfo>(
            SpecCodingBundle.message("statusbar.modelSelector.popup.selectModel"),
            models,
        ) {
            override fun getTextFor(value: ModelInfo): String {
                val current = if (value.id == settings.selectedCliModel) " ✓" else ""
                return "${value.name}$current"
            }

            override fun onChosen(selectedValue: ModelInfo, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    settings.defaultProvider = provider
                    settings.selectedCliModel = selectedValue.id
                    GlobalConfigSyncService.getInstance().notifyGlobalConfigChanged(
                        sourceProject = null,
                        reason = "toolbar-model-switch",
                    )
                }
                return PopupStep.FINAL_CHOICE
            }
        }
    }

    sealed class ToolbarMenuItem(val label: String) {
        class StatusInfo(label: String) : ToolbarMenuItem(label)
        class Separator(label: String) : ToolbarMenuItem(label)
        class ModelSwitch(label: String, val provider: String) : ToolbarMenuItem(label)
        class DetectCli(label: String) : ToolbarMenuItem(label)
        class OpenChat(label: String) : ToolbarMenuItem(label)
        class OpenSettings(label: String) : ToolbarMenuItem(label)
    }
}
