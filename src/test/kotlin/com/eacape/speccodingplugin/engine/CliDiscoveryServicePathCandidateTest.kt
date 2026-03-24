package com.eacape.speccodingplugin.engine

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliDiscoveryServicePathCandidateTest {

    @Test
    fun `buildCommonLocationCandidates should include mac paths`() {
        val candidates = CliDiscoveryService.buildCommonLocationCandidates(
            toolName = "codex",
            userHome = "/Users/alice",
            env = mapOf(
                "NPM_CONFIG_PREFIX" to "/Users/alice/.npm-packages",
                "NVM_BIN" to "/Users/alice/.nvm/versions/node/v22.0.0/bin",
                "VOLTA_HOME" to "/Users/alice/.volta",
            ),
            isWindows = false,
            isMac = true,
        ).map(::normalizePath)

        assertTrue(candidates.contains("/opt/homebrew/bin/codex"))
        assertTrue(candidates.contains("/usr/local/bin/codex"))
        assertTrue(candidates.contains("/Users/alice/.nvm/versions/node/v22.0.0/bin/codex"))
        assertTrue(candidates.contains("/Users/alice/.npm-packages/bin/codex"))
        assertTrue(candidates.contains("/Users/alice/.volta/bin/codex"))
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

    private fun normalizePath(path: String): String = path.replace('\\', '/')
}
