package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecWorkspaceInitializerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
    }

    @Test
    fun `initializeProjectWorkspace should create required directories and guide`() {
        val initializer = SpecWorkspaceInitializer(project)

        val first = initializer.initializeProjectWorkspace()
        val second = initializer.initializeProjectWorkspace()

        assertEquals(first, second)
        assertTrue(Files.isDirectory(first.rootDir))
        assertTrue(Files.isDirectory(first.specsDir))
        assertTrue(Files.isDirectory(first.locksDir))
        assertTrue(Files.isDirectory(first.backupDir))

        val guidePath = first.rootDir.resolve("WORKSPACE.md")
        assertTrue(Files.isRegularFile(guidePath))
        val guideContent = Files.readString(guidePath)
        assertTrue(guideContent.contains("`specs/`"))
        assertTrue(guideContent.contains("`.locks/`"))
        assertTrue(guideContent.contains("`.backup/`"))
        assertTrue(guideContent.contains("`.history/snapshots/`"))
        assertTrue(guideContent.contains("`.history/audit.yaml`"))
    }

    @Test
    fun `saveWorkflow should initialize workflow history directories`() {
        val storage = SpecStorage.getInstance(project)
        val workflow = SpecWorkflow(
            id = "wf-workspace",
            currentPhase = SpecPhase.SPECIFY,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Workspace Init",
            description = "verify workspace init",
        )

        storage.saveWorkflow(workflow).getOrThrow()

        val root = tempDir.resolve(".spec-coding")
        assertTrue(Files.isDirectory(root.resolve(".locks")))
        assertTrue(Files.isDirectory(root.resolve(".backup")))
        assertTrue(Files.isDirectory(root.resolve("specs")))

        val historyDir = root
            .resolve("specs")
            .resolve(workflow.id)
            .resolve(".history")
        val sourcesDir = root
            .resolve("specs")
            .resolve(workflow.id)
            .resolve("sources")
        assertTrue(Files.isDirectory(sourcesDir))
        assertTrue(Files.isDirectory(historyDir))
        assertTrue(Files.isDirectory(historyDir.resolve("snapshots")))
        assertTrue(Files.isDirectory(historyDir.resolve("baselines")))
        assertTrue(Files.isDirectory(historyDir.resolve("config")))
        assertTrue(Files.isRegularFile(historyDir.resolve("audit.yaml")))
    }
}
