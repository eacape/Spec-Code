package com.eacape.speccodingplugin.llm

import com.eacape.speccodingplugin.stream.ChatStreamEvent

enum class LlmRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

data class LlmMessage(
    val role: LlmRole,
    val content: String,
)

data class LlmRequest(
    val messages: List<LlmMessage>,
    val model: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val imagePaths: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
)

data class LlmUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
) {
    val totalTokens: Int
        get() = promptTokens + completionTokens
}

data class LlmResponse(
    val content: String,
    val model: String,
    val usage: LlmUsage = LlmUsage(),
    val finishReason: String? = null,
)

data class LlmChunk(
    val delta: String,
    val isLast: Boolean = false,
    val event: ChatStreamEvent? = null,
)

data class LlmHealthStatus(
    val healthy: Boolean,
    val message: String? = null,
)
