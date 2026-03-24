package com.eacape.speccodingplugin.core

import com.eacape.speccodingplugin.llm.LlmMessage
import com.eacape.speccodingplugin.llm.LlmRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecCodingProjectServiceTest {
    @Test
    fun `llm role enum includes core roles`() {
        assertTrue(LlmRole.entries.contains(LlmRole.SYSTEM))
        assertTrue(LlmRole.entries.contains(LlmRole.USER))
        assertTrue(LlmRole.entries.contains(LlmRole.ASSISTANT))
    }

    @Test
    fun `normalize chat history keeps leading system summary`() {
        val history = listOf(
            LlmMessage(LlmRole.SYSTEM, "summary"),
            LlmMessage(LlmRole.USER, "u1"),
            LlmMessage(LlmRole.ASSISTANT, "a1"),
        )

        val normalized = SpecCodingProjectService.normalizeChatHistory(history)

        assertEquals(3, normalized.size)
        assertEquals(LlmRole.SYSTEM, normalized[0].role)
        assertEquals("summary", normalized[0].content)
        assertEquals(LlmRole.USER, normalized[1].role)
        assertEquals(LlmRole.ASSISTANT, normalized[2].role)
    }

    @Test
    fun `normalize chat history drops non leading system messages`() {
        val history = listOf(
            LlmMessage(LlmRole.USER, "u1"),
            LlmMessage(LlmRole.SYSTEM, "internal status"),
            LlmMessage(LlmRole.ASSISTANT, "a1"),
            LlmMessage(LlmRole.USER, "u2"),
        )

        val normalized = SpecCodingProjectService.normalizeChatHistory(history)

        assertEquals(
            listOf(
                LlmMessage(LlmRole.USER, "u1"),
                LlmMessage(LlmRole.ASSISTANT, "a1"),
                LlmMessage(LlmRole.USER, "u2"),
            ),
            normalized,
        )
    }

    @Test
    fun `normalize chat history keeps summary when history exceeds limit`() {
        val dialogMessages = (1..40).map { index ->
            if (index % 2 == 0) {
                LlmMessage(LlmRole.ASSISTANT, "a$index")
            } else {
                LlmMessage(LlmRole.USER, "u$index")
            }
        }
        val history = buildList {
            add(LlmMessage(LlmRole.SYSTEM, "summary"))
            addAll(dialogMessages)
        }

        val normalized = SpecCodingProjectService.normalizeChatHistory(history)

        assertEquals(24, normalized.size)
        assertEquals(LlmRole.SYSTEM, normalized.first().role)
        assertEquals("summary", normalized.first().content)
        assertEquals(dialogMessages.takeLast(23), normalized.drop(1))
    }
}
