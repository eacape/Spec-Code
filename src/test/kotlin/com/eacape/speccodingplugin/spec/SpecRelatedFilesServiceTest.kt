package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecRelatedFilesServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
    }

    @Test
    fun `suggestRelatedFiles should merge existing with vcs and file events and apply exclusions`() {
        val service = SpecRelatedFilesService(
            project = project,
            vcsSource = object : RelatedFilesCandidateSource {
                override fun snapshotProjectRelativePaths(): List<String> {
                    return listOf(
                        "build/tmp/ignored.txt",
                        "src/main/kotlin/A.kt",
                    )
                }
            },
            fileEventSource = object : RelatedFilesCandidateSource {
                override fun snapshotProjectRelativePaths(): List<String> {
                    return listOf(
                        "src/main/kotlin/C.kt",
                        "src/main/kotlin/A.kt",
                    )
                }
            },
        )

        val suggestions = service.suggestRelatedFiles(
            taskId = "T-001",
            existingRelatedFiles = listOf(
                " src/main/kotlin/Existing.kt ",
                ".spec-coding/specs/wf/tasks.md",
            ),
        )

        assertEquals(
            listOf(
                "src/main/kotlin/Existing.kt",
                "src/main/kotlin/A.kt",
                "src/main/kotlin/C.kt",
            ),
            suggestions,
        )
    }

    @Test
    fun `snapshotWorkspaceCandidatePaths should merge vcs and file events without existing task paths`() {
        val service = SpecRelatedFilesService(
            project = project,
            vcsSource = object : RelatedFilesCandidateSource {
                override fun snapshotProjectRelativePaths(): List<String> {
                    return listOf("src/main/kotlin/A.kt", "build/generated/ignore.kt")
                }
            },
            fileEventSource = object : RelatedFilesCandidateSource {
                override fun snapshotProjectRelativePaths(): List<String> {
                    return listOf("src/test/kotlin/BTest.kt", "src/main/kotlin/A.kt")
                }
            },
        )

        assertEquals(
            listOf("src/main/kotlin/A.kt", "src/test/kotlin/BTest.kt"),
            service.snapshotWorkspaceCandidatePaths(),
        )
    }

    @Test
    fun `suggestRelatedFiles should ignore candidates with line breaks or escaped roots`() {
        val service = SpecRelatedFilesService(
            project = project,
            vcsSource = object : RelatedFilesCandidateSource {
                override fun snapshotProjectRelativePaths(): List<String> {
                    return listOf(
                        "src/main/kotlin/Safe.kt",
                        "src/main/kotlin/Bad.kt\nmalicious.yaml",
                    )
                }
            },
            fileEventSource = object : RelatedFilesCandidateSource {
                override fun snapshotProjectRelativePaths(): List<String> {
                    return listOf("../outside.txt")
                }
            },
        )

        val suggestions = service.suggestRelatedFiles(
            taskId = "T-001",
            existingRelatedFiles = listOf("src/test/kotlin/ExistingTest.kt\r\nnext"),
        )

        assertEquals(listOf("src/main/kotlin/Safe.kt"), suggestions)
    }

    @Test
    fun `git status candidate source should report modified and untracked files`() {
        Git.init().setDirectory(tempDir.toFile()).call().use { git ->
            val srcDir = tempDir.resolve("src")
            Files.createDirectories(srcDir)

            val fooPath = srcDir.resolve("Foo.kt")
            Files.writeString(fooPath, "class Foo {}")
            git.add().addFilepattern("src/Foo.kt").call()
            git.commit()
                .setMessage("init")
                .setAuthor("test", "test@example.com")
                .call()

            Files.writeString(fooPath, "class Foo { val x = 1 }")
            val barPath = srcDir.resolve("Bar.kt")
            Files.writeString(barPath, "class Bar {}")
        }

        val candidates = GitStatusCandidateSource(project).snapshotProjectRelativePaths()

        assertTrue(candidates.contains("src/Foo.kt"), "Expected modified file Foo.kt to be suggested, got: $candidates")
        assertTrue(candidates.contains("src/Bar.kt"), "Expected untracked file Bar.kt to be suggested, got: $candidates")
    }
}
