package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecCodeContextServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
    }

    @Test
    fun `buildCodeContextPack should apply phase strategy and task-specific verification entry points`() {
        Files.createDirectories(tempDir.resolve("src/main/kotlin/com/example"))
        Files.createDirectories(tempDir.resolve("src/test/kotlin/com/example"))
        Files.writeString(tempDir.resolve("build.gradle.kts"), "plugins {}")
        Files.writeString(tempDir.resolve("settings.gradle.kts"), "rootProject.name = \"demo\"")
        Files.writeString(tempDir.resolve("src/main/kotlin/com/example/App.kt"), "class App")
        Files.writeString(tempDir.resolve("src/test/kotlin/com/example/AppTest.kt"), "class AppTest")

        val workflow = workflowWithTasks(
            """
            ## Task List

            ### T-001: Touch App
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn: []
            relatedFiles:
              - src/main/kotlin/com/example/App.kt
            verificationResult: null
            ```
            - [ ] Update the existing app.
            """.trimIndent(),
        )
        val service = SpecCodeContextService(
            project = project,
            workspaceCandidateFilesProvider = {
                listOf(
                    "src/main/kotlin/com/example/App.kt",
                    "src/test/kotlin/com/example/AppTest.kt",
                )
            },
            projectConfigLoader = {
                SpecProjectConfigService(project).load().copy(
                    verify = SpecVerifyConfig(
                        commands = listOf(
                            SpecVerifyCommand(
                                id = "unit",
                                displayName = "Unit Tests",
                                command = listOf("./gradlew", "test"),
                                workingDirectory = ".",
                            ),
                        ),
                    ),
                )
            },
            vcsCodeChangeProvider = {
                CodeChangeSummary(
                    source = CodeChangeSource.VCS_STATUS,
                    files = listOf(
                        CodeChangeFile(
                            path = "src/main/kotlin/com/example/App.kt",
                            status = CodeChangeFileStatus.MODIFIED,
                        ),
                    ),
                    summary = "Git working tree reports 1 changed file(s).",
                    available = true,
                )
            },
        )

        val requirementsPack = service.buildCodeContextPack(
            workflow = workflow,
            phase = SpecPhase.SPECIFY,
        )
        val tasksPack = service.buildCodeContextPack(
            workflow = workflow,
            phase = SpecPhase.IMPLEMENT,
            explicitFileHints = listOf(
                tempDir.resolve("src/test/kotlin/com/example/AppTest.kt").toString(),
            ),
        )

        assertEquals(CodeContextCollectionFocus.CURRENT_CAPABILITIES, requirementsPack.focus)
        assertTrue(requirementsPack.verificationEntryPoints.isEmpty())
        assertEquals(CodeContextCollectionFocus.IMPLEMENTATION_ENTRYPOINTS, tasksPack.focus)
        assertEquals(listOf("src/main/kotlin/com/example/App.kt"), tasksPack.confirmedRelatedFiles)
        assertEquals(1, tasksPack.verificationEntryPoints.size)
        assertTrue(tasksPack.projectStructure?.keyPaths?.contains("src/test/kotlin") == true)
        assertTrue(
            tasksPack.candidateFiles.any { candidate ->
                candidate.path == "src/test/kotlin/com/example/AppTest.kt" &&
                    candidate.signals.contains(CodeContextCandidateSignal.EXPLICIT_SELECTION)
            },
        )
        assertTrue(tasksPack.candidateFiles.size <= CodeContextCollectionStrategy.forPhase(SpecPhase.IMPLEMENT).candidateFileBudget)
        assertEquals(CodeChangeSource.VCS_STATUS, tasksPack.changeSummary.source)
        assertFalse(tasksPack.isDegraded())
    }

    @Test
    fun `buildCodeContextPack should fall back to workspace candidates when vcs summary is unavailable`() {
        Files.createDirectories(tempDir.resolve("src/main/kotlin"))
        Files.writeString(tempDir.resolve("src/main/kotlin/Fallback.kt"), "class Fallback")

        val service = SpecCodeContextService(
            project = project,
            workspaceCandidateFilesProvider = { listOf("src/main/kotlin/Fallback.kt") },
            projectConfigLoader = { SpecProjectConfigService(project).load() },
            vcsCodeChangeProvider = { null },
        )

        val pack = service.buildCodeContextPack(workflowWithTasks("## Task List"))

        assertEquals(CodeChangeSource.WORKSPACE_CANDIDATES, pack.changeSummary.source)
        assertTrue(pack.changeSummary.available)
        assertTrue(pack.changeSummary.files.any { file -> file.path == "src/main/kotlin/Fallback.kt" })
        assertFalse(pack.isDegraded())
    }

    @Test
    fun `buildCodeContextPack should report degradation when project root is unavailable`() {
        val noRootProject = mockk<Project>().also { mockedProject ->
            every { mockedProject.basePath } returns null
        }
        val service = SpecCodeContextService(
            project = noRootProject,
            workspaceCandidateFilesProvider = { emptyList() },
            projectConfigLoader = { SpecProjectConfigService(project).load() },
            vcsCodeChangeProvider = { null },
        )

        val pack = service.buildCodeContextPack(workflowWithTasks("## Task List"))

        assertTrue(pack.isDegraded())
        assertTrue(
            pack.degradationReasons.any { reason ->
                reason.contains("Project base path is unavailable")
            },
        )
        assertFalse(pack.hasAutoContext())
    }

    @Test
    fun `git status provider should capture diff stats and symbol api hints`() {
        Files.createDirectories(tempDir.resolve("src/main/kotlin/com/example"))
        val sourceFile = tempDir.resolve("src/main/kotlin/com/example/App.kt")
        Files.writeString(
            sourceFile,
            """
            package com.example

            class App {
                fun stable() = Unit
            }
            """.trimIndent(),
        )

        Git.init().setDirectory(tempDir.toFile()).call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit()
                .setMessage("initial")
                .setAuthor("Spec", "spec@example.com")
                .setCommitter("Spec", "spec@example.com")
                .call()
        }

        Files.writeString(
            sourceFile,
            """
            package com.example

            class App {
                fun stable() = Unit
                public fun newApi() = Unit
            }
            """.trimIndent(),
        )

        val summary = GitStatusCodeChangeSummaryProvider(tempDir).collect()

        assertEquals(CodeChangeSource.VCS_STATUS, summary?.source)
        val changedFile = summary?.files?.firstOrNull { it.path == "src/main/kotlin/com/example/App.kt" }
        assertEquals(CodeChangeFileStatus.MODIFIED, changedFile?.status)
        assertTrue((changedFile?.addedLineCount ?: 0) > 0)
        assertTrue(changedFile?.symbolChanges?.any { hint -> hint.contains("newApi") } == true)
        assertTrue(changedFile?.apiChanges?.any { hint -> hint.contains("newApi") } == true)
    }

    private fun workflowWithTasks(tasksMarkdown: String): SpecWorkflow {
        return SpecWorkflow(
            id = "wf-code-context",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to SpecDocument(
                    id = "tasks",
                    phase = SpecPhase.IMPLEMENT,
                    content = tasksMarkdown,
                    metadata = SpecMetadata(
                        title = "tasks",
                        description = "tasks",
                    ),
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
        )
    }
}
