package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SpecRequirementsQuickFixServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage
    private lateinit var artifactService: SpecArtifactService
    private lateinit var engine: SpecEngine
    private lateinit var service: SpecRequirementsQuickFixService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
        artifactService = SpecArtifactService(project)
        engine = SpecEngine(project, storage, generationHandler = ::unusedGeneration)
        service = SpecRequirementsQuickFixService(
            project = project,
            storage = storage,
            artifactService = artifactService,
            updateDocument = { workflowId, content, expectedRevision ->
                engine.updateDocumentContent(
                    workflowId = workflowId,
                    phase = SpecPhase.SPECIFY,
                    content = content,
                    expectedRevision = expectedRevision,
                )
            },
        )
    }

    @Test
    fun `repairRequirementsArtifact should replace scaffold placeholders and append audit`() {
        val workflow = engine.createWorkflow(
            title = "Repair Requirements Draft",
            description = "repair placeholders",
        ).getOrThrow()

        val result = service.repairRequirementsArtifact(workflow.id)
        val persisted = artifactService.readArtifact(workflow.id, StageId.REQUIREMENTS).orEmpty()
        val reloaded = storage.loadWorkflow(workflow.id).getOrThrow()
        val audits = storage.listAuditEvents(workflow.id).getOrThrow()
            .filter { event -> event.eventType == SpecAuditEventType.REQUIREMENTS_ARTIFACT_REPAIRED }

        assertTrue(result.changed)
        assertEquals(
            listOf(
                RequirementsDraftIssueKind.TODO_PLACEHOLDERS,
                RequirementsDraftIssueKind.USER_STORY_TEMPLATE,
            ),
            result.issuesBefore,
        )
        assertTrue(result.issuesAfter.isEmpty())
        assertFalse(persisted.contains("TODO:"))
        assertFalse(persisted.contains("<role>"))
        assertTrue(persisted.contains("As a workflow author"))
        assertTrue(reloaded.getDocument(SpecPhase.SPECIFY)?.validationResult?.valid == true)
        assertEquals(1, audits.size)
        assertEquals("gate-quick-fix", audits.single().details["trigger"])
    }

    @Test
    fun `repairRequirementsArtifact should be noop when requirements no longer contain scaffold placeholders`() {
        val workflow = engine.createWorkflow(
            title = "Ready Requirements",
            description = "already concrete",
        ).getOrThrow()
        engine.updateDocumentContent(
            workflowId = workflow.id,
            phase = SpecPhase.SPECIFY,
            content = """
                # Requirements Document

                ## Functional Requirements
                - Support concrete requirement details.

                ## Non-Functional Requirements
                - Keep the workflow offline-first and auditable.

                ## User Stories
                As a reviewer, I want the requirements to be concrete, so that the workflow can continue safely.

                ## Acceptance Criteria
                - [ ] The scope, constraints, and acceptance criteria are verifiable.
            """.trimIndent(),
        ).getOrThrow()

        val result = service.repairRequirementsArtifact(workflow.id)
        val audits = storage.listAuditEvents(workflow.id).getOrThrow()
            .filter { event -> event.eventType == SpecAuditEventType.REQUIREMENTS_ARTIFACT_REPAIRED }

        assertFalse(result.changed)
        assertTrue(result.issuesBefore.isEmpty())
        assertTrue(result.issuesAfter.isEmpty())
        assertTrue(audits.isEmpty())
    }

    private suspend fun unusedGeneration(request: SpecGenerationRequest): SpecGenerationResult {
        error("Unexpected generation request for ${request.phase}")
    }
}
