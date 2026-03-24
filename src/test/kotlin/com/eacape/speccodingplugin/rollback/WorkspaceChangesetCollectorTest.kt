package com.eacape.speccodingplugin.rollback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class WorkspaceChangesetCollectorTest {

    @Test
    fun `diff should detect created modified and deleted files`() {
        val root = Files.createTempDirectory("workspace-changeset-")
        try {
            write(root.resolve("same.txt"), "same")
            write(root.resolve("mod.txt"), "before")
            write(root.resolve("del.txt"), "to-delete")

            val before = WorkspaceChangesetCollector.capture(root)

            write(root.resolve("mod.txt"), "after")
            Files.delete(root.resolve("del.txt"))
            write(root.resolve("new.txt"), "created")

            val after = WorkspaceChangesetCollector.capture(root)
            val changes = WorkspaceChangesetCollector.diff(root, before, after)

            assertEquals(3, changes.size)
            val byName = changes.associateBy { fileName(it.filePath) }

            val created = byName["new.txt"]
            assertNotNull(created)
            assertEquals(FileChange.ChangeType.CREATED, created?.changeType)
            assertEquals("created", created?.afterContent)
            assertEquals(null, created?.beforeContent)

            val modified = byName["mod.txt"]
            assertNotNull(modified)
            assertEquals(FileChange.ChangeType.MODIFIED, modified?.changeType)
            assertEquals("before", modified?.beforeContent)
            assertEquals("after", modified?.afterContent)

            val deleted = byName["del.txt"]
            assertNotNull(deleted)
            assertEquals(FileChange.ChangeType.DELETED, deleted?.changeType)
            assertEquals("to-delete", deleted?.beforeContent)
            assertEquals(null, deleted?.afterContent)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `capture should ignore excluded directories and store file`() {
        val root = Files.createTempDirectory("workspace-capture-")
        try {
            write(root.resolve("src/main.txt"), "ok")
            write(root.resolve(".git/ignored.txt"), "git")
            write(root.resolve("build/generated.txt"), "build")
            write(root.resolve(".spec-coding/changesets.json"), "{}")

            val snapshot = WorkspaceChangesetCollector.capture(root)

            assertTrue(snapshot.files.containsKey("src/main.txt"))
            assertFalse(snapshot.files.containsKey(".git/ignored.txt"))
            assertFalse(snapshot.files.containsKey("build/generated.txt"))
            assertFalse(snapshot.files.containsKey(".spec-coding/changesets.json"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun write(path: Path, content: String) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, content, StandardCharsets.UTF_8)
    }

    private fun fileName(filePath: String): String {
        return Paths.get(filePath).fileName.toString()
    }
}
