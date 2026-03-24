package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecYamlCodecTest {

    @Test
    fun `decodeMap should parse scalar sequence and mapping`() {
        val raw = """
            schemaVersion: 1
            templates:
              - FULL_SPEC
              - QUICK_TASK
            gate:
              allowWarning: true
        """.trimIndent()

        val parsed = SpecYamlCodec.decodeMap(raw)

        assertEquals(1, (parsed["schemaVersion"] as Number).toInt())
        assertEquals(listOf("FULL_SPEC", "QUICK_TASK"), parsed["templates"])
        val gate = parsed["gate"] as Map<*, *>
        assertEquals(true, gate["allowWarning"])
    }

    @Test
    fun `decodeMap should reject explicit type tags`() {
        val raw = "title: !!str workflow"

        val error = assertThrows(IllegalArgumentException::class.java) {
            SpecYamlCodec.decodeMap(raw)
        }

        assertTrue(error.message.orEmpty().contains("type tags", ignoreCase = true))
    }

    @Test
    fun `decodeMap should reject anchors and aliases`() {
        val raw = """
            defaults: &base
              title: workflow
            current: *base
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) {
            SpecYamlCodec.decodeMap(raw)
        }

        assertTrue(error.message.orEmpty().contains("anchors", ignoreCase = true))
    }

    @Test
    fun `decodeMap should reject merge key`() {
        val raw = """
            stage:
              <<:
                enabled: true
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) {
            SpecYamlCodec.decodeMap(raw)
        }

        assertTrue(error.message.orEmpty().contains("merge key", ignoreCase = true))
    }

    @Test
    fun `decodeMap should reject duplicate keys`() {
        val raw = """
            verify:
              timeoutMs: 1000
              timeoutMs: 2000
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) {
            SpecYamlCodec.decodeMap(raw)
        }

        assertTrue(error.message.orEmpty().contains("duplicate", ignoreCase = true))
    }

    @Test
    fun `decodeMap should reject multi document streams`() {
        val raw = """
            schemaVersion: 1
            ---
            verify:
              commands: []
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) {
            SpecYamlCodec.decodeMap(raw)
        }

        assertTrue(error.message.orEmpty().contains("multi-document", ignoreCase = true))
    }

    @Test
    fun `encodeMap should be stable across insertion order`() {
        val first = linkedMapOf<String, Any?>(
            "z" to 3,
            "a" to linkedMapOf(
                "b" to 2,
                "a" to 1,
            ),
            "list" to listOf(
                linkedMapOf<String, Any?>(
                    "b" to 2,
                    "a" to 1,
                ),
            ),
        )
        val second = linkedMapOf<String, Any?>(
            "list" to listOf(
                linkedMapOf<String, Any?>(
                    "a" to 1,
                    "b" to 2,
                ),
            ),
            "a" to linkedMapOf(
                "a" to 1,
                "b" to 2,
            ),
            "z" to 3,
        )

        val encodedFirst = SpecYamlCodec.encodeMap(first)
        val encodedSecond = SpecYamlCodec.encodeMap(second)

        assertEquals(encodedFirst, encodedSecond)
    }
}
