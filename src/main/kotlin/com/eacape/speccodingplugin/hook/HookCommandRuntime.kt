package com.eacape.speccodingplugin.hook

import com.eacape.speccodingplugin.core.ExternalProcessLaunchSpec
import com.eacape.speccodingplugin.core.ExternalProcessLauncher
import com.eacape.speccodingplugin.core.ExternalProcessStartupFailureClassifier
import com.eacape.speccodingplugin.core.ExternalProcessStartupFailureKind
import com.eacape.speccodingplugin.core.ManagedMergedOutputProcess
import java.io.File
import java.util.concurrent.TimeUnit

internal enum class HookCommandFailureKind(val label: String) {
    EXECUTABLE_NOT_FOUND("executable-not-found"),
    WORKING_DIRECTORY_UNAVAILABLE("working-directory-unavailable"),
    ACCESS_DENIED("access-denied"),
    STARTUP_FAILED("startup-failed"),
}

internal data class HookCommandStartupDiagnostic(
    val kind: HookCommandFailureKind,
    val executable: String,
    val workingDirectory: File?,
    val startupErrorMessage: String? = null,
) {
    fun renderMessage(): String {
        return "Hook command failed to start (${kind.label}): ${renderDetail()}"
    }

    fun renderDetail(): String {
        return when (kind) {
            HookCommandFailureKind.EXECUTABLE_NOT_FOUND ->
                "hook executable was not found: ${renderExecutable()}"

            HookCommandFailureKind.WORKING_DIRECTORY_UNAVAILABLE ->
                "working directory is unavailable: ${renderWorkingDirectory()}"

            HookCommandFailureKind.ACCESS_DENIED ->
                "access denied while starting hook command in ${renderWorkingDirectory()}"

            HookCommandFailureKind.STARTUP_FAILED ->
                startupErrorMessage?.ifBlank { null }
                    ?: "hook process could not be started"
        }
    }

    private fun renderExecutable(): String {
        return executable.substringAfterLast('/').substringAfterLast('\\').ifBlank { executable }
    }

    private fun renderWorkingDirectory(): String {
        return workingDirectory?.absolutePath ?: "default process working directory"
    }
}

internal class HookCommandRuntime(
    private val processStarter: (String?, List<String>) -> Process = { basePath, command ->
        ExternalProcessLauncher.start(
            ExternalProcessLaunchSpec(
                command = command,
                workingDirectory = basePath?.let(::File),
                redirectErrorStream = true,
            ),
        )
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
                startupDiagnostic = HookCommandFailureDiagnostics.diagnoseStartup(
                    basePath = basePath,
                    executable = executable,
                    startupErrorMessage = error.message ?: error::class.java.simpleName,
                ),
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
    val startupDiagnostic: HookCommandStartupDiagnostic? = null,
) {
    val startupErrorMessage: String?
        get() = startupDiagnostic?.startupErrorMessage
}

internal object HookCommandFailureDiagnostics {

    fun diagnoseStartup(
        basePath: String?,
        executable: String,
        startupErrorMessage: String,
    ): HookCommandStartupDiagnostic {
        return HookCommandStartupDiagnostic(
            kind = classifyStartupFailureKind(
                basePath = basePath,
                startupErrorMessage = startupErrorMessage,
            ),
            executable = executable,
            workingDirectory = basePath?.let(::File),
            startupErrorMessage = startupErrorMessage,
        )
    }

    private fun classifyStartupFailureKind(
        basePath: String?,
        startupErrorMessage: String,
    ): HookCommandFailureKind {
        return when (
            ExternalProcessStartupFailureClassifier.classify(
                startupErrorMessage = startupErrorMessage,
                workingDirectory = basePath?.let(::File),
                missingExecutableHints = setOf("missing tool"),
            )
        ) {
            ExternalProcessStartupFailureKind.EXECUTABLE_NOT_FOUND ->
                HookCommandFailureKind.EXECUTABLE_NOT_FOUND

            ExternalProcessStartupFailureKind.WORKING_DIRECTORY_UNAVAILABLE ->
                HookCommandFailureKind.WORKING_DIRECTORY_UNAVAILABLE

            ExternalProcessStartupFailureKind.ACCESS_DENIED ->
                HookCommandFailureKind.ACCESS_DENIED

            ExternalProcessStartupFailureKind.STARTUP_FAILED ->
                HookCommandFailureKind.STARTUP_FAILED
        }
    }
}
