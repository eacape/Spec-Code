package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecRequirementsQuickFixResult
import com.eacape.speccodingplugin.spec.SpecRequirementsQuickFixService
import com.eacape.speccodingplugin.spec.SpecTasksQuickFixResult
import com.eacape.speccodingplugin.spec.SpecTasksQuickFixService
import java.nio.file.Path

internal data class SpecWorkflowGateArtifactRepairBackgroundRequest<T>(
    val title: String,
    val task: () -> T,
    val onSuccess: (T) -> Unit,
)

internal interface SpecWorkflowGateArtifactRepairBackgroundRunner {
    fun <T> run(request: SpecWorkflowGateArtifactRepairBackgroundRequest<T>)
}

internal class SpecWorkflowGateArtifactRepairCoordinator(
    private val backgroundRunner: SpecWorkflowGateArtifactRepairBackgroundRunner,
    private val runTasksRepair: (workflowId: String, trigger: String) -> SpecTasksQuickFixResult,
    private val runRequirementsRepair: (workflowId: String, trigger: String) -> SpecRequirementsQuickFixResult,
    private val rememberWorkflow: (String) -> Unit,
    private val showInfo: (title: String, message: String) -> Unit,
    private val notifySuccess: (String) -> Unit,
    private val openFile: (path: Path, line: Int?) -> Unit,
    private val refreshWorkflows: (String) -> Unit,
) {

    fun repairTasksArtifact(workflowId: String) {
        backgroundRunner.run(
            SpecWorkflowGateArtifactRepairBackgroundRequest(
                title = SpecCodingBundle.message("spec.action.editor.fixTasks.progress"),
                task = {
                    runTasksRepair(
                        workflowId,
                        SpecTasksQuickFixService.TRIGGER_GATE_QUICK_FIX,
                    )
                },
                onSuccess = ::handleTasksRepairSuccess,
            ),
        )
    }

    fun repairRequirementsArtifact(workflowId: String) {
        backgroundRunner.run(
            SpecWorkflowGateArtifactRepairBackgroundRequest(
                title = SpecCodingBundle.message("spec.action.editor.fixRequirements.progress"),
                task = {
                    runRequirementsRepair(
                        workflowId,
                        SpecRequirementsQuickFixService.TRIGGER_GATE_QUICK_FIX,
                    )
                },
                onSuccess = ::handleRequirementsRepairSuccess,
            ),
        )
    }

    private fun handleTasksRepairSuccess(result: SpecTasksQuickFixResult) {
        rememberWorkflow(result.workflowId)
        when {
            !result.changed -> {
                showInfo(
                    SpecCodingBundle.message("spec.action.editor.fixTasks.none.title"),
                    SpecCodingBundle.message("spec.action.editor.fixTasks.none.message"),
                )
            }

            result.issuesAfter.isNotEmpty() -> {
                val firstIssue = result.issuesAfter.first()
                showInfo(
                    SpecCodingBundle.message("spec.action.editor.fixTasks.partial.title"),
                    SpecCodingBundle.message(
                        "spec.action.editor.fixTasks.partial.message",
                        result.issuesAfter.size,
                        firstIssue.line,
                        firstIssue.message,
                    ),
                )
                openFile(result.tasksDocumentPath, firstIssue.line)
            }

            else -> {
                notifySuccess(
                    SpecCodingBundle.message(
                        "spec.action.editor.fixTasks.success.message",
                        result.issuesBefore.size,
                    ),
                )
                openFile(result.tasksDocumentPath, null)
            }
        }
        refreshWorkflows(result.workflowId)
    }

    private fun handleRequirementsRepairSuccess(result: SpecRequirementsQuickFixResult) {
        rememberWorkflow(result.workflowId)
        when {
            result.issuesBefore.isEmpty() -> {
                showInfo(
                    SpecCodingBundle.message("spec.action.editor.fixRequirements.none.title"),
                    SpecCodingBundle.message("spec.action.editor.fixRequirements.none.message"),
                )
            }

            result.issuesAfter.isNotEmpty() -> {
                showInfo(
                    SpecCodingBundle.message("spec.action.editor.fixRequirements.partial.title"),
                    SpecCodingBundle.message(
                        "spec.action.editor.fixRequirements.partial.message",
                        result.issuesAfter.size,
                    ),
                )
                openFile(result.requirementsDocumentPath, 1)
            }

            else -> {
                notifySuccess(
                    SpecCodingBundle.message(
                        "spec.action.editor.fixRequirements.success.message",
                        result.issuesBefore.size,
                    ),
                )
                openFile(result.requirementsDocumentPath, null)
            }
        }
        refreshWorkflows(result.workflowId)
    }
}
