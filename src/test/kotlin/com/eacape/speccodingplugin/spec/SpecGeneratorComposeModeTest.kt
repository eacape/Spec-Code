package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.llm.LlmRequest
import com.eacape.speccodingplugin.llm.LlmResponse
import com.eacape.speccodingplugin.llm.LlmRole
import com.eacape.speccodingplugin.llm.LlmRouter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecGeneratorComposeModeTest {

    @Test
    fun `generate should build revise prompt from current artifact baseline`() = runBlocking {
        val llmRouter = mockk<LlmRouter>()
        val generator = SpecGenerator(llmRouter)
        var capturedLlmRequest: LlmRequest? = null
        coEvery {
            llmRouter.generate(providerId = any(), request = any())
        } coAnswers {
            capturedLlmRequest = invocation.args[1] as LlmRequest
            LlmResponse(
                content = """
                    ## Architecture Design
                    - Preserve the current design baseline while adding sourceId traceability.

                    ## Technology Choices
                    - Kotlin and IntelliJ Platform SDK.

                    ## Data Model
                    - Track revise operations separately from generate operations.
                """.trimIndent(),
                model = "mock-model",
            )
        }

        val result = generator.generate(
            SpecGenerationRequest(
                phase = SpecPhase.DESIGN,
                input = "Add sourceId traceability to the existing design artifact.",
                previousDocument = promptDocument(
                    phase = SpecPhase.SPECIFY,
                    id = "requirements-baseline",
                    content = """
                        ## Functional Requirements
                        - Keep source references traceable.
                    """.trimIndent(),
                ),
                currentDocument = promptDocument(
                    phase = SpecPhase.DESIGN,
                    id = "design-current",
                    content = """
                        ## Architecture Design
                        - Existing design baseline to preserve.

                        ## Technology Choices
                        - Existing Kotlin stack.

                        ## Data Model
                        - Existing workflow state model.
                    """.trimIndent(),
                ),
                incrementalBaselineContext = """
                    ## Incremental Workflow Baseline
                    - Baseline workflow ID: wf-baseline
                """.trimIndent(),
                codeContextPack = promptCodeContextPack(),
                options = GenerationOptions(
                    providerId = "mock",
                    model = "mock-model",
                    composeActionMode = ArtifactComposeActionMode.REVISE,
                ),
            ),
        )

        val userPrompt = capturedLlmRequest
            ?.messages
            ?.lastOrNull { message -> message.role == LlmRole.USER }
            ?.content
        assertTrue(result is SpecGenerationResult.Success)
        assertNotNull(userPrompt)
        assertTrue(userPrompt!!.contains("## Current design.md"))
        assertTrue(userPrompt.contains("Existing design baseline to preserve."))
        assertTrue(userPrompt.contains("## Revision Instruction"))
        assertTrue(userPrompt.contains("Add sourceId traceability to the existing design artifact."))
        assertTrue(userPrompt.contains("## Upstream requirements.md Reference"))
        assertTrue(userPrompt.contains("Keep source references traceable."))
        assertTrue(userPrompt.contains("## Incremental Workflow Baseline"))
        assertTrue(userPrompt.contains("## Local Repository Code Context"))
        assertTrue(userPrompt.contains("src/main/kotlin/com/example/WorkflowEngine.kt"))
        assertTrue(
            userPrompt.indexOf("## Current design.md") < userPrompt.indexOf("## Upstream requirements.md Reference"),
        )
    }

    @Test
    fun `draftClarification should use current artifact baseline when compose mode is revise`() = runBlocking {
        val llmRouter = mockk<LlmRouter>()
        val generator = SpecGenerator(llmRouter)
        var capturedLlmRequest: LlmRequest? = null
        coEvery {
            llmRouter.generate(providerId = any(), request = any())
        } coAnswers {
            capturedLlmRequest = invocation.args[1] as LlmRequest
            LlmResponse(
                content = """
                    ## Clarification Questions
                    1. Should sourceId be written into the existing design sections or a dedicated appendix?

                    ## Existing Context Summary
                    - The current design artifact already exists and should be revised in place.
                """.trimIndent(),
                model = "mock-model",
            )
        }

        val draft = generator.draftClarification(
            SpecGenerationRequest(
                phase = SpecPhase.DESIGN,
                input = "Add sourceId traceability to the design artifact.",
                previousDocument = promptDocument(
                    phase = SpecPhase.SPECIFY,
                    id = "requirements-upstream",
                    content = """
                        ## Functional Requirements
                        - Trace sources by sourceId.
                    """.trimIndent(),
                ),
                currentDocument = promptDocument(
                    phase = SpecPhase.DESIGN,
                    id = "design-current",
                    content = """
                        ## Architecture Design
                        - Existing design baseline to revise.
                    """.trimIndent(),
                ),
                codeContextPack = promptCodeContextPack(),
                options = GenerationOptions(
                    providerId = "mock",
                    model = "mock-model",
                    composeActionMode = ArtifactComposeActionMode.REVISE,
                ),
            ),
        ).getOrThrow()

        val userPrompt = capturedLlmRequest
            ?.messages
            ?.lastOrNull { message -> message.role == LlmRole.USER }
            ?.content
        assertEquals(
            listOf("Should sourceId be written into the existing design sections or a dedicated appendix?"),
            draft.questions,
        )
        assertNotNull(userPrompt)
        assertTrue(userPrompt!!.contains("preparing to revise"))
        assertTrue(userPrompt.contains("## Current Artifact Baseline"))
        assertTrue(userPrompt.contains("Existing design baseline to revise."))
        assertTrue(userPrompt.contains("## Upstream Reference Document"))
        assertTrue(userPrompt.contains("Trace sources by sourceId."))
        assertTrue(userPrompt.contains("## Local Repository Code Context"))
        assertTrue(userPrompt.contains("Candidate Files To Reuse Or Inspect"))
    }

    @Test
    fun `generate should guide and enrich relatedFiles for implement planning`() = runBlocking {
        val llmRouter = mockk<LlmRouter>()
        val generator = SpecGenerator(llmRouter)
        var capturedLlmRequest: LlmRequest? = null
        coEvery {
            llmRouter.generate(providerId = any(), request = any())
        } coAnswers {
            capturedLlmRequest = invocation.args[1] as LlmRequest
            LlmResponse(
                content = """
                    ## Task List

                    ### T-001: Update SpecWorkflowPanel session reuse
                    ```spec-task
                    status: PENDING
                    priority: P0
                    dependsOn: []
                    relatedFiles: []
                    verificationResult: null
                    ```
                    - [ ] Update shared chat session reuse in SpecWorkflowPanel.

                    ## Implementation Steps
                    1. Update implementation and regression coverage.

                    ## Test Plan
                    - [ ] Run regression coverage.
                """.trimIndent(),
                model = "mock-model",
            )
        }

        val result = generator.generate(
            SpecGenerationRequest(
                phase = SpecPhase.IMPLEMENT,
                input = "Split the design into implementation tasks.",
                previousDocument = promptDocument(
                    phase = SpecPhase.DESIGN,
                    id = "design-upstream",
                    content = """
                        ## Architecture Design
                        - Reuse a shared workflow chat session during task execution.
                    """.trimIndent(),
                ),
                codeContextPack = CodeContextPack(
                    phase = SpecPhase.IMPLEMENT,
                    projectStructure = ProjectStructureSummary(
                        topLevelDirectories = listOf("src"),
                        topLevelFiles = listOf("README.md"),
                        keyPaths = listOf("src/main/kotlin", "src/test/kotlin"),
                        summary = "Summarize likely implementation files, tests, and verification entry points.",
                    ),
                    candidateFiles = listOf(
                        CodeContextCandidateFile(
                            path = "src/main/kotlin/com/example/SpecWorkflowPanel.kt",
                            signals = setOf(CodeContextCandidateSignal.VCS_CHANGE),
                        ),
                    ),
                    changeSummary = CodeChangeSummary(
                        source = CodeChangeSource.VCS_STATUS,
                        files = listOf(
                            CodeChangeFile(
                                path = "src/main/kotlin/com/example/SpecWorkflowPanel.kt",
                                status = CodeChangeFileStatus.MODIFIED,
                            ),
                        ),
                        summary = "Git working tree reports 1 changed file(s).",
                        available = true,
                    ),
                ),
                options = GenerationOptions(
                    providerId = "mock",
                    model = "mock-model",
                ),
            ),
        )

        val userPrompt = capturedLlmRequest
            ?.messages
            ?.lastOrNull { message -> message.role == LlmRole.USER }
            ?.content
        val generated = (result as SpecGenerationResult.Success).document.content

        assertNotNull(userPrompt)
        assertTrue(userPrompt!!.contains("## relatedFiles Planning Guidance"))
        assertTrue(userPrompt.contains(SpecTaskPlanningGroundingSupport.RELATED_FILES_REASON_PREFIX))
        assertTrue(userPrompt.contains("src/main/kotlin/com/example/SpecWorkflowPanel.kt"))
        assertTrue(generated.contains("relatedFiles:"))
        assertTrue(generated.contains("src/main/kotlin/com/example/SpecWorkflowPanel.kt"))
    }

    private fun promptCodeContextPack(phase: SpecPhase = SpecPhase.DESIGN): CodeContextPack {
        return CodeContextPack(
            phase = phase,
            projectStructure = ProjectStructureSummary(
                topLevelDirectories = listOf("src", "docs"),
                topLevelFiles = listOf("README.md"),
                keyPaths = listOf("src/main/kotlin", "src/test/kotlin"),
                summary = "Summarize existing modules, boundaries, extension points, and implementation constraints.",
            ),
            candidateFiles = listOf(
                CodeContextCandidateFile(
                    path = "src/main/kotlin/com/example/WorkflowEngine.kt",
                    signals = setOf(CodeContextCandidateSignal.VCS_CHANGE),
                ),
            ),
            changeSummary = CodeChangeSummary(
                source = CodeChangeSource.VCS_STATUS,
                files = listOf(
                    CodeChangeFile(
                        path = "src/main/kotlin/com/example/WorkflowEngine.kt",
                        status = CodeChangeFileStatus.MODIFIED,
                    ),
                ),
                summary = "Git working tree reports 1 changed file(s).",
                available = true,
            ),
        )
    }

    private fun promptDocument(
        phase: SpecPhase,
        id: String,
        content: String,
    ): SpecDocument {
        return SpecDocument(
            id = id,
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = "${phase.displayName} prompt document",
                description = "prompt baseline",
            ),
        )
    }
}
