package com.eacape.speccodingplugin.spec

import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal enum class VerifyCommandFailureKind(val label: String) {
    EXECUTABLE_NOT_FOUND("executable-not-found"),
    WORKING_DIRECTORY_UNAVAILABLE("working-directory-unavailable"),
    ACCESS_DENIED("access-denied"),
    STARTUP_FAILED("startup-failed"),
}

internal data class VerifyCommandFailureDiagnostic(
    val kind: VerifyCommandFailureKind,
    val commandId: String,
    val executable: String,
    val workingDirectory: Path,
    val startupErrorMessage: String? = null,
) {
    fun renderMessage(): String {
        return "Verify command $commandId failed to start (${kind.label}): ${renderDetail()}"
    }

    fun renderDetail(): String {
        return when (kind) {
            VerifyCommandFailureKind.EXECUTABLE_NOT_FOUND ->
                "verify executable was not found: ${renderExecutable()}"

            VerifyCommandFailureKind.WORKING_DIRECTORY_UNAVAILABLE ->
                "working directory is unavailable: ${workingDirectory.normalize()}"

            VerifyCommandFailureKind.ACCESS_DENIED ->
                "access denied while starting verify command in ${workingDirectory.normalize()}"

            VerifyCommandFailureKind.STARTUP_FAILED ->
                startupErrorMessage?.ifBlank { null }
                    ?: "verify process could not be started"
        }
    }

    private fun renderExecutable(): String {
        return executable.substringAfterLast('/').substringAfterLast('\\').ifBlank { executable }
    }
}

internal class VerifyCommandStartupError(
    val diagnostic: VerifyCommandFailureDiagnostic,
) : WorkflowDomainError(diagnostic.renderMessage())

internal data class VerifyCommandRuntimeResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val startupDiagnostic: VerifyCommandFailureDiagnostic? = null,
)

internal class VerifyCommandRuntime(
    private val processStarter: (VerifyCommandExecutionRequest) -> Process = { request ->
        ProcessBuilder(request.command)
            .directory(request.workingDirectory.toFile())
            .redirectErrorStream(false)
            .start()
    },
    private val outputJoinTimeoutMillis: Long = DEFAULT_OUTPUT_JOIN_TIMEOUT_MILLIS,
    private val timeoutDestroyGraceWaitMillis: Long = DEFAULT_TIMEOUT_DESTROY_GRACE_WAIT_MILLIS,
    private val timeoutDestroyForceWaitMillis: Long = DEFAULT_TIMEOUT_DESTROY_FORCE_WAIT_MILLIS,
) {

    fun execute(request: VerifyCommandExecutionRequest): VerifyCommandRuntimeResult {
        val normalizedTimeoutMs = request.timeoutMs.toLong().coerceAtLeast(1L)
        return runCatching {
            val process = processStarter(request)
            val runtime = ManagedSplitOutputProcess.start(
                process = process,
                outputLimitChars = request.outputLimitChars,
                stdoutThreadName = "${request.commandId}-stdout",
                stderrThreadName = "${request.commandId}-stderr",
            )
            val completion = runtime.awaitCompletion(
                timeout = normalizedTimeoutMs,
                timeoutUnit = TimeUnit.MILLISECONDS,
                joinTimeoutMillis = outputJoinTimeoutMillis,
                timeoutDestroyGraceWait = timeoutDestroyGraceWaitMillis,
                timeoutDestroyGraceWaitUnit = TimeUnit.MILLISECONDS,
                timeoutDestroyForceWait = timeoutDestroyForceWaitMillis,
                timeoutDestroyForceWaitUnit = TimeUnit.MILLISECONDS,
            )
            VerifyCommandRuntimeResult(
                stdout = completion.stdout,
                stderr = completion.stderr,
                exitCode = completion.exitCode,
                timedOut = completion.timedOut,
                stdoutTruncated = completion.stdoutTruncated,
                stderrTruncated = completion.stderrTruncated,
            )
        }.getOrElse { error ->
            VerifyCommandRuntimeResult(
                stdout = "",
                stderr = "",
                exitCode = null,
                timedOut = false,
                stdoutTruncated = false,
                stderrTruncated = false,
                startupDiagnostic = VerifyCommandFailureDiagnostics.diagnoseStartup(
                    request = request,
                    startupErrorMessage = error.message ?: error::class.java.simpleName,
                ),
            )
        }
    }

    private companion object {
        private const val DEFAULT_OUTPUT_JOIN_TIMEOUT_MILLIS = 1_000L
        private const val DEFAULT_TIMEOUT_DESTROY_GRACE_WAIT_MILLIS = 250L
        private const val DEFAULT_TIMEOUT_DESTROY_FORCE_WAIT_MILLIS = 500L
    }
}

internal object VerifyCommandFailureDiagnostics {

    fun diagnoseStartup(
        request: VerifyCommandExecutionRequest,
        startupErrorMessage: String,
    ): VerifyCommandFailureDiagnostic {
        return VerifyCommandFailureDiagnostic(
            kind = classifyStartupFailureKind(
                workingDirectory = request.workingDirectory,
                startupErrorMessage = startupErrorMessage,
            ),
            commandId = request.commandId,
            executable = request.command.firstOrNull().orEmpty(),
            workingDirectory = request.workingDirectory,
            startupErrorMessage = startupErrorMessage,
        )
    }

    private fun classifyStartupFailureKind(
        workingDirectory: Path,
        startupErrorMessage: String,
    ): VerifyCommandFailureKind {
        if (!java.nio.file.Files.isDirectory(workingDirectory)) {
            return VerifyCommandFailureKind.WORKING_DIRECTORY_UNAVAILABLE
        }

        val normalized = startupErrorMessage.lowercase()
        return when {
            normalized.contains("createprocess error=5") ||
                normalized.contains("access is denied") ||
                normalized.contains("permission denied") ->
                VerifyCommandFailureKind.ACCESS_DENIED

            normalized.contains("createprocess error=2") ||
                normalized.contains("no such file or directory") ||
                normalized.contains("cannot find the file specified") ||
                normalized.contains("error=2,") ||
                normalized.contains("missing executable") ||
                normalized.contains("executable not found") ->
                VerifyCommandFailureKind.EXECUTABLE_NOT_FOUND

            normalized.contains("createprocess error=267") ||
                normalized.contains("the directory name is invalid") ||
                normalized.contains("not a directory") ->
                VerifyCommandFailureKind.WORKING_DIRECTORY_UNAVAILABLE

            else -> VerifyCommandFailureKind.STARTUP_FAILED
        }
    }
}
