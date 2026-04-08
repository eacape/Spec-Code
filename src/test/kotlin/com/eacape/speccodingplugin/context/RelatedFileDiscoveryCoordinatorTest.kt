package com.eacape.speccodingplugin.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class RelatedFileDiscoveryCoordinatorTest {

    private val basePath = Path.of("repo").toAbsolutePath().normalize()

    @Test
    fun `discoverHeuristicLayer should resolve jvm static import back to owning file`() {
        val helperFile = basePath.resolve("src/main/kotlin/demo/Helper.kt").normalize()

        val result = RelatedFileDiscoveryCoordinator.discoverHeuristicLayer(
            content = "import demo.Helper.run",
            context = discoveryContext(
                activeFilePath = basePath.resolve("src/main/kotlin/demo/Main.kt"),
                language = RelatedFileDiscoveryLanguage.JVM,
            ),
            resolveFile = resolverFor(helperFile),
        )

        assertEquals(1, result.referenceCount)
        assertEquals(1, result.resolvedReferenceCount)
        assertEquals(emptyList<String>(), result.unresolvedReferences)
        assertEquals(listOf(helperFile.toString()), result.items.mapNotNull { it.filePath })
    }

    @Test
    fun `discoverHeuristicLayer should resolve relative typescript imports from active file directory`() {
        val bootstrapFile = basePath.resolve("src/web/app/bootstrap.ts").normalize()
        val sharedHelperFile = basePath.resolve("src/web/shared/helper.ts").normalize()

        val result = RelatedFileDiscoveryCoordinator.discoverHeuristicLayer(
            content = """
                import "./bootstrap"
                import { helper } from "../shared/helper"
            """.trimIndent(),
            context = discoveryContext(
                activeFilePath = basePath.resolve("src/web/app/main.ts"),
                language = RelatedFileDiscoveryLanguage.TYPESCRIPT,
            ),
            resolveFile = resolverFor(bootstrapFile, sharedHelperFile),
        )

        assertEquals(2, result.referenceCount)
        assertEquals(2, result.resolvedReferenceCount)
        assertEquals(
            listOf(bootstrapFile.toString(), sharedHelperFile.toString()),
            result.items.mapNotNull { it.filePath },
        )
    }

    @Test
    fun `discoverHeuristicLayer should resolve relative python from import to symbol file`() {
        val formatterFile = basePath.resolve("src/main/python/app/utils/formatter.py").normalize()

        val result = RelatedFileDiscoveryCoordinator.discoverHeuristicLayer(
            content = "from .utils import formatter",
            context = discoveryContext(
                activeFilePath = basePath.resolve("src/main/python/app/main.py"),
                language = RelatedFileDiscoveryLanguage.PYTHON,
            ),
            resolveFile = resolverFor(formatterFile),
        )

        assertEquals(1, result.referenceCount)
        assertEquals(1, result.resolvedReferenceCount)
        assertEquals(listOf(formatterFile.toString()), result.items.mapNotNull { it.filePath })
    }

    @Test
    fun `merge should expose unresolved heuristic references and semantic skipped layer`() {
        val heuristic = RelatedFileLayerResult(
            items = listOf(
                ContextItem(
                    type = ContextType.IMPORT_DEPENDENCY,
                    label = "Helper.kt",
                    content = "",
                    filePath = "/repo/src/main/kotlin/demo/Helper.kt",
                    priority = 40,
                ),
            ),
            referenceCount = 2,
            resolvedReferenceCount = 1,
            unresolvedReferences = listOf("typescript:./missing"),
        )

        val result = RelatedFileDiscoveryCoordinator.merge(
            heuristicResult = heuristic,
            semanticResult = RelatedFileLayerResult.skipped("unavailable"),
        )

        assertEquals(2, result.heuristicReferenceCount)
        assertEquals(1, result.heuristicResolvedCount)
        assertEquals(listOf("typescript:./missing"), result.unresolvedReferences)
        assertTrue(result.skippedLayers.contains("semantic:unavailable"))
    }

    private fun discoveryContext(
        activeFilePath: Path,
        language: RelatedFileDiscoveryLanguage,
    ): RelatedFileDiscoveryContext {
        return RelatedFileDiscoveryContext(
            basePath = basePath,
            activeFilePath = activeFilePath.toAbsolutePath().normalize(),
            language = language,
        )
    }

    private fun resolverFor(vararg existingFiles: Path): (Path) -> RelatedFileResolvedFile? {
        val filesByPath = existingFiles
            .map { it.toAbsolutePath().normalize() }
            .associateBy { it }
        return { candidate ->
            val normalizedCandidate = candidate.toAbsolutePath().normalize()
            filesByPath[normalizedCandidate]?.let { resolved ->
                RelatedFileResolvedFile(
                    path = resolved.toString(),
                    name = resolved.fileName.toString(),
                )
            }
        }
    }
}
