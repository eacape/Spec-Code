package com.eacape.speccodingplugin.llm

import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger

/**
 * 模型注册表 - 根据 CLI 探测结果动态管理可用模型
 */
@Service
class ModelRegistry {
    private val logger = thisLogger()
    private val models = mutableMapOf<String, ModelInfo>()

    init {
        try {
            refreshFromDiscovery()
        } catch (e: Exception) {
            logger.warn("ModelRegistry init: CLI discovery unavailable, registry is empty", e)
        }

        // 监听 CLI 探测完成事件，自动刷新模型列表
        try {
            val discoveryService = CliDiscoveryService.getInstance()
            discoveryService.addDiscoveryListener {
                refreshFromDiscovery()
                logger.info("ModelRegistry auto-refreshed after CLI discovery")
            }
        } catch (e: Exception) {
            logger.warn("ModelRegistry: failed to register discovery listener", e)
        }
    }

    /**
     * 从 CliDiscoveryService 刷新模型列表
     */
    fun refreshFromDiscovery() {
        val discoveryService = CliDiscoveryService.getInstance()
        models.clear()

        if (discoveryService.claudeInfo.available) {
            discoveryService.claudeInfo.models.forEach { modelId ->
                register(
                    ModelInfo(
                        id = modelId,
                        name = formatModelName(modelId),
                        provider = ClaudeCliLlmProvider.ID,
                        contextWindow = 200_000,
                        capabilities = setOf(
                            ModelCapability.CODE_GENERATION,
                            ModelCapability.CODE_REVIEW,
                            ModelCapability.CHAT,
                            ModelCapability.REASONING,
                        ),
                    )
                )
            }
        }

        if (discoveryService.codexInfo.available) {
            discoveryService.codexInfo.models.forEach { modelId ->
                register(
                    ModelInfo(
                        id = modelId,
                        name = formatModelName(modelId),
                        provider = CodexCliLlmProvider.ID,
                        contextWindow = 200_000,
                        capabilities = setOf(
                            ModelCapability.CODE_GENERATION,
                            ModelCapability.CODE_REVIEW,
                            ModelCapability.CHAT,
                        ),
                    )
                )
            }
        }

        logger.info("ModelRegistry refreshed: ${models.size} models from ${getModelsByProvider().keys}")
    }

    fun register(model: ModelInfo) {
        models[model.id] = model
    }

    fun getModel(id: String): ModelInfo? = models[id]

    fun getAllModels(): List<ModelInfo> = models.values.toList()

    fun getModelsByProvider(): Map<String, List<ModelInfo>> = models.values.groupBy { it.provider }

    fun getModelsForProvider(provider: String): List<ModelInfo> = models.values.filter { it.provider == provider }

    fun hasCapability(modelId: String, capability: ModelCapability): Boolean {
        return models[modelId]?.capabilities?.contains(capability) ?: false
    }

    private fun formatModelName(modelId: String): String {
        val normalized = modelId.trim().lowercase()

        return when {
            normalized == "sonnet" -> "Claude Sonnet"
            normalized == "opus" -> "Claude Opus"
            normalized == "haiku" -> "Claude Haiku"
            normalized.startsWith("claude-") -> formatClaudeName(normalized)
            else -> normalized
                .replace("-", " ")
                .replace(Regex("""\d{8}$"""), "")
                .trim()
                .split(" ")
                .joinToString(" ") { token ->
                    when {
                        token.equals("gpt", ignoreCase = true) -> "GPT"
                        token.equals("codex", ignoreCase = true) -> "Codex"
                        token.equals("o3", ignoreCase = true) -> "O3"
                        token.equals("o4", ignoreCase = true) -> "O4"
                        else -> token.replaceFirstChar { c -> c.uppercase() }
                    }
                }
        }
    }

    private fun formatClaudeName(modelId: String): String {
        val withoutDate = modelId.removeSuffixDate()
        val parts = withoutDate.split("-")
        if (parts.size < 2) return modelId

        val family = parts[1].replaceFirstChar { it.uppercase() }
        val sb = StringBuilder("Claude ").append(family)

        if (parts.size >= 4 && parts[2].all { it.isDigit() } && parts[3].all { it.isDigit() }) {
            sb.append(" ").append(parts[2]).append(".").append(parts[3])
            if (parts.size > 4) {
                sb.append(" ")
                sb.append(parts.drop(4).joinToString(" ") { formatSuffixToken(it) })
            }
            return sb.toString().trim()
        }

        if (parts.size > 2) {
            sb.append(" ")
            sb.append(parts.drop(2).joinToString(" ") { formatSuffixToken(it) })
        }

        return sb.toString().trim()
    }

    private fun String.removeSuffixDate(): String {
        return replace(Regex("""-\d{8}$"""), "")
    }

    private fun formatSuffixToken(token: String): String {
        return when {
            token.equals("1m", ignoreCase = true) -> "1M"
            token.all { it.isDigit() } -> token
            else -> token.replaceFirstChar { it.uppercase() }
        }
    }

    companion object {
        fun getInstance(): ModelRegistry = service()
    }
}

/**
 * 模型信息
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String,
    val contextWindow: Int,
    val capabilities: Set<ModelCapability>,
    val description: String? = null,
)

/**
 * 模型能力
 */
enum class ModelCapability {
    CODE_GENERATION,
    CODE_REVIEW,
    CHAT,
    REASONING,
    REFACTOR,
    TEST_GENERATION,
}
