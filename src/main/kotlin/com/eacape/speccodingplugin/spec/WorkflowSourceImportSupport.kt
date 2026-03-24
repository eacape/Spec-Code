package com.eacape.speccodingplugin.spec

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max

data class WorkflowSourceImportConstraints(
    val allowedExtensions: Set<String> = DEFAULT_ALLOWED_EXTENSIONS,
    val maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE_BYTES,
) {
    init {
        require(maxFileSizeBytes > 0L) { "maxFileSizeBytes must be positive" }
    }

    fun supportsFileName(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension.isNotBlank() && allowedExtensions.contains(extension)
    }

    companion object {
        val DEFAULT_ALLOWED_EXTENSIONS: Set<String> = linkedSetOf(
            "md",
            "markdown",
            "txt",
            "pdf",
            "png",
            "jpg",
            "jpeg",
        )

        const val DEFAULT_MAX_FILE_SIZE_BYTES: Long = 10L * 1024L * 1024L
    }
}

data class WorkflowSourceImportValidation(
    val acceptedPaths: List<Path>,
    val rejectedFiles: List<RejectedWorkflowSourceFile>,
)

data class RejectedWorkflowSourceFile(
    val path: Path,
    val reason: Reason,
) {
    enum class Reason {
        NOT_A_FILE,
        UNSUPPORTED_EXTENSION,
        FILE_TOO_LARGE,
    }
}

object WorkflowSourceImportSupport {

    fun validate(
        paths: List<Path>,
        constraints: WorkflowSourceImportConstraints = WorkflowSourceImportConstraints(),
    ): WorkflowSourceImportValidation {
        val accepted = mutableListOf<Path>()
        val rejected = mutableListOf<RejectedWorkflowSourceFile>()

        paths.forEach { rawPath ->
            val normalizedPath = rawPath.toAbsolutePath().normalize()
            val fileName = normalizedPath.fileName?.toString().orEmpty()
            when {
                !Files.isRegularFile(normalizedPath) -> {
                    rejected.add(RejectedWorkflowSourceFile(normalizedPath, RejectedWorkflowSourceFile.Reason.NOT_A_FILE))
                }

                !constraints.supportsFileName(fileName) -> {
                    rejected.add(
                        RejectedWorkflowSourceFile(
                            normalizedPath,
                            RejectedWorkflowSourceFile.Reason.UNSUPPORTED_EXTENSION,
                        ),
                    )
                }

                Files.size(normalizedPath) > constraints.maxFileSizeBytes -> {
                    rejected.add(
                        RejectedWorkflowSourceFile(
                            normalizedPath,
                            RejectedWorkflowSourceFile.Reason.FILE_TOO_LARGE,
                        ),
                    )
                }

                else -> accepted.add(normalizedPath)
            }
        }

        return WorkflowSourceImportValidation(
            acceptedPaths = accepted.distinct(),
            rejectedFiles = rejected,
        )
    }

    fun formatFileSize(bytes: Long): String {
        val safeBytes = max(bytes, 0L)
        if (safeBytes < 1024L) {
            return "${safeBytes} B"
        }
        val kib = safeBytes / 1024.0
        if (kib < 1024.0) {
            return String.format("%.1f KB", kib)
        }
        val mib = kib / 1024.0
        return String.format("%.1f MB", mib)
    }

    fun formatAllowedExtensions(constraints: WorkflowSourceImportConstraints): String {
        return constraints.allowedExtensions
            .map { extension -> ".$extension" }
            .joinToString(", ")
    }
}
