package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.toStageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailPreviewContentCoordinatorTest {

    @Test
    fun `artifact only preview should use workbench content and unavailable message`() {
        val workflow = workflow(currentPhase = SpecPhase.IMPLEMENT)

        val plan = SpecDetailPreviewContentCoordinator.forActivePreview(
            workflow = workflow,
            selectedPhase = null,
            workbenchArtifactBinding = artifactBinding(
                stageId = StageId.VERIFY,
                fileName = "verification.md",
                documentPhase = null,
                available = false,
                previewContent = "# Verification\n\nPending result",
                unavailableMessage = "verification.md is missing",
            ),
            isGeneratingActive = false,
            keepGeneratingIndicator = false,
            revisionLockedPhase = null,
            isEditing = false,
        )

        assertEquals("# Verification\n\nPending result", plan.markdownContent)
        assertNull(plan.interactivePhase)
        assertFalse(plan.keepGeneratingLabel)
        assertEquals("verification.md is missing", plan.validationMessage?.text)
        assertEquals(SpecDetailPreviewValidationTone.ERROR, plan.validationMessage?.tone)
    }

    @Test
    fun `blank document should use workbench empty state without interactive checklist`() {
        val workflow = workflow(
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(phase = SpecPhase.SPECIFY, content = ""),
            ),
        )
        val emptyMessage = SpecCodingBundle.message("spec.detail.workbench.requirements.missing")

        val plan = SpecDetailPreviewContentCoordinator.forDocumentPreview(
            workflow = workflow,
            phase = SpecPhase.SPECIFY,
            workbenchArtifactBinding = artifactBinding(
                stageId = StageId.REQUIREMENTS,
                fileName = "requirements.md",
                documentPhase = SpecPhase.SPECIFY,
                available = false,
                emptyStateMessage = emptyMessage,
                unavailableMessage = emptyMessage,
            ),
            isGeneratingActive = false,
            keepGeneratingIndicator = false,
            revisionLockedPhase = null,
            isEditing = false,
        )

        assertEquals(emptyMessage, plan.markdownContent)
        assertNull(plan.interactivePhase)
        assertFalse(plan.keepGeneratingLabel)
        assertNull(plan.validationMessage)
    }

    @Test
    fun `document preview should keep generating label while generation is active`() {
        val workflow = workflow(
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = "- [ ] Prepare rollout checklist",
                    validationResult = ValidationResult(valid = false),
                ),
            ),
        )

        val plan = SpecDetailPreviewContentCoordinator.forDocumentPreview(
            workflow = workflow,
            phase = SpecPhase.IMPLEMENT,
            workbenchArtifactBinding = null,
            isGeneratingActive = true,
            keepGeneratingIndicator = true,
            revisionLockedPhase = null,
            isEditing = false,
        )

        assertEquals("- [ ] Prepare rollout checklist", plan.markdownContent)
        assertEquals(SpecPhase.IMPLEMENT, plan.interactivePhase)
        assertTrue(plan.keepGeneratingLabel)
        assertNull(plan.validationMessage)
    }

    @Test
    fun `revision lock should override document validation message`() {
        val workflow = workflow(
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = "# Design",
                    validationResult = ValidationResult(valid = true),
                ),
            ),
        )

        val plan = SpecDetailPreviewContentCoordinator.forDocumentPreview(
            workflow = workflow,
            phase = SpecPhase.DESIGN,
            workbenchArtifactBinding = null,
            isGeneratingActive = false,
            keepGeneratingIndicator = false,
            revisionLockedPhase = SpecPhase.DESIGN,
            isEditing = false,
        )

        assertFalse(plan.keepGeneratingLabel)
        assertEquals(
            SpecCodingBundle.message("spec.detail.revision.locked.banner", SpecCodingBundle.message("spec.detail.step.design")),
            plan.validationMessage?.text,
        )
        assertEquals(SpecDetailPreviewValidationTone.MUTED, plan.validationMessage?.tone)
    }

    @Test
    fun `validation failure preview should keep markdown and error tone`() {
        val plan = SpecDetailPreviewContentCoordinator.forValidationFailure(
            markdownContent = "# Tasks\n\n- missing implementation steps",
            validationMessage = "Validation failed",
        )

        assertEquals("# Tasks\n\n- missing implementation steps", plan.markdownContent)
        assertNull(plan.interactivePhase)
        assertFalse(plan.keepGeneratingLabel)
        assertEquals("Validation failed", plan.validationMessage?.text)
        assertEquals(SpecDetailPreviewValidationTone.ERROR, plan.validationMessage?.tone)
    }

    private fun workflow(
        currentPhase: SpecPhase,
        documents: Map<SpecPhase, SpecDocument> = emptyMap(),
    ): SpecWorkflow {
        return SpecWorkflow(
            id = "wf-preview-content-${currentPhase.name.lowercase()}",
            currentPhase = currentPhase,
            documents = documents,
            status = WorkflowStatus.IN_PROGRESS,
            title = "Preview Content",
            description = "Preview routing coordinator",
            stageStates = emptyMap(),
            currentStage = currentPhase.toStageId(),
            createdAt = 1L,
            updatedAt = 2L,
        )
    }

    private fun document(
        phase: SpecPhase,
        content: String,
        validationResult: ValidationResult? = null,
    ): SpecDocument {
        return SpecDocument(
            id = "doc-${phase.name.lowercase()}",
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = phase.displayName,
                description = "${phase.displayName} document",
                createdAt = 1L,
                updatedAt = 2L,
            ),
            validationResult = validationResult,
        )
    }

    private fun artifactBinding(
        stageId: StageId,
        fileName: String,
        documentPhase: SpecPhase?,
        available: Boolean,
        previewContent: String? = null,
        emptyStateMessage: String? = null,
        unavailableMessage: String? = null,
    ): SpecWorkflowStageArtifactBinding {
        return SpecWorkflowStageArtifactBinding(
            stageId = stageId,
            title = fileName,
            fileName = fileName,
            documentPhase = documentPhase,
            mode = SpecWorkflowWorkbenchDocumentMode.READ_ONLY,
            fallbackEditable = false,
            available = available,
            previewContent = previewContent,
            emptyStateMessage = emptyStateMessage,
            unavailableMessage = unavailableMessage,
        )
    }
}
