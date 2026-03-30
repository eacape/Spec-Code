package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.toStageId

internal data class SpecDetailPanelViewState(
    val artifactOnlyView: Boolean,
    val displayedDocumentPhase: SpecPhase?,
    val editablePhase: SpecPhase?,
    val revisionLockedPhase: SpecPhase?,
    val selectedDocumentAvailable: Boolean,
    val artifactOpenAvailable: Boolean,
) {
    val editRequiresExplicitRevisionStart: Boolean
        get() = revisionLockedPhase != null

    companion object {
        fun resolve(
            workflow: SpecWorkflow,
            selectedPhase: SpecPhase?,
            preferredWorkbenchPhase: SpecPhase?,
            explicitRevisionPhase: SpecPhase?,
            workbenchArtifactBinding: SpecWorkflowStageArtifactBinding?,
        ): SpecDetailPanelViewState {
            val artifactOnlyView = isArtifactOnlyView(workbenchArtifactBinding)
            val displayedDocumentPhase = if (artifactOnlyView) {
                null
            } else {
                selectedPhase ?: preferredWorkbenchPhase ?: workflow.currentPhase
            }
            val editablePhase = if (artifactOnlyView) {
                null
            } else {
                selectedPhase ?: preferredWorkbenchPhase ?: workflow.currentPhase
            }
            val revisionLockedPhase = displayedDocumentPhase?.takeIf { phase ->
                requiresExplicitRevisionEntry(workflow, phase) && explicitRevisionPhase != phase
            }

            return SpecDetailPanelViewState(
                artifactOnlyView = artifactOnlyView,
                displayedDocumentPhase = displayedDocumentPhase,
                editablePhase = editablePhase,
                revisionLockedPhase = revisionLockedPhase,
                selectedDocumentAvailable = selectedPhase?.let(workflow.documents::containsKey) == true,
                artifactOpenAvailable = artifactOnlyView && workbenchArtifactBinding?.available == true,
            )
        }

        fun isArtifactOnlyView(binding: SpecWorkflowStageArtifactBinding?): Boolean {
            return binding?.documentPhase == null && !binding?.fileName.isNullOrBlank()
        }

        private fun requiresExplicitRevisionEntry(workflow: SpecWorkflow, phase: SpecPhase): Boolean {
            if (phase != SpecPhase.SPECIFY && phase != SpecPhase.DESIGN) {
                return false
            }
            return stageProgressForPhase(workflow, phase) == StageProgress.DONE
        }

        private fun stageProgressForPhase(workflow: SpecWorkflow, phase: SpecPhase): StageProgress {
            return workflow.stageStates[phase.toStageId()]?.status ?: when {
                phase == workflow.currentPhase -> StageProgress.IN_PROGRESS
                workflow.currentPhase.ordinal > phase.ordinal -> StageProgress.DONE
                else -> StageProgress.NOT_STARTED
            }
        }
    }
}
