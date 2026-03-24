package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class StreamingTraceAssemblerTest {

    @Test
    fun `gbk mojibake output should be repaired to chinese`() {
        val expected = "把大模型调味包讲明白.md"
        val garbled = String(expected.toByteArray(StandardCharsets.UTF_8), Charset.forName("GBK"))
        val assembler = StreamingTraceAssembler()

        assembler.onStructuredEvent(
            ChatStreamEvent(
                kind = ChatTraceKind.OUTPUT,
                detail = garbled,
                status = ChatTraceStatus.INFO,
            )
        )

        val snapshot = assembler.snapshot(content = "")
        assertEquals(1, snapshot.items.size)
        assertEquals(expected, snapshot.items.first().detail)
    }

    @Test
    fun `noise line should still be filtered after mojibake repair support`() {
        val assembler = StreamingTraceAssembler()

        assembler.onStructuredEvent(
            ChatStreamEvent(
                kind = ChatTraceKind.OUTPUT,
                detail = "'C:\\Users\\12186\\.claude' ç═╬▌xóèij",
                status = ChatTraceStatus.ERROR,
            )
        )

        val snapshot = assembler.snapshot(content = "")
        assertTrue(snapshot.items.isEmpty())
    }

    @Test
    fun `structured lifecycle events should collapse by stable key hint`() {
        val assembler = StreamingTraceAssembler()

        assembler.onStructuredEvent(
            ChatStreamEvent(
                kind = ChatTraceKind.TASK,
                detail = "Queued spec page AI execution.",
                status = ChatTraceStatus.INFO,
                id = "task_execution_lifecycle",
            ),
        )
        assembler.onStructuredEvent(
            ChatStreamEvent(
                kind = ChatTraceKind.TASK,
                detail = "Cancelling execution.",
                status = ChatTraceStatus.RUNNING,
                id = "task_execution_lifecycle",
            ),
        )
        assembler.onStructuredEvent(
            ChatStreamEvent(
                kind = ChatTraceKind.TASK,
                detail = "AI execution cancelled by user.",
                status = ChatTraceStatus.DONE,
                id = "task_execution_lifecycle",
            ),
        )

        val snapshot = assembler.snapshot(content = "")

        assertEquals(1, snapshot.items.size)
        assertEquals("AI execution cancelled by user.", snapshot.items.single().detail)
        assertEquals(ExecutionTimelineParser.Status.DONE, snapshot.items.single().status)
    }
}
