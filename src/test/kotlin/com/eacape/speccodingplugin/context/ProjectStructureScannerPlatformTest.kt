package com.eacape.speccodingplugin.context

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class ProjectStructureScannerPlatformTest : BasePlatformTestCase() {

    fun `test getProjectTree should cache results per maxDepth`() {
        createProjectFile("src/App.kt", "class App")
        val scanner = ProjectStructureScanner.getInstance(project)

        val shallowTree = scanner.getProjectTree(maxDepth = 1)
        val deepTree = scanner.getProjectTree(maxDepth = 2)

        assertTrue(shallowTree.contains("src/"))
        assertFalse(shallowTree.contains("App.kt"))
        assertTrue(deepTree.contains("App.kt"))
    }

    fun `test getProjectTree should invalidate cached tree after structural VFS change`() {
        createProjectFile("docs/alpha.md", "# alpha")
        val scanner = ProjectStructureScanner.getInstance(project)

        val initialTree = scanner.getProjectTree(maxDepth = 2)
        assertTrue(initialTree.contains("alpha.md"))
        assertFalse(initialTree.contains("beta.md"))

        createProjectFile("docs/beta.md", "# beta")
        UIUtil.dispatchAllInvocationEvents()

        val updatedTree = scanner.getProjectTree(maxDepth = 2)
        assertTrue(updatedTree.contains("beta.md"))
    }

    private fun createProjectFile(relativePath: String, content: String): VirtualFile {
        val baseDir = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)
            ?: error("Project base dir not found")
        val segments = relativePath.split('/').filter(String::isNotBlank)
        require(segments.isNotEmpty()) { "relativePath must not be blank" }

        var current = baseDir
        var createdFile: VirtualFile? = null
        WriteAction.runAndWait<Throwable> {
            segments.dropLast(1).forEach { segment ->
                current = current.findChild(segment) ?: current.createChildDirectory(this@ProjectStructureScannerPlatformTest, segment)
            }
            val fileName = segments.last()
            createdFile = current.findChild(fileName) ?: current.createChildData(this@ProjectStructureScannerPlatformTest, fileName)
            VfsUtil.saveText(createdFile!!, content)
        }
        return createdFile ?: error("Failed to create file $relativePath")
    }
}
