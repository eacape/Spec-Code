package com.eacape.speccodingplugin.ui.spec

internal class SpecWorkflowListActionCoordinator(
    private val currentItems: () -> List<SpecWorkflowListPanel.WorkflowListItem>,
    private val selectedWorkflowId: () -> String?,
    private val highlightedWorkflowId: () -> String?,
    private val showWorkflowSwitcher: (SpecWorkflowSwitcherPopupRequest) -> Unit,
    private val cancelWorkflowSwitcher: () -> Unit,
    private val selectionCoordinator: SpecWorkflowSelectionCoordinator,
    private val openWorkflow: (String) -> Unit,
    private val editWorkflow: (String) -> Unit,
    private val deleteWorkflow: (String) -> Result<Unit>,
    private val launchDeleteInBackground: (task: () -> Unit) -> Unit,
    private val invokeLater: (action: () -> Unit) -> Unit,
    private val onDeleteSuccess: (deletedWorkflowId: String, refreshTarget: SpecWorkflowSelectionRefreshTarget) -> Unit,
    private val onDeleteFailure: (Throwable) -> Unit,
) {

    fun requestSwitch() {
        val items = currentItems()
        if (items.isEmpty()) {
            return
        }
        cancelWorkflowSwitcher()
        showWorkflowSwitcher(
            SpecWorkflowSwitcherPopupRequest(
                items = items,
                initialSelectionWorkflowId = normalize(selectedWorkflowId())
                    ?: normalize(highlightedWorkflowId()),
                onOpenWorkflow = { workflowId -> openWorkflow(workflowId) },
                onEditWorkflow = { workflowId -> editWorkflow(workflowId) },
                onDeleteWorkflow = ::requestDelete,
            ),
        )
    }

    fun requestDelete(workflowId: String) {
        val normalizedWorkflowId = normalize(workflowId) ?: return
        val refreshTarget = selectionCoordinator.resolveDeleteRefreshTarget(
            workflowId = normalizedWorkflowId,
            currentItems = currentItems(),
            selectedWorkflowId = selectedWorkflowId(),
        )
        cancelWorkflowSwitcher()
        launchDeleteInBackground {
            deleteWorkflow(normalizedWorkflowId)
                .onSuccess {
                    invokeLater {
                        onDeleteSuccess(normalizedWorkflowId, refreshTarget)
                    }
                }
                .onFailure { error ->
                    invokeLater {
                        onDeleteFailure(error)
                    }
                }
        }
    }

    private fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
