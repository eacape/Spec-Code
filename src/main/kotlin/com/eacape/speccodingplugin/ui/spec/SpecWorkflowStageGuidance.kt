package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.WorkflowStatus

internal data class SpecWorkflowStageGuidance(
    val headline: String,
    val summary: String,
    val checklist: List<String>,
)

internal object SpecWorkflowStageGuidanceBuilder {
    fun build(state: SpecWorkflowOverviewState): SpecWorkflowStageGuidance {
        val activeStages = state.activeStages.ifEmpty { state.stageStepper.stages.map { it.stageId } }
        val currentStep = state.stageStepper.stages.firstOrNull { it.stageId == state.currentStage }
        val fallbackWorkbenchState = SpecWorkflowStageWorkbenchState(
            currentStage = state.currentStage,
            focusedStage = state.currentStage,
            progress = SpecWorkflowStageProgressView(
                stepIndex = activeStages.indexOf(state.currentStage).coerceAtLeast(0) + 1,
                totalSteps = activeStages.size.coerceAtLeast(1),
                stageStatus = currentStep?.progress ?: StageProgress.IN_PROGRESS,
                completedCheckCount = 0,
                totalCheckCount = 0,
                completionChecks = emptyList(),
            ),
            primaryAction = null,
            overflowActions = emptyList(),
            blockers = emptyList(),
            focusDetails = emptyList(),
            artifactBinding = SpecWorkflowStageArtifactBinding(
                stageId = state.currentStage,
                title = SpecWorkflowOverviewPresenter.stageLabel(state.currentStage),
                fileName = state.currentStage.artifactFileName,
                documentPhase = null,
                mode = SpecWorkflowWorkbenchDocumentMode.READ_ONLY,
                fallbackEditable = false,
            ),
            implementationFocus = null,
            visibleSections = SpecWorkflowWorkspaceLayout.visibleSections(
                currentStage = state.currentStage,
                status = state.status,
            ),
        )
        return build(state, fallbackWorkbenchState)
    }

    fun build(
        state: SpecWorkflowOverviewState,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ): SpecWorkflowStageGuidance {
        val stageLabel = SpecWorkflowOverviewPresenter.stageLabel(workbenchState.focusedStage)
        return SpecWorkflowStageGuidance(
            headline = if (workbenchState.focusedStage == state.currentStage) {
                SpecCodingBundle.message("spec.toolwindow.overview.focus.title.current", stageLabel)
            } else {
                SpecCodingBundle.message("spec.toolwindow.overview.focus.title.focused", stageLabel)
            },
            summary = buildSummary(state, workbenchState),
            checklist = buildChecklist(state, workbenchState),
        )
    }

    private fun buildSummary(
        state: SpecWorkflowOverviewState,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ): String {
        if (state.status == WorkflowStatus.COMPLETED || workbenchState.focusedStage == StageId.ARCHIVE) {
            return SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.archive")
        }
        return when (workbenchState.focusedStage) {
            StageId.REQUIREMENTS -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.requirements")
            StageId.DESIGN -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.design")
            StageId.TASKS -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.tasks")
            StageId.IMPLEMENT -> buildImplementSummary(workbenchState)
            StageId.VERIFY -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.verify")
            StageId.ARCHIVE -> SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.archive")
        }
    }

    private fun buildImplementSummary(workbenchState: SpecWorkflowStageWorkbenchState): String {
        val implementationFocus = workbenchState.implementationFocus
        return when (implementationFocus?.status) {
            com.eacape.speccodingplugin.spec.TaskStatus.IN_PROGRESS -> when (implementationFocus.progress?.phase) {
                com.eacape.speccodingplugin.spec.ExecutionLivePhase.WAITING_CONFIRMATION -> SpecCodingBundle.message(
                    "spec.toolwindow.overview.focus.summary.implement.waitingConfirmation",
                    implementationFocus.taskId,
                    implementationFocus.title,
                )

                com.eacape.speccodingplugin.spec.ExecutionLivePhase.CANCELLING -> SpecCodingBundle.message(
                    "spec.toolwindow.overview.focus.summary.implement.cancelling",
                    implementationFocus.taskId,
                    implementationFocus.title,
                )

                else -> SpecCodingBundle.message(
                    "spec.toolwindow.overview.focus.summary.implement.running",
                    implementationFocus.taskId,
                    implementationFocus.title,
                    implementationFocus.progress?.phaseLabel
                        ?: SpecCodingBundle.message("spec.toolwindow.execution.phase.requestDispatched"),
                )
            }

            com.eacape.speccodingplugin.spec.TaskStatus.PENDING -> SpecCodingBundle.message(
                "spec.toolwindow.overview.focus.summary.implement.start",
                implementationFocus.taskId,
                implementationFocus.title,
            )

            com.eacape.speccodingplugin.spec.TaskStatus.BLOCKED -> SpecCodingBundle.message(
                "spec.toolwindow.overview.focus.summary.implement.blocked",
                implementationFocus.taskId,
                implementationFocus.title,
            )

            else -> {
                if (workbenchState.primaryAction?.kind == SpecWorkflowWorkbenchActionKind.ADVANCE) {
                    SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.implement.continueCheck")
                } else {
                    SpecCodingBundle.message("spec.toolwindow.overview.focus.summary.implement")
                }
            }
        }
    }

    private fun buildChecklist(
        state: SpecWorkflowOverviewState,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ): List<String> {
        if (workbenchState.progress.completionChecks.isEmpty()) {
            return emptyList()
        }
        return workbenchState.progress.completionChecks.map { check ->
            val key = if (check.completed) {
                "spec.toolwindow.overview.check.state.done"
            } else {
                "spec.toolwindow.overview.check.state.todo"
            }
            SpecCodingBundle.message(key, check.label)
        }
    }
}
