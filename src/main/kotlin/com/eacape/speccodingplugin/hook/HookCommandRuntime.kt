package com.eacape.speccodingplugin.hook

import com.eacape.speccodingplugin.core.ManagedMergedOutputProcess
import java.io.File
import java.util.concurrent.TimeUnit

internal class HookCommandRuntime(
    private val processStarter: (String?, List<String>) -> Process = { basePath, command ->
        ProcessBuilder(command)
            .directory(basePath?.let(::File))
            .redirectErrorStream(true)
            .start()
    },
    private val outputLimitChars: Int = DEFAULT_OUTPUT_LIMIT_CHARS,
    private val outputJoinTimeoutMillis: Long = DEFAULT_OUTPUT_JOIN_TIMEOUT_MILLIS,
    private val forceDestroyWaitSeconds: Long = DEFAULT_FORCE_DESTROY_WAIT_SECONDS,
) {

    fun execute(
        basePath: String?,
        executable: String,
        args: List<String>,
        timeoutMs: Long,
    ): HookCommandExecutionResult {
        val normalizedTimeoutMs = timeoutMs.coerceAtLeast(1L)
        val command = listOf(executable) + args
        return runCatching {
            val process = processStarter(basePath, command)
            val runtime = ManagedMergedOutputProcess.start(
                process = process,
                outputLimitChars = outputLimitChars,
                threadName = "hook-command-output-${command.joinToString(" ").hashCode()}",
            )
            val completion = runtime.awaitCompletion(
                timeout = normalizedTimeoutMs,
                timeoutUnit = TimeUnit.MILLISECONDS,
                joinTimeoutMillis = outputJoinTimeoutMillis,
                timeoutDestroyWait = forceDestroyWaitSeconds,
                timeoutDestroyWaitUnit = TimeUnit.SECONDS,
            )
            HookCommandExecutionResult(
                output = completion.output.ifBlank { null },
                exitCode = completion.exitCode,
                timedOut = completion.timedOut,
            )
        }.getOrElse { error ->
            HookCommandExecutionResult(
                output = null,
                exitCode = null,
                timedOut = false,
                startupErrorMessage = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private companion object {
        private const val DEFAULT_OUTPUT_LIMIT_CHARS = 8_192
        private const val DEFAULT_OUTPUT_JOIN_TIMEOUT_MILLIS = 2_000L
        private const val DEFAULT_FORCE_DESTROY_WAIT_SECONDS = 2L
    }
}

internal data class HookCommandExecutionResult(
    val output: String?,
    val exitCode: Int?,
    val timedOut: Boolean,
    val startupErrorMessage: String? = null,
)
