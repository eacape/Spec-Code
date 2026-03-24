package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SpecStorageSnapshotTest {

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
    fun `saveWorkflow and saveDocument should create before and after workflow snapshots`() {
        val workflowId = "wf-snapshot"
        storage.saveWorkflow(workflow(workflowId)).getOrThrow()

        storage.saveDocument(
            workflowId = workflowId,
            document = document(
                phase = SpecPhase.SPECIFY,
                content = "snapshot-content-v1",
            ),
        ).getOrThrow()

        val snapshots = storage.listWorkflowSnapshots(workflowId)
        assertTrue(snapshots.any { it.trigger == SpecSnapshotTrigger.WORKFLOW_SAVE_BEFORE })
        assertTrue(snapshots.any { it.trigger == SpecSnapshotTrigger.WORKFLOW_SAVE_AFTER })
        assertTrue(snapshots.any { it.trigger == SpecSnapshotTrigger.DOCUMENT_SAVE_BEFORE })
        assertTrue(snapshots.any { it.trigger == SpecSnapshotTrigger.DOCUMENT_SAVE_AFTER })

        val afterDocumentSnapshot = snapshots.first {
            it.trigger == SpecSnapshotTrigger.DOCUMENT_SAVE_AFTER && it.phase == SpecPhase.SPECIFY
        }
        assertNotNull(afterDocumentSnapshot.operationId)
        assertTrue(afterDocumentSnapshot.files.contains("requirements.md"))

        val loaded = storage.loadWorkflowSnapshot(
            workflowId = workflowId,
            snapshotId = afterDocumentSnapshot.snapshotId,
        ).getOrThrow()
        val loadedSpecify = loaded.getDocument(SpecPhase.SPECIFY)
        assertNotNull(loadedSpecify)
        assertTrue(loadedSpecify!!.content.contains("snapshot-content-v1"))
    }

    @Test
    fun `pinned baseline should reference snapshot and support delta comparison`() {
        val workflowId = "wf-baseline-ref"
        storage.saveWorkflow(workflow(workflowId)).getOrThrow()
        storage.saveDocument(
            workflowId = workflowId,
            document = document(
                phase = SpecPhase.SPECIFY,
                content = "baseline-v1",
            ),
        ).getOrThrow()

        val baselineSnapshot = storage.listWorkflowSnapshots(workflowId)
            .first { it.trigger == SpecSnapshotTrigger.DOCUMENT_SAVE_AFTER }
        val baselineRef = storage.pinDeltaBaseline(
            workflowId = workflowId,
            snapshotId = baselineSnapshot.snapshotId,
            label = "before-edit",
        ).getOrThrow()

        storage.saveDocument(
            workflowId = workflowId,
            document = document(
                phase = SpecPhase.SPECIFY,
                content = "baseline-v2",
            ),
        ).getOrThrow()

        val listedBaselines = storage.listDeltaBaselines(workflowId)
        assertTrue(listedBaselines.any { it.baselineId == baselineRef.baselineId })

        val baselineWorkflow = storage.loadDeltaBaselineWorkflow(workflowId, baselineRef.baselineId).getOrThrow()
        val currentWorkflow = storage.loadWorkflow(workflowId).getOrThrow()
        val delta = SpecDeltaCalculator.compareWorkflows(
            baselineWorkflow = baselineWorkflow,
            targetWorkflow = currentWorkflow,
        )

        assertEquals(
            SpecDeltaStatus.MODIFIED,
            delta.phaseDeltas.first { it.phase == SpecPhase.SPECIFY }.status,
        )
        assertEquals(
            "${workflowId}@snapshot:${baselineSnapshot.snapshotId}",
            delta.baselineWorkflowId,
        )
    }

    @Test
    fun `workflow snapshots should capture verification artifact and allow baseline reads`() {
        val workflowId = "wf-verify-snapshot"
        val artifactService = SpecArtifactService(project)

        storage.saveWorkflow(workflow(workflowId)).getOrThrow()
        artifactService.writeArtifact(
            workflowId = workflowId,
            stageId = StageId.VERIFY,
            content = """
                # Verification Document

                ## Result
                conclusion: PASS
                runId: verify-run-1
                at: 2026-03-11T09:00:00Z
                summary: captured
            """.trimIndent(),
        )
        storage.saveWorkflow(
            storage.loadWorkflow(workflowId).getOrThrow().copy(updatedAt = 10L),
        ).getOrThrow()

        val snapshot = storage.listWorkflowSnapshots(workflowId)
            .first { it.trigger == SpecSnapshotTrigger.WORKFLOW_SAVE_AFTER && it.files.contains("verification.md") }
        assertTrue(snapshot.files.contains("verification.md"))

        val snapshotContent = storage.loadWorkflowSnapshotArtifact(
            workflowId = workflowId,
            snapshotId = snapshot.snapshotId,
            stageId = StageId.VERIFY,
        ).getOrThrow()
        assertNotNull(snapshotContent)
        assertTrue(snapshotContent!!.contains("runId: verify-run-1"))

        val baseline = storage.pinDeltaBaseline(
            workflowId = workflowId,
            snapshotId = snapshot.snapshotId,
            label = "with-verify",
        ).getOrThrow()
        val baselineContent = storage.loadDeltaBaselineArtifact(
            workflowId = workflowId,
            baselineId = baseline.baselineId,
            stageId = StageId.VERIFY,
        ).getOrThrow()
        assertEquals(snapshotContent, baselineContent)
    }

    @Test
    fun `checkWorkflowSnapshotConsistency should report missing referenced artifact`() {
        val workflowId = "wf-snapshot-consistency"
        storage.saveWorkflow(workflow(workflowId)).getOrThrow()
        storage.saveDocument(
            workflowId = workflowId,
            document = document(
                phase = SpecPhase.SPECIFY,
                content = "snapshot-consistency",
            ),
        ).getOrThrow()

        val snapshot = storage.listWorkflowSnapshots(workflowId)
            .first { it.trigger == SpecSnapshotTrigger.DOCUMENT_SAVE_AFTER }
        val artifactPath = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflowId)
            .resolve(".history")
            .resolve("snapshots")
            .resolve(snapshot.snapshotId)
            .resolve("requirements.md")
        java.nio.file.Files.delete(artifactPath)

        val issues = storage.checkWorkflowSnapshotConsistency(workflowId)
        assertEquals(1, issues.size)
        assertEquals(SpecSnapshotConsistencyIssueKind.MISSING_ARTIFACT, issues.single().kind)
        assertEquals("requirements.md", issues.single().artifactFileName)
    }

    private fun workflow(id: String): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.SPECIFY,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "workflow-$id",
            description = "snapshot-test",
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
                description = "snapshot",
            ),
            validationResult = ValidationResult(valid = true),
        )
    }
}
