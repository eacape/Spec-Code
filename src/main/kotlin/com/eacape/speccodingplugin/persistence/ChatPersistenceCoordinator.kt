package com.eacape.speccodingplugin.persistence

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.rollback.Changeset
import com.eacape.speccodingplugin.rollback.ChangesetStore
import com.eacape.speccodingplugin.rollback.WorkspaceChangesetCollector
import com.eacape.speccodingplugin.session.ConversationMessage
import com.eacape.speccodingplugin.session.ConversationRole
import com.eacape.speccodingplugin.session.ConversationSession
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.session.WorkflowChatBinding
import com.eacape.speccodingplugin.session.resolvedWorkflowChatBinding
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

internal data class ChatSessionRestoreSnapshot(
    val session: ConversationSession?,
    val messages: List<ConversationMessage>,
)

@Service(Service.Level.PROJECT)
class ChatPersistenceCoordinator(private val project: Project) {
    private var _sessionManagerOverride: SessionManager? = null
    private var _changesetStoreOverride: ChangesetStore? = null

    private val sessionManager: SessionManager by lazy {
        _sessionManagerOverride ?: SessionManager.getInstance(project)
    }
    private val changesetStore: ChangesetStore by lazy {
        _changesetStoreOverride ?: ChangesetStore.getInstance(project)
    }

    internal constructor(
        project: Project,
        sessionManager: SessionManager,
        changesetStore: ChangesetStore,
    ) : this(project) {
        _sessionManagerOverride = sessionManager
        _changesetStoreOverride = changesetStore
    }

    internal fun createSession(
        title: String,
        providerId: String?,
        specTaskId: String? = null,
        workflowChatBinding: WorkflowChatBinding? = null,
    ): Result<ConversationSession> {
        return sessionManager.createSession(
            title = title,
            specTaskId = specTaskId,
            modelProvider = providerId,
            workflowChatBinding = workflowChatBinding,
        )
    }

    internal fun loadSessionRestoreSnapshot(
        sessionId: String,
        fetchLimit: Int,
        restoredMessageLimit: Int,
    ): Result<ChatSessionRestoreSnapshot> {
        return runCatching {
            ChatSessionRestoreSnapshot(
                session = sessionManager.getSession(sessionId),
                messages = loadRecentMessages(sessionId, fetchLimit, restoredMessageLimit),
            )
        }
    }

    internal fun loadRecentMessages(
        sessionId: String,
        fetchLimit: Int,
        restoredMessageLimit: Int,
    ): List<ConversationMessage> {
        if (restoredMessageLimit <= 0) {
            return emptyList()
        }
        return sessionManager.listMessages(sessionId, limit = fetchLimit)
            .takeLast(restoredMessageLimit)
    }

    internal fun getSession(sessionId: String): ConversationSession? {
        return sessionManager.getSession(sessionId)
    }

    internal fun resolveWorkflowChatBinding(sessionId: String): WorkflowChatBinding? {
        return getSession(sessionId)?.resolvedWorkflowChatBinding()
    }

    internal fun persistWorkflowChatBinding(
        sessionId: String,
        binding: WorkflowChatBinding,
    ): Result<ConversationSession> {
        return sessionManager.updateWorkflowChatBinding(sessionId, binding)
    }

    internal fun persistMessage(
        sessionId: String,
        role: ConversationRole,
        content: String,
        metadataJson: String? = null,
    ): Result<ConversationMessage> {
        return sessionManager.addMessage(
            sessionId = sessionId,
            role = role,
            content = content,
            metadataJson = metadataJson,
        )
    }

    internal fun persistWorkflowCommandChangeset(
        command: String,
        beforeSnapshot: WorkspaceChangesetCollector.Snapshot?,
        success: Boolean,
        exitCode: Int?,
        timedOut: Boolean,
        stoppedByUser: Boolean,
        outputTruncated: Boolean,
    ): Result<Changeset?> {
        return runCatching {
            val root = resolveWorkspaceRoot() ?: return@runCatching null
            val before = beforeSnapshot ?: return@runCatching null
            val after = WorkspaceChangesetCollector.capture(root)
            val changes = WorkspaceChangesetCollector.diff(root, before, after)
            val status = when {
                stoppedByUser -> "stopped"
                timedOut -> "timeout"
                success -> "success"
                exitCode != null -> "error"
                else -> "failed"
            }
            val commandSummary = command.take(WORKFLOW_CHANGESET_COMMAND_MAX_LENGTH)
            val metadata = linkedMapOf(
                "source" to "workflow-command",
                "command" to commandSummary,
                "status" to status,
            )
            exitCode?.let { metadata["exitCode"] = it.toString() }
            if (timedOut) metadata["timedOut"] = "true"
            if (stoppedByUser) metadata["stoppedByUser"] = "true"
            if (outputTruncated) metadata["outputTruncated"] = "true"
            if (changes.isEmpty()) metadata["noFileChange"] = "true"

            Changeset(
                id = UUID.randomUUID().toString(),
                description = "Command: $commandSummary",
                changes = changes,
                metadata = metadata,
            ).also(changesetStore::save)
        }
    }

    internal fun persistAssistantResponseChangeset(
        requestText: String,
        providerId: String?,
        modelId: String?,
        beforeSnapshot: WorkspaceChangesetCollector.Snapshot?,
        hasExecutionTrace: Boolean,
    ): Result<Changeset?> {
        return runCatching {
            val root = resolveWorkspaceRoot() ?: return@runCatching null
            val before = beforeSnapshot ?: return@runCatching null
            val after = WorkspaceChangesetCollector.capture(root)
            val changes = WorkspaceChangesetCollector.diff(root, before, after)
            if (changes.isEmpty() && !hasExecutionTrace) {
                return@runCatching null
            }

            val metadata = linkedMapOf(
                "source" to "assistant-response",
                "request" to summarizeAssistantRequest(requestText),
            )
            providerId
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { metadata["provider"] = it }
            modelId
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { metadata["model"] = it }
            if (hasExecutionTrace) {
                metadata["trace"] = "true"
            }
            if (changes.isEmpty()) {
                metadata["status"] = "no-file-change"
            }

            Changeset(
                id = UUID.randomUUID().toString(),
                description = "Response: ${metadata.getValue("request")}",
                changes = changes,
                metadata = metadata,
            ).also(changesetStore::save)
        }
    }

    private fun summarizeAssistantRequest(requestText: String): String {
        return requestText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .joinToString(" ") { it.trim() }
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .take(ASSISTANT_CHANGESET_REQUEST_MAX_LENGTH)
            .ifBlank { SpecCodingBundle.message("common.unknown") }
    }

    private fun resolveWorkspaceRoot(): Path? {
        val basePath = project.basePath ?: return null
        return runCatching { Paths.get(basePath).toAbsolutePath().normalize() }.getOrNull()
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val WORKFLOW_CHANGESET_COMMAND_MAX_LENGTH = 120
        private const val ASSISTANT_CHANGESET_REQUEST_MAX_LENGTH = 120

        fun getInstance(project: Project): ChatPersistenceCoordinator = project.service()
    }
}
