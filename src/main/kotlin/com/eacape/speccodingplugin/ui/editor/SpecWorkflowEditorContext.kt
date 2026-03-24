package com.eacape.speccodingplugin.ui.editor

import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Paths

internal data class SpecWorkflowEditorContext(
    val workflowId: String,
    val fileName: String,
)

internal object SpecWorkflowEditorContextResolver {
    private const val SPEC_STORAGE_MARKER = "/.spec-coding/specs/"
    private val supportedFileNames = setOf(
        "workflow.yaml",
        "requirements.md",
        "design.md",
        "tasks.md",
        "verification.md",
    )

    fun resolve(file: VirtualFile?): SpecWorkflowEditorContext? {
        val normalizedPath = normalizePath(file?.path ?: return null) ?: return null
        val markerIndex = normalizedPath.indexOf(SPEC_STORAGE_MARKER)
        if (markerIndex < 0) {
            return null
        }

        val remaining = normalizedPath.substring(markerIndex + SPEC_STORAGE_MARKER.length)
        val workflowId = remaining.substringBefore('/').trim()
        val relativePath = remaining.substringAfter('/', missingDelimiterValue = "").trim()
        if (workflowId.isBlank() || relativePath.isBlank() || relativePath.contains('/')) {
            return null
        }
        if (relativePath !in supportedFileNames) {
            return null
        }

        return SpecWorkflowEditorContext(
            workflowId = workflowId,
            fileName = relativePath,
        )
    }

    private fun normalizePath(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isBlank()) {
            return null
        }
        return runCatching {
            Paths.get(trimmed)
                .normalize()
                .toString()
                .replace('\\', '/')
                .trimEnd('/')
        }.getOrElse {
            trimmed.replace('\\', '/').trimEnd('/')
        }
    }
}
