package com.eacape.speccodingplugin.engine

import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Claude CLI (`--output-format stream-json`) 逐行解析器。
 * 输出文本增量（assistant delta）与结构化过程事件（trace）。
 */
internal object ClaudeStreamJsonParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun parseLine(line: String): EngineChunk? {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return null

        val payload = parseJsonObject(trimmed)
        if (payload == null) {
            if (trimmed.startsWith("{")) {
                // JSON 行不完整时先忽略，避免把原始片段渲染到回答正文
                return null
            }
            val fallbackDelta = sanitizeAssistantDelta(line)
            if (fallbackDelta.isEmpty()) return null
            val fallbackEvent = CliProgressEventParser.parseStdout(fallbackDelta.trim())
                ?: return null
            return EngineChunk(
                delta = "",
                event = fallbackEvent,
            )
        }

        val type = payload.string("type")?.lowercase().orEmpty()
        if (type.isBlank()) return null

        val delta = when (type) {
            "stream_event" -> extractStreamEventDelta(payload)
            "assistant" -> extractAssistantDelta(payload)
            else -> ""
        }

        val event = when (type) {
            "progress" -> parseProgressEvent(payload)
            "tool_use_summary" -> parseToolSummaryEvent(payload)
            "system" -> parseSystemEvent(payload)
            "result" -> parseResultEvent(payload)
            "stream_event" -> parseStreamEventTrace(payload)
            else -> null
        }

        val sanitizedDelta = sanitizeAssistantDelta(delta)

        if (sanitizedDelta.isEmpty() && event == null) {
            return null
        }

        return EngineChunk(
            delta = sanitizedDelta,
            event = event,
        )
    }

    private fun parseJsonObject(raw: String): JsonObject? {
        return runCatching { json.parseToJsonElement(raw) }
            .getOrNull()
            .asObject()
    }

    private fun extractAssistantDelta(payload: JsonObject): String {
        payload.string("delta")?.takeIf { it.isNotBlank() }?.let { return it }
        payload.obj("delta")?.string("text")?.takeIf { it.isNotBlank() }?.let { return it }

        val partial = payload.bool("is_partial") ?: payload.bool("partial")
        if (partial == true) {
            val partialText = extractMessageText(payload.obj("message"))
            if (!partialText.isNullOrBlank()) {
                return partialText
            }
        }
        return ""
    }

    private fun extractStreamEventDelta(payload: JsonObject): String {
        val event = payload.obj("event") ?: payload.obj("stream_event") ?: return ""
        val eventType = event.string("type")?.lowercase().orEmpty()

        if (eventType == "content_block_delta") {
            val delta = event.obj("delta")
            val deltaType = delta?.string("type")?.lowercase().orEmpty()
            delta?.string("text")
                ?.takeIf { it.isNotBlank() && (deltaType.isBlank() || deltaType.contains("text")) }
                ?.let { return it }
        }

        if (eventType == "content_block_start") {
            val block = event.obj("content_block")
            if (block?.string("type")?.equals("text", ignoreCase = true) == true) {
                block.string("text")?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }

        return ""
    }

    private fun parseProgressEvent(payload: JsonObject): ChatStreamEvent? {
        val detail = normalizeDetail(
            firstNonBlank(
                payload.string("message"),
                payload.string("text"),
                payload.string("status"),
                extractMessageText(payload.obj("message")),
            )
        ) ?: return null

        return buildEvent(
            detail = detail,
            preferredKind = ChatTraceKind.TASK,
            preferredStatus = ChatTraceStatus.RUNNING,
            payload = payload,
            type = "progress",
        )
    }

    private fun parseToolSummaryEvent(payload: JsonObject): ChatStreamEvent? {
        val detail = normalizeDetail(
            firstNonBlank(
                payload.string("summary"),
                payload.string("message"),
                payload.string("text"),
                payload.string("tool"),
                payload.string("tool_name"),
            )
        ) ?: return null

        return buildEvent(
            detail = detail,
            preferredKind = ChatTraceKind.TOOL,
            preferredStatus = ChatTraceStatus.DONE,
            payload = payload,
            type = "tool_use_summary",
        )
    }

    private fun parseSystemEvent(payload: JsonObject): ChatStreamEvent? {
        val subtype = payload.string("subtype")
        val detail = normalizeDetail(
            firstNonBlank(
                payload.string("message"),
                payload.string("text"),
                payload.string("status"),
                subtype?.let { "system:$it" },
            )
        ) ?: return null

        val status = when {
            containsAny(subtype, "error", "failed", "failure") -> ChatTraceStatus.ERROR
            containsAny(subtype, "done", "complete", "completed", "finish", "finished") -> ChatTraceStatus.DONE
            else -> ChatTraceStatus.RUNNING
        }

        return buildEvent(
            detail = detail,
            preferredKind = ChatTraceKind.TASK,
            preferredStatus = status,
            payload = payload,
            type = "system",
        )
    }

    private fun parseResultEvent(payload: JsonObject): ChatStreamEvent? {
        val isError = payload.bool("is_error") == true ||
            containsAny(payload.string("subtype"), "error", "failed", "failure") ||
            payload.containsKey("error")

        val detail = if (isError) {
            normalizeDetail(
                firstNonBlank(
                    payload.string("error"),
                    payload.obj("error")?.toString(),
                    payload.string("message"),
                    payload.string("status"),
                    "result: error",
                )
            )
        } else {
            // Avoid duplicating the full assistant answer inside execution trace.
            normalizeDetail(
                firstNonBlank(
                    payload.string("message"),
                    payload.string("status"),
                    payload.string("subtype")?.let { "result: $it" },
                    "result: done",
                )
            )
        } ?: return null

        return buildEvent(
            detail = detail,
            preferredKind = if (isError) ChatTraceKind.TOOL else ChatTraceKind.VERIFY,
            preferredStatus = if (isError) ChatTraceStatus.ERROR else ChatTraceStatus.DONE,
            payload = payload,
            type = "result",
        )
    }

    private fun parseStreamEventTrace(payload: JsonObject): ChatStreamEvent? {
        val event = payload.obj("event") ?: payload.obj("stream_event") ?: return null
        val eventType = event.string("type")?.lowercase().orEmpty()
        if (eventType.isBlank()) return null

        val block = event.obj("content_block")
        val delta = event.obj("delta")
        val blockType = block?.string("type")?.lowercase().orEmpty()
        val deltaType = delta?.string("type")?.lowercase().orEmpty()

        if (eventType == "content_block_start" && blockType == "tool_use") {
            val name = firstNonBlank(
                block?.string("name"),
                block?.string("tool_name"),
                event.string("name"),
            ) ?: "tool_use"
            return buildEvent(
                detail = normalizeDetail(name) ?: name,
                preferredKind = ChatTraceKind.TOOL,
                preferredStatus = ChatTraceStatus.RUNNING,
                payload = payload,
                type = "stream_event",
            )
        }

        if (eventType == "content_block_delta" && (deltaType.contains("tool") || deltaType.contains("input_json"))) {
            val detail = normalizeDetail(
                firstNonBlank(
                    delta?.string("partial_json"),
                    delta?.string("text"),
                    event.string("name"),
                )
            ) ?: return null
            return buildEvent(
                detail = detail,
                preferredKind = ChatTraceKind.TOOL,
                preferredStatus = ChatTraceStatus.RUNNING,
                payload = payload,
                type = "stream_event",
            )
        }

        if (eventType == "content_block_stop" && (blockType == "tool_use" || containsAny(block?.string("name"), "tool"))) {
            val detail = normalizeDetail(
                firstNonBlank(
                    block?.string("name"),
                    block?.string("tool_name"),
                    "tool",
                )
            ) ?: return null
            return buildEvent(
                detail = detail,
                preferredKind = ChatTraceKind.TOOL,
                preferredStatus = ChatTraceStatus.DONE,
                payload = payload,
                type = "stream_event",
            )
        }

        return null
    }

    private fun extractMessageText(message: JsonObject?): String? {
        if (message == null) return null

        firstNonBlank(
            message.string("text"),
            message.string("content"),
            message.obj("delta")?.string("text"),
        )?.takeIf { it.isNotBlank() }?.let { return it }

        val content = message.array("content") ?: return null
        val text = content
            .mapNotNull { extractContentText(it) }
            .joinToString("")
            .takeIf { it.isNotBlank() }
        return text
    }

    private fun extractContentText(element: JsonElement): String? {
        val obj = element.asObject() ?: return null
        val type = obj.string("type")?.lowercase()
        return when (type) {
            "text" -> obj.string("text")
            else -> obj.string("text")
        }?.takeIf { it.isNotBlank() }
    }

    private fun buildEvent(
        detail: String,
        preferredKind: ChatTraceKind,
        preferredStatus: ChatTraceStatus,
        payload: JsonObject,
        type: String,
    ): ChatStreamEvent {
        val parsed = CliProgressEventParser.parseStderr(detail)
        val kind = when {
            parsed == null -> preferredKind
            parsed.kind == ChatTraceKind.OUTPUT -> preferredKind
            else -> parsed.kind
        }
        val status = when (parsed?.status) {
            ChatTraceStatus.ERROR -> ChatTraceStatus.ERROR
            ChatTraceStatus.DONE -> ChatTraceStatus.DONE
            else -> preferredStatus
        }

        val metadata = linkedMapOf(
            "source" to "claude-stream-json",
            "type" to type,
        ).apply {
            payload.string("subtype")?.let { put("subtype", it) }
            payload.string("id")?.let { put("id", it) }
        }

        return ChatStreamEvent(
            kind = kind,
            detail = detail,
            status = status,
            id = payload.string("id"),
            sequence = payload.long("sequence"),
            metadata = metadata,
        )
    }

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonObject.obj(key: String): JsonObject? = this[key].asObject()

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
    }

    private fun normalizeDetail(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return value
            .replace(REPLACEMENT_CHAR_REGEX, "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(MAX_EVENT_DETAIL_LENGTH)
            .ifBlank { null }
    }

    private fun sanitizeAssistantDelta(value: String): String {
        if (value.isEmpty()) return value
        return value
            .replace(REPLACEMENT_CHAR_REGEX, "")
            .replace(CONTROL_CHAR_REGEX, "")
            .ifBlank { "" }
    }

    private fun containsAny(value: String?, vararg candidates: String): Boolean {
        val normalized = value?.lowercase() ?: return false
        return candidates.any { normalized.contains(it) }
    }

    private val REPLACEMENT_CHAR_REGEX = Regex("""\uFFFD+""")
    private val CONTROL_CHAR_REGEX = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F]""")
    private const val MAX_EVENT_DETAIL_LENGTH = 800
}
