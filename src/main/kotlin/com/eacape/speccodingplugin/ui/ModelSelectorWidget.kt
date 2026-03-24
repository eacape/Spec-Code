package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.eacape.speccodingplugin.window.GlobalConfigChangedEvent
import com.eacape.speccodingplugin.window.GlobalConfigSyncListener
import com.eacape.speccodingplugin.window.GlobalConfigSyncService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

/**
 * 状态栏模型选择器 Widget
 * 显示当前选中的模型，点击可弹出模型列表进行切换
 */
class ModelSelectorWidget(project: Project) : EditorBasedWidget(project), StatusBarWidget.MultipleTextValuesPresentation {

    private val modelRegistry = ModelRegistry.getInstance()
    private val settings = SpecCodingSettingsState.getInstance()
    private val globalConfigSyncService = GlobalConfigSyncService.getInstance()

    init {
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(
            GlobalConfigSyncListener.TOPIC,
            object : GlobalConfigSyncListener {
                override fun onGlobalConfigChanged(event: GlobalConfigChangedEvent) {
                    updateWidgetSafely()
                }
            }
        )

        connection.subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    updateWidgetSafely()
                }
            }
        )
    }

    override fun ID(): String = "SpecCoding.ModelSelector"

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getTooltipText(): String {
        val currentModel = getCurrentModel()
        return if (currentModel != null) {
            SpecCodingBundle.message(
                "statusbar.modelSelector.tooltip.current",
                currentModel.name,
                providerDisplayName(currentModel.provider),
                currentModel.contextWindow,
            )
        } else {
            SpecCodingBundle.message("statusbar.modelSelector.tooltip.none")
        }
    }

    override fun getSelectedValue(): String {
        val currentModel = getCurrentModel()
        return if (currentModel != null) {
            SpecCodingBundle.message("statusbar.modelSelector.selected.current", currentModel.name)
        } else {
            SpecCodingBundle.message("statusbar.modelSelector.selected.none")
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? {
        return Consumer { showModelSelectionPopup() }
    }

    private fun showModelSelectionPopup() {
        val popup = createModelSelectionPopup()
        val statusBar = WindowManager.getInstance().getStatusBar(project) ?: return
        popup.showInCenterOf(statusBar.component ?: return)
    }

    private fun createModelSelectionPopup(): ListPopup {
        val modelsByProvider = modelRegistry.getModelsByProvider()

        val providerStep = object : BaseListPopupStep<String>(
            SpecCodingBundle.message("statusbar.modelSelector.popup.selectProvider"),
            modelsByProvider.keys.toList(),
        ) {
            override fun getTextFor(value: String): String = providerDisplayName(value)

            override fun isSpeedSearchEnabled(): Boolean = true

            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    return null
                }
                return createModelSelectionStep(selectedValue, modelsByProvider[selectedValue] ?: emptyList())
            }

            override fun hasSubstep(selectedValue: String): Boolean = true
        }

        return JBPopupFactory.getInstance().createListPopup(providerStep)
    }

    private fun createModelSelectionStep(provider: String, models: List<ModelInfo>): PopupStep<ModelInfo> {
        return object : BaseListPopupStep<ModelInfo>(
            SpecCodingBundle.message("statusbar.modelSelector.popup.selectModel"),
            models,
        ) {
            override fun getTextFor(value: ModelInfo): String {
                val current = if (isCurrentModel(value)) {
                    SpecCodingBundle.message("statusbar.modelSelector.item.currentSuffix")
                } else {
                    ""
                }
                return "${value.name}$current"
            }

            override fun isSpeedSearchEnabled(): Boolean = true

            override fun onChosen(selectedValue: ModelInfo, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    selectModel(provider, selectedValue)
                }
                return PopupStep.FINAL_CHOICE
            }
        }
    }

    private fun selectModel(provider: String, model: ModelInfo) {
        settings.defaultProvider = provider
        settings.selectedCliModel = model.id

        globalConfigSyncService.notifyGlobalConfigChanged(
            sourceProject = project,
            reason = "model-selector-widget",
        )

        myStatusBar?.updateWidget(ID())
    }

    private fun providerDisplayName(provider: String): String {
        return when (provider) {
            ClaudeCliLlmProvider.ID -> SpecCodingBundle.message("statusbar.modelSelector.provider.claudeCli")
            CodexCliLlmProvider.ID -> SpecCodingBundle.message("statusbar.modelSelector.provider.codexCli")
            else -> provider.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun updateWidgetSafely() {
        if (project.isDisposed) {
            return
        }
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                myStatusBar?.updateWidget(ID())
            }
        }
    }

    private fun getCurrentModel(): ModelInfo? {
        val modelId = settings.selectedCliModel
        if (modelId.isBlank()) return null
        return modelRegistry.getModel(modelId)
    }

    private fun isCurrentModel(model: ModelInfo): Boolean {
        return getCurrentModel()?.id == model.id
    }

    companion object {
        const val ID = "SpecCoding.ModelSelector"
    }
}
