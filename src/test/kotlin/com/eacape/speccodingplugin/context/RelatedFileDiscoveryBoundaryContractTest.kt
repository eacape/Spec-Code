package com.eacape.speccodingplugin.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

class RelatedFileDiscoveryBoundaryContractTest {

    @Test
    fun `related file discovery service should delegate active editor snapshot resolution`() {
        val source = Files.readString(
            Paths.relatedFileDiscoveryService,
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("RelatedFileDiscoveryRequestResolver.resolveFromActiveEditor(project)"))
        assertTrue(source.contains("RelatedFileDiscoveryCoordinator.discoverHeuristicLayer("))
        assertTrue(source.contains("semanticResolver.resolve(request.context)"))
        assertFalse(source.contains("FileEditorManager.getInstance(project).selectedTextEditor"))
        assertFalse(source.contains("ReadAction.compute"))
    }

    @Test
    fun `related file discovery request resolver should own active editor snapshot wiring`() {
        val source = Files.readString(
            Paths.relatedFileDiscoveryRequestResolver,
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("FileEditorManager.getInstance(project).selectedTextEditor"))
        assertTrue(source.contains("ReadAction.compute"))
        assertTrue(source.contains("editor.document.modificationStamp"))
    }

    @Test
    fun `ui main sources should not access related file discovery directly`() {
        val usageFiles = discoverUsages(
            root = Paths.uiMainRoot,
            pattern = Regex("""\bRelatedFileDiscovery(?:Coordinator)?\b"""),
        )

        assertEquals(emptyList<String>(), usageFiles)
    }

    @Test
    fun `main sources should access related file discovery service only through context collector`() {
        val usageFiles = discoverUsages(
            root = Paths.mainRoot,
            pattern = Regex("""\bRelatedFileDiscovery\.getInstance\("""),
        )

        assertEquals(
            listOf("com/eacape/speccodingplugin/context/ContextCollector.kt"),
            usageFiles,
        )
    }

    @Test
    fun `main sources should invoke heuristic coordinator only from related file discovery service`() {
        val usageFiles = discoverUsages(
            root = Paths.mainRoot,
            pattern = Regex("""\bRelatedFileDiscoveryCoordinator\."""),
            excluded = setOf(Paths.relatedFileDiscoveryCoordinator),
        )

        assertEquals(
            listOf("com/eacape/speccodingplugin/context/RelatedFileDiscovery.kt"),
            usageFiles,
        )
    }

    private fun discoverUsages(
        root: Path,
        pattern: Regex,
        excluded: Set<Path> = emptySet(),
    ): List<String> {
        Files.walk(root).use { stream ->
            return stream
                .filter { path ->
                    Files.isRegularFile(path) &&
                        path.fileName.toString().endsWith(".kt") &&
                        path !in excluded
                }
                .map { path -> path.normalize() }
                .filter { path ->
                    pattern.containsMatchIn(Files.readString(path, StandardCharsets.UTF_8))
                }
                .map { path -> root.relativize(path).invariantSeparatorsPathString }
                .sorted()
                .toList()
        }
    }

    private object Paths {
        val mainRoot: Path = Path.of("src", "main", "kotlin")
        val uiMainRoot: Path = mainRoot.resolve("com/eacape/speccodingplugin/ui")
        val relatedFileDiscoveryService: Path =
            mainRoot.resolve("com/eacape/speccodingplugin/context/RelatedFileDiscovery.kt")
        val relatedFileDiscoveryCoordinator: Path =
            mainRoot.resolve("com/eacape/speccodingplugin/context/RelatedFileDiscoveryCoordinator.kt")
        val relatedFileDiscoveryRequestResolver: Path =
            mainRoot.resolve("com/eacape/speccodingplugin/context/RelatedFileDiscoveryRequestResolver.kt")
    }
}
