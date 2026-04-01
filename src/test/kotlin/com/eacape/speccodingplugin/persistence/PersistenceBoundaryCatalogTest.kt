package com.eacape.speccodingplugin.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class PersistenceBoundaryCatalogTest {

    @Test
    fun `catalog should define mandatory persistence stores`() {
        val boundariesById = PersistenceBoundaryCatalog.storeBoundaries.associateBy { it.id }

        assertNotNull(boundariesById[PersistenceBoundaryCatalog.StoreId.SESSION_MANAGER])
        assertNotNull(boundariesById[PersistenceBoundaryCatalog.StoreId.SPEC_STORAGE])
        assertNotNull(boundariesById[PersistenceBoundaryCatalog.StoreId.CHANGESET_STORE])

        boundariesById.values.forEach { boundary ->
            assertTrue(boundary.summary.isNotBlank(), "Boundary ${boundary.id} summary must not be blank")
            assertTrue(boundary.persistedState.isNotBlank(), "Boundary ${boundary.id} persistedState must not be blank")
            assertTrue(boundary.apiMethods.isNotEmpty(), "Boundary ${boundary.id} apiMethods must not be empty")
            assertTrue(boundary.allowedCoordinators.isNotEmpty(), "Boundary ${boundary.id} allowedCoordinators must not be empty")
            assertTrue(boundary.uiGuardrail.isNotBlank(), "Boundary ${boundary.id} uiGuardrail must not be blank")
        }
    }

    @Test
    fun `every public persistence api should belong to exactly one store boundary`() {
        val assignments = PersistenceBoundaryCatalog.storeBoundaries
            .flatMap { boundary -> boundary.apiMethods.map { methodName -> methodName to boundary.id } }
            .groupBy({ it.first }, { it.second })

        val duplicatedAssignments = assignments
            .filterValues { ids -> ids.distinct().size > 1 }
            .mapValues { (_, ids) -> ids.distinct().sortedBy { it.name } }

        assertTrue(
            duplicatedAssignments.isEmpty(),
            "Persistence API methods assigned to multiple store boundaries: $duplicatedAssignments",
        )

        val discovered = PersistenceBoundaryCatalog.storeBoundaries
            .flatMap { boundary ->
                declaredPublicApiMethods(boundary.sourceRelativePath).map { methodName -> methodName to boundary.id }
            }
            .groupBy({ it.first }, { it.second })

        val missing = discovered.keys - PersistenceBoundaryCatalog.coveredApiMethods
        val stale = PersistenceBoundaryCatalog.coveredApiMethods - discovered.keys

        assertTrue(
            missing.isEmpty() && stale.isEmpty(),
            buildString {
                appendLine("Persistence boundary catalog drift detected.")
                appendLine("Every public persistence API should belong to exactly one store boundary.")
                if (missing.isNotEmpty()) {
                    appendLine("Missing methods: ${missing.sorted().joinToString(", ")}")
                }
                if (stale.isNotEmpty()) {
                    appendLine("Stale catalog methods: ${stale.sorted().joinToString(", ")}")
                }
            },
        )
    }

    @Test
    fun `only cataloged main sources may coordinate multiple persistence stores`() {
        val discovered = discoverMultiStoreSourceUsage(mainSourceRoot)
        val configured = PersistenceBoundaryCatalog.crossStoreCoordinators
            .associateBy(PersistenceBoundaryCatalog.CrossStoreCoordinatorRule::relativePath)

        val missing = discovered.keys - configured.keys
        val stale = configured.keys - discovered.keys
        val mismatches = (discovered.keys intersect configured.keys)
            .mapNotNull { relativePath ->
                val actual = discovered.getValue(relativePath)
                val expected = configured.getValue(relativePath).stores
                if (actual == expected) {
                    null
                } else {
                    "$relativePath expected ${expected.sortedStoreLabels()} but found ${actual.sortedStoreLabels()}"
                }
            }

        assertTrue(
            missing.isEmpty() && stale.isEmpty() && mismatches.isEmpty(),
            buildString {
                appendLine("Persistence cross-store coordinator drift detected.")
                appendLine("Any production source that touches more than one persistence store must be cataloged explicitly.")
                if (missing.isNotEmpty()) {
                    appendLine("Missing coordinator rules: ${missing.sorted().joinToString(", ")}")
                }
                if (stale.isNotEmpty()) {
                    appendLine("Stale coordinator rules: ${stale.sorted().joinToString(", ")}")
                }
                if (mismatches.isNotEmpty()) {
                    appendLine("Store mismatches:")
                    mismatches.forEach(::appendLine)
                }
            },
        )
    }

    @Test
    fun `ui persistence access should stay cataloged and avoid direct spec storage imports`() {
        val discovered = discoverDirectUiStoreUsage()
        val configured = PersistenceBoundaryCatalog.uiDirectAccessRules
            .associateBy(PersistenceBoundaryCatalog.UiDirectAccessRule::relativePath)

        val missing = discovered.keys - configured.keys
        val stale = configured.keys - discovered.keys
        val mismatches = (discovered.keys intersect configured.keys)
            .mapNotNull { relativePath ->
                val actual = discovered.getValue(relativePath)
                val expected = configured.getValue(relativePath).stores
                if (actual == expected) {
                    null
                } else {
                    "$relativePath expected ${expected.sortedStoreLabels()} but found ${actual.sortedStoreLabels()}"
                }
            }
        val forbidden = discovered
            .filterValues { stores -> stores.any { it in PersistenceBoundaryCatalog.forbiddenUiStores } }
            .keys

        assertTrue(
            missing.isEmpty() && stale.isEmpty() && mismatches.isEmpty() && forbidden.isEmpty(),
            buildString {
                appendLine("UI persistence access drift detected.")
                appendLine("UI-owned store access must stay cataloged, and UI should not import SpecStorage directly.")
                if (missing.isNotEmpty()) {
                    appendLine("Missing UI access rules: ${missing.sorted().joinToString(", ")}")
                }
                if (stale.isNotEmpty()) {
                    appendLine("Stale UI access rules: ${stale.sorted().joinToString(", ")}")
                }
                if (mismatches.isNotEmpty()) {
                    appendLine("Store mismatches:")
                    mismatches.forEach(::appendLine)
                }
                if (forbidden.isNotEmpty()) {
                    appendLine("Forbidden UI store usage: ${forbidden.sorted().joinToString(", ")}")
                }
            },
        )
    }

    @Test
    fun `production sources should use shared project services for session and changeset persistence`() {
        val directConstructionPatterns = listOf(
            "SessionManager(project)" to "SessionManager",
            "ChangesetStore(project)" to "ChangesetStore",
        )

        val offenders = mutableListOf<String>()

        Files.walk(mainSourceRoot).use { stream ->
            stream
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
                .forEach { path ->
                    val source = Files.readString(path)
                    directConstructionPatterns.forEach { (pattern, typeName) ->
                        if (source.contains(pattern)) {
                            offenders += "${relativePath(path)} -> $typeName"
                        }
                    }
                }
        }

        assertTrue(
            offenders.isEmpty(),
            "Direct production construction of project persistence services detected:\n${offenders.joinToString("\n")}",
        )
    }

    @Test
    fun `task execution service should depend on shared session manager service`() {
        val source = Files.readString(
            projectRoot.resolve(
                Path.of(
                    "src",
                    "main",
                    "kotlin",
                    "com",
                    "eacape",
                    "speccodingplugin",
                    "spec",
                    "SpecTaskExecutionService.kt",
                ),
            ),
        )

        assertTrue(source.contains("_sessionManagerOverride ?: SessionManager.getInstance(project)"))
        assertTrue(source.contains("private val sessionManager: SessionManager by lazy"))
    }

    @Test
    fun `catalog should not keep ui multi store exceptions`() {
        val uiExceptionRules = PersistenceBoundaryCatalog.crossStoreCoordinators
            .filter { rule -> "/ui/" in rule.relativePath.replace(File.separatorChar, '/') }

        assertEquals(emptyList<PersistenceBoundaryCatalog.CrossStoreCoordinatorRule>(), uiExceptionRules)
    }

    private fun declaredPublicApiMethods(relativePath: String): Set<String> {
        val methodPattern = Regex("^ {4}(?:suspend )?fun ([A-Za-z0-9_]+)\\(", setOf(RegexOption.MULTILINE))
        val source = Files.readString(projectRoot.resolve(relativePath))
        val className = relativePath.substringAfterLast('/').removeSuffix(".kt")
        val classSource = extractTopLevelClassSource(source, className)
        return methodPattern.findAll(classSource)
            .map { match -> match.groupValues[1] }
            .toSet()
    }

    private fun discoverMultiStoreSourceUsage(root: Path): Map<String, Set<PersistenceBoundaryCatalog.StoreId>> {
        val discovered = linkedMapOf<String, Set<PersistenceBoundaryCatalog.StoreId>>()
        Files.walk(root).use { stream ->
            stream
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
                .forEach { path ->
                    val source = Files.readString(path)
                    val matchedStores = storeUsagePatterns
                        .mapNotNull { (storeId, pattern) -> storeId.takeIf { pattern.containsMatchIn(source) } }
                        .toSet()
                    if (matchedStores.size > 1) {
                        discovered[relativePath(path)] = matchedStores
                    }
                }
        }
        return discovered
    }

    private fun discoverDirectUiStoreUsage(): Map<String, Set<PersistenceBoundaryCatalog.StoreId>> {
        val discovered = linkedMapOf<String, Set<PersistenceBoundaryCatalog.StoreId>>()
        Files.walk(uiSourceRoot).use { stream ->
            stream
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
                .forEach { path ->
                    val source = Files.readString(path)
                    val matchedStores = storeUsagePatterns
                        .mapNotNull { (storeId, pattern) -> storeId.takeIf { pattern.containsMatchIn(source) } }
                        .toSet()
                    if (matchedStores.isNotEmpty()) {
                        discovered[relativePath(path)] = matchedStores
                    }
                }
        }
        return discovered
    }

    private fun relativePath(path: Path): String {
        return projectRoot.relativize(path.toAbsolutePath().normalize())
            .toString()
            .replace(File.separatorChar, '/')
    }

    private fun extractTopLevelClassSource(source: String, className: String): String {
        val classMatch = Regex("""class\s+$className\b""").find(source)
            ?: error("Could not find class declaration for $className")
        val bodyStart = source.indexOf('{', classMatch.range.last)
        require(bodyStart >= 0) { "Could not find class body for $className" }

        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return source.substring(bodyStart + 1, index)
                    }
                }
            }
        }
        error("Could not locate end of class body for $className")
    }

    private fun Set<PersistenceBoundaryCatalog.StoreId>.sortedStoreLabels(): String {
        return this
            .map { it.name }
            .sorted()
            .joinToString(", ")
    }

    companion object {
        private val projectRoot: Path = Path.of("").toAbsolutePath().normalize()
        private val mainSourceRoot: Path = projectRoot.resolve("src/main/kotlin")
        private val uiSourceRoot: Path = projectRoot.resolve(
            Path.of(
                "src",
                "main",
                "kotlin",
                "com",
                "eacape",
                "speccodingplugin",
                "ui",
            ),
        )

        private val storeUsagePatterns: Map<PersistenceBoundaryCatalog.StoreId, Regex> = mapOf(
            PersistenceBoundaryCatalog.StoreId.SESSION_MANAGER to Regex("""\bSessionManager\b\s*(?:[<:?]|\.getInstance\b|\()"""),
            PersistenceBoundaryCatalog.StoreId.SPEC_STORAGE to Regex("""\bSpecStorage\b\s*(?:[<:?]|\.getInstance\b|\()"""),
            PersistenceBoundaryCatalog.StoreId.CHANGESET_STORE to Regex("""\bChangesetStore\b\s*(?:[<:?]|\.getInstance\b|\()"""),
        )
    }
}
