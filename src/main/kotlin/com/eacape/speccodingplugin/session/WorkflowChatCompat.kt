package com.eacape.speccodingplugin.session

internal const val WORKFLOW_CHAT_MODE_KEY = "workflow"
internal const val LEGACY_SPEC_CHAT_MODE_KEY = "spec"
internal const val WORKFLOW_CHAT_COMMAND_PREFIX = "/workflow"
internal const val LEGACY_SPEC_CHAT_COMMAND_PREFIX = "/spec"
private const val WORKFLOW_CHAT_SESSION_PREFIX = "[workflow]"
private const val LEGACY_SPEC_CHAT_SESSION_PREFIX = "[spec]"

internal fun canonicalizeWorkflowChatModeKey(modeKey: String?): String? {
    val normalized = modeKey?.trim()?.ifBlank { null } ?: return null
    return when {
        normalized.equals(WORKFLOW_CHAT_MODE_KEY, ignoreCase = true) -> WORKFLOW_CHAT_MODE_KEY
        normalized.equals(LEGACY_SPEC_CHAT_MODE_KEY, ignoreCase = true) -> WORKFLOW_CHAT_MODE_KEY
        else -> normalized
    }
}

internal fun canonicalizeWorkflowChatCommand(command: String?): String? {
    val trimmed = command?.trim()?.ifBlank { null } ?: return null
    return when {
        hasSlashCommandPrefix(trimmed, WORKFLOW_CHAT_COMMAND_PREFIX) ->
            WORKFLOW_CHAT_COMMAND_PREFIX + trimmed.substring(WORKFLOW_CHAT_COMMAND_PREFIX.length)

        hasSlashCommandPrefix(trimmed, LEGACY_SPEC_CHAT_COMMAND_PREFIX) ->
            WORKFLOW_CHAT_COMMAND_PREFIX + trimmed.substring(LEGACY_SPEC_CHAT_COMMAND_PREFIX.length)

        else -> null
    }
}

internal fun workflowChatCommandArgs(command: String): String {
    return canonicalizeWorkflowChatCommand(command)
        ?.removePrefix(WORKFLOW_CHAT_COMMAND_PREFIX)
        ?.trim()
        .orEmpty()
}

internal fun isWorkflowChatCommand(command: String?): Boolean = canonicalizeWorkflowChatCommand(command) != null

internal fun displayWorkflowChatCommand(command: String?): String? {
    val trimmed = command?.trim()?.ifBlank { null } ?: return null
    return canonicalizeWorkflowChatCommand(trimmed) ?: trimmed
}

internal fun displayWorkflowChatSessionTitle(title: String?): String? {
    val trimmed = title?.trim()?.ifBlank { null } ?: return null
    displayWorkflowChatCommand(trimmed)?.let { return it }
    return when {
        trimmed.startsWith(WORKFLOW_CHAT_SESSION_PREFIX, ignoreCase = true) ->
            WORKFLOW_CHAT_SESSION_PREFIX + trimmed.substring(WORKFLOW_CHAT_SESSION_PREFIX.length)

        trimmed.startsWith(LEGACY_SPEC_CHAT_SESSION_PREFIX, ignoreCase = true) ->
            WORKFLOW_CHAT_SESSION_PREFIX + trimmed.substring(LEGACY_SPEC_CHAT_SESSION_PREFIX.length)

        else -> trimmed
    }
}

internal fun isWorkflowChatSessionTitle(title: String?): Boolean {
    val normalized = title?.trim()?.lowercase().orEmpty()
    return normalized.startsWith(WORKFLOW_CHAT_COMMAND_PREFIX) ||
        normalized.startsWith(LEGACY_SPEC_CHAT_COMMAND_PREFIX) ||
        normalized.startsWith(WORKFLOW_CHAT_SESSION_PREFIX) ||
        normalized.startsWith(LEGACY_SPEC_CHAT_SESSION_PREFIX)
}

private fun hasSlashCommandPrefix(value: String, prefix: String): Boolean {
    if (!value.startsWith(prefix, ignoreCase = true)) {
        return false
    }
    return value.length == prefix.length || value[prefix.length].isWhitespace()
}
