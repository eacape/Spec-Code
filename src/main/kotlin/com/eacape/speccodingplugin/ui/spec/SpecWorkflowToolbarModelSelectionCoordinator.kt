package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.MockLlmProvider
import java.util.Locale

internal data class SpecWorkflowToolbarProviderSelectionPlan(
    val providerIds: List<String>,
    val selectedProviderId: String?,
    val selectedProviderTooltip: String,
)

internal data class SpecWorkflowToolbarModelSelectionPlan(
    val models: List<ModelInfo>,
    val selectedModelId: String?,
    val enabled: Boolean,
    val selectedModelTooltip: String?,
)

internal object SpecWorkflowToolbarModelSelectionCoordinator {

    fun providerPlan(
        availableUiProviders: List<String>,
        availableProviders: List<String>,
        previousSelection: String?,
        settingsDefaultProvider: String,
        routerDefaultProvider: String,
        mockLabel: String,
    ): SpecWorkflowToolbarProviderSelectionPlan {
        val providers = availableUiProviders
            .ifEmpty { availableProviders }
            .ifEmpty { listOf(MockLlmProvider.ID) }
        val selectedProviderId = resolveSelectedProvider(
            providers = providers,
            previousSelection = previousSelection,
            settingsDefaultProvider = settingsDefaultProvider,
            routerDefaultProvider = routerDefaultProvider,
        )
        return SpecWorkflowToolbarProviderSelectionPlan(
            providerIds = providers,
            selectedProviderId = selectedProviderId,
            selectedProviderTooltip = providerDisplayName(selectedProviderId, mockLabel),
        )
    }

    fun modelPlan(
        selectedProviderId: String?,
        modelsForProvider: List<ModelInfo>,
        previousModelId: String?,
        settingsDefaultProvider: String,
        settingsSelectedModelId: String,
    ): SpecWorkflowToolbarModelSelectionPlan {
        val providerId = selectedProviderId?.trim().orEmpty()
        if (providerId.isBlank()) {
            return SpecWorkflowToolbarModelSelectionPlan(
                models = emptyList(),
                selectedModelId = null,
                enabled = false,
                selectedModelTooltip = null,
            )
        }

        val savedModelId = settingsSelectedModelId.trim()
        val models = modelsForProvider
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .toMutableList()
        if (models.isEmpty() && savedModelId.isNotBlank() && providerId == settingsDefaultProvider.trim()) {
            models += ModelInfo(
                id = savedModelId,
                name = savedModelId,
                provider = providerId,
                contextWindow = 0,
                capabilities = emptySet(),
            )
        }

        val preferredModelId = previousModelId?.trim().takeUnless { it.isNullOrBlank() }
            ?: savedModelId.takeUnless(String::isBlank)
        val selectedModel = preferredModelId?.let { preferred ->
            models.firstOrNull { it.id == preferred }
        } ?: models.firstOrNull()
        return SpecWorkflowToolbarModelSelectionPlan(
            models = models,
            selectedModelId = selectedModel?.id,
            enabled = models.isNotEmpty(),
            selectedModelTooltip = selectedModel?.name,
        )
    }

    fun providerDisplayName(providerId: String?, mockLabel: String): String {
        return when (providerId?.trim()) {
            ClaudeCliLlmProvider.ID -> "claude"
            CodexCliLlmProvider.ID -> "codex"
            MockLlmProvider.ID -> mockLabel
            null,
            "" -> ""
            else -> providerId.lowercase(Locale.ROOT)
        }
    }

    private fun resolveSelectedProvider(
        providers: List<String>,
        previousSelection: String?,
        settingsDefaultProvider: String,
        routerDefaultProvider: String,
    ): String? {
        val preferred = previousSelection?.trim().takeUnless { it.isNullOrBlank() }
            ?: settingsDefaultProvider.trim().takeUnless(String::isBlank)
            ?: routerDefaultProvider.trim().takeUnless(String::isBlank)
        return preferred?.let { preferredId ->
            providers.firstOrNull { it == preferredId }
        } ?: providers.firstOrNull()
    }
}
