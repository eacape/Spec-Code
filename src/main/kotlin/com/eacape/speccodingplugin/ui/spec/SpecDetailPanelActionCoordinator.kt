package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus

internal data class SpecDetailPanelActionButtonState(
    val visible: Boolean,
    val enabled: Boolean,
    val disabledReason: String? = null,
)

internal data class SpecDetailPanelActionState(
    val generate: SpecDetailPanelActionButtonState,
    val nextPhase: SpecDetailPanelActionButtonState,
    val goBack: SpecDetailPanelActionButtonState,
    val complete: SpecDetailPanelActionButtonState,
    val pauseResume: SpecDetailPanelActionButtonState,
    val openEditor: SpecDetailPanelActionButtonState,
    val historyDiff: SpecDetailPanelActionButtonState,
    val edit: SpecDetailPanelActionButtonState,
    val save: SpecDetailPanelActionButtonState,
    val cancelEdit: SpecDetailPanelActionButtonState,
    val confirmGenerate: SpecDetailPanelActionButtonState,
    val regenerateClarification: SpecDetailPanelActionButtonState,
    val skipClarification: SpecDetailPanelActionButtonState,
    val cancelClarification: SpecDetailPanelActionButtonState,
)

internal object SpecDetailPanelActionCoordinator {

    fun resolve(
        workflow: SpecWorkflow,
        composeMode: ArtifactComposeActionMode,
        viewState: SpecDetailPanelViewState,
        isEditing: Boolean,
        clarificationLifecycleState: SpecDetailClarificationLifecycleState,
        revisionLockedDisabledReason: (SpecPhase) -> String,
    ): SpecDetailPanelActionState {
        val inProgress = workflow.status == WorkflowStatus.IN_PROGRESS
        val clarificationMode = SpecDetailClarificationModeCoordinator.resolve(
            workflowStatus = workflow.status,
            composeMode = composeMode,
            lifecycleState = clarificationLifecycleState,
        )
        val standardModeEnabled = inProgress &&
            !isEditing &&
            !clarificationMode.isActive &&
            !clarificationLifecycleState.isGeneratingActive &&
            viewState.revisionLockedPhase == null

        return SpecDetailPanelActionState(
            generate = button(
                visible = clarificationMode.standardActionsVisible,
                enabled = standardModeEnabled,
                disabledReason = viewState.revisionLockedPhase?.let(revisionLockedDisabledReason)
                    ?: ArtifactComposeActionUiText.primaryActionDisabledReason(
                        mode = composeMode,
                        status = workflow.status,
                        isGeneratingActive = clarificationLifecycleState.isGeneratingActive,
                        isEditing = isEditing,
                    ),
            ),
            nextPhase = button(
                visible = clarificationMode.standardActionsVisible,
                enabled = workflow.canProceedToNext() && standardModeEnabled,
            ),
            goBack = hiddenButton(),
            complete = hiddenButton(),
            pauseResume = hiddenButton(),
            openEditor = button(
                visible = clarificationMode.standardActionsVisible,
                enabled = !isEditing &&
                    clarificationMode.standardActionsVisible &&
                    (viewState.selectedDocumentAvailable || viewState.artifactOpenAvailable),
            ),
            historyDiff = button(
                visible = clarificationMode.standardActionsVisible && !viewState.artifactOnlyView,
                enabled = !viewState.artifactOnlyView &&
                    !isEditing &&
                    clarificationMode.standardActionsVisible &&
                    viewState.selectedDocumentAvailable,
            ),
            edit = button(
                visible = clarificationMode.standardActionsVisible && !viewState.artifactOnlyView && !isEditing,
                enabled = !viewState.artifactOnlyView &&
                    !isEditing &&
                    !clarificationLifecycleState.isGeneratingActive &&
                    clarificationMode.standardActionsVisible &&
                    viewState.editablePhase != null,
            ),
            save = button(visible = clarificationMode.standardActionsVisible && isEditing, enabled = isEditing),
            cancelEdit = button(visible = clarificationMode.standardActionsVisible && isEditing, enabled = isEditing),
            confirmGenerate = clarificationMode.confirmGenerate,
            regenerateClarification = clarificationMode.regenerateClarification,
            skipClarification = clarificationMode.skipClarification,
            cancelClarification = clarificationMode.cancelClarification,
        )
    }

    private fun button(
        visible: Boolean,
        enabled: Boolean,
        disabledReason: String? = null,
    ): SpecDetailPanelActionButtonState {
        return SpecDetailPanelActionButtonState(
            visible = visible,
            enabled = enabled,
            disabledReason = disabledReason,
        )
    }

    private fun hiddenButton(): SpecDetailPanelActionButtonState {
        return SpecDetailPanelActionButtonState(
            visible = false,
            enabled = false,
        )
    }
}
