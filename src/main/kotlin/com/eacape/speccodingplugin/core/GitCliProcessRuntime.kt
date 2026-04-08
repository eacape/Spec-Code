package com.eacape.speccodingplugin.core

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal data class GitCliProcessResult(
    val output: String?,
    val exitCode: Int?,
    val timedOut: Boolean,
    val outputTruncated: Boolean,
    val startupErrorMessage: String? = null,
) {
    val failed: Boolean
        get() = timedOut || startupErrorMessage != null || (exitCode ?: -1) != 0
}

internal class GitCliProcessRuntime(
    private val processStarter: (File?, List<String>) -> Process = { workingDir, args ->
        ProcessBuilder(buildCommand(args))
            .apply {
                if (workingDir != null) {
                    directory(workingDir)
                }
                redirectErrorStream(true)
            }
            .start()
    },
    private val outputLimitChars: Int = DEFAULT_OUTPUT_LIMIT_CHARS,
    private val outputJoinTimeoutMillis: Long = DEFAULT_OUTPUT_JOIN_TIMEOUT_MILLIS,
    private val forceDestroyWaitMillis: Long = DEFAULT_FORCE_DESTROY_WAIT_MILLIS,
) {

    fun execute(
        workingDir: File?,
        timeoutMs: Long,
        args: List<String>,
    ): GitCliProcessResult {
        val normalizedTimeoutMs = timeoutMs.coerceAtLeast(1L)
        val command = buildCommand(args)
        return runCatching {
            val process = processStarter(workingDir, args)
            val runtime = ManagedMergedOutputProcess.start(
                process = process,
                outputLimitChars = outputLimitChars,
                threadName = "git-cli-output-${command.joinToString(" ").hashCode()}",
            )
            val completion = runtime.awaitCompletion(
                timeout = normalizedTimeoutMs,
                timeoutUnit = TimeUnit.MILLISECONDS,
                joinTimeoutMillis = outputJoinTimeoutMillis,
                timeoutDestroyWait = forceDestroyWaitMillis,
                timeoutDestroyWaitUnit = TimeUnit.MILLISECONDS,
            )
            GitCliProcessResult(
                output = completion.output.ifBlank { null },
                exitCode = completion.exitCode,
                timedOut = completion.timedOut,
                outputTruncated = completion.outputTruncated,
            )
        }.getOrElse { error ->
            GitCliProcessResult(
                output = null,
                exitCode = null,
                timedOut = false,
                outputTruncated = false,
                startupErrorMessage = error.message ?: error::class.java.simpleName,
            )
        }
    }

    companion object {
        internal fun buildCommand(args: List<String>): List<String> = listOf(GIT_EXECUTABLE) + args

        internal fun renderCommand(args: List<String>): String = buildCommand(args).joinToString(" ")

        private const val GIT_EXECUTABLE = "git"
        private const val DEFAULT_OUTPUT_LIMIT_CHARS = 16_384
        private const val DEFAULT_OUTPUT_JOIN_TIMEOUT_MILLIS = 2_000L
        private const val DEFAULT_FORCE_DESTROY_WAIT_MILLIS = 1_000L
    }
}

internal fun interface GitCommandExecutor {
    fun run(workingDir: Path?, args: List<String>): Result<String>
}

internal class CliGitCommandExecutor(
    private val runtime: GitCliProcessRuntime = GitCliProcessRuntime(),
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MILLIS,
) : GitCommandExecutor {

    override fun run(workingDir: Path?, args: List<String>): Result<String> {
        val result = runDetailed(workingDir, args)
        val diagnostic = diagnoseFailure(workingDir, args, result, timeoutMs)
        return if (diagnostic != null) {
            Result.failure(GitCommandExecutionException(diagnostic))
        } else {
            Result.success(result.output.orEmpty().trim())
        }
    }

    fun runDetailed(
        workingDir: Path?,
        args: List<String>,
        timeoutMs: Long = this.timeoutMs,
    ): GitCliProcessResult {
        return runtime.execute(
            workingDir = workingDir?.toFile(),
            timeoutMs = timeoutMs,
            args = args,
        )
    }

    internal fun diagnoseFailure(
        workingDir: Path?,
        args: List<String>,
        result: GitCliProcessResult,
        timeoutMs: Long = this.timeoutMs,
    ): GitCliFailureDiagnostic? {
        return GitCliFailureDiagnostics.diagnose(
            workingDir = workingDir,
            args = args,
            timeoutMs = timeoutMs,
            result = result,
        )
    }

    private companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 300_000L
    }
}

internal fun GitCommandExecutor.runOrThrow(workingDir: Path?, args: List<String>): String {
    return run(workingDir, args).getOrElse { error ->
        throw error
    }
}
