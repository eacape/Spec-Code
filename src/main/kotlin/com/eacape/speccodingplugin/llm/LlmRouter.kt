package com.eacape.speccodingplugin.llm

import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger

/**
 * LLM 路由器
 * 根据 CLI 探测结果动态构建 provider 列表
 */
@Service(Service.Level.APP)
class LlmRouter {

    private val logger = thisLogger()

    @Volatile
    private var providerMap: Map<String, LlmProvider> = emptyMap()

    @Volatile
    private var _defaultProviderId: String = MockLlmProvider.ID

    init {
        refreshProviders()

        // 监听 CLI 探测完成事件，自动刷新 provider 列表
        val discoveryService = CliDiscoveryService.getInstance()
        discoveryService.addDiscoveryListener {
            refreshProviders()
            logger.info("LlmRouter auto-refreshed after CLI discovery")
        }
    }

    /**
     * 根据 CliDiscoveryService 的探测结果刷新 provider 列表
     */
    fun refreshProviders() {
        val discoveryService = CliDiscoveryService.getInstance()
        val providers = mutableListOf<LlmProvider>()

        // 始终保留 Mock provider 作为回退
        providers.add(MockLlmProvider())

        if (discoveryService.claudeInfo.available) {
            providers.add(ClaudeCliLlmProvider(discoveryService))
        }

        if (discoveryService.codexInfo.available) {
            providers.add(CodexCliLlmProvider(discoveryService))
        }

        providerMap = providers.associateBy { it.id }

        // 默认 provider 优先级: claude-cli > codex-cli > mock
        _defaultProviderId = when {
            providerMap.containsKey(ClaudeCliLlmProvider.ID) -> ClaudeCliLlmProvider.ID
            providerMap.containsKey(CodexCliLlmProvider.ID) -> CodexCliLlmProvider.ID
            else -> MockLlmProvider.ID
        }

        logger.info("LlmRouter refreshed: providers=${providerMap.keys}, default=$_defaultProviderId")
    }

    fun availableProviders(): List<String> = providerMap.keys.sorted()

    /** UI 层使用：过滤掉 mock provider */
    fun availableUiProviders(): List<String> =
        providerMap.keys.filter { it != MockLlmProvider.ID }.sorted()

    fun defaultProviderId(): String = _defaultProviderId

    suspend fun generate(providerId: String?, request: LlmRequest): LlmResponse {
        return resolveProvider(providerId).generate(request)
    }

    suspend fun stream(
        providerId: String?,
        request: LlmRequest,
        onChunk: suspend (LlmChunk) -> Unit,
    ): LlmResponse {
        return resolveProvider(providerId).stream(request, onChunk)
    }

    fun cancel(providerId: String?, requestId: String) {
        val normalizedRequestId = requestId.trim()
        if (normalizedRequestId.isBlank()) {
            return
        }
        runCatching {
            resolveProvider(providerId).cancel(normalizedRequestId)
        }.onFailure { error ->
            logger.warn("Failed to cancel request=$normalizedRequestId for provider=${providerId.orEmpty()}", error)
        }
    }

    suspend fun healthCheckAll(): Map<String, LlmHealthStatus> {
        return providerMap.mapValues { (_, provider) -> provider.healthCheck() }
    }

    private fun resolveProvider(providerId: String?): LlmProvider {
        val normalized = providerId?.trim().orEmpty()
        if (normalized.isNotBlank()) {
            providerMap[normalized]?.let { return it }
        }
        return providerMap[_defaultProviderId]
            ?: error("Default provider '$_defaultProviderId' is not registered")
    }

    companion object {
        fun getInstance(): LlmRouter = service()
    }
}
