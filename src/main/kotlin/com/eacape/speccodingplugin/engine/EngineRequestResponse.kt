package com.eacape.speccodingplugin.engine

import com.eacape.speccodingplugin.stream.ChatStreamEvent

/**
 * 引擎请求
 */
data class EngineRequest(
    val prompt: String,
    val capability: EngineCapability = EngineCapability.CODE_GENERATION,
    val context: EngineContext = EngineContext(),
    val imagePaths: List<String> = emptyList(),
    val options: Map<String, String> = emptyMap(),
)

/**
 * 引擎上下文
 */
data class EngineContext(
    val workingDirectory: String? = null,
    val currentFile: String? = null,
    val selectedCode: String? = null,
    val language: String? = null,
)

/**
 * 引擎响应
 */
data class EngineResponse(
    val content: String,
    val engineId: String,
    val success: Boolean = true,
    val error: String? = null,
)

/**
 * 引擎流式输出块
 */
data class EngineChunk(
    val delta: String,
    val isLast: Boolean = false,
    val event: ChatStreamEvent? = null,
)
