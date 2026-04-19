package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.llm.ModelCapability
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.MockLlmProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowToolbarModelSelectionCoordinatorTest {

    @Test
    fun `providerPlan should preserve previous provider selection when it is still available`() {
        val plan = SpecWorkflowToolbarModelSelectionCoordinator.providerPlan(
            availableUiProviders = listOf("claude-cli", "codex-cli"),
            availableProviders = listOf(MockLlmProvider.ID),
            previousSelection = " codex-cli ",
            settingsDefaultProvider = "claude-cli",
            routerDefaultProvider = "claude-cli",
            mockLabel = "mock",
        )

        assertEquals(listOf("claude-cli", "codex-cli"), plan.providerIds)
        assertEquals("codex-cli", plan.selectedProviderId)
        assertEquals("codex", plan.selectedProviderTooltip)
    }

    @Test
    fun `providerPlan should fall back to settings default and synthesize mock provider when discovery is empty`() {
        val settingsFallback = SpecWorkflowToolbarModelSelectionCoordinator.providerPlan(
            availableUiProviders = listOf("claude-cli", "codex-cli"),
            availableProviders = emptyList(),
            previousSelection = null,
            settingsDefaultProvider = "codex-cli",
            routerDefaultProvider = "claude-cli",
            mockLabel = "mock",
        )
        val mockFallback = SpecWorkflowToolbarModelSelectionCoordinator.providerPlan(
            availableUiProviders = emptyList(),
            availableProviders = emptyList(),
            previousSelection = null,
            settingsDefaultProvider = "",
            routerDefaultProvider = "",
            mockLabel = "mock",
        )

        assertEquals("codex-cli", settingsFallback.selectedProviderId)
        assertEquals("codex", settingsFallback.selectedProviderTooltip)
        assertEquals(listOf(MockLlmProvider.ID), mockFallback.providerIds)
        assertEquals(MockLlmProvider.ID, mockFallback.selectedProviderId)
        assertEquals("mock", mockFallback.selectedProviderTooltip)
    }

    @Test
    fun `modelPlan should sort models and preserve previous selection`() {
        val plan = SpecWorkflowToolbarModelSelectionCoordinator.modelPlan(
            selectedProviderId = "claude-cli",
            modelsForProvider = listOf(
                model(id = "model-z", name = "zeta"),
                model(id = "model-a", name = "Alpha"),
                model(id = "model-m", name = "mid"),
            ),
            previousModelId = " model-m ",
            settingsDefaultProvider = "claude-cli",
            settingsSelectedModelId = "model-a",
        )

        assertEquals(listOf("model-a", "model-m", "model-z"), plan.models.map(ModelInfo::id))
        assertEquals("model-m", plan.selectedModelId)
        assertEquals("mid", plan.selectedModelTooltip)
        assertTrue(plan.enabled)
    }

    @Test
    fun `modelPlan should synthesize saved model for selected default provider when discovery is empty`() {
        val plan = SpecWorkflowToolbarModelSelectionCoordinator.modelPlan(
            selectedProviderId = "codex-cli",
            modelsForProvider = emptyList(),
            previousModelId = null,
            settingsDefaultProvider = "codex-cli",
            settingsSelectedModelId = "codex-o4",
        )

        assertEquals(listOf("codex-o4"), plan.models.map(ModelInfo::id))
        assertEquals("codex-o4", plan.selectedModelId)
        assertEquals("codex-o4", plan.selectedModelTooltip)
        assertTrue(plan.enabled)
    }

    @Test
    fun `modelPlan should stay disabled when provider is blank or saved model belongs to another provider`() {
        val noProviderPlan = SpecWorkflowToolbarModelSelectionCoordinator.modelPlan(
            selectedProviderId = "   ",
            modelsForProvider = listOf(model(id = "model-a", name = "Alpha")),
            previousModelId = "model-a",
            settingsDefaultProvider = "claude-cli",
            settingsSelectedModelId = "model-a",
        )
        val mismatchedProviderPlan = SpecWorkflowToolbarModelSelectionCoordinator.modelPlan(
            selectedProviderId = "codex-cli",
            modelsForProvider = emptyList(),
            previousModelId = null,
            settingsDefaultProvider = "claude-cli",
            settingsSelectedModelId = "claude-sonnet",
        )

        assertTrue(noProviderPlan.models.isEmpty())
        assertNull(noProviderPlan.selectedModelId)
        assertFalse(noProviderPlan.enabled)
        assertTrue(mismatchedProviderPlan.models.isEmpty())
        assertNull(mismatchedProviderPlan.selectedModelId)
        assertFalse(mismatchedProviderPlan.enabled)
    }

    private fun model(id: String, name: String): ModelInfo {
        return ModelInfo(
            id = id,
            name = name,
            provider = "claude-cli",
            contextWindow = 32_000,
            capabilities = setOf(ModelCapability.CHAT),
        )
    }
}
