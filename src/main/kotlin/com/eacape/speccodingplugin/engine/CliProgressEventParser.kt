package com.eacape.speccodingplugin.engine

import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus

internal object CliProgressEventParser {

    private enum class ParseMode {
        STRICT,
        LENIENT,
    }

    fun parseStdout(line: String): ChatStreamEvent? {
        return parse(line, ParseMode.STRICT)
    }

    fun parseStderr(line: String): ChatStreamEvent? {
        return parse(line, ParseMode.LENIENT)
    }

    private fun parse(line: String, mode: ParseMode): ChatStreamEvent? {
        val detail = sanitize(line)
        if (detail.isBlank()) return null
        if (looksLikeMojibake(detail)) return null

        val lower = detail.lowercase()
        val kind = detectKind(detail = detail, lower = lower, mode = mode)
        if (mode == ParseMode.STRICT && kind == ChatTraceKind.OUTPUT && !isExplicitOutput(detail, lower)) {
            return null
        }
        val status = detectStatus(kind = kind, detail = detail, lower = lower)

        return ChatStreamEvent(
            kind = kind,
            detail = detail,
            status = status,
        )
    }

    private fun sanitize(line: String): String {
        return ANSI_REGEX.replace(line, "")
            .replace('\u0008', ' ')
            .replace(REPLACEMENT_CHAR_REGEX, "")
            .trim()
            .replace(LEADING_SPINNER_REGEX, "")
            .removePrefix("-")
            .removePrefix("*")
            .trim()
    }

    private fun detectKind(detail: String, lower: String, mode: ParseMode): ChatTraceKind {
        val isStrict = mode == ParseMode.STRICT

        if (startsWithAny(lower, "[thinking]", "thinking") || detail.startsWith("思考")) {
            return ChatTraceKind.THINK
        }
        if (startsWithAny(lower, "[task]", "task", "subtask") || detail.startsWith("任务") || detail.startsWith("子任务")) {
            return ChatTraceKind.TASK
        }
        if (startsWithAny(lower, "[verify]", "verify", "test") || detail.startsWith("验证") || detail.startsWith("测试")) {
            return ChatTraceKind.VERIFY
        }
        if (startsWithAny(lower, "[read]", "read") || detail.contains("读取")) {
            return ChatTraceKind.READ
        }
        if (startsWithAny(lower, "[edit]", "edit") || lower.contains("apply_patch") || detail.contains("修改") || detail.contains("编辑")) {
            return ChatTraceKind.EDIT
        }
        if (startsWithAny(lower, "[tool]", "tool", "shell") ||
            lower.startsWith("powershell") ||
            lower.startsWith("cmd") ||
            lower.startsWith("bash")
        ) {
            return ChatTraceKind.TOOL
        }

        if (!isStrict) {
            if (containsAny(lower, "thinking", "analy", "reasoning")) {
                return ChatTraceKind.THINK
            }
            if (containsAny(lower, "read", "scan", "grep", "list", "ls ", "cat ")) {
                return ChatTraceKind.READ
            }
            if (containsAny(lower, "edit", "patch", "write file", "rewrite")) {
                return ChatTraceKind.EDIT
            }
            if (containsAny(lower, "task", "step", "progress", "phase")) {
                return ChatTraceKind.TASK
            }
            if (containsAny(lower, "verify", "test", "assert", "coverage")) {
                return ChatTraceKind.VERIFY
            }
            if (containsAny(lower, "powershell", "shell", "command", "exec", "running")) {
                return ChatTraceKind.TOOL
            }
        }

        return ChatTraceKind.OUTPUT
    }

    private fun detectStatus(kind: ChatTraceKind, detail: String, lower: String): ChatTraceStatus {
        if (containsAny(lower, "error", "failed", "failure", "exception", "timeout", "timed out") || detail.contains("失败") || detail.contains("错误")) {
            return ChatTraceStatus.ERROR
        }
        if (containsAny(lower, "done", "completed", "finished", "success", "passed") || detail.contains("完成") || detail.contains("已完成")) {
            return ChatTraceStatus.DONE
        }
        return when (kind) {
            ChatTraceKind.THINK,
            ChatTraceKind.TASK,
            ChatTraceKind.TOOL,
            ChatTraceKind.READ,
            ChatTraceKind.EDIT,
            ChatTraceKind.VERIFY,
            -> ChatTraceStatus.RUNNING
            ChatTraceKind.OUTPUT -> ChatTraceStatus.INFO
        }
    }

    private fun startsWithAny(value: String, vararg prefixes: String): Boolean {
        return prefixes.any { value.startsWith(it) }
    }

    private fun containsAny(value: String, vararg terms: String): Boolean {
        return terms.any { value.contains(it) }
    }

    private fun isExplicitOutput(detail: String, lower: String): Boolean {
        return startsWithAny(lower, "[output]", "output", "[stdout]", "[stderr]") ||
            detail.startsWith("输出") ||
            detail.startsWith("工具输出")
    }

    private fun looksLikeMojibake(detail: String): Boolean {
        val normalized = detail.trim()
        if (normalized.isBlank()) return false
        if (CJK_REGEX.containsMatchIn(normalized)) return false
        if (BOX_DRAWING_REGEX.containsMatchIn(normalized)) return true

        val suspiciousCount = SUSPICIOUS_MOJIBAKE_REGEX.findAll(normalized).count()
        if (suspiciousCount < MOJIBAKE_MIN_COUNT) return false
        val ratio = suspiciousCount.toDouble() / normalized.length.toDouble().coerceAtLeast(1.0)
        return ratio >= MOJIBAKE_MIN_RATIO
    }

    private val ANSI_REGEX = Regex("""\u001B\[[;\d]*[ -/]*[@-~]""")
    private val LEADING_SPINNER_REGEX = Regex("""^[\u2800-\u28FF◐◑◒◓◴◵◶◷◜◝◞◟|/\\-]+\s*""")
    private val REPLACEMENT_CHAR_REGEX = Regex("""\uFFFD+""")
    private val BOX_DRAWING_REGEX = Regex("""[\u2500-\u259F]""")
    private val CJK_REGEX = Regex("""\p{IsHan}""")
    private val SUSPICIOUS_MOJIBAKE_REGEX = Regex("""[\u00C0-\u024F\u2500-\u259F]""")
    private const val MOJIBAKE_MIN_COUNT = 4
    private const val MOJIBAKE_MIN_RATIO = 0.15
}
