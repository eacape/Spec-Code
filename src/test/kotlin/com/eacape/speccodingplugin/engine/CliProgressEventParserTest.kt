package com.eacape.speccodingplugin.engine

import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CliProgressEventParserTest {

    @Test
    fun `parseStdout should detect explicit workflow markers`() {
        val thinking = CliProgressEventParser.parseStdout("[Thinking] collect context")
        val task = CliProgressEventParser.parseStdout("[Task] implement parser done")

        assertNotNull(thinking)
        assertEquals(ChatTraceKind.THINK, thinking!!.kind)
        assertEquals(ChatTraceStatus.RUNNING, thinking.status)

        assertNotNull(task)
        assertEquals(ChatTraceKind.TASK, task!!.kind)
        assertEquals(ChatTraceStatus.DONE, task.status)
    }

    @Test
    fun `parseStdout should ignore plain answer lines`() {
        val event = CliProgressEventParser.parseStdout("Here is the final implementation summary for your request.")
        assertNull(event)
    }

    @Test
    fun `parseStderr should convert shell progress to tool event`() {
        val event = CliProgressEventParser.parseStderr("powershell.exe -Command ./gradlew.bat test")

        assertNotNull(event)
        assertEquals(ChatTraceKind.TOOL, event!!.kind)
        assertEquals(ChatTraceStatus.RUNNING, event.status)
    }

    @Test
    fun `parseStderr should mark failures as error`() {
        val event = CliProgressEventParser.parseStderr("CLI request timed out after 45 seconds")

        assertNotNull(event)
        assertEquals(ChatTraceStatus.ERROR, event!!.status)
    }

    @Test
    fun `parseStderr should ignore replacement-char noise`() {
        val event = CliProgressEventParser.parseStderr("������")
        assertNull(event)
    }

    @Test
    fun `parseStderr should ignore mojibake line with path`() {
        val event = CliProgressEventParser.parseStderr("'C:\\Users\\12186\\.claude' ç╣□xóèij")
        assertNull(event)
    }
}
