package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow

internal data class SpecDetailClarificationLifecycleState(
    val clarificationState: SpecDetailClarificationFormState? = null,
    val activeDetailIndex: Int? = null,
    val checklistReadOnly: Boolean = false,
    val isGeneratingActive: Boolean = false,
    val isClarificationGenerating: Boolean = false,
)

internal data class SpecDetailClarificationGeneratingLifecyclePlan(
    val lifecycleState: SpecDetailClarificationLifecycleState,
    val questionsMarkdown: String,
    val suggestedDetails: String,
    val previewSurfacePlan: SpecDetailPreviewSurfacePlan,
)

internal data class SpecDetailClarificationDraftLifecyclePlan(
    val lifecycleState: SpecDetailClarificationLifecycleState,
    val questionsMarkdown: String,
    val suggestedDetails: String,
    val inputSyncPlan: SpecDetailClarificationInputSyncPlan?,
    val previewSurfacePlan: SpecDetailPreviewSurfacePlan,
    val statusPlan: SpecDetailPreviewStatusPlan,
)

internal data class SpecDetailClarificationExitLifecyclePlan(
    val lifecycleState: SpecDetailClarificationLifecycleState,
    val clearInput: Boolean,
    val previewSurfacePlan: SpecDetailPreviewSurfacePlan,
)

internal data class SpecDetailClarificationFailureRestorePlan(
    val lifecycleState: SpecDetailClarificationLifecycleState,
    val previewSurfacePlan: SpecDetailPreviewSurfacePlan?,
    val restoreClarificationPreview: Boolean,
    val restoreDocumentPhase: SpecPhase?,
    val statusPlan: SpecDetailPreviewStatusPlan?,
)

internal object SpecDetailClarificationLifecycleCoordinator {

    fun clear(): SpecDetailClarificationLifecycleState {
        return SpecDetailClarificationLifecycleState()
    }

    fun showGenerating(
        phase: SpecPhase,
        input: String,
        suggestedDetails: String,
        mode: ArtifactComposeActionMode,
    ): SpecDetailClarificationGeneratingLifecyclePlan {
        val questionsMarkdown = ArtifactComposeActionUiText.clarificationGenerating(mode)
        return SpecDetailClarificationGeneratingLifecyclePlan(
            lifecycleState = SpecDetailClarificationLifecycleState(
                clarificationState = SpecDetailClarificationFormState(
                    phase = phase,
                    input = input,
                    questionsMarkdown = questionsMarkdown,
                ),
                activeDetailIndex = null,
                checklistReadOnly = false,
                isGeneratingActive = true,
                isClarificationGenerating = true,
            ),
            questionsMarkdown = questionsMarkdown,
            suggestedDetails = suggestedDetails,
            previewSurfacePlan = SpecDetailPreviewSurfaceCoordinator.forClarification(isGenerating = true),
        )
    }

    fun showDraft(
        phase: SpecPhase,
        input: String,
        questionsMarkdown: String,
        suggestedDetails: String,
        structuredQuestions: List<String>,
        clarificationText: SpecDetailClarificationText,
        mode: ArtifactComposeActionMode,
    ): SpecDetailClarificationDraftLifecyclePlan {
        val draftState = SpecDetailClarificationFormState.draft(
            phase = phase,
            input = input,
            questionsMarkdown = questionsMarkdown,
            suggestedDetails = suggestedDetails,
            structuredQuestions = structuredQuestions,
            text = clarificationText,
        )
        val lifecycleState = SpecDetailClarificationLifecycleState(
            clarificationState = draftState.state,
            activeDetailIndex = draftState.activeDetailIndex,
            checklistReadOnly = false,
            isGeneratingActive = false,
            isClarificationGenerating = false,
        )
        return SpecDetailClarificationDraftLifecyclePlan(
            lifecycleState = lifecycleState,
            questionsMarkdown = questionsMarkdown,
            suggestedDetails = suggestedDetails,
            inputSyncPlan = if (draftState.state.checklistMode) {
                SpecDetailClarificationContextCoordinator.resolveInputSyncPlan(
                    state = draftState.state,
                    clarificationText = clarificationText,
                )
            } else {
                null
            },
            previewSurfacePlan = SpecDetailPreviewSurfaceCoordinator.forClarification(isGenerating = false),
            statusPlan = SpecDetailPreviewStatusCoordinator.clarificationHint(mode),
        )
    }

    fun stopGenerating(
        state: SpecDetailClarificationLifecycleState,
        unlockChecklist: Boolean = true,
    ): SpecDetailClarificationLifecycleState {
        return state.copy(
            checklistReadOnly = if (unlockChecklist) false else state.checklistReadOnly,
            isGeneratingActive = false,
            isClarificationGenerating = false,
        )
    }

    fun exit(isEditing: Boolean, clearInput: Boolean): SpecDetailClarificationExitLifecyclePlan {
        return SpecDetailClarificationExitLifecyclePlan(
            lifecycleState = clear(),
            clearInput = clearInput,
            previewSurfacePlan = if (isEditing) {
                SpecDetailPreviewSurfaceCoordinator.forEdit()
            } else {
                SpecDetailPreviewSurfaceCoordinator.forPreview()
            },
        )
    }

    fun restoreAfterFailure(
        state: SpecDetailClarificationLifecycleState,
        workflow: SpecWorkflow?,
        selectedPhase: SpecPhase?,
    ): SpecDetailClarificationFailureRestorePlan {
        val restoredState = stopGenerating(state = state, unlockChecklist = true)
        return when {
            workflow == null -> SpecDetailClarificationFailureRestorePlan(
                lifecycleState = restoredState,
                previewSurfacePlan = null,
                restoreClarificationPreview = false,
                restoreDocumentPhase = null,
                statusPlan = SpecDetailPreviewStatusCoordinator.noWorkflow(),
            )

            restoredState.clarificationState != null -> SpecDetailClarificationFailureRestorePlan(
                lifecycleState = restoredState,
                previewSurfacePlan = SpecDetailPreviewSurfaceCoordinator.forClarification(isGenerating = false),
                restoreClarificationPreview = true,
                restoreDocumentPhase = null,
                statusPlan = null,
            )

            else -> SpecDetailClarificationFailureRestorePlan(
                lifecycleState = restoredState,
                previewSurfacePlan = null,
                restoreClarificationPreview = false,
                restoreDocumentPhase = selectedPhase ?: workflow.currentPhase,
                statusPlan = null,
            )
        }
    }
}
