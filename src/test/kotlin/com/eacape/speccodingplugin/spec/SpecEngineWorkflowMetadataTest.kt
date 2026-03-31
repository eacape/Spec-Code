package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SpecEngineWorkflowMetadataTest {

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
    fun `createWorkflow should expose metadata through list and open`() {
        val engine = SpecEngine(project, storage) { SpecGenerationResult.Failure("not used") }
        val workflow = engine.createWorkflow(
            title = "Metadata workflow",
            description = "list and open",
        ).getOrThrow()

        val listedMeta = engine.listWorkflowMetadata().firstOrNull { it.workflowId == workflow.id }
        assertNotNull(listedMeta)
        assertEquals(WorkflowTemplate.FULL_SPEC, listedMeta?.template)
        assertEquals(StageId.REQUIREMENTS, listedMeta?.currentStage)
        assertEquals(
            StageProgress.IN_PROGRESS,
            listedMeta?.stageStates?.get(StageId.REQUIREMENTS)?.status,
        )

        val opened = engine.openWorkflow(workflow.id).getOrThrow()
        assertEquals(workflow.id, opened.meta.workflowId)
        assertEquals(StageId.REQUIREMENTS, opened.meta.currentStage)
        assertEquals(workflow.id, opened.workflow.id)
    }

    @Test
    fun `proceedToNextPhase should update stage state metadata`() {
        val engine = SpecEngine(project, storage) { request ->
            val rawContent = when (request.phase) {
                SpecPhase.SPECIFY -> """
                    ## Functional Requirements
                    - Create tasks inside the workflow.

                    ## Non-Functional Requirements
                    - Keep the editor responsive.

                    ## User Stories
                    As a user, I want to create tasks quickly.

                    ## Acceptance Criteria
                    - [ ] The workflow can advance to design after generation.
                """.trimIndent()

                SpecPhase.DESIGN -> """
                    ## Architecture Design
                    - Use a layered plugin structure.

                    ## Technology Choices
                    - Kotlin + IntelliJ Platform

                    ## Data Model
                    data class Task(val id: String)

                    ## API Design
                    - createTask(title: String)

                    ## Non-Functional Design
                    - Keep stage metadata auditable.
                """.trimIndent()

                SpecPhase.IMPLEMENT -> """
                    ## Tasks

                    ### T-001: Complete implementation
                    ```spec-task
                    status: PENDING
                    priority: P0
                    dependsOn: []
                    relatedFiles: []
                    verificationResult: null
                    ```
                    - [ ] Write implementation code

                    ## Implementation Steps
                    1. Write implementation code
                    2. Run tests

                    ## Test Plan
                    - [ ] Unit tests
                """.trimIndent()
            }
            val draft = SpecDocument(
                id = "doc-${request.phase.name.lowercase()}",
                phase = request.phase,
                content = rawContent,
                metadata = SpecMetadata(
                    title = "${request.phase.displayName} Document",
                    description = "generated",
                ),
            )
            SpecGenerationResult.Success(
                draft.copy(validationResult = SpecValidator.validate(draft)),
            )
        }

        val workflow = engine.createWorkflow(
            title = "Stage transition metadata",
            description = "state progression",
        ).getOrThrow()
        runBlocking {
            engine.generateCurrentPhase(workflow.id, "generate").collect()
        }

        engine.proceedToNextPhase(workflow.id).getOrThrow()
        val reloaded = engine.reloadWorkflow(workflow.id).getOrThrow()
        assertEquals(StageId.DESIGN, reloaded.currentStage)
        assertEquals(StageProgress.DONE, reloaded.stageStates.getValue(StageId.REQUIREMENTS).status)
        assertTrue(reloaded.stageStates.getValue(StageId.REQUIREMENTS).completedAt != null)
        assertEquals(StageProgress.IN_PROGRESS, reloaded.stageStates.getValue(StageId.DESIGN).status)
    }
}
