package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.WorkflowStatus

internal data class SpecDetailClarificationModeActionState(
    val isActive: Boolean,
    val standardActionsVisible: Boolean,
    val confirmGenerate: SpecDetailPanelActionButtonState,
    val regenerateClarification: SpecDetailPanelActionButtonState,
    val skipClarification: SpecDetailPanelActionButtonState,
    val cancelClarification: SpecDetailPanelActionButtonState,
)

internal object SpecDetailClarificationModeCoordinator {

    fun resolve(
        workflowStatus: WorkflowStatus,
        composeMode: ArtifactComposeActionMode,
        lifecycleState: SpecDetailClarificationLifecycleState,
    ): SpecDetailClarificationModeActionState {
        val isActive = lifecycleState.clarificationState != null
        val clarificationLocked = isActive && lifecycleState.checklistReadOnly
        val inProgress = workflowStatus == WorkflowStatus.IN_PROGRESS
        return SpecDetailClarificationModeActionState(
            isActive = isActive,
            standardActionsVisible = !isActive,
            confirmGenerate = button(
                visible = isActive,
                enabled = isActive && inProgress && !lifecycleState.isGeneratingActive && !clarificationLocked,
                disabledReason = ArtifactComposeActionUiText.clarificationConfirmDisabledReason(
                    mode = composeMode,
                    status = workflowStatus,
                    isGeneratingActive = lifecycleState.isGeneratingActive,
                    clarificationLocked = clarificationLocked,
                ),
            ),
            regenerateClarification = button(
                visible = isActive,
                enabled = isActive && inProgress && !lifecycleState.isGeneratingActive && !clarificationLocked,
            ),
            skipClarification = button(
                visible = isActive,
                enabled = isActive && inProgress && !lifecycleState.isGeneratingActive && !clarificationLocked,
            ),
            cancelClarification = button(
                visible = isActive,
                enabled = isActive && !lifecycleState.isGeneratingActive && !clarificationLocked,
            ),
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
}
