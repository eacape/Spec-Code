package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.llm.LlmRouter

internal data class RequirementsSectionAiSettings(
    val defaultProviderId: String? = null,
    val selectedCliModel: String? = null,
)

internal object RequirementsSectionAiSupport {

    fun resolvePreferredRealProviderId(
        providerHint: String? = null,
        llmRouter: LlmRouter? = null,
        settingsProvider: (() -> RequirementsSectionAiSettings)? = null,
    ): String? {
        val router = llmRouter ?: runCatching { LlmRouter.getInstance() }.getOrNull() ?: return null
        val availableProviders = router.availableUiProviders()
        if (availableProviders.isEmpty()) {
            return null
        }

        val hintedProvider = providerHint
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (hintedProvider != null) {
            availableProviders.firstOrNull { provider -> provider == hintedProvider }?.let { return it }
        }

        val defaultProvider = runCatching {
            (settingsProvider ?: ::loadSettings)().defaultProviderId.trimToValue()
        }.getOrNull()
        if (defaultProvider != null) {
            availableProviders.firstOrNull { provider -> provider == defaultProvider }?.let { return it }
        }

        return availableProviders.firstOrNull()
    }

    fun loadSettings(): RequirementsSectionAiSettings {
        return runCatching {
            val settingsClass = Class.forName("com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState")
            val instance = settingsClass.getMethod("getInstance").invoke(null)
            RequirementsSectionAiSettings(
                defaultProviderId = settingsClass
                    .getMethod("getDefaultProvider")
                    .invoke(instance) as? String,
                selectedCliModel = settingsClass
                    .getMethod("getSelectedCliModel")
                    .invoke(instance) as? String,
            )
        }.getOrElse { RequirementsSectionAiSettings() }
    }

    fun unavailableReason(
        providerHint: String? = null,
        llmRouter: LlmRouter? = null,
        settingsProvider: (() -> RequirementsSectionAiSettings)? = null,
    ): String? {
        return if (
            resolvePreferredRealProviderId(
                providerHint = providerHint,
                llmRouter = llmRouter,
                settingsProvider = settingsProvider,
            ) == null
        ) {
            SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.error.aiUnavailable")
        } else {
            null
        }
    }

    private fun String?.trimToValue(): String? {
        return this
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

}
