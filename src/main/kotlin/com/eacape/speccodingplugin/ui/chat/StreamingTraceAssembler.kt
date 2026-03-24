package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.stream.ChatStreamEvent
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal class StreamingTraceAssembler {

    data class TraceItem(
        val kind: ExecutionTimelineParser.Kind,
        val status: ExecutionTimelineParser.Status,
        val detail: String,
        val fileAction: WorkflowQuickActionParser.FileAction? = null,
        val isVerbose: Boolean = kind == ExecutionTimelineParser.Kind.THINK ||
            kind == ExecutionTimelineParser.Kind.TOOL ||
            kind == ExecutionTimelineParser.Kind.OUTPUT,
    )

    data class TraceSnapshot(
        val items: List<TraceItem>,
    ) {
        val hasTrace: Boolean
            get() = items.isNotEmpty()
    }

    private val structuredItems = linkedMapOf<String, ExecutionTimelineParser.TimelineItem>()
    private var cachedParsedContent: CachedParsedContent? = null

    fun clear() {
        structuredItems.clear()
        cachedParsedContent = null
    }

    fun onStructuredEvent(event: ChatStreamEvent) {
        val sanitizedDetail = sanitizeDetail(event.detail) ?: return
        mergeItem(
            ExecutionTimelineParser.fromStructuredEvent(event.copy(detail = sanitizedDetail)),
            structuredItems,
        )
    }

    fun hasStructuredItems(): Boolean = structuredItems.isNotEmpty()

    fun markRunningItemsDone() {
        if (structuredItems.isEmpty()) return
        structuredItems.entries.toList().forEach { (key, item) ->
            if (item.status == ExecutionTimelineParser.Status.RUNNING) {
                structuredItems[key] = item.copy(status = ExecutionTimelineParser.Status.DONE)
            }
        }
    }

    fun snapshot(
        content: String,
        includeRawContent: Boolean = true,
        finalizeRunningItems: Boolean = false,
    ): TraceSnapshot {
        val merged = linkedMapOf<String, ExecutionTimelineParser.TimelineItem>()

        if (includeRawContent) {
            sanitizedTimelineItems(content).forEach { item ->
                mergeItem(finalizeIfRunning(item, finalizeRunningItems), merged)
            }
        }
        structuredItems.values.forEach { item ->
            val sanitizedDetail = sanitizeDetail(item.detail) ?: return@forEach
            mergeItem(
                finalizeIfRunning(item.copy(detail = sanitizedDetail), finalizeRunningItems),
                merged,
            )
        }

        return TraceSnapshot(
            items = merged.values.map { item ->
                TraceItem(
                    kind = item.kind,
                    status = item.status,
                    detail = item.detail,
                    fileAction = extractFileAction(item),
                )
            }
        )
    }

    private fun sanitizedTimelineItems(content: String): List<ExecutionTimelineParser.TimelineItem> {
        val fingerprint = ContentFingerprint(
            length = content.length,
            hash = content.hashCode(),
        )
        val cached = cachedParsedContent
        if (cached != null && cached.fingerprint == fingerprint) {
            return cached.items
        }

        val parsed = ExecutionTimelineParser.parse(content)
            .asSequence()
            .mapNotNull { item ->
                val sanitizedDetail = sanitizeDetail(item.detail) ?: return@mapNotNull null
                item.copy(detail = sanitizedDetail)
            }
            .toList()
        cachedParsedContent = CachedParsedContent(
            fingerprint = fingerprint,
            items = parsed,
        )
        return parsed
    }

    private fun extractFileAction(item: ExecutionTimelineParser.TimelineItem): WorkflowQuickActionParser.FileAction? {
        if (item.kind != ExecutionTimelineParser.Kind.EDIT) return null
        val direct = WorkflowQuickActionParser.parse(item.detail).files.firstOrNull()
        if (direct != null) return direct
        return WorkflowQuickActionParser.parse("`${item.detail}`").files.firstOrNull()
    }

    private fun mergeItem(
        item: ExecutionTimelineParser.TimelineItem,
        sink: MutableMap<String, ExecutionTimelineParser.TimelineItem>,
    ) {
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

    private fun logicalKey(item: ExecutionTimelineParser.TimelineItem): String {
        item.logicalKeyHint?.takeIf(String::isNotBlank)?.let { keyHint ->
            return "${item.kind.name}:$keyHint"
        }
        val normalized = item.detail
            .lowercase()
            .replace(TASK_PROGRESS_PREFIX_REGEX, "")
            .replace(MERGE_NOISE_REGEX, "")
            .trim()
            .ifBlank { "_" }
        return "${item.kind.name}:$normalized"
    }

    private fun statusPriority(status: ExecutionTimelineParser.Status): Int {
        return when (status) {
            ExecutionTimelineParser.Status.ERROR -> 3
            ExecutionTimelineParser.Status.DONE -> 2
            ExecutionTimelineParser.Status.RUNNING -> 1
            ExecutionTimelineParser.Status.INFO -> 0
        }
    }

    private fun sanitizeDetail(value: String): String? {
        val normalized = normalizeDetail(value) ?: return null
        if (looksLikePlaceholderDetail(normalized)) return null
        if (!looksLikeGarbledLine(normalized)) return normalized
        return tryRepairMojibake(normalized)
    }

    private fun finalizeIfRunning(
        item: ExecutionTimelineParser.TimelineItem,
        finalizeRunningItems: Boolean,
    ): ExecutionTimelineParser.TimelineItem {
        if (!finalizeRunningItems || item.status != ExecutionTimelineParser.Status.RUNNING) {
            return item
        }
        return item.copy(status = ExecutionTimelineParser.Status.DONE)
    }

    private fun normalizeDetail(value: String): String? {
        val normalized = value
            .replace('\uFFFD', ' ')
            .replace('\u0008', ' ')
            .replace(CONTROL_CHAR_REGEX, "")
            .trim()
        if (normalized.isBlank()) return null
        return normalized
    }

    private fun tryRepairMojibake(line: String): String? {
        return RECOVERY_SOURCE_CHARSETS.asSequence()
            .mapNotNull { sourceCharset ->
                runCatching {
                    String(line.toByteArray(sourceCharset), StandardCharsets.UTF_8)
                }.getOrNull()
            }
            .mapNotNull { normalizeDetail(it) }
            .filterNot { looksLikePlaceholderDetail(it) }
            .filter { CJK_REGEX.findAll(it).count() >= RECOVERY_MIN_CJK_COUNT }
            .filterNot { looksLikeGarbledLine(it) }
            .firstOrNull()
    }

    private fun looksLikeGarbledLine(line: String): Boolean {
        if (line.isBlank()) return false
        if (PRIVATE_USE_REGEX.containsMatchIn(line)) return true
        if (CJK_REGEX.containsMatchIn(line)) return false
        if (BOX_DRAWING_REGEX.containsMatchIn(line)) return true
        val suspiciousCount = SUSPICIOUS_CHAR_REGEX.findAll(line).count()
        if (suspiciousCount < GARBLED_MIN_COUNT) return false
        val ratio = suspiciousCount.toDouble() / line.length.toDouble().coerceAtLeast(1.0)
        return ratio >= GARBLED_MIN_RATIO
    }

    private fun looksLikePlaceholderDetail(line: String): Boolean {
        if (line.isBlank()) return true
        if (line.length > PLACEHOLDER_MAX_LENGTH) return false
        return PLACEHOLDER_REGEX.matches(line)
    }

    companion object {
        private val TASK_PROGRESS_PREFIX_REGEX = Regex("""^\d+\s*/\s*\d+\s*""")
        private val MERGE_NOISE_REGEX =
            Regex("""\b(running|done|completed|finished|success|failed|error|进行中|已完成|完成|失败|错误)\b""")
        private val CONTROL_CHAR_REGEX = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F]""")
        private val BOX_DRAWING_REGEX = Regex("""[\u2500-\u259F]""")
        private val CJK_REGEX = Regex("""\p{IsHan}""")
        private val PRIVATE_USE_REGEX = Regex("""[\uE000-\uF8FF]""")
        private val SUSPICIOUS_CHAR_REGEX = Regex("""[\u00C0-\u024F\u2500-\u259F]""")
        private const val GARBLED_MIN_COUNT = 4
        private const val GARBLED_MIN_RATIO = 0.15
        private const val PLACEHOLDER_MAX_LENGTH = 4
        private const val RECOVERY_MIN_CJK_COUNT = 2
        private val PLACEHOLDER_REGEX = Regex("""^[\p{P}\p{S}]+$""")
        private val RECOVERY_SOURCE_CHARSETS = listOf(
            Charset.forName("GB18030"),
            Charset.forName("GBK"),
            Charset.forName("Big5"),
        )
    }

    private data class ContentFingerprint(
        val length: Int,
        val hash: Int,
    )

    private data class CachedParsedContent(
        val fingerprint: ContentFingerprint,
        val items: List<ExecutionTimelineParser.TimelineItem>,
    )
}
