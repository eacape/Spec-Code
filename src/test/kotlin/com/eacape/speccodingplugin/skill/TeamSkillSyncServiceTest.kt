package com.eacape.speccodingplugin.skill

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

class TeamSkillSyncServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var projectRoot: Path
    private lateinit var project: Project
    private lateinit var settings: SpecCodingSettingsState
    private lateinit var skillRegistry: SkillRegistry

    @BeforeEach
    fun setUp() {
        projectRoot = tempDir.resolve("project")
        Files.createDirectories(projectRoot)

        project = mockk(relaxed = true)
        every { project.basePath } returns projectRoot.toString()
        every { project.name } returns "DemoProject"

        settings = SpecCodingSettingsState().apply {
            teamSkillRepoUrl = "https://example.com/team-skills.git"
            teamSkillRepoBranch = "main"
        }

        skillRegistry = mockk(relaxed = true)
    }

    @Test
    fun `pullFromTeamRepo should copy team skills and reload registry`() {
        val commands = mutableListOf<Pair<Path?, List<String>>>()
        val gitExecutor = GitCommandExecutor { workingDir, args ->
            commands += workingDir to args
            when {
                args.firstOrNull() == "clone" -> {
                    val mirror = Paths.get(args.last())
                    Files.createDirectories(mirror.resolve(".git"))
                    val skillsDir = mirror.resolve(".spec-coding").resolve("skills")
                    Files.createDirectories(skillsDir)
                    Files.writeString(skillsDir.resolve("review.yaml"), "id: cloned-review\n")
                    Result.success("cloned")
                }
                args == listOf("pull", "--ff-only", "origin", "main") -> {
                    val mirror = workingDir ?: error("workingDir is required for pull")
                    val skillsDir = mirror.resolve(".spec-coding").resolve("skills")
                    Files.createDirectories(skillsDir)
                    Files.writeString(skillsDir.resolve("review.yaml"), "id: pulled-review\n")
                    Result.success("pulled")
                }
                else -> Result.success("")
            }
        }

        val service = TeamSkillSyncService(
            project = project,
            settingsProvider = { settings },
            skillRegistryProvider = { skillRegistry },
            gitExecutor = gitExecutor,
        )

        val result = service.pullFromTeamRepo()

        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertEquals("main", payload.branch)
        assertEquals(1, payload.syncedFiles)
        verify(exactly = 1) { skillRegistry.reloadFromDisk() }

        val localSkill = projectRoot.resolve(".spec-coding").resolve("skills").resolve("review.yaml")
        assertTrue(Files.exists(localSkill))
        assertTrue(Files.readString(localSkill).contains("pulled-review"))
        assertTrue(commands.any { it.second == listOf("pull", "--ff-only", "origin", "main") })
    }

    @Test
    fun `pushToTeamRepo should commit and push when skills changed`() {
        val localSkillDir = projectRoot.resolve(".spec-coding").resolve("skills")
        Files.createDirectories(localSkillDir)
        Files.writeString(localSkillDir.resolve("review.yaml"), "id: local-review\n")

        val commands = mutableListOf<Pair<Path?, List<String>>>()
        val gitExecutor = GitCommandExecutor { workingDir, args ->
            commands += workingDir to args
            when {
                args.firstOrNull() == "clone" -> {
                    val mirror = Paths.get(args.last())
                    Files.createDirectories(mirror.resolve(".git"))
                    Result.success("cloned")
                }
                args == listOf("status", "--porcelain", "--", ".spec-coding/skills") ->
                    Result.success("M .spec-coding/skills/review.yaml")
                args == listOf("rev-parse", "HEAD") ->
                    Result.success("def456")
                else -> Result.success("")
            }
        }

        val service = TeamSkillSyncService(
            project = project,
            settingsProvider = { settings },
            skillRegistryProvider = { skillRegistry },
            gitExecutor = gitExecutor,
        )

        val result = service.pushToTeamRepo()

        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertFalse(payload.noChanges)
        assertEquals("def456", payload.commitId)
        assertTrue(commands.any { it.second == listOf("commit", "-m", "chore(skill): sync skills from DemoProject") })
        assertTrue(commands.any { it.second == listOf("push", "origin", "main") })

        val mirrorSkill = projectRoot
            .resolve(".spec-coding")
            .resolve(".team-sync")
            .resolve("skill-repo")
            .resolve(".spec-coding")
            .resolve("skills")
            .resolve("review.yaml")
        assertTrue(Files.exists(mirrorSkill))
        assertTrue(Files.readString(mirrorSkill).contains("local-review"))
    }

    @Test
    fun `pushToTeamRepo should skip commit and push when no skill changes`() {
        val localSkillDir = projectRoot.resolve(".spec-coding").resolve("skills")
        Files.createDirectories(localSkillDir)
        Files.writeString(localSkillDir.resolve("review.yaml"), "id: local-review\n")

        val commands = mutableListOf<Pair<Path?, List<String>>>()
        val gitExecutor = GitCommandExecutor { _, args ->
            commands += null to args
            when {
                args.firstOrNull() == "clone" -> {
                    val mirror = Paths.get(args.last())
                    Files.createDirectories(mirror.resolve(".git"))
                    Result.success("cloned")
                }
                args == listOf("status", "--porcelain", "--", ".spec-coding/skills") ->
                    Result.success("")
                else -> Result.success("")
            }
        }

        val service = TeamSkillSyncService(
            project = project,
            settingsProvider = { settings },
            skillRegistryProvider = { skillRegistry },
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
        settings.teamSkillRepoUrl = " "

        var invoked = false
        val gitExecutor = GitCommandExecutor { _, _ ->
            invoked = true
            Result.success("")
        }

        val service = TeamSkillSyncService(
            project = project,
            settingsProvider = { settings },
            skillRegistryProvider = { skillRegistry },
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
