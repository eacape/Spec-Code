package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.spec.WorkflowStatus

internal enum class SpecWorkflowWorkspaceChipTone {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
    MUTED,
}

internal data class SpecWorkflowWorkspaceMetricPresentation(
    val title: String,
    val value: String,
    val tone: SpecWorkflowWorkspaceChipTone,
)

internal data class SpecWorkflowWorkspaceSectionSummaries(
    val overview: String,
    val tasks: String,
    val gate: String,
    val verify: String,
    val documents: String,
)

internal data class SpecWorkflowWorkspaceSummaryPresentation(
    val title: String,
    val meta: String,
    val focusTitle: String,
    val focusSummary: String,
    val stageMetric: SpecWorkflowWorkspaceMetricPresentation,
    val gateMetric: SpecWorkflowWorkspaceMetricPresentation,
    val tasksMetric: SpecWorkflowWorkspaceMetricPresentation,
    val verifyMetric: SpecWorkflowWorkspaceMetricPresentation,
    val sectionSummaries: SpecWorkflowWorkspaceSectionSummaries,
)

internal object SpecWorkflowWorkspaceSummaryPresenter {
    fun build(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        workbenchState: SpecWorkflowStageWorkbenchState,
        guidance: SpecWorkflowStageGuidance,
        tasks: List<StructuredTask>,
        verifyDeltaState: SpecWorkflowVerifyDeltaState,
        gateResult: GateResult?,
    ): SpecWorkflowWorkspaceSummaryPresentation {
        val nextStageText = overviewState.nextStage
            ?.let(SpecWorkflowOverviewPresenter::stageLabel)
            ?: SpecCodingBundle.message("spec.toolwindow.overview.nextStage.none")
        return SpecWorkflowWorkspaceSummaryPresentation(
            title = workflow.title.ifBlank { workflow.id },
            meta = buildMeta(workflow, nextStageText),
            focusTitle = guidance.headline,
            focusSummary = guidance.summary,
            stageMetric = buildStageMetric(workflow.status, workbenchState),
            gateMetric = buildGateMetric(gateResult),
            tasksMetric = buildTasksMetric(tasks),
            verifyMetric = buildVerifyMetric(verifyDeltaState),
            sectionSummaries = SpecWorkflowWorkspaceSectionSummaries(
                overview = buildOverviewSectionSummary(overviewState, workbenchState, nextStageText),
                tasks = buildTasksSectionSummary(tasks),
                gate = buildGateSectionSummary(gateResult),
                verify = buildVerifySectionSummary(verifyDeltaState),
                documents = buildDocumentsSectionSummary(workbenchState),
            ),
        )
    }

    private fun buildMeta(
        workflow: SpecWorkflow,
        nextStageText: String,
    ): String {
        return buildString {
            append(SpecCodingBundle.message("spec.toolwindow.overview.secondary.workflow"))
            append(": ")
            append(workflow.id)
            append(" | ")
            append(SpecCodingBundle.message("spec.toolwindow.overview.secondary.template"))
            append(": ")
            append(SpecWorkflowOverviewPresenter.templateLabel(workflow.template))
            append(" | ")
            append(SpecCodingBundle.message("spec.toolwindow.overview.secondary.next"))
            append(": ")
            append(nextStageText)
        }
    }

    private fun buildStageMetric(
        status: WorkflowStatus,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ): SpecWorkflowWorkspaceMetricPresentation {
        val stageLabel = SpecWorkflowOverviewPresenter.stageLabel(workbenchState.focusedStage)
        val checksText = "${workbenchState.progress.completedCheckCount}/${workbenchState.progress.totalCheckCount}"
        val progressText = "${workbenchState.progress.stepIndex}/${workbenchState.progress.totalSteps}"
        val stageStatus = SpecWorkflowOverviewPresenter.progressLabel(workbenchState.progress.stageStatus)
        val title = if (workbenchState.focusedStage == workbenchState.currentStage) {
            SpecCodingBundle.message("spec.toolwindow.overview.currentStage")
        } else {
            SpecCodingBundle.message("spec.toolwindow.overview.secondary.focus")
        }
        val value = if (workbenchState.focusedStage == workbenchState.currentStage) {
            "$stageLabel / $checksText / $progressText / $stageStatus"
        } else {
            "$stageLabel / $checksText / $progressText"
        }
        val tone = when (status) {
            WorkflowStatus.COMPLETED -> SpecWorkflowWorkspaceChipTone.SUCCESS
            WorkflowStatus.PAUSED -> SpecWorkflowWorkspaceChipTone.WARNING
            WorkflowStatus.FAILED -> SpecWorkflowWorkspaceChipTone.ERROR
            WorkflowStatus.IN_PROGRESS -> SpecWorkflowWorkspaceChipTone.INFO
        }
        return SpecWorkflowWorkspaceMetricPresentation(
            title = title,
            value = value,
            tone = tone,
        )
    }

    private fun buildGateMetric(gateResult: GateResult?): SpecWorkflowWorkspaceMetricPresentation {
        val tone = when (gateResult?.status) {
            GateStatus.ERROR -> SpecWorkflowWorkspaceChipTone.ERROR
            GateStatus.WARNING -> SpecWorkflowWorkspaceChipTone.WARNING
            GateStatus.PASS -> SpecWorkflowWorkspaceChipTone.SUCCESS
            null -> SpecWorkflowWorkspaceChipTone.MUTED
        }
        val value = when (gateResult?.status) {
            GateStatus.PASS -> SpecCodingBundle.message("spec.toolwindow.gate.status.pass")
            GateStatus.WARNING -> SpecCodingBundle.message("spec.toolwindow.gate.status.warning")
            GateStatus.ERROR -> SpecCodingBundle.message("spec.toolwindow.gate.status.error")
            null -> SpecCodingBundle.message("spec.toolwindow.gate.status.unavailable")
        }
        return SpecWorkflowWorkspaceMetricPresentation(
            title = SpecCodingBundle.message("spec.toolwindow.section.gate"),
            value = value,
            tone = tone,
        )
    }

    private fun buildTasksMetric(tasks: List<StructuredTask>): SpecWorkflowWorkspaceMetricPresentation {
        val value = if (tasks.isEmpty()) {
            "0/0"
        } else {
            val completed = tasks.count { it.status == TaskStatus.COMPLETED }
            "$completed/${tasks.size}"
        }
        val tone = when {
            tasks.any { it.status == TaskStatus.BLOCKED } -> SpecWorkflowWorkspaceChipTone.WARNING
            tasks.isNotEmpty() && tasks.all { it.status == TaskStatus.COMPLETED } -> SpecWorkflowWorkspaceChipTone.SUCCESS
            else -> SpecWorkflowWorkspaceChipTone.INFO
        }
        return SpecWorkflowWorkspaceMetricPresentation(
            title = SpecCodingBundle.message("spec.toolwindow.tasks.title"),
            value = value,
            tone = tone,
        )
    }

    private fun buildVerifyMetric(state: SpecWorkflowVerifyDeltaState): SpecWorkflowWorkspaceMetricPresentation {
        val tone = when (state.verificationHistory.firstOrNull()?.conclusion) {
            VerificationConclusion.FAIL -> SpecWorkflowWorkspaceChipTone.ERROR
            VerificationConclusion.WARN -> SpecWorkflowWorkspaceChipTone.WARNING
            VerificationConclusion.PASS -> SpecWorkflowWorkspaceChipTone.SUCCESS
            null -> SpecWorkflowWorkspaceChipTone.MUTED
        }
        return SpecWorkflowWorkspaceMetricPresentation(
            title = SpecCodingBundle.message("spec.toolwindow.section.verify"),
            value = "${state.verificationHistory.size} / ${verificationStatusText(state)}",
            tone = tone,
        )
    }

    private fun buildOverviewSectionSummary(
        overviewState: SpecWorkflowOverviewState,
        workbenchState: SpecWorkflowStageWorkbenchState,
        nextStageText: String,
    ): String {
        return buildString {
            append(SpecWorkflowOverviewPresenter.stageLabel(workbenchState.focusedStage))
            append(" | ")
            append(workbenchState.progress.completedCheckCount)
            append("/")
            append(workbenchState.progress.totalCheckCount)
            append(" ")
            append(SpecCodingBundle.message("spec.toolwindow.overview.checks.short"))
            append(" | ")
            append(SpecCodingBundle.message("spec.toolwindow.overview.secondary.next"))
            append(": ")
            append(nextStageText)
        }
    }

    private fun buildTasksSectionSummary(tasks: List<StructuredTask>): String {
        if (tasks.isEmpty()) {
            return SpecCodingBundle.message("spec.toolwindow.tasks.emptyForWorkflow")
        }
        val completed = tasks.count { it.status == TaskStatus.COMPLETED }
        val blocked = tasks.count { it.status == TaskStatus.BLOCKED }
        return SpecCodingBundle.message(
            "spec.toolwindow.tasks.summary",
            tasks.size,
            completed,
            blocked,
        )
    }

    private fun buildGateSectionSummary(gateResult: GateResult?): String {
        if (gateResult == null) {
            return SpecCodingBundle.message("spec.toolwindow.gate.summary.none")
        }
        return SpecCodingBundle.message(
            "spec.toolwindow.gate.summary",
            gateResult.aggregation.errorCount,
            gateResult.aggregation.warningCount,
            gateResult.aggregation.totalViolationCount,
        )
    }

    private fun buildVerifySectionSummary(state: SpecWorkflowVerifyDeltaState): String {
        val latest = state.verificationHistory.firstOrNull()?.conclusion?.name
            ?: SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.pending")
        return when {
            !state.verifyEnabled -> SpecCodingBundle.message(
                "spec.toolwindow.verifyDelta.summary.disabled",
                state.baselineChoices.size,
            )

            state.verificationHistory.isEmpty() -> SpecCodingBundle.message(
                "spec.toolwindow.verifyDelta.summary.noRuns",
                state.baselineChoices.size,
            )

            else -> SpecCodingBundle.message(
                "spec.toolwindow.verifyDelta.summary",
                state.verificationHistory.size,
                latest,
                state.baselineChoices.size,
            )
        }
    }

    private fun buildDocumentsSectionSummary(workbenchState: SpecWorkflowStageWorkbenchState): String {
        return buildString {
            append(SpecWorkflowOverviewPresenter.stageLabel(workbenchState.focusedStage))
            append(" | ")
            append(workbenchState.artifactBinding.fileName ?: workbenchState.artifactBinding.title)
        }
    }

    private fun verificationStatusText(state: SpecWorkflowVerifyDeltaState): String {
        if (!state.verifyEnabled) {
            return SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.disabled")
        }
        return when (state.verificationHistory.firstOrNull()?.conclusion) {
            VerificationConclusion.PASS -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.pass")
            VerificationConclusion.WARN -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.warn")
            VerificationConclusion.FAIL -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.fail")
            null -> SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.pending")
        }
    }
}
