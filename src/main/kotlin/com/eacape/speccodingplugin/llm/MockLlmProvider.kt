package com.eacape.speccodingplugin.llm

import kotlinx.coroutines.delay

class MockLlmProvider : LlmProvider {
    override val id: String = ID

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val prompt = request.messages.lastOrNull { it.role == LlmRole.USER }?.content.orEmpty()
        val output = "[Mock Reply] 我已收到：$prompt"
        return LlmResponse(
            content = output,
            model = request.model ?: "mock-model-v1",
            usage = LlmUsage(
                promptTokens = request.messages.sumOf { estimateTokens(it.content) },
                completionTokens = estimateTokens(output),
            ),
            finishReason = "stop",
        )
    }

    override suspend fun stream(request: LlmRequest, onChunk: suspend (LlmChunk) -> Unit): LlmResponse {
        val response = generate(request)
        val parts = response.content.chunked(8)
        for (part in parts) {
            delay(40)
            onChunk(LlmChunk(delta = part, isLast = false))
        }
        onChunk(LlmChunk(delta = "", isLast = true))
        return response
    }

    override fun cancel(requestId: String) {
    }

    override suspend fun healthCheck(): LlmHealthStatus {
        return LlmHealthStatus(healthy = true, message = "Mock provider is ready")
    }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    companion object {
        const val ID = "mock"
    }
}

