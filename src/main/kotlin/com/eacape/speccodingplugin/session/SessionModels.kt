package com.eacape.speccodingplugin.session

enum class ConversationRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

enum class SessionFilter {
    ALL,
    SPEC,
    VIBE,
}

data class ConversationSession(
    val id: String,
    val title: String,
    val specTaskId: String? = null,
    val worktreeId: String? = null,
    val modelProvider: String? = null,
    val parentSessionId: String? = null,
    val branchFromMessageId: String? = null,
    val branchName: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val workflowChatBinding: WorkflowChatBinding? = null,
)

data class ConversationMessage(
    val id: String,
    val sessionId: String,
    val role: ConversationRole,
    val content: String,
    val tokenCount: Int? = null,
    val createdAt: Long,
    val metadataJson: String? = null,
)

data class SessionSummary(
    val id: String,
    val title: String,
    val specTaskId: String? = null,
    val worktreeId: String? = null,
    val modelProvider: String? = null,
    val parentSessionId: String? = null,
    val branchName: String? = null,
    val messageCount: Int,
    val updatedAt: Long,
    val workflowChatBinding: WorkflowChatBinding? = null,
)

data class SessionBranchComparison(
    val leftSessionId: String,
    val rightSessionId: String,
    val commonPrefixCount: Int,
    val leftOnlyCount: Int,
    val rightOnlyCount: Int,
    val leftPreview: String? = null,
    val rightPreview: String? = null,
)

data class SessionContextSnapshot(
    val id: String,
    val sessionId: String,
    val messageId: String? = null,
    val title: String,
    val messageCount: Int,
    val metadataJson: String? = null,
    val createdAt: Long,
)
