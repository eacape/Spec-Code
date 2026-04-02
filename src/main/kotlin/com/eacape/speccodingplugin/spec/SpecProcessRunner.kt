package com.eacape.speccodingplugin.spec
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class SpecProcessRunner {

    data class SanitizedDisplayCommand(
        val text: String,
        val redacted: Boolean,
    )

    fun prepare(
        projectRoot: Path,
        verifyConfig: SpecVerifyConfig,
        command: SpecVerifyCommand,
    ): VerifyCommandExecutionRequest {
        val normalizedProjectRoot = projectRoot.toAbsolutePath().normalize()
        val commandId = command.id.trim()
            .takeIf(String::isNotEmpty)
            ?: throw InvalidVerifyCommandError(null, "id must be a non-blank string")
        requireSafeInlineText(commandId, "id", commandId)
        val normalizedCommand = command.command.mapIndexed { index, token ->
            normalizeCommandToken(commandId, index, token)
        }
        if (normalizedCommand.isEmpty()) {
            throw InvalidVerifyCommandError(commandId, "command must contain at least one token")
        }

        val workingDirectory = normalizeWorkingDirectory(
            commandId = commandId,
            projectRoot = normalizedProjectRoot,
            workingDirectory = command.workingDirectory ?: verifyConfig.defaultWorkingDirectory,
        )
        val timeoutMs = command.timeoutMs ?: verifyConfig.defaultTimeoutMs
        requirePositive(commandId, "timeoutMs", timeoutMs)
        val outputLimitChars = command.outputLimitChars ?: verifyConfig.defaultOutputLimitChars
        requirePositive(commandId, "outputLimitChars", outputLimitChars)
        val redactionPatterns = (verifyConfig.redactionPatterns + command.redactionPatterns)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        validateRedactionPatterns(commandId, redactionPatterns)

        return VerifyCommandExecutionRequest(
            commandId = commandId,
            displayName = normalizeDisplayName(commandId, command.displayName),
            command = normalizedCommand,
            workingDirectory = workingDirectory,
            timeoutMs = timeoutMs,
            outputLimitChars = outputLimitChars,
            redactionPatterns = redactionPatterns,
        )
    }

    fun execute(request: VerifyCommandExecutionRequest): VerifyCommandExecutionResult {
        validateRequest(request)
        val redactionRules = compileRedactionRules(request.commandId, request.redactionPatterns)
        val runtime = try {
            val process = ProcessBuilder(request.command)
                .directory(request.workingDirectory.toFile())
                .redirectErrorStream(false)
                .start()
            ManagedSplitOutputProcess.start(
                process = process,
                outputLimitChars = request.outputLimitChars,
                stdoutThreadName = "${request.commandId}-stdout",
                stderrThreadName = "${request.commandId}-stderr",
            )
        } catch (error: Exception) {
            throw InvalidVerifyCommandError(
                request.commandId,
                "failed to start process: ${error.message ?: error::class.java.simpleName}",
            )
        }

        val startedAt = System.nanoTime()
        val completion = runtime.awaitCompletion(
            timeout = request.timeoutMs.toLong(),
            timeoutUnit = TimeUnit.MILLISECONDS,
            joinTimeoutMillis = OUTPUT_JOIN_TIMEOUT_MILLIS,
            timeoutDestroyGraceWait = TIMEOUT_DESTROY_GRACE_WAIT_MILLIS,
            timeoutDestroyGraceWaitUnit = TimeUnit.MILLISECONDS,
            timeoutDestroyForceWait = TIMEOUT_DESTROY_FORCE_WAIT_MILLIS,
            timeoutDestroyForceWaitUnit = TimeUnit.MILLISECONDS,
        )
        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        val redactedStdout = redact(completion.stdout, redactionRules)
        val redactedStderr = redact(completion.stderr, redactionRules)
        return VerifyCommandExecutionResult(
            commandId = request.commandId,
            exitCode = completion.exitCode,
            stdout = redactedStdout.text,
            stderr = redactedStderr.text,
            durationMs = durationMs,
            timedOut = completion.timedOut,
            stdoutTruncated = completion.stdoutTruncated,
            stderrTruncated = completion.stderrTruncated,
            redacted = redactedStdout.redacted || redactedStderr.redacted,
        )
    }

    private fun validateRequest(request: VerifyCommandExecutionRequest) {
        if (request.commandId.isBlank()) {
            throw InvalidVerifyCommandError(null, "commandId must be a non-blank string")
        }
        requireSafeInlineText(request.commandId, "commandId", request.commandId)
        if (request.command.isEmpty()) {
            throw InvalidVerifyCommandError(request.commandId, "command must contain at least one token")
        }
        request.command.forEachIndexed { index, token ->
            normalizeCommandToken(request.commandId, index, token)
        }
        request.displayName?.let { displayName ->
            normalizeDisplayName(request.commandId, displayName)
        }
        requirePositive(request.commandId, "timeoutMs", request.timeoutMs)
        requirePositive(request.commandId, "outputLimitChars", request.outputLimitChars)
        if (!request.workingDirectory.isAbsolute) {
            throw InvalidVerifyCommandError(request.commandId, "workingDirectory must be an absolute path")
        }
        if (!Files.isDirectory(request.workingDirectory)) {
            throw InvalidVerifyCommandError(
                request.commandId,
                "workingDirectory does not exist: ${request.workingDirectory}",
            )
        }
    }

    private fun normalizeWorkingDirectory(
        commandId: String,
        projectRoot: Path,
        workingDirectory: String,
    ): Path {
        val normalizedWorkingDirectory = workingDirectory.trim()
            .takeIf(String::isNotEmpty)
            ?: throw InvalidVerifyCommandError(commandId, "workingDirectory must be a non-blank string")
        requireSafeInlineText(commandId, "workingDirectory", normalizedWorkingDirectory)
        val resolvedPath = try {
            val candidate = Path.of(normalizedWorkingDirectory)
            if (candidate.isAbsolute) {
                candidate.normalize().toAbsolutePath()
            } else {
                projectRoot.resolve(candidate).normalize().toAbsolutePath()
            }
        } catch (error: InvalidPathException) {
            throw InvalidVerifyCommandError(
                commandId,
                "workingDirectory '$normalizedWorkingDirectory' is not a valid path: ${error.message ?: normalizedWorkingDirectory}",
            )
        }
        if (!resolvedPath.startsWith(projectRoot)) {
            throw VerifyCommandWorkingDirectoryError(
                commandId = commandId,
                workingDirectory = normalizedWorkingDirectory,
                projectRoot = projectRoot.toString(),
            )
        }
        return resolvedPath
    }

    private fun requirePositive(commandId: String, fieldName: String, value: Int) {
        if (value <= 0) {
            throw InvalidVerifyCommandError(commandId, "$fieldName must be greater than 0")
        }
    }

    internal fun sanitizeCommandForDisplay(
        commandId: String,
        command: List<String>,
        customPatterns: List<String>,
    ): SanitizedDisplayCommand {
        val renderedCommand = command.joinToString(" ") { token -> quoteCommandToken(token) }
        val redactedCommand = redact(renderedCommand, compileRedactionRules(commandId, customPatterns))
        return SanitizedDisplayCommand(
            text = redactedCommand.text,
            redacted = redactedCommand.redacted,
        )
    }

    internal fun commandFingerprint(command: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(command.joinToString(separator = "\u0000").toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    internal fun effectiveRedactionRuleCount(customPatterns: List<String>): Int {
        return DEFAULT_REDACTION_RULES.size + customPatterns
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .size
    }

    private fun validateRedactionPatterns(commandId: String, patterns: List<String>) {
        compileCustomRedactionRules(commandId, patterns)
    }

    private fun compileRedactionRules(commandId: String, customPatterns: List<String>): List<RedactionRule> {
        return DEFAULT_REDACTION_RULES + compileCustomRedactionRules(commandId, customPatterns)
    }

    private fun compileCustomRedactionRules(commandId: String, patterns: List<String>): List<RedactionRule> {
        return patterns.map { pattern ->
            try {
                RedactionRule(Regex(pattern), REDACTED_VALUE)
            } catch (error: IllegalArgumentException) {
                throw InvalidVerifyCommandError(
                    commandId,
                    "redaction pattern '$pattern' is invalid: ${error.message ?: pattern}",
                )
            }
        }
    }

    private fun normalizeCommandToken(
        commandId: String,
        index: Int,
        token: String,
    ): String {
        val normalizedToken = token.trim()
            .takeIf(String::isNotEmpty)
            ?: throw InvalidVerifyCommandError(commandId, "command[$index] must be a non-blank string")
        requireSafeInlineText(commandId, "command[$index]", normalizedToken)
        return normalizedToken
    }

    private fun normalizeDisplayName(
        commandId: String,
        displayName: String?,
    ): String? {
        val normalizedDisplayName = displayName?.trim()?.takeIf(String::isNotEmpty) ?: return null
        requireSafeInlineText(commandId, "displayName", normalizedDisplayName)
        return normalizedDisplayName
    }

    private fun requireSafeInlineText(commandId: String, fieldName: String, value: String) {
        if (value.any { character -> character == '\u0000' || character == '\r' || character == '\n' }) {
            throw InvalidVerifyCommandError(
                commandId,
                "$fieldName must not contain line breaks or NUL characters",
            )
        }
    }

    private fun redact(text: String, rules: List<RedactionRule>): RedactionResult {
        var updatedText = text
        var redacted = false
        rules.forEach { rule ->
            val nextText = rule.pattern.replace(updatedText, rule.replacement)
            if (nextText != updatedText) {
                redacted = true
                updatedText = nextText
            }
        }
        return RedactionResult(
            text = updatedText,
            redacted = redacted,
        )
    }

    private fun quoteCommandToken(token: String): String {
        return if (token.any(Char::isWhitespace)) {
            "\"${token.replace("\"", "\\\"")}\""
        } else {
            token
        }
    }

    private data class RedactionRule(
        val pattern: Regex,
        val replacement: String,
    )

    private data class RedactionResult(
        val text: String,
        val redacted: Boolean,
    )

    companion object {
        private const val OUTPUT_JOIN_TIMEOUT_MILLIS = 1_000L
        private const val TIMEOUT_DESTROY_GRACE_WAIT_MILLIS = 250L
        private const val TIMEOUT_DESTROY_FORCE_WAIT_MILLIS = 500L
        private const val REDACTED_VALUE = "<redacted>"

        private val DEFAULT_REDACTION_RULES = listOf(
            RedactionRule(
                pattern = Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)([^\\s]+)"),
                replacement = "${'$'}1$REDACTED_VALUE",
            ),
            RedactionRule(
                pattern = Regex("(?i)(authorization\\s*[:=]\\s*basic\\s+)([^\\s]+)"),
                replacement = "${'$'}1$REDACTED_VALUE",
            ),
            RedactionRule(
                pattern = Regex("(?i)(bearer\\s+)([A-Za-z0-9._-]+)"),
                replacement = "${'$'}1$REDACTED_VALUE",
            ),
            RedactionRule(
                pattern = Regex(
                    "(?i)\\b(api[_-]?key|x-api-key|token|access[_-]?token|refresh[_-]?token|session(?:[_-]?id)?|secret|password|passwd|pwd)\\b(\\s*[:=]\\s*)([^\\s,;&]+)",
                ),
                replacement = "${'$'}1${'$'}2$REDACTED_VALUE",
            ),
            RedactionRule(
                pattern = Regex(
                    "(?i)([\"'](?:api[_-]?key|access[_-]?token|refresh[_-]?token|session(?:[_-]?id)?|token|secret|password|passwd|pwd)[\"']\\s*:\\s*[\"'])([^\"']+)([\"'])",
                ),
                replacement = "${'$'}1$REDACTED_VALUE${'$'}3",
            ),
            RedactionRule(
                pattern = Regex("(?i)((?:https?|ssh)://[^\\s/@:]+:)([^@\\s/]+)(@)"),
                replacement = "${'$'}1$REDACTED_VALUE${'$'}3",
            ),
            RedactionRule(
                pattern = Regex("\\b(gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|glpat-[A-Za-z0-9_-]+|sk-[A-Za-z0-9_-]+)\\b"),
                replacement = REDACTED_VALUE,
            ),
        )
    }
}
