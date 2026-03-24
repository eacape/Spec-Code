package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowStatus

internal enum class SpecWorkflowWorkspaceSectionId {
    OVERVIEW,
    TASKS,
    GATE,
    VERIFY,
    DOCUMENTS,
}

internal object SpecWorkflowWorkspaceLayout {
    fun visibleSections(
        currentStage: StageId,
        status: WorkflowStatus,
    ): Set<SpecWorkflowWorkspaceSectionId> {
        val visible = linkedSetOf(
            SpecWorkflowWorkspaceSectionId.OVERVIEW,
            SpecWorkflowWorkspaceSectionId.TASKS,
            SpecWorkflowWorkspaceSectionId.DOCUMENTS,
        )

        if (status != WorkflowStatus.COMPLETED && currentStage != StageId.ARCHIVE) {
            visible += SpecWorkflowWorkspaceSectionId.GATE
        }
        if (status == WorkflowStatus.COMPLETED || currentStage in setOf(StageId.IMPLEMENT, StageId.VERIFY, StageId.ARCHIVE)) {
            visible += SpecWorkflowWorkspaceSectionId.VERIFY
        }

        return visible
    }

    fun defaultExpandedSections(
        currentStage: StageId,
        status: WorkflowStatus,
    ): Set<SpecWorkflowWorkspaceSectionId> {
        if (status == WorkflowStatus.COMPLETED || currentStage == StageId.ARCHIVE) {
            return linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.VERIFY,
            )
        }

        return when (currentStage) {
            StageId.REQUIREMENTS,
            StageId.DESIGN,
            -> linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            )

            StageId.TASKS,
            StageId.IMPLEMENT,
            -> linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.TASKS,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            )

            StageId.VERIFY -> linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.VERIFY,
            )

            StageId.ARCHIVE -> linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.VERIFY,
            )
        }
    }
}
