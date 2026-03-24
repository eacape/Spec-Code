package com.eacape.speccodingplugin.llm

internal object LlmRequestContext {
    internal const val WORKING_DIRECTORY_METADATA_KEY = "working_directory"
    internal const val OPERATION_MODE_METADATA_KEY = "operation_mode"

    fun extractWorkingDirectory(request: LlmRequest): String? {
        return normalizeWorkingDirectory(request.workingDirectory)
            ?: normalizeWorkingDirectory(request.metadata[WORKING_DIRECTORY_METADATA_KEY])
    }

    fun extractOperationMode(request: LlmRequest): String? {
        return request.metadata[OPERATION_MODE_METADATA_KEY]
            ?.trim()
            ?.ifBlank { null }
    }

    fun normalizeWorkingDirectory(path: String?): String? {
        return path?.trim()?.ifBlank { null }
    }
}
