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
import java.nio.file.attribute.PosixFilePermission

class SpecStorageHistoryTest {

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
    fun `saveDocument should create history snapshot and can load it`() {
        val workflowId = "wf-history"
        val firstDoc = document(phase = SpecPhase.SPECIFY, content = "first")
        val secondDoc = document(phase = SpecPhase.SPECIFY, content = "second")

        storage.saveDocument(workflowId, firstDoc).getOrThrow()
        storage.saveDocument(workflowId, secondDoc).getOrThrow()

        val history = storage.listDocumentHistory(workflowId, SpecPhase.SPECIFY)
        assertTrue(history.isNotEmpty())
        assertTrue(history.size >= 2)
        assertTrue(history.zipWithNext().all { (a, b) -> a.createdAt >= b.createdAt })

        val latestSnapshot = history.first()
        val loaded = storage.loadDocumentSnapshot(
            workflowId = workflowId,
            phase = SpecPhase.SPECIFY,
            snapshotId = latestSnapshot.snapshotId,
        ).getOrThrow()

        assertEquals(SpecPhase.SPECIFY, loaded.phase)
        assertFalse(loaded.content.isBlank())

        val deletedSnapshotId = history.last().snapshotId
        storage.deleteDocumentSnapshot(workflowId, SpecPhase.SPECIFY, deletedSnapshotId).getOrThrow()
        val afterDelete = storage.listDocumentHistory(workflowId, SpecPhase.SPECIFY)
        assertTrue(afterDelete.none { it.snapshotId == deletedSnapshotId })

        val pruned = storage.pruneDocumentHistory(
            workflowId = workflowId,
            phase = SpecPhase.SPECIFY,
            keepLatest = 1,
        ).getOrThrow()
        assertTrue(pruned >= 0)
        val afterPrune = storage.listDocumentHistory(workflowId, SpecPhase.SPECIFY)
        assertTrue(afterPrune.size <= 1)
    }

    @Test
    fun `archiveWorkflow should move completed workflow and append audit log`() {
        val workflowId = "wf-archive"
        val completed = workflow(
            id = workflowId,
            status = WorkflowStatus.COMPLETED,
            phase = SpecPhase.IMPLEMENT,
        )
        storage.saveWorkflow(completed).getOrThrow()
        storage.saveDocument(workflowId, document(phase = SpecPhase.IMPLEMENT, content = "done")).getOrThrow()

        val archived = storage.archiveWorkflow(completed).getOrThrow()

        val activeDir = tempDir.resolve(".spec-coding").resolve("specs").resolve(workflowId)
        assertFalse(Files.exists(activeDir))
        assertTrue(Files.exists(archived.archivePath))
        assertTrue(Files.exists(archived.archivePath.resolve("workflow.yaml")))
        assertTrue(Files.exists(archived.archivePath.resolve(SpecPhase.IMPLEMENT.outputFileName)))
        assertTrue(Files.exists(archived.auditLogPath))
        assertTrue(archived.archivedAt > 0L)
        assertTrue(archived.readOnlySummary.filesMarkedReadOnly > 0)
        assertTrue(archived.readOnlySummary.failures >= 0)
        assertEquals(
            archived.archivePath.resolve(".history").resolve("audit.yaml"),
            archived.auditLogPath,
        )
        assertTrue(storage.listWorkflows().none { it == workflowId })

        val logContent = Files.readString(archived.auditLogPath)
        val events = SpecAuditLogCodec.decodeDocuments(logContent)
        assertTrue(
            events.any { event ->
                event.workflowId == workflowId &&
                    event.eventType == SpecAuditEventType.DOCUMENT_SAVED
            }
        )
        assertTrue(
            events.any { event ->
                event.workflowId == workflowId &&
                    event.eventType == SpecAuditEventType.WORKFLOW_ARCHIVED &&
                    event.details["archiveId"] == archived.archiveId &&
                    event.details["readOnlyPolicy"] == "best-effort-file-attributes" &&
                    event.details["readOnlyFiles"] == archived.readOnlySummary.filesMarkedReadOnly.toString() &&
                    event.details["readOnlyFailures"] == archived.readOnlySummary.failures.toString()
            }
        )
        assertReadOnly(archived.archivePath.resolve("workflow.yaml"))
        assertReadOnly(archived.archivePath.resolve(SpecPhase.IMPLEMENT.outputFileName))
        assertReadOnly(archived.auditLogPath)
    }

    @Test
    fun `archiveWorkflow should reject non completed workflow`() {
        val workflow = workflow(
            id = "wf-active",
            status = WorkflowStatus.IN_PROGRESS,
            phase = SpecPhase.DESIGN,
        )
        storage.saveWorkflow(workflow).getOrThrow()

        val result = storage.archiveWorkflow(workflow)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Only completed workflow can be archived") == true)
    }

    private fun document(phase: SpecPhase, content: String): SpecDocument {
        return SpecDocument(
            id = "doc-${phase.name.lowercase()}",
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = phase.displayName,
                description = "history-test",
            ),
            validationResult = ValidationResult(valid = true),
        )
    }

    private fun workflow(
        id: String,
        status: WorkflowStatus,
        phase: SpecPhase,
    ): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = phase,
            documents = emptyMap(),
            status = status,
            title = "workflow-$id",
            description = "archive-test",
        )
    }

    private fun assertReadOnly(path: Path) {
        val supportedViews = path.fileSystem.supportedFileAttributeViews()
        when {
            supportedViews.contains("dos") -> {
                val readOnly = Files.getAttribute(path, "dos:readonly") as Boolean
                assertTrue(readOnly, "Expected DOS readonly attribute for $path")
            }

            supportedViews.contains("posix") -> {
                val permissions = Files.getPosixFilePermissions(path)
                assertFalse(
                    permissions.contains(PosixFilePermission.OWNER_WRITE),
                    "Expected OWNER_WRITE to be removed for $path",
                )
            }

            else -> {
                assertFalse(path.toFile().canWrite(), "Expected $path to be non-writable")
            }
        }
    }
}
