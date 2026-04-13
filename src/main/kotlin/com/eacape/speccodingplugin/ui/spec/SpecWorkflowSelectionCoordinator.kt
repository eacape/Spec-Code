package com.eacape.speccodingplugin.ui.spec

internal data class SpecWorkflowSelectionRefreshRequest(
    val items: List<SpecWorkflowListPanel.WorkflowListItem>,
    val selectWorkflowId: String? = null,
    val selectedWorkflowId: String? = null,
    val highlightedWorkflowId: String? = null,
    val preserveListMode: Boolean = false,
)

internal data class SpecWorkflowSelectionRefreshDecision(
    val validWorkflowIds: Set<String>,
    val targetOpenedWorkflowId: String?,
    val targetHighlightedWorkflowId: String?,
) {
    val switchWorkflowEnabled: Boolean = validWorkflowIds.isNotEmpty()
    val shouldClearOpenedWorkflow: Boolean = targetOpenedWorkflowId == null
    val resetHighlightOnClear: Boolean = targetHighlightedWorkflowId == null
}

internal data class SpecWorkflowSelectionRefreshTarget(
    val selectWorkflowId: String?,
    val preserveListMode: Boolean,
)

internal interface SpecWorkflowSelectionCallbacks {
    fun highlightWorkflow(workflowId: String?)

    fun clearOpenedWorkflowUi(resetHighlight: Boolean)

    fun loadWorkflow(workflowId: String)

    fun publishWorkflowSelection(workflowId: String)
}

internal class SpecWorkflowSelectionCoordinator {

    fun resolveRefresh(request: SpecWorkflowSelectionRefreshRequest): SpecWorkflowSelectionRefreshDecision {
        val workflowIds = request.items.asSequence()
            .mapNotNull { item -> normalize(item.workflowId) }
            .toCollection(linkedSetOf())
        val validHighlightedWorkflowId = normalize(request.highlightedWorkflowId)
            ?.takeIf(workflowIds::contains)
        val targetOpenedWorkflowId = normalize(request.selectWorkflowId)
            ?.takeIf(workflowIds::contains)
            ?: normalize(request.selectedWorkflowId)?.takeIf(workflowIds::contains)
            ?: workflowIds.firstOrNull()?.takeIf {
                !request.preserveListMode && validHighlightedWorkflowId == null
            }
        val targetHighlightedWorkflowId = targetOpenedWorkflowId
            ?: if (request.preserveListMode) {
                validHighlightedWorkflowId ?: workflowIds.firstOrNull()
            } else {
                validHighlightedWorkflowId
            }
        return SpecWorkflowSelectionRefreshDecision(
            validWorkflowIds = workflowIds,
            targetOpenedWorkflowId = targetOpenedWorkflowId,
            targetHighlightedWorkflowId = targetHighlightedWorkflowId,
        )
    }

    fun resolveDeleteRefreshTarget(
        workflowId: String,
        currentItems: List<SpecWorkflowListPanel.WorkflowListItem>,
        selectedWorkflowId: String?,
    ): SpecWorkflowSelectionRefreshTarget {
        val deletedWorkflowId = normalize(workflowId)
            ?: return SpecWorkflowSelectionRefreshTarget(selectWorkflowId = null, preserveListMode = true)
        val remainingWorkflowIds = currentItems.asSequence()
            .mapNotNull { item -> normalize(item.workflowId) }
            .filterNot { candidate -> candidate == deletedWorkflowId }
            .toList()
        val remainingWorkflowIdSet = remainingWorkflowIds.toSet()
        val normalizedSelectedWorkflowId = normalize(selectedWorkflowId)
        val preservedSelectedWorkflowId = normalizedSelectedWorkflowId
            ?.takeIf { candidate -> candidate != deletedWorkflowId && candidate in remainingWorkflowIdSet }
        if (preservedSelectedWorkflowId != null) {
            return SpecWorkflowSelectionRefreshTarget(
                selectWorkflowId = preservedSelectedWorkflowId,
                preserveListMode = false,
            )
        }
        if (normalizedSelectedWorkflowId == deletedWorkflowId) {
            return SpecWorkflowSelectionRefreshTarget(
                selectWorkflowId = remainingWorkflowIds.firstOrNull(),
                preserveListMode = false,
            )
        }
        return SpecWorkflowSelectionRefreshTarget(selectWorkflowId = null, preserveListMode = true)
    }

    fun focus(workflowId: String, selectedWorkflowId: String?, callbacks: SpecWorkflowSelectionCallbacks) {
        val normalizedWorkflowId = normalize(workflowId) ?: return
        callbacks.highlightWorkflow(normalizedWorkflowId)
        val normalizedSelectedWorkflowId = normalize(selectedWorkflowId)
        if (normalizedSelectedWorkflowId != null && normalizedSelectedWorkflowId != normalizedWorkflowId) {
            callbacks.clearOpenedWorkflowUi(resetHighlight = false)
        }
    }

    fun open(workflowId: String, callbacks: SpecWorkflowSelectionCallbacks) {
        val normalizedWorkflowId = normalize(workflowId) ?: return
        callbacks.highlightWorkflow(normalizedWorkflowId)
        callbacks.loadWorkflow(normalizedWorkflowId)
        callbacks.publishWorkflowSelection(normalizedWorkflowId)
    }

    fun backToList(callbacks: SpecWorkflowSelectionCallbacks) {
        callbacks.clearOpenedWorkflowUi(resetHighlight = false)
    }

    private fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
