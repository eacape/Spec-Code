package com.eacape.speccodingplugin.engine

enum class CliToolAvailabilityIssueKind {
    EXECUTABLE_PATH_INVALID,
    EXECUTABLE_NOT_FOUND,
    ACCESS_DENIED,
    WORKING_DIRECTORY_UNAVAILABLE,
    COMMAND_TIMEOUT,
    COMMAND_FAILED,
}

data class CliToolAvailabilityIssue(
    val kind: CliToolAvailabilityIssueKind,
    val detail: String,
) {
    fun renderSummary(): String = detail
}

internal object CliToolAvailabilityIssues {

    fun fromStartupDiagnostic(diagnostic: CliCommandStartupDiagnostic): CliToolAvailabilityIssue {
        val kind = when (diagnostic.kind) {
            CliCommandFailureKind.EXECUTABLE_PATH_INVALID -> CliToolAvailabilityIssueKind.EXECUTABLE_PATH_INVALID
            CliCommandFailureKind.EXECUTABLE_NOT_FOUND -> CliToolAvailabilityIssueKind.EXECUTABLE_NOT_FOUND
            CliCommandFailureKind.ACCESS_DENIED -> CliToolAvailabilityIssueKind.ACCESS_DENIED
            CliCommandFailureKind.WORKING_DIRECTORY_UNAVAILABLE ->
                CliToolAvailabilityIssueKind.WORKING_DIRECTORY_UNAVAILABLE

            CliCommandFailureKind.STARTUP_FAILED -> CliToolAvailabilityIssueKind.COMMAND_FAILED
        }
        return CliToolAvailabilityIssue(
            kind = kind,
            detail = diagnostic.renderDetail(),
        )
    }

    fun timeout(timeoutSeconds: Long): CliToolAvailabilityIssue {
        return CliToolAvailabilityIssue(
            kind = CliToolAvailabilityIssueKind.COMMAND_TIMEOUT,
            detail = "cli probe timed out after ${timeoutSeconds.coerceAtLeast(1L)}s",
        )
    }

    fun commandFailed(exitCode: Int?, output: String): CliToolAvailabilityIssue {
        val summary = summarizeOutput(output)
        val detail = buildString {
            append("cli probe exited with code ")
            append(exitCode ?: "unknown")
            if (summary != null) {
                append(": ")
                append(summary)
            }
        }
        return CliToolAvailabilityIssue(
            kind = CliToolAvailabilityIssueKind.COMMAND_FAILED,
            detail = detail,
        )
    }

    private fun summarizeOutput(output: String): String? {
        val firstLine = output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        return if (firstLine.length <= MAX_OUTPUT_SUMMARY_LENGTH) {
            firstLine
        } else {
            firstLine.take(MAX_OUTPUT_SUMMARY_LENGTH - 3) + "..."
        }
    }

    private const val MAX_OUTPUT_SUMMARY_LENGTH = 160
}
