package com.eacape.speccodingplugin.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class CliDiscoveryServicePathCandidateTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `buildCommonLocationCandidates should include mac paths`() {
        val candidates = CliDiscoveryService.buildCommonLocationCandidates(
            toolName = "codex",
            userHome = "/Users/alice",
            env = mapOf(
                "NPM_CONFIG_PREFIX" to "/Users/alice/.npm-packages",
                "NVM_BIN" to "/Users/alice/.nvm/versions/node/v22.0.0/bin",
                "VOLTA_HOME" to "/Users/alice/.volta",
                "BUN_INSTALL" to "/Users/alice/.bun-install",
            ),
            isWindows = false,
            isMac = true,
        ).map(::normalizePath)

        assertTrue(candidates.contains("/opt/homebrew/bin/codex"))
        assertTrue(candidates.contains("/usr/local/bin/codex"))
        assertTrue(candidates.contains("/Users/alice/.nvm/versions/node/v22.0.0/bin/codex"))
        assertTrue(candidates.contains("/Users/alice/.npm-packages/bin/codex"))
        assertTrue(candidates.contains("/Users/alice/.volta/bin/codex"))
        assertTrue(candidates.contains("/Users/alice/.bun-install/bin/codex"))
        assertTrue(candidates.contains("/Users/alice/.bun/bin/codex"))
        assertTrue(candidates.contains("/Users/alice/Library/pnpm/codex"))
    }

    @Test
    fun `buildClaudeCliPackageCandidates should include mac global install paths`() {
        val candidates = CliDiscoveryService.buildClaudeCliPackageCandidates(
            cliPath = "/opt/homebrew/bin/claude",
            userHome = "/Users/alice",
            env = mapOf(
                "NPM_CONFIG_PREFIX" to "/opt/homebrew",
                "NVM_BIN" to "/Users/alice/.nvm/versions/node/v22.0.0/bin",
            ),
            isMac = true,
        ).map(::normalizePath)

        assertTrue(candidates.contains("/opt/homebrew/lib/node_modules/@anthropic-ai/claude-code/cli.js"))
        assertTrue(candidates.contains("/Users/alice/.npm-global/lib/node_modules/@anthropic-ai/claude-code/cli.js"))
        assertTrue(candidates.contains("/Users/alice/.nvm/versions/node/v22.0.0/lib/node_modules/@anthropic-ai/claude-code/cli.js"))
    }

    @Test
    fun `buildAugmentedPathValue should prepend executable dir and include fallback bins`() {
        Files.createDirectories(tempDir.resolve(".nvm/versions/node/v22.0.0/bin"))
        Files.createDirectories(tempDir.resolve(".fnm/node-versions/v21.0.0/installation/bin"))

        val augmented = CliDiscoveryService.buildAugmentedPathValue(
            currentPath = listOf("/usr/bin", "/custom/current").joinToString(File.pathSeparator),
            userHome = normalizePath(tempDir.toString()),
            env = mapOf("BUN_INSTALL" to "${normalizePath(tempDir.toString())}/.bun-install"),
            isWindows = false,
            isMac = true,
            extraDirectories = listOf("/tool/bin"),
        )
        val entries = augmented.split(File.pathSeparator).map(::normalizePath)

        assertEquals("/tool/bin", entries.first())
        assertTrue(entries.contains("/usr/bin"))
        assertTrue(entries.contains("/custom/current"))
        assertTrue(entries.contains("${normalizePath(tempDir.toString())}/.bun-install/bin"))
        assertTrue(entries.contains("${normalizePath(tempDir.toString())}/.nvm/versions/node/v22.0.0/bin"))
        assertTrue(entries.contains("${normalizePath(tempDir.toString())}/.fnm/node-versions/v21.0.0/installation/bin"))
        assertTrue(entries.contains("/opt/homebrew/bin"))
    }

    @Test
    fun `buildShellLookupCommands should add interactive fallback on mac`() {
        val commands = CliDiscoveryService.buildShellLookupCommands(
            toolName = "codex",
            isMac = true,
        )

        assertEquals(listOf("-lc", "command -v codex"), commands[0])
        assertEquals(listOf("-ilc", "command -v codex"), commands[1])
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/')
}
