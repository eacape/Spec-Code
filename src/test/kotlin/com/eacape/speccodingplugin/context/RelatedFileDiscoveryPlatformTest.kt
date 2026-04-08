package com.eacape.speccodingplugin.context

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class RelatedFileDiscoveryPlatformTest : BasePlatformTestCase() {

    fun `test discoverRelatedFilesDetailed should cache repeated relative typescript lookups`() {
        createProjectFile(
            "src/web/shared/helper.ts",
            """
            export const helper = () => "ok"
            """.trimIndent(),
        )
        createProjectFile(
            "src/web/app/bootstrap.ts",
            """
            export const bootstrap = () => Unit
            """.trimIndent(),
        )
        val mainFile = createProjectFile(
            "src/web/app/main.ts",
            """
            import "./bootstrap"
            import { helper } from "../shared/helper"

            helper()
            """.trimIndent(),
        )
        openInEditor(mainFile)

        val service = RelatedFileDiscovery.getInstance(project)
        val firstResult = service.discoverRelatedFilesDetailed()
        val secondResult = service.discoverRelatedFilesDetailed()
        val cacheStats = service.cacheStatsSnapshot()

        assertSame(firstResult, secondResult)
        assertEquals(1, cacheStats.hitCount)
        assertEquals(1, cacheStats.missCount)
        assertEquals(2, secondResult.items.size)
        assertTrue(secondResult.items.mapNotNull { it.filePath }.any { it.endsWith("bootstrap.ts") })
        assertTrue(secondResult.items.mapNotNull { it.filePath }.any { it.endsWith("helper.ts") })
        assertTrue(secondResult.skippedLayers.contains("semantic:unavailable"))
    }

    fun `test discoverRelatedFilesDetailed should invalidate cached result after dependency rename`() {
        val formatterFile = createProjectFile(
            "src/main/python/app/utils/formatter.py",
            """
            def format_todo(value: str) -> str:
                return value.strip()
            """.trimIndent(),
        )
        val mainFile = createProjectFile(
            "src/main/python/app/main.py",
            """
            from .utils import formatter

            formatter.format_todo("hi")
            """.trimIndent(),
        )
        openInEditor(mainFile)

        val service = RelatedFileDiscovery.getInstance(project)
        val initialResult = service.discoverRelatedFilesDetailed()

        renameFile(formatterFile, "formatterRenamed.py")
        UIUtil.dispatchAllInvocationEvents()

        val updatedResult = service.discoverRelatedFilesDetailed()
        val cacheStats = service.cacheStatsSnapshot()

        assertEquals(1, initialResult.items.size)
        assertTrue(initialResult.items.single().filePath!!.endsWith("formatter.py"))
        assertTrue(updatedResult.items.isEmpty())
        assertEquals(2, cacheStats.missCount)
        assertTrue(cacheStats.lastInvalidationReason.startsWith("vfs-rename:"))
    }

    private fun openInEditor(file: VirtualFile) {
        myFixture.openFileInEditor(file)
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun renameFile(file: VirtualFile, newName: String) {
        WriteAction.runAndWait<Throwable> {
            file.rename(this@RelatedFileDiscoveryPlatformTest, newName)
        }
        UIUtil.dispatchAllInvocationEvents()
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
                current = current.findChild(segment) ?: current.createChildDirectory(this@RelatedFileDiscoveryPlatformTest, segment)
            }
            val fileName = segments.last()
            createdFile = current.findChild(fileName) ?: current.createChildData(this@RelatedFileDiscoveryPlatformTest, fileName)
            VfsUtil.saveText(createdFile!!, content)
        }
        return createdFile ?: error("Failed to create file $relativePath")
    }
}
