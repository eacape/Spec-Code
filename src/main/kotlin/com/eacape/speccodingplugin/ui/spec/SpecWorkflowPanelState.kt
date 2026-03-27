package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId

internal enum class DocumentWorkspaceView {
    DOCUMENT,
    STRUCTURED_TASKS,
}

internal class SpecWorkflowPanelState {

    var selectedWorkflowId: String? = null
        set(value) {
            field = normalize(value)
        }

    var highlightedWorkflowId: String? = null
        set(value) {
            field = normalize(value)
        }

    var focusedStage: StageId? = null

    var selectedDocumentWorkspaceView: DocumentWorkspaceView = DocumentWorkspaceView.DOCUMENT

    var selectedStructuredTaskId: String? = null
        set(value) {
            field = normalize(value)
        }

    var pendingOpenWorkflowRequest: SpecToolWindowOpenRequest? = null
        private set

    fun selectWorkflow(workflowId: String): Boolean {
        val normalizedWorkflowId = normalize(workflowId) ?: return false
        val changed = selectedWorkflowId != normalizedWorkflowId
        selectedWorkflowId = normalizedWorkflowId
        if (changed) {
            focusedStage = null
            selectedDocumentWorkspaceView = DocumentWorkspaceView.DOCUMENT
            selectedStructuredTaskId = null
        }
        return changed
    }

    fun highlightWorkflow(workflowId: String?) {
        highlightedWorkflowId = workflowId
    }

    fun clearOpenedWorkflow(resetHighlight: Boolean) {
        selectedWorkflowId = null
        focusedStage = null
        selectedDocumentWorkspaceView = DocumentWorkspaceView.DOCUMENT
        selectedStructuredTaskId = null
        if (resetHighlight) {
            highlightedWorkflowId = null
        }
    }

    fun rememberPendingOpenRequest(request: SpecToolWindowOpenRequest): SpecToolWindowOpenRequest {
        val normalizedRequest = request.copy(
            workflowId = normalize(request.workflowId).orEmpty(),
            taskId = normalize(request.taskId),
        )
        pendingOpenWorkflowRequest = normalizedRequest
        return normalizedRequest
    }

    fun clearPendingOpenRequest() {
        pendingOpenWorkflowRequest = null
    }

    fun dropPendingOpenRequestIfInvalid(validWorkflowIds: Set<String>) {
        if (pendingOpenWorkflowRequest?.workflowId?.let { workflowId -> workflowId !in validWorkflowIds } == true) {
            pendingOpenWorkflowRequest = null
        }
    }

    private fun normalize(value: String?): String? = value?.trim()?.ifBlank { null }
}
