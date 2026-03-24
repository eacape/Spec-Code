package com.eacape.speccodingplugin.mcp

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP 客户端
 * 实现 JSON-RPC over stdio 协议
 */
class McpClient(
    private val server: McpServer,
    private val scope: CoroutineScope
) {
    private val logger = thisLogger()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // 进程 IO
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var errorReader: BufferedReader? = null
    private var stderrJob: Job? = null
    private var processMonitorJob: Job? = null

    // 请求-响应映射
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>()

    // 通知通道
    private val notificationChannel = Channel<JsonRpcNotification>(Channel.UNLIMITED)

    // 是否已初始化
    @Volatile
    private var initialized = false

    private val stderrTail = ArrayDeque<String>()
    private val stderrTailLock = Any()
    private val terminationHandled = AtomicBoolean(false)
    @Volatile
    private var stopRequested = false
    @Volatile
    private var runtimeLogListener: ((McpRuntimeLogEvent) -> Unit)? = null
    @Volatile
    private var lifecycleListener: ((McpClientUnexpectedTermination) -> Unit)? = null

    /**
     * 启动 MCP Server
     */
    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            logger.info("Starting MCP server: ${server.config.name}")
            stopRequested = false
            terminationHandled.set(false)

            val launchCommand = resolveLaunchCommand(
                command = server.config.command,
                args = server.config.args,
            )
            emitRuntimeLog(
                level = McpRuntimeLogLevel.INFO,
                message = "Launch command: ${formatCommandForLog(launchCommand)}",
            )

            // 构建进程
            val processBuilder = ProcessBuilder(launchCommand)

            // 设置环境变量
            if (server.config.env.isNotEmpty()) {
                processBuilder.environment().putAll(server.config.env)
            }

            // 重定向错误流
            processBuilder.redirectErrorStream(false)

            // 启动进程
            val process = try {
                processBuilder.start()
            } catch (error: Exception) {
                emitRuntimeLog(
                    level = McpRuntimeLogLevel.ERROR,
                    message = buildLaunchFailureMessage(
                        attemptedCommand = launchCommand.firstOrNull().orEmpty(),
                        error = error,
                    ),
                )
                throw IllegalStateException(
                    buildLaunchFailureMessage(
                        attemptedCommand = launchCommand.firstOrNull().orEmpty(),
                        error = error,
                    ),
                    error,
                )
            }
            server.process = process
            server.status = ServerStatus.STARTING
            val pidText = runCatching { process.pid().toString() }.getOrNull()
            emitRuntimeLog(
                level = McpRuntimeLogLevel.INFO,
                message = if (pidText != null) "Process started (pid=$pidText)" else "Process started",
            )

            // 设置 IO
            writer = BufferedWriter(OutputStreamWriter(process.outputStream))
            reader = BufferedReader(InputStreamReader(process.inputStream))
            errorReader = BufferedReader(InputStreamReader(process.errorStream))

            stderrJob = scope.launch(Dispatchers.IO) {
                consumeStderr()
            }
            processMonitorJob = scope.launch(Dispatchers.IO) {
                monitorProcess(process)
            }

            // 启动读取协程
            scope.launch {
                readLoop()
            }

            try {
                // 初始化握手
                emitRuntimeLog(McpRuntimeLogLevel.INFO, "Waiting for initialize response...")
                initialize()
            } catch (error: Exception) {
                throw enrichStartupError(error, process)
            }

            server.status = ServerStatus.RUNNING
            emitRuntimeLog(McpRuntimeLogLevel.INFO, "Server is running")
            logger.info("MCP server started: ${server.config.name}")
        }
    }

    /**
     * 停止 MCP Server
     */
    fun stop() {
        logger.info("Stopping MCP server: ${server.config.name}")
        emitRuntimeLog(McpRuntimeLogLevel.INFO, "Stopping server process")

        val process = server.process
        val errorReaderToClose = errorReader
        val writerToClose = writer
        val readerToClose = reader
        val stderrJobToCancel = stderrJob
        val processMonitorJobToCancel = processMonitorJob
        stopRequested = true
        terminationHandled.set(true)
        errorReader = null
        writer = null
        reader = null
        stderrJob = null
        processMonitorJob = null
        try {
            stderrJobToCancel?.cancel()
            processMonitorJobToCancel?.cancel()
            if (process != null) {
                runCatching { process.destroy() }
                if (process.isAlive) {
                    emitRuntimeLog(
                        McpRuntimeLogLevel.WARN,
                        "Process still alive after destroy, forcing termination",
                    )
                    runCatching { process.destroyForcibly() }
                }
            }
        } catch (e: Exception) {
            logger.warn("Error stopping MCP server", e)
        } finally {
            // Stream closing can block on some Windows process states; do it asynchronously.
            scope.launch(Dispatchers.IO) {
                runCatching { errorReaderToClose?.close() }
                runCatching { writerToClose?.close() }
                runCatching { readerToClose?.close() }
            }
        }

        server.status = ServerStatus.STOPPED
        server.process = null
        initialized = false

        failPendingRequests(CancellationException("MCP server stopped"))

        emitRuntimeLog(McpRuntimeLogLevel.INFO, "Server stopped")
        logger.info("MCP server stopped: ${server.config.name}")
    }

    /**
     * 初始化握手
     */
    private suspend fun initialize() {
        val params = InitializeParams(
            protocolVersion = McpProtocol.VERSION,
            capabilities = ClientCapabilities(),
            clientInfo = ClientInfo(
                name = McpProtocol.CLIENT_NAME,
                version = McpProtocol.CLIENT_VERSION
            )
        )

        val response = sendRequest(McpMethods.INITIALIZE, json.encodeToJsonElement(
            InitializeParams.serializer(),
            params
        ))

        if (response.error != null) {
            throw Exception("Initialize failed: ${response.error.message}")
        }

        val result = json.decodeFromJsonElement(
            InitializeResult.serializer(),
            response.result!!
        )

        server.capabilities = result.capabilities
        initialized = true

        // 发送 initialized 通知
        sendNotification(McpMethods.INITIALIZED, null)

        emitRuntimeLog(
            level = McpRuntimeLogLevel.INFO,
            message = "Initialize succeeded: ${result.serverInfo.name} ${result.serverInfo.version}",
        )
        logger.info("MCP server initialized: ${result.serverInfo.name} ${result.serverInfo.version}")
    }

    /**
     * 列出工具
     */
    suspend fun listTools(): Result<List<McpTool>> = runCatching {
        checkInitialized()
        emitRuntimeLog(McpRuntimeLogLevel.INFO, "Requesting tools/list...")

        val response = sendRequest(McpMethods.TOOLS_LIST, buildJsonObject { })

        if (response.error != null) {
            throw Exception("List tools failed: ${response.error.message}")
        }

        val result = json.decodeFromJsonElement(
            ToolsListResult.serializer(),
            response.result!!
        )

        emitRuntimeLog(McpRuntimeLogLevel.INFO, "tools/list returned ${result.tools.size} tool(s)")
        result.tools
    }

    /**
     * 调用工具
     */
    suspend fun callTool(toolName: String, arguments: Map<String, Any>): Result<ToolCallResult> = runCatching {
        checkInitialized()

        val params = ToolsCallParams(
            name = toolName,
            arguments = json.parseToJsonElement(json.encodeToString(arguments))
        )

        val response = sendRequest(
            McpMethods.TOOLS_CALL,
            json.encodeToJsonElement(ToolsCallParams.serializer(), params)
        )

        if (response.error != null) {
            return@runCatching ToolCallResult.Error(
                code = response.error.code,
                message = response.error.message,
                data = response.error.data
            )
        }

        val result = json.decodeFromJsonElement(
            ToolsCallResult.serializer(),
            response.result!!
        )

        ToolCallResult.Success(
            content = result.content,
            isError = result.isError ?: false
        )
    }

    /**
     * 发送请求
     */
    private suspend fun sendRequest(method: String, params: JsonElement?): JsonRpcResponse {
        val requestId = generateRequestId()
        val request = JsonRpcRequest(
            id = requestId,
            method = method,
            params = params
        )

        val deferred = CompletableDeferred<JsonRpcResponse>()
        pendingRequests[requestId] = deferred

        // 发送请求
        val requestJson = json.encodeToString(request)
        withContext(Dispatchers.IO) {
            writer?.write(requestJson)
            writer?.newLine()
            writer?.flush()
        }

        logger.debug("Sent request: $method (id: $requestId)")

        // 等待响应（超时 30 秒）
        return withTimeout(30_000) {
            deferred.await()
        }
    }

    /**
     * 发送通知
     */
    private suspend fun sendNotification(method: String, params: JsonElement?) {
        val notification = JsonRpcNotification(
            method = method,
            params = params
        )

        val notificationJson = json.encodeToString(notification)
        withContext(Dispatchers.IO) {
            writer?.write(notificationJson)
            writer?.newLine()
            writer?.flush()
        }

        logger.debug("Sent notification: $method")
    }

    /**
     * 读取循环
     */
    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        try {
            while (true) {
                val line = reader?.readLine() ?: break

                if (line.isBlank()) continue

                try {
                    handleMessage(line)
                } catch (e: Exception) {
                    logger.warn("Error handling message: $line", e)
                }
            }
        } catch (e: Exception) {
            if (stopRequested) {
                return@withContext
            }
            logger.warn("Read loop error", e)
            reportUnexpectedTermination(
                baseMessage = "Read loop error: ${e.message ?: e::class.java.simpleName}",
                exitCode = runCatching { server.process?.exitValue() }.getOrNull(),
            )
        }
    }

    private suspend fun monitorProcess(process: Process) = withContext(Dispatchers.IO) {
        val exitCode = runCatching { process.waitFor() }.getOrNull() ?: return@withContext
        if (!stopRequested) {
            reportUnexpectedTermination(
                baseMessage = "Server process exited unexpectedly",
                exitCode = exitCode,
            )
        }
    }

    private suspend fun consumeStderr() = withContext(Dispatchers.IO) {
        try {
            while (true) {
                val line = errorReader?.readLine() ?: break
                val normalized = line.trim()
                if (normalized.isNotEmpty()) {
                    appendStderrLine(normalized)
                    emitRuntimeLog(McpRuntimeLogLevel.STDERR, normalized)
                    logger.debug("MCP stderr [${server.config.id}]: $normalized")
                }
            }
        } catch (_: Exception) {
            // ignore: process shutdown/stream closed
        }
    }

    private fun appendStderrLine(line: String) {
        synchronized(stderrTailLock) {
            if (stderrTail.size >= STDERR_TAIL_MAX_LINES) {
                stderrTail.removeFirst()
            }
            stderrTail.addLast(line)
        }
    }

    private fun latestStderrSummary(): String {
        val snapshot = synchronized(stderrTailLock) { stderrTail.toList() }
        if (snapshot.isEmpty()) return ""
        return snapshot.joinToString(" | ")
            .take(STDERR_TAIL_MAX_CHARS)
    }

    private fun resolveLaunchCommand(command: String, args: List<String>): List<String> {
        val normalized = command.trim()
        if (!isWindows()) {
            return listOf(normalized) + args
        }
        val resolved = resolveWindowsCommand(normalized) ?: normalized
        return listOf(resolved) + args
    }

    private fun resolveWindowsCommand(command: String): String? {
        if (command.isBlank()) return null

        val path = runCatching { Paths.get(command) }.getOrNull()
        val hasPathSeparator = command.contains('\\') || command.contains('/')
        val hasExtension = path?.fileName?.toString()?.contains('.') == true
        if (hasPathSeparator) {
            if (path != null && Files.isRegularFile(path)) {
                return path.toString()
            }
            if (!hasExtension) {
                WINDOWS_EXEC_EXTENSIONS.forEach { extension ->
                    val candidate = Paths.get("$command$extension")
                    if (Files.isRegularFile(candidate)) {
                        return candidate.toString()
                    }
                }
            }
            return null
        }

        val pathEntries = (System.getenv("PATH") ?: "")
            .split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val commandVariants = if (hasExtension) {
            listOf(command)
        } else {
            WINDOWS_EXEC_EXTENSIONS.map { ext -> "$command$ext" }
        }
        pathEntries.forEach { directory ->
            commandVariants.forEach { variant ->
                val candidate = Paths.get(directory, variant)
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString()
                }
            }
        }

        if (command.equals("npx", ignoreCase = true) || command.equals("npm", ignoreCase = true)) {
            preferredNodeCommandCandidates(command).forEach { candidate ->
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString()
                }
            }
        }

        return null
    }

    private fun preferredNodeCommandCandidates(command: String): List<Path> {
        val commandCmd = "$command.cmd"
        val result = mutableListOf<Path>()
        val programFiles = System.getenv("ProgramFiles")?.trim().orEmpty()
        val programFilesX86 = System.getenv("ProgramFiles(x86)")?.trim().orEmpty()
        val appData = System.getenv("APPDATA")?.trim().orEmpty()
        if (programFiles.isNotBlank()) {
            result.add(Paths.get(programFiles, "nodejs", commandCmd))
        }
        if (programFilesX86.isNotBlank()) {
            result.add(Paths.get(programFilesX86, "nodejs", commandCmd))
        }
        if (appData.isNotBlank()) {
            result.add(Paths.get(appData, "npm", commandCmd))
        }
        return result
    }

    private fun enrichStartupError(error: Throwable, process: Process): Exception {
        val base = error.message?.trim().orEmpty().ifBlank { "Unknown startup error" }
        val exitCode = runCatching { process.exitValue() }.getOrNull()
        val stderrSummary = latestStderrSummary()
        val message = buildString {
            append(base)
            if (exitCode != null) {
                append(" (exit=")
                append(exitCode)
                append(")")
            }
            if (stderrSummary.isNotBlank()) {
                append("; stderr: ")
                append(stderrSummary)
            }
        }
        emitRuntimeLog(McpRuntimeLogLevel.ERROR, "Startup failed: $message")
        return IllegalStateException(message, error)
    }

    private fun buildLaunchFailureMessage(attemptedCommand: String, error: Throwable): String {
        val base = error.message?.trim().orEmpty().ifBlank { "Process launch failed" }
        if (!isWindows()) return base
        val commandName = server.config.command.trim().lowercase()
        val commandNotFound = base.contains("CreateProcess error=2", ignoreCase = true)
        if (commandNotFound && (commandName == "npx" || commandName == "npm")) {
            return "$base; on Windows this command is usually a .cmd wrapper. " +
                "Try setting command to '${commandName}.cmd' or install global binary and use it directly."
        }
        if (commandNotFound) {
            return "$base; attempted command: $attemptedCommand"
        }
        return base
    }

    private fun isWindows(): Boolean {
        val os = System.getProperty("os.name") ?: return false
        return os.startsWith("Windows", ignoreCase = true)
    }

    /**
     * 处理消息
     */
    private fun handleMessage(message: String) {
        logger.debug("Received message: $message")

        // 尝试解析为响应
        try {
            val response = json.decodeFromString<JsonRpcResponse>(message)
            val deferred = pendingRequests.remove(response.id)
            deferred?.complete(response)
            return
        } catch (e: Exception) {
            // 不是响应，继续尝试通知
        }

        // 尝试解析为通知
        try {
            val notification = json.decodeFromString<JsonRpcNotification>(message)
            notificationChannel.trySend(notification)
        } catch (e: Exception) {
            logger.warn("Failed to parse message: $message", e)
        }
    }

    /**
     * 获取通知流
     */
    fun getNotifications(): Flow<JsonRpcNotification> = flow {
        for (notification in notificationChannel) {
            emit(notification)
        }
    }

    /**
     * 检查是否已初始化
     */
    private fun checkInitialized() {
        if (!initialized) {
            throw IllegalStateException("MCP client not initialized")
        }
    }

    /**
     * 生成请求 ID
     */
    private fun generateRequestId(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * 检查 Server 是否运行
     */
    fun isRunning(): Boolean {
        return server.status == ServerStatus.RUNNING && initialized
    }

    fun setRuntimeLogListener(listener: (McpRuntimeLogEvent) -> Unit) {
        runtimeLogListener = listener
    }

    fun setLifecycleListener(listener: (McpClientUnexpectedTermination) -> Unit) {
        lifecycleListener = listener
    }

    private fun reportUnexpectedTermination(baseMessage: String, exitCode: Int?) {
        if (!terminationHandled.compareAndSet(false, true)) return
        initialized = false
        server.process = null
        server.status = ServerStatus.ERROR
        val stderrSummary = latestStderrSummary()
        val message = buildTerminationMessage(baseMessage, exitCode, stderrSummary)
        server.error = message
        failPendingRequests(IllegalStateException(message))
        emitRuntimeLog(McpRuntimeLogLevel.ERROR, message)
        runCatching {
            lifecycleListener?.invoke(
                McpClientUnexpectedTermination(
                    message = message,
                    exitCode = exitCode,
                )
            )
        }.onFailure { error ->
            logger.debug("Failed to emit MCP lifecycle event", error)
        }
    }

    private fun buildTerminationMessage(
        baseMessage: String,
        exitCode: Int?,
        stderrSummary: String,
    ): String {
        return buildString {
            append(baseMessage)
            if (exitCode != null) {
                append(" (exit=")
                append(exitCode)
                append(')')
            }
            if (stderrSummary.isNotBlank()) {
                append("; stderr: ")
                append(stderrSummary)
            }
        }
    }

    private fun failPendingRequests(cause: Throwable) {
        pendingRequests.values.forEach { deferred ->
            deferred.completeExceptionally(cause)
        }
        pendingRequests.clear()
    }

    private fun emitRuntimeLog(level: McpRuntimeLogLevel, message: String) {
        val normalizedMessage = message.trim().replace('\n', ' ')
        if (normalizedMessage.isEmpty()) return
        val event = McpRuntimeLogEvent(
            level = level,
            message = normalizedMessage.take(RUNTIME_LOG_MAX_CHARS),
        )
        runCatching {
            runtimeLogListener?.invoke(event)
        }.onFailure { error ->
            logger.debug("Failed to emit MCP runtime log", error)
        }
    }

    private fun formatCommandForLog(command: List<String>): String {
        return command.joinToString(" ") { token ->
            if (token.contains(' ')) "\"$token\"" else token
        }
    }

    companion object {
        private const val STDERR_TAIL_MAX_LINES = 8
        private const val STDERR_TAIL_MAX_CHARS = 420
        private const val RUNTIME_LOG_MAX_CHARS = 600
        private val WINDOWS_EXEC_EXTENSIONS = listOf(".cmd", ".bat", ".exe", ".com")
    }
}
