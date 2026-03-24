package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecEngineTemplateSwitchPreviewTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
    }

    @Test
    fun `previewTemplateSwitch should report stage and gate impacts with preview id`() {
        val engine = SpecEngine(project, storage) { SpecGenerationResult.Failure("unused") }
        val created = engine.createWorkflow(
            title = "Full spec workflow",
            description = "switch preview",
        ).getOrThrow()

        val preview = engine.previewTemplateSwitch(created.id, WorkflowTemplate.DIRECT_IMPLEMENT).getOrThrow()

        assertTrue(WorkflowIdGenerator.isValid(preview.previewId, prefix = "preview"))
        assertEquals(WorkflowTemplate.FULL_SPEC, preview.fromTemplate)
        assertEquals(WorkflowTemplate.DIRECT_IMPLEMENT, preview.toTemplate)
        assertEquals(StageId.REQUIREMENTS, preview.currentStage)
        assertEquals(StageId.IMPLEMENT, preview.resultingStage)
        assertTrue(preview.currentStageChanged)
        assertEquals(emptyList<StageId>(), preview.addedActiveStages)
        assertEquals(
            listOf(StageId.REQUIREMENTS, StageId.DESIGN, StageId.TASKS),
            preview.deactivatedStages,
        )
        assertEquals(emptyList<StageId>(), preview.gateAddedStages)
        assertEquals(
            listOf(StageId.REQUIREMENTS, StageId.DESIGN, StageId.TASKS),
            preview.gateRemovedStages,
        )
        assertEquals(listOf("tasks.md"), preview.artifactImpacts.map { it.fileName })
        assertEquals(
            TemplateSwitchArtifactStrategy.REUSE_EXISTING,
            preview.artifactImpacts.single().strategy,
        )
    }

    @Test
    fun `previewTemplateSwitch should surface missing required artifacts without scaffolding them`() {
        val configPath = tempDir.resolve(".spec-coding").resolve("config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(
            configPath,
            """
            schemaVersion: 1
            defaultTemplate: DIRECT_IMPLEMENT
            """.trimIndent() + "\n",
        )

        val engine = SpecEngine(project, storage) { SpecGenerationResult.Failure("unused") }
        val created = engine.createWorkflow(
            title = "Direct implement workflow",
            description = "switch preview",
        ).getOrThrow()

        val preview = engine.previewTemplateSwitch(created.id, WorkflowTemplate.FULL_SPEC).getOrThrow()
        val workflowDir = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(created.id)

        assertEquals(listOf(StageId.REQUIREMENTS, StageId.DESIGN, StageId.TASKS), preview.addedActiveStages)
        assertEquals(emptyList<StageId>(), preview.deactivatedStages)
        assertEquals(listOf(StageId.REQUIREMENTS, StageId.DESIGN, StageId.TASKS), preview.gateAddedStages)
        assertFalse(preview.currentStageChanged)
        assertEquals(StageId.IMPLEMENT, preview.resultingStage)
        assertEquals(
            listOf("requirements.md", "design.md"),
            preview.missingRequiredArtifacts.map { it.fileName },
        )
        assertEquals(
            TemplateSwitchArtifactStrategy.GENERATE_SKELETON,
            preview.missingRequiredArtifacts.first { it.fileName == "requirements.md" }.strategy,
        )
        assertEquals(
            TemplateSwitchArtifactStrategy.GENERATE_SKELETON,
            preview.missingRequiredArtifacts.first { it.fileName == "design.md" }.strategy,
        )
        assertEquals(
            TemplateSwitchArtifactStrategy.REUSE_EXISTING,
            preview.artifactImpacts.first { it.fileName == "tasks.md" }.strategy,
        )
        assertFalse(Files.exists(workflowDir.resolve("requirements.md")))
        assertFalse(Files.exists(workflowDir.resolve("design.md")))
    }
}
