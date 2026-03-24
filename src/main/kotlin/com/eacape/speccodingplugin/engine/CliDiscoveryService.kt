package com.eacape.speccodingplugin.engine

import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.EnvironmentUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * CLI 工具信息
 */
data class CliToolInfo(
    val available: Boolean,
    val path: String,
    val version: String? = null,
    val models: List<String> = emptyList(),
)

/**
 * CLI 斜杠命令信息
 */
data class CliSlashCommandInfo(
    val providerId: String,
    val command: String,
    val description: String = "",
    val invocationKind: CliSlashInvocationKind = CliSlashInvocationKind.COMMAND,
)

enum class CliSlashInvocationKind {
    COMMAND,
    OPTION,
}

/**
 * CLI 自动探测服务
 * 在后台线程探测本地已安装的 claude / codex CLI 工具
 */
@Service(Service.Level.APP)
class CliDiscoveryService : com.intellij.openapi.Disposable {

    private val logger = thisLogger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var claudeInfo: CliToolInfo = CliToolInfo(available = false, path = "claude")
        private set

    @Volatile
    var codexInfo: CliToolInfo = CliToolInfo(available = false, path = "codex")
        private set

    @Volatile
    var claudeSlashCommands: List<CliSlashCommandInfo> = emptyList()
        private set

    @Volatile
    var codexSlashCommands: List<CliSlashCommandInfo> = emptyList()
        private set

    @Volatile
    var discoveryCompleted: Boolean = false
        private set

    private val listeners = mutableListOf<() -> Unit>()

    init {
        restoreCachedDiscoverySnapshot()
        // 插件启动时自动在后台探测 CLI 工具
        discoverAllAsync()
    }

    fun addDiscoveryListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeDiscoveryListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it() }
    }

    fun listSlashCommands(): List<CliSlashCommandInfo> {
        val merged = linkedMapOf<String, CliSlashCommandInfo>()
        (claudeSlashCommands + codexSlashCommands).forEach { item ->
            val normalized = item.command.trim().lowercase(Locale.ROOT)
            val provider = item.providerId.trim().lowercase(Locale.ROOT)
            if (normalized.isBlank()) return@forEach
            if (provider.isBlank()) return@forEach
            val key = "$provider::$normalized"
            if (!merged.containsKey(key)) {
                merged[key] = item.copy(
                    providerId = provider,
                    command = normalized,
                )
            }
        }
        return merged.values.toList()
    }

    /**
     * 异步探测所有 CLI 工具
     */
    fun discoverAllAsync() {
        scope.launch {
            discoverAll()
        }
    }

    /**
     * 同步探测所有 CLI 工具（需在 IO 线程调用）
     */
    suspend fun discoverAll() = withContext(Dispatchers.IO) {
        try {
            val settings = SpecCodingSettingsState.getInstance()

            val claudePath = resolveCliPath(settings.claudeCodeCliPath, "claude")
            claudeInfo = discoverClaude(claudePath)
            claudeSlashCommands = if (claudeInfo.available) {
                parseClaudeSlashCommands(claudeInfo.path)
            } else {
                emptyList()
            }

            val codexPath = resolveCliPath(settings.codexCliPath, "codex")
            codexInfo = discoverCodex(codexPath)
            codexSlashCommands = if (codexInfo.available) {
                parseCodexSlashCommands(codexInfo.path)
            } else {
                emptyList()
            }
            persistDiscoverySnapshot(settings, claudeInfo, codexInfo)

            discoveryCompleted = true
            logger.info(
                "CLI discovery completed: claude=${claudeInfo.available}(${claudeInfo.path}), " +
                    "codex=${codexInfo.available}(${codexInfo.path}), " +
                    "slashCommands=${claudeSlashCommands.size + codexSlashCommands.size}"
            )
            notifyListeners()
        } catch (e: Exception) {
            logger.warn("CLI discovery failed", e)
            claudeSlashCommands = emptyList()
            codexSlashCommands = emptyList()
            discoveryCompleted = true
            notifyListeners()
        }
    }

    private fun restoreCachedDiscoverySnapshot() {
        runCatching {
            val settings = SpecCodingSettingsState.getInstance()
            val restoredClaude = CliToolInfo(
                available = settings.cachedClaudeAvailable,
                path = bootstrapPath(
                    configuredPath = settings.claudeCodeCliPath,
                    cachedPath = settings.cachedClaudePath,
                    fallbackCommand = "claude",
                ),
                version = settings.cachedClaudeVersion.takeIf { it.isNotBlank() },
                models = decodeModels(settings.cachedClaudeModels),
            )
            val restoredCodex = CliToolInfo(
                available = settings.cachedCodexAvailable,
                path = bootstrapPath(
                    configuredPath = settings.codexCliPath,
                    cachedPath = settings.cachedCodexPath,
                    fallbackCommand = "codex",
                ),
                version = settings.cachedCodexVersion.takeIf { it.isNotBlank() },
                models = decodeModels(settings.cachedCodexModels),
            )
            claudeInfo = restoredClaude
            codexInfo = restoredCodex
            logger.info(
                "CLI discovery cache restored: claude=${restoredClaude.available}(${restoredClaude.path}), " +
                    "codex=${restoredCodex.available}(${restoredCodex.path}), ts=${settings.cachedCliDiscoveryEpochMillis}"
            )
        }.onFailure { error ->
            logger.warn("Failed to restore CLI discovery cache", error)
        }
    }

    private fun persistDiscoverySnapshot(
        settings: SpecCodingSettingsState,
        claude: CliToolInfo,
        codex: CliToolInfo,
    ) {
        settings.cachedClaudeAvailable = claude.available
        settings.cachedClaudePath = claude.path
        settings.cachedClaudeVersion = claude.version.orEmpty()
        settings.cachedClaudeModels = encodeModels(claude.models)

        settings.cachedCodexAvailable = codex.available
        settings.cachedCodexPath = codex.path
        settings.cachedCodexVersion = codex.version.orEmpty()
        settings.cachedCodexModels = encodeModels(codex.models)

        settings.cachedCliDiscoveryEpochMillis = System.currentTimeMillis()
    }

    private fun bootstrapPath(configuredPath: String, cachedPath: String, fallbackCommand: String): String {
        val configured = configuredPath.trim()
        if (configured.isNotBlank()) {
            return resolveCliPath(configured, fallbackCommand)
        }
        val cached = cachedPath.trim()
        if (cached.isNotBlank()) {
            return resolveCliPath(cached, fallbackCommand)
        }
        return fallbackCommand
    }

    private fun decodeModels(serialized: String): List<String> {
        if (serialized.isBlank()) return emptyList()
        return serialized
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun encodeModels(models: List<String>): String {
        return models
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
    }

    /**
     * 解析 CLI 路径：
     * - 如果用户配置了路径且是有效的可执行文件，直接使用
     * - 如果用户配置的路径是目录，尝试在目录下查找可执行文件
     * - 如果目录下找不到，继续在 PATH 和常见位置中查找
     * - 如果配置的是裸命令名，在 PATH 中查找
     */
    private fun resolveCliPath(configuredPath: String, toolName: String): String {
        val normalizedConfiguredPath = normalizePathInput(configuredPath)
        if (normalizedConfiguredPath.isNotBlank()) {
            val configFile = File(normalizedConfiguredPath)

            // 用户配置的是可执行文件路径
            if (configFile.isFile) {
                return configFile.absolutePath
            }

            // 用户配置的是目录（如 C:\Users\xxx\.claude），在目录下查找可执行文件
            if (configFile.isDirectory) {
                val found = findExecutableInDir(configFile, toolName)
                if (found != null) return found
                // 目录下没找到，继续用 toolName 在 PATH 中查找
                logger.info("No executable '$toolName' found in directory: $normalizedConfiguredPath, falling back to PATH")
            }

            // 配置的路径可能是裸命令名（如 "claude"），尝试在 PATH 中查找
            if (!normalizedConfiguredPath.contains(File.separator) && !normalizedConfiguredPath.contains("/")) {
                val found = findInPath(normalizedConfiguredPath)
                if (found != null) return found
                val shellFound = findInLoginShellPath(normalizedConfiguredPath)
                if (shellFound != null) return shellFound
            }

            // 配置的路径无效（目录下没找到、不是文件、不是裸命令名），
            // 不再原样返回无效路径，继续用 toolName 在 PATH 和常见位置中查找
        }

        // 在 PATH 和常见安装位置中查找
        return findInPath(toolName)
            ?: findInCommonLocations(toolName)
            ?: findInLoginShellPath(toolName)
            ?: toolName
    }

    /**
     * 在指定目录下查找可执行文件
     */
    private fun findExecutableInDir(dir: File, toolName: String): String? {
        val extensions = if (isWindows()) listOf(".exe", ".cmd", ".bat", "") else listOf("")
        for (ext in extensions) {
            val file = File(dir, toolName + ext)
            if (isExecutable(file)) {
                return file.absolutePath
            }
        }
        return null
    }

    /**
     * 探测 Claude CLI
     */
    private fun discoverClaude(cliPath: String): CliToolInfo {
        val version = runCommand(cliPath, listOf("--version"))
            ?: return CliToolInfo(available = false, path = cliPath)

        val models = parseClaudeModels(cliPath)

        return CliToolInfo(
            available = true,
            path = cliPath,
            version = version.trim(),
            models = models,
        )
    }

    /**
     * 探测 Codex CLI
     */
    private fun discoverCodex(cliPath: String): CliToolInfo {
        val version = runCommand(cliPath, listOf("--version"))
            ?: return CliToolInfo(available = false, path = cliPath)

        val models = parseCodexModels(cliPath)

        return CliToolInfo(
            available = true,
            path = cliPath,
            version = version.trim(),
            models = models,
        )
    }

    /**
     * 尝试通过 claude model list 获取模型列表
     */
    private fun parseClaudeModels(cliPath: String): List<String> {
        val modelListOutputs = listOfNotNull(
            runCommand(cliPath, listOf("model", "list"), acceptNonZeroExit = true),
            runCommand(cliPath, listOf("models", "list"), acceptNonZeroExit = true),
        )
        val helpOutput = runCommand(cliPath, listOf("--help"), acceptNonZeroExit = true)

        return try {
            val fullIds = linkedSetOf<String>()
            val aliases = linkedSetOf<String>()

            modelListOutputs.forEach { output ->
                fullIds += CLAUDE_MODEL_ID_REGEX.findAll(output).map { it.value.lowercase() }.toList()
                aliases += CLAUDE_MODEL_ALIAS_REGEX.findAll(output).map { it.value.lowercase() }.toList()
            }
            helpOutput?.let { output ->
                // Help output often contains example model IDs (e.g. one Sonnet snapshot).
                // Treat it as alias fallback only; prefer model-list/local-package results.
                aliases += CLAUDE_MODEL_ALIAS_REGEX.findAll(output).map { it.value.lowercase() }.toList()
            }

            // 从本地安装的 Claude CLI 包中提取模型常量（例如 IFA={opus,sonnet,haiku}）。
            val packageModels = parseClaudeModelsFromLocalCliPackage(cliPath)
            if (packageModels.isNotEmpty()) {
                fullIds += packageModels
            }

            if (fullIds.isNotEmpty()) return fullIds.toList()
            if (aliases.isNotEmpty()) return aliases.toList()

            // Last resort: if nothing else is available, fall back to full IDs found in help text.
            helpOutput
                ?.let { CLAUDE_MODEL_ID_REGEX.findAll(it).map { match -> match.value.lowercase() }.toList() }
                .orEmpty()
                .distinct()
        } catch (e: Exception) {
            logger.info("Failed to parse claude models: ${e.message}")
            emptyList()
        }
    }

    private fun parseClaudeModelsFromLocalCliPackage(cliPath: String): List<String> {
        return try {
            val userHome = System.getProperty("user.home")
            val env = effectiveEnvironment()
            val candidates = buildClaudeCliPackageCandidates(
                cliPath = cliPath,
                userHome = userHome,
                env = env,
                isMac = isMac(),
            ).map(::File).toMutableList()

            // nvm installs packages under ~/.nvm/versions/node/<version>/lib/node_modules.
            if (!userHome.isNullOrBlank()) {
                val nvmVersionsDir = File(userHome, ".nvm/versions/node")
                if (nvmVersionsDir.isDirectory) {
                    nvmVersionsDir.listFiles()
                        ?.asSequence()
                        ?.filter { it.isDirectory }
                        ?.map { versionDir -> File(versionDir, CLAUDE_GLOBAL_PACKAGE_CLI_JS_RELATIVE_PATH) }
                        ?.forEach { candidates.add(it) }
                }
            }

            val cliJs = candidates.firstOrNull { it.isFile } ?: return emptyList()
            val content = cliJs.readText()

            val canonicalMatch = CLAUDE_CANONICAL_MODEL_OBJECT_REGEX.find(content)
            if (canonicalMatch != null) {
                return canonicalMatch.groupValues
                    .drop(1)
                    .map { it.lowercase() }
                    .distinct()
            }

            CLAUDE_MODEL_ID_REGEX.findAll(content)
                .map { it.value.lowercase() }
                .toList()
                .distinct()
        } catch (e: Exception) {
            logger.debug("Failed to parse Claude local package models: ${e.message}")
            emptyList()
        }
    }

    /**
     * 尝试解析 codex 支持的模型
     */
    private fun parseCodexModels(cliPath: String): List<String> {
        val modelListOutputs = listOfNotNull(
            runCommand(cliPath, listOf("models", "list"), acceptNonZeroExit = true),
            runCommand(cliPath, listOf("model", "list"), acceptNonZeroExit = true),
        )
        val helpOutputs = listOfNotNull(
            runCommand(cliPath, listOf("--help"), acceptNonZeroExit = true),
            runCommand(cliPath, listOf("exec", "--help"), acceptNonZeroExit = true),
        )

        return try {
            val extracted = linkedSetOf<String>()

            // 优先使用模型列表命令输出，其次使用本地 codex 数据目录中的缓存与配置。
            modelListOutputs.forEach { output ->
                extracted += extractCodexModels(output)
            }
            val codexHome = resolveCodexHomeDir()
            extracted += parseCodexModelsFromLocalCache(codexHome)
            extracted += parseCodexModelsFromConfig(codexHome)
            extracted += parseCodexModelsFromSessions(codexHome)

            // 命令 help 往往只含示例或文案（如 "codex-provided sandbox"），仅作为最后兜底。
            if (extracted.isEmpty()) {
                helpOutputs.forEach { output ->
                    extracted += extractCodexModels(output)
                }
            }

            extracted.toList()
        } catch (e: Exception) {
            logger.info("Failed to parse codex models: ${e.message}")
            emptyList()
        }
    }

    private fun parseClaudeSlashCommands(cliPath: String): List<CliSlashCommandInfo> {
        val helpOutput = runCommand(cliPath, listOf("--help"), acceptNonZeroExit = true)
            ?: return emptyList()
        return parseClaudeSlashCommandsFromHelp(helpOutput)
    }

    private fun parseCodexSlashCommands(cliPath: String): List<CliSlashCommandInfo> {
        val helpOutput = runCommand(cliPath, listOf("--help"), acceptNonZeroExit = true)
            ?: return emptyList()
        return parseCodexSlashCommandsFromHelp(helpOutput)
    }

    private fun extractCodexModels(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()
        val extracted = linkedSetOf<String>()
        CODEX_MODEL_REGEX.findAll(rawText).forEach { match ->
            val normalized = normalizeCodexModelId(match.value)
            if (isValidCodexModelId(normalized)) {
                extracted += normalized
            }
        }
        return extracted.toList()
    }

    private fun parseCodexModelsFromLocalCache(codexHome: File): List<String> {
        val cacheFile = File(codexHome, "models_cache.json")
        if (!cacheFile.isFile) return emptyList()
        return try {
            extractCodexModels(cacheFile.readText())
        } catch (e: Exception) {
            logger.debug("Failed to parse Codex models cache: ${e.message}")
            emptyList()
        }
    }

    private fun parseCodexModelsFromConfig(codexHome: File): List<String> {
        val configFile = File(codexHome, "config.toml")
        if (!configFile.isFile) return emptyList()

        return try {
            val extracted = linkedSetOf<String>()
            var inMigrationSection = false

            configFile.useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isBlank() || trimmed.startsWith("#")) {
                        return@forEach
                    }

                    if (trimmed.startsWith("[")) {
                        inMigrationSection = trimmed.equals("[notice.model_migrations]", ignoreCase = true)
                        return@forEach
                    }

                    CODEX_CONFIG_MODEL_ASSIGNMENT_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let { model ->
                        val normalized = normalizeCodexModelId(model)
                        if (isValidCodexModelId(normalized)) {
                            extracted += normalized
                        }
                    }

                    if (inMigrationSection) {
                        CODEX_CONFIG_MIGRATION_REGEX.find(trimmed)?.let { migration ->
                            val fromModel = normalizeCodexModelId(migration.groupValues[1])
                            val toModel = normalizeCodexModelId(migration.groupValues[2])
                            if (isValidCodexModelId(fromModel)) extracted += fromModel
                            if (isValidCodexModelId(toModel)) extracted += toModel
                        }
                    }
                }
            }

            extracted.toList()
        } catch (e: Exception) {
            logger.debug("Failed to parse Codex config models: ${e.message}")
            emptyList()
        }
    }

    private fun parseCodexModelsFromSessions(codexHome: File): List<String> {
        val sessionsDir = File(codexHome, "sessions")
        if (!sessionsDir.isDirectory) return emptyList()

        return try {
            val recentSessionFiles = sessionsDir.walkTopDown()
                .maxDepth(CODEX_SESSION_MAX_DEPTH)
                .filter { it.isFile && it.extension.equals("jsonl", ignoreCase = true) }
                .sortedByDescending { it.lastModified() }
                .take(CODEX_SESSION_FILES_TO_SCAN)
                .toList()
            if (recentSessionFiles.isEmpty()) return emptyList()

            val extracted = linkedSetOf<String>()
            recentSessionFiles.forEach { file ->
                file.useLines { lines ->
                    lines.take(CODEX_SESSION_LINES_PER_FILE).forEach { line ->
                        CODEX_SESSION_MODEL_REGEX.findAll(line).forEach { match ->
                            val normalized = normalizeCodexModelId(match.groupValues[1])
                            if (isValidCodexModelId(normalized)) {
                                extracted += normalized
                            }
                        }
                    }
                }
            }
            extracted.toList()
        } catch (e: Exception) {
            logger.debug("Failed to parse Codex session models: ${e.message}")
            emptyList()
        }
    }

    private fun resolveCodexHomeDir(): File {
        val envHome = normalizePathInput(effectiveEnvironment()["CODEX_HOME"].orEmpty())
        if (!envHome.isNullOrBlank()) {
            return File(envHome)
        }
        val userHome = System.getProperty("user.home").orEmpty()
        return File(userHome, ".codex")
    }

    private fun normalizeCodexModelId(value: String): String {
        return value.trim().trim('"', '\'', ',', ';').lowercase()
    }

    private fun isValidCodexModelId(modelId: String): Boolean {
        if (modelId.isBlank()) return false
        if (!CODEX_MODEL_REGEX.matches(modelId)) return false
        if (modelId in CODEX_MODEL_EXCLUDE_SET) return false
        return true
    }

    /**
     * 执行命令并返回 stdout，超时或失败返回 null。
     * Windows 上通过 cmd /c 执行以支持 .cmd/.bat 脚本。
     */
    private fun runCommand(
        executable: String,
        args: List<String>,
        timeoutSeconds: Long = 10,
        acceptNonZeroExit: Boolean = false,
    ): String? {
        return try {
            val directCommand = listOf(executable) + args
            logger.debug("Running command: ${directCommand.joinToString(" ")}")
            val builder = ProcessBuilder(directCommand)
                .redirectErrorStream(true)
            configureCommandEnvironment(builder, executable)
            var executedCommand = directCommand

            val process = try {
                builder.start()
            } catch (e: Exception) {
                if (!isWindows()) {
                    throw e
                }
                logger.debug("Direct command failed, fallback to cmd /c: ${e.message}")
                val fallbackCommand = listOf("cmd", "/c", executable) + args
                executedCommand = fallbackCommand
                val fallbackBuilder = ProcessBuilder(fallbackCommand)
                    .redirectErrorStream(true)
                configureCommandEnvironment(fallbackBuilder, executable)
                fallbackBuilder.start()
            }
            val outputFuture = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                logger.debug("Command timed out: ${executedCommand.joinToString(" ")}")
                val timedOutOutput = runCatching { outputFuture.get(1, TimeUnit.SECONDS) }.getOrNull().orEmpty()
                return if (acceptNonZeroExit && timedOutOutput.isNotBlank()) timedOutOutput else null
            }
            val output = runCatching { outputFuture.get(1, TimeUnit.SECONDS) }.getOrNull().orEmpty()
            if (process.exitValue() == 0 || (acceptNonZeroExit && output.isNotBlank())) {
                output
            } else {
                logger.debug("Command exited with ${process.exitValue()}: ${executedCommand.joinToString(" ")}")
                null
            }
        } catch (e: Exception) {
            logger.debug("Command failed: $executable ${args.joinToString(" ")}: ${e.message}")
            null
        }
    }

    private fun configureCommandEnvironment(builder: ProcessBuilder, executable: String) {
        ensureExecutableParentOnPath(builder, executable)
        if (isWindows() && executable.contains("claude", ignoreCase = true)) {
            val env = builder.environment()
            val configuredBash = env["CLAUDE_CODE_GIT_BASH_PATH"] ?: effectiveEnvironment()["CLAUDE_CODE_GIT_BASH_PATH"]
            if (configuredBash.isNullOrBlank() || !File(configuredBash).isFile) {
                findGitBashPath()?.let { env["CLAUDE_CODE_GIT_BASH_PATH"] = it }
            }
        }
    }

    private fun ensureExecutableParentOnPath(builder: ProcessBuilder, executable: String) {
        val normalizedExecutable = normalizePathInput(executable)
        val executableFile = File(normalizedExecutable)
        val parent = executableFile.parentFile ?: return
        if (!parent.isDirectory) return

        val env = builder.environment()
        val pathKey = resolveEnvKey(env, "PATH")
        val currentPath = env[pathKey].orEmpty()
        val normalizedParent = normalizePathForComparison(parent.path)
        val hasParentInPath = currentPath.split(File.pathSeparator)
            .asSequence()
            .map { normalizePathForComparison(normalizePathInput(it)) }
            .any { it == normalizedParent }
        if (hasParentInPath) return

        env[pathKey] = if (currentPath.isBlank()) {
            parent.path
        } else {
            parent.path + File.pathSeparator + currentPath
        }
    }

    private fun normalizePathForComparison(path: String): String {
        return if (isWindows()) {
            path.replace('\\', '/').trimEnd('/').lowercase()
        } else {
            path.replace('\\', '/').trimEnd('/')
        }
    }

    /**
     * 在 PATH 中查找可执行文件
     */
    private fun findInPath(name: String): String? {
        val pathEnv = envValue(effectiveEnvironment(), "PATH") ?: return null
        val extensions = if (isWindows()) {
            listOf(".cmd", ".exe", ".bat", "")
        } else {
            listOf("")
        }

        for (dir in pathEnv.split(File.pathSeparator)) {
            val normalizedDir = normalizePathInput(dir)
            if (normalizedDir.isBlank()) continue
            for (ext in extensions) {
                val file = File(normalizedDir, name + ext)
                if (isExecutable(file)) {
                    return file.absolutePath
                }
            }
        }
        return null
    }

    /**
     * 在常见安装位置查找 CLI 工具（npm 全局安装等）
     */
    private fun findInCommonLocations(toolName: String): String? {
        val userHome = System.getProperty("user.home")
        val env = effectiveEnvironment()
        val candidates = buildCommonLocationCandidates(
            toolName = toolName,
            userHome = userHome,
            env = env,
            isWindows = isWindows(),
            isMac = isMac(),
        ).toMutableList()
        candidates += findInNodeVersionManagers(toolName, userHome)

        return candidates.asSequence()
            .map(::File)
            .firstOrNull { isExecutable(it) }
            ?.absolutePath
    }

    private fun findInNodeVersionManagers(toolName: String, userHome: String?): List<String> {
        if (userHome.isNullOrBlank()) return emptyList()
        val candidates = mutableListOf<String>()

        val nvmVersionsDir = File(userHome, ".nvm/versions/node")
        if (nvmVersionsDir.isDirectory) {
            nvmVersionsDir.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.name }
                ?.map { versionDir -> File(versionDir, "bin/$toolName").path }
                ?.forEach { candidates += it }
        }

        val fnmVersionsDir = File(userHome, ".fnm/node-versions")
        if (fnmVersionsDir.isDirectory) {
            fnmVersionsDir.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.name }
                ?.map { versionDir -> File(versionDir, "installation/bin/$toolName").path }
                ?.forEach { candidates += it }
        }

        return candidates
    }

    private fun findInLoginShellPath(toolName: String): String? {
        if (isWindows()) return null
        if (!toolName.matches(Regex("^[A-Za-z0-9._-]+$"))) return null

        val shell = when {
            isMac() && File("/bin/zsh").isFile -> "/bin/zsh"
            File("/bin/bash").isFile -> "/bin/bash"
            else -> "/bin/sh"
        }

        return try {
            val process = ProcessBuilder(shell, "-lc", "command -v $toolName")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(4, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                return null
            }
            if (process.exitValue() != 0) return null
            process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { candidate ->
                        candidate.isNotBlank() &&
                            (candidate.startsWith("/") || candidate.contains(File.separator)) &&
                            File(candidate).isFile
                    }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizePathInput(value: String): String {
        val raw = value.trim().trim('"', '\'')
        if (raw.isBlank()) return ""
        val userHome = System.getProperty("user.home").orEmpty()
        if (raw == "~") return userHome
        if (raw.startsWith("~/") || raw.startsWith("~\\")) {
            return File(userHome, raw.substring(2)).path
        }
        return raw
    }

    private fun effectiveEnvironment(): Map<String, String> {
        val merged = linkedMapOf<String, String>()

        fun addEntry(key: String, value: String?) {
            if (value.isNullOrBlank()) return
            merged[key] = value
            merged[key.uppercase(Locale.ROOT)] = value
        }

        System.getenv().forEach { (key, value) -> addEntry(key, value) }
        runCatching { EnvironmentUtil.getEnvironmentMap() }
            .getOrNull()
            ?.forEach { (key, value) -> addEntry(key, value) }
        return merged
    }

    private fun envValue(env: Map<String, String>, key: String): String? {
        return env[key]
            ?: env[key.uppercase(Locale.ROOT)]
            ?: env.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
    }

    private fun resolveEnvKey(env: MutableMap<String, String>, key: String): String {
        return env.keys.firstOrNull { it.equals(key, ignoreCase = true) } ?: key
    }

    /**
     * 判断文件是否可执行。
     * Windows 上 .cmd/.bat 文件的 canExecute() 返回 false，需特殊处理。
     */
    private fun isExecutable(file: File): Boolean {
        if (!file.isFile) return false
        if (isWindows()) {
            val ext = file.extension.lowercase()
            if (ext in listOf("cmd", "bat")) return true
        }
        return file.canExecute()
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    private fun isMac(): Boolean =
        System.getProperty("os.name").lowercase().contains("mac")

    private fun findGitBashPath(): String? {
        val candidates = listOf(
            "C:\\Program Files\\Git\\bin\\bash.exe",
            "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
            "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
            "C:\\Program Files (x86)\\Git\\usr\\bin\\bash.exe",
        )
        return candidates.firstOrNull { File(it).isFile }
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        fun getInstance(): CliDiscoveryService = service()
        private const val CLAUDE_LOCAL_PACKAGE_CLI_JS_RELATIVE_PATH = "node_modules/@anthropic-ai/claude-code/cli.js"
        private const val CLAUDE_GLOBAL_PACKAGE_CLI_JS_RELATIVE_PATH = "lib/node_modules/@anthropic-ai/claude-code/cli.js"
        private val CLAUDE_MODEL_ID_REGEX = Regex("""\bclaude-[a-z0-9][a-z0-9.-]*\b""", RegexOption.IGNORE_CASE)
        private val CLAUDE_MODEL_ALIAS_REGEX = Regex("""\b(sonnet|opus|haiku)\b""", RegexOption.IGNORE_CASE)
        private val CLAUDE_CANONICAL_MODEL_OBJECT_REGEX = Regex(
            """\{opus:"(claude-[^"]+)",sonnet:"(claude-[^"]+)",haiku:"(claude-[^"]+)"\}""",
            RegexOption.IGNORE_CASE,
        )
        private val CODEX_MODEL_REGEX = Regex(
            """\b(o\d(?:-[a-z0-9][a-z0-9.-]*)?|gpt-[a-z0-9][a-z0-9.-]*|codex-[a-z0-9][a-z0-9.-]*)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val CODEX_CONFIG_MODEL_ASSIGNMENT_REGEX = Regex(
            "^model\\s*=\\s*\"([^\"]+)\"",
            RegexOption.IGNORE_CASE,
        )
        private val CODEX_CONFIG_MIGRATION_REGEX = Regex(
            "^\"([^\"]+)\"\\s*=\\s*\"([^\"]+)\"",
            RegexOption.IGNORE_CASE,
        )
        private val CODEX_SESSION_MODEL_REGEX = Regex(
            "\"model\"\\s*:\\s*\"([^\"]+)\"",
            RegexOption.IGNORE_CASE,
        )
        private val CODEX_MODEL_EXCLUDE_SET = setOf(
            "codex-provided",
        )
        private const val CODEX_SESSION_FILES_TO_SCAN = 12
        private const val CODEX_SESSION_LINES_PER_FILE = 2000
        private const val CODEX_SESSION_MAX_DEPTH = 8
        private const val CLAUDE_PROVIDER_ID = "claude-cli"
        private const val CODEX_PROVIDER_ID = "codex-cli"
        private const val MAX_SLASH_DESCRIPTION_LENGTH = 120
        private val HELP_COMMAND_LINE_REGEX = Regex("""^\s{2}([A-Za-z][A-Za-z0-9|_-]*)\s{2,}(.+)$""")
        private val SLASH_COMMAND_NAME_REGEX = Regex("""^[a-z0-9][a-z0-9-]*$""")
        private val CAMEL_CASE_BOUNDARY_REGEX = Regex("""([a-z0-9])([A-Z])""")
        private val MULTI_WHITESPACE_REGEX = Regex("""\s+""")

        internal fun parseClaudeSlashCommandsFromHelp(helpOutput: String): List<CliSlashCommandInfo> {
            if (helpOutput.isBlank()) return emptyList()

            val extracted = linkedMapOf<String, CliSlashCommandInfo>()
            var inCommandsSection = false
            helpOutput.lineSequence().forEach { rawLine ->
                val line = rawLine.trimEnd()
                val trimmed = line.trim()
                if (trimmed.equals("Commands:", ignoreCase = true)) {
                    inCommandsSection = true
                    return@forEach
                }
                if (!inCommandsSection) return@forEach
                if (trimmed.isBlank()) return@forEach
                if (trimmed.endsWith(":") && !trimmed.equals("Commands:", ignoreCase = true)) {
                    inCommandsSection = false
                    return@forEach
                }

                val commandMatch = HELP_COMMAND_LINE_REGEX.find(line) ?: return@forEach
                val command = normalizeSlashCommandToken(commandMatch.groupValues[1]) ?: return@forEach
                if (extracted.containsKey(command)) return@forEach
                extracted[command] = CliSlashCommandInfo(
                    providerId = CLAUDE_PROVIDER_ID,
                    command = command,
                    description = normalizeDescription(commandMatch.groupValues[2]),
                    invocationKind = CliSlashInvocationKind.COMMAND,
                )
            }
            return extracted.values.toList()
        }

        internal fun parseCodexSlashCommandsFromHelp(helpOutput: String): List<CliSlashCommandInfo> {
            if (helpOutput.isBlank()) return emptyList()

            val extracted = linkedMapOf<String, CliSlashCommandInfo>()
            var inCommandsSection = false
            helpOutput.lineSequence().forEach { rawLine ->
                val line = rawLine.trimEnd()
                val trimmed = line.trim()
                if (trimmed.equals("Commands:", ignoreCase = true)) {
                    inCommandsSection = true
                    return@forEach
                }
                if (!inCommandsSection) return@forEach
                if (trimmed.isBlank()) return@forEach
                if (trimmed.endsWith(":") && !trimmed.equals("Commands:", ignoreCase = true)) {
                    inCommandsSection = false
                    return@forEach
                }

                val commandMatch = HELP_COMMAND_LINE_REGEX.find(line) ?: return@forEach
                val command = normalizeSlashCommandToken(commandMatch.groupValues[1]) ?: return@forEach
                if (extracted.containsKey(command)) return@forEach
                extracted[command] = CliSlashCommandInfo(
                    providerId = CODEX_PROVIDER_ID,
                    command = command,
                    description = normalizeDescription(commandMatch.groupValues[2]),
                    invocationKind = CliSlashInvocationKind.COMMAND,
                )
            }
            return extracted.values.toList()
        }

        private fun normalizeSlashCommandToken(raw: String): String? {
            val candidate = raw
                .trim()
                .removePrefix("/")
                .substringBefore("|")
                .substringBefore(",")
                .trim()
            if (candidate.isBlank()) return null
            val kebab = CAMEL_CASE_BOUNDARY_REGEX
                .replace(candidate, "$1-$2")
                .lowercase(Locale.ROOT)
            return kebab.takeIf { SLASH_COMMAND_NAME_REGEX.matches(it) }
        }

        private fun normalizeDescription(raw: String): String {
            if (raw.isBlank()) return ""
            return MULTI_WHITESPACE_REGEX
                .replace(raw.trim(), " ")
                .take(MAX_SLASH_DESCRIPTION_LENGTH)
        }

        internal fun buildCommonLocationCandidates(
            toolName: String,
            userHome: String?,
            env: Map<String, String>,
            isWindows: Boolean,
            isMac: Boolean,
        ): List<String> {
            val candidates = mutableListOf<String>()
            val dedupe = linkedSetOf<String>()

            fun addPath(path: String?) {
                val raw = path?.trim()?.trim('"', '\'') ?: return
                if (raw.isBlank()) return
                val key = raw.replace('\\', '/').trimEnd('/').lowercase()
                if (key.isBlank()) return
                if (dedupe.add(key)) candidates.add(raw)
            }

            fun addUnder(base: String?, child: String) {
                val normalizedBase = base?.trim()?.trim('"', '\'') ?: return
                if (normalizedBase.isBlank()) return
                addPath(File(normalizedBase, child).path)
            }

            if (isWindows) {
                addUnder(env["APPDATA"], "npm/$toolName.cmd")
                addUnder(env["APPDATA"], "npm/$toolName.exe")
                addUnder(env["APPDATA"], "npm/$toolName.bat")
                addUnder(env["NVM_HOME"], "$toolName.cmd")
                addUnder(env["NVM_HOME"], "$toolName.exe")
                addUnder(userHome, "AppData/Roaming/npm/$toolName.cmd")
                addUnder(userHome, "AppData/Roaming/npm/$toolName.exe")
                addUnder(userHome, ".local/bin/$toolName.exe")
                addUnder(userHome, ".local/bin/$toolName.cmd")
                return candidates
            }

            val npmPrefix = env["NPM_CONFIG_PREFIX"] ?: env["PREFIX"]
            addUnder(env["NVM_BIN"], toolName)
            addUnder(env["PNPM_HOME"], toolName)
            addUnder(env["VOLTA_HOME"], "bin/$toolName")
            addUnder(npmPrefix, "bin/$toolName")

            addUnder(userHome, ".local/bin/$toolName")
            addUnder(userHome, ".npm-global/bin/$toolName")
            addUnder(userHome, ".yarn/bin/$toolName")
            addUnder(userHome, ".volta/bin/$toolName")
            addUnder(userHome, ".asdf/shims/$toolName")
            addUnder(userHome, ".fnm/current/bin/$toolName")
            addUnder(userHome, ".nvm/current/bin/$toolName")

            if (isMac) {
                addUnder(userHome, "Library/pnpm/$toolName")
                addPath("/opt/homebrew/bin/$toolName")
                addPath("/opt/local/bin/$toolName")
            }
            addPath("/usr/local/bin/$toolName")
            addPath("/usr/bin/$toolName")

            return candidates
        }

        internal fun buildClaudeCliPackageCandidates(
            cliPath: String,
            userHome: String?,
            env: Map<String, String>,
            isMac: Boolean,
        ): List<String> {
            val candidates = mutableListOf<String>()
            val dedupe = linkedSetOf<String>()

            fun addPath(path: String?) {
                val raw = path?.trim()?.trim('"', '\'') ?: return
                if (raw.isBlank()) return
                val key = raw.replace('\\', '/').trimEnd('/').lowercase()
                if (key.isBlank()) return
                if (dedupe.add(key)) candidates.add(raw)
            }

            fun addUnder(base: String?, child: String) {
                val normalizedBase = base?.trim()?.trim('"', '\'') ?: return
                if (normalizedBase.isBlank()) return
                addPath(File(normalizedBase, child).path)
            }

            val normalizedCliPath = cliPath.trim().trim('"', '\'')
            val cliExecutable = File(normalizedCliPath)
            val executableParent = cliExecutable.parentFile
            if (executableParent != null) {
                addPath(File(executableParent, CLAUDE_LOCAL_PACKAGE_CLI_JS_RELATIVE_PATH).path)
                executableParent.parentFile?.let { parent ->
                    addPath(File(parent, CLAUDE_GLOBAL_PACKAGE_CLI_JS_RELATIVE_PATH).path)
                    addPath(File(parent, CLAUDE_LOCAL_PACKAGE_CLI_JS_RELATIVE_PATH).path)
                }
            }

            addUnder(env["APPDATA"], "npm/$CLAUDE_LOCAL_PACKAGE_CLI_JS_RELATIVE_PATH")

            val npmPrefix = env["NPM_CONFIG_PREFIX"] ?: env["PREFIX"]
            addUnder(npmPrefix, CLAUDE_GLOBAL_PACKAGE_CLI_JS_RELATIVE_PATH)
            addUnder(userHome, ".npm-global/$CLAUDE_GLOBAL_PACKAGE_CLI_JS_RELATIVE_PATH")
            addUnder(userHome, ".local/$CLAUDE_GLOBAL_PACKAGE_CLI_JS_RELATIVE_PATH")

            val nvmBin = env["NVM_BIN"]?.trim()?.trim('"', '\'')
            if (!nvmBin.isNullOrBlank()) {
                val nvmBinDir = File(nvmBin)
                val nodeVersionDir = nvmBinDir.parentFile
                if (nodeVersionDir != null) {
                    addPath(File(nodeVersionDir, CLAUDE_GLOBAL_PACKAGE_CLI_JS_RELATIVE_PATH).path)
                }
            }

            val voltaHome = env["VOLTA_HOME"]
            addUnder(
                voltaHome,
                "tools/image/packages/@anthropic-ai/claude-code/$CLAUDE_GLOBAL_PACKAGE_CLI_JS_RELATIVE_PATH",
            )

            addPath("/usr/local/$CLAUDE_GLOBAL_PACKAGE_CLI_JS_RELATIVE_PATH")
            if (isMac) {
                addPath("/opt/homebrew/$CLAUDE_GLOBAL_PACKAGE_CLI_JS_RELATIVE_PATH")
                addPath("/opt/local/$CLAUDE_GLOBAL_PACKAGE_CLI_JS_RELATIVE_PATH")
            }

            return candidates
        }
    }
}
