package com.eacape.speccodingplugin.ui.spec

internal data class SpecWorkflowLoadTrigger(
    val workflowId: String,
    val includeSources: Boolean,
    val followCurrentPhase: Boolean = false,
    val previousSelectedWorkflowId: String? = null,
)

internal data class SpecWorkflowOpenRequestDecision(
    val normalizedRequest: SpecToolWindowOpenRequest?,
    val shouldApplyToCurrentWorkflow: Boolean,
    val refreshWorkflowId: String?,
)

internal class SpecWorkflowNavigationCoordinator {

    fun buildSelectionLoadTrigger(
        workflowId: String,
        selectedWorkflowId: String?,
    ): SpecWorkflowLoadTrigger? {
        val normalizedWorkflowId = normalize(workflowId) ?: return null
        return SpecWorkflowLoadTrigger(
            workflowId = normalizedWorkflowId,
            includeSources = true,
            previousSelectedWorkflowId = normalize(selectedWorkflowId),
        )
    }

    fun buildReloadLoadTrigger(
        selectedWorkflowId: String?,
        followCurrentPhase: Boolean,
    ): SpecWorkflowLoadTrigger? {
        val normalizedWorkflowId = normalize(selectedWorkflowId) ?: return null
        return SpecWorkflowLoadTrigger(
            workflowId = normalizedWorkflowId,
            includeSources = false,
            followCurrentPhase = followCurrentPhase,
        )
    }

    fun resolveOpenRequest(
        request: SpecToolWindowOpenRequest,
        selectedWorkflowId: String?,
        currentWorkflowId: String?,
    ): SpecWorkflowOpenRequestDecision {
        val normalizedWorkflowId = normalize(request.workflowId)
            ?: return SpecWorkflowOpenRequestDecision(
                normalizedRequest = null,
                shouldApplyToCurrentWorkflow = false,
                refreshWorkflowId = null,
            )
        val normalizedRequest = request.copy(
            workflowId = normalizedWorkflowId,
            taskId = normalize(request.taskId),
        )
        val shouldApplyToCurrentWorkflow =
            normalize(selectedWorkflowId) == normalizedWorkflowId &&
                normalize(currentWorkflowId) == normalizedWorkflowId
        return SpecWorkflowOpenRequestDecision(
            normalizedRequest = normalizedRequest,
            shouldApplyToCurrentWorkflow = shouldApplyToCurrentWorkflow,
            refreshWorkflowId = normalizedWorkflowId,
        )
    }

    private fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
