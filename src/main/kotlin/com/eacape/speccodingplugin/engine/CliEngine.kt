package com.eacape.speccodingplugin.engine

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CLI 引擎基类
 * 封装通过命令行调用外部 AI 工具的通用逻辑
 */
abstract class CliEngine(
    override val id: String,
    override val displayName: String,
    override val capabilities: Set<EngineCapability>,
    val cliPath: String,
) : CodeGenerationEngine {

    private val logger = thisLogger()
    private val commandRuntime = CliCommandRuntime()
    private val streamingProcessLifecycle = CliStreamingProcessLifecycle(
        logInfo = { message -> logger.info(message) },
    )
    private val activeProcesses = ConcurrentHashMap<String, Process>()

    @Volatile
    private var _status: EngineStatus = EngineStatus.STOPPED

    override val status: EngineStatus get() = _status

    override suspend fun start() {
        _status = EngineStatus.STARTING
        val result = healthCheck()
        _status = if (result.healthy) EngineStatus.RUNNING else EngineStatus.ERROR
    }

    override suspend fun stop() {
        _status = EngineStatus.STOPPED
    }

    override suspend fun healthCheck(): EngineHealthResult {
        return withContext(Dispatchers.IO) {
            try {
                // 尝试直接执行获取版本号来验证 CLI 可用性
                // 不再依赖 Files.exists() 检查，因为裸命令名或 .cmd 文件可能无法通过路径检查
                val version = getVersion()
                if (version != null) {
                    EngineHealthResult(
                        healthy = true,
                        status = EngineStatus.RUNNING,
                        message = "OK",
                        version = version
                    )
                } else {
                    EngineHealthResult(
                        healthy = false,
                        status = EngineStatus.ERROR,
                        message = "CLI not available: $cliPath"
                    )
                }
            } catch (e: Exception) {
                EngineHealthResult(
                    healthy = false,
                    status = EngineStatus.ERROR,
                    message = e.message ?: "Health check failed"
                )
            }
        }
    }

    override suspend fun generate(
        request: EngineRequest
    ): EngineResponse = withContext(Dispatchers.IO) {
        val requestId = request.options["requestId"] ?: System.nanoTime().toString()
        val timeoutSeconds = resolveTimeoutSeconds(request)
        try {
            val args = buildCommandArgs(request)
            val process = startProcess(
                args = args,
                workingDir = request.context.workingDirectory,
                environmentOverrides = environmentOverrides(request),
            )
            activeProcesses[requestId] = process
            val (timedOut, watchdogFuture) = streamingProcessLifecycle.startTimeoutWatchdog(process, timeoutSeconds)
            try {
                writeRequestInput(process, request)
                val stderrFuture = CompletableFuture.supplyAsync {
                    process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                }
                val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val exitCode = process.waitFor()
                val stderr = sanitizeProcessError(
                    runCatching { stderrFuture.get(1, TimeUnit.SECONDS) }.getOrNull().orEmpty()
                )
                if (timedOut.get()) {
                    return@withContext EngineResponse(
                        content = "",
                        engineId = id,
                        success = false,
                        error = resolveProcessFailureMessage(
                            stderr = stderr,
                            stdout = output,
                            fallback = streamingProcessLifecycle.timeoutMessage(timeoutSeconds),
                        ),
                    )
                }

                if (exitCode == 0) {
                    EngineResponse(
                        content = output,
                        engineId = id,
                        success = true
                    )
                } else {
                    EngineResponse(
                        content = "",
                        engineId = id,
                        success = false,
                        error = resolveProcessFailureMessage(
                            stderr = stderr,
                            stdout = output,
                            fallback = "Exit code: $exitCode",
                        ),
                    )
                }
            } finally {
                watchdogFuture?.cancel(true)
                activeProcesses.remove(requestId)
            }
        } catch (e: Exception) {
            EngineResponse(
                content = "",
                engineId = id,
                success = false,
                error = e.message
            )
        }
    }

    override fun stream(request: EngineRequest): Flow<EngineChunk> = flow {
        val requestId = request.options["requestId"] ?: System.nanoTime().toString()
        val timeoutSeconds = resolveTimeoutSeconds(request)
        val inactivityTimeoutMillis = streamInactivityTimeoutMillis(request)?.takeIf { it > 0 }
        val args = buildStreamCommandArgs(request)
        val process = startProcess(
            args = args,
            workingDir = request.context.workingDirectory,
            environmentOverrides = environmentOverrides(request),
        )
        activeProcesses[requestId] = process
        val (timedOut, watchdogFuture) = streamingProcessLifecycle.startTimeoutWatchdog(process, timeoutSeconds)
        try {
            writeRequestInput(process, request)
            var emittedChunk = false
            var sawStdoutData = false
            var terminatedAfterStreamDrain = false
            var terminatedAfterInactivity = false
            var inactivityTerminationAttempted = false
            val frames: BlockingQueue<StreamFrame> = LinkedBlockingQueue()
            val stdoutDone = AtomicBoolean(false)
            val stderrDone = AtomicBoolean(false)
            val stderrText = StringBuilder()
            val stdoutText = StringBuilder()
            var lastFrameAtNanos = System.nanoTime()

            val stdoutPump = startLinePump(
                stream = process.inputStream,
                source = StreamSource.STDOUT,
                queue = frames,
                done = stdoutDone,
                stderrCollector = null,
            )
            val stderrPump = startLinePump(
                stream = process.errorStream,
                source = StreamSource.STDERR,
                queue = frames,
                done = stderrDone,
                stderrCollector = stderrText,
            )

            while (true) {
                currentCoroutineContext().ensureActive()
                val frame = frames.poll(STREAM_POLL_MILLIS, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    lastFrameAtNanos = System.nanoTime()
                    val parsed = when (frame.source) {
                        StreamSource.STDOUT -> parseStreamLine(
                            if (frame.hasLineBreak) frame.line + "\n" else frame.line
                        )
                        StreamSource.STDERR -> parseProgressLine(frame.line)
                    }
                    if (frame.source == StreamSource.STDOUT && frame.line.isNotBlank()) {
                        sawStdoutData = true
                        appendDiagnostic(stdoutText, frame.line)
                    }
                    if (parsed != null) {
                        emittedChunk = true
                        emit(parsed)
                    }
                } else if (
                    inactivityTimeoutMillis != null &&
                    !inactivityTerminationAttempted &&
                    sawStdoutData &&
                    process.isAlive &&
                    frames.isEmpty() &&
                    (System.nanoTime() - lastFrameAtNanos) >= TimeUnit.MILLISECONDS.toNanos(inactivityTimeoutMillis)
                ) {
                    inactivityTerminationAttempted = true
                    terminatedAfterInactivity = streamingProcessLifecycle.terminateAfterInactivity(
                        process = process,
                        requestId = requestId,
                        inactivityTimeoutMillis = inactivityTimeoutMillis,
                    )
                }

                val streamDrained = stdoutDone.get() && stderrDone.get() && frames.isEmpty()
                val terminatedAfterIdleAndFlushed = terminatedAfterInactivity && !process.isAlive && frames.isEmpty()
                if (streamDrained || terminatedAfterIdleAndFlushed) {
                    if (process.isAlive) {
                        terminatedAfterStreamDrain =
                            streamingProcessLifecycle.terminateAfterStreamDrain(process, requestId)
                    }
                    break
                }
            }

            stdoutPump.join(STREAM_PUMP_JOIN_MILLIS)
            stderrPump.join(STREAM_PUMP_JOIN_MILLIS)

            currentCoroutineContext().ensureActive()
            val stderr = sanitizeProcessError(stderrText.toString())
            val stdout = sanitizeProcessError(stdoutText.toString())
            val exitCode = runCatching {
                if (process.isAlive) {
                    null
                } else {
                    process.exitValue()
                }
            }.getOrNull()
            val tolerateTerminatedExitCode = (terminatedAfterStreamDrain || terminatedAfterInactivity) && sawStdoutData
            if (timedOut.get()) {
                val resolvedTimeoutMessage = resolveProcessFailureMessage(
                    stderr = stderr,
                    stdout = stdout,
                    fallback = streamingProcessLifecycle.timeoutMessage(timeoutSeconds),
                )
                throw RuntimeException(resolvedTimeoutMessage)
            }
            if (exitCode == null && !tolerateTerminatedExitCode) {
                throw RuntimeException(
                    resolveProcessFailureMessage(
                        stderr = stderr,
                        stdout = stdout,
                        fallback = "CLI process did not exit after streams drained",
                    )
                )
            }
            if (exitCode != null && exitCode != 0 && !tolerateTerminatedExitCode) {
                throw RuntimeException(
                    resolveProcessFailureMessage(
                        stderr = stderr,
                        stdout = stdout,
                        fallback = "CLI exited with code: $exitCode",
                    )
                )
            }
            if (!emittedChunk && stderr.isNotBlank()) {
                throw RuntimeException(
                    resolveProcessFailureMessage(
                        stderr = stderr,
                        stdout = stdout,
                        fallback = "CLI returned no content",
                    )
                )
            }
            emit(EngineChunk(delta = "", isLast = true))
        } finally {
            watchdogFuture?.cancel(true)
            if (process.isAlive) {
                process.destroyForcibly()
            }
            activeProcesses.remove(requestId)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 取消指定请求的进程
     */
    fun cancelProcess(requestId: String) {
        activeProcesses.remove(requestId)?.let { process ->
            if (process.isAlive) {
                process.destroyForcibly()
                logger.info("Cancelled process for request: $requestId")
            }
        }
    }

    /**
     * 取消所有活跃进程
     */
    fun cancelAll() {
        activeProcesses.keys.toList().forEach { cancelProcess(it) }
    }

    override fun estimateTokens(content: String): Int {
        // 粗略估算：每 4 个字符约 1 个 token
        return content.length / 4
    }

    /** 构建命令行参数 */
    protected abstract fun buildCommandArgs(request: EngineRequest): List<String>

    /**
     * 构建流式命令行参数。
     * 默认与非流式保持一致，子类可按需覆盖（例如启用结构化流输出）。
     */
    protected open fun buildStreamCommandArgs(request: EngineRequest): List<String> = buildCommandArgs(request)

    /**
     * 可选 stdin 负载；返回 null 表示不写入 stdin。
     */
    protected open fun stdinPayload(request: EngineRequest): String? = null

    /** 解析流式输出行 */
    protected abstract fun parseStreamLine(line: String): EngineChunk?

    /**
     * 解析 CLI 过程输出（通常来自 stderr）。
     * 默认映射为 trace 事件，不写入最终 answer 文本。
     */
    protected open fun parseProgressLine(line: String): EngineChunk? {
        val event = CliProgressEventParser.parseStderr(line) ?: return null
        return EngineChunk(
            delta = "",
            event = event,
        )
    }

    /**
     * 清洗 stderr 单行内容，用于最终错误消息拼装。
     * 返回 null 表示该行属于协议噪音/可忽略元数据，不应暴露给用户。
     */
    protected open fun sanitizeProcessErrorLine(line: String): String? {
        return line.takeIf { it.isNotBlank() }
    }

    /**
     * stdout 在无换行时的分片阈值。
     * 返回 null 或 <= 0 时仅按换行刷新，适合 JSONL 等按行协议。
     */
    protected open fun stdoutChunkFlushChars(): Int? = STREAM_STDOUT_FLUSH_CHARS

    /**
     * 流式输出在出现有效 stdout 后的静默超时。
     * 返回 null 或 <= 0 时禁用；用于处理输出已经完成但 CLI 没有关闭流的场景。
     */
    protected open fun streamInactivityTimeoutMillis(request: EngineRequest): Long? = null

    /**
     * 可选的进程级环境变量覆写。
     */
    protected open fun environmentOverrides(request: EngineRequest): Map<String, String> = emptyMap()

    /** 获取版本号 */
    protected abstract suspend fun getVersion(): String?

    protected fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    protected fun runCliCommandForOutput(
        args: List<String>,
        timeoutSeconds: Long,
        acceptNonZeroExit: Boolean = false,
        workingDir: String? = null,
        environmentOverrides: Map<String, String> = emptyMap(),
    ): String? {
        val request = CliCommandRequest(
            executable = cliPath,
            args = args,
            workingDirectory = workingDir?.let(::File),
            environmentOverrides = environmentOverrides,
            redirectErrorStream = true,
        )
        val result = commandRuntime.execute(
            request = request,
            timeoutMs = timeoutSeconds.coerceAtLeast(1L) * 1000L,
        )
        result.startupDiagnostic?.let { diagnostic ->
            logger.debug("CLI probe failed for '${request.renderCommand()}': ${diagnostic.renderMessage()}")
            return null
        }
        if (result.timedOut) {
            logger.debug("CLI probe timed out: ${request.renderCommand()}")
            return if (acceptNonZeroExit && result.output.isNotBlank()) result.output else null
        }
        return if (result.exitCode == 0 || (acceptNonZeroExit && result.output.isNotBlank())) {
            result.output
        } else {
            logger.debug("CLI probe exited with ${result.exitCode}: ${request.renderCommand()}")
            null
        }
    }

    private fun writeRequestInput(process: Process, request: EngineRequest) {
        val payload = stdinPayload(request)
        if (payload == null) {
            runCatching { process.outputStream.close() }
            return
        }
        process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.write(payload)
        }
    }

    private fun resolveTimeoutSeconds(request: EngineRequest): Long? {
        val option = request.options["timeout_seconds"]?.toLongOrNull()
        return option?.takeIf { it > 0 }
    }

    protected open fun startProcess(
        args: List<String>,
        workingDir: String?,
        environmentOverrides: Map<String, String> = emptyMap(),
    ): Process {
        return commandRuntime.start(
            CliCommandRequest(
                executable = cliPath,
                args = args,
                workingDirectory = workingDir?.let(::File),
                environmentOverrides = environmentOverrides,
                redirectErrorStream = false,
            ),
        )
    }

    private fun startLinePump(
        stream: InputStream,
        source: StreamSource,
        queue: BlockingQueue<StreamFrame>,
        done: AtomicBoolean,
        stderrCollector: StringBuilder?,
    ): Thread {
        val pump = Thread {
            try {
                InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                    val stdoutFlushChars = stdoutChunkFlushChars()
                    val buffer = StringBuilder()

                    fun flush(hasLineBreak: Boolean = false) {
                        if (buffer.isEmpty()) return
                        val text = if (source == StreamSource.STDERR) {
                            buffer.toString().trimEnd()
                        } else {
                            buffer.toString()
                        }
                        buffer.setLength(0)
                        if (source == StreamSource.STDERR && text.isBlank()) return
                        if (stderrCollector != null) {
                            synchronized(stderrCollector) {
                                stderrCollector.appendLine(text)
                            }
                        }
                        queue.offer(
                            StreamFrame(
                                source = source,
                                line = text,
                                hasLineBreak = hasLineBreak,
                            )
                        )
                    }

                    while (true) {
                        val value = reader.read()
                        if (value == -1) break
                        val ch = value.toChar()
                        when (ch) {
                            '\n', '\r' -> flush(hasLineBreak = true)
                            else -> {
                                buffer.append(ch)
                                if (source == StreamSource.STDERR && buffer.length >= STREAM_PROGRESS_FLUSH_CHARS) {
                                    flush(hasLineBreak = false)
                                }
                                if (source == StreamSource.STDOUT &&
                                    stdoutFlushChars != null &&
                                    stdoutFlushChars > 0 &&
                                    buffer.length >= stdoutFlushChars
                                ) {
                                    flush(hasLineBreak = false)
                                }
                            }
                        }
                    }
                    flush(hasLineBreak = false)
                }
            } catch (ignored: Exception) {
                logger.debug("Stream pump closed for $id ($source): ${ignored.message}")
            } finally {
                done.set(true)
            }
        }
        pump.isDaemon = true
        pump.name = "cli-$id-${source.name.lowercase()}-pump"
        pump.start()
        return pump
    }

    private fun sanitizeProcessError(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(ERROR_ANSI_REGEX, "")
            .replace(ERROR_REPLACEMENT_CHAR_REGEX, "")
            .replace('\u0008', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .mapNotNull(::sanitizeProcessErrorLine)
            .filter { it.isNotBlank() }
            .filterNot { looksLikeMojibakeLine(it) }
            .joinToString("\n")
            .trim()
    }

    private fun looksLikeMojibakeLine(line: String): Boolean {
        val normalized = line.trim()
        if (normalized.isBlank()) return false
        if (ERROR_CJK_REGEX.containsMatchIn(normalized)) return false
        if (ERROR_BOX_DRAWING_REGEX.containsMatchIn(normalized)) return true
        val suspiciousCount = ERROR_SUSPICIOUS_MOJIBAKE_REGEX.findAll(normalized).count()
        if (suspiciousCount < ERROR_MOJIBAKE_MIN_COUNT) return false
        val ratio = suspiciousCount.toDouble() / normalized.length.toDouble().coerceAtLeast(1.0)
        return ratio >= ERROR_MOJIBAKE_MIN_RATIO
    }

    private fun resolveProcessFailureMessage(
        stderr: String,
        stdout: String,
        fallback: String,
    ): String {
        val stderrMessage = stderr.trim()
        if (isMeaningfulErrorText(stderrMessage)) {
            return stderrMessage
        }
        val stdoutMessage = stdout.trim()
        if (isMeaningfulErrorText(stdoutMessage)) {
            return stdoutMessage
        }
        return fallback
    }

    private fun isMeaningfulErrorText(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return false
        }
        if (normalized.lowercase() in PLACEHOLDER_ERROR_MESSAGES) {
            return false
        }
        if (normalized.length <= 3 && PLACEHOLDER_SYMBOLS_REGEX.matches(normalized)) {
            return false
        }
        return ERROR_TEXT_CONTENT_REGEX.containsMatchIn(normalized)
    }

    private fun appendDiagnostic(buffer: StringBuilder, line: String) {
        if (line.isBlank() || buffer.length >= MAX_DIAGNOSTIC_TEXT_LENGTH) {
            return
        }
        if (buffer.isNotEmpty()) {
            buffer.appendLine()
        }
        val remaining = MAX_DIAGNOSTIC_TEXT_LENGTH - buffer.length
        if (remaining <= 0) {
            return
        }
        buffer.append(line.take(remaining))
    }

    companion object {
        private const val STREAM_POLL_MILLIS = 120L
        private const val STREAM_PUMP_JOIN_MILLIS = 500L
        private const val STREAM_PROGRESS_FLUSH_CHARS = 120
        private const val STREAM_STDOUT_FLUSH_CHARS = 64
        private const val MAX_DIAGNOSTIC_TEXT_LENGTH = 2000
        private val ERROR_ANSI_REGEX = Regex("""\u001B\[[;\d]*[ -/]*[@-~]""")
        private val ERROR_REPLACEMENT_CHAR_REGEX = Regex("""\uFFFD+""")
        private val ERROR_BOX_DRAWING_REGEX = Regex("""[\u2500-\u259F]""")
        private val ERROR_CJK_REGEX = Regex("""\p{IsHan}""")
        private val ERROR_SUSPICIOUS_MOJIBAKE_REGEX = Regex("""[\u00C0-\u024F\u2500-\u259F]""")
        private val PLACEHOLDER_ERROR_MESSAGES = setOf("-", "--", "—", "...", "…", "null", "none", "unknown")
        private val PLACEHOLDER_SYMBOLS_REGEX = Regex("""^[\p{Punct}\s]+$""")
        private val ERROR_TEXT_CONTENT_REGEX = Regex("""[A-Za-z0-9\p{IsHan}]""")
        private const val ERROR_MOJIBAKE_MIN_COUNT = 4
        private const val ERROR_MOJIBAKE_MIN_RATIO = 0.15
    }

    private enum class StreamSource {
        STDOUT,
        STDERR,
    }

    private data class StreamFrame(
        val source: StreamSource,
        val line: String,
        val hasLineBreak: Boolean,
    )
}
