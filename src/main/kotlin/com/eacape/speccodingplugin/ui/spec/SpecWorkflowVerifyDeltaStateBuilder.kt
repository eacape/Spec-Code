package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDeltaBaselineRef
import com.eacape.speccodingplugin.spec.SpecDeltaStatus
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.SpecWorkflowDelta
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.VerifyRunHistoryEntry

internal class SpecWorkflowVerifyDeltaStateBuilder(
    private val listVerificationHistory: (workflowId: String) -> List<VerifyRunHistoryEntry>,
    private val resolveBaselineWorkflowTitle: (workflowId: String) -> String?,
    private val listDeltaBaselines: (workflowId: String) -> List<SpecDeltaBaselineRef>,
    private val hasWorkflowSnapshots: (workflowId: String) -> Boolean,
    private val compareByWorkflowId: (baselineWorkflowId: String, targetWorkflowId: String) -> Result<SpecWorkflowDelta>,
    private val compareByDeltaBaseline: (workflowId: String, baselineId: String) -> Result<SpecWorkflowDelta>,
    private val hasVerificationArtifact: (workflowId: String) -> Boolean,
    private val logFailure: (message: String, error: Throwable) -> Unit = { _, _ -> },
) {

    fun build(
        workflow: SpecWorkflow,
        refreshedAtMillis: Long,
    ): SpecWorkflowVerifyDeltaState {
        val verificationHistory = runCatching {
            listVerificationHistory(workflow.id)
        }.getOrElse { error ->
            logFailure("Unable to load verification history for workflow ${workflow.id}", error)
            emptyList()
        }
        val baselineChoices = buildBaselineChoices(workflow)
        val canPinBaseline = runCatching {
            hasWorkflowSnapshots(workflow.id)
        }.getOrElse { error ->
            logFailure("Unable to inspect workflow snapshots for ${workflow.id}", error)
            false
        }
        val deltaSummary = buildPreferredDeltaSummary(workflow, baselineChoices)
        return SpecWorkflowVerifyDeltaState(
            workflowId = workflow.id,
            verifyEnabled = workflow.verifyEnabled || workflow.stageStates[StageId.VERIFY]?.active == true,
            verificationDocumentAvailable = hasVerificationArtifact(workflow.id),
            verificationHistory = verificationHistory,
            baselineChoices = baselineChoices,
            deltaSummary = deltaSummary,
            preferredBaselineChoiceId = baselineChoices.firstOrNull()?.stableId,
            canPinBaseline = canPinBaseline,
            refreshedAtMillis = refreshedAtMillis,
        )
    }

    private fun buildBaselineChoices(workflow: SpecWorkflow): List<SpecWorkflowDeltaBaselineChoice> {
        return buildList {
            workflow.baselineWorkflowId
                ?.trim()
                ?.takeIf { baselineWorkflowId -> baselineWorkflowId.isNotEmpty() && baselineWorkflowId != workflow.id }
                ?.let { baselineWorkflowId ->
                    add(
                        SpecWorkflowReferenceBaselineChoice(
                            workflowId = baselineWorkflowId,
                            title = resolveBaselineWorkflowTitle(baselineWorkflowId)
                                ?.ifBlank { baselineWorkflowId }
                                ?: baselineWorkflowId,
                        ),
                    )
                }
            addAll(
                runCatching {
                    listDeltaBaselines(workflow.id)
                }.getOrElse { error ->
                    logFailure("Unable to load delta baselines for workflow ${workflow.id}", error)
                    emptyList()
                }.map(::SpecWorkflowPinnedDeltaBaselineChoice),
            )
        }
    }

    private fun buildPreferredDeltaSummary(
        workflow: SpecWorkflow,
        baselineChoices: List<SpecWorkflowDeltaBaselineChoice>,
    ): String? {
        val preferredChoice = baselineChoices.firstOrNull() ?: return null
        return runCatching {
            when (preferredChoice) {
                is SpecWorkflowReferenceBaselineChoice -> compareByWorkflowId(
                    preferredChoice.workflowId,
                    workflow.id,
                )

                is SpecWorkflowPinnedDeltaBaselineChoice -> compareByDeltaBaseline(
                    workflow.id,
                    preferredChoice.baseline.baselineId,
                )
            }.getOrThrow()
        }.map(::formatDeltaSummary).getOrElse { error ->
            logFailure("Unable to build delta summary for workflow ${workflow.id}", error)
            null
        }
    }

    private fun formatDeltaSummary(delta: SpecWorkflowDelta): String {
        return SpecCodingBundle.message(
            "spec.delta.summary",
            delta.baselineWorkflowId,
            delta.targetWorkflowId,
            delta.count(SpecDeltaStatus.ADDED),
            delta.count(SpecDeltaStatus.MODIFIED),
            delta.count(SpecDeltaStatus.REMOVED),
            delta.count(SpecDeltaStatus.UNCHANGED),
        )
    }
}
