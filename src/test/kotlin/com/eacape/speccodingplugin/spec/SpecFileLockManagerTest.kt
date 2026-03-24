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

class SpecFileLockManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var initializer: SpecWorkspaceInitializer

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        initializer = SpecWorkspaceInitializer(project)
    }

    @Test
    fun `withWorkflowLock should create and release lock file`() {
        val manager = SpecFileLockManager(initializer)
        val lockPath = initializer
            .initializeProjectWorkspace()
            .locksDir
            .resolve("workflow-wf-lock.lock")

        assertFalse(Files.exists(lockPath))
        manager.withWorkflowLock("wf-lock") {
            assertTrue(Files.exists(lockPath))
            val content = Files.readString(lockPath)
            assertTrue(content.contains("resourceKey=workflow-wf-lock"))
        }
        assertFalse(Files.exists(lockPath))
    }

    @Test
    fun `withWorkflowLock should timeout when active lock exists`() {
        val lockPath = initializer
            .initializeProjectWorkspace()
            .locksDir
            .resolve("workflow-wf-timeout.lock")
        Files.writeString(
            lockPath,
            """
            ownerToken=other-owner
            resourceKey=workflow-wf-timeout
            createdAtEpochMs=${System.currentTimeMillis()}
            """.trimIndent() + "\n",
        )

        val manager = SpecFileLockManager(
            workspaceInitializer = initializer,
            policy = SpecFileLockPolicy(
                acquireTimeoutMs = 80,
                staleLockAgeMs = 60_000,
                retryIntervalMs = 10,
            ),
        )

        val result = runCatching {
            manager.withWorkflowLock("wf-timeout") { }
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SpecFileLockTimeoutException)
        assertTrue(Files.exists(lockPath))
    }

    @Test
    fun `withWorkflowLock should evict stale lock and acquire new one`() {
        val lockPath = initializer
            .initializeProjectWorkspace()
            .locksDir
            .resolve("workflow-wf-stale.lock")
        Files.writeString(
            lockPath,
            """
            ownerToken=stale-owner
            resourceKey=workflow-wf-stale
            createdAtEpochMs=${System.currentTimeMillis() - 100_000}
            """.trimIndent() + "\n",
        )

        val manager = SpecFileLockManager(
            workspaceInitializer = initializer,
            policy = SpecFileLockPolicy(
                acquireTimeoutMs = 200,
                staleLockAgeMs = 1_000,
                retryIntervalMs = 10,
            ),
        )

        manager.withWorkflowLock("wf-stale") {
            val lockContent = Files.readString(lockPath)
            assertTrue(lockContent.contains("resourceKey=workflow-wf-stale"))
            assertFalse(lockContent.contains("ownerToken=stale-owner"))
        }
        assertFalse(Files.exists(lockPath))
    }

    @Test
    fun `inspectLocks and recoverStaleLocks should report and delete stale lock files only`() {
        val locksDir = initializer.initializeProjectWorkspace().locksDir
        val staleLockPath = locksDir.resolve("workflow-wf-stale.lock")
        val activeLockPath = locksDir.resolve("workflow-wf-active.lock")
        Files.writeString(
            staleLockPath,
            """
            ownerToken=stale-owner
            resourceKey=workflow-wf-stale
            createdAtEpochMs=1
            """.trimIndent() + "\n",
        )
        Files.writeString(
            activeLockPath,
            """
            ownerToken=active-owner
            resourceKey=workflow-wf-active
            createdAtEpochMs=4950
            """.trimIndent() + "\n",
        )

        val manager = SpecFileLockManager(
            workspaceInitializer = initializer,
            policy = SpecFileLockPolicy(
                acquireTimeoutMs = 200,
                staleLockAgeMs = 1_000,
                retryIntervalMs = 10,
            ),
            nowProvider = { 5_000L },
        )

        val inspections = manager.inspectLocks().associateBy { it.resourceKey }
        assertEquals(true, inspections.getValue("workflow-wf-stale").stale)
        assertEquals(false, inspections.getValue("workflow-wf-active").stale)

        val recovered = manager.recoverStaleLocks()
        assertEquals(listOf(staleLockPath), recovered)
        assertFalse(Files.exists(staleLockPath))
        assertTrue(Files.exists(activeLockPath))
    }
}
