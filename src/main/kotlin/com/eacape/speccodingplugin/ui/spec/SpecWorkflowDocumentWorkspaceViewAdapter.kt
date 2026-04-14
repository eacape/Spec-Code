package com.eacape.speccodingplugin.ui.spec

internal data class SpecWorkflowDocumentWorkspaceViewPresentation(
    val supportsStructuredTasksView: Boolean,
    val effectiveView: DocumentWorkspaceView,
    val shouldSyncStructuredTaskSelection: Boolean,
)

internal interface SpecWorkflowDocumentWorkspaceViewUi {
    fun setDocumentWorkspaceTabsVisible(visible: Boolean)

    fun showDocumentWorkspaceCard(view: DocumentWorkspaceView)

    fun refreshDocumentWorkspaceViewButtons()

    fun syncStructuredTaskSelection(taskId: String?)

    fun refreshDocumentWorkspaceContainers()
}

internal class SpecWorkflowDocumentWorkspaceViewAdapter(
    private val ui: SpecWorkflowDocumentWorkspaceViewUi,
    private val selectedView: () -> DocumentWorkspaceView,
    private val selectedStructuredTaskId: () -> String?,
    private val supportsStructuredTasksDocumentWorkspaceView: (SpecWorkflowStageWorkbenchState?) -> Boolean,
) {
    fun resolvePresentation(
        workbenchState: SpecWorkflowStageWorkbenchState?,
    ): SpecWorkflowDocumentWorkspaceViewPresentation {
        val supportsStructuredTasksView = supportsStructuredTasksDocumentWorkspaceView(workbenchState)
        val effectiveView = if (supportsStructuredTasksView) {
            selectedView()
        } else {
            DocumentWorkspaceView.DOCUMENT
        }
        return SpecWorkflowDocumentWorkspaceViewPresentation(
            supportsStructuredTasksView = supportsStructuredTasksView,
            effectiveView = effectiveView,
            shouldSyncStructuredTaskSelection =
                supportsStructuredTasksView && effectiveView == DocumentWorkspaceView.STRUCTURED_TASKS,
        )
    }

    fun updatePresentation(workbenchState: SpecWorkflowStageWorkbenchState?) {
        val presentation = resolvePresentation(workbenchState)
        ui.setDocumentWorkspaceTabsVisible(presentation.supportsStructuredTasksView)
        ui.showDocumentWorkspaceCard(presentation.effectiveView)
        ui.refreshDocumentWorkspaceViewButtons()
        if (presentation.shouldSyncStructuredTaskSelection) {
            ui.syncStructuredTaskSelection(selectedStructuredTaskId())
        }
        ui.refreshDocumentWorkspaceContainers()
    }
}
