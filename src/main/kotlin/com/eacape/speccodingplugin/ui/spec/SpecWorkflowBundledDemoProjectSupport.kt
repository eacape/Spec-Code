package com.eacape.speccodingplugin.ui.spec

import com.intellij.openapi.application.PathManager
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class SpecWorkflowBundledDemoProject(
    val rootDirectory: Path,
    val readmePath: Path,
    val fileCount: Int,
)

internal object SpecWorkflowBundledDemoProjectSupport {
    private const val DEMO_DIRECTORY_NAME = "first-workflow-kotlin-todo"
    private const val README_RELATIVE_PATH = "README.md"

    private val resourceFiles = listOf(
        ResourceFile(
            relativePath = "README.md",
            resourcePath = "demo/$DEMO_DIRECTORY_NAME/README.md",
        ),
        ResourceFile(
            relativePath = "settings.gradle.kts",
            resourcePath = "demo/$DEMO_DIRECTORY_NAME/settings.gradle.kts",
        ),
        ResourceFile(
            relativePath = "build.gradle.kts",
            resourcePath = "demo/$DEMO_DIRECTORY_NAME/build.gradle.kts",
        ),
        ResourceFile(
            relativePath = "src/main/kotlin/demo/todo/TodoFormatter.kt",
            resourcePath = "demo/$DEMO_DIRECTORY_NAME/src/main/kotlin/demo/todo/TodoFormatter.kt",
        ),
        ResourceFile(
            relativePath = "src/test/kotlin/demo/todo/TodoFormatterTest.kt",
            resourcePath = "demo/$DEMO_DIRECTORY_NAME/src/test/kotlin/demo/todo/TodoFormatterTest.kt",
        ),
    )

    fun materializeDefault(): SpecWorkflowBundledDemoProject {
        return materialize(defaultRootDirectory())
    }

    internal fun materialize(rootDirectory: Path): SpecWorkflowBundledDemoProject {
        Files.createDirectories(rootDirectory)
        resourceFiles.forEach { file ->
            val destination = rootDirectory.resolve(file.relativePath)
            if (Files.exists(destination)) {
                return@forEach
            }
            destination.parent?.let(Files::createDirectories)
            resourceStream(file.resourcePath).use { input ->
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val readmePath = rootDirectory.resolve(README_RELATIVE_PATH)
        check(Files.exists(readmePath)) { "Bundled demo README missing at $readmePath" }
        return SpecWorkflowBundledDemoProject(
            rootDirectory = rootDirectory,
            readmePath = readmePath,
            fileCount = resourceFiles.size,
        )
    }

    internal fun expectedRelativePaths(): List<String> {
        return resourceFiles.map(ResourceFile::relativePath)
    }

    private fun defaultRootDirectory(): Path {
        return Path.of(PathManager.getSystemPath())
            .resolve("spec-code")
            .resolve("demo-projects")
            .resolve(DEMO_DIRECTORY_NAME)
    }

    private fun resourceStream(resourcePath: String): InputStream {
        return javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("Bundled demo resource not found: $resourcePath")
    }

    private data class ResourceFile(
        val relativePath: String,
        val resourcePath: String,
    )
}
