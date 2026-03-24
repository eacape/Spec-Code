package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecEngineArtifactBootstrapTest {

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
    fun `createWorkflow should scaffold quick task artifacts from project template`() {
        val configPath = tempDir.resolve(".spec-coding").resolve("config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(
            configPath,
            """
            schemaVersion: 1
            defaultTemplate: QUICK_TASK
            """.trimIndent() + "\n",
        )

        val engine = SpecEngine(project, storage) { SpecGenerationResult.Failure("not used") }
        val workflow = engine.createWorkflow(
            title = "quick task",
            description = "artifact bootstrap",
        ).getOrThrow()

        val workflowDir = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflow.id)
        assertEquals(StageId.TASKS, workflow.currentStage)
        assertTrue(workflow.stageStates.getValue(StageId.TASKS).active)
        assertFalse(workflow.stageStates.getValue(StageId.REQUIREMENTS).active)
        assertFalse(workflow.stageStates.getValue(StageId.DESIGN).active)
        assertTrue(Files.exists(workflowDir.resolve("tasks.md")))
        assertFalse(Files.exists(workflowDir.resolve("requirements.md")))
        assertFalse(Files.exists(workflowDir.resolve("design.md")))
        assertFalse(Files.exists(workflowDir.resolve("verification.md")))
    }

    @Test
    fun `createWorkflow should scaffold tasks and verification for direct implement when verify enabled`() {
        val configPath = tempDir.resolve(".spec-coding").resolve("config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(
            configPath,
            """
            schemaVersion: 1
            defaultTemplate: DIRECT_IMPLEMENT
            templates:
              DIRECT_IMPLEMENT:
                verifyEnabled: true
            """.trimIndent() + "\n",
        )

        val engine = SpecEngine(project, storage) { SpecGenerationResult.Failure("not used") }
        val workflow = engine.createWorkflow(
            title = "direct implement",
            description = "artifact bootstrap",
        ).getOrThrow()

        val workflowDir = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflow.id)
        assertEquals(StageId.IMPLEMENT, workflow.currentStage)
        assertFalse(workflow.stageStates.getValue(StageId.TASKS).active)
        assertTrue(workflow.stageStates.getValue(StageId.VERIFY).active)
        assertTrue(Files.exists(workflowDir.resolve("tasks.md")))
        assertTrue(Files.exists(workflowDir.resolve("verification.md")))
    }

    @Test
    fun `createWorkflow should honor explicit verify override from dialog`() {
        val configPath = tempDir.resolve(".spec-coding").resolve("config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(
            configPath,
            """
            schemaVersion: 1
            defaultTemplate: DIRECT_IMPLEMENT
            templates:
              DIRECT_IMPLEMENT:
                verifyEnabled: true
            """.trimIndent() + "\n",
        )

        val engine = SpecEngine(project, storage) { SpecGenerationResult.Failure("not used") }
        val workflow = engine.createWorkflow(
            title = "direct implement without verify",
            description = "explicit verify override",
            verifyEnabled = false,
        ).getOrThrow()

        val workflowDir = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflow.id)
        assertFalse(workflow.verifyEnabled)
        assertFalse(workflow.stageStates.getValue(StageId.VERIFY).active)
        assertTrue(Files.exists(workflowDir.resolve("tasks.md")))
        assertFalse(Files.exists(workflowDir.resolve("verification.md")))
    }

    @Test
    fun `createWorkflow should honor explicit template selection over project default`() {
        val configPath = tempDir.resolve(".spec-coding").resolve("config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(
            configPath,
            """
            schemaVersion: 1
            defaultTemplate: FULL_SPEC
            """.trimIndent() + "\n",
        )

        val engine = SpecEngine(project, storage) { SpecGenerationResult.Failure("not used") }
        val workflow = engine.createWorkflow(
            title = "quick task from dialog",
            description = "explicit template",
            template = WorkflowTemplate.QUICK_TASK,
        ).getOrThrow()

        val workflowDir = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflow.id)
        assertEquals(WorkflowTemplate.QUICK_TASK, workflow.template)
        assertEquals(StageId.TASKS, workflow.currentStage)
        assertTrue(Files.exists(workflowDir.resolve("tasks.md")))
        assertFalse(Files.exists(workflowDir.resolve("requirements.md")))
        assertFalse(Files.exists(workflowDir.resolve("design.md")))
    }
}
