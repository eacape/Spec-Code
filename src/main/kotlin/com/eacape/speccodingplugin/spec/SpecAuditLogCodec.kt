package com.eacape.speccodingplugin.spec

import java.time.Instant

/**
 * Encodes/decodes append-only audit logs using YAML multi-document stream.
 */
object SpecAuditLogCodec {

    fun encodeDocument(event: SpecAuditEvent): String {
        val root = linkedMapOf<String, Any?>(
            "schemaVersion" to event.schemaVersion,
            "eventId" to event.eventId,
            "workflowId" to event.workflowId,
            "eventType" to event.eventType.name,
            "occurredAtEpochMs" to event.occurredAtEpochMs,
            "occurredAt" to event.occurredAt,
        )
        event.actor?.takeIf { it.isNotBlank() }?.let { actor ->
            root["actor"] = actor
        }
        if (event.details.isNotEmpty()) {
            root["details"] = event.details.toSortedMap()
        }
        var body = SpecYamlCodec.encodeMap(root)
        if (!body.endsWith("\n")) {
            body += "\n"
        }
        return "---\n$body"
    }

    fun decodeDocuments(streamContent: String): List<SpecAuditEvent> {
        if (streamContent.isBlank()) {
            return emptyList()
        }
        return splitDocuments(streamContent).mapNotNull { document ->
            decodeDocument(document).getOrNull()
        }
    }

    fun decodeDocument(document: String): Result<SpecAuditEvent> {
        return runCatching {
            val root = SpecYamlCodec.decodeMap(document)
            val eventType = parseEventType(root["eventType"])
                ?: error("Invalid audit eventType")
            val workflowId = root["workflowId"]?.toString()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: error("Invalid audit workflowId")
            val occurredAtEpochMs = parseLong(root["occurredAtEpochMs"])
                ?: parseInstant(root["occurredAt"])
                ?: error("Invalid audit occurredAt")
            val occurredAt = root["occurredAt"]?.toString()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: Instant.ofEpochMilli(occurredAtEpochMs).toString()

            SpecAuditEvent(
                schemaVersion = parseInt(root["schemaVersion"]) ?: SpecAuditEvent.CURRENT_SCHEMA_VERSION,
                eventId = root["eventId"]?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "event-$occurredAtEpochMs",
                workflowId = workflowId,
                eventType = eventType,
                occurredAtEpochMs = occurredAtEpochMs,
                occurredAt = occurredAt,
                actor = root["actor"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
                details = parseDetails(root["details"]),
            )
        }
    }

    private fun splitDocuments(streamContent: String): List<String> {
        val documents = mutableListOf<String>()
        val current = StringBuilder()
        var hasDocument = false

        streamContent
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .forEach { line ->
                if (line.trim() == "---") {
                    if (hasDocument) {
                        val body = current.toString().trim()
                        if (body.isNotEmpty()) {
                            documents += body
                        }
                        current.clear()
                    }
                    hasDocument = true
                } else {
                    current.appendLine(line)
                }
            }

        val trailing = current.toString().trim()
        if (trailing.isNotEmpty()) {
            documents += trailing
        }
        return documents
    }

    private fun parseEventType(raw: Any?): SpecAuditEventType? {
        val normalized = raw?.toString()?.trim().orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        return SpecAuditEventType.entries.firstOrNull { it.name == normalized }
    }

    private fun parseInt(raw: Any?): Int? {
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    private fun parseLong(raw: Any?): Long? {
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
    }

    private fun parseInstant(raw: Any?): Long? {
        val normalized = raw?.toString()?.trim().orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        return runCatching { Instant.parse(normalized).toEpochMilli() }.getOrNull()
    }

    private fun parseDetails(raw: Any?): Map<String, String> {
        if (raw !is Map<*, *>) {
            return emptyMap()
        }
        return raw.entries
            .mapNotNull { (key, value) ->
                val normalizedKey = key?.toString()?.trim().orEmpty()
                if (normalizedKey.isBlank()) {
                    return@mapNotNull null
                }
                val normalizedValue = value?.toString()?.trim().orEmpty()
                normalizedKey to normalizedValue
            }
            .toMap()
            .toSortedMap()
    }
}
