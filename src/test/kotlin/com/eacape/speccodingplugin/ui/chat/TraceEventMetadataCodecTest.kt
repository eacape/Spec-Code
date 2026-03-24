package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TraceEventMetadataCodecTest {

    @Test
    fun `encode and decode should round trip core trace fields`() {
        val source = listOf(
            ChatStreamEvent(
                kind = ChatTraceKind.TASK,
                detail = "system:init",
                status = ChatTraceStatus.DONE,
                id = "evt-1",
                sequence = 12L,
                metadata = mapOf("source" to "claude-stream-json"),
            ),
            ChatStreamEvent(
                kind = ChatTraceKind.TOOL,
                detail = "powershell.exe -Command rg --files",
                status = ChatTraceStatus.RUNNING,
            ),
        )

        val encoded = TraceEventMetadataCodec.encode(source)
        val restored = TraceEventMetadataCodec.decode(encoded)

        assertEquals(2, restored.size)
        assertEquals(ChatTraceKind.TASK, restored[0].kind)
        assertEquals(ChatTraceStatus.DONE, restored[0].status)
        assertEquals("system:init", restored[0].detail)
        assertEquals("evt-1", restored[0].id)
        assertEquals(12L, restored[0].sequence)
        assertEquals("claude-stream-json", restored[0].metadata["source"])

        assertEquals(ChatTraceKind.TOOL, restored[1].kind)
        assertEquals(ChatTraceStatus.RUNNING, restored[1].status)
    }

    @Test
    fun `decode should tolerate invalid metadata json`() {
        val restored = TraceEventMetadataCodec.decode("{invalid-json")
        assertTrue(restored.isEmpty())
    }

    @Test
    fun `encode and decode payload should round trip elapsed timestamps`() {
        val startedAtMillis = 1_700_000_000_000L
        val finishedAtMillis = startedAtMillis + 13_700L
        val encoded = TraceEventMetadataCodec.encode(
            events = listOf(
                ChatStreamEvent(
                    kind = ChatTraceKind.TASK,
                    detail = "stream task",
                    status = ChatTraceStatus.DONE,
                )
            ),
            startedAtMillis = startedAtMillis,
            finishedAtMillis = finishedAtMillis,
        )

        val restored = TraceEventMetadataCodec.decodePayload(encoded)
        assertEquals(1, restored.events.size)
        assertEquals(startedAtMillis, restored.startedAtMillis)
        assertEquals(finishedAtMillis, restored.finishedAtMillis)
    }

    @Test
    fun `decode payload should keep compatibility for metadata without elapsed timestamps`() {
        val encoded = TraceEventMetadataCodec.encode(
            events = listOf(
                ChatStreamEvent(
                    kind = ChatTraceKind.TOOL,
                    detail = "tool:read",
                    status = ChatTraceStatus.INFO,
                )
            ),
        )

        val restored = TraceEventMetadataCodec.decodePayload(encoded)
        assertEquals(1, restored.events.size)
        assertNull(restored.startedAtMillis)
        assertNull(restored.finishedAtMillis)
    }
}
