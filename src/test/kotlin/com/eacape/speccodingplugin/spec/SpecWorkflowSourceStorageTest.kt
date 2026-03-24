package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class SpecWorkflowSourceStorageTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
    }

    @Test
    fun `importWorkflowSource should persist file metadata and audit event`() {
        val workflowId = "wf-source-import"
        seedWorkflow(workflowId)
        val importedFile = tempDir.resolve("incoming/Customer PRD (V1).md")
        Files.createDirectories(importedFile.parent)
        Files.writeString(
            importedFile,
            "# Customer PRD\n\n- Keep workflow artifacts file-first.\n",
            StandardCharsets.UTF_8,
        )

        val asset = storage.importWorkflowSource(
            workflowId = workflowId,
            importedFromStage = StageId.REQUIREMENTS,
            importedFromEntry = "SPEC_COMPOSER",
            sourcePath = importedFile,
        ).getOrThrow()

        val workflowDir = tempDir.resolve(".spec-coding/specs").resolve(workflowId)
        val storedFile = workflowDir.resolve("sources").resolve("SRC-001-customer-prd-v1.md")
        val listedAssets = storage.listWorkflowSources(workflowId).getOrThrow()
        val auditEvent = storage.listAuditEvents(workflowId).getOrThrow()
            .last { event -> event.eventType == SpecAuditEventType.SOURCE_IMPORTED }

        assertEquals("SRC-001", asset.sourceId)
        assertEquals("Customer PRD (V1).md", asset.originalFileName)
        assertEquals("sources/SRC-001-customer-prd-v1.md", asset.storedRelativePath)
        assertEquals("text/markdown", asset.mediaType)
        assertEquals(1, listedAssets.size)
        assertEquals(asset, listedAssets.single())
        assertTrue(Files.isRegularFile(storedFile))
        assertEquals(Files.readString(importedFile), Files.readString(storedFile))
        assertEquals(
            sha256Hex(Files.readString(importedFile).toByteArray(StandardCharsets.UTF_8)),
            asset.contentHash,
        )
        assertTrue(Files.readString(workflowDir.resolve("sources.yaml")).contains("sourceId: SRC-001"))
        assertEquals("SRC-001", auditEvent.details["sourceId"])
        assertEquals("SPEC_COMPOSER", auditEvent.details["importedFromEntry"])
        assertEquals(StageId.REQUIREMENTS.name, auditEvent.details["importedFromStage"])
        assertEquals(asset.storedRelativePath, auditEvent.details["storedRelativePath"])
    }

    @Test
    fun `importWorkflowSource should allocate incremental ids and keep stable hashes for duplicate content`() {
        val workflowId = "wf-source-sequence"
        seedWorkflow(workflowId)
        val firstFile = tempDir.resolve("incoming/Architecture Notes.txt")
        val secondFile = tempDir.resolve("incoming/Architecture Notes Copy!!.txt")
        Files.createDirectories(firstFile.parent)
        Files.writeString(firstFile, "Same content\n", StandardCharsets.UTF_8)
        Files.writeString(secondFile, "Same content\n", StandardCharsets.UTF_8)

        val firstAsset = storage.importWorkflowSource(
            workflowId = workflowId,
            importedFromStage = StageId.DESIGN,
            importedFromEntry = "SPEC_COMPOSER",
            sourcePath = firstFile,
        ).getOrThrow()
        val secondAsset = storage.importWorkflowSource(
            workflowId = workflowId,
            importedFromStage = StageId.TASKS,
            importedFromEntry = "SPEC_COMPOSER",
            sourcePath = secondFile,
        ).getOrThrow()

        val listedAssets = storage.listWorkflowSources(workflowId).getOrThrow()

        assertEquals("SRC-001", firstAsset.sourceId)
        assertEquals("SRC-002", secondAsset.sourceId)
        assertEquals(firstAsset.contentHash, secondAsset.contentHash)
        assertEquals("sources/SRC-002-architecture-notes-copy.txt", secondAsset.storedRelativePath)
        assertEquals(listOf(firstAsset, secondAsset), listedAssets)
    }

    @Test
    fun `readWorkflowSourceText should only return safe textual source content`() {
        val workflowId = "wf-source-read"
        seedWorkflow(workflowId)
        val importedFile = tempDir.resolve("incoming/context.md")
        Files.createDirectories(importedFile.parent)
        Files.writeString(
            importedFile,
            "# Context\n\n- Persist uploaded workflow sources.\n",
            StandardCharsets.UTF_8,
        )
        val textualAsset = storage.importWorkflowSource(
            workflowId = workflowId,
            importedFromStage = StageId.REQUIREMENTS,
            importedFromEntry = "SPEC_COMPOSER",
            sourcePath = importedFile,
        ).getOrThrow()
        val workflowDir = tempDir.resolve(".spec-coding/specs").resolve(workflowId)
        val binaryRelativePath = "sources/SRC-999-wireframe.png"
        Files.write(workflowDir.resolve(binaryRelativePath), ByteArray(32) { 7 })
        val binaryAsset = textualAsset.copy(
            sourceId = "SRC-999",
            storedRelativePath = binaryRelativePath,
            mediaType = "image/png",
        )
        val escapedAsset = textualAsset.copy(
            sourceId = "SRC-998",
            storedRelativePath = "../outside.md",
        )

        assertEquals(
            Files.readString(importedFile, StandardCharsets.UTF_8),
            storage.readWorkflowSourceText(workflowId, textualAsset).getOrThrow(),
        )
        assertEquals(null, storage.readWorkflowSourceText(workflowId, binaryAsset).getOrThrow())
        assertEquals(null, storage.readWorkflowSourceText(workflowId, escapedAsset).getOrThrow())
    }

    private fun seedWorkflow(workflowId: String) {
        storage.saveWorkflow(
            SpecWorkflow(
                id = workflowId,
                currentPhase = SpecPhase.SPECIFY,
                currentStage = StageId.REQUIREMENTS,
                documents = emptyMap(),
                status = WorkflowStatus.IN_PROGRESS,
                title = "source-import",
                description = "seed workflow for source import",
            ),
        ).getOrThrow()
    }

    private fun sha256Hex(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
