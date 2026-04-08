package com.eacape.speccodingplugin.mcp

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MCP client implementing JSON-RPC over stdio.
 */
class McpClient private constructor(
    private val processRuntime: McpServerProcessRuntime,
    private val server: McpServer,
    private val scope: CoroutineScope,
) {
    private val logger = thisLogger()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var errorReader: BufferedReader? = null
    private var stderrJob: Job? = null
    private var processMonitorJob: Job? = null

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>()
    private val notificationChannel = Channel<JsonRpcNotification>(Channel.UNLIMITED)

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

    constructor(server: McpServer, scope: CoroutineScope) : this(
        McpServerProcessRuntime(),
        server,
        scope,
    )

    internal constructor(
        server: McpServer,
        scope: CoroutineScope,
        processRuntime: McpServerProcessRuntime,
        testOnly: Boolean,
    ) : this(
        processRuntime,
        server,
        scope,
    ) {
        check(testOnly)
    }

    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            logger.info("Starting MCP server: ${server.config.name}")
            stopRequested = false
            terminationHandled.set(false)

            val launchCommand = processRuntime.prepareLaunchCommand(server.config)
            emitRuntimeLog(
                level = McpRuntimeLogLevel.INFO,
                message = "Launch command: ${formatCommandForLog(launchCommand)}",
            )

            val process = try {
                processRuntime.start(
                    config = server.config,
                    launchCommand = launchCommand,
                )
            } catch (error: McpProcessLaunchException) {
                emitRuntimeLog(
                    level = McpRuntimeLogLevel.ERROR,
                    message = error.diagnostic.renderMessage(),
                )
                throw error
            }
            server.process = process
            server.status = ServerStatus.STARTING
            val pidText = runCatching { process.pid().toString() }.getOrNull()
            emitRuntimeLog(
                level = McpRuntimeLogLevel.INFO,
                message = if (pidText != null) "Process started (pid=$pidText)" else "Process started",
            )

            writer = BufferedWriter(OutputStreamWriter(process.outputStream))
            reader = BufferedReader(InputStreamReader(process.inputStream))
            errorReader = BufferedReader(InputStreamReader(process.errorStream))

            stderrJob = scope.launch(Dispatchers.IO) {
                consumeStderr()
            }
            processMonitorJob = scope.launch(Dispatchers.IO) {
                monitorProcess(process)
            }

            scope.launch {
                readLoop()
            }

            try {
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

    private suspend fun initialize() {
        val params = InitializeParams(
            protocolVersion = McpProtocol.VERSION,
            capabilities = ClientCapabilities(),
            clientInfo = ClientInfo(
                name = McpProtocol.CLIENT_NAME,
                version = McpProtocol.CLIENT_VERSION,
            ),
        )

        val response = sendRequest(
            McpMethods.INITIALIZE,
            json.encodeToJsonElement(InitializeParams.serializer(), params),
        )

        if (response.error != null) {
            throw Exception("Initialize failed: ${response.error.message}")
        }

        val result = json.decodeFromJsonElement(
            InitializeResult.serializer(),
            response.result!!,
        )

        server.capabilities = result.capabilities
        initialized = true

        sendNotification(McpMethods.INITIALIZED, null)

        emitRuntimeLog(
            level = McpRuntimeLogLevel.INFO,
            message = "Initialize succeeded: ${result.serverInfo.name} ${result.serverInfo.version}",
        )
        logger.info("MCP server initialized: ${result.serverInfo.name} ${result.serverInfo.version}")
    }

    suspend fun listTools(): Result<List<McpTool>> = runCatching {
        checkInitialized()
        emitRuntimeLog(McpRuntimeLogLevel.INFO, "Requesting tools/list...")

        val response = sendRequest(McpMethods.TOOLS_LIST, buildJsonObject { })

        if (response.error != null) {
            throw Exception("List tools failed: ${response.error.message}")
        }

        val result = json.decodeFromJsonElement(
            ToolsListResult.serializer(),
            response.result!!,
        )

        emitRuntimeLog(McpRuntimeLogLevel.INFO, "tools/list returned ${result.tools.size} tool(s)")
        result.tools
    }

    suspend fun callTool(toolName: String, arguments: Map<String, Any>): Result<ToolCallResult> = runCatching {
        checkInitialized()

        val params = ToolsCallParams(
            name = toolName,
            arguments = json.parseToJsonElement(json.encodeToString(arguments)),
        )

        val response = sendRequest(
            McpMethods.TOOLS_CALL,
            json.encodeToJsonElement(ToolsCallParams.serializer(), params),
        )

        if (response.error != null) {
            return@runCatching ToolCallResult.Error(
                code = response.error.code,
                message = response.error.message,
                data = response.error.data,
            )
        }

        val result = json.decodeFromJsonElement(
            ToolsCallResult.serializer(),
            response.result!!,
        )

        ToolCallResult.Success(
            content = result.content,
            isError = result.isError ?: false,
        )
    }

    private suspend fun sendRequest(method: String, params: JsonElement?): JsonRpcResponse {
        val requestId = generateRequestId()
        val request = JsonRpcRequest(
            id = requestId,
            method = method,
            params = params,
        )

        val deferred = CompletableDeferred<JsonRpcResponse>()
        pendingRequests[requestId] = deferred

        val requestJson = json.encodeToString(request)
        withContext(Dispatchers.IO) {
            writer?.write(requestJson)
            writer?.newLine()
            writer?.flush()
        }

        logger.debug("Sent request: $method (id: $requestId)")

        return withTimeout(30_000) {
            deferred.await()
        }
    }

    private suspend fun sendNotification(method: String, params: JsonElement?) {
        val notification = JsonRpcNotification(
            method = method,
            params = params,
        )

        val notificationJson = json.encodeToString(notification)
        withContext(Dispatchers.IO) {
            writer?.write(notificationJson)
            writer?.newLine()
            writer?.flush()
        }

        logger.debug("Sent notification: $method")
    }

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
            // Ignore shutdown and stream-close races.
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
        return snapshot.joinToString(" | ").take(STDERR_TAIL_MAX_CHARS)
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
                append(')')
            }
            if (stderrSummary.isNotBlank()) {
                append("; stderr: ")
                append(stderrSummary)
            }
        }
        emitRuntimeLog(McpRuntimeLogLevel.ERROR, "Startup failed: $message")
        return IllegalStateException(message, error)
    }

    private fun handleMessage(message: String) {
        logger.debug("Received message: $message")

        try {
            val response = json.decodeFromString<JsonRpcResponse>(message)
            val deferred = pendingRequests.remove(response.id)
            deferred?.complete(response)
            return
        } catch (_: Exception) {
            // Not a response, continue as notification.
        }

        try {
            val notification = json.decodeFromString<JsonRpcNotification>(message)
            notificationChannel.trySend(notification)
        } catch (e: Exception) {
            logger.warn("Failed to parse message: $message", e)
        }
    }

    fun getNotifications(): Flow<JsonRpcNotification> = flow {
        for (notification in notificationChannel) {
            emit(notification)
        }
    }

    private fun checkInitialized() {
        if (!initialized) {
            throw IllegalStateException("MCP client not initialized")
        }
    }

    private fun generateRequestId(): String {
        return UUID.randomUUID().toString()
    }

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
                ),
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

    }
}
