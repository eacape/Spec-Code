package com.eacape.speccodingplugin.ui.spec

import java.awt.Component

internal data class SpecWorkflowSwitcherPopupRequest(
    val items: Collection<SpecWorkflowListPanel.WorkflowListItem>,
    val initialSelectionWorkflowId: String?,
    val onOpenWorkflow: (String) -> Unit,
    val onEditWorkflow: (String) -> Unit,
    val onDeleteWorkflow: (String) -> Unit,
)

internal interface SpecWorkflowSwitcherPopupController {
    fun cancel()

    fun isVisibleForTest(): Boolean = false

    fun visibleWorkflowIdsForTest(): List<String> = emptyList()

    fun applySearchForTest(query: String) {}

    fun confirmSelectionForTest() {}

    fun selectedWorkflowIdForTest(): String? = null
}

private class SpecWorkflowSwitcherPopupControllerAdapter(
    private val popup: SpecWorkflowSwitcherPopup,
) : SpecWorkflowSwitcherPopupController {
    override fun cancel() {
        popup.cancel()
    }

    override fun isVisibleForTest(): Boolean = popup.isVisibleForTest()

    override fun visibleWorkflowIdsForTest(): List<String> = popup.visibleWorkflowIdsForTest()

    override fun applySearchForTest(query: String) {
        popup.applySearchForTest(query)
    }

    override fun confirmSelectionForTest() {
        popup.confirmSelectionForTest()
    }

    override fun selectedWorkflowIdForTest(): String? = popup.selectedWorkflowIdForTest()
}

internal class SpecWorkflowSwitcherUiHost(
    private val showPopupUi: (SpecWorkflowSwitcherPopupRequest) -> SpecWorkflowSwitcherPopupController,
) {
    constructor(owner: Component) : this(
        showPopupUi = { request ->
            val popup = SpecWorkflowSwitcherPopup(
                items = request.items,
                initialSelectionWorkflowId = request.initialSelectionWorkflowId,
                onOpenWorkflow = request.onOpenWorkflow,
                onEditWorkflow = request.onEditWorkflow,
                onDeleteWorkflow = request.onDeleteWorkflow,
            )
            popup.showUnderneathOf(owner)
            SpecWorkflowSwitcherPopupControllerAdapter(popup)
        },
    )

    private var popupController: SpecWorkflowSwitcherPopupController? = null

    fun show(request: SpecWorkflowSwitcherPopupRequest) {
        cancel()
        popupController = showPopupUi(
            request.copy(
                onOpenWorkflow = { workflowId ->
                    popupController = null
                    request.onOpenWorkflow(workflowId)
                },
                onEditWorkflow = { workflowId ->
                    popupController = null
                    request.onEditWorkflow(workflowId)
                },
                onDeleteWorkflow = { workflowId ->
                    popupController = null
                    request.onDeleteWorkflow(workflowId)
                },
            ),
        )
    }

    fun cancel() {
        popupController?.cancel()
        popupController = null
    }

    internal fun isVisibleForTest(): Boolean = popupController != null

    internal fun visibleWorkflowIdsForTest(): List<String> {
        return popupController?.visibleWorkflowIdsForTest().orEmpty()
    }

    internal fun applySearchForTest(query: String) {
        popupController?.applySearchForTest(query)
    }

    internal fun confirmSelectionForTest() {
        popupController?.confirmSelectionForTest()
    }

    internal fun selectedWorkflowIdForTest(): String? = popupController?.selectedWorkflowIdForTest()
}
