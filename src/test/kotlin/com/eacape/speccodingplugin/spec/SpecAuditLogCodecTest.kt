package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecAuditLogCodecTest {

    @Test
    fun `encodeDocument should write yaml multi-document with stable details order`() {
        val event = SpecAuditEvent(
            eventId = "event-1",
            workflowId = "wf-audit",
            eventType = SpecAuditEventType.WORKFLOW_SAVED,
            occurredAtEpochMs = 1_700_000_000_123L,
            occurredAt = "2023-11-14T22:13:20.123Z",
            actor = "codex",
            details = mapOf(
                "zKey" to "z-value",
                "aKey" to "a-value",
            ),
        )

        val encoded = SpecAuditLogCodec.encodeDocument(event)

        assertTrue(encoded.startsWith("---\n"))
        assertTrue(encoded.contains("eventType: WORKFLOW_SAVED"))
        val aIndex = encoded.indexOf("aKey: a-value")
        val zIndex = encoded.indexOf("zKey: z-value")
        assertTrue(aIndex in 1 until zIndex)
    }

    @Test
    fun `decodeDocuments should parse appended yaml stream`() {
        val first = SpecAuditEvent(
            eventId = "event-1",
            workflowId = "wf-audit",
            eventType = SpecAuditEventType.DOCUMENT_SAVED,
            occurredAtEpochMs = 1_700_000_000_123L,
            occurredAt = "2023-11-14T22:13:20.123Z",
            details = mapOf("phase" to "DESIGN"),
        )
        val second = SpecAuditEvent(
            eventId = "event-2",
            workflowId = "wf-audit",
            eventType = SpecAuditEventType.WORKFLOW_ARCHIVED,
            occurredAtEpochMs = 1_700_000_000_999L,
            occurredAt = "2023-11-14T22:13:20.999Z",
            details = mapOf("archiveId" to "wf-audit-1"),
        )
        val stream = SpecAuditLogCodec.encodeDocument(first) + SpecAuditLogCodec.encodeDocument(second)

        val decoded = SpecAuditLogCodec.decodeDocuments(stream)

        assertEquals(listOf(first, second), decoded)
    }
}
