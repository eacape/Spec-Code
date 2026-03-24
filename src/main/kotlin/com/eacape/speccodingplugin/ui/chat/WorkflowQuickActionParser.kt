package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.session.displayWorkflowChatCommand
import com.eacape.speccodingplugin.session.isWorkflowChatCommand

/**
 * 从工作流区块中提取可交互动作（文件跳转与命令建议）。
 */
object WorkflowQuickActionParser {

    data class FileAction(
        val path: String,
        val line: Int? = null,
    ) {
        val displayPath: String
            get() = if (line != null) "$path:$line" else path
    }

    data class QuickActions(
        val files: List<FileAction>,
        val commands: List<String>,
    )

    fun parse(content: String): QuickActions {
        if (content.isBlank()) {
            return QuickActions(
                files = emptyList(),
                commands = emptyList(),
            )
        }

        val files = linkedMapOf<String, FileAction>()
        val commands = linkedSetOf<String>()

        collectFiles(content, files)
        collectCommands(content, commands)

        return QuickActions(
            files = files.values.toList(),
            commands = commands.toList(),
        )
    }

    fun parseFileActionCandidate(candidate: String): FileAction? {
        val files = linkedMapOf<String, FileAction>()
        addFileCandidate(candidate, files)
        return files.values.firstOrNull()
    }

    private fun collectFiles(content: String, sink: MutableMap<String, FileAction>) {
        val withoutUrls = URL_REGEX.replace(content, " ")
        BACKTICK_INLINE_REGEX.findAll(withoutUrls).forEach { match ->
            addFileCandidate(match.groupValues[1], sink)
        }
        PATH_REGEX.findAll(withoutUrls).forEach { match ->
            addFileCandidate(match.groupValues[1], sink)
        }
    }

    private fun collectCommands(content: String, sink: MutableSet<String>) {
        val contentWithoutBlocks = StringBuilder(content)

        SHELL_BLOCK_REGEX.findAll(content).forEach { match ->
            val language = match.groupValues[1].trim().lowercase()
            if (language !in SHELL_LANGUAGES) return@forEach

            val block = match.groupValues[2]
            block.lines().forEach { rawLine ->
                val command = normalizeCommandCandidate(rawLine) ?: return@forEach
                if (isLikelyCommand(command)) {
                    sink += displayCommand(command)
                }
            }
            replaceRangeWithWhitespace(contentWithoutBlocks, match.range.first, match.range.last)
        }

        val remaining = contentWithoutBlocks.toString()
        BACKTICK_INLINE_REGEX.findAll(remaining).forEach { match ->
            val candidate = normalizeCommandCandidate(match.groupValues[1]) ?: return@forEach
            if (isLikelyCommand(candidate)) {
                sink += displayCommand(candidate)
            }
        }

        remaining.lines().forEach { rawLine ->
            val line = normalizeNonCodeLine(rawLine)
            if (line.isBlank()) return@forEach

            val prefixMatch = COMMAND_PREFIX_REGEX.find(line)
            if (prefixMatch != null) {
                val candidate = normalizeCommandCandidate(prefixMatch.groupValues[2]) ?: return@forEach
                if (isLikelyCommand(candidate)) {
                    sink += displayCommand(candidate)
                }
                return@forEach
            }

            val candidate = normalizeCommandCandidate(line) ?: return@forEach
            if (isLikelyCommand(candidate)) {
                sink += displayCommand(candidate)
            }
        }
    }

    private fun displayCommand(command: String): String {
        return if (isWorkflowChatCommand(command)) {
            displayWorkflowChatCommand(command).orEmpty()
        } else {
            command
        }
    }

    private fun replaceRangeWithWhitespace(buffer: StringBuilder, start: Int, endInclusive: Int) {
        for (idx in start..endInclusive) {
            buffer.setCharAt(idx, ' ')
        }
    }

    private fun addFileCandidate(rawCandidate: String, sink: MutableMap<String, FileAction>) {
        val cleaned = sanitizeCandidate(rawCandidate)
        if (cleaned.isBlank()) return
        if (cleaned.startsWith("http://", ignoreCase = true) ||
            cleaned.startsWith("https://", ignoreCase = true)
        ) {
            return
        }
        if (isLikelyCommand(cleaned)) {
            return
        }

        val parsed = parsePathWithLine(cleaned) ?: return
        val normalizedPath = parsed.first
            .replace('\\', '/')
            .removePrefix("./")
            .trim()
        if (!looksLikePath(normalizedPath)) return
        if (looksLikeDomainPath(normalizedPath)) return

        val action = FileAction(path = normalizedPath, line = parsed.second)
        val key = "${action.path.lowercase()}:${action.line ?: 0}"
        sink.putIfAbsent(key, action)
    }

    private fun sanitizeCandidate(raw: String): String {
        var value = raw.trim()
        value = value.removeSurrounding("`")
        value = value.removePrefix("file://")
        value = value.trim()
        value = value.trimStart('(', '[', '"', '\'')
        value = value.trimEnd(')', ']', '.', ',', ';', '"', '\'')
        return value
    }

    private fun parsePathWithLine(candidate: String): Pair<String, Int?>? {
        val hashMatch = HASH_LINE_REGEX.matchEntire(candidate)
        if (hashMatch != null) {
            val path = hashMatch.groupValues[1].trim()
            val line = hashMatch.groupValues[2].toIntOrNull()
            if (path.isNotBlank()) {
                return path to line
            }
        }

        val colonMatch = COLON_LINE_REGEX.matchEntire(candidate)
        if (colonMatch != null) {
            val path = colonMatch.groupValues[1].trim()
            val line = colonMatch.groupValues[2].toIntOrNull()
            if (path.isNotBlank()) {
                return path to line
            }
        }

        return candidate to null
    }

    private fun normalizeNonCodeLine(rawLine: String): String {
        var value = rawLine.trim()
        if (value.startsWith(">")) {
            value = value.removePrefix(">").trimStart()
        }
        val listPrefixMatch = LIST_PREFIX_REGEX.find(value)
        if (listPrefixMatch != null) {
            value = value.removePrefix(listPrefixMatch.value).trimStart()
        }
        return value
    }

    private fun normalizeCommandCandidate(raw: String): String? {
        var value = raw.trim()
        if (value.isBlank()) return null

        value = value.removeSurrounding("`")
        value = value.removePrefix("$").trimStart()
        value = value.removePrefix("PS>").trimStart()
        value = value.removePrefix(">").trimStart()
        value = value.trimEnd('`')
        value = value.trim()

        if (value.isBlank()) return null
        if (value.startsWith("#")) return null
        if (value.startsWith("//")) return null
        if (value.contains('\n')) return null
        return value
    }

    private fun looksLikePath(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        if (candidate.contains(" ")) return false
        if (candidate.startsWith("/")) return true
        if (candidate.startsWith("../") || candidate.startsWith("./")) return true
        if (WINDOWS_DRIVE_REGEX.containsMatchIn(candidate)) return true
        if (candidate.contains("/") || candidate.contains("\\")) return true
        return FILE_NAME_REGEX.matches(candidate.substringAfterLast('/'))
    }

    private fun looksLikeDomainPath(path: String): Boolean {
        if (path.startsWith("/") ||
            path.startsWith("../") ||
            WINDOWS_DRIVE_REGEX.containsMatchIn(path)
        ) {
            return false
        }
        val firstSegment = path.substringBefore('/')
        if (!firstSegment.contains(".")) {
            return false
        }
        val suffix = firstSegment.substringAfterLast('.', "")
        return suffix.length in 2..6
    }

    private fun isLikelyCommand(candidate: String): Boolean {
        val value = candidate.trim()
        if (value.isBlank()) return false
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }

        val token = value.split(Regex("\\s+"), limit = 2).firstOrNull()?.lowercase() ?: return false
        if (token in COMMAND_PREFIXES) return true
        if (token.startsWith("./") || token.startsWith(".\\")) return true
        if (token.endsWith(".bat") || token.endsWith(".cmd") || token.endsWith(".ps1")) return true
        if (value.contains(" && ") || value.contains(" | ")) return true
        if (token.startsWith("/") && value.contains(" ")) return true
        return false
    }

    private val BACKTICK_INLINE_REGEX = Regex("`([^`\\n]+)`")
    private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val PATH_REGEX = Regex(
        """(?<!://)(?<![A-Za-z0-9])((?:[A-Za-z]:[\\/]|\.{1,2}[\\/])?(?:[A-Za-z0-9_.-]+[\\/])+[A-Za-z0-9_.-]+\.[A-Za-z0-9]{1,8}(?:(?::\d+(?::\d+)?)|(?:#L\d+(?:C\d+)?))?)"""
    )
    private val SHELL_BLOCK_REGEX = Regex("(?s)```\\s*([A-Za-z0-9_-]*)\\s*\\n(.*?)```")
    private val COMMAND_PREFIX_REGEX =
        Regex("""^(command|cmd|命令|运行命令|执行命令)\s*[:：]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val LIST_PREFIX_REGEX = Regex("""^([-*]|\d+[.)])\s+""")
    private val HASH_LINE_REGEX = Regex("""^(.*)#L(\d+)(?:C\d+)?$""", RegexOption.IGNORE_CASE)
    private val COLON_LINE_REGEX = Regex("""^(.*?):(\d+)(?::\d+)?$""")
    private val WINDOWS_DRIVE_REGEX = Regex("""^[A-Za-z]:[\\/]""")
    private val FILE_NAME_REGEX = Regex("""[A-Za-z0-9_.-]+\.[A-Za-z0-9]{1,8}$""")

    private val SHELL_LANGUAGES = setOf(
        "",
        "bash",
        "sh",
        "shell",
        "zsh",
        "powershell",
        "pwsh",
        "cmd",
        "bat",
        "ps1",
    )

    private val COMMAND_PREFIXES = setOf(
        "gradle",
        "./gradlew",
        "./gradlew.bat",
        ".\\gradlew",
        ".\\gradlew.bat",
        "mvn",
        "mvnw",
        "./mvnw",
        ".\\mvnw",
        "npm",
        "npx",
        "pnpm",
        "yarn",
        "bun",
        "node",
        "python",
        "python3",
        "pip",
        "pytest",
        "uv",
        "poetry",
        "pipenv",
        "go",
        "cargo",
        "rustc",
        "dotnet",
        "java",
        "javac",
        "git",
        "rg",
        "ls",
        "cat",
        "cp",
        "mv",
        "rm",
        "mkdir",
        "touch",
        "make",
        "cmake",
        "ctest",
        "docker",
        "kubectl",
        "helm",
        "terraform",
        "ansible-playbook",
        "powershell",
        "pwsh",
        "cmd",
        "/pipeline",
        "/mode",
        "/workflow",
        "/spec",
    )
}
