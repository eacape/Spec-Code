package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset
import com.eacape.speccodingplugin.spec.WorkflowSourceImportConstraints
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class SpecWorkflowComposerSourceUiHostTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `requestAdd should skip chooser when current stage is not editable`() {
        val recorder = RecordingEnvironment().apply {
            currentWorkflow = workflow(id = "wf-readonly", stageId = StageId.VERIFY)
            selectedWorkflowId = "wf-readonly"
            selectedPaths = listOf(writeFile("incoming/client-prd.md", "# Client PRD".toByteArray(StandardCharsets.UTF_8)))
        }

        host(recorder).requestAdd()

        assertEquals(0, recorder.chooserCalls)
        assertTrue(recorder.importCalls.isEmpty())
        assertTrue(recorder.statusTexts.isEmpty())
    }

    @Test
    fun `requestAdd should show rejection dialog and status when all selected files are invalid`() {
        val recorder = RecordingEnvironment().apply {
            currentWorkflow = workflow(id = "wf-rejected")
            selectedWorkflowId = "wf-rejected"
            selectedPaths = listOf(
                writeFile("incoming/archive.zip", "zip".toByteArray(StandardCharsets.UTF_8)),
                writeFile("incoming/notes.txt", ByteArray(12) { 1 }),
            )
            sourceImportConstraints = WorkflowSourceImportConstraints(maxFileSizeBytes = 8L)
        }

        host(recorder).requestAdd()

        assertEquals(1, recorder.chooserCalls)
        assertEquals(
            listOf(SpecCodingBundle.message("spec.detail.sources.status.rejected", 2)),
            recorder.statusTexts,
        )
        assertEquals(1, recorder.validationDialogs.size)
        assertTrue(recorder.validationDialogs.single().message.contains("archive.zip"))
        assertTrue(recorder.validationDialogs.single().message.contains("notes.txt"))
        assertTrue(recorder.appliedPresentations.isEmpty())
    }

    @Test
    fun `requestAdd should apply imported presentation and keep partial validation feedback`() {
        val recorder = RecordingEnvironment().apply {
            currentWorkflow = workflow(id = "wf-imported", stageId = StageId.TASKS)
            selectedWorkflowId = "wf-imported"
            currentWorkflowSources = listOf(asset("SRC-010", "existing.md"))
            selectedSourceIdsByWorkflowId["wf-imported"] = linkedSetOf("SRC-010")
        }
        val validPath = writeFile("incoming/client-prd.md", "# Client PRD".toByteArray(StandardCharsets.UTF_8))
        val invalidPath = writeFile("incoming/archive.zip", "zip".toByteArray(StandardCharsets.UTF_8))
        recorder.selectedPaths = listOf(validPath, invalidPath)
        recorder.importResults[validPath.toAbsolutePath().normalize()] = Result.success(
            asset("SRC-001", "client-prd.md"),
        )

        host(recorder).requestAdd()

        assertEquals(1, recorder.chooserCalls)
        assertEquals(
            listOf(
                ImportCall(
                    workflowId = "wf-imported",
                    importedFromStage = StageId.TASKS,
                    importedFromEntry = "SPEC_COMPOSER",
                    sourcePath = validPath.toAbsolutePath().normalize(),
                ),
            ),
            recorder.importCalls,
        )
        assertEquals(1, recorder.appliedPresentations.size)
        assertEquals(
            listOf("SRC-001", "SRC-010"),
            recorder.appliedPresentations.single().presentation.assets.map(WorkflowSourceAsset::sourceId),
        )
        assertEquals(
            linkedSetOf("SRC-010", "SRC-001"),
            recorder.appliedPresentations.single().presentation.selectedSourceIds,
        )
        assertEquals(
            listOf(SpecCodingBundle.message("spec.detail.sources.status.importedPartial", 1, 1)),
            recorder.statusTexts,
        )
        assertEquals(1, recorder.validationDialogs.size)
        assertTrue(recorder.validationDialogs.single().message.contains("archive.zip"))
    }

    @Test
    fun `requestAdd should surface workflow error when import fails`() {
        val recorder = RecordingEnvironment().apply {
            currentWorkflow = workflow(id = "wf-failed")
            selectedWorkflowId = "wf-failed"
        }
        val validPath = writeFile("incoming/client-prd.md", "# Client PRD".toByteArray(StandardCharsets.UTF_8))
        recorder.selectedPaths = listOf(validPath)
        recorder.importResults[validPath.toAbsolutePath().normalize()] = Result.failure(IllegalStateException("boom"))

        host(recorder).requestAdd()

        assertEquals(
            listOf(SpecCodingBundle.message("spec.workflow.error", "boom")),
            recorder.statusTexts,
        )
        assertTrue(recorder.validationDialogs.isEmpty())
        assertTrue(recorder.appliedPresentations.isEmpty())
    }

    @Test
    fun `requestRemove should apply updated selection and report status`() {
        val recorder = RecordingEnvironment().apply {
            currentWorkflow = workflow(id = "wf-remove", stageId = StageId.DESIGN)
            currentWorkflowSources = listOf(
                asset("SRC-002", "second.md"),
                asset("SRC-001", "first.md"),
            )
            selectedSourceIdsByWorkflowId["wf-remove"] = linkedSetOf("SRC-001", "SRC-002")
        }

        host(recorder).requestRemove("SRC-001")

        assertEquals(1, recorder.appliedPresentations.size)
        assertEquals(
            linkedSetOf("SRC-002"),
            recorder.appliedPresentations.single().presentation.selectedSourceIds,
        )
        assertEquals(
            listOf(SpecCodingBundle.message("spec.detail.sources.status.removed", "SRC-001")),
            recorder.statusTexts,
        )
    }

    @Test
    fun `requestRestore should restore default selection and report source count`() {
        val recorder = RecordingEnvironment().apply {
            currentWorkflow = workflow(id = "wf-restore")
            currentWorkflowSources = listOf(
                asset("SRC-002", "second.md"),
                asset("SRC-001", "first.md"),
            )
            selectedSourceIdsByWorkflowId["wf-restore"] = linkedSetOf("SRC-002")
        }

        host(recorder).requestRestore()

        assertEquals(1, recorder.appliedPresentations.size)
        assertEquals(
            linkedSetOf("SRC-001", "SRC-002"),
            recorder.appliedPresentations.single().presentation.selectedSourceIds,
        )
        assertEquals(
            listOf(SpecCodingBundle.message("spec.detail.sources.status.restored", 2)),
            recorder.statusTexts,
        )
    }

    private fun host(recorder: RecordingEnvironment): SpecWorkflowComposerSourceUiHost {
        return SpecWorkflowComposerSourceUiHost(
            currentWorkflow = { recorder.currentWorkflow },
            currentWorkflowSources = { recorder.currentWorkflowSources },
            selectedSourceIds = { workflowId -> recorder.selectedSourceIdsByWorkflowId[workflowId] },
            chooseSourcePaths = {
                recorder.chooserCalls += 1
                recorder.selectedPaths
            },
            applyWorkflowSourcesPresentation = { workflowId, presentation ->
                recorder.appliedPresentations += AppliedPresentationCall(workflowId, presentation)
                recorder.currentWorkflowSources = presentation.assets
                recorder.selectedSourceIdsByWorkflowId[workflowId] = presentation.selectedSourceIds
            },
            isWorkflowStillSelected = { workflowId ->
                workflowId == recorder.selectedWorkflowId
            },
            showValidationDialogUi = { dialog ->
                recorder.validationDialogs += dialog
            },
            setStatusText = { text ->
                recorder.statusTexts += text
            },
            composerSourceCoordinator = coordinator(recorder),
        )
    }

    private fun coordinator(recorder: RecordingEnvironment): SpecWorkflowComposerSourceCoordinator {
        return SpecWorkflowComposerSourceCoordinator(
            sourceImportConstraints = recorder.sourceImportConstraints,
            runBackground = { request ->
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

    private fun workflow(
        id: String,
        stageId: StageId = StageId.REQUIREMENTS,
    ): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.SPECIFY,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Workflow $id",
            description = "workflow",
            template = WorkflowTemplate.FULL_SPEC,
            currentStage = stageId,
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
            importedAt = "2026-04-15T00:00:00Z",
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

    private data class AppliedPresentationCall(
        val workflowId: String,
        val presentation: SpecWorkflowComposerSourcePresentation,
    )

    private data class ImportCall(
        val workflowId: String,
        val importedFromStage: StageId,
        val importedFromEntry: String,
        val sourcePath: Path,
    )

    private class RecordingEnvironment {
        var currentWorkflow: SpecWorkflow? = null
        var selectedWorkflowId: String? = null
        var sourceImportConstraints: WorkflowSourceImportConstraints = WorkflowSourceImportConstraints()
        var currentWorkflowSources: List<WorkflowSourceAsset> = emptyList()
        var selectedPaths: List<Path> = emptyList()
        var chooserCalls: Int = 0
        val selectedSourceIdsByWorkflowId = mutableMapOf<String, Set<String>?>()
        val appliedPresentations = mutableListOf<AppliedPresentationCall>()
        val validationDialogs = mutableListOf<SpecWorkflowComposerSourceValidationDialog>()
        val statusTexts = mutableListOf<String>()
        val importCalls = mutableListOf<ImportCall>()
        val importResults = linkedMapOf<Path, Result<WorkflowSourceAsset>>()
    }
}
