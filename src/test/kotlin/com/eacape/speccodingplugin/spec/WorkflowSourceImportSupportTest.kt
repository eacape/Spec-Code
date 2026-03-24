package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class WorkflowSourceImportSupportTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `validate should accept supported files within size limit`() {
        val markdown = tempDir.resolve("notes.md")
        val image = tempDir.resolve("wireframe.png")
        Files.writeString(markdown, "# Notes\n", StandardCharsets.UTF_8)
        Files.write(image, ByteArray(128) { 1 })

        val validation = WorkflowSourceImportSupport.validate(
            paths = listOf(markdown, image),
            constraints = WorkflowSourceImportConstraints(maxFileSizeBytes = 1024L),
        )

        assertEquals(listOf(markdown.toAbsolutePath().normalize(), image.toAbsolutePath().normalize()), validation.acceptedPaths)
        assertTrue(validation.rejectedFiles.isEmpty())
    }

    @Test
    fun `validate should reject unsupported or oversized files`() {
        val archive = tempDir.resolve("payload.zip")
        val oversized = tempDir.resolve("requirements.pdf")
        Files.write(archive, ByteArray(16) { 2 })
        Files.write(oversized, ByteArray(4096) { 3 })

        val validation = WorkflowSourceImportSupport.validate(
            paths = listOf(archive, oversized, tempDir.resolve("missing.txt")),
            constraints = WorkflowSourceImportConstraints(maxFileSizeBytes = 64L),
        )

        assertTrue(validation.acceptedPaths.isEmpty())
        assertEquals(3, validation.rejectedFiles.size)
        assertEquals(RejectedWorkflowSourceFile.Reason.UNSUPPORTED_EXTENSION, validation.rejectedFiles[0].reason)
        assertEquals(RejectedWorkflowSourceFile.Reason.FILE_TOO_LARGE, validation.rejectedFiles[1].reason)
        assertEquals(RejectedWorkflowSourceFile.Reason.NOT_A_FILE, validation.rejectedFiles[2].reason)
    }
}
