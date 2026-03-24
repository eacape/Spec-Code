package com.eacape.speccodingplugin.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Paths

/**
 * 相关文件发现服务（Project-level Service）
 * 通过解析 import 语句发现当前文件的依赖文件
 */
@Service(Service.Level.PROJECT)
class RelatedFileDiscovery(private val project: Project) {

    /**
     * 从当前编辑器文件中发现相关文件
     */
    fun discoverRelatedFiles(): List<ContextItem> {
        val editor = getActiveEditor() ?: return emptyList()
        val virtualFile = editor.virtualFile ?: return emptyList()

        val content = ReadAction.compute<String, Throwable> {
            editor.document.text
        }

        val imports = parseImports(content)
        val basePath = project.basePath ?: return emptyList()

        return imports.mapNotNull { importPath ->
            resolveImportToFile(importPath, basePath)
        }
    }

    private fun parseImports(content: String): List<String> {
        val results = mutableListOf<String>()

        for (line in content.lines()) {
            val trimmed = line.trim()

            // Kotlin/Java: import com.example.Foo
            KOTLIN_IMPORT.matchEntire(trimmed)?.let {
                results.add(it.groupValues[1])
                return@let
            }

            // TypeScript/JavaScript: import ... from '...'
            TS_IMPORT.find(trimmed)?.let {
                results.add(it.groupValues[1])
                return@let
            }

            // Python: from foo.bar import baz
            PYTHON_FROM_IMPORT.matchEntire(trimmed)?.let {
                results.add(it.groupValues[1])
                return@let
            }
        }

        return results
    }

    private fun resolveImportToFile(
        importPath: String,
        basePath: String,
    ): ContextItem? {
        // Try Kotlin/Java package path
        val jvmPath = importPath.replace('.', '/')
        val candidates = listOf(
            "$jvmPath.kt",
            "$jvmPath.java",
            "$importPath.ts",
            "$importPath.tsx",
            "$importPath.js",
            "$importPath.py",
            "${importPath.replace('.', '/')}.py",
        )

        for (candidate in candidates) {
            val resolved = findInSourceRoots(candidate, basePath)
            if (resolved != null) {
                return ContextItem(
                    type = ContextType.IMPORT_DEPENDENCY,
                    label = resolved.name,
                    content = "",
                    filePath = resolved.path,
                    priority = 40,
                )
            }
        }

        return null
    }

    private fun findInSourceRoots(
        relativePath: String,
        basePath: String,
    ): com.intellij.openapi.vfs.VirtualFile? {
        val sourceRoots = listOf(
            "src/main/kotlin",
            "src/main/java",
            "src",
            "lib",
            "",
        )

        for (root in sourceRoots) {
            val fullPath = Paths.get(basePath, root, relativePath)
                .normalize().toString()
            val vf = LocalFileSystem.getInstance()
                .findFileByPath(fullPath)
            if (vf != null && !vf.isDirectory) {
                return vf
            }
        }

        return null
    }

    private fun getActiveEditor(): Editor? {
        return FileEditorManager.getInstance(project)
            .selectedTextEditor
    }

    companion object {
        private val KOTLIN_IMPORT =
            Regex("""^import\s+([\w.]+)""")
        private val TS_IMPORT =
            Regex("""from\s+['"]([^'"]+)['"]""")
        private val PYTHON_FROM_IMPORT =
            Regex("""^from\s+([\w.]+)\s+import""")

        fun getInstance(project: Project): RelatedFileDiscovery {
            return project.getService(RelatedFileDiscovery::class.java)
        }
    }
}
