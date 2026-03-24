package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus

/**
 * 从助手输出中提取可视化执行信号（思考、读取、编辑、任务、验证）。
 * 当前版本基于文本约定解析，后续可平滑替换为结构化事件流。
 */
internal object ExecutionTimelineParser {

    enum class Kind {
        THINK,
        READ,
        EDIT,
        TASK,
        VERIFY,
        TOOL,
        OUTPUT,
    }

    enum class Status {
        RUNNING,
        DONE,
        ERROR,
        INFO,
    }

    data class TimelineItem(
        val kind: Kind,
        val status: Status,
        val detail: String,
        val logicalKeyHint: String? = null,
    )

    fun parse(content: String): List<TimelineItem> {
        if (content.isBlank()) return emptyList()

        val itemsByKey = linkedMapOf<String, TimelineItem>()

        content.lines().forEach { rawLine ->
            val item = parseLine(rawLine) ?: return@forEach
            mergeItem(item, itemsByKey)
        }

        return itemsByKey.values.toList()
    }

    fun parseLine(rawLine: String): TimelineItem? {
        val line = normalizeLine(rawLine)
        if (line.isBlank()) return null

        val kind = detectKind(line) ?: return null
        val status = detectStatus(line)
        val detail = stripPrefix(line, kind).ifBlank { line }
        return TimelineItem(
            kind = kind,
            status = status,
            detail = detail,
        )
    }

    fun fromStructuredEvent(event: ChatStreamEvent): TimelineItem {
        return TimelineItem(
            kind = mapKind(event.kind),
            status = mapStatus(event.status),
            detail = event.detail.trim().ifBlank { event.kind.name.lowercase() },
            logicalKeyHint = event.metadata["timelineKey"]?.trim()?.ifBlank { null }
                ?: event.id?.trim()?.ifBlank { null },
        )
    }

    private fun normalizeLine(rawLine: String): String {
        return rawLine
            .trim()
            .removePrefix("-")
            .removePrefix("*")
            .trim()
    }

    private fun detectKind(line: String): Kind? {
        val normalized = line.lowercase()

        return when {
            THINK_SIGNAL_REGEX.containsMatchIn(line) -> Kind.THINK
            READ_SIGNAL_REGEX.containsMatchIn(line) -> Kind.READ
            EDIT_SIGNAL_REGEX.containsMatchIn(line) -> Kind.EDIT
            TASK_SIGNAL_REGEX.containsMatchIn(line) -> Kind.TASK
            VERIFY_SIGNAL_REGEX.containsMatchIn(line) ||
                normalized.startsWith("ran test") ||
                normalized.startsWith("tests passed") -> Kind.VERIFY
            TOOL_SIGNAL_REGEX.containsMatchIn(line) -> Kind.TOOL
            OUTPUT_SIGNAL_REGEX.containsMatchIn(line) -> Kind.OUTPUT
            else -> null
        }
    }

    private fun detectStatus(line: String): Status {
        val normalized = line.lowercase()

        val progress = TASK_PROGRESS_REGEX.find(line)
        if (progress != null) {
            val current = progress.groupValues[1].toIntOrNull()
            val total = progress.groupValues[2].toIntOrNull()
            if (current != null && total != null && total > 0) {
                return if (current >= total) Status.DONE else Status.RUNNING
            }
        }

        if (ERROR_KEYWORDS.any { keyword -> normalized.contains(keyword) } ||
            line.contains("失败") ||
            line.contains("错误")
        ) {
            return Status.ERROR
        }

        if (DONE_KEYWORDS.any { keyword -> normalized.contains(keyword) } ||
            line.contains("完成") ||
            line.contains("已完成")
        ) {
            return Status.DONE
        }

        if (RUNNING_KEYWORDS.any { keyword -> normalized.contains(keyword) } ||
            line.endsWith(">") ||
            line.contains("进行中") ||
            line.contains("处理中")
        ) {
            return Status.RUNNING
        }

        return Status.INFO
    }

    private fun stripPrefix(line: String, kind: Kind): String {
        val regex = when (kind) {
            Kind.THINK -> THINK_PREFIX_REGEX
            Kind.READ -> READ_PREFIX_REGEX
            Kind.EDIT -> EDIT_PREFIX_REGEX
            Kind.TASK -> TASK_PREFIX_REGEX
            Kind.VERIFY -> VERIFY_PREFIX_REGEX
            Kind.TOOL -> TOOL_PREFIX_REGEX
            Kind.OUTPUT -> OUTPUT_PREFIX_REGEX
        }
        return line.replaceFirst(regex, "").trim().ifBlank { line }
    }

    private fun mergeItem(item: TimelineItem, sink: MutableMap<String, TimelineItem>) {
        val key = logicalKey(item)
        val existing = sink[key]
        if (existing == null) {
            sink[key] = item
            return
        }

        sink[key] = when {
            statusPriority(item.status) > statusPriority(existing.status) -> item
            statusPriority(item.status) < statusPriority(existing.status) -> existing
            item.detail.length >= existing.detail.length -> item
            else -> existing
        }
    }

    private fun logicalKey(item: TimelineItem): String {
        item.logicalKeyHint?.takeIf(String::isNotBlank)?.let { keyHint ->
            return "${item.kind.name}:$keyHint"
        }
        val normalizedDetail = normalizeDetailForKey(item.kind, item.detail)
        return "${item.kind.name}:${normalizedDetail.ifBlank { "_" }}"
    }

    private fun normalizeDetailForKey(kind: Kind, detail: String): String {
        var normalized = detail.lowercase().trim()
        if (kind == Kind.TASK) {
            normalized = normalized.replace(TASK_PROGRESS_PREFIX_REGEX, "").trim()
        }
        normalized = normalized.replace(MERGE_NOISE_REGEX, "").trim()
        return normalized
    }

    private fun statusPriority(status: Status): Int {
        return when (status) {
            Status.ERROR -> 3
            Status.DONE -> 2
            Status.RUNNING -> 1
            Status.INFO -> 0
        }
    }

    private fun mapKind(kind: ChatTraceKind): Kind {
        return when (kind) {
            ChatTraceKind.THINK -> Kind.THINK
            ChatTraceKind.READ -> Kind.READ
            ChatTraceKind.EDIT -> Kind.EDIT
            ChatTraceKind.TASK -> Kind.TASK
            ChatTraceKind.VERIFY -> Kind.VERIFY
            ChatTraceKind.TOOL -> Kind.TOOL
            ChatTraceKind.OUTPUT -> Kind.OUTPUT
        }
    }

    private fun mapStatus(status: ChatTraceStatus): Status {
        return when (status) {
            ChatTraceStatus.RUNNING -> Status.RUNNING
            ChatTraceStatus.DONE -> Status.DONE
            ChatTraceStatus.ERROR -> Status.ERROR
            ChatTraceStatus.INFO -> Status.INFO
        }
    }

    private val TASK_PROGRESS_REGEX = Regex("""(\d+)\s*/\s*(\d+)""")
    private val TASK_PROGRESS_PREFIX_REGEX = Regex("""^\d+\s*/\s*\d+\s*""")
    private val MERGE_NOISE_REGEX = Regex("""\b(running|done|completed|finished|success|failed|error|进行中|已完成|完成|失败|错误)\b""")
    private val THINK_SIGNAL_REGEX = Regex(
        """^(\[thinking\]\s*|(?:thinking|思考)\s*[:：>\-]+\s*)""",
        RegexOption.IGNORE_CASE,
    )
    private val READ_SIGNAL_REGEX = Regex(
        """^(\[read\]\s*|read\s+files?\b\s*[:：>\-]?\s*|(?:读取文件|批量读取文件)(?:\s|[:：>\-]|$))""",
        RegexOption.IGNORE_CASE,
    )
    private val EDIT_SIGNAL_REGEX = Regex(
        """^(\[edit\]\s*|edit\s+files?\b\s*[:：>\-]?\s*|apply\s+patch\b\s*[:：>\-]?\s*|(?:编辑文件|修改文件)(?:\s|[:：>\-]|$))""",
        RegexOption.IGNORE_CASE,
    )
    private val TASK_SIGNAL_REGEX = Regex(
        """^(\[task\]\s*|(?:task|subtask)\b\s*[:：>\-]+\s*|(?:任务|子任务)\s*[:：>\-]+\s*|(?:任务|子任务)\s+\d+\s*/\s*\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val VERIFY_SIGNAL_REGEX = Regex(
        """^(\[verify\]\s*|verify\b\s*[:：>\-]+\s*|test\b\s*[:：>\-]+\s*|(?:验证|测试)\s*[:：>\-]+\s*|(?:运行测试|执行测试)\s*[:：>\-]?\s*)""",
        RegexOption.IGNORE_CASE,
    )
    private val TOOL_SIGNAL_REGEX = Regex(
        """^(\[tool\]\s*|tool\b\s*[:：>\-]+\s*|(?:工具调用|调用工具)\s*[:：>\-]?\s*)""",
        RegexOption.IGNORE_CASE,
    )
    private val OUTPUT_SIGNAL_REGEX = Regex(
        """^((?:\[output\]|\[stdout\]|\[stderr\])\s*|output\b\s*[:：>\-]+\s*|(?:输出|工具输出)\s*[:：>\-]+\s*)""",
        RegexOption.IGNORE_CASE,
    )
    private val THINK_PREFIX_REGEX = Regex("""^(\[thinking\]|thinking|思考)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val READ_PREFIX_REGEX = Regex("""^(\[read\]|read\s+files?|读取文件|批量读取文件)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val EDIT_PREFIX_REGEX = Regex("""^(\[edit\]|edit\s+files?|apply\s+patch|编辑文件|修改文件)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val TASK_PREFIX_REGEX = Regex("""^(\[task\]|task|subtask|任务|子任务)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val VERIFY_PREFIX_REGEX = Regex("""^(\[verify\]|verify|test|验证|测试)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val TOOL_PREFIX_REGEX = Regex("""^(\[tool\]|tool|工具调用|调用工具)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val OUTPUT_PREFIX_REGEX = Regex("""^(\[output\]|output|\[stdout\]|\[stderr\]|输出|工具输出)\s*[:：>\-]*\s*""", RegexOption.IGNORE_CASE)
    private val DONE_KEYWORDS = listOf("done", "completed", "finished", "success")
    private val RUNNING_KEYWORDS = listOf("running", "progress", "processing")
    private val ERROR_KEYWORDS = listOf("error", "failed", "failure", "exception")
}
