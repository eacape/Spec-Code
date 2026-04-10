package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDeltaBaselineRef
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.SpecWorkflowDelta
import com.eacape.speccodingplugin.spec.SpecWorkflowSnapshotEntry
import com.eacape.speccodingplugin.spec.StageId
import java.nio.file.Files
import java.nio.file.Path

internal data class SpecWorkflowVerifyDeltaCompareRequest(
    val targetWorkflow: SpecWorkflow,
    val choice: SpecWorkflowDeltaBaselineChoice,
)

internal class SpecWorkflowVerifyDeltaCoordinator(
    private val runVerificationWorkflow: (
        workflowId: String,
        onCompleted: (String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) -> Unit,
    private val locateArtifact: (workflowId: String, stageId: StageId) -> Path,
    private val openFile: (Path) -> Boolean,
    private val runIo: (task: () -> Unit) -> Unit,
    private val invokeLater: (action: () -> Unit) -> Unit,
    private val compareByWorkflowId: (baselineWorkflowId: String, targetWorkflowId: String) -> Result<SpecWorkflowDelta>,
    private val compareByDeltaBaseline: (workflowId: String, baselineId: String) -> Result<SpecWorkflowDelta>,
    private val listWorkflowSnapshots: (workflowId: String) -> List<SpecWorkflowSnapshotEntry>,
    private val pinDeltaBaseline: (
        workflowId: String,
        snapshotId: String,
        label: String,
    ) -> Result<SpecDeltaBaselineRef>,
    private val showDeltaDialog: (SpecWorkflow, SpecWorkflowDelta) -> Unit,
    private val reloadCurrentWorkflow: () -> Unit,
    private val setStatusText: (String) -> Unit,
    private val showFailureStatus: (String, List<SpecWorkflowTroubleshootingAction>) -> Unit,
    private val buildRuntimeTroubleshootingActions: (
        workflowId: String,
        trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
    ) -> List<SpecWorkflowTroubleshootingAction>,
    private val renderFailureMessage: (Throwable) -> String,
) {

    fun runVerification(workflowId: String) {
        val normalizedWorkflowId = workflowId.trim().ifBlank { return }
        runVerificationWorkflow(
            normalizedWorkflowId,
            { summary ->
                reloadCurrentWorkflow()
                setStatusText(summary)
            },
            { error ->
                showFailureStatus(
                    SpecCodingBundle.message(
                        "spec.workflow.error",
                        renderFailureMessage(error),
                    ),
                    buildRuntimeTroubleshootingActions(
                        normalizedWorkflowId,
                        SpecWorkflowRuntimeTroubleshootingTrigger.VERIFY_FAILURE,
                    ),
                )
            },
        )
    }

    fun openVerificationDocument(workflowId: String) {
        val normalizedWorkflowId = workflowId.trim().ifBlank { return }
        val path = locateArtifact(normalizedWorkflowId, StageId.VERIFY)
        if (!Files.exists(path) || !openFile(path)) {
            setStatusText(SpecCodingBundle.message("spec.action.verify.document.unavailable.title"))
        }
    }

    fun compareBaseline(request: SpecWorkflowVerifyDeltaCompareRequest) {
        runIo {
            val result = when (val choice = request.choice) {
                is SpecWorkflowReferenceBaselineChoice -> compareByWorkflowId(
                    choice.workflowId,
                    request.targetWorkflow.id,
                )

                is SpecWorkflowPinnedDeltaBaselineChoice -> compareByDeltaBaseline(
                    request.targetWorkflow.id,
                    choice.baseline.baselineId,
                )
            }
            invokeLater {
                result.onSuccess { delta ->
                    showDeltaDialog(request.targetWorkflow, delta)
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

    fun pinBaseline(workflowId: String) {
        val normalizedWorkflowId = workflowId.trim().ifBlank { return }
        runIo {
            val snapshot = listWorkflowSnapshots(normalizedWorkflowId).firstOrNull()
            if (snapshot == null) {
                invokeLater {
                    setStatusText(SpecCodingBundle.message("spec.toolwindow.verifyDelta.pin.unavailable"))
                }
                return@runIo
            }
            val label = SpecCodingBundle.message(
                "spec.toolwindow.verifyDelta.pin.autoLabel",
                snapshot.snapshotId,
            )
            val result = pinDeltaBaseline(
                normalizedWorkflowId,
                snapshot.snapshotId,
                label,
            )
            invokeLater {
                result.onSuccess { baseline ->
                    reloadCurrentWorkflow()
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.toolwindow.verifyDelta.pin.saved",
                            baseline.label ?: baseline.baselineId,
                        ),
                    )
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
