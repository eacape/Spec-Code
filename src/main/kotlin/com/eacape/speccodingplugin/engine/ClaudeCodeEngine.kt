package com.eacape.speccodingplugin.engine

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Claude Code CLI 引擎适配
 */
class ClaudeCodeEngine(
    cliPath: String,
) : CliEngine(
    id = "claude-code",
    displayName = "Claude Code CLI",
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

    @Volatile
    private var cachedImageFlagSupport: Boolean? = null

    override fun buildCommandArgs(
        request: EngineRequest
    ): List<String> {
        return buildArgs(
            request = request,
            outputFormat = "text",
            includePartialMessages = false,
            verbose = false,
        )
    }

    override fun buildStreamCommandArgs(request: EngineRequest): List<String> {
        return buildArgs(
            request = request,
            outputFormat = "stream-json",
            includePartialMessages = true,
            verbose = true,
        )
    }

    override fun stdoutChunkFlushChars(): Int? = null

    override fun parseStreamLine(
        line: String
    ): EngineChunk? {
        if (line.isEmpty()) return null
        return ClaudeStreamJsonParser.parseLine(line)
    }

    fun supportsImageFlag(): Boolean {
        cachedImageFlagSupport?.let { return it }
        val detected = detectImageFlagSupport()
        cachedImageFlagSupport = detected
        return detected
    }

    private fun buildArgs(
        request: EngineRequest,
        outputFormat: String,
        includePartialMessages: Boolean,
        verbose: Boolean,
    ): List<String> {
        val args = mutableListOf<String>()
        val promptViaStdin = request.options["prompt_via_stdin"]?.equals("true", ignoreCase = true) == true
        val inlineSystemPromptIntoStdin = shouldInlineSystemPromptIntoStdin(
            request = request,
            promptViaStdin = promptViaStdin,
        )
        args.add("--print")
        args.add("--output-format")
        args.add(outputFormat)
        if (promptViaStdin) {
            args.add("--input-format")
            args.add("text")
        }

        if (verbose) {
            args.add("--verbose")
        }

        if (includePartialMessages) {
            args.add("--include-partial-messages")
        }

        request.options["model"]?.let {
            args.add("--model")
            args.add(it)
        }

        request.options["permission_mode"]?.let {
            args.add("--permission-mode")
            args.add(it)
        }

        request.options["add_dir"]?.let {
            args.add("--add-dir")
            args.add(it)
        }

        request.options["tools"]?.let {
            args.add("--tools")
            args.add(it)
        }

        if (request.imagePaths.isNotEmpty() && supportsImageFlag()) {
            request.imagePaths.forEach { imagePath ->
                args.add("--image")
                args.add(imagePath)
            }
        }

        if (request.options["allow_dangerously_skip_permissions"]?.equals("true", ignoreCase = true) == true) {
            args.add("--allow-dangerously-skip-permissions")
        }
        if (request.options["dangerously_skip_permissions"]?.equals("true", ignoreCase = true) == true) {
            args.add("--dangerously-skip-permissions")
        }

        request.options["system_prompt"]
            ?.takeUnless { inlineSystemPromptIntoStdin }
            ?.let {
            args.add("--system-prompt")
            args.add(it)
        }

        if (!promptViaStdin) {
            args.add(request.prompt)
        }
        return args
    }

    protected override fun environmentOverrides(request: EngineRequest): Map<String, String> {
        val maxTokens = request.options["max_tokens"]
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return emptyMap()
        return mapOf(CLAUDE_MAX_OUTPUT_TOKENS_ENV to maxTokens)
    }

    protected override fun stdinPayload(request: EngineRequest): String? {
        val promptViaStdin = request.options["prompt_via_stdin"]?.equals("true", ignoreCase = true) == true
        return if (promptViaStdin) {
            buildStdinPayload(request)
        } else {
            null
        }
    }

    private fun shouldInlineSystemPromptIntoStdin(
        request: EngineRequest,
        promptViaStdin: Boolean,
    ): Boolean {
        if (!promptViaStdin || !isWindows()) {
            return false
        }
        return request.options["system_prompt"]?.isNotBlank() == true
    }

    private fun buildStdinPayload(request: EngineRequest): String {
        val systemPrompt = request.options["system_prompt"]?.takeIf(String::isNotBlank)
            ?: return request.prompt
        if (!shouldInlineSystemPromptIntoStdin(request, promptViaStdin = true)) {
            return request.prompt
        }
        return buildString {
            appendLine("Follow these system instructions as highest priority:")
            appendLine(systemPrompt)
            if (request.prompt.isNotBlank()) {
                appendLine()
                appendLine("User request:")
                append(request.prompt)
            }
        }.trimEnd()
    }

    private fun detectImageFlagSupport(): Boolean {
        val helpOutput = runCommandForOutput("--help") ?: return false
        return helpOutput.contains("--image")
    }

    private fun runCommandForOutput(vararg args: String): String? {
        val command = listOf(cliPath) + args.toList()
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            if (!isWindows()) {
                return null
            }
            runCatching {
                ProcessBuilder(listOf("cmd", "/c", cliPath) + args.toList())
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: return null
        }

        return runCatching {
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                return null
            }
            val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (output.isBlank()) null else output
        }.getOrNull()
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    override suspend fun getVersion(): String? {
        return try {
            val command = if (System.getProperty("os.name").lowercase().contains("win")) {
                listOf("cmd", "/c", cliPath, "--version")
            } else {
                listOf(cliPath, "--version")
            }
            val process = ProcessBuilder(command)
                .redirectErrorStream(true).start()
            val output = process.inputStream
                .bufferedReader(StandardCharsets.UTF_8).readText().trim()
            process.waitFor()
            if (process.exitValue() == 0) output else null
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        private const val CLAUDE_MAX_OUTPUT_TOKENS_ENV = "CLAUDE_CODE_MAX_OUTPUT_TOKENS"
    }
}
