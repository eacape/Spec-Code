package com.eacape.speccodingplugin.hook

import com.eacape.speccodingplugin.core.GitCliProcessRuntime
import java.io.File

internal class HookGitCommandRuntime private constructor(
    private val runtime: GitCliProcessRuntime,
) {
    constructor() : this(
        runtime = GitCliProcessRuntime(
            outputLimitChars = DEFAULT_OUTPUT_LIMIT_CHARS,
            outputJoinTimeoutMillis = DEFAULT_OUTPUT_JOIN_TIMEOUT_MILLIS,
            forceDestroyWaitMillis = DEFAULT_FORCE_DESTROY_WAIT_MILLIS,
        ),
    )

    constructor(
        processStarter: (String, List<String>) -> Process,
        outputLimitChars: Int = DEFAULT_OUTPUT_LIMIT_CHARS,
        outputJoinTimeoutMillis: Long = DEFAULT_OUTPUT_JOIN_TIMEOUT_MILLIS,
        forceDestroyWaitMillis: Long = DEFAULT_FORCE_DESTROY_WAIT_MILLIS,
    ) : this(
        runtime = GitCliProcessRuntime(
            processStarter = { workingDir, args ->
                processStarter(
                    requireNotNull(workingDir) { "Git working directory is required" }.path,
                    GitCliProcessRuntime.buildCommand(args),
                )
            },
            outputLimitChars = outputLimitChars,
            outputJoinTimeoutMillis = outputJoinTimeoutMillis,
            forceDestroyWaitMillis = forceDestroyWaitMillis,
        ),
    )

    fun execute(
        basePath: String,
        timeoutMs: Long,
        vararg args: String,
    ): HookGitCommandExecutionResult {
        val result = runtime.execute(
            workingDir = File(basePath),
            timeoutMs = timeoutMs,
            args = args.toList(),
        )
        return when {
            result.timedOut -> HookGitCommandExecutionResult(
                    output = null,
                    failed = true,
                    timedOut = true,
                )

            result.failed -> HookGitCommandExecutionResult(
                    output = null,
                    failed = true,
                    timedOut = false,
                )

            else -> HookGitCommandExecutionResult(
                    output = result.output,
                    failed = false,
                    timedOut = false,
                )
        }
    }

    private companion object {
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
