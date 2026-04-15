package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus

internal data class SpecWorkflowToolbarActionAvailability(
    val createWorktreeEnabled: Boolean = false,
    val mergeWorktreeEnabled: Boolean = false,
    val deltaEnabled: Boolean = false,
    val archiveEnabled: Boolean = false,
)

internal object SpecWorkflowToolbarActionAvailabilityBuilder {

    fun build(workflow: SpecWorkflow?): SpecWorkflowToolbarActionAvailability {
        if (workflow == null) {
            return empty()
        }
        return SpecWorkflowToolbarActionAvailability(
            createWorktreeEnabled = true,
            mergeWorktreeEnabled = true,
            deltaEnabled = true,
            archiveEnabled = workflow.status == WorkflowStatus.COMPLETED,
        )
    }

    fun empty(): SpecWorkflowToolbarActionAvailability = SpecWorkflowToolbarActionAvailability()
}
