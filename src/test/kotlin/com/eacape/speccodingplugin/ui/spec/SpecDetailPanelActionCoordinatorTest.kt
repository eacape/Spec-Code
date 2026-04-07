package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailPanelActionCoordinatorTest {

    @Test
    fun `resolve should keep edit available when revision is locked but block generate`() {
        val workflow = workflow(status = WorkflowStatus.IN_PROGRESS)
        val viewState = SpecDetailPanelViewState(
            artifactOnlyView = false,
            displayedDocumentPhase = SpecPhase.SPECIFY,
            editablePhase = SpecPhase.SPECIFY,
            revisionLockedPhase = SpecPhase.SPECIFY,
            selectedDocumentAvailable = true,
            artifactOpenAvailable = false,
        )

        val state = SpecDetailPanelActionCoordinator.resolve(
            workflow = workflow,
            composeMode = ArtifactComposeActionMode.GENERATE,
            viewState = viewState,
            isEditing = false,
            clarificationLifecycleState = SpecDetailClarificationLifecycleState(),
            revisionLockedDisabledReason = { phase -> "locked:${phase.name}" },
        )

        assertTrue(state.generate.visible)
        assertFalse(state.generate.enabled)
        assertEquals("locked:SPECIFY", state.generate.disabledReason)
        assertTrue(state.edit.visible)
        assertTrue(state.edit.enabled)
        assertTrue(state.openEditor.enabled)
        assertTrue(state.historyDiff.enabled)
    }

    @Test
    fun `resolve should hide edit and history actions for artifact only preview`() {
        val workflow = workflow(status = WorkflowStatus.IN_PROGRESS)
        val viewState = SpecDetailPanelViewState(
            artifactOnlyView = true,
            displayedDocumentPhase = null,
            editablePhase = null,
            revisionLockedPhase = null,
            selectedDocumentAvailable = false,
            artifactOpenAvailable = true,
        )

        val state = SpecDetailPanelActionCoordinator.resolve(
            workflow = workflow,
            composeMode = ArtifactComposeActionMode.GENERATE,
            viewState = viewState,
            isEditing = false,
            clarificationLifecycleState = SpecDetailClarificationLifecycleState(),
            revisionLockedDisabledReason = { "locked:${it.name}" },
        )

        assertTrue(state.openEditor.visible)
        assertTrue(state.openEditor.enabled)
        assertFalse(state.historyDiff.visible)
        assertFalse(state.edit.visible)
    }

    @Test
    fun `resolve should disable clarification confirm when checklist is read only`() {
        val workflow = workflow(status = WorkflowStatus.IN_PROGRESS)
        val viewState = SpecDetailPanelViewState(
            artifactOnlyView = false,
            displayedDocumentPhase = SpecPhase.DESIGN,
            editablePhase = SpecPhase.DESIGN,
            revisionLockedPhase = null,
            selectedDocumentAvailable = true,
            artifactOpenAvailable = false,
        )

        val state = SpecDetailPanelActionCoordinator.resolve(
            workflow = workflow,
            composeMode = ArtifactComposeActionMode.REVISE,
            viewState = viewState,
            isEditing = false,
            clarificationLifecycleState = SpecDetailClarificationLifecycleState(
                clarificationState = SpecDetailClarificationFormState(
                    phase = SpecPhase.DESIGN,
                    input = "clarify",
                    questionsMarkdown = "question",
                ),
                checklistReadOnly = true,
            ),
            revisionLockedDisabledReason = { "locked:${it.name}" },
        )

        assertTrue(state.confirmGenerate.visible)
        assertFalse(state.confirmGenerate.enabled)
        assertEquals(
            SpecCodingBundle.message("spec.detail.clarify.confirm.disabled.revise"),
            state.confirmGenerate.disabledReason,
        )
        assertFalse(state.regenerateClarification.enabled)
        assertFalse(state.skipClarification.enabled)
        assertFalse(state.cancelClarification.enabled)
    }

    private fun workflow(status: WorkflowStatus): SpecWorkflow {
        return SpecWorkflow(
            id = "wf-detail-actions",
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.DESIGN to document(SpecPhase.DESIGN, "design"),
            ),
            status = status,
            title = "Detail actions",
            description = "detail actions test",
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
