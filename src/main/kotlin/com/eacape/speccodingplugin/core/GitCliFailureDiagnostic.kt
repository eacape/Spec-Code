package com.eacape.speccodingplugin.core

import java.io.File
import java.nio.file.Path

internal enum class GitCliFailureKind {
    TIMEOUT,
    EXECUTABLE_NOT_FOUND,
    WORKING_DIRECTORY_UNAVAILABLE,
    ACCESS_DENIED,
    STARTUP_FAILED,
    EXIT_FAILURE,
}

internal data class GitCliFailureDiagnostic(
    val kind: GitCliFailureKind,
    val args: List<String>,
    val workingDirectory: File?,
    val timeoutMs: Long? = null,
    val exitCode: Int? = null,
    val output: String? = null,
    val startupErrorMessage: String? = null,
    val outputTruncated: Boolean = false,
) {
    val suggestedExitCode: Int
        get() = when (kind) {
            GitCliFailureKind.TIMEOUT -> 124
            GitCliFailureKind.ACCESS_DENIED -> 126
            GitCliFailureKind.EXECUTABLE_NOT_FOUND,
            GitCliFailureKind.WORKING_DIRECTORY_UNAVAILABLE,
            GitCliFailureKind.STARTUP_FAILED,
            -> 127
            GitCliFailureKind.EXIT_FAILURE -> exitCode ?: 1
        }

    fun renderMessage(includeCommand: Boolean = true): String {
        val commandDisplay = GitCliProcessRuntime.renderCommand(args)
        val detail = renderDetail()
        return when (kind) {
            GitCliFailureKind.TIMEOUT ->
                if (includeCommand) {
                    "Git command timed out after ${timeoutMs ?: 0L}ms ($commandDisplay)"
                } else {
                    detail
                }

            GitCliFailureKind.EXIT_FAILURE ->
                if (includeCommand) {
                    "Git command failed ($commandDisplay): $detail"
                } else {
                    detail
                }

            else ->
                if (includeCommand) {
                    "Failed to start git command ($commandDisplay): $detail"
                } else {
                    detail
                }
        }
    }

    fun renderDetail(): String {
        return when (kind) {
            GitCliFailureKind.TIMEOUT -> "timed out after ${timeoutMs ?: 0L}ms"
            GitCliFailureKind.EXECUTABLE_NOT_FOUND -> "git executable was not found on PATH"
            GitCliFailureKind.WORKING_DIRECTORY_UNAVAILABLE ->
                "working directory is unavailable: ${workingDirectory?.path ?: "unknown"}"

            GitCliFailureKind.ACCESS_DENIED ->
                buildString {
                    append("access denied while starting git")
                    workingDirectory?.path?.takeIf(String::isNotBlank)?.let { path ->
                        append(" in ")
                        append(path)
                    }
                }

            GitCliFailureKind.STARTUP_FAILED ->
                startupErrorMessage?.ifBlank { null }
                    ?: "git process could not be started"

            GitCliFailureKind.EXIT_FAILURE ->
                buildString {
                    if (!output.isNullOrBlank()) {
                        append(output.trim())
                    } else {
                        append("exitCode=")
                        append(exitCode ?: "unknown")
                    }
                    if (outputTruncated) {
                        append(" [output truncated]")
                    }
                }
        }
    }
}

internal class GitCommandExecutionException(
    val diagnostic: GitCliFailureDiagnostic,
) : IllegalStateException(diagnostic.renderMessage())

internal object GitCliFailureDiagnostics {
    fun diagnose(
        workingDir: Path?,
        args: List<String>,
        timeoutMs: Long,
        result: GitCliProcessResult,
    ): GitCliFailureDiagnostic? {
        if (result.timedOut) {
            return GitCliFailureDiagnostic(
                kind = GitCliFailureKind.TIMEOUT,
                args = args,
                workingDirectory = workingDir?.toFile(),
                timeoutMs = timeoutMs,
            )
        }

        val startupErrorMessage = result.startupErrorMessage
        if (startupErrorMessage != null) {
            return GitCliFailureDiagnostic(
                kind = classifyStartupFailureKind(workingDir?.toFile(), startupErrorMessage),
                args = args,
                workingDirectory = workingDir?.toFile(),
                timeoutMs = timeoutMs,
                startupErrorMessage = startupErrorMessage,
            )
        }

        if ((result.exitCode ?: -1) != 0) {
            return GitCliFailureDiagnostic(
                kind = GitCliFailureKind.EXIT_FAILURE,
                args = args,
                workingDirectory = workingDir?.toFile(),
                timeoutMs = timeoutMs,
                exitCode = result.exitCode,
                output = result.output,
                outputTruncated = result.outputTruncated,
            )
        }

        return null
    }

    private fun classifyStartupFailureKind(
        workingDirectory: File?,
        startupErrorMessage: String,
    ): GitCliFailureKind {
        if (workingDirectory != null && (!workingDirectory.exists() || !workingDirectory.isDirectory)) {
            return GitCliFailureKind.WORKING_DIRECTORY_UNAVAILABLE
        }

        val normalized = startupErrorMessage.lowercase()
        return when {
            normalized.contains("createprocess error=5") ||
                normalized.contains("access is denied") ||
                normalized.contains("permission denied") ->
                GitCliFailureKind.ACCESS_DENIED

            normalized.contains("createprocess error=2") ||
                normalized.contains("no such file or directory") ||
                normalized.contains("cannot find the file specified") ||
                normalized.contains("error=2,") ->
                GitCliFailureKind.EXECUTABLE_NOT_FOUND

            normalized.contains("createprocess error=267") ||
                normalized.contains("the directory name is invalid") ||
                normalized.contains("not a directory") ->
                GitCliFailureKind.WORKING_DIRECTORY_UNAVAILABLE

            else -> GitCliFailureKind.STARTUP_FAILED
        }
    }
}
