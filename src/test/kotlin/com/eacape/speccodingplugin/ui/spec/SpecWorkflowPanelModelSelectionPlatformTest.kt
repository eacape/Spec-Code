package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.llm.ModelCapability
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.llm.MockLlmProvider
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class SpecWorkflowPanelModelSelectionPlatformTest : BasePlatformTestCase() {

    fun `test workflow panel should select configured default model on open`() {
        val settings = SpecCodingSettingsState.getInstance()
        val router = LlmRouter.getInstance()
        val providerId = router.availableUiProviders()
            .ifEmpty { router.availableProviders() }
            .ifEmpty { listOf(MockLlmProvider.ID) }
            .first()
        val modelRegistry = ModelRegistry.getInstance()
        val token = System.nanoTime()
        val defaultModelId = "settings-default-$token"
        val temporaryModelId = "settings-temp-$token"
        val originalProvider = settings.defaultProvider
        val originalModel = settings.selectedCliModel

        modelRegistry.register(
            ModelInfo(
                id = defaultModelId,
                name = "Settings Default $token",
                provider = providerId,
                contextWindow = 32_000,
                capabilities = setOf(ModelCapability.CHAT),
            ),
        )
        modelRegistry.register(
            ModelInfo(
                id = temporaryModelId,
                name = "Settings Temporary $token",
                provider = providerId,
                contextWindow = 32_000,
                capabilities = setOf(ModelCapability.CHAT),
            ),
        )

        try {
            settings.defaultProvider = providerId
            settings.selectedCliModel = defaultModelId

            val panel = createPanel()

            waitUntil {
                panel.selectedProviderIdForTest() == providerId &&
                    panel.selectedModelIdForTest() == defaultModelId
            }

            ApplicationManager.getApplication().invokeAndWait {
                panel.selectToolbarModelForTest(providerId, temporaryModelId)
            }
            waitUntil {
                panel.selectedModelIdForTest() == temporaryModelId
            }

            ApplicationManager.getApplication().invokeAndWait {
                panel.syncToolbarSelectionFromSettings()
            }
            waitUntil {
                panel.selectedProviderIdForTest() == providerId &&
                    panel.selectedModelIdForTest() == defaultModelId
            }
        } finally {
            settings.defaultProvider = originalProvider
            settings.selectedCliModel = originalModel
        }
    }

    private fun createPanel(): SpecWorkflowPanel {
        var panel: SpecWorkflowPanel? = null
        ApplicationManager.getApplication().invokeAndWait {
            panel = SpecWorkflowPanel(project)
            Disposer.register(testRootDisposable, panel!!)
        }
        return panel ?: error("Failed to create SpecWorkflowPanel")
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(20)
        }
        fail("Condition was not met within ${timeoutMs}ms")
    }
}
