package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessSnapshot

internal enum class SpecWorkflowRuntimeTroubleshootingTrigger {
    TASK_EXECUTION_PRECHECK,
    TASK_EXECUTION_FAILURE,
    GENERATION_PRECHECK,
    GENERATION_FAILURE,
    CLARIFICATION_DRAFT_FAILURE,
    VERIFY_FAILURE,
    WORKTREE_FAILURE,
}

internal object SpecWorkflowRuntimeTroubleshootingCoordinator {
    fun build(
        trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
        readiness: LocalEnvironmentReadinessSnapshot,
        tracking: SpecWorkflowFirstRunTrackingSnapshot,
        template: WorkflowTemplate,
    ): List<SpecWorkflowTroubleshootingAction> {
        val actions = linkedMapOf<String, SpecWorkflowTroubleshootingAction>()
        if (shouldOfferQuickTaskFallback(trigger, readiness, tracking, template)) {
            actions["quickTask"] = SpecWorkflowTroubleshootingAction.SelectEntry(
                entry = SpecWorkflowPrimaryEntry.QUICK_TASK,
                label = SpecCodingBundle.message("spec.dialog.troubleshooting.action.switchToQuickTask"),
            )
        }
        actions["settings"] = SpecWorkflowTroubleshootingAction.OpenSettings(
            label = SpecCodingBundle.message("spec.dialog.troubleshooting.action.openSettings"),
        )
        if (shouldOfferBundledDemo(trigger, readiness, tracking, template)) {
            actions["demo"] = SpecWorkflowTroubleshootingAction.OpenBundledDemo(
                label = SpecCodingBundle.message("spec.dialog.troubleshooting.action.openBundledDemo"),
            )
        }
        return actions.values.toList()
    }

    private fun shouldOfferQuickTaskFallback(
        trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
        readiness: LocalEnvironmentReadinessSnapshot,
        tracking: SpecWorkflowFirstRunTrackingSnapshot,
        template: WorkflowTemplate,
    ): Boolean {
        if (trigger == SpecWorkflowRuntimeTroubleshootingTrigger.WORKTREE_FAILURE) {
            return false
        }
        if (template != WorkflowTemplate.FULL_SPEC) {
            return false
        }
        if (!readiness.quickTaskReady) {
            return false
        }
        if (!readiness.fullSpecReady) {
            return true
        }
        return tracking.createSuccessCount == 0
    }

    private fun shouldOfferBundledDemo(
        trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
        readiness: LocalEnvironmentReadinessSnapshot,
        tracking: SpecWorkflowFirstRunTrackingSnapshot,
        template: WorkflowTemplate,
    ): Boolean {
        if (tracking.createSuccessCount == 0) {
            return true
        }
        if (!readiness.quickTaskReady) {
            return true
        }
        if (template == WorkflowTemplate.FULL_SPEC && !readiness.fullSpecReady) {
            return true
        }
        return false
    }
}
