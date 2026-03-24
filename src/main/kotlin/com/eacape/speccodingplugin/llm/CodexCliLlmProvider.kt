package com.eacape.speccodingplugin.llm

import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.engine.EngineContext
import com.eacape.speccodingplugin.engine.EngineRequest
import com.eacape.speccodingplugin.engine.OpenAiCodexEngine
import com.eacape.speccodingplugin.mcp.McpHub
import com.eacape.speccodingplugin.mcp.McpServerConfig
import com.eacape.speccodingplugin.mcp.TransportType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Codex CLI LlmProvider 适配器
 * 将 LlmProvider 接口委托给 OpenAiCodexEngine
 */
class CodexCliLlmProvider(
    private val discoveryService: CliDiscoveryService,
) : LlmProvider {

    override val id: String = ID

    private val logger = thisLogger()

    private val enginesByPath = ConcurrentHashMap<String, OpenAiCodexEngine>()

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val engineRequest = toEngineRequest(request)
        val engineResponse = resolveEngine().generate(engineRequest)

        if (!engineResponse.success) {
            throw RuntimeException("Codex CLI error: ${engineResponse.error}")
        }

        return LlmResponse(
            content = engineResponse.content,
            model = request.model ?: "codex-cli",
        )
    }

    override suspend fun stream(
        request: LlmRequest,
        onChunk: suspend (LlmChunk) -> Unit,
    ): LlmResponse {
        val engineRequest = toEngineRequest(request)
        val contentBuilder = StringBuilder()
        val engine = resolveEngine()

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
            model = request.model ?: "codex-cli",
        )
    }

    override fun cancel(requestId: String) {
        enginesByPath.values.forEach { engine ->
            engine.cancelProcess(requestId)
        }
    }

    override suspend fun healthCheck(): LlmHealthStatus {
        val info = discoveryService.codexInfo
        return LlmHealthStatus(
            healthy = info.available,
            message = if (info.available) "Codex CLI v${info.version}" else "Codex CLI not found",
        )
    }

    private fun toEngineRequest(request: LlmRequest): EngineRequest {
        // Codex 不支持 system prompt 参数，将 system 消息合并到 prompt 中
        val allMessages = request.messages.joinToString("\n\n") { msg ->
            when (msg.role) {
                LlmRole.SYSTEM -> "[System]\n${msg.content}"
                LlmRole.USER -> msg.content
                LlmRole.ASSISTANT -> "[Assistant]\n${msg.content}"
            }
        }

        val options = mutableMapOf<String, String>()
        request.model?.let { options["model"] = it }
        request.metadata["requestId"]?.let { options["requestId"] = it }
        val workingDirectory = LlmRequestContext.extractWorkingDirectory(request)
        val operationMode = LlmRequestContext.extractOperationMode(request)
        options.putAll(mapCodexExecutionOptions(operationMode))
        options.putAll(mapCodexMcpServerOptions(workingDirectory))

        return EngineRequest(
            prompt = allMessages,
            context = EngineContext(workingDirectory = workingDirectory),
            imagePaths = normalizeImagePaths(request.imagePaths),
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

    private fun resolveEngine(): OpenAiCodexEngine {
        val cliPath = resolveExecutablePath(discoveryService.codexInfo.path)
        return enginesByPath.computeIfAbsent(cliPath) { path ->
            logger.info("Using Codex CLI path: $path")
            OpenAiCodexEngine(path)
        }
    }

    private fun resolveExecutablePath(rawPath: String): String {
        val sanitized = rawPath.trim().trim('"', '\'')
        if (sanitized.isBlank()) return "codex"

        val normalized = sanitized.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)
        if (normalized.endsWith("/.codex")) {
            // `.codex` is Codex home directory, not an executable path.
            return "codex"
        }

        val configuredFile = File(sanitized)
        if (configuredFile.isDirectory) {
            val candidates = if (isWindows()) {
                listOf("codex.cmd", "codex.exe", "codex.bat", "codex")
            } else {
                listOf("codex")
            }
            candidates
                .asSequence()
                .map { child -> File(configuredFile, child) }
                .firstOrNull { child -> child.isFile }
                ?.let { executable -> return executable.absolutePath }
            return "codex"
        }

        return sanitized
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")

    private fun mapCodexExecutionOptions(operationMode: String?): Map<String, String> {
        val mode = operationMode
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.let { runCatching { OperationMode.valueOf(it) }.getOrNull() }
            ?: return emptyMap()
        return when (mode) {
            OperationMode.PLAN -> mapOf("sandbox_mode" to "read-only")
            OperationMode.DEFAULT -> emptyMap()
            OperationMode.AGENT -> mapOf("full_auto" to "true")
            OperationMode.AUTO -> mapOf(
                "dangerously_bypass_approvals_and_sandbox" to "true",
            )
        }
    }

    private fun mapCodexMcpServerOptions(workingDirectory: String?): Map<String, String> {
        val project = resolveProjectForWorkingDirectory(workingDirectory) ?: return emptyMap()
        val mcpHub = runCatching { McpHub.getInstance(project) }
            .getOrElse { error ->
                logger.debug("Failed to get McpHub for project: ${project.name}", error)
                return emptyMap()
            }

        runCatching { mcpHub.loadPersistedConfigs() }
            .onFailure { error -> logger.debug("Failed to load persisted MCP configs", error) }

        val trustedServers = mcpHub.getAllServers()
            .map { it.config }
            .filter { config ->
                config.trusted && config.command.trim().isNotBlank()
            }
        if (trustedServers.isEmpty()) {
            return emptyMap()
        }

        val options = linkedMapOf<String, String>()
        val usedNames = mutableSetOf<String>()
        trustedServers
            .sortedBy { it.id.lowercase(Locale.ROOT) }
            .forEachIndexed { index, config ->
                val codexServerName = normalizeCodexServerName(config.id, usedNames, index)
                buildCodexMcpConfigEntries(codexServerName, config).forEachIndexed { entryIndex, entry ->
                    options["${OpenAiCodexEngine.CODEX_CONFIG_OPTION_PREFIX}${index.toString().padStart(2, '0')}_${entryIndex.toString().padStart(2, '0')}"] =
                        entry
                }
            }
        return options
    }

    private fun buildCodexMcpConfigEntries(serverName: String, config: McpServerConfig): List<String> {
        return when (config.transport) {
            TransportType.STDIO -> {
                buildList {
                    add("mcp_servers.$serverName.command=${tomlString(config.command.trim())}")
                    add("mcp_servers.$serverName.args=${tomlStringArray(config.args)}")
                    if (config.env.isNotEmpty()) {
                        add("mcp_servers.$serverName.env=${tomlInlineStringMap(config.env)}")
                    }
                }
            }

            TransportType.SSE -> {
                // Codex CLI uses `url` for streamable HTTP server definitions.
                listOf("mcp_servers.$serverName.url=${tomlString(config.command.trim())}")
            }
        }
    }

    private fun resolveProjectForWorkingDirectory(workingDirectory: String?): Project? {
        val normalizedWorkingDirectory = normalizePathForMatch(workingDirectory ?: return null)
        val candidates = ProjectManager.getInstance().openProjects
            .asSequence()
            .filter { !it.isDisposed }
            .mapNotNull { project ->
                val basePath = project.basePath ?: return@mapNotNull null
                val normalizedBasePath = normalizePathForMatch(basePath)
                val isMatch = normalizedWorkingDirectory == normalizedBasePath ||
                    normalizedWorkingDirectory.startsWith("$normalizedBasePath/")
                if (isMatch) {
                    project to normalizedBasePath.length
                } else {
                    null
                }
            }
            .toList()
        if (candidates.isNotEmpty()) {
            return candidates.maxByOrNull { (_, length) -> length }?.first
        }
        val openProjects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        return if (openProjects.size == 1) openProjects.first() else null
    }

    private fun normalizePathForMatch(path: String): String {
        return path
            .replace('\\', '/')
            .trimEnd('/')
            .lowercase(Locale.ROOT)
    }

    private fun normalizeCodexServerName(
        seed: String,
        usedNames: MutableSet<String>,
        index: Int,
    ): String {
        val normalized = seed
            .trim()
            .lowercase(Locale.ROOT)
            .replace(CODEX_SERVER_NAME_REGEX, "_")
            .trim('_')
            .ifBlank { "server_${index + 1}" }
            .take(MAX_CODEX_SERVER_NAME_LENGTH)
        if (usedNames.add(normalized)) {
            return normalized
        }
        var suffix = 2
        var candidate = "${normalized}_${suffix}"
        while (!usedNames.add(candidate)) {
            suffix += 1
            candidate = "${normalized}_${suffix}"
        }
        return candidate
    }

    private fun tomlStringArray(values: List<String>): String {
        if (values.isEmpty()) return "[]"
        return values.joinToString(prefix = "[", postfix = "]", separator = ",") { value ->
            tomlString(value)
        }
    }

    private fun tomlInlineStringMap(values: Map<String, String>): String {
        if (values.isEmpty()) return "{}"
        return values
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .entries
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
                "${tomlInlineKey(key)}=${tomlString(value)}"
            }
    }

    private fun tomlInlineKey(value: String): String {
        val normalized = value.trim()
        if (normalized.matches(TOML_BARE_KEY_REGEX)) {
            return normalized
        }
        return "\"${escapeForTomlBasicString(normalized)}\""
    }

    private fun tomlString(value: String): String {
        if (canUseTomlLiteralString(value)) {
            return "'$value'"
        }
        return "\"${escapeForTomlBasicString(value)}\""
    }

    private fun canUseTomlLiteralString(value: String): Boolean {
        return !value.contains('\'') &&
            !value.contains('\n') &&
            !value.contains('\r')
    }

    private fun escapeForTomlBasicString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
    }

    companion object {
        const val ID = "codex-cli"
        private val CODEX_SERVER_NAME_REGEX = Regex("[^a-z0-9_]+")
        private val TOML_BARE_KEY_REGEX = Regex("^[A-Za-z0-9_-]+$")
        private const val MAX_CODEX_SERVER_NAME_LENGTH = 48
    }
}
