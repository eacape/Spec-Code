package com.eacape.speccodingplugin.engine

import java.nio.charset.StandardCharsets

/**
 * OpenAI Codex CLI 引擎适配
 */
class OpenAiCodexEngine(
    cliPath: String,
) : CliEngine(
    id = "openai-codex",
    displayName = "OpenAI Codex CLI",
    capabilities = setOf(
        EngineCapability.CODE_GENERATION,
        EngineCapability.CODE_REVIEW,
        EngineCapability.REFACTOR,
        EngineCapability.TEST_GENERATION,
        EngineCapability.BUG_FIX,
        EngineCapability.EXPLANATION,
    ),
    cliPath = cliPath,
) {

    override fun buildCommandArgs(request: EngineRequest): List<String> {
        val args = mutableListOf<String>()

        // Modern Codex CLI runs non-interactive requests via `codex exec [PROMPT]`.
        args.add("exec")
        // Plugin can be used from Welcome/default workspaces that are not git repos.
        args.add("--skip-git-repo-check")

        request.options["sandbox_mode"]?.let {
            args.add("--sandbox")
            args.add(it)
        }

        if (request.options["full_auto"]?.equals("true", ignoreCase = true) == true) {
            args.add("--full-auto")
        }

        if (request.options["dangerously_bypass_approvals_and_sandbox"]?.equals("true", ignoreCase = true) == true) {
            args.add("--dangerously-bypass-approvals-and-sandbox")
        }

        request.options["model"]?.let {
            args.add("--model")
            args.add(it)
        }

        request.context.workingDirectory?.let {
            args.add("--cd")
            args.add(it)
        }

        request.options["add_dir"]?.let {
            args.add("--add-dir")
            args.add(it)
        }

        request.options
            .asSequence()
            .filter { (key, _) -> key.startsWith(CODEX_CONFIG_OPTION_PREFIX) }
            .sortedBy { (key, _) -> key }
            .forEach { (_, value) ->
                val trimmed = value.trim()
                if (trimmed.isNotEmpty()) {
                    args.add("-c")
                    args.add(trimmed)
                }
            }

        request.imagePaths.forEach { imagePath ->
            args.add("--image")
            args.add(imagePath)
        }

        // Read prompt from stdin to avoid shell tokenization issues on Windows cmd fallback.
        args.add("--")
        args.add("-")

        return args
    }

    override fun stdinPayload(request: EngineRequest): String? = request.prompt

    override fun parseStreamLine(line: String): EngineChunk? {
        if (line.isEmpty()) return null
        val eventLine = line.trimEnd('\n', '\r')
        return EngineChunk(
            delta = line,
            event = if (eventLine.isBlank()) null else CliProgressEventParser.parseStdout(eventLine),
        )
    }

    override fun streamInactivityTimeoutMillis(request: EngineRequest): Long = CODEX_STREAM_INACTIVITY_TIMEOUT_MILLIS

    override suspend fun getVersion(): String? {
        return try {
            val command = if (System.getProperty("os.name").lowercase().contains("win")) {
                listOf("cmd", "/c", cliPath, "--version")
            } else {
                listOf(cliPath, "--version")
            }
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
            process.waitFor()
            if (process.exitValue() == 0) output else null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val CODEX_CONFIG_OPTION_PREFIX: String = "codex_config_"
        private const val CODEX_STREAM_INACTIVITY_TIMEOUT_MILLIS = 20_000L
    }
}
