package com.eacape.speccodingplugin.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LlmRequestContextTest {

    @Test
    fun `extractWorkingDirectory prefers explicit request field`() {
        val request = LlmRequest(
            messages = emptyList(),
            workingDirectory = "  D:/repo/project  ",
            metadata = mapOf(LlmRequestContext.WORKING_DIRECTORY_METADATA_KEY to "D:/fallback"),
        )

        assertEquals("D:/repo/project", LlmRequestContext.extractWorkingDirectory(request))
    }

    @Test
    fun `extractWorkingDirectory falls back to metadata`() {
        val request = LlmRequest(
            messages = emptyList(),
            metadata = mapOf(LlmRequestContext.WORKING_DIRECTORY_METADATA_KEY to "  D:/repo/project  "),
        )

        assertEquals("D:/repo/project", LlmRequestContext.extractWorkingDirectory(request))
    }

    @Test
    fun `extractWorkingDirectory returns null when no usable path exists`() {
        val request = LlmRequest(
            messages = emptyList(),
            workingDirectory = "   ",
            metadata = mapOf(LlmRequestContext.WORKING_DIRECTORY_METADATA_KEY to "\t"),
        )

        assertNull(LlmRequestContext.extractWorkingDirectory(request))
    }
}
