package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.llm.LlmRequest
import com.eacape.speccodingplugin.llm.LlmResponse
import com.eacape.speccodingplugin.llm.LlmRole
import com.eacape.speccodingplugin.llm.LlmRouter
import com.intellij.openapi.project.Project
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecCodeGroundingAcceptanceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage
    private lateinit var artifactService: SpecArtifactService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
        artifactService = SpecArtifactService(project)
    }

    @Test
    fun `design generation should inject repo context without manual sources`() = runBlocking {
        writeProjectFile(
            "README.md",
            """
                # Existing Plugin
                The project already has a workflow module and test coverage.
            """.trimIndent(),
        )
        writeProjectFile(
            "src/main/kotlin/com/example/WorkflowModule.kt",
            """
                package com.example

                class WorkflowModule {
                    fun stableBoundary() = Unit
                }
            """.trimIndent(),
        )
        initializeGitRepository()
        writeProjectFile(
            "src/main/kotlin/com/example/WorkflowModule.kt",
            """
                package com.example

                class WorkflowModule {
                    fun stableBoundary() = Unit
                    fun extendWorkflowBoundary() = Unit
                }
            """.trimIndent(),
        )

        val harness = createEngineHarness(validDesignMarkdown())
        val workflow = harness.engine.createWorkflow(
            title = "Repo grounded design",
            description = "FR-12A acceptance",
        ).getOrThrow()
        advanceWorkflowToDesign(harness.engine, workflow.id)

        harness.generateCurrentPhase(
            workflowId = workflow.id,
            input = "Design the next change around the existing workflow module.",
        )

        val prompt = harness.lastUserPrompt()
        val persisted = harness.engine.reloadWorkflow(workflow.id).getOrThrow()
            .getDocument(SpecPhase.DESIGN)
            ?: error("design document should be persisted")

        assertTrue(prompt.contains("## Local Repository Code Context"))
        assertTrue(prompt.contains("README.md"))
        assertTrue(prompt.contains("src/main/kotlin/com/example/WorkflowModule.kt"))
        assertTrue(prompt.contains("### Code Change Summary"))
        assertTrue(prompt.contains("MODIFIED: `src/main/kotlin/com/example/WorkflowModule.kt`"))
        assertFalse(prompt.contains(".spec-coding/"))
        assertTrue(persisted.content.contains("## Architecture Design"))
    }

    @Test
    fun `implement generation should persist non-empty relatedFiles from automatic code grounding`() = runBlocking {
        writeProjectFile(
            "src/main/kotlin/com/example/WorkflowTaskGroundingService.kt",
            """
                package com.example

                class WorkflowTaskGroundingService {
                    fun stablePlan() = Unit
                }
            """.trimIndent(),
        )
        initializeGitRepository()
        writeProjectFile(
            "src/main/kotlin/com/example/WorkflowTaskGroundingService.kt",
            """
                package com.example

                class WorkflowTaskGroundingService {
                    fun stablePlan() = Unit
                    fun keepRelatedFilesGrounded() = Unit
                }
            """.trimIndent(),
        )

        val harness = createEngineHarness(
            """
                ## Task List

                ### T-001: Update WorkflowTaskGroundingService
                ```spec-task
                status: PENDING
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```
                - [ ] Update WorkflowTaskGroundingService to keep generated tasks bound to real code files.

                ## Implementation Steps
                1. Update task planning around the existing grounding service.

                ## Test Plan
                - [ ] Run workflow grounding regression coverage.
            """.trimIndent(),
        )
        val workflow = harness.engine.createWorkflow(
            title = "Grounded tasks",
            description = "Task 146 relatedFiles acceptance",
        ).getOrThrow()
        advanceWorkflowToImplement(harness.engine, workflow.id)

        harness.generateCurrentPhase(
            workflowId = workflow.id,
            input = "Plan the implementation tasks for grounded relatedFiles.",
        )

        val prompt = harness.lastUserPrompt()
        val persisted = harness.engine.reloadWorkflow(workflow.id).getOrThrow()
            .getDocument(SpecPhase.IMPLEMENT)
            ?: error("tasks document should be persisted")
        val parsedTask = SpecTaskMarkdownParser.parse(persisted.content).tasks.single()

        assertTrue(prompt.contains("## relatedFiles Planning Guidance"))
        assertTrue(prompt.contains("src/main/kotlin/com/example/WorkflowTaskGroundingService.kt"))
        assertFalse(prompt.contains(".spec-coding/"))
        assertEquals(
            listOf("src/main/kotlin/com/example/WorkflowTaskGroundingService.kt"),
            parsedTask.relatedFiles,
        )
        assertFalse(persisted.content.contains("relatedFiles: []"))
        assertFalse(persisted.content.contains(SpecTaskPlanningGroundingSupport.RELATED_FILES_REASON_PREFIX))
    }

    @Test
    fun `incremental generation should combine baseline artifacts and code diff grounding`() = runBlocking {
        writeProjectFile(
            "README.md",
            "# Incremental Project\n",
        )
        writeProjectFile(
            "src/main/kotlin/com/example/IncrementalWorkflowBridge.kt",
            """
                package com.example

                class IncrementalWorkflowBridge {
                    fun stableBridge() = Unit
                }
            """.trimIndent(),
        )
        initializeGitRepository()
        writeProjectFile(
            "src/main/kotlin/com/example/IncrementalWorkflowBridge.kt",
            """
                package com.example

                class IncrementalWorkflowBridge {
                    fun stableBridge() = Unit
                    fun addIncrementalHook() = Unit
                }
            """.trimIndent(),
        )

        val harness = createEngineHarness(validRequirementsMarkdown())
        val baseline = harness.engine.createWorkflow(
            title = "Baseline workflow",
            description = "existing baseline",
        ).getOrThrow()
        harness.engine.updateDocumentContent(
            workflowId = baseline.id,
            phase = SpecPhase.SPECIFY,
            content = """
                ## Functional Requirements
                - Keep the existing workflow boundaries stable.

                ## Non-Functional Requirements
                - Preserve file-first compatibility.

                ## User Stories
                As a maintainer, I want incremental updates to start from the current baseline.

                ## Acceptance Criteria
                - [ ] Baseline requirements stay readable for follow-up changes.
            """.trimIndent(),
        ).getOrThrow()

        val incremental = harness.engine.createWorkflow(
            title = "Incremental workflow",
            description = "add grounded incremental requirements",
            changeIntent = SpecChangeIntent.INCREMENTAL,
            baselineWorkflowId = baseline.id,
        ).getOrThrow()

        harness.generateCurrentPhase(
            workflowId = incremental.id,
            input = "Add incremental requirements for the existing workflow bridge.",
        )

        val prompt = harness.lastUserPrompt()

        assertTrue(prompt.contains("Keep the existing workflow boundaries stable."))
        assertTrue(prompt.contains(baseline.id))
        assertTrue(prompt.contains("## Local Repository Code Context"))
        assertTrue(prompt.contains("src/main/kotlin/com/example/IncrementalWorkflowBridge.kt"))
        assertTrue(prompt.contains("### Code Change Summary"))
        assertFalse(prompt.contains(".spec-coding/"))
    }

    @Test
    fun `delta export should use git diff grounded code changes summary`() {
        writeProjectFile(
            "src/main/kotlin/com/example/WorkflowApi.kt",
            """
                package com.example

                class WorkflowApi {
                    fun stableApi() = Unit
                }
            """.trimIndent(),
        )
        initializeGitRepository()
        writeProjectFile(
            "src/main/kotlin/com/example/WorkflowApi.kt",
            """
                package com.example

                class WorkflowApi {
                    fun stableApi() = Unit
                    public fun exposeDiff() = Unit
                }
            """.trimIndent(),
        )

        val specEngine = SpecEngine(project, storage) { SpecGenerationResult.Failure("unused") }
        val deltaService = SpecDeltaService(
            project = project,
            specEngine = specEngine,
            storage = storage,
            artifactService = artifactService,
            workspaceCandidateFilesProvider = {
                listOf("src/main/kotlin/com/example/WorkflowApi.kt")
            },
            codeChangeSummaryProvider = { root ->
                GitStatusCodeChangeSummaryProvider(root).collect()
            },
        )

        storage.saveWorkflow(deltaWorkflow("wf-delta-baseline")).getOrThrow()
        storage.saveWorkflow(deltaWorkflow("wf-delta-target")).getOrThrow()
        storage.saveDocument(
            workflowId = "wf-delta-baseline",
            document = deltaDocument(
                """
                    ## Task List

                    ### T-001: Keep WorkflowApi stable
                    ```spec-task
                    status: PENDING
                    priority: P0
                    dependsOn: []
                    relatedFiles:
                      - src/main/kotlin/com/example/WorkflowApi.kt
                    verificationResult: null
                    ```
                    - [ ] Keep the baseline API stable.

                    ## Implementation Steps
                    1. Preserve the current API surface.
                """.trimIndent(),
            ),
        ).getOrThrow()
        storage.saveDocument(
            workflowId = "wf-delta-target",
            document = deltaDocument(
                """
                    ## Task List

                    ### T-001: Keep WorkflowApi stable
                    ```spec-task
                    status: COMPLETED
                    priority: P0
                    dependsOn: []
                    relatedFiles:
                      - src/main/kotlin/com/example/WorkflowApi.kt
                    verificationResult: null
                    ```
                    - [x] Keep the baseline API stable while exposing a new entry point.

                    ## Implementation Steps
                    1. Extend the API entry point.
                """.trimIndent(),
            ),
        ).getOrThrow()

        val delta = deltaService.compareByWorkflowId(
            baselineWorkflowId = "wf-delta-baseline",
            targetWorkflowId = "wf-delta-target",
        ).getOrThrow()
        val markdown = deltaService.exportMarkdown(delta)
        val changedFile = delta.codeChangesSummary.files.first { file ->
            file.path == "src/main/kotlin/com/example/WorkflowApi.kt"
        }

        assertEquals(CodeChangeSource.VCS_STATUS, delta.codeChangesSummary.source)
        assertTrue(changedFile.addedLineCount > 0)
        assertTrue(changedFile.apiChanges.any { hint -> hint.contains("exposeDiff") })
        assertTrue(markdown.contains("## Code Changes Summary"))
        assertTrue(markdown.contains("src/main/kotlin/com/example/WorkflowApi.kt"))
        assertTrue(markdown.contains("exposeDiff"))
        assertFalse(markdown.contains(".spec-coding/"))
    }

    @Test
    fun `generator should surface degraded code context notes when repository context is unavailable`() = runBlocking {
        val router = mockk<LlmRouter>()
        var capturedRequest: LlmRequest? = null
        coEvery {
            router.generate(providerId = any(), request = any())
        } coAnswers {
            capturedRequest = invocation.args[1] as LlmRequest
            LlmResponse(
                content = validRequirementsMarkdown(),
                model = "mock-model",
            )
        }

        val generator = SpecGenerator(router)
        val result = generator.generate(
            SpecGenerationRequest(
                phase = SpecPhase.SPECIFY,
                input = "Generate requirements with degraded repo context.",
                codeContextPack = CodeContextPack(
                    phase = SpecPhase.SPECIFY,
                    changeSummary = CodeChangeSummary.unavailable(
                        "No Git working tree or workspace candidate changes were detected.",
                    ),
                    degradationReasons = listOf(
                        "Project base path is unavailable; local code context collection was skipped.",
                    ),
                ),
                options = GenerationOptions(
                    providerId = "mock",
                    model = "mock-model",
                ),
            ),
        )

        val prompt = capturedRequest.userPrompt()

        assertTrue(result is SpecGenerationResult.Success)
        assertTrue(prompt.contains("Context status: degraded"))
        assertTrue(prompt.contains("### Code Context Degradation Notes"))
        assertTrue(prompt.contains("Project base path is unavailable"))
    }

    private fun createEngineHarness(responseContent: String): EngineHarness {
        val router = mockk<LlmRouter>()
        val capturedRequests = mutableListOf<LlmRequest>()
        coEvery {
            router.generate(providerId = any(), request = any())
        } coAnswers {
            val request = invocation.args[1] as LlmRequest
            capturedRequests += request
            LlmResponse(
                content = responseContent,
                model = "mock-model",
            )
        }
        val generator = SpecGenerator(router)
        val engine = SpecEngine(
            project = project,
            storage = storage,
            generationHandler = generator::generate,
            clarificationHandler = generator::draftClarification,
        )
        return EngineHarness(
            engine = engine,
            requests = capturedRequests,
        )
    }

    private suspend fun EngineHarness.generateCurrentPhase(
        workflowId: String,
        input: String,
        options: GenerationOptions = GenerationOptions(),
    ) {
        engine.generateCurrentPhase(
            workflowId = workflowId,
            input = input,
            options = options,
        ).collect()
    }

    private fun advanceWorkflowToDesign(
        engine: SpecEngine,
        workflowId: String,
    ) {
        engine.updateDocumentContent(
            workflowId = workflowId,
            phase = SpecPhase.SPECIFY,
            content = validRequirementsMarkdown(),
        ).getOrThrow()
        engine.proceedToNextPhase(workflowId).getOrThrow()
    }

    private fun advanceWorkflowToImplement(
        engine: SpecEngine,
        workflowId: String,
    ) {
        advanceWorkflowToDesign(engine, workflowId)
        engine.updateDocumentContent(
            workflowId = workflowId,
            phase = SpecPhase.DESIGN,
            content = validDesignMarkdown(),
        ).getOrThrow()
        engine.proceedToNextPhase(workflowId).getOrThrow()
    }

    private fun writeProjectFile(
        relativePath: String,
        content: String,
    ) {
        val path = tempDir.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun initializeGitRepository() {
        Git.init().setDirectory(tempDir.toFile()).call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit()
                .setMessage("initial")
                .setAuthor("Spec", "spec@example.com")
                .setCommitter("Spec", "spec@example.com")
                .call()
        }
    }

    private fun validRequirementsMarkdown(): String {
        return """
            ## Functional Requirements
            - Users can update the workflow safely.

            ## Non-Functional Requirements
            - Preserve file-first compatibility.

            ## User Stories
            As a maintainer, I want grounded requirements, so that generated specs stay close to the codebase.

            ## Acceptance Criteria
            - [ ] Generated requirements align with the existing repository structure.
        """.trimIndent()
    }

    private fun validDesignMarkdown(): String {
        return """
            ## Architecture Design
            - Extend the existing workflow module without breaking file-first state derivation.

            ## Technology Choices
            - Technology Stack: Kotlin and IntelliJ Platform SDK.

            ## Data Model
            - Keep workflow stage metadata explicit and compatible.

            ## API Design
            - Expose grounded generation entry points for spec composition.

            ## Non-Functional Requirements
            - Preserve deterministic local replay and auditability.
        """.trimIndent()
    }

    private fun deltaWorkflow(id: String): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = id,
            description = "Task 146 delta acceptance",
        )
    }

    private fun deltaDocument(content: String): SpecDocument {
        return SpecDocument(
            id = "doc-implement",
            phase = SpecPhase.IMPLEMENT,
            content = content,
            metadata = SpecMetadata(
                title = "Implement Document",
                description = "Task 146 delta acceptance",
            ),
            validationResult = ValidationResult(valid = true),
        )
    }

    private data class EngineHarness(
        val engine: SpecEngine,
        val requests: List<LlmRequest>,
    ) {
        fun lastUserPrompt(): String {
            return requests.last()
                .messages
                .lastOrNull { message -> message.role == LlmRole.USER }
                ?.content
                ?: error("user prompt should be captured")
        }
    }

    private fun LlmRequest?.userPrompt(): String {
        return this
            ?.messages
            ?.lastOrNull { message -> message.role == LlmRole.USER }
            ?.content
            ?: error("user prompt should be captured")
    }
}
