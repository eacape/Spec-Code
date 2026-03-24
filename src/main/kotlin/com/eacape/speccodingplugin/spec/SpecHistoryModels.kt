package com.eacape.speccodingplugin.spec

data class SpecDocumentHistoryEntry(
    val snapshotId: String,
    val phase: SpecPhase,
    val createdAt: Long,
)

enum class SpecSnapshotTrigger {
    WORKFLOW_SAVE_BEFORE,
    WORKFLOW_SAVE_AFTER,
    DOCUMENT_SAVE_BEFORE,
    DOCUMENT_SAVE_AFTER,
    WORKFLOW_ARCHIVE_BEFORE,
    WORKFLOW_DELETE_BEFORE,
}

data class SpecWorkflowSnapshotEntry(
    val snapshotId: String,
    val workflowId: String,
    val trigger: SpecSnapshotTrigger,
    val createdAt: Long,
    val operationId: String? = null,
    val phase: SpecPhase? = null,
    val files: List<String> = emptyList(),
)

data class SpecDeltaBaselineRef(
    val baselineId: String,
    val workflowId: String,
    val snapshotId: String,
    val createdAt: Long,
    val label: String? = null,
)
