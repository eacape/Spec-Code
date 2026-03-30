package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailPanelViewStateTest {

    @Test
    fun `resolve should keep completed requirements read only until revision starts`() {
        val workflow = completedRequirementsWorkflow()

        val state = SpecDetailPanelViewState.resolve(
            workflow = workflow,
            selectedPhase = SpecPhase.SPECIFY,
            preferredWorkbenchPhase = null,
            explicitRevisionPhase = null,
            workbenchArtifactBinding = null,
        )

        assertFalse(state.artifactOnlyView)
        assertEquals(SpecPhase.SPECIFY, state.displayedDocumentPhase)
        assertEquals(SpecPhase.SPECIFY, state.editablePhase)
        assertEquals(SpecPhase.SPECIFY, state.revisionLockedPhase)
        assertTrue(state.selectedDocumentAvailable)
        assertFalse(state.artifactOpenAvailable)
        assertTrue(state.editRequiresExplicitRevisionStart)
    }

    @Test
    fun `resolve should unlock completed design when explicit revision matches selected phase`() {
        val workflow = completedRequirementsWorkflow()

        val state = SpecDetailPanelViewState.resolve(
            workflow = workflow,
            selectedPhase = SpecPhase.DESIGN,
            preferredWorkbenchPhase = null,
            explicitRevisionPhase = SpecPhase.DESIGN,
            workbenchArtifactBinding = null,
        )

        assertEquals(SpecPhase.DESIGN, state.displayedDocumentPhase)
        assertEquals(SpecPhase.DESIGN, state.editablePhase)
        assertNull(state.revisionLockedPhase)
        assertFalse(state.editRequiresExplicitRevisionStart)
    }

    @Test
    fun `resolve should switch to artifact only workbench view when binding has no document phase`() {
        val workflow = completedRequirementsWorkflow()

        val state = SpecDetailPanelViewState.resolve(
            workflow = workflow,
            selectedPhase = SpecPhase.DESIGN,
            preferredWorkbenchPhase = SpecPhase.DESIGN,
            explicitRevisionPhase = null,
            workbenchArtifactBinding = SpecWorkflowStageArtifactBinding(
                stageId = StageId.VERIFY,
                title = "verification.md",
                fileName = "verification.md",
                documentPhase = null,
                mode = SpecWorkflowWorkbenchDocumentMode.READ_ONLY,
                fallbackEditable = false,
                available = true,
            ),
        )

        assertTrue(state.artifactOnlyView)
        assertNull(state.displayedDocumentPhase)
        assertNull(state.editablePhase)
        assertNull(state.revisionLockedPhase)
        assertTrue(state.selectedDocumentAvailable)
        assertTrue(state.artifactOpenAvailable)
    }

    private fun completedRequirementsWorkflow(): SpecWorkflow {
        return SpecWorkflow(
            id = "wf-detail-state",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.SPECIFY to document(SpecPhase.SPECIFY, "requirements"),
                SpecPhase.DESIGN to document(SpecPhase.DESIGN, "design"),
                SpecPhase.IMPLEMENT to document(SpecPhase.IMPLEMENT, "tasks"),
            ),
            stageStates = mapOf(
                StageId.REQUIREMENTS to StageState(active = true, status = StageProgress.DONE),
                StageId.DESIGN to StageState(active = true, status = StageProgress.DONE),
                StageId.IMPLEMENT to StageState(active = true, status = StageProgress.IN_PROGRESS),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Detail state",
            description = "detail state test",
            createdAt = 1L,
            updatedAt = 2L,
        )
    }

    private fun document(phase: SpecPhase, content: String): SpecDocument {
        return SpecDocument(
            id = "doc-${phase.name.lowercase()}",
            phase = phase,
            content = content,
            metadata = SpecMetadata(title = phase.displayName, description = phase.description),
            validationResult = ValidationResult(valid = true),
        )
    }
}
