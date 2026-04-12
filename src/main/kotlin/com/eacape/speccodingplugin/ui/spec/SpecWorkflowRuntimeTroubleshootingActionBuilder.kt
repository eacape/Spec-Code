package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessSnapshot

internal class SpecWorkflowRuntimeTroubleshootingActionBuilder(
    private val readinessSnapshot: () -> LocalEnvironmentReadinessSnapshot,
    private val trackingSnapshot: () -> SpecWorkflowFirstRunTrackingSnapshot,
    private val resolveTemplate: (workflowId: String) -> WorkflowTemplate,
) {

    fun build(
        workflowId: String,
        trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
    ): List<SpecWorkflowTroubleshootingAction> {
        val normalizedWorkflowId = workflowId.trim().ifBlank { return emptyList() }
        return SpecWorkflowRuntimeTroubleshootingCoordinator.build(
            trigger = trigger,
            readiness = readinessSnapshot(),
            tracking = trackingSnapshot(),
            template = resolveTemplate(normalizedWorkflowId),
        )
    }
}
