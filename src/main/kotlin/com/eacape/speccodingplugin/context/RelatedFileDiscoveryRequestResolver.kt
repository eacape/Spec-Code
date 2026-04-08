package com.eacape.speccodingplugin.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import java.nio.file.Path

internal data class RelatedFileDiscoveryRequest(
    val filePath: String,
    val fileName: String,
    val content: String,
    val documentModificationStamp: Long,
    val context: RelatedFileDiscoveryContext,
)

internal object RelatedFileDiscoveryRequestResolver {

    fun resolveFromActiveEditor(project: Project): RelatedFileDiscoveryRequest? {
        return ReadAction.compute<RelatedFileDiscoveryRequest?, Throwable> {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@compute null
            val virtualFile = editor.virtualFile ?: return@compute null
            val basePath = project.basePath ?: return@compute null
            RelatedFileDiscoveryRequest(
                filePath = virtualFile.path,
                fileName = virtualFile.name,
                content = editor.document.text,
                documentModificationStamp = editor.document.modificationStamp,
                context = RelatedFileDiscoveryContext(
                    basePath = Path.of(basePath),
                    activeFilePath = Path.of(virtualFile.path),
                    language = RelatedFileDiscoveryLanguage.fromFileName(virtualFile.name),
                ),
            )
        }
    }
}
