package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class AtomicFileIOTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `writeString should create target file with content`() {
        val io = AtomicFileIO()
        val target = tempDir.resolve("specs").resolve("wf-1").resolve("requirements.md")

        io.writeString(target, "hello\nworld")

        assertTrue(Files.exists(target))
        assertEquals("hello\nworld", Files.readString(target))
    }

    @Test
    fun `writeString should replace existing file content`() {
        val io = AtomicFileIO()
        val target = tempDir.resolve("specs").resolve("wf-2").resolve("workflow.yaml")
        Files.createDirectories(target.parent)
        Files.writeString(target, "status: OLD")

        io.writeString(target, "status: NEW")

        assertEquals("status: NEW", Files.readString(target))
    }

    @Test
    fun `writeString should keep original content when replace fails`() {
        val target = tempDir.resolve("specs").resolve("wf-3").resolve("tasks.md")
        Files.createDirectories(target.parent)
        Files.writeString(target, "original")

        val io = AtomicFileIO(
            moveOperation = { _, _ ->
                throw IOException("simulated move failure")
            },
        )

        val result = runCatching { io.writeString(target, "new-content") }

        assertTrue(result.isFailure)
        assertEquals("original", Files.readString(target))
        val orphanTemps = Files.list(target.parent).use { stream ->
            stream
                .filter { path -> path.fileName.toString().endsWith(".tmp") }
                .toList()
        }
        assertTrue(orphanTemps.isEmpty())
    }
}
