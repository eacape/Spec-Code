package com.eacape.speccodingplugin.telemetry

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private enum class ExternalProcessCategory(val label: String) {
    CLI("cli"),
    HOOK("hook"),
    MCP("mcp"),
    GIT("git"),
    VERIFY("verify"),
    WORKFLOW("workflow"),
}

private enum class ExternalProcessThreadExpectation(val label: String) {
    BACKGROUND_ONLY("background-only"),
    BACKGROUND_PREFERRED("background-preferred"),
    MIXED_OR_UNKNOWN("mixed-or-unknown"),
}

private enum class ExternalProcessMainThreadRisk(val label: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

private data class ExternalProcessInventoryRule(
    val relativePath: String,
    val category: ExternalProcessCategory,
    val occurrenceCount: Int,
    val threadExpectation: ExternalProcessThreadExpectation,
    val mainThreadRisk: ExternalProcessMainThreadRisk,
    val summary: String,
)

private data class DiscoveredProcessBuilderUsage(
    val relativePath: String,
    val occurrenceCount: Int,
)

private object ExternalProcessInventoryContract {
    val requiredCategories = setOf(
        ExternalProcessCategory.CLI,
        ExternalProcessCategory.HOOK,
        ExternalProcessCategory.MCP,
        ExternalProcessCategory.GIT,
        ExternalProcessCategory.VERIFY,
    )

    val rules = listOf(
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/core/GitCliProcessRuntime.kt",
            category = ExternalProcessCategory.GIT,
            occurrenceCount = 1,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_ONLY,
            mainThreadRisk = ExternalProcessMainThreadRisk.MEDIUM,
            summary = "Shared git runtime for hook polling, worktree operations, and team asset sync.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/engine/CliCommandRuntime.kt",
            category = ExternalProcessCategory.CLI,
            occurrenceCount = 1,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_ONLY,
            mainThreadRisk = ExternalProcessMainThreadRisk.LOW,
            summary = "Shared CLI runtime for engine execution, discovery probes, version checks, and Windows cmd fallback.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/hook/HookCommandRuntime.kt",
            category = ExternalProcessCategory.HOOK,
            occurrenceCount = 1,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_ONLY,
            mainThreadRisk = ExternalProcessMainThreadRisk.LOW,
            summary = "Shared hook RUN_COMMAND runtime with merged-output timeout handling and structured startup diagnostics.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/mcp/McpServerProcessRuntime.kt",
            category = ExternalProcessCategory.MCP,
            occurrenceCount = 1,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_ONLY,
            mainThreadRisk = ExternalProcessMainThreadRisk.LOW,
            summary = "Shared MCP server launch runtime with Windows command resolution and structured startup diagnostics.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/spec/VerifyCommandRuntime.kt",
            category = ExternalProcessCategory.VERIFY,
            occurrenceCount = 1,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_PREFERRED,
            mainThreadRisk = ExternalProcessMainThreadRisk.MEDIUM,
            summary = "VERIFY command runtime with timeout and split stdout/stderr truncation handling.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/core/WorkflowCommandProcessRuntime.kt",
            category = ExternalProcessCategory.WORKFLOW,
            occurrenceCount = 1,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_ONLY,
            mainThreadRisk = ExternalProcessMainThreadRisk.MEDIUM,
            summary = "Shared workflow shell command runtime with stop/dispose semantics and structured startup diagnostics extracted out of the UI runner.",
        ),
    )

    fun discoverProcessBuilderUsages(): List<DiscoveredProcessBuilderUsage> {
        Files.walk(mainSourceRoot).use { stream ->
            return stream
                .filter { path ->
                    Files.isRegularFile(path) &&
                        path.fileName.toString().endsWith(".kt")
                }
                .toList()
                .mapNotNull { path ->
                    val occurrenceCount = processBuilderRegex.findAll(Files.readString(path)).count()
                    if (occurrenceCount == 0) {
                        null
                    } else {
                        DiscoveredProcessBuilderUsage(
                            relativePath = projectRoot.relativize(path.toAbsolutePath().normalize())
                                .toString()
                                .replace(File.separatorChar, '/'),
                            occurrenceCount = occurrenceCount,
                        )
                    }
                }
                .sortedBy(DiscoveredProcessBuilderUsage::relativePath)
        }
    }

    fun renderInventorySnapshot(
        discovered: List<DiscoveredProcessBuilderUsage>,
    ): String {
        val discoveredByPath = discovered.associateBy(DiscoveredProcessBuilderUsage::relativePath)
        return rules
            .sortedWith(
                compareBy<ExternalProcessInventoryRule> { it.category.label }
                    .thenBy { it.relativePath },
            )
            .joinToString("\n") { rule ->
                val actualCount = discoveredByPath[rule.relativePath]?.occurrenceCount ?: 0
                "- ${rule.category.label} | ${rule.threadExpectation.label} | risk=${rule.mainThreadRisk.label} | " +
                    "ProcessBuilder x$actualCount | ${rule.relativePath} | ${rule.summary}"
            }
    }

    private val projectRoot: Path = Path.of("").toAbsolutePath().normalize()
    private val mainSourceRoot: Path = projectRoot.resolve("src/main/kotlin")
    private val processBuilderRegex = Regex("""\bProcessBuilder\s*\(""")
}

class ExternalProcessInventoryContractTest {

    @Test
    fun `every ProcessBuilder source should be cataloged with risk metadata`() {
        val discovered = ExternalProcessInventoryContract.discoverProcessBuilderUsages()
        val configuredByPath = ExternalProcessInventoryContract.rules.associateBy { it.relativePath }
        val discoveredByPath = discovered.associateBy { it.relativePath }

        val missing = discoveredByPath.keys - configuredByPath.keys
        val stale = configuredByPath.keys - discoveredByPath.keys
        val countMismatches = (discoveredByPath.keys intersect configuredByPath.keys)
            .mapNotNull { relativePath ->
                val actual = discoveredByPath.getValue(relativePath).occurrenceCount
                val expected = configuredByPath.getValue(relativePath).occurrenceCount
                if (actual == expected) {
                    null
                } else {
                    "$relativePath expected $expected ProcessBuilder callsites but found $actual"
                }
            }

        assertTrue(
            missing.isEmpty() && stale.isEmpty() && countMismatches.isEmpty(),
            buildString {
                appendLine("External process inventory drift detected.")
                appendLine("Every Kotlin source that constructs ProcessBuilder must be classified with category, thread expectation, and main-thread risk.")
                if (missing.isNotEmpty()) {
                    appendLine("Missing inventory rules: ${missing.joinToString(", ")}")
                }
                if (stale.isNotEmpty()) {
                    appendLine("Stale inventory rules: ${stale.joinToString(", ")}")
                }
                if (countMismatches.isNotEmpty()) {
                    appendLine("Count mismatches:")
                    countMismatches.forEach(::appendLine)
                }
                appendLine("Current configured inventory:")
                appendLine(ExternalProcessInventoryContract.renderInventorySnapshot(discovered))
            },
        )
    }

    @Test
    fun `inventory should cover required external process categories`() {
        val configuredCategories = ExternalProcessInventoryContract.rules
            .map { it.category }
            .toSet()
        val missingCategories = ExternalProcessInventoryContract.requiredCategories - configuredCategories

        assertTrue(
            missingCategories.isEmpty(),
            "Missing external process categories: ${missingCategories.joinToString { it.label }}",
        )
    }

    @Test
    fun `ui package should not own raw ProcessBuilder launches`() {
        val uiOwnedRules = ExternalProcessInventoryContract.rules.filter { "/ui/" in it.relativePath }

        assertTrue(
            uiOwnedRules.isEmpty(),
            "Raw ProcessBuilder launches should no longer live under src/main/kotlin/.../ui. Remaining UI-owned rules: ${uiOwnedRules.joinToString { it.relativePath }}",
        )
    }
}
