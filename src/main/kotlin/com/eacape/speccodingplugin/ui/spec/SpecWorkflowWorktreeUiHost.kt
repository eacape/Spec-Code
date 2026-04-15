package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.ui.worktree.NewWorktreeDialog

internal data class SpecWorkflowWorktreeCreateDialogRequest(
    val workflowId: String,
    val workflowTitle: String,
    val suggestedBaseBranch: String,
)

internal class SpecWorkflowWorktreeUiHost(
    private val currentWorkflow: () -> SpecWorkflow?,
    private val resolveSuggestedBaseBranch: () -> String,
    private val showCreateDialogUi: (SpecWorkflowWorktreeCreateDialogRequest) -> SpecWorkflowWorktreeCreateRequest?,
    private val createWorktree: (SpecWorkflowWorktreeCreateRequest) -> Unit,
    private val mergeWorktree: (SpecWorkflowWorktreeMergeRequest) -> Unit,
    private val setStatusText: (String) -> Unit,
) {

    constructor(
        currentWorkflow: () -> SpecWorkflow?,
        suggestBaseBranch: () -> String,
        createWorktree: (SpecWorkflowWorktreeCreateRequest) -> Unit,
        mergeWorktree: (SpecWorkflowWorktreeMergeRequest) -> Unit,
        setStatusText: (String) -> Unit,
    ) : this(
        currentWorkflow = currentWorkflow,
        resolveSuggestedBaseBranch = suggestBaseBranch,
        showCreateDialogUi = { request ->
            val dialog = NewWorktreeDialog(
                specTaskId = request.workflowId,
                specTitle = request.workflowTitle,
                baseBranch = request.suggestedBaseBranch,
            )
            if (!dialog.showAndGet()) {
                null
            } else {
                dialog.resultShortName?.let { shortName ->
                    SpecWorkflowWorktreeCreateRequest(
                        specTaskId = dialog.resultSpecTaskId ?: request.workflowId,
                        shortName = shortName,
                        baseBranch = dialog.resultBaseBranch ?: request.suggestedBaseBranch,
                    )
                }
            }
        },
        createWorktree = createWorktree,
        mergeWorktree = mergeWorktree,
        setStatusText = setStatusText,
    )

    fun requestCreate() {
        val workflow = currentWorkflow() ?: run {
            showWorkflowSelectionRequired()
            return
        }
        val dialogRequest = SpecWorkflowWorktreeCreateDialogRequest(
            workflowId = workflow.id,
            workflowTitle = workflow.title.ifBlank { workflow.id },
            suggestedBaseBranch = suggestedBaseBranch(),
        )
        showCreateDialogUi(dialogRequest)?.let(createWorktree)
    }

    fun requestMerge() {
        val workflow = currentWorkflow() ?: run {
            showWorkflowSelectionRequired()
            return
        }
        mergeWorktree(
            SpecWorkflowWorktreeMergeRequest(
                workflowId = workflow.id,
            ),
        )
    }

    private fun suggestedBaseBranch(): String {
        return runCatching {
            resolveSuggestedBaseBranch()
        }.getOrDefault("main")
    }

    private fun showWorkflowSelectionRequired() {
        setStatusText(SpecCodingBundle.message("spec.workflow.worktree.selectFirst"))
    }
}
