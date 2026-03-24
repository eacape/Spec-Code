package com.eacape.speccodingplugin.prompt

import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class TeamPromptSyncServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var projectRoot: Path
    private lateinit var project: Project
    private lateinit var settings: SpecCodingSettingsState
    private lateinit var promptManager: PromptManager

    @BeforeEach
    fun setUp() {
        projectRoot = tempDir.resolve("project")
        Files.createDirectories(projectRoot)

        project = mockk(relaxed = true)
        every { project.basePath } returns projectRoot.toString()
        every { project.name } returns "DemoProject"

        settings = SpecCodingSettingsState().apply {
            teamPromptRepoUrl = "https://example.com/team-prompts.git"
            teamPromptRepoBranch = "main"
        }

        promptManager = mockk(relaxed = true)
    }

    @Test
    fun `pullFromTeamRepo should copy team prompts and reload prompt manager`() {
        val commands = mutableListOf<Pair<Path?, List<String>>>()
        val gitExecutor = GitCommandExecutor { workingDir, args ->
            commands += workingDir to args
            when {
                args.firstOrNull() == "clone" -> {
                    val mirror = Paths.get(args.last())
                    Files.createDirectories(mirror.resolve(".git"))
                    val promptDir = mirror.resolve(".spec-coding").resolve("prompts")
                    Files.createDirectories(promptDir)
                    Files.writeString(promptDir.resolve("catalog.yaml"), "templates:\n  - id: cloned\n")
                    Result.success("cloned")
                }
                args == listOf("pull", "--ff-only", "origin", "main") -> {
                    val mirror = workingDir ?: error("workingDir is required for pull")
                    val promptDir = mirror.resolve(".spec-coding").resolve("prompts")
                    Files.createDirectories(promptDir)
                    Files.writeString(promptDir.resolve("catalog.yaml"), "templates:\n  - id: pulled\n")
                    Result.success("pulled")
                }
                else -> Result.success("")
            }
        }

        val service = TeamPromptSyncService(
            project = project,
            settingsProvider = { settings },
            promptManagerProvider = { promptManager },
            gitExecutor = gitExecutor,
        )

        val result = service.pullFromTeamRepo()

        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertEquals("main", payload.branch)
        assertEquals(1, payload.syncedFiles)
        verify(exactly = 1) { promptManager.reloadFromDisk() }

        val localCatalog = projectRoot.resolve(".spec-coding").resolve("prompts").resolve("catalog.yaml")
        assertTrue(Files.exists(localCatalog))
        assertTrue(Files.readString(localCatalog).contains("pulled"))
        assertTrue(commands.any { it.second == listOf("pull", "--ff-only", "origin", "main") })
    }

    @Test
    fun `pushToTeamRepo should commit and push when prompts changed`() {
        val localPromptDir = projectRoot.resolve(".spec-coding").resolve("prompts")
        Files.createDirectories(localPromptDir)
        Files.writeString(localPromptDir.resolve("catalog.yaml"), "templates:\n  - id: local\n")

        val commands = mutableListOf<Pair<Path?, List<String>>>()
        val gitExecutor = GitCommandExecutor { workingDir, args ->
            commands += workingDir to args
            when {
                args.firstOrNull() == "clone" -> {
                    val mirror = Paths.get(args.last())
                    Files.createDirectories(mirror.resolve(".git"))
                    Result.success("cloned")
                }
                args == listOf("status", "--porcelain", "--", ".spec-coding/prompts") ->
                    Result.success("M .spec-coding/prompts/catalog.yaml")
                args == listOf("rev-parse", "HEAD") ->
                    Result.success("abc123")
                else -> Result.success("")
            }
        }

        val service = TeamPromptSyncService(
            project = project,
            settingsProvider = { settings },
            promptManagerProvider = { promptManager },
            gitExecutor = gitExecutor,
        )

        val result = service.pushToTeamRepo()

        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertFalse(payload.noChanges)
        assertEquals("abc123", payload.commitId)
        assertTrue(commands.any { it.second == listOf("commit", "-m", "chore(prompt): sync prompts from DemoProject") })
        assertTrue(commands.any { it.second == listOf("push", "origin", "main") })

        val mirrorCatalog = projectRoot
            .resolve(".spec-coding")
            .resolve(".team-sync")
            .resolve("prompt-repo")
            .resolve(".spec-coding")
            .resolve("prompts")
            .resolve("catalog.yaml")
        assertTrue(Files.exists(mirrorCatalog))
        assertTrue(Files.readString(mirrorCatalog).contains("local"))
    }

    @Test
    fun `pushToTeamRepo should skip commit and push when no prompt changes`() {
        val localPromptDir = projectRoot.resolve(".spec-coding").resolve("prompts")
        Files.createDirectories(localPromptDir)
        Files.writeString(localPromptDir.resolve("catalog.yaml"), "templates:\n  - id: local\n")

        val commands = mutableListOf<Pair<Path?, List<String>>>()
        val gitExecutor = GitCommandExecutor { _, args ->
            commands += null to args
            when {
                args.firstOrNull() == "clone" -> {
                    val mirror = Paths.get(args.last())
                    Files.createDirectories(mirror.resolve(".git"))
                    Result.success("cloned")
                }
                args == listOf("status", "--porcelain", "--", ".spec-coding/prompts") ->
                    Result.success("")
                else -> Result.success("")
            }
        }

        val service = TeamPromptSyncService(
            project = project,
            settingsProvider = { settings },
            promptManagerProvider = { promptManager },
            gitExecutor = gitExecutor,
        )

        val result = service.pushToTeamRepo()

        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertTrue(payload.noChanges)
        assertEquals(null, payload.commitId)
        assertFalse(commands.any { it.second.firstOrNull() == "commit" })
        assertFalse(commands.any { it.second.firstOrNull() == "push" })
    }

    @Test
    fun `pullFromTeamRepo should fail when repo url missing`() {
        settings.teamPromptRepoUrl = "  "

        var invoked = false
        val gitExecutor = GitCommandExecutor { _, _ ->
            invoked = true
            Result.success("")
        }

        val service = TeamPromptSyncService(
            project = project,
            settingsProvider = { settings },
            promptManagerProvider = { promptManager },
            gitExecutor = gitExecutor,
        )

        val result = service.pullFromTeamRepo()

        assertTrue(result.isFailure)
        assertFalse(invoked)
        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error?.message.orEmpty().contains("repository URL is empty"))
    }
}
