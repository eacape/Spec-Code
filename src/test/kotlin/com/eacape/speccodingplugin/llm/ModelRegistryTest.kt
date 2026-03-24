package com.eacape.speccodingplugin.llm

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ModelRegistryTest {

    private lateinit var registry: ModelRegistry

    @BeforeEach
    fun setUp() {
        // ModelRegistry.init may fail without IntelliJ Application context;
        // that's fine – it catches the exception and starts empty.
        registry = ModelRegistry()

        // Seed with known models so we can test query / capability logic.
        registry.register(
            ModelInfo("gpt-4o", "GPT-4o", "openai", 128_000,
                setOf(ModelCapability.CODE_GENERATION, ModelCapability.CODE_REVIEW, ModelCapability.CHAT, ModelCapability.REASONING))
        )
        registry.register(
            ModelInfo("gpt-4o-mini", "GPT-4o Mini", "openai", 128_000,
                setOf(ModelCapability.CODE_GENERATION, ModelCapability.CHAT))
        )
        registry.register(
            ModelInfo("claude-opus-4-20250514", "Claude Opus 4", "claude_cli", 200_000,
                setOf(ModelCapability.CODE_GENERATION, ModelCapability.CODE_REVIEW, ModelCapability.CHAT, ModelCapability.REASONING))
        )
        registry.register(
            ModelInfo("claude-sonnet-4-20250514", "Claude Sonnet 4", "claude_cli", 200_000,
                setOf(ModelCapability.CODE_GENERATION, ModelCapability.CODE_REVIEW, ModelCapability.CHAT, ModelCapability.REASONING))
        )
    }

    @Test
    fun `should have registered models`() {
        val models = registry.getAllModels()
        assertTrue(models.isNotEmpty(), "Registry should have models after seeding")
    }

    @Test
    fun `should get model by id`() {
        val model = registry.getModel("gpt-4o")
        assertNotNull(model)
        assertEquals("gpt-4o", model?.id)
        assertEquals("GPT-4o", model?.name)
        assertEquals("openai", model?.provider)
    }

    @Test
    fun `should return null for non-existent model`() {
        val model = registry.getModel("non-existent-model")
        assertNull(model)
    }

    @Test
    fun `should group models by provider`() {
        val grouped = registry.getModelsByProvider()
        assertTrue(grouped.containsKey("openai"))
        assertTrue(grouped.containsKey("claude_cli"))
        assertEquals(2, grouped["openai"]?.size)
        assertEquals(2, grouped["claude_cli"]?.size)
    }

    @Test
    fun `should check model capabilities`() {
        assertTrue(registry.hasCapability("gpt-4o", ModelCapability.CODE_GENERATION))
        assertTrue(registry.hasCapability("gpt-4o", ModelCapability.CODE_REVIEW))
        assertTrue(registry.hasCapability("claude-opus-4-20250514", ModelCapability.REASONING))
    }

    @Test
    fun `should return false for non-existent model capability check`() {
        assertFalse(registry.hasCapability("non-existent-model", ModelCapability.CODE_GENERATION))
    }

    @Test
    fun `should register custom model`() {
        val customModel = ModelInfo(
            id = "custom-model",
            name = "Custom Model",
            provider = "custom",
            contextWindow = 8000,
            capabilities = setOf(ModelCapability.CHAT)
        )
        registry.register(customModel)

        val retrieved = registry.getModel("custom-model")
        assertNotNull(retrieved)
        assertEquals("Custom Model", retrieved?.name)
        assertEquals("custom", retrieved?.provider)
    }

    @Test
    fun `should overwrite existing model on re-registration`() {
        val model1 = ModelInfo("test-model", "Test Model V1", "test", 4000, setOf(ModelCapability.CHAT))
        val model2 = ModelInfo("test-model", "Test Model V2", "test", 8000, setOf(ModelCapability.CHAT, ModelCapability.CODE_GENERATION))

        registry.register(model1)
        registry.register(model2)

        val retrieved = registry.getModel("test-model")
        assertEquals("Test Model V2", retrieved?.name)
        assertEquals(8000, retrieved?.contextWindow)
        assertEquals(2, retrieved?.capabilities?.size)
    }

    @Test
    fun `should have valid context windows`() {
        registry.getAllModels().forEach { model ->
            assertTrue(model.contextWindow > 0, "Model ${model.name} should have positive context window")
        }
    }

    @Test
    fun `should have unique model ids`() {
        val models = registry.getAllModels()
        val ids = models.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "All model IDs should be unique")
    }

    @Test
    fun `should have at least one capability per model`() {
        registry.getAllModels().forEach { model ->
            assertTrue(model.capabilities.isNotEmpty(), "Model ${model.name} should have at least one capability")
        }
    }
}
