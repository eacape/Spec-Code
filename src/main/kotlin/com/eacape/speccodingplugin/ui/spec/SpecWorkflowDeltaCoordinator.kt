package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDeltaExportFormat
import com.eacape.speccodingplugin.spec.SpecDeltaExportResult
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.SpecWorkflowDelta

internal data class SpecWorkflowDeltaBaselineSelectionResult(
    val confirmed: Boolean,
    val baselineWorkflowId: String? = null,
)

internal data class SpecWorkflowDeltaDialogRequest(
    val targetWorkflow: SpecWorkflow,
    val delta: SpecWorkflowDelta,
    val onOpenHistoryDiff: (SpecPhase) -> Unit,
    val onExportReport: (SpecDeltaExportFormat) -> Result<SpecDeltaExportResult>,
    val onReportExported: (SpecDeltaExportResult) -> Unit,
)

internal class SpecWorkflowDeltaCoordinator(
    private val listWorkflowIds: () -> List<String>,
    private val loadWorkflow: (String) -> Result<SpecWorkflow>,
    private val compareByWorkflowId: (baselineWorkflowId: String, targetWorkflowId: String) -> Result<SpecWorkflowDelta>,
    private val runIo: (task: () -> Unit) -> Unit,
    private val invokeLater: (action: () -> Unit) -> Unit,
    private val selectBaselineWorkflow: (
        currentWorkflowId: String,
        workflowOptions: List<SpecBaselineSelectDialog.WorkflowOption>,
    ) -> SpecWorkflowDeltaBaselineSelectionResult,
    private val showDeltaDialog: (SpecWorkflowDeltaDialogRequest) -> Unit,
    private val showHistoryDiff: (workflowId: String, phase: SpecPhase, currentDocument: SpecDocument?) -> Unit,
    private val exportReport: (SpecWorkflowDelta, SpecDeltaExportFormat) -> Result<SpecDeltaExportResult>,
    private val setStatusText: (String) -> Unit,
    private val renderFailureMessage: (Throwable) -> String,
) {

    fun show(targetWorkflow: SpecWorkflow?) {
        val activeWorkflow = targetWorkflow ?: run {
            setStatusText(SpecCodingBundle.message("spec.delta.error.noCurrentWorkflow"))
            return
        }

        runIo {
            val workflowOptions = listWorkflowIds()
                .filter { workflowId -> workflowId != activeWorkflow.id }
                .mapNotNull { workflowId ->
                    loadWorkflow(workflowId).getOrNull()?.let { workflow ->
                        SpecBaselineSelectDialog.WorkflowOption(
                            workflowId = workflow.id,
                            title = workflow.title.ifBlank { workflow.id },
                            description = workflow.description,
                        )
                    }
                }

            if (workflowOptions.isEmpty()) {
                invokeLater {
                    setStatusText(SpecCodingBundle.message("spec.delta.emptyCandidates"))
                }
                return@runIo
            }

            invokeLater {
                val selection = selectBaselineWorkflow(activeWorkflow.id, workflowOptions)
                if (!selection.confirmed) {
                    return@invokeLater
                }

                val baselineWorkflowId = selection.baselineWorkflowId
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                if (baselineWorkflowId == null) {
                    setStatusText(SpecCodingBundle.message("spec.delta.selectBaseline.required"))
                    return@invokeLater
                }

                runIo {
                    val result = compareByWorkflowId(baselineWorkflowId, activeWorkflow.id)
                    invokeLater {
                        result.onSuccess { delta ->
                            showComparisonResult(activeWorkflow, delta)
                            setStatusText(SpecCodingBundle.message("spec.delta.generated"))
                        }.onFailure { error ->
                            setStatusText(
                                SpecCodingBundle.message(
                                    "spec.workflow.error",
                                    renderFailureMessage(error),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun showComparisonResult(targetWorkflow: SpecWorkflow, delta: SpecWorkflowDelta) {
        showDeltaDialog(
            SpecWorkflowDeltaDialogRequest(
                targetWorkflow = targetWorkflow,
                delta = delta,
                onOpenHistoryDiff = { phase ->
                    showHistoryDiff(
                        targetWorkflow.id,
                        phase,
                        targetWorkflow.documents[phase],
                    )
                },
                onExportReport = { format ->
                    exportReport(delta, format)
                },
                onReportExported = { export ->
                    setStatusText(SpecCodingBundle.message("spec.delta.export.done", export.fileName))
                },
            ),
        )
    }
}
