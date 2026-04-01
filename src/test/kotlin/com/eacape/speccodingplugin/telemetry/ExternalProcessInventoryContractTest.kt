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
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/engine/CliDiscoveryService.kt",
            category = ExternalProcessCategory.CLI,
            occurrenceCount = 3,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_ONLY,
            mainThreadRisk = ExternalProcessMainThreadRisk.MEDIUM,
            summary = "CLI discovery probes and login-shell PATH fallback.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/engine/CliEngine.kt",
            category = ExternalProcessCategory.CLI,
            occurrenceCount = 2,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_ONLY,
            mainThreadRisk = ExternalProcessMainThreadRisk.LOW,
            summary = "Primary CLI execution path with Windows fallback and env overrides.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/engine/ClaudeCodeEngine.kt",
            category = ExternalProcessCategory.CLI,
            occurrenceCount = 3,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_PREFERRED,
            mainThreadRisk = ExternalProcessMainThreadRisk.MEDIUM,
            summary = "Claude CLI output/version probes plus Windows cmd fallback.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/engine/OpenAiCodexEngine.kt",
            category = ExternalProcessCategory.CLI,
            occurrenceCount = 1,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_PREFERRED,
            mainThreadRisk = ExternalProcessMainThreadRisk.MEDIUM,
            summary = "Codex CLI version probe for environment readiness.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/hook/HookExecutor.kt",
            category = ExternalProcessCategory.HOOK,
            occurrenceCount = 1,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_ONLY,
            mainThreadRisk = ExternalProcessMainThreadRisk.LOW,
            summary = "User-configured hook command execution on IO dispatcher.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/hook/HookGitCommitWatcher.kt",
            category = ExternalProcessCategory.GIT,
            occurrenceCount = 1,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_ONLY,
            mainThreadRisk = ExternalProcessMainThreadRisk.MEDIUM,
            summary = "Git HEAD polling watcher with repeated background process churn.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/mcp/McpClient.kt",
            category = ExternalProcessCategory.MCP,
            occurrenceCount = 1,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_ONLY,
            mainThreadRisk = ExternalProcessMainThreadRisk.LOW,
            summary = "MCP server bootstrap and lifecycle management on IO dispatcher.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/prompt/TeamPromptSyncService.kt",
            category = ExternalProcessCategory.GIT,
            occurrenceCount = 1,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_PREFERRED,
            mainThreadRisk = ExternalProcessMainThreadRisk.MEDIUM,
            summary = "Team prompt git sync executor for shared prompt assets.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/skill/TeamSkillSyncService.kt",
            category = ExternalProcessCategory.GIT,
            occurrenceCount = 1,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_PREFERRED,
            mainThreadRisk = ExternalProcessMainThreadRisk.MEDIUM,
            summary = "Team skill git sync executor for shared skill assets.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/spec/SpecProcessRunner.kt",
            category = ExternalProcessCategory.VERIFY,
            occurrenceCount = 1,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_PREFERRED,
            mainThreadRisk = ExternalProcessMainThreadRisk.MEDIUM,
            summary = "VERIFY command runner with timeout, truncation, and redaction.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanelWorkflowCommandRunner.kt",
            category = ExternalProcessCategory.WORKFLOW,
            occurrenceCount = 1,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_ONLY,
            mainThreadRisk = ExternalProcessMainThreadRisk.HIGH,
            summary = "Workflow shell command runtime extracted behind a UI-owned runner, pending broader process unification.",
        ),
        ExternalProcessInventoryRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/worktree/CliGitWorktreeExecutor.kt",
            category = ExternalProcessCategory.GIT,
            occurrenceCount = 1,
            threadExpectation = ExternalProcessThreadExpectation.BACKGROUND_ONLY,
            mainThreadRisk = ExternalProcessMainThreadRisk.LOW,
            summary = "Git worktree executor isolated behind IO-bound coroutine entry points.",
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
    fun `ui owned process launches should stay marked as high main thread risk`() {
        val uiOwnedRules = ExternalProcessInventoryContract.rules.filter { "/ui/" in it.relativePath }
        val nonHighRisk = uiOwnedRules.filter { it.mainThreadRisk != ExternalProcessMainThreadRisk.HIGH }

        assertTrue(
            uiOwnedRules.isNotEmpty(),
            "Expected at least one UI-owned ProcessBuilder inventory rule.",
        )
        assertTrue(
            nonHighRisk.isEmpty(),
            buildString {
                appendLine("UI-owned external process entry points must stay HIGH main-thread risk until they are extracted out of Swing panels.")
                nonHighRisk.forEach { rule ->
                    appendLine("- ${rule.relativePath} -> ${rule.mainThreadRisk.label}")
                }
            },
        )
    }
}
