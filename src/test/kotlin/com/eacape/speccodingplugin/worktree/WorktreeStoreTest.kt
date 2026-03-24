package com.eacape.speccodingplugin.worktree

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorktreeStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var repoPath: Path

    @BeforeEach
    fun setUp() {
        repoPath = tempDir.resolve("repo")
        Files.createDirectories(repoPath)

        project = mockk(relaxed = true)
        every { project.basePath } returns repoPath.toString()
    }

    @Test
    fun `save then load should persist state to disk`() {
        val store = WorktreeStore(project)
        val state = WorktreeState(
            activeWorktreeId = "wt-1",
            bindings = listOf(
                WorktreeBinding(
                    id = "wt-1",
                    specTaskId = "SPEC-1",
                    branchName = "spec/spec-1-auth",
                    worktreePath = repoPath.resolve("../spec-spec-1-auth").normalize().toString(),
                    baseBranch = "main",
                    status = WorktreeStatus.ACTIVE,
                    createdAt = 1000L,
                    updatedAt = 2000L,
                )
            ),
        )

        store.save(state)

        val filePath = storagePath()
        assertTrue(Files.exists(filePath))

        val reloaded = WorktreeStore(project).load()
        assertEquals("wt-1", reloaded.activeWorktreeId)
        assertEquals(1, reloaded.bindings.size)
        assertEquals("SPEC-1", reloaded.bindings.first().specTaskId)
        assertEquals(WorktreeStatus.ACTIVE, reloaded.bindings.first().status)
    }

    @Test
    fun `load should fallback to empty state when file is corrupted`() {
        val filePath = storagePath()
        Files.createDirectories(filePath.parent)
        Files.writeString(filePath, "{ invalid json")

        val loaded = WorktreeStore(project).load()

        assertNull(loaded.activeWorktreeId)
        assertTrue(loaded.bindings.isEmpty())
    }

    @Test
    fun `load should fallback unknown status to active`() {
        val filePath = storagePath()
        Files.createDirectories(filePath.parent)
        Files.writeString(
            filePath,
            """
            {
              "version": 1,
              "activeWorktreeId": "wt-1",
              "bindings": [
                {
                  "id": "wt-1",
                  "specTaskId": "SPEC-1",
                  "branchName": "spec/spec-1-auth",
                  "worktreePath": "D:/tmp/spec-spec-1-auth",
                  "baseBranch": "main",
                  "status": "UNKNOWN_STATUS",
                  "createdAt": 1,
                  "updatedAt": 2,
                  "lastError": null
                }
              ]
            }
            """.trimIndent(),
        )

        val loaded = WorktreeStore(project).load()

        assertEquals("wt-1", loaded.activeWorktreeId)
        assertEquals(1, loaded.bindings.size)
        assertEquals(WorktreeStatus.ACTIVE, loaded.bindings.first().status)
    }

    @Test
    fun `save should keep in-memory state when base path is unavailable`() {
        val noBasePathProject = mockk<Project>(relaxed = true)
        every { noBasePathProject.basePath } returns null

        val store = WorktreeStore(noBasePathProject)
        val state = WorktreeState(
            activeWorktreeId = "wt-2",
            bindings = listOf(
                WorktreeBinding(
                    id = "wt-2",
                    specTaskId = "SPEC-2",
                    branchName = "spec/spec-2-x",
                    worktreePath = "D:/tmp/spec-spec-2-x",
                    baseBranch = "main",
                    status = WorktreeStatus.MERGED,
                    createdAt = 10L,
                    updatedAt = 20L,
                )
            ),
        )

        store.save(state)
        val loaded = store.load()
        assertEquals("wt-2", loaded.activeWorktreeId)
        assertEquals(1, loaded.bindings.size)

        val freshStore = WorktreeStore(noBasePathProject)
        val freshLoaded = freshStore.load()
        assertNull(freshLoaded.activeWorktreeId)
        assertTrue(freshLoaded.bindings.isEmpty())
    }

    private fun storagePath(): Path = repoPath.resolve(".spec-coding").resolve("worktrees.json")
}
