package com.eacape.speccodingplugin.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextFormatterTest {

    @Test
    fun `format returns empty string for empty snapshot`() {
        val snapshot = ContextSnapshot(
            items = emptyList(),
            tokenBudget = 1000,
        )
        assertEquals("", ContextFormatter.format(snapshot))
    }

    @Test
    fun `format includes type and label header`() {
        val snapshot = ContextSnapshot(
            items = listOf(
                ContextItem(
                    type = ContextType.CURRENT_FILE,
                    label = "Main.kt",
                    content = "fun main() {}",
                    filePath = "/src/Main.kt",
                    priority = 70,
                ),
            ),
            tokenBudget = 8000,
        )

        val result = ContextFormatter.format(snapshot)

        assertTrue(result.contains("### Current File: Main.kt"))
        assertTrue(result.contains("File: `/src/Main.kt`"))
        assertTrue(result.contains("fun main() {}"))
    }
}
