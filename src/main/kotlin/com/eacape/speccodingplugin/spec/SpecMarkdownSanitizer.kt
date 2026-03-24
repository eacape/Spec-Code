package com.eacape.speccodingplugin.spec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/**
 * 清洗 LLM 生成的规格 Markdown，移除工具调用中间协议和明显的过程噪音。
 */
object SpecMarkdownSanitizer {

    fun sanitize(rawContent: String): String {
        if (rawContent.isBlank()) return ""

        val source = rawContent
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val shouldExtractPayload = shouldExtractPayload(source)
        var normalized = if (shouldExtractPayload) extractLikelyPayloadFromCodeFence(source) else source
        if (shouldExtractPayload) {
            normalized = extractJsonContentFieldIfPresent(normalized)
        }
        val decoded = if (shouldExtractPayload) decodeEscapedTextIfNeeded(normalized) else normalized
        val escapedDecoded = decoded != normalized
        normalized = decoded
        val containsToolMarkers = TOOL_MARKER_REGEX.containsMatchIn(normalized) ||
            TOOL_METADATA_JSON_LINE_REGEX.containsMatchIn(normalized)

        normalized = TOOL_CALL_BLOCK_REGEX.replace(normalized, "\n")
        val cleanedLines = normalized.lineSequence()
            .map(::stripInlineToolTags)
            .map { it.trimEnd() }
            .filterNot(::isToolMetadataLine)
            .filterNot { containsToolMarkers && isJsonStructuralNoiseLine(it) }
            .filterNot { containsToolMarkers && isAgentNarrationLine(it) }
            .toList()
        normalized = collapseBlankLines(cleanedLines.joinToString("\n"))
        if (containsToolMarkers || escapedDecoded || NOISE_HINT_REGEX.containsMatchIn(source)) {
            normalized = trimLeadingAgentPrelude(normalized)
        }
        return normalized.trim()
    }

    private fun shouldExtractPayload(source: String): Boolean {
        val trimmed = source.trimStart()
        if (trimmed.startsWith("{") && trimmed.contains("\"content\"")) {
            return true
        }
        if (TOOL_MARKER_REGEX.containsMatchIn(source) || TOOL_METADATA_JSON_LINE_REGEX.containsMatchIn(source)) {
            return true
        }
        val escapedNewlineCount = ESCAPED_NEWLINE_REGEX.findAll(source).count()
        if (escapedNewlineCount >= 2 && escapedNewlineCount > source.count { it == '\n' }) {
            return true
        }
        val wrappedFenceLanguage = wrappedFenceLanguageOrNull(source) ?: return false
        return wrappedFenceLanguage.isBlank() || wrappedFenceLanguage in PAYLOAD_WRAPPER_FENCE_LANGUAGES
    }

    private fun extractLikelyPayloadFromCodeFence(content: String): String {
        extractWrappedFenceBody(content)?.let { wrappedBody ->
            if (wrappedBody.isNotBlank()) return wrappedBody
        }
        val blocks = CODE_FENCE_REGEX.findAll(content)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (blocks.isEmpty()) return content

        val preferred = blocks.firstOrNull { block ->
            val decoded = decodeEscapedTextIfNeeded(block)
            decoded.lineSequence().any(::isLikelyDocumentStartLine)
        }
        return preferred ?: blocks.maxByOrNull { it.length } ?: content
    }

    private fun wrappedFenceLanguageOrNull(source: String): String? {
        val lines = source
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
        if (lines.isEmpty()) return null

        var first = 0
        while (first < lines.size && lines[first].isBlank()) {
            first += 1
        }
        if (first >= lines.size) return null

        val opening = FENCE_OPEN_LINE_REGEX.matchEntire(lines[first].trim()) ?: return null
        var last = lines.lastIndex
        while (last > first && lines[last].isBlank()) {
            last -= 1
        }
        if (last <= first) return null
        if (!FENCE_CLOSE_LINE_REGEX.matches(lines[last].trim())) return null

        val outsideHasContent = lines.take(first).any { it.isNotBlank() } ||
            lines.drop(last + 1).any { it.isNotBlank() }
        if (outsideHasContent) return null

        return opening.groupValues[1].trim().lowercase(Locale.ROOT)
    }

    private fun extractWrappedFenceBody(source: String): String? {
        val language = wrappedFenceLanguageOrNull(source) ?: return null
        if (language.isNotBlank() && language !in PAYLOAD_WRAPPER_FENCE_LANGUAGES) {
            return null
        }

        val lines = source
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
        var first = 0
        while (first < lines.size && lines[first].isBlank()) {
            first += 1
        }
        var last = lines.lastIndex
        while (last > first && lines[last].isBlank()) {
            last -= 1
        }
        if (last <= first) return null

        return lines.subList(first + 1, last).joinToString("\n")
    }

    private fun extractJsonContentFieldIfPresent(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") || !trimmed.contains("\"content\"")) {
            return content
        }
        return runCatching {
            val root = jsonParser.parseToJsonElement(trimmed) as? JsonObject ?: return@runCatching content
            val value = (root["content"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (value.isBlank()) content else value
        }.getOrElse { content }
    }

    private fun decodeEscapedTextIfNeeded(content: String): String {
        val trimmed = content.trim()
        val unwrapped = if (
            trimmed.length >= 2 &&
            trimmed.startsWith("\"") &&
            trimmed.endsWith("\"")
        ) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }

        val escapedNewlineCount = ESCAPED_NEWLINE_REGEX.findAll(unwrapped).count()
        val realNewlineCount = unwrapped.count { it == '\n' }
        if (escapedNewlineCount < 2 || escapedNewlineCount <= realNewlineCount) {
            return content
        }

        return unwrapped
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun stripInlineToolTags(line: String): String {
        return line
            .replace(TOOL_INLINE_TAG_PAIR_REGEX, "")
            .replace(TOOL_INLINE_OPEN_CLOSE_TAG_REGEX, "")
            .trimEnd()
    }

    private fun isToolMetadataLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("<tool_", ignoreCase = true) || trimmed.startsWith("</tool_", ignoreCase = true)) {
            return true
        }
        if (TOOL_INLINE_TAG_PAIR_REGEX.containsMatchIn(trimmed)) {
            return true
        }
        if (TOOL_METADATA_JSON_LINE_REGEX.containsMatchIn(trimmed)) {
            return true
        }
        return false
    }

    private fun isJsonStructuralNoiseLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        return JSON_STRUCTURAL_LINE_REGEX.matches(trimmed)
    }

    private fun trimLeadingAgentPrelude(content: String): String {
        val lines = content.lines()
        if (lines.isEmpty()) return content

        val firstDocumentLineIndex = lines.indexOfFirst(::isLikelyDocumentStartLine)
        if (firstDocumentLineIndex <= 0 || firstDocumentLineIndex > MAX_LEADING_NOISE_LINES) {
            return content
        }
        return lines.drop(firstDocumentLineIndex).joinToString("\n")
    }

    private fun isLikelyDocumentStartLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false

        if (MARKDOWN_HEADING_REGEX.matches(trimmed)) return true
        if (CHECKBOX_ITEM_REGEX.matches(trimmed)) return true
        if (RAW_CHECKBOX_ITEM_REGEX.matches(trimmed)) return true
        if (ORDERED_ITEM_REGEX.matches(trimmed)) return true
        if (REQUIREMENT_ID_REGEX.matches(trimmed)) return true

        val normalized = trimmed.lowercase(Locale.ROOT)
        return DOCUMENT_START_MARKERS.any { marker -> normalized.startsWith(marker) }
    }

    private fun isAgentNarrationLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        return AGENT_NARRATION_LINE_REGEX.containsMatchIn(trimmed)
    }

    private fun collapseBlankLines(text: String, maxConsecutiveBlankLines: Int = 1): String {
        val out = StringBuilder()
        var consecutiveBlank = 0
        for (line in text.lineSequence()) {
            val isBlank = line.isBlank()
            if (isBlank) {
                consecutiveBlank += 1
                if (consecutiveBlank > maxConsecutiveBlankLines) continue
            } else {
                consecutiveBlank = 0
            }

            if (out.isNotEmpty()) {
                out.append('\n')
            }
            out.append(line)
        }
        return out.toString()
    }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val CODE_FENCE_REGEX = Regex("```(?:[a-zA-Z0-9_-]+)?\\n([\\s\\S]*?)```")
    private val FENCE_OPEN_LINE_REGEX = Regex("""^```\s*([a-zA-Z0-9_-]*)\s*$""")
    private val FENCE_CLOSE_LINE_REGEX = Regex("""^```\s*$""")
    private val ESCAPED_NEWLINE_REGEX = Regex("""\\n|\\r\\n""")
    private val TOOL_CALL_BLOCK_REGEX = Regex(
        pattern = "<tool_calls?>.*?</tool_calls?>",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TOOL_INLINE_TAG_PAIR_REGEX = Regex(
        pattern = "<tool_(?:name|input)>.*?</tool_(?:name|input)>",
        options = setOf(RegexOption.IGNORE_CASE),
    )
    private val TOOL_INLINE_OPEN_CLOSE_TAG_REGEX = Regex(
        pattern = "</?tool_(?:call|calls|name|input)>",
        options = setOf(RegexOption.IGNORE_CASE),
    )
    private val TOOL_MARKER_REGEX = Regex(
        pattern = "<tool_(?:call|calls|name|input)>",
        options = setOf(RegexOption.IGNORE_CASE),
    )
    private val TOOL_METADATA_JSON_LINE_REGEX = Regex(
        pattern = """^\s*(?:(?:"(?:tool_calls?|tool_name|tool_input|context|plan_file_path)"\s*:)|(?:tool_(?:calls?|name|input)\s*:)).*""",
        options = setOf(RegexOption.IGNORE_CASE),
    )
    private val JSON_STRUCTURAL_LINE_REGEX = Regex("""^[{}\[\],"]+$""")
    private val NOISE_HINT_REGEX = Regex(
        pattern = """<tool_|"tool_(?:calls?|name|input)"|\\n|```|plan_file_path|现在直接输出|先把计划写好|from the exploration|let me""",
        options = setOf(RegexOption.IGNORE_CASE),
    )
    private const val MAX_LEADING_NOISE_LINES = 24
    private const val MAX_INITIAL_DOC_SCAN_LINES = 40
    private val PAYLOAD_WRAPPER_FENCE_LANGUAGES = setOf(
        "markdown",
        "md",
        "text",
        "txt",
        "json",
        "yaml",
        "yml",
        "plain",
        "plaintext",
    )
    private val MARKDOWN_HEADING_REGEX = Regex("""^\s{0,3}#{1,6}\s+\S+""")
    private val CHECKBOX_ITEM_REGEX = Regex("""^\s*-\s*\[[ xX]\]\s+\S+""")
    private val RAW_CHECKBOX_ITEM_REGEX = Regex("""^\s*\[[ xX]\]\s+\S+""")
    private val ORDERED_ITEM_REGEX = Regex("""^\s*\d+\.\s+\S+""")
    private val REQUIREMENT_ID_REGEX = Regex("""^\s*(?:FR|NFR|US)-\d+""", RegexOption.IGNORE_CASE)
    private val AGENT_NARRATION_LINE_REGEX = Regex(
        pattern = """^(?:based on\b|let me\b|i\b|next,\s*i\b|to better understand\b|from the exploration results\b|让我\b|我先\b|接下来我\b|先把\b|现在直接\b|下面是\b|以下是\b)""",
        options = setOf(RegexOption.IGNORE_CASE),
    )
    private val DOCUMENT_START_MARKERS = setOf(
        "需求文档",
        "设计文档",
        "实现任务",
        "功能需求",
        "非功能需求",
        "用户故事",
        "验收标准",
        "约束条件",
        "需要确认的问题",
        "需要澄清的问题",
        "澄清问题",
        "待确认问题",
        "架构设计",
        "技术选型",
        "数据模型",
        "接口设计",
        "任务列表",
        "实现步骤",
        "任务依赖",
        "测试计划",
        "概述",
        "requirements",
        "design",
        "tasks",
        "implementation",
        "clarification questions",
        "open questions",
        "architecture",
        "api design",
    )
}
