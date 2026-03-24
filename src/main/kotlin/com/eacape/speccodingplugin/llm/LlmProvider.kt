package com.eacape.speccodingplugin.llm

interface LlmProvider {
    val id: String

    suspend fun generate(request: LlmRequest): LlmResponse

    suspend fun stream(request: LlmRequest, onChunk: suspend (LlmChunk) -> Unit): LlmResponse

    fun cancel(requestId: String)

    suspend fun healthCheck(): LlmHealthStatus
}

