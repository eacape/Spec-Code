package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailClarificationModeCoordinatorTest {

    @Test
    fun `resolve should keep clarification actions hidden when clarification is inactive`() {
        val state = SpecDetailClarificationModeCoordinator.resolve(
            workflowStatus = WorkflowStatus.IN_PROGRESS,
            composeMode = ArtifactComposeActionMode.GENERATE,
            lifecycleState = SpecDetailClarificationLifecycleState(),
        )

        assertFalse(state.isActive)
        assertTrue(state.standardActionsVisible)
        assertFalse(state.confirmGenerate.visible)
        assertFalse(state.regenerateClarification.visible)
        assertFalse(state.skipClarification.visible)
        assertFalse(state.cancelClarification.visible)
    }

    @Test
    fun `resolve should enable clarification actions for editable draft`() {
        val state = SpecDetailClarificationModeCoordinator.resolve(
            workflowStatus = WorkflowStatus.IN_PROGRESS,
            composeMode = ArtifactComposeActionMode.GENERATE,
            lifecycleState = lifecycleState(),
        )

        assertTrue(state.isActive)
        assertFalse(state.standardActionsVisible)
        assertTrue(state.confirmGenerate.visible)
        assertTrue(state.confirmGenerate.enabled)
        assertTrue(state.regenerateClarification.enabled)
        assertTrue(state.skipClarification.enabled)
        assertTrue(state.cancelClarification.enabled)
    }

    @Test
    fun `resolve should disable clarification actions while clarification is generating`() {
        val state = SpecDetailClarificationModeCoordinator.resolve(
            workflowStatus = WorkflowStatus.IN_PROGRESS,
            composeMode = ArtifactComposeActionMode.REVISE,
            lifecycleState = lifecycleState().copy(isGeneratingActive = true, isClarificationGenerating = true),
        )

        assertTrue(state.confirmGenerate.visible)
        assertFalse(state.confirmGenerate.enabled)
        assertEquals(
            ArtifactComposeActionUiText.primaryActionDisabledReason(
                mode = ArtifactComposeActionMode.REVISE,
                status = WorkflowStatus.IN_PROGRESS,
                isGeneratingActive = true,
                isEditing = false,
            ),
            state.confirmGenerate.disabledReason,
        )
        assertFalse(state.regenerateClarification.enabled)
        assertFalse(state.skipClarification.enabled)
        assertFalse(state.cancelClarification.enabled)
    }

    @Test
    fun `resolve should lock clarification actions when checklist becomes read only`() {
        val state = SpecDetailClarificationModeCoordinator.resolve(
            workflowStatus = WorkflowStatus.IN_PROGRESS,
            composeMode = ArtifactComposeActionMode.REVISE,
            lifecycleState = lifecycleState().copy(checklistReadOnly = true),
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

    @Test
    fun `resolve should keep cancel available outside in progress status`() {
        val state = SpecDetailClarificationModeCoordinator.resolve(
            workflowStatus = WorkflowStatus.PAUSED,
            composeMode = ArtifactComposeActionMode.GENERATE,
            lifecycleState = lifecycleState(),
        )

        assertFalse(state.confirmGenerate.enabled)
        assertFalse(state.regenerateClarification.enabled)
        assertFalse(state.skipClarification.enabled)
        assertTrue(state.cancelClarification.enabled)
    }

    private fun lifecycleState(): SpecDetailClarificationLifecycleState {
        return SpecDetailClarificationLifecycleState(
            clarificationState = SpecDetailClarificationFormState(
                phase = SpecPhase.SPECIFY,
                input = "clarify offline mode",
                questionsMarkdown = "1. question",
            ),
        )
    }
}
