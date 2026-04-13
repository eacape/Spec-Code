package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset
import com.eacape.speccodingplugin.spec.WorkflowSourceImportConstraints
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class SpecWorkflowComposerSourceCoordinatorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `buildPresentation should preserve known selection and append preferred imported sources`() {
        val coordinator = coordinator(RecordingEnvironment())

        val presentation = coordinator.buildPresentation(
            currentStage = StageId.REQUIREMENTS,
            assets = listOf(
                asset("SRC-002", "existing.md"),
                asset("SRC-001", "new.md"),
            ),
            preserveSelection = true,
            existingSelection = linkedSetOf("SRC-002", "SRC-missing"),
            preferredSourceIds = linkedSetOf("SRC-001"),
        )

        assertEquals(listOf("SRC-001", "SRC-002"), presentation.assets.map(WorkflowSourceAsset::sourceId))
        assertEquals(linkedSetOf("SRC-002", "SRC-001"), presentation.selectedSourceIds)
        assertTrue(presentation.editable)
    }

    @Test
    fun `removeSource should default to all sources and drop requested source from selection`() {
        val coordinator = coordinator(RecordingEnvironment())

        val presentation = coordinator.removeSource(
            currentStage = StageId.DESIGN,
            sourceId = "SRC-001",
            currentAssets = listOf(
                asset("SRC-002", "second.md"),
                asset("SRC-001", "first.md"),
            ),
            existingSelection = null,
        )

        assertNotNull(presentation)
        assertEquals(listOf("SRC-001", "SRC-002"), presentation!!.assets.map(WorkflowSourceAsset::sourceId))
        assertEquals(linkedSetOf("SRC-002"), presentation.selectedSourceIds)
        assertTrue(presentation.editable)
    }

    @Test
    fun `resolveSourceUsage should fall back to all known sources when selection is empty or stale`() {
        val coordinator = coordinator(RecordingEnvironment())

        val usage = coordinator.resolveSourceUsage(
            currentAssets = listOf(
                asset("SRC-003", "third.md"),
                asset("SRC-001", "first.md"),
            ),
            existingSelection = linkedSetOf("SRC-missing"),
        )

        assertEquals(listOf("SRC-001", "SRC-003"), usage.selectedSourceIds)
    }

    @Test
    fun `importSources should reject invalid files without scheduling background work`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(
            recorder,
            WorkflowSourceImportConstraints(maxFileSizeBytes = 8L),
        )
        val unsupportedPath = writeFile("incoming/archive.zip", "zip".toByteArray(StandardCharsets.UTF_8))
        val oversizedPath = writeFile("incoming/notes.txt", ByteArray(12) { 1 })
        var rejected: SpecWorkflowComposerSourceRejectedImport? = null
        var imported: SpecWorkflowComposerSourceImportSuccess? = null
        var failureStatus: String? = null

        coordinator.importSources(
            request = SpecWorkflowComposerSourceImportRequest(
                workflowId = "wf-1",
                currentStage = StageId.REQUIREMENTS,
                selectedPaths = listOf(unsupportedPath, oversizedPath),
                currentAssets = emptyList(),
                existingSelection = null,
            ),
            isWorkflowStillSelected = { true },
            onRejected = { rejected = it },
            onImported = { imported = it },
            onFailure = { failureStatus = it },
        )

        assertEquals(0, recorder.backgroundRuns)
        assertNull(imported)
        assertNull(failureStatus)
        assertEquals(
            SpecCodingBundle.message("spec.detail.sources.status.rejected", 2),
            rejected?.statusText,
        )
        assertTrue(rejected?.validationDialog?.message?.contains("archive.zip") == true)
        assertTrue(rejected?.validationDialog?.message?.contains("notes.txt") == true)
    }

    @Test
    fun `importSources should merge imported assets and keep partial validation feedback`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)
        val validPath = writeFile("incoming/client-prd.md", "# Client PRD".toByteArray(StandardCharsets.UTF_8))
        val invalidPath = writeFile("incoming/archive.zip", "zip".toByteArray(StandardCharsets.UTF_8))
        recorder.importResults[validPath.toAbsolutePath().normalize()] = Result.success(
            asset("SRC-001", "client-prd.md"),
        )
        var imported: SpecWorkflowComposerSourceImportSuccess? = null

        coordinator.importSources(
            request = SpecWorkflowComposerSourceImportRequest(
                workflowId = "wf-2",
                currentStage = StageId.TASKS,
                selectedPaths = listOf(validPath, invalidPath),
                currentAssets = listOf(asset("SRC-010", "existing.md")),
                existingSelection = linkedSetOf("SRC-010"),
            ),
            isWorkflowStillSelected = { true },
            onRejected = {
                throw AssertionError("expected imported outcome")
            },
            onImported = { imported = it },
            onFailure = { status ->
                throw AssertionError("unexpected failure: $status")
            },
        )

        assertEquals(1, recorder.backgroundRuns)
        assertEquals(
            SpecCodingBundle.message("spec.detail.sources.importing"),
            recorder.lastBackgroundTitle,
        )
        assertEquals(
            listOf(
                ImportCall(
                    workflowId = "wf-2",
                    importedFromStage = StageId.TASKS,
                    importedFromEntry = "SPEC_COMPOSER",
                    sourcePath = validPath.toAbsolutePath().normalize(),
                ),
            ),
            recorder.importCalls,
        )
        assertEquals(listOf("SRC-001", "SRC-010"), imported?.presentation?.assets?.map(WorkflowSourceAsset::sourceId))
        assertEquals(linkedSetOf("SRC-010", "SRC-001"), imported?.presentation?.selectedSourceIds)
        assertEquals(
            SpecCodingBundle.message("spec.detail.sources.status.importedPartial", 1, 1),
            imported?.statusText,
        )
        assertTrue(imported?.validationDialog?.message?.contains("archive.zip") == true)
    }

    @Test
    fun `importSources should surface workflow error when background import fails`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)
        val validPath = writeFile("incoming/client-prd.md", "# Client PRD".toByteArray(StandardCharsets.UTF_8))
        recorder.importResults[validPath.toAbsolutePath().normalize()] = Result.failure(IllegalStateException("boom"))
        var failureStatus: String? = null

        coordinator.importSources(
            request = SpecWorkflowComposerSourceImportRequest(
                workflowId = "wf-3",
                currentStage = StageId.REQUIREMENTS,
                selectedPaths = listOf(validPath),
                currentAssets = emptyList(),
                existingSelection = null,
            ),
            isWorkflowStillSelected = { true },
            onRejected = {
                throw AssertionError("expected failure status")
            },
            onImported = {
                throw AssertionError("expected failure status")
            },
            onFailure = { failureStatus = it },
        )

        assertEquals(1, recorder.backgroundRuns)
        assertEquals(
            SpecCodingBundle.message("spec.workflow.error", "boom"),
            failureStatus,
        )
    }

    private fun coordinator(
        recorder: RecordingEnvironment,
        constraints: WorkflowSourceImportConstraints = WorkflowSourceImportConstraints(),
    ): SpecWorkflowComposerSourceCoordinator {
        return SpecWorkflowComposerSourceCoordinator(
            sourceImportConstraints = constraints,
            runBackground = { request ->
                recorder.backgroundRuns += 1
                recorder.lastBackgroundTitle = request.title
                runCatching {
                    request.task()
                }.onSuccess { importedAssets ->
                    request.onSuccess(importedAssets)
                }.onFailure { error ->
                    request.onFailure(error)
                }
            },
            importWorkflowSource = { workflowId, importedFromStage, importedFromEntry, sourcePath ->
                val normalizedPath = sourcePath.toAbsolutePath().normalize()
                recorder.importCalls += ImportCall(
                    workflowId = workflowId,
                    importedFromStage = importedFromStage,
                    importedFromEntry = importedFromEntry,
                    sourcePath = normalizedPath,
                )
                recorder.importResults[normalizedPath]
                    ?: Result.failure(IllegalStateException("missing stub"))
            },
            renderFailureMessage = { error ->
                error.message ?: "unknown"
            },
        )
    }

    private fun asset(sourceId: String, fileName: String): WorkflowSourceAsset {
        return WorkflowSourceAsset(
            sourceId = sourceId,
            originalFileName = fileName,
            storedRelativePath = "workflow-sources/$fileName",
            mediaType = "text/markdown",
            fileSize = 64L,
            contentHash = "hash-$sourceId",
            importedAt = "2026-04-13T00:00:00Z",
            importedFromStage = StageId.REQUIREMENTS,
            importedFromEntry = "SPEC_COMPOSER",
        )
    }

    private fun writeFile(relativePath: String, content: ByteArray): Path {
        val path = tempDir.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.write(path, content)
        return path
    }

    private class RecordingEnvironment {
        var backgroundRuns: Int = 0
        var lastBackgroundTitle: String? = null
        val importCalls = mutableListOf<ImportCall>()
        val importResults = linkedMapOf<Path, Result<WorkflowSourceAsset>>()
    }

    private data class ImportCall(
        val workflowId: String,
        val importedFromStage: StageId,
        val importedFromEntry: String,
        val sourcePath: Path,
    )
}
