package com.eacape.speccodingplugin.core

import java.io.File

internal enum class ExternalProcessStartupFailureKind {
    EXECUTABLE_NOT_FOUND,
    WORKING_DIRECTORY_UNAVAILABLE,
    ACCESS_DENIED,
    STARTUP_FAILED,
}

internal object ExternalProcessStartupFailureClassifier {

    fun classify(
        startupErrorMessage: String,
        workingDirectory: File? = null,
        missingExecutableHints: Set<String> = emptySet(),
    ): ExternalProcessStartupFailureKind {
        if (workingDirectory != null && !workingDirectory.isDirectory) {
            return ExternalProcessStartupFailureKind.WORKING_DIRECTORY_UNAVAILABLE
        }

        val normalized = startupErrorMessage.lowercase()
        return when {
            normalized.contains("createprocess error=5") ||
                normalized.contains("access is denied") ||
                normalized.contains("permission denied") ->
                ExternalProcessStartupFailureKind.ACCESS_DENIED

            normalized.contains("createprocess error=2") ||
                normalized.contains("no such file or directory") ||
                normalized.contains("cannot find the file specified") ||
                normalized.contains("error=2,") ||
                normalized.contains("missing executable") ||
                normalized.contains("executable not found") ||
                normalized.contains("command not found") ||
                missingExecutableHints.any { hint ->
                    hint.isNotBlank() && normalized.contains(hint.lowercase())
                } ->
                ExternalProcessStartupFailureKind.EXECUTABLE_NOT_FOUND

            normalized.contains("createprocess error=267") ||
                normalized.contains("the directory name is invalid") ||
                normalized.contains("not a directory") ->
                ExternalProcessStartupFailureKind.WORKING_DIRECTORY_UNAVAILABLE

            else -> ExternalProcessStartupFailureKind.STARTUP_FAILED
        }
    }
}
