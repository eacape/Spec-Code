package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailPreviewChecklistInteractionCoordinatorTest {

    @Test
    fun `cursorKind should prefer wait while checklist save is in flight`() {
        val kind = SpecDetailPreviewChecklistInteractionCoordinator.cursorKind(
            interaction = interaction(),
            hoveredLineIndex = 1,
            isEditing = false,
            hasClarificationState = false,
            isSaving = true,
        )

        assertEquals(SpecDetailPreviewChecklistCursorKind.WAIT, kind)
    }

    @Test
    fun `cursorKind should only return hand for interactive hovered checklist rows`() {
        assertEquals(
            SpecDetailPreviewChecklistCursorKind.HAND,
            SpecDetailPreviewChecklistInteractionCoordinator.cursorKind(
                interaction = interaction(),
                hoveredLineIndex = 1,
                isEditing = false,
                hasClarificationState = false,
                isSaving = false,
            ),
        )
        assertEquals(
            SpecDetailPreviewChecklistCursorKind.DEFAULT,
            SpecDetailPreviewChecklistInteractionCoordinator.cursorKind(
                interaction = interaction(),
                hoveredLineIndex = null,
                isEditing = false,
                hasClarificationState = false,
                isSaving = false,
            ),
        )
        assertEquals(
            SpecDetailPreviewChecklistCursorKind.DEFAULT,
            SpecDetailPreviewChecklistInteractionCoordinator.cursorKind(
                interaction = interaction(),
                hoveredLineIndex = 1,
                isEditing = true,
                hasClarificationState = false,
                isSaving = false,
            ),
        )
    }

    @Test
    fun `buildTogglePlan should ignore blocked and invalid toggle requests`() {
        assertTrue(
            SpecDetailPreviewChecklistInteractionCoordinator.buildTogglePlan(
                interaction = null,
                lineIndex = 1,
                isEditing = false,
                hasClarificationState = false,
                isSaving = false,
            ) is SpecDetailPreviewChecklistTogglePlan.Ignore,
        )
        assertTrue(
            SpecDetailPreviewChecklistInteractionCoordinator.buildTogglePlan(
                interaction = interaction(),
                lineIndex = null,
                isEditing = false,
                hasClarificationState = false,
                isSaving = false,
            ) is SpecDetailPreviewChecklistTogglePlan.Ignore,
        )
        assertTrue(
            SpecDetailPreviewChecklistInteractionCoordinator.buildTogglePlan(
                interaction = interaction(),
                lineIndex = 1,
                isEditing = false,
                hasClarificationState = true,
                isSaving = false,
            ) is SpecDetailPreviewChecklistTogglePlan.Ignore,
        )
        assertTrue(
            SpecDetailPreviewChecklistInteractionCoordinator.buildTogglePlan(
                interaction = interaction(),
                lineIndex = 0,
                isEditing = false,
                hasClarificationState = false,
                isSaving = false,
            ) is SpecDetailPreviewChecklistTogglePlan.Ignore,
        )
    }

    @Test
    fun `buildTogglePlan should emit save plan with toggled checklist content`() {
        val plan = SpecDetailPreviewChecklistInteractionCoordinator.buildTogglePlan(
            interaction = interaction(),
            lineIndex = 1,
            isEditing = false,
            hasClarificationState = false,
            isSaving = false,
        )

        assertTrue(plan is SpecDetailPreviewChecklistTogglePlan.Save)
        plan as SpecDetailPreviewChecklistTogglePlan.Save
        assertEquals(SpecPhase.IMPLEMENT, plan.phase)
        assertEquals(
            """
            ### T-002: rollout
            - [x] Ship fix
            - [x] Verify smoke
            """.trimIndent(),
            plan.updatedContent,
        )
    }

    @Test
    fun `buildSaveCompletionPlan should refresh workflow on success and buttons on failure`() {
        val workflow = workflow()

        val successPlan = SpecDetailPreviewChecklistInteractionCoordinator.buildSaveCompletionPlan(
            result = Result.success(workflow),
            hasCurrentWorkflow = true,
        )
        val failurePlan = SpecDetailPreviewChecklistInteractionCoordinator.buildSaveCompletionPlan(
            result = Result.failure(IllegalStateException("boom")),
            hasCurrentWorkflow = true,
        )
        val emptyFailurePlan = SpecDetailPreviewChecklistInteractionCoordinator.buildSaveCompletionPlan(
            result = Result.failure(IllegalStateException("boom")),
            hasCurrentWorkflow = false,
        )

        assertEquals(workflow, successPlan.updatedWorkflow)
        assertFalse(successPlan.refreshButtonStates)
        assertNull(failurePlan.updatedWorkflow)
        assertTrue(failurePlan.refreshButtonStates)
        assertNull(emptyFailurePlan.updatedWorkflow)
        assertFalse(emptyFailurePlan.refreshButtonStates)
    }

    private fun interaction(): SpecDetailPreviewChecklistInteractionPlan {
        return SpecDetailPreviewChecklistInteractionPlan(
            phase = SpecPhase.IMPLEMENT,
            content = """
                ### T-002: rollout
                - [ ] Ship fix
                - [x] Verify smoke
            """.trimIndent(),
        )
    }

    private fun workflow(): SpecWorkflow {
        return SpecWorkflow(
            id = "wf-preview-checklist-save",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Preview Checklist Save",
            description = "Preview checklist interaction coordinator",
            createdAt = 1L,
            updatedAt = 2L,
        )
    }
}
