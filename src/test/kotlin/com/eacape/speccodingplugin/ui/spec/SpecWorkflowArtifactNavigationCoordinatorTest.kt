package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecDocumentHistoryEntry
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SpecWorkflowArtifactNavigationCoordinatorTest {

    @Test
    fun `openPhaseDocument should open resolved workflow phase artifact path`() {
        var openedPath: Path? = null
        val coordinator = coordinator(
            resolvePhaseDocumentPath = { workflowId, phase ->
                Path.of("sandbox", workflowId, phase.outputFileName)
            },
            openFile = { path ->
                openedPath = path
                true
            },
        )

        coordinator.openPhaseDocument(" wf-1 ", SpecPhase.DESIGN)

        assertEquals(Path.of("sandbox", "wf-1", "design.md"), openedPath)
    }

    @Test
    fun `openArtifactInEditor should surface unavailable status when artifact cannot be opened`() {
        val statusMessages = mutableListOf<String>()
        val coordinator = coordinator(
            locateArtifact = { workflowId, fileName ->
                Path.of("sandbox", workflowId, fileName)
            },
            openFile = { false },
            setStatusText = statusMessages::add,
        )

        coordinator.openArtifactInEditor("wf-1", "verify.md")

        assertEquals(
            listOf(SpecCodingBundle.message("spec.action.verify.document.unavailable.title")),
            statusMessages,
        )
    }

    @Test
    fun `showHistoryDiff should surface missing current document`() {
        val statusMessages = mutableListOf<String>()
        val coordinator = coordinator(
            setStatusText = statusMessages::add,
        )

        coordinator.showHistoryDiff(
            workflowId = "wf-1",
            phase = SpecPhase.SPECIFY,
            currentDocument = null,
        )

        assertEquals(
            listOf(SpecCodingBundle.message("spec.history.noCurrentDocument")),
            statusMessages,
        )
    }

    @Test
    fun `showHistoryDiff should open dialog with loaded snapshots and delegate snapshot actions`() {
        var dialogRequest: SpecWorkflowHistoryDiffDialogRequest? = null
        val statusMessages = mutableListOf<String>()
        val deleteCalls = mutableListOf<String>()
        val pruneCalls = mutableListOf<Int>()
        val exportCalls = mutableListOf<String>()
        val phase = SpecPhase.DESIGN
        val coordinator = coordinator(
            listDocumentHistory = { workflowId, requestedPhase ->
                assertEquals("wf-1", workflowId)
                assertEquals(phase, requestedPhase)
                listOf(
                    SpecDocumentHistoryEntry(snapshotId = "1001", phase = phase, createdAt = 1001L),
                    SpecDocumentHistoryEntry(snapshotId = "1002", phase = phase, createdAt = 1002L),
                )
            },
            loadDocumentSnapshot = { _, _, snapshotId ->
                when (snapshotId) {
                    "1001" -> Result.success(document(phase, "snapshot-1001"))
                    else -> Result.failure(IllegalStateException("missing"))
                }
            },
            deleteDocumentSnapshot = { workflowId, requestedPhase, snapshotId ->
                deleteCalls += "$workflowId:${requestedPhase.name}:$snapshotId"
                Result.success(Unit)
            },
            pruneDocumentHistory = { _, _, keepLatest ->
                pruneCalls += keepLatest
                Result.success(keepLatest)
            },
            exportHistoryDiffSummary = { workflowId, requestedPhase, content ->
                exportCalls += "$workflowId:${requestedPhase.name}:$content"
                Result.success("summary.md")
            },
            showHistoryDiffDialog = { request ->
                dialogRequest = request
            },
            setStatusText = statusMessages::add,
        )

        coordinator.showHistoryDiff(
            workflowId = "wf-1",
            phase = phase,
            currentDocument = document(phase, "current"),
        )

        val request = requireNotNull(dialogRequest)
        assertEquals(1, request.snapshots.size)
        assertEquals("1001", request.snapshots.single().snapshotId)
        assertEquals(phase, request.currentDocument.phase)
        assertEquals("current", request.currentDocument.content)
        assertEquals(true, request.onDeleteSnapshot(request.snapshots.single()))
        assertEquals(3, request.onPruneSnapshots(3))
        assertEquals(Result.success("summary.md"), request.onExportSummary("summary"))
        assertEquals(listOf("wf-1:DESIGN:1001"), deleteCalls)
        assertEquals(listOf(3), pruneCalls)
        assertEquals(listOf("wf-1:DESIGN:summary"), exportCalls)
        assertEquals(
            listOf(SpecCodingBundle.message("spec.history.diff.opened", phase.displayName, 1)),
            statusMessages,
        )
    }

    @Test
    fun `showHistoryDiff should surface no snapshot status when no snapshot documents can be loaded`() {
        var dialogRequest: SpecWorkflowHistoryDiffDialogRequest? = null
        val statusMessages = mutableListOf<String>()
        val phase = SpecPhase.IMPLEMENT
        val coordinator = coordinator(
            listDocumentHistory = { _, _ ->
                listOf(SpecDocumentHistoryEntry(snapshotId = "2001", phase = phase, createdAt = 2001L))
            },
            loadDocumentSnapshot = { _, _, _ ->
                Result.failure(IllegalStateException("missing"))
            },
            showHistoryDiffDialog = { request ->
                dialogRequest = request
            },
            setStatusText = statusMessages::add,
        )

        coordinator.showHistoryDiff(
            workflowId = "wf-1",
            phase = phase,
            currentDocument = document(phase, "current"),
        )

        assertNull(dialogRequest)
        assertEquals(
            listOf(SpecCodingBundle.message("spec.history.noSnapshot")),
            statusMessages,
        )
    }

    private fun coordinator(
        resolvePhaseDocumentPath: (workflowId: String, phase: SpecPhase) -> Path? = { _, _ -> null },
        locateArtifact: (workflowId: String, fileName: String) -> Path = { _, fileName -> Path.of("sandbox", fileName) },
        openFile: (Path) -> Boolean = { true },
        listDocumentHistory: (workflowId: String, phase: SpecPhase) -> List<SpecDocumentHistoryEntry> = { _, _ ->
            emptyList()
        },
        loadDocumentSnapshot: (workflowId: String, phase: SpecPhase, snapshotId: String) -> Result<SpecDocument> =
            { _, _, _ -> Result.failure(IllegalStateException("missing")) },
        deleteDocumentSnapshot: (workflowId: String, phase: SpecPhase, snapshotId: String) -> Result<Unit> =
            { _, _, _ -> Result.success(Unit) },
        pruneDocumentHistory: (workflowId: String, phase: SpecPhase, keepLatest: Int) -> Result<Int> =
            { _, _, keepLatest -> Result.success(keepLatest) },
        exportHistoryDiffSummary: (workflowId: String, phase: SpecPhase, content: String) -> Result<String> =
            { _, _, _ -> Result.success("export.md") },
        showHistoryDiffDialog: (SpecWorkflowHistoryDiffDialogRequest) -> Unit = {},
        setStatusText: (String) -> Unit = {},
    ): SpecWorkflowArtifactNavigationCoordinator {
        return SpecWorkflowArtifactNavigationCoordinator(
            resolvePhaseDocumentPath = resolvePhaseDocumentPath,
            locateArtifact = locateArtifact,
            openFile = openFile,
            runIo = { task -> task() },
            invokeLater = { action -> action() },
            listDocumentHistory = listDocumentHistory,
            loadDocumentSnapshot = loadDocumentSnapshot,
            deleteDocumentSnapshot = deleteDocumentSnapshot,
            pruneDocumentHistory = pruneDocumentHistory,
            exportHistoryDiffSummary = exportHistoryDiffSummary,
            showHistoryDiffDialog = showHistoryDiffDialog,
            setStatusText = setStatusText,
            artifactExists = { true },
        )
    }

    private fun document(
        phase: SpecPhase,
        content: String,
    ): SpecDocument {
        return SpecDocument(
            id = "${phase.name.lowercase()}-$content",
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = phase.displayName,
                description = content,
            ),
        )
    }
}
