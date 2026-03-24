package com.eacape.speccodingplugin.context

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

/**
 * 项目结构扫描器（Project-level Service）
 * 生成目录树字符串，统计文件类型
 */
@Service(Service.Level.PROJECT)
class ProjectStructureScanner(private val project: Project) {

    @Volatile
    private var cachedTree: String? = null

    /**
     * 生成项目目录树字符串
     */
    fun getProjectTree(maxDepth: Int = 4): String {
        cachedTree?.let { return it }

        val basePath = project.basePath ?: return ""
        val baseDir = LocalFileSystem.getInstance()
            .findFileByPath(basePath) ?: return ""

        val sb = StringBuilder()
        sb.appendLine(baseDir.name + "/")
        buildTree(baseDir, sb, "", maxDepth, 0)

        val result = sb.toString().trimEnd()
        cachedTree = result
        return result
    }

    /**
     * 获取项目结构的上下文项
     */
    fun getProjectStructureContext(): ContextItem {
        val tree = getProjectTree()
        return ContextItem(
            type = ContextType.PROJECT_STRUCTURE,
            label = "Project Structure",
            content = tree,
            filePath = project.basePath,
            priority = 30,
        )
    }

    /**
     * 获取指定目录结构的上下文项（用于 @目录 引用）。
     */
    fun getDirectoryStructureContext(
        directoryPath: String,
        maxDepth: Int = 3,
    ): ContextItem? {
        val normalized = directoryPath.trim().ifBlank { return null }
        val dir = LocalFileSystem.getInstance().findFileByPath(normalized) ?: return null
        if (!dir.isDirectory) return null

        val projectBase = project.basePath
        if (projectBase != null && !dir.path.startsWith(projectBase)) {
            return null
        }

        val sb = StringBuilder()
        sb.appendLine(dir.name + "/")
        buildTree(dir, sb, "", maxDepth, 0)

        val content = sb.toString().trimEnd()
        if (content.isBlank()) return null

        return ContextItem(
            type = ContextType.PROJECT_STRUCTURE,
            label = "Dir: ${dir.name}",
            content = content,
            filePath = dir.path,
            priority = 45,
        )
    }

    fun invalidateCache() {
        cachedTree = null
    }

    private fun buildTree(
        dir: VirtualFile,
        sb: StringBuilder,
        prefix: String,
        maxDepth: Int,
        currentDepth: Int,
    ) {
        if (currentDepth >= maxDepth) return

        val children = dir.children
            ?.filter { !isIgnored(it.name) }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            ?: return

        for ((index, child) in children.withIndex()) {
            val isLast = index == children.size - 1
            val connector = if (isLast) "└── " else "├── "
            val extension = if (isLast) "    " else "│   "

            val suffix = if (child.isDirectory) "/" else ""
            sb.appendLine("$prefix$connector${child.name}$suffix")

            if (child.isDirectory) {
                buildTree(
                    child, sb,
                    prefix + extension,
                    maxDepth,
                    currentDepth + 1,
                )
            }
        }
    }

    private fun isIgnored(name: String): Boolean {
        return name in IGNORED_NAMES
    }

    companion object {
        private val IGNORED_NAMES = setOf(
            ".git", ".idea", ".gradle",
            "build", "out", "node_modules",
            ".spec-coding", "__pycache__",
            ".DS_Store", "Thumbs.db",
        )

        fun getInstance(project: Project): ProjectStructureScanner {
            return project.getService(ProjectStructureScanner::class.java)
        }
    }
}
