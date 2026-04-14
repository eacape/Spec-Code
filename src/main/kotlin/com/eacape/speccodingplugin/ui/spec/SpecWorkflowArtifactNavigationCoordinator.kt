package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecDocumentHistoryEntry
import com.eacape.speccodingplugin.spec.SpecPhase
import java.nio.file.Files
import java.nio.file.Path

internal data class SpecWorkflowHistoryDiffDialogRequest(
    val phase: SpecPhase,
    val currentDocument: SpecDocument,
    val snapshots: List<SpecHistoryDiffDialog.SnapshotVersion>,
    val onDeleteSnapshot: (SpecHistoryDiffDialog.SnapshotVersion) -> Boolean,
    val onPruneSnapshots: (Int) -> Int,
    val onExportSummary: (String) -> Result<String>,
)

internal class SpecWorkflowArtifactNavigationCoordinator(
    private val resolvePhaseDocumentPath: (workflowId: String, phase: SpecPhase) -> Path?,
    private val locateArtifact: (workflowId: String, fileName: String) -> Path,
    private val openFile: (Path) -> Boolean,
    private val runIo: (task: () -> Unit) -> Unit,
    private val invokeLater: (action: () -> Unit) -> Unit,
    private val listDocumentHistory: (workflowId: String, phase: SpecPhase) -> List<SpecDocumentHistoryEntry>,
    private val loadDocumentSnapshot: (workflowId: String, phase: SpecPhase, snapshotId: String) -> Result<SpecDocument>,
    private val deleteDocumentSnapshot: (workflowId: String, phase: SpecPhase, snapshotId: String) -> Result<Unit>,
    private val pruneDocumentHistory: (workflowId: String, phase: SpecPhase, keepLatest: Int) -> Result<Int>,
    private val exportHistoryDiffSummary: (workflowId: String, phase: SpecPhase, content: String) -> Result<String>,
    private val showHistoryDiffDialog: (SpecWorkflowHistoryDiffDialogRequest) -> Unit,
    private val setStatusText: (String) -> Unit,
    private val artifactExists: (Path) -> Boolean = Files::exists,
) {

    fun openPhaseDocument(workflowId: String?, phase: SpecPhase) {
        val normalizedWorkflowId = workflowId.normalizeWorkflowId() ?: return
        val path = resolvePhaseDocumentPath(normalizedWorkflowId, phase) ?: return
        openFile(path)
    }

    fun openArtifactInEditor(workflowId: String?, fileName: String) {
        val normalizedWorkflowId = workflowId.normalizeWorkflowId() ?: return
        val path = runCatching { locateArtifact(normalizedWorkflowId, fileName) }.getOrNull() ?: return
        if (!artifactExists(path) || !openFile(path)) {
            setStatusText(SpecCodingBundle.message("spec.action.verify.document.unavailable.title"))
        }
    }

    fun showHistoryDiff(
        workflowId: String?,
        phase: SpecPhase,
        currentDocument: SpecDocument?,
    ) {
        val normalizedWorkflowId = workflowId.normalizeWorkflowId() ?: return
        if (currentDocument == null) {
            setStatusText(SpecCodingBundle.message("spec.history.noCurrentDocument"))
            return
        }

        runIo {
            val snapshots = listDocumentHistory(normalizedWorkflowId, phase).loadSnapshots(normalizedWorkflowId, phase)
            invokeLater {
                if (snapshots.isEmpty()) {
                    setStatusText(SpecCodingBundle.message("spec.history.noSnapshot"))
                    return@invokeLater
                }

                showHistoryDiffDialog(
                    SpecWorkflowHistoryDiffDialogRequest(
                        phase = phase,
                        currentDocument = currentDocument,
                        snapshots = snapshots,
                        onDeleteSnapshot = { snapshot ->
                            deleteDocumentSnapshot(normalizedWorkflowId, phase, snapshot.snapshotId).isSuccess
                        },
                        onPruneSnapshots = { keepLatest ->
                            pruneDocumentHistory(normalizedWorkflowId, phase, keepLatest).getOrElse { -1 }
                        },
                        onExportSummary = { content ->
                            exportHistoryDiffSummary(normalizedWorkflowId, phase, content)
                        },
                    ),
                )
                setStatusText(
                    SpecCodingBundle.message(
                        "spec.history.diff.opened",
                        phase.displayName,
                        snapshots.size,
                    ),
                )
            }
        }
    }

    private fun List<SpecDocumentHistoryEntry>.loadSnapshots(
        workflowId: String,
        phase: SpecPhase,
    ): List<SpecHistoryDiffDialog.SnapshotVersion> {
        return mapNotNull { entry ->
            loadDocumentSnapshot(workflowId, phase, entry.snapshotId).getOrNull()?.let { snapshotDocument ->
                SpecHistoryDiffDialog.SnapshotVersion(
                    snapshotId = entry.snapshotId,
                    createdAt = entry.createdAt,
                    document = snapshotDocument,
                )
            }
        }
    }

    private fun String?.normalizeWorkflowId(): String? {
        return this?.trim()?.takeIf { it.isNotEmpty() }
    }
}
