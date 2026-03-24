package com.eacape.speccodingplugin.ui.completion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TriggerParserTest {

    @Test
    fun `returns null for empty text`() {
        assertNull(TriggerParser.parse("", 0))
    }

    @Test
    fun `returns null for caret at zero`() {
        assertNull(TriggerParser.parse("/hello", 0))
    }

    @Test
    fun `detects slash at start of text`() {
        val result = TriggerParser.parse("/rev", 4)
        assertNotNull(result)
        assertEquals(TriggerType.SLASH, result!!.triggerType)
        assertEquals(0, result.triggerOffset)
        assertEquals("rev", result.query)
    }

    @Test
    fun `detects at sign after space`() {
        val result = TriggerParser.parse("hello @Main", 11)
        assertNotNull(result)
        assertEquals(TriggerType.AT, result!!.triggerType)
        assertEquals("Main", result.query)
    }

    @Test
    fun `detects hash after space`() {
        val result = TriggerParser.parse("see #Foo", 8)
        assertNotNull(result)
        assertEquals(TriggerType.HASH, result!!.triggerType)
        assertEquals("Foo", result.query)
    }

    @Test
    fun `detects angle bracket at start`() {
        val result = TriggerParser.parse(">temp", 5)
        assertNotNull(result)
        assertEquals(TriggerType.ANGLE, result!!.triggerType)
        assertEquals("temp", result.query)
    }

    @Test
    fun `returns null when trigger not at word boundary`() {
        val result = TriggerParser.parse("email@test", 10)
        assertNull(result)
    }

    @Test
    fun `returns null for plain text`() {
        val result = TriggerParser.parse("hello world", 11)
        assertNull(result)
    }
}
