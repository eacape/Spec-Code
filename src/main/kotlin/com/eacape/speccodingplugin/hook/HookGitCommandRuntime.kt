package com.eacape.speccodingplugin.hook

import com.eacape.speccodingplugin.core.ManagedMergedOutputProcess
import java.io.File
import java.util.concurrent.TimeUnit

internal class HookGitCommandRuntime(
    private val processStarter: (String, List<String>) -> Process = { basePath, command ->
        ProcessBuilder(command)
            .directory(File(basePath))
            .redirectErrorStream(true)
            .start()
    },
    private val outputLimitChars: Int = DEFAULT_OUTPUT_LIMIT_CHARS,
    private val outputJoinTimeoutMillis: Long = DEFAULT_OUTPUT_JOIN_TIMEOUT_MILLIS,
    private val forceDestroyWaitMillis: Long = DEFAULT_FORCE_DESTROY_WAIT_MILLIS,
) {

    fun execute(
        basePath: String,
        timeoutMs: Long,
        vararg args: String,
    ): HookGitCommandExecutionResult {
        val command = listOf(GIT_EXECUTABLE) + args
        val normalizedTimeoutMs = timeoutMs.coerceAtLeast(1L)
        return runCatching {
            val process = processStarter(basePath, command)
            val runtime = ManagedMergedOutputProcess.start(
                process = process,
                outputLimitChars = outputLimitChars,
                threadName = "hook-git-command-output-${command.joinToString(" ").hashCode()}",
            )
            val completion = runtime.awaitCompletion(
                timeout = normalizedTimeoutMs,
                timeoutUnit = TimeUnit.MILLISECONDS,
                joinTimeoutMillis = outputJoinTimeoutMillis,
                timeoutDestroyWait = forceDestroyWaitMillis,
                timeoutDestroyWaitUnit = TimeUnit.MILLISECONDS,
            )
            when {
                completion.timedOut -> HookGitCommandExecutionResult(
                    output = null,
                    failed = true,
                    timedOut = true,
                )

                (completion.exitCode ?: -1) != 0 -> HookGitCommandExecutionResult(
                    output = null,
                    failed = true,
                    timedOut = false,
                )

                else -> HookGitCommandExecutionResult(
                    output = completion.output.ifBlank { null },
                    failed = false,
                    timedOut = false,
                )
            }
        }.getOrElse {
            HookGitCommandExecutionResult(
                output = null,
                failed = true,
                timedOut = false,
            )
        }
    }

    private companion object {
        private const val GIT_EXECUTABLE = "git"
        private const val DEFAULT_OUTPUT_LIMIT_CHARS = 2_048
        private const val DEFAULT_OUTPUT_JOIN_TIMEOUT_MILLIS = 500L
        private const val DEFAULT_FORCE_DESTROY_WAIT_MILLIS = 1_000L
    }
}

internal data class HookGitCommandExecutionResult(
    val output: String?,
    val failed: Boolean,
    val timedOut: Boolean,
)
