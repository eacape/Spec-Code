package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.RequirementsSectionRepairService
import com.eacape.speccodingplugin.spec.SpecArtifactService
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecGenerationRequest
import com.eacape.speccodingplugin.spec.SpecGenerationResult
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecStorage
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RequirementsSectionRepairUiSupportTest {

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
    fun `previewAndApply should keep requirements unchanged when preview is cancelled`() {
        val workflow = engine.createWorkflow(
            title = "repair-ui-cancel",
            description = "cancel preview should not write",
        ).getOrThrow()
        engine.updateDocumentContent(workflow.id, SpecPhase.SPECIFY, REQUIREMENTS_WITH_GAPS).getOrThrow()
        val repairService = RequirementsSectionRepairService(
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
            draftGenerator = {
                Result.success(
                    """
                    ## Non-Functional Requirements
                    - Preserve the existing requirements file until the user confirms.
                    """.trimIndent(),
                )
            },
            llmRouter = mockk<LlmRouter>(relaxed = true),
        )
        val contentBefore = artifactService.readArtifact(workflow.id, com.eacape.speccodingplugin.spec.StageId.REQUIREMENTS).orEmpty()
        val historyBefore = storage.listDocumentHistory(workflow.id, SpecPhase.SPECIFY).size
        var previewCancelled = false
        var applyRunnerInvoked = false

        RequirementsSectionRepairUiSupport.previewAndApply(
            project = project,
            workflowId = workflow.id,
            missingSections = listOf(RequirementsSectionId.NON_FUNCTIONAL),
            onPreviewCancelled = { previewCancelled = true },
            onFailure = { error -> fail("Unexpected repair failure: ${error.message}") },
            repairServiceFactory = { repairService },
            previewRunner = { _, task, onSuccess, _ -> onSuccess(task()) },
            applyRunner = { _, _, _, _ -> applyRunnerInvoked = true },
            previewDialogPresenter = { false },
            showInfo = { _, _ -> fail("No info dialog expected when preview is cancelled") },
            notifySuccess = { fail("No success notification expected when preview is cancelled") },
            openRequirementsDocument = { fail("No file should be opened when preview is cancelled") },
            selectWorkflow = { fail("Workflow selection should not change when preview is cancelled") },
        )

        val contentAfter = artifactService.readArtifact(workflow.id, com.eacape.speccodingplugin.spec.StageId.REQUIREMENTS).orEmpty()
        val historyAfter = storage.listDocumentHistory(workflow.id, SpecPhase.SPECIFY).size

        assertTrue(previewCancelled)
        assertFalse(applyRunnerInvoked)
        assertEquals(contentBefore, contentAfter)
        assertEquals(historyBefore, historyAfter)
    }

    private suspend fun unusedGeneration(request: SpecGenerationRequest): SpecGenerationResult {
        error("Unexpected generation request for ${request.phase}")
    }

    companion object {
        private val REQUIREMENTS_WITH_GAPS = """
            # Requirements Document

            ## Functional Requirements
            - Preserve the user-authored functional details.

            ## Acceptance Criteria
            - [ ] Repair preview remains cancellable.
        """.trimIndent()
    }
}
