package com.eacape.speccodingplugin.llm

import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.engine.ClaudeCodeEngine
import com.eacape.speccodingplugin.engine.EngineContext
import com.eacape.speccodingplugin.engine.EngineRequest
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import com.intellij.openapi.diagnostic.thisLogger
import java.util.Locale

/**
 * Claude CLI LlmProvider 适配器
 * 将 LlmProvider 接口委托给 ClaudeCodeEngine
 */
class ClaudeCliLlmProvider(
    private val discoveryService: CliDiscoveryService,
) : LlmProvider {

    override val id: String = ID

    private val logger = thisLogger()

    private val engine: ClaudeCodeEngine by lazy {
        ClaudeCodeEngine(discoveryService.claudeInfo.path)
    }

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val engineRequest = toEngineRequest(request)
        val engineResponse = engine.generate(engineRequest)

        if (!engineResponse.success) {
            throw RuntimeException("Claude CLI error: ${engineResponse.error}")
        }

        return LlmResponse(
            content = engineResponse.content,
            model = request.model ?: "claude-cli",
        )
    }

    override suspend fun stream(
        request: LlmRequest,
        onChunk: suspend (LlmChunk) -> Unit,
    ): LlmResponse {
        val engineRequest = toEngineRequest(request)
        val fallbackNotice = engineRequest.options[IMAGE_FALLBACK_NOTICE_OPTION_KEY]
        if (!fallbackNotice.isNullOrBlank()) {
            onChunk(
                LlmChunk(
                    delta = "",
                    event = ChatStreamEvent(
                        kind = ChatTraceKind.TOOL,
                        detail = fallbackNotice,
                        status = ChatTraceStatus.INFO,
                    ),
                )
            )
        }
        val contentBuilder = StringBuilder()

        engine.stream(engineRequest).collect { chunk ->
            contentBuilder.append(chunk.delta)
            onChunk(
                LlmChunk(
                    delta = chunk.delta,
                    isLast = chunk.isLast,
                    event = chunk.event,
                )
            )
        }

        return LlmResponse(
            content = contentBuilder.toString(),
            model = request.model ?: "claude-cli",
        )
    }

    override fun cancel(requestId: String) {
        engine.cancelProcess(requestId)
    }

    override suspend fun healthCheck(): LlmHealthStatus {
        val info = discoveryService.claudeInfo
        return LlmHealthStatus(
            healthy = info.available,
            message = if (info.available) "Claude CLI v${info.version}" else "Claude CLI not found",
        )
    }

    private fun toEngineRequest(request: LlmRequest): EngineRequest {
        val systemMessages = request.messages
            .filter { it.role == LlmRole.SYSTEM }
            .joinToString("\n\n") { it.content }

        val userMessages = request.messages
            .filter { it.role != LlmRole.SYSTEM }
            .joinToString("\n\n") { it.content }

        val options = mutableMapOf<String, String>()
        request.model?.let { options["model"] = it }
        if (systemMessages.isNotBlank()) {
            options["system_prompt"] = systemMessages
        }
        request.maxTokens?.let { options["max_tokens"] = it.toString() }
        request.metadata["requestId"]?.let { options["requestId"] = it }
        // Always pass prompt through stdin to avoid Windows cmd length limits.
        options["prompt_via_stdin"] = "true"
        val workingDirectory = LlmRequestContext.extractWorkingDirectory(request)
        val normalizedImagePaths = normalizeImagePaths(request.imagePaths)
        val supportsImageFlag = normalizedImagePaths.isEmpty() || engine.supportsImageFlag()
        val effectivePrompt = if (!supportsImageFlag && normalizedImagePaths.isNotEmpty()) {
            options[IMAGE_FALLBACK_NOTICE_OPTION_KEY] = IMAGE_FALLBACK_NOTICE
            mergePromptWithImageFallback(userMessages, normalizedImagePaths)
        } else {
            userMessages
        }

        val operationMode = LlmRequestContext.extractOperationMode(request)
        mapClaudePermissionMode(operationMode)?.let { options["permission_mode"] = it }
        if (operationMode?.equals(OperationMode.AUTO.name, ignoreCase = true) == true) {
            options["allow_dangerously_skip_permissions"] = "true"
            options["dangerously_skip_permissions"] = "true"
        }
        val isSpecRequest =
            request.metadata[SPEC_WORKFLOW_METADATA_KEY]?.equals("true", ignoreCase = true) == true ||
                request.metadata[SPEC_CLARIFICATION_METADATA_KEY]?.equals("true", ignoreCase = true) == true
        if (isSpecRequest) {
            // Spec workflow only needs pure text generation; disable tools to avoid unnecessary CLI tool execution paths.
            options.putIfAbsent("permission_mode", "plan")
            options["tools"] = ""
        }

        return EngineRequest(
            prompt = effectivePrompt,
            context = EngineContext(workingDirectory = workingDirectory),
            imagePaths = if (supportsImageFlag) normalizedImagePaths else emptyList(),
            options = options,
        )
    }

    private fun normalizeImagePaths(imagePaths: List<String>): List<String> {
        return imagePaths
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun mergePromptWithImageFallback(prompt: String, imagePaths: List<String>): String {
        val fallbackBlock = buildString {
            appendLine("Attached image files (native --image option is unavailable in current Claude CLI):")
            imagePaths.forEach { path ->
                appendLine("- $path")
            }
            append("If visual details are required, ask the user to describe key parts of the image.")
        }
        if (prompt.isBlank()) {
            return fallbackBlock
        }
        return "$prompt\n\n$fallbackBlock"
    }

    private fun mapClaudePermissionMode(operationMode: String?): String? {
        val mode = operationMode
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.let { runCatching { OperationMode.valueOf(it) }.getOrNull() }
            ?: return null
        return when (mode) {
            OperationMode.PLAN -> "plan"
            OperationMode.DEFAULT -> null
            OperationMode.AGENT -> "acceptEdits"
            OperationMode.AUTO -> "bypassPermissions"
        }
    }

    companion object {
        const val ID = "claude-cli"
        private const val IMAGE_FALLBACK_NOTICE_OPTION_KEY = "image_fallback_notice"
        private const val IMAGE_FALLBACK_NOTICE =
            "当前 Claude CLI 暂不支持 --image，已降级为图片路径提示。可升级 Claude CLI 以启用原生图片输入。"
        private const val SPEC_WORKFLOW_METADATA_KEY = "specWorkflow"
        private const val SPEC_CLARIFICATION_METADATA_KEY = "specClarification"
    }
}
