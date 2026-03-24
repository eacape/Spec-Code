package com.eacape.speccodingplugin.acceptance.phase1

import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.core.OperationModeManager
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.prompt.GlobalPromptManager
import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase1AcceptanceWorkflowTest {

    @Test
    fun `phase1 acceptance preconditions should be available`() {
        val registry = ModelRegistry()

        // In test environment without IntelliJ Application context,
        // CLI discovery is unavailable so registry starts empty.
        // Verify it can accept dynamic registrations.
        registry.register(
            com.eacape.speccodingplugin.llm.ModelInfo(
                id = "claude-sonnet-4-20250514",
                name = "Claude Sonnet 4",
                provider = "claude_cli",
                contextWindow = 200_000,
                capabilities = setOf(com.eacape.speccodingplugin.llm.ModelCapability.CODE_GENERATION),
            )
        )
        assertTrue(registry.getAllModels().isNotEmpty(), "Model registry should accept dynamic registrations")
        assertTrue(registry.getModelsForProvider("claude_cli").isNotEmpty(), "CLI models should be available after registration")

        val promptManager = GlobalPromptManager()
        assertTrue(promptManager.listPromptTemplates().isNotEmpty(), "Global prompt manager should have templates")

        val operationModeManager = OperationModeManager(fakeProject())
        assertEquals(OperationMode.DEFAULT, operationModeManager.getCurrentMode())
    }

    @Test
    fun `operation modes required by phase1 should all be reachable`() {
        val manager = OperationModeManager(fakeProject())

        OperationMode.entries.forEach { mode ->
            manager.switchMode(mode)
            assertEquals(mode, manager.getCurrentMode(), "Mode should switch to $mode")
        }
    }

    private fun fakeProject(): Project {
        return mockk(relaxed = true)
    }
}
