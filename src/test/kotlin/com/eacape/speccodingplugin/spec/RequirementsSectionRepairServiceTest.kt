package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.llm.LlmRouter
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

class RequirementsSectionRepairServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage
    private lateinit var artifactService: SpecArtifactService
    private lateinit var engine: SpecEngine

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
        artifactService = SpecArtifactService(project)
        engine = SpecEngine(project, storage, generationHandler = ::unusedGeneration)
    }

    @Test
    fun `previewRepair should insert missing sections in canonical order without mutating document`() {
        val workflow = engine.createWorkflow(
            title = "repair-preview-order",
            description = "preview only",
        ).getOrThrow()
        engine.updateDocumentContent(workflow.id, SpecPhase.SPECIFY, ENGLISH_REQUIREMENTS_WITH_GAPS).getOrThrow()
        val service = repairService(
            draft = """
                ## User Stories
                As a planner, I want targeted repairs, so that I can keep the current draft.

                ## Non-Functional Requirements
                - Keep the repair deterministic and file-first.
            """.trimIndent(),
        )

        val contentBeforePreview = loadRequirementsContent(workflow.id)
        val preview = service.previewRepair(
            workflow.id,
            listOf(RequirementsSectionId.NON_FUNCTIONAL, RequirementsSectionId.USER_STORIES),
        )
        val contentAfterPreview = loadRequirementsContent(workflow.id)

        assertEquals(contentBeforePreview, contentAfterPreview)
        assertEquals(
            listOf(RequirementsSectionId.NON_FUNCTIONAL, RequirementsSectionId.USER_STORIES),
            preview.patches.map { patch -> patch.sectionId },
        )
        assertTrue(
            preview.updatedContent.indexOf("## Functional Requirements") <
                preview.updatedContent.indexOf("## Non-Functional Requirements"),
        )
        assertTrue(
            preview.updatedContent.indexOf("## Non-Functional Requirements") <
                preview.updatedContent.indexOf("## User Stories"),
        )
        assertTrue(
            preview.updatedContent.indexOf("## User Stories") <
                preview.updatedContent.indexOf("## Acceptance Criteria"),
        )
        assertTrue(preview.updatedContent.contains("- Keep the repair deterministic and file-first."))
        assertTrue(
            preview.updatedContent.contains(
                "As a planner, I want targeted repairs, so that I can keep the current draft.",
            ),
        )
    }

    @Test
    fun `applyPreview should persist updated requirements and append document history`() {
        val workflow = engine.createWorkflow(
            title = "repair-apply",
            description = "apply preview",
        ).getOrThrow()
        engine.updateDocumentContent(workflow.id, SpecPhase.SPECIFY, ENGLISH_REQUIREMENTS_WITH_GAPS).getOrThrow()
        val service = repairService(
            draft = """
                ## Non-Functional Requirements
                - Keep repairs auditable and stable.

                ## User Stories
                As a reviewer, I want a narrow patch preview, so that I can approve additions safely.
            """.trimIndent(),
        )
        val preview = service.previewRepair(workflow.id, emptyList())
        val historyBeforeApply = storage.listDocumentHistory(workflow.id, SpecPhase.SPECIFY).size

        val result = service.applyPreview(preview)
        val persistedArtifact = artifactService.readArtifact(workflow.id, StageId.REQUIREMENTS).orEmpty()
        val historyAfterApply = storage.listDocumentHistory(workflow.id, SpecPhase.SPECIFY).size
        val documentSaveAudits = storage.listAuditEvents(workflow.id).getOrThrow()
            .filter { event -> event.eventType == SpecAuditEventType.DOCUMENT_SAVED }

        assertTrue(persistedArtifact.contains("## Non-Functional Requirements"))
        assertTrue(persistedArtifact.contains("## User Stories"))
        assertTrue(historyAfterApply > historyBeforeApply)
        assertTrue(documentSaveAudits.size >= 2)
        assertEquals(
            artifactService.locateArtifact(workflow.id, StageId.REQUIREMENTS),
            result.requirementsDocumentPath,
        )
    }

    @Test
    fun `previewRepair should localize generated headings to existing document style`() {
        val workflow = engine.createWorkflow(
            title = "repair-localized-style",
            description = "localized headings",
        ).getOrThrow()
        engine.updateDocumentContent(workflow.id, SpecPhase.SPECIFY, CHINESE_REQUIREMENTS_WITH_GAPS).getOrThrow()
        val service = repairService(
            draft = """
                ## Non-Functional Requirements
                - 保持补全流程稳定且可回放。

                ## User Stories
                作为规格作者，我希望只补全缺失章节，以便保留已有内容。
            """.trimIndent(),
        )

        val preview = service.previewRepair(workflow.id, emptyList())

        assertTrue(preview.updatedContent.contains("## 非功能需求"))
        assertTrue(preview.updatedContent.contains("## 用户故事"))
        assertFalse(preview.updatedContent.contains("## Non-Functional Requirements"))
        assertFalse(preview.updatedContent.contains("## User Stories"))
    }

    @Test
    fun `previewRepair should insert missing leading sections before the first later section in canonical order`() {
        val workflow = engine.createWorkflow(
            title = "repair-leading-sections",
            description = "stable insertion order",
        ).getOrThrow()
        engine.updateDocumentContent(
            workflow.id,
            SpecPhase.SPECIFY,
            """
                # Requirements Document

                ## User Stories
                As a writer, I want later sections to stay in place, so that repairs are predictable.

                ## Acceptance Criteria
                - [ ] Missing leading sections are inserted ahead of later ones.
            """.trimIndent(),
        ).getOrThrow()
        val service = repairService(
            draft = """
                ## Functional Requirements
                - Preserve the canonical top-level section order.

                ## Non-Functional Requirements
                - Insert the repair before the first later section when needed.
            """.trimIndent(),
        )

        val preview = service.previewRepair(workflow.id, emptyList())

        assertEquals(
            listOf(RequirementsSectionId.FUNCTIONAL, RequirementsSectionId.NON_FUNCTIONAL),
            preview.patches.map { patch -> patch.sectionId },
        )
        assertTrue(
            preview.updatedContent.indexOf("## Functional Requirements") <
                preview.updatedContent.indexOf("## Non-Functional Requirements"),
        )
        assertTrue(
            preview.updatedContent.indexOf("## Non-Functional Requirements") <
                preview.updatedContent.indexOf("## User Stories"),
        )
    }

    @Test
    fun `previewRepair should treat explicit blank clarification override as no context`() {
        val workflow = engine.createWorkflow(
            title = "repair-blank-override",
            description = "skip clarification context",
        ).getOrThrow()
        engine.saveClarificationRetryState(
            workflow.id,
            ClarificationRetryState(
                input = "repair requirements",
                confirmedContext = "Should not leak into skipped clarify repair.",
                questionsMarkdown = "1. Clarify missing sections?",
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = listOf(RequirementsSectionId.NON_FUNCTIONAL),
            ),
        ).getOrThrow()
        engine.updateDocumentContent(workflow.id, SpecPhase.SPECIFY, ENGLISH_REQUIREMENTS_WITH_GAPS).getOrThrow()

        var capturedContext: String? = null
        val service = RequirementsSectionRepairService(
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
            draftGenerator = { request ->
                capturedContext = request.confirmedContext
                Result.success(
                    """
                    ## Non-Functional Requirements
                    - Keep repair output deterministic.
                    """.trimIndent(),
                )
            },
            llmRouter = mockk<LlmRouter>(relaxed = true),
        )

        service.previewRepair(
            workflowId = workflow.id,
            requestedMissingSections = listOf(RequirementsSectionId.NON_FUNCTIONAL),
            confirmedContextOverride = "",
        )

        assertEquals("", capturedContext)
    }

    private fun repairService(draft: String): RequirementsSectionRepairService {
        return RequirementsSectionRepairService(
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
            draftGenerator = { Result.success(draft) },
            llmRouter = mockk<LlmRouter>(relaxed = true),
        )
    }

    private fun loadRequirementsContent(workflowId: String): String {
        return storage.loadWorkflow(workflowId).getOrThrow()
            .getDocument(SpecPhase.SPECIFY)
            ?.content
            .orEmpty()
            .trim()
    }

    private suspend fun unusedGeneration(request: SpecGenerationRequest): SpecGenerationResult {
        error("Unexpected generation request for ${request.phase}")
    }

    companion object {
        private val ENGLISH_REQUIREMENTS_WITH_GAPS = """
            # Requirements Document

            ## Functional Requirements
            - Keep the existing functional details untouched.

            ## Acceptance Criteria
            - [ ] Missing sections can be repaired without rewriting the whole file.
        """.trimIndent()

        private val CHINESE_REQUIREMENTS_WITH_GAPS = """
            # 需求文档

            ## 功能需求
            - 保留现有功能性描述。

            ## 验收标准
            - [ ] 仅新增缺失章节，不改写已有内容。
        """.trimIndent()
    }
}
