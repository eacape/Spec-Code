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
import java.nio.file.attribute.FileTime

class SpecWorkspaceRecoveryServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var workspaceInitializer: SpecWorkspaceInitializer
    private lateinit var storage: SpecStorage

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        workspaceInitializer = SpecWorkspaceInitializer(project)
        storage = SpecStorage.getInstance(project)
    }

    @Test
    fun `runStartupRecovery should not create workspace when spec directory is absent`() {
        val service = SpecWorkspaceRecoveryService(
            project = project,
            storage = storage,
            workspaceInitializer = workspaceInitializer,
            lockManager = SpecFileLockManager(workspaceInitializer),
            nowProvider = { 100_000L },
        )

        val report = service.runStartupRecovery()

        assertFalse(report.hasFindings)
        assertFalse(Files.exists(tempDir.resolve(".spec-coding")))
    }

    @Test
    fun `runStartupRecovery should clean orphan temp files and report stale locks plus snapshot issues`() {
        workspaceInitializer.initializeProjectWorkspace()
        val workflowId = "wf-recovery"
        storage.saveWorkflow(workflow(workflowId)).getOrThrow()
        storage.saveDocument(
            workflowId = workflowId,
            document = document(
                phase = SpecPhase.SPECIFY,
                content = "recovery-snapshot",
            ),
        ).getOrThrow()

        val snapshot = storage.listWorkflowSnapshots(workflowId)
            .first { it.trigger == SpecSnapshotTrigger.DOCUMENT_SAVE_AFTER }
        val snapshotArtifact = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflowId)
            .resolve(".history")
            .resolve("snapshots")
            .resolve(snapshot.snapshotId)
            .resolve("requirements.md")
        Files.delete(snapshotArtifact)

        val orphanTemp = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflowId)
            .resolve(".requirements.md.crash.tmp")
        Files.writeString(orphanTemp, "orphan")
        Files.setLastModifiedTime(orphanTemp, FileTime.fromMillis(0))

        val staleLockPath = tempDir
            .resolve(".spec-coding")
            .resolve(".locks")
            .resolve("workflow-wf-recovery.lock")
        Files.writeString(
            staleLockPath,
            """
            ownerToken=stale-owner
            resourceKey=workflow-wf-recovery
            createdAtEpochMs=1
            """.trimIndent() + "\n",
        )

        val lockManager = SpecFileLockManager(
            workspaceInitializer = workspaceInitializer,
            policy = SpecFileLockPolicy(
                acquireTimeoutMs = 200,
                staleLockAgeMs = 1_000,
                retryIntervalMs = 10,
            ),
            nowProvider = { 50_000L },
        )
        val service = SpecWorkspaceRecoveryService(
            project = project,
            storage = storage,
            workspaceInitializer = workspaceInitializer,
            lockManager = lockManager,
            nowProvider = { 50_000L },
            orphanTempAgeMs = 5_000L,
        )

        val report = service.runStartupRecovery()

        assertEquals(listOf(orphanTemp), report.cleanedTempFiles)
        assertFalse(Files.exists(orphanTemp))
        assertEquals(listOf(staleLockPath), report.staleLocks.map { it.lockPath })
        assertEquals(1, report.snapshotIssues.size)
        assertEquals(SpecSnapshotConsistencyIssueKind.MISSING_ARTIFACT, report.snapshotIssues.single().kind)
        assertEquals("requirements.md", report.snapshotIssues.single().artifactFileName)

        val recoveredLocks = service.recoverStaleLocks()
        assertEquals(listOf(staleLockPath), recoveredLocks)
        assertFalse(Files.exists(staleLockPath))
        assertTrue(report.requiresAttention)
    }

    private fun workflow(id: String): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.SPECIFY,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "workflow-$id",
            description = "recovery",
        )
    }

    private fun document(
        phase: SpecPhase,
        content: String,
    ): SpecDocument {
        return SpecDocument(
            id = "doc-${phase.name.lowercase()}",
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = phase.displayName,
                description = "recovery",
            ),
            validationResult = ValidationResult(valid = true),
        )
    }
}
