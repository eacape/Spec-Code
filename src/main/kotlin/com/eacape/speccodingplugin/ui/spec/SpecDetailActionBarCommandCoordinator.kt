package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase

internal sealed interface SpecDetailActionBarGeneratePlan {
    data class Submit(
        val input: String,
    ) : SpecDetailActionBarGeneratePlan

    data class InputRequired(
        val phase: SpecPhase?,
    ) : SpecDetailActionBarGeneratePlan
}

internal sealed interface SpecDetailActionBarOpenEditorPlan {
    data object Ignore : SpecDetailActionBarOpenEditorPlan

    data class PhaseDocument(
        val phase: SpecPhase,
    ) : SpecDetailActionBarOpenEditorPlan

    data class ArtifactFile(
        val fileName: String,
    ) : SpecDetailActionBarOpenEditorPlan
}

internal sealed interface SpecDetailActionBarEditPlan {
    data object Ignore : SpecDetailActionBarEditPlan

    data class StartEditing(
        val explicitRevisionPhase: SpecPhase?,
    ) : SpecDetailActionBarEditPlan
}

internal object SpecDetailActionBarCommandCoordinator {

    fun resolveGenerate(
        input: String,
        phase: SpecPhase?,
        canReuseLastInput: Boolean,
    ): SpecDetailActionBarGeneratePlan {
        val normalized = input.trim()
        val allowBlank = phase == SpecPhase.DESIGN || phase == SpecPhase.IMPLEMENT
        return if (normalized.isNotBlank() || allowBlank || canReuseLastInput) {
            SpecDetailActionBarGeneratePlan.Submit(normalized)
        } else {
            SpecDetailActionBarGeneratePlan.InputRequired(phase)
        }
    }

    fun resolveOpenEditor(
        selectedPhase: SpecPhase?,
        artifactFileName: String?,
    ): SpecDetailActionBarOpenEditorPlan {
        return when {
            selectedPhase != null -> SpecDetailActionBarOpenEditorPlan.PhaseDocument(selectedPhase)
            artifactFileName != null -> SpecDetailActionBarOpenEditorPlan.ArtifactFile(artifactFileName)
            else -> SpecDetailActionBarOpenEditorPlan.Ignore
        }
    }

    fun resolveEdit(viewState: SpecDetailPanelViewState?): SpecDetailActionBarEditPlan {
        val state = viewState ?: return SpecDetailActionBarEditPlan.Ignore
        val editablePhase = state.editablePhase ?: return SpecDetailActionBarEditPlan.Ignore
        return SpecDetailActionBarEditPlan.StartEditing(
            explicitRevisionPhase = editablePhase.takeIf { state.revisionLockedPhase == editablePhase },
        )
    }
}
