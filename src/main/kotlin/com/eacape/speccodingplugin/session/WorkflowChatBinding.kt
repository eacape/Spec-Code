package com.eacape.speccodingplugin.session

import com.eacape.speccodingplugin.spec.StageId

data class WorkflowChatBinding(
    val workflowId: String,
    val focusedStage: StageId? = null,
    val source: WorkflowChatEntrySource,
    val actionIntent: WorkflowChatActionIntent = WorkflowChatActionIntent.DISCUSS,
)

enum class WorkflowChatEntrySource {
    MODE_SWITCH,
    SPEC_PAGE,
    TASK_PANEL,
    SIDEBAR,
    SESSION_RESTORE,
}

enum class WorkflowChatActionIntent {
    DISCUSS,
    EXECUTE_TASK,
    RETRY_TASK,
    COMPLETE_TASK,
}

internal fun WorkflowChatBinding.normalizedOrNull(): WorkflowChatBinding? {
    val normalizedWorkflowId = workflowId.trim().ifBlank { return null }
    return copy(workflowId = normalizedWorkflowId)
}

internal fun legacyWorkflowChatBinding(
    workflowId: String?,
    source: WorkflowChatEntrySource = WorkflowChatEntrySource.SESSION_RESTORE,
    actionIntent: WorkflowChatActionIntent = WorkflowChatActionIntent.DISCUSS,
    focusedStage: StageId? = null,
): WorkflowChatBinding? {
    val normalizedWorkflowId = workflowId?.trim()?.ifBlank { null } ?: return null
    return WorkflowChatBinding(
        workflowId = normalizedWorkflowId,
        focusedStage = focusedStage,
        source = source,
        actionIntent = actionIntent,
    )
}

internal fun workflowChatBindingFromStorage(
    workflowId: String?,
    focusedStageName: String?,
    sourceName: String?,
    actionIntentName: String?,
    legacyWorkflowId: String? = null,
    fallbackSource: WorkflowChatEntrySource = WorkflowChatEntrySource.SESSION_RESTORE,
): WorkflowChatBinding? {
    val normalizedWorkflowId = workflowId?.trim()?.ifBlank { null }
        ?: legacyWorkflowId?.trim()?.ifBlank { null }
        ?: return null
    val focusedStage = focusedStageName
        ?.trim()
        ?.ifBlank { null }
        ?.let { raw ->
            StageId.entries.firstOrNull { stage -> stage.name.equals(raw, ignoreCase = true) }
        }
    val source = sourceName
        ?.trim()
        ?.ifBlank { null }
        ?.let { raw ->
            WorkflowChatEntrySource.entries.firstOrNull { entry -> entry.name.equals(raw, ignoreCase = true) }
        }
        ?: fallbackSource
    val actionIntent = actionIntentName
        ?.trim()
        ?.ifBlank { null }
        ?.let { raw ->
            WorkflowChatActionIntent.entries.firstOrNull { intent -> intent.name.equals(raw, ignoreCase = true) }
        }
        ?: WorkflowChatActionIntent.DISCUSS
    return WorkflowChatBinding(
        workflowId = normalizedWorkflowId,
        focusedStage = focusedStage,
        source = source,
        actionIntent = actionIntent,
    ).normalizedOrNull()
}

fun ConversationSession.resolvedWorkflowChatBinding(): WorkflowChatBinding? {
    return workflowChatBinding?.normalizedOrNull() ?: legacyWorkflowChatBinding(specTaskId)
}

fun SessionSummary.resolvedWorkflowChatBinding(): WorkflowChatBinding? {
    return workflowChatBinding?.normalizedOrNull() ?: legacyWorkflowChatBinding(specTaskId)
}
