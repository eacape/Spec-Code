package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class WorkflowIdGeneratorTest {

    @Test
    fun `nextId should generate valid spec prefixed ULID`() {
        val generator = WorkflowIdGenerator()

        repeat(20) {
            val id = generator.nextId()
            assertTrue(WorkflowIdGenerator.isValid(id))
            assertTrue(id.startsWith("spec-"))
            assertEquals(31, id.length)
            assertNotNull(WorkflowIdGenerator.extractTimestamp(id))
        }
    }

    @Test
    fun `generated ids should be lexicographically monotonic in same millisecond`() {
        val fixedTimestamp = 1_770_000_000_000L
        val generator = WorkflowIdGenerator(
            clock = { fixedTimestamp },
            random = Random(7),
        )

        val ids = (1..100).map { generator.nextId() }
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `extractTimestamp should decode encoded ULID timestamp`() {
        val fixedTimestamp = 1_770_000_000_123L
        val generator = WorkflowIdGenerator(
            clock = { fixedTimestamp },
            random = Random(3),
        )
        val id = generator.nextId()

        assertEquals(fixedTimestamp, WorkflowIdGenerator.extractTimestamp(id))
    }

    @Test
    fun `isValid should reject malformed workflow id`() {
        assertFalse(WorkflowIdGenerator.isValid("spec-01INVALID"))
        assertFalse(WorkflowIdGenerator.isValid("badprefix-01HWJ1Q7XJ5D9CKB3S99H82V3F"))
        assertFalse(WorkflowIdGenerator.isValid("spec-01HWJ1Q7XJ5D9CKB3S99H82V3I")) // I is invalid
    }
}
