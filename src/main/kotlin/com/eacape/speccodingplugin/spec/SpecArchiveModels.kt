package com.eacape.speccodingplugin.spec

import java.nio.file.Path

data class SpecArchiveResult(
    val workflowId: String,
    val archiveId: String,
    val archivePath: Path,
    val auditLogPath: Path,
    val archivedAt: Long,
    val readOnlySummary: SpecArchiveReadOnlySummary,
)

data class SpecArchiveReadOnlySummary(
    val filesMarkedReadOnly: Int,
    val failures: Int,
)

enum class SpecAuditEventType {
    WORKFLOW_CREATED,
    WORKFLOW_OPENED,
    WORKFLOW_CLONED_WITH_TEMPLATE,
    TEMPLATE_SELECTED,
    TEMPLATE_SWITCHED,
    TEMPLATE_SWITCH_ROLLED_BACK,
    STAGE_ADVANCED,
    STAGE_JUMPED,
    STAGE_ROLLED_BACK,
    GATE_RULE_DOWNGRADED,
    GATE_WARNING_CONFIRMED,
    WORKFLOW_SAVED,
    CONFIG_PINNED,
    DOCUMENT_SAVED,
    ARTIFACT_SCAFFOLDED,
    REQUIREMENTS_ARTIFACT_REPAIRED,
    TASKS_ARTIFACT_REPAIRED,
    TASK_STATUS_CHANGED,
    TASK_EXECUTION_RUN_CREATED,
    TASK_EXECUTION_RUN_STATUS_CHANGED,
    TASK_EXECUTION_REQUEST_RECORDED,
    RELATED_FILES_UPDATED,
    TASK_VERIFICATION_RESULT_UPDATED,
    CLARIFICATION_CONFIRMED,
    VERIFICATION_RUN_COMPLETED,
    DELTA_BASELINE_SELECTED,
    DELTA_EXPORTED,
    SOURCE_IMPORTED,
    SOURCE_USAGE_RECORDED,
    SOURCE_REPLACED,
    SOURCE_REMOVED,
    SNAPSHOT_DELETED,
    HISTORY_PRUNED,
    WORKFLOW_DELETED,
    WORKFLOW_ARCHIVED,
}

data class SpecAuditEvent(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val eventId: String,
    val workflowId: String,
    val eventType: SpecAuditEventType,
    val occurredAtEpochMs: Long,
    val occurredAt: String,
    val actor: String? = null,
    val details: Map<String, String> = emptyMap(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
