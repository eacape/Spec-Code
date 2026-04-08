package com.eacape.speccodingplugin.context

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class CodeGraphServicePlatformTest : BasePlatformTestCase() {

    fun `test buildFromActiveEditor should cache snapshots per graph options`() {
        createProjectFile(
            "src/main/kotlin/demo/HelperA.kt",
            """
            package demo

            fun helperA() = "A"
            """.trimIndent(),
        )
        createProjectFile(
            "src/main/kotlin/demo/HelperB.kt",
            """
            package demo

            fun helperB() = "B"
            """.trimIndent(),
        )
        val mainFile = createProjectFile(
            "src/main/kotlin/demo/Main.kt",
            """
            package demo

            fun runTask(): String {
                return helperA() + helperB()
            }
            """.trimIndent(),
        )
        openInEditor(mainFile)

        val service = CodeGraphService.getInstance(project)
        val narrowOptions = CodeGraphService.GraphBuildOptions(maxDependencies = 1, maxCallEdges = 10)
        val wideOptions = CodeGraphService.GraphBuildOptions(maxDependencies = 4, maxCallEdges = 10)

        val narrowSnapshot = service.buildFromActiveEditor(narrowOptions).getOrThrow()
        val repeatedNarrowSnapshot = service.buildFromActiveEditor(narrowOptions).getOrThrow()
        val wideSnapshot = service.buildFromActiveEditor(wideOptions).getOrThrow()
        val repeatedWideSnapshot = service.buildFromActiveEditor(wideOptions).getOrThrow()

        assertSame(narrowSnapshot, repeatedNarrowSnapshot)
        assertSame(wideSnapshot, repeatedWideSnapshot)
        assertNotSame(narrowSnapshot, wideSnapshot)
        assertEquals(1, narrowSnapshot.dependencyEdges().size)
        assertTrue(wideSnapshot.dependencyEdges().size >= 2)
    }

    fun `test buildFromActiveEditor should invalidate cached snapshot after active file content change`() {
        val mainFile = createProjectFile(
            "src/main/kotlin/demo/Main.kt",
            """
            package demo

            fun runTask() {
                prepare()
            }

            fun prepare() = Unit
            """.trimIndent(),
        )
        openInEditor(mainFile)

        val service = CodeGraphService.getInstance(project)
        val initialSnapshot = service.buildFromActiveEditor().getOrThrow()

        overwriteFile(
            mainFile,
            """
            package demo

            fun runTask() {
                prepare()
                finish()
            }

            fun prepare() = Unit

            fun finish() = Unit
            """.trimIndent(),
        )

        val updatedSnapshot = service.buildFromActiveEditor().getOrThrow()

        assertNotSame(initialSnapshot, updatedSnapshot)
        assertTrue(updatedSnapshot.callEdges().size > initialSnapshot.callEdges().size)
    }

    fun `test buildFromActiveEditor should invalidate cached dependency paths after VFS rename`() {
        val helperFile = createProjectFile(
            "src/main/kotlin/demo/Helper.kt",
            """
            package demo

            fun helper() = "ok"
            """.trimIndent(),
        )
        val mainFile = createProjectFile(
            "src/main/kotlin/demo/Main.kt",
            """
            package demo

            fun runTask(): String {
                return helper()
            }
            """.trimIndent(),
        )
        openInEditor(mainFile)

        val service = CodeGraphService.getInstance(project)
        val initialSnapshot = service.buildFromActiveEditor().getOrThrow()
        assertTrue(initialSnapshot.dependencyEdges().any { edge -> edge.toId.contains("Helper.kt") })

        renameFile(helperFile, "HelperRenamed.kt")

        val renamedSnapshot = service.buildFromActiveEditor().getOrThrow()

        assertNotSame(initialSnapshot, renamedSnapshot)
        assertTrue(renamedSnapshot.dependencyEdges().any { edge -> edge.toId.contains("HelperRenamed.kt") })
        assertFalse(renamedSnapshot.dependencyEdges().any { edge -> edge.toId.contains("Helper.kt") })
    }

    private fun openInEditor(file: VirtualFile) {
        myFixture.openFileInEditor(file)
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun overwriteFile(file: VirtualFile, content: String) {
        WriteAction.runAndWait<Throwable> {
            VfsUtil.saveText(file, content)
        }
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun renameFile(file: VirtualFile, newName: String) {
        WriteAction.runAndWait<Throwable> {
            file.rename(this@CodeGraphServicePlatformTest, newName)
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
                current = current.findChild(segment) ?: current.createChildDirectory(this@CodeGraphServicePlatformTest, segment)
            }
            val fileName = segments.last()
            createdFile = current.findChild(fileName) ?: current.createChildData(this@CodeGraphServicePlatformTest, fileName)
            VfsUtil.saveText(createdFile!!, content)
        }
        return createdFile ?: error("Failed to create file $relativePath")
    }
}
