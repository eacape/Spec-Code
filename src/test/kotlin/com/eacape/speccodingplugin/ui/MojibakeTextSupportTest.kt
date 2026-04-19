package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class MojibakeTextSupportTest {

    @Test
    fun `repairLineIfNeeded should repair chinese gbk mojibake`() {
        val expected = "\u8F93\u51FA\u7ED3\u679C"
        val garbled = String(expected.toByteArray(StandardCharsets.UTF_8), Charset.forName("GBK"))

        assertTrue(MojibakeTextSupport.looksLikeGarbledLine(garbled))
        assertEquals(expected, MojibakeTextSupport.repairLineIfNeeded(garbled))
    }

    @Test
    fun `repairLineIfNeeded should preserve indentation around repaired content`() {
        val expected = "\u6267\u884C\u6210\u529F"
        val garbled = String(expected.toByteArray(StandardCharsets.UTF_8), Charset.forName("GBK"))

        assertEquals("  $expected  ", MojibakeTextSupport.repairLineIfNeeded("  $garbled  "))
    }

    @Test
    fun `repairLineIfNeeded should ignore valid chinese line`() {
        val valid = "\u8F93\u51FA\u7ED3\u679C"

        assertFalse(MojibakeTextSupport.looksLikeGarbledLine(valid))
        assertNull(MojibakeTextSupport.repairLineIfNeeded(valid))
    }
}
