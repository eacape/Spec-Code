package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SpecStorageLockingTest {

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
    fun `saveWorkflow should reject concurrent write when timeout is reached`() {
        val lockManager = SpecFileLockManager(
            workspaceInitializer = initializer,
            policy = SpecFileLockPolicy(
                acquireTimeoutMs = 80,
                staleLockAgeMs = 30_000,
                retryIntervalMs = 10,
            ),
        )
        val writerStarted = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)

        val slowAtomicFileIO = AtomicFileIO(
            moveOperation = { source, target ->
                writerStarted.countDown()
                releaseWriter.await(2, TimeUnit.SECONDS)
                try {
                    Files.move(
                        source,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        source,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            },
        )

        val storage = SpecStorage(
            project = project,
            atomicFileIO = slowAtomicFileIO,
            workspaceInitializer = initializer,
            lockManager = lockManager,
        )
        val workflow = SpecWorkflow(
            id = "wf-lock-timeout",
            currentPhase = SpecPhase.SPECIFY,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Lock Test",
            description = "concurrent lock timeout",
        )

        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstSave = executor.submit<Result<Path>> {
                storage.saveWorkflow(workflow)
            }
            assertTrue(writerStarted.await(2, TimeUnit.SECONDS))

            val secondSave = executor.submit<Result<Path>> {
                storage.saveWorkflow(workflow)
            }
            val secondResult = secondSave.get(2, TimeUnit.SECONDS)
            assertTrue(secondResult.isFailure)
            assertTrue(secondResult.exceptionOrNull() is SpecFileLockTimeoutException)

            releaseWriter.countDown()
            val firstResult = firstSave.get(2, TimeUnit.SECONDS)
            assertTrue(firstResult.isSuccess)
        } finally {
            releaseWriter.countDown()
            executor.shutdownNow()
        }
    }
}
