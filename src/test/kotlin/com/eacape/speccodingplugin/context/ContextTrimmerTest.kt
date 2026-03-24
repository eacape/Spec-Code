package com.eacape.speccodingplugin.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextTrimmerTest {

    @Test
    fun `estimateTokens returns chars divided by 4`() {
        assertEquals(25, ContextTrimmer.estimateTokens("a".repeat(100)))
        assertEquals(1, ContextTrimmer.estimateTokens("ab"))
    }

    @Test
    fun `estimateTokens returns at least 1`() {
        assertEquals(1, ContextTrimmer.estimateTokens("a"))
        assertEquals(1, ContextTrimmer.estimateTokens(""))
    }

    @Test
    fun `trim returns empty snapshot for empty list`() {
        val snapshot = ContextTrimmer.trim(emptyList(), 1000)
        assertTrue(snapshot.items.isEmpty())
        assertEquals(0, snapshot.totalTokenEstimate)
        assertFalse(snapshot.wasTrimmed)
    }

    @Test
    fun `trim keeps all items when within budget`() {
        val items = listOf(
            makeItem("a", priority = 90, tokens = 100),
            makeItem("b", priority = 50, tokens = 200),
        )

        val snapshot = ContextTrimmer.trim(items, 500)

        assertEquals(2, snapshot.items.size)
        assertEquals(300, snapshot.totalTokenEstimate)
        assertFalse(snapshot.wasTrimmed)
    }

    @Test
    fun `trim drops low priority items when over budget`() {
        val items = listOf(
            makeItem("high", priority = 90, tokens = 300),
            makeItem("low", priority = 10, tokens = 300),
        )

        val snapshot = ContextTrimmer.trim(items, 400)

        assertEquals(1, snapshot.items.size)
        assertEquals("high", snapshot.items[0].label)
        assertTrue(snapshot.wasTrimmed)
    }

    @Test
    fun `trim preserves high priority items first`() {
        val items = listOf(
            makeItem("low", priority = 10, tokens = 100),
            makeItem("mid", priority = 50, tokens = 100),
            makeItem("high", priority = 90, tokens = 100),
        )

        val snapshot = ContextTrimmer.trim(items, 250)

        assertEquals(2, snapshot.items.size)
        val labels = snapshot.items.map { it.label }.toSet()
        assertTrue("high" in labels)
        assertTrue("mid" in labels)
        assertFalse("low" in labels)
        assertTrue(snapshot.wasTrimmed)
    }

    private fun makeItem(
        label: String,
        priority: Int,
        tokens: Int,
    ): ContextItem {
        val content = "x".repeat(tokens * 4)
        return ContextItem(
            type = ContextType.CURRENT_FILE,
            label = label,
            content = content,
            priority = priority,
            tokenEstimate = tokens,
        )
    }
}
