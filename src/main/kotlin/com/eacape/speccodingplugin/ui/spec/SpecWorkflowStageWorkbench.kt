package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.ImplementStageReadiness
import com.eacape.speccodingplugin.spec.RequirementsSectionSupport
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecTaskDependencyRules
import com.eacape.speccodingplugin.spec.SpecValidator
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.evaluateImplementStageReadiness
import com.eacape.speccodingplugin.spec.toStageId

internal enum class SpecWorkflowWorkbenchActionKind {
    ADVANCE,
    JUMP,
    ROLLBACK,
    START_TASK,
    RESUME_TASK,
    COMPLETE_TASK,
    STOP_TASK_EXECUTION,
    OPEN_TASK_CHAT,
    RUN_VERIFY,
    PREVIEW_VERIFY_PLAN,
    OPEN_VERIFICATION,
    SHOW_DELTA,
    COMPLETE_WORKFLOW,
    ARCHIVE_WORKFLOW,
}

internal enum class SpecWorkflowWorkbenchDocumentMode {
    PREVIEW,
    READ_ONLY,
}

internal data class SpecWorkflowWorkbenchAction(
    val kind: SpecWorkflowWorkbenchActionKind,
    val label: String,
    val enabled: Boolean,
    val targetStage: StageId? = null,
    val taskId: String? = null,
    val disabledReason: String? = null,
)

internal data class SpecWorkflowStageCompletionCheck(
    val id: String,
    val label: String,
    val completed: Boolean,
    val blockerMessage: String? = null,
)

internal data class SpecWorkflowStageProgressView(
    val stepIndex: Int,
    val totalSteps: Int,
    val stageStatus: StageProgress,
    val completedCheckCount: Int,
    val totalCheckCount: Int,
    val completionChecks: List<SpecWorkflowStageCompletionCheck>,
)

internal data class SpecWorkflowStageArtifactBinding(
    val stageId: StageId,
    val title: String,
    val fileName: String?,
    val documentPhase: SpecPhase?,
    val mode: SpecWorkflowWorkbenchDocumentMode,
    val fallbackEditable: Boolean,
    val available: Boolean = false,
    val previewContent: String? = null,
    val emptyStateMessage: String? = null,
    val unavailableMessage: String? = null,
)

internal data class SpecWorkflowImplementationFocus(
    val taskId: String,
    val title: String,
    val status: TaskStatus,
    val progress: SpecWorkflowExecutionProgressPresentation? = null,
)

internal data class SpecWorkflowStageWorkbenchState(
    val currentStage: StageId,
    val focusedStage: StageId,
    val progress: SpecWorkflowStageProgressView,
    val primaryAction: SpecWorkflowWorkbenchAction?,
    val overflowActions: List<SpecWorkflowWorkbenchAction>,
    val blockers: List<String>,
    val focusDetails: List<String> = emptyList(),
    val artifactBinding: SpecWorkflowStageArtifactBinding,
    val implementationFocus: SpecWorkflowImplementationFocus? = null,
    val visibleSections: Set<SpecWorkflowWorkspaceSectionId>,
)

internal object SpecWorkflowStageWorkbenchBuilder {
    fun build(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        tasks: List<StructuredTask> = emptyList(),
        liveProgressByTaskId: Map<String, com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress> = emptyMap(),
        verifyDeltaState: SpecWorkflowVerifyDeltaState? = null,
        gateResult: GateResult? = null,
        focusedStage: StageId? = null,
    ): SpecWorkflowStageWorkbenchState {
        val orderedStages = overviewState.stageStepper.stages.map { it.stageId }
        val resolvedFocusedStage = focusedStage
            ?.takeIf { requestedStage -> requestedStage in orderedStages }
            ?: overviewState.currentStage
        val focusedStep = overviewState.stageStepper.stages
            .firstOrNull { it.stageId == resolvedFocusedStage }
            ?: overviewState.stageStepper.stages.firstOrNull()
            ?: SpecWorkflowStageStepState(
                stageId = overviewState.currentStage,
                active = true,
                current = true,
                progress = StageProgress.IN_PROGRESS,
            )
        val activeStages = overviewState.activeStages.ifEmpty { orderedStages }
        val resolvedStepIndex = activeStages.indexOf(resolvedFocusedStage)
            .takeIf { it >= 0 }
            ?: orderedStages.indexOf(resolvedFocusedStage).coerceAtLeast(0)
        val completionChecks = buildCompletionChecks(
            workflow = workflow,
            overviewState = overviewState,
            focusedStage = resolvedFocusedStage,
            tasks = tasks,
            verifyDeltaState = verifyDeltaState,
            gateResult = gateResult,
        )
        val implementationFocus = buildImplementationFocus(
            focusedStage = resolvedFocusedStage,
            tasks = tasks,
            liveProgressByTaskId = liveProgressByTaskId,
        )
        val blockers = buildBlockers(
            overviewState = overviewState,
            focusedStage = resolvedFocusedStage,
            gateResult = gateResult,
            completionChecks = completionChecks,
        )

        return SpecWorkflowStageWorkbenchState(
            currentStage = overviewState.currentStage,
            focusedStage = resolvedFocusedStage,
            progress = SpecWorkflowStageProgressView(
                stepIndex = resolvedStepIndex + 1,
                totalSteps = activeStages.size.coerceAtLeast(1),
                stageStatus = focusedStep.progress,
                completedCheckCount = completionChecks.count(SpecWorkflowStageCompletionCheck::completed),
                totalCheckCount = completionChecks.size,
                completionChecks = completionChecks,
            ),
            primaryAction = buildPrimaryAction(
                workflow = workflow,
                overviewState = overviewState,
                focusedStage = resolvedFocusedStage,
                tasks = tasks,
                verifyDeltaState = verifyDeltaState,
                implementationFocus = implementationFocus,
                blockers = blockers,
            ),
            overflowActions = buildOverflowActions(
                overviewState = overviewState,
                focusedStage = resolvedFocusedStage,
                verifyDeltaState = verifyDeltaState,
                implementationFocus = implementationFocus,
            ),
            blockers = blockers,
            focusDetails = buildFocusDetails(
                workflow = workflow,
                overviewState = overviewState,
                focusedStage = resolvedFocusedStage,
                tasks = tasks,
                implementationFocus = implementationFocus,
                verifyDeltaState = verifyDeltaState,
            ),
            artifactBinding = buildArtifactBinding(
                workflow = workflow,
                focusedStage = resolvedFocusedStage,
                tasks = tasks,
                verifyDeltaState = verifyDeltaState,
            ),
            implementationFocus = implementationFocus,
            visibleSections = SpecWorkflowWorkspaceLayout.visibleSections(
                currentStage = resolvedFocusedStage,
                status = overviewState.status,
            ),
        )
    }

    private fun buildPrimaryAction(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId,
        tasks: List<StructuredTask>,
        verifyDeltaState: SpecWorkflowVerifyDeltaState?,
        implementationFocus: SpecWorkflowImplementationFocus?,
        blockers: List<String>,
    ): SpecWorkflowWorkbenchAction? {
        if (overviewState.status == WorkflowStatus.COMPLETED) {
            return if (focusedStage == StageId.ARCHIVE) {
                SpecWorkflowWorkbenchAction(
                    kind = SpecWorkflowWorkbenchActionKind.ARCHIVE_WORKFLOW,
                    label = SpecCodingBundle.message("spec.toolwindow.overview.primary.archive"),
                    enabled = true,
                )
            } else {
                null
            }
        }
        if (focusedStage == overviewState.currentStage && focusedStage == StageId.IMPLEMENT) {
            return buildImplementPrimaryAction(
                workflow = workflow,
                overviewState = overviewState,
                tasks = tasks,
                implementationFocus = implementationFocus,
            )
        }
        if (focusedStage == overviewState.currentStage && focusedStage == StageId.VERIFY) {
            buildVerifyPrimaryAction(
                workflow = workflow,
                overviewState = overviewState,
                verifyDeltaState = verifyDeltaState,
                blockers = blockers,
            )?.let { return it }
        }
        if (focusedStage == StageId.ARCHIVE) {
            buildArchivePrimaryAction(
                overviewState = overviewState,
                blockers = blockers,
            )?.let { return it }
        }
        return when {
            focusedStage == overviewState.currentStage -> {
                val enabled = overviewState.stageStepper.canAdvance && blockers.isEmpty()
                SpecWorkflowWorkbenchAction(
                    kind = SpecWorkflowWorkbenchActionKind.ADVANCE,
                    label = overviewState.nextStage?.let { nextStage ->
                        SpecCodingBundle.message(
                            "spec.toolwindow.overview.primary.advance",
                            SpecWorkflowOverviewPresenter.stageLabel(nextStage),
                        )
                    } ?: SpecCodingBundle.message("spec.action.advance.text"),
                    enabled = enabled,
                    targetStage = overviewState.nextStage,
                    disabledReason = disabledReason(enabled, blockers),
                )
            }

            else -> null
        }
    }

    private fun buildVerifyPrimaryAction(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        verifyDeltaState: SpecWorkflowVerifyDeltaState?,
        blockers: List<String>,
    ): SpecWorkflowWorkbenchAction? {
        val verifyEnabled = verifyDeltaState?.verifyEnabled == true ||
            workflow.stageStates[StageId.VERIFY]?.active == true
        val verificationReady = verifyEnabled &&
            verifyDeltaState?.verificationHistory?.isNotEmpty() == true &&
            verifyDeltaState?.verificationDocumentAvailable == true
        return if (verificationReady && overviewState.stageStepper.canAdvance && blockers.isEmpty()) {
            SpecWorkflowWorkbenchAction(
                kind = SpecWorkflowWorkbenchActionKind.ADVANCE,
                label = overviewState.nextStage?.let { nextStage ->
                    SpecCodingBundle.message(
                        "spec.toolwindow.overview.primary.advance",
                        SpecWorkflowOverviewPresenter.stageLabel(nextStage),
                    )
                } ?: SpecCodingBundle.message("spec.action.advance.text"),
                enabled = true,
                targetStage = overviewState.nextStage,
            )
        } else {
            val enabled = verifyEnabled
            SpecWorkflowWorkbenchAction(
                kind = SpecWorkflowWorkbenchActionKind.RUN_VERIFY,
                label = SpecCodingBundle.message("spec.toolwindow.overview.primary.verify.run"),
                enabled = enabled,
                disabledReason = disabledReason(
                    enabled = enabled,
                    blockers = blockers,
                    fallbackMessage = SpecCodingBundle.message("spec.toolwindow.overview.blockers.verify.enabled"),
                ),
            )
        }
    }

    private fun buildArchivePrimaryAction(
        overviewState: SpecWorkflowOverviewState,
        blockers: List<String>,
    ): SpecWorkflowWorkbenchAction? {
        return when {
            overviewState.status == WorkflowStatus.COMPLETED -> SpecWorkflowWorkbenchAction(
                kind = SpecWorkflowWorkbenchActionKind.ARCHIVE_WORKFLOW,
                label = SpecCodingBundle.message("spec.toolwindow.overview.primary.archive"),
                enabled = true,
            )

            focusedStageRequiresCompletion(overviewState) -> {
                val enabled = blockers.isEmpty()
                SpecWorkflowWorkbenchAction(
                    kind = SpecWorkflowWorkbenchActionKind.COMPLETE_WORKFLOW,
                    label = SpecCodingBundle.message("spec.toolwindow.overview.primary.archive.complete"),
                    enabled = enabled,
                    disabledReason = disabledReason(enabled, blockers),
                )
            }

            else -> null
        }
    }

    private fun buildImplementPrimaryAction(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        tasks: List<StructuredTask>,
        implementationFocus: SpecWorkflowImplementationFocus?,
    ): SpecWorkflowWorkbenchAction? {
        val implementReadiness = evaluateImplementStageReadiness(
            tasksDocument = workflow.getDocument(SpecPhase.IMPLEMENT),
            tasks = tasks,
        )
        // Task-level controls are already available in the structured task list.
        // The overview keeps only the stage-level advance action to avoid duplicate entry points.
        if (implementationFocus != null) {
            return null
        }
        if (!implementReadiness.allWorkSettled) {
            return null
        }
        val enabled = overviewState.stageStepper.canAdvance && implementReadiness.relatedFilesConfirmed
        return SpecWorkflowWorkbenchAction(
            kind = SpecWorkflowWorkbenchActionKind.ADVANCE,
            label = overviewState.nextStage?.let { nextStage ->
                SpecCodingBundle.message(
                    "spec.toolwindow.overview.primary.implement.continueCheck.withTarget",
                    SpecWorkflowOverviewPresenter.stageLabel(nextStage),
                )
            } ?: SpecCodingBundle.message("spec.toolwindow.overview.primary.implement.continueCheck"),
            enabled = enabled,
            targetStage = overviewState.nextStage,
            disabledReason = disabledReason(
                enabled = enabled,
                blockers = if (!implementReadiness.relatedFilesConfirmed) {
                    listOf(SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.relatedFiles"))
                } else {
                    emptyList()
                },
            ),
        )
    }

    private fun buildOverflowActions(
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId,
        verifyDeltaState: SpecWorkflowVerifyDeltaState?,
        implementationFocus: SpecWorkflowImplementationFocus?,
    ): List<SpecWorkflowWorkbenchAction> {
        return buildList {
            when (focusedStage) {
                StageId.IMPLEMENT -> Unit

                StageId.VERIFY -> {
                    if (verifyDeltaState?.verifyEnabled == true) {
                        add(
                            SpecWorkflowWorkbenchAction(
                                kind = SpecWorkflowWorkbenchActionKind.PREVIEW_VERIFY_PLAN,
                                label = SpecCodingBundle.message("spec.toolwindow.overview.more.previewVerifyPlan"),
                                enabled = true,
                            ),
                        )
                    }
                    if (verifyDeltaState?.verificationDocumentAvailable == true) {
                        add(
                            SpecWorkflowWorkbenchAction(
                                kind = SpecWorkflowWorkbenchActionKind.OPEN_VERIFICATION,
                                label = SpecCodingBundle.message("spec.toolwindow.overview.more.openVerification"),
                                enabled = true,
                            ),
                        )
                    }
                    if (verifyDeltaState?.baselineChoices?.isNotEmpty() == true) {
                        add(
                            SpecWorkflowWorkbenchAction(
                                kind = SpecWorkflowWorkbenchActionKind.SHOW_DELTA,
                                label = SpecCodingBundle.message("spec.toolwindow.overview.more.showDelta"),
                                enabled = true,
                            ),
                        )
                    }
                }

                StageId.ARCHIVE -> {
                    if (verifyDeltaState?.verificationDocumentAvailable == true) {
                        add(
                            SpecWorkflowWorkbenchAction(
                                kind = SpecWorkflowWorkbenchActionKind.OPEN_VERIFICATION,
                                label = SpecCodingBundle.message("spec.toolwindow.overview.more.openVerification"),
                                enabled = true,
                            ),
                        )
                    }
                    if (verifyDeltaState?.baselineChoices?.isNotEmpty() == true) {
                        add(
                            SpecWorkflowWorkbenchAction(
                                kind = SpecWorkflowWorkbenchActionKind.SHOW_DELTA,
                                label = SpecCodingBundle.message("spec.toolwindow.overview.more.showDelta"),
                                enabled = true,
                            ),
                        )
                    }
                }

                else -> Unit
            }
        }
    }

    private fun buildFocusDetails(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId,
        tasks: List<StructuredTask>,
        implementationFocus: SpecWorkflowImplementationFocus?,
        verifyDeltaState: SpecWorkflowVerifyDeltaState?,
    ): List<String> {
        return when (focusedStage) {
            StageId.REQUIREMENTS -> buildRequirementsFocusDetails(workflow)
            StageId.DESIGN -> buildDesignFocusDetails(workflow)
            StageId.TASKS -> buildTasksFocusDetails(workflow, tasks)
            StageId.IMPLEMENT -> buildImplementFocusDetails(tasks, implementationFocus)
            StageId.VERIFY -> buildVerifyFocusDetails(workflow, verifyDeltaState)
            StageId.ARCHIVE -> buildArchiveFocusDetails(workflow, overviewState, tasks, verifyDeltaState)
        }
    }

    private fun buildRequirementsFocusDetails(workflow: SpecWorkflow): List<String> {
        val requirementsReady = workflow.documents.containsKey(SpecPhase.SPECIFY)
        return listOf(
            SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.requirements.source"),
            artifactStateLine("requirements.md", requirementsReady),
            SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.requirements.workspace"),
        )
    }

    private fun buildDesignFocusDetails(workflow: SpecWorkflow): List<String> {
        val designReady = workflow.documents.containsKey(SpecPhase.DESIGN)
        return listOf(
            SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.design.source"),
            artifactStateLine("design.md", designReady),
            SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.design.workspace"),
        )
    }

    private fun buildTasksFocusDetails(
        workflow: SpecWorkflow,
        tasks: List<StructuredTask>,
    ): List<String> {
        val completedCount = tasks.count { it.status == TaskStatus.COMPLETED }
        val inProgressCount = tasks.count { it.displayStatus == TaskStatus.IN_PROGRESS }
        val tasksLine = when {
            !workflow.documents.containsKey(SpecPhase.IMPLEMENT) -> artifactStateLine("tasks.md", false)
            tasks.isEmpty() -> SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.tasks.none")
            else -> SpecCodingBundle.message(
                "spec.toolwindow.overview.focus.detail.tasks.count",
                tasks.size,
                completedCount,
                inProgressCount,
            )
        }
        return listOf(
            SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.tasks.source"),
            tasksLine,
            SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.tasks.workspace"),
        )
    }

    private fun buildImplementFocusDetails(
        tasks: List<StructuredTask>,
        implementationFocus: SpecWorkflowImplementationFocus?,
    ): List<String> {
        val completedCount = tasks.count { it.status == TaskStatus.COMPLETED }
        val inProgressCount = tasks.count { it.displayStatus == TaskStatus.IN_PROGRESS }
        val pendingCount = tasks.count { task ->
            task.displayStatus == TaskStatus.PENDING || task.displayStatus == TaskStatus.BLOCKED
        }
        val tasksLine = if (tasks.isEmpty()) {
            SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.implement.none")
        } else {
            SpecCodingBundle.message(
                "spec.toolwindow.overview.focus.detail.implement.count",
                tasks.size,
                completedCount,
                inProgressCount,
                pendingCount,
            )
        }
        val progress = implementationFocus?.progress
        if (progress == null) {
            return listOf(
                SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.implement.source"),
                tasksLine,
                SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.implement.workspace"),
            )
        }
        return buildList {
            add(
                SpecCodingBundle.message(
                    "spec.toolwindow.overview.focus.detail.implement.execution",
                    progress.phaseLabel,
                    progress.elapsedText,
                    progress.lastActivityText,
                ),
            )
            add(
                SpecCodingBundle.message(
                    "spec.toolwindow.overview.focus.detail.implement.latest",
                    progress.detailText,
                ),
            )
            progress.recentActivityTrailText?.let { recentActivity ->
                add(
                    SpecCodingBundle.message(
                        "spec.toolwindow.overview.focus.detail.implement.recent",
                        recentActivity,
                    ),
                )
            }
            add(tasksLine)
        }
    }

    private fun buildVerifyFocusDetails(
        workflow: SpecWorkflow,
        verifyDeltaState: SpecWorkflowVerifyDeltaState?,
    ): List<String> {
        val verifyEnabled = verifyDeltaState?.verifyEnabled == true ||
            workflow.stageStates[StageId.VERIFY]?.active == true
        val latestRun = verifyDeltaState?.verificationHistory?.firstOrNull()
        val planLine = when {
            !verifyEnabled -> SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.verify.plan.disabled")
            latestRun != null -> SpecCodingBundle.message(
                "spec.toolwindow.overview.focus.detail.verify.plan.latest",
                latestRun.planId,
                latestRun.commandCount,
            )

            else -> SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.verify.plan.ready")
        }
        val latestLine = when {
            latestRun != null -> SpecCodingBundle.message(
                "spec.toolwindow.overview.focus.detail.verify.latest",
                verificationStatusText(latestRun.conclusion),
                latestRun.runId,
                compactSummary(latestRun.summary),
            )

            verifyEnabled -> SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.verify.latest.none")
            else -> SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.verify.latest.disabled")
        }
        return listOf(planLine, latestLine, buildDeltaFocusLine(verifyDeltaState))
    }

    private fun buildArchiveFocusDetails(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        tasks: List<StructuredTask>,
        verifyDeltaState: SpecWorkflowVerifyDeltaState?,
    ): List<String> {
        val implementReadyToComplete = evaluateImplementStageReadiness(
            tasksDocument = workflow.getDocument(SpecPhase.IMPLEMENT),
            tasks = tasks,
        ).readyForWorkflowCompletion
        val archiveLine = when {
            workflow.status == WorkflowStatus.COMPLETED -> {
                SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.archive.ready")
            }

            overviewState.currentStage == StageId.ARCHIVE && !implementReadyToComplete -> {
                SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.archive.completeBlocked")
            }

            overviewState.currentStage == StageId.ARCHIVE -> {
                SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.archive.completeFirst")
            }

            else -> {
                SpecCodingBundle.message(
                    "spec.toolwindow.overview.focus.detail.archive.waiting",
                    SpecWorkflowOverviewPresenter.stageLabel(overviewState.currentStage),
                )
            }
        }
        val latestRun = verifyDeltaState?.verificationHistory?.firstOrNull()
        val verificationLine = when {
            latestRun != null -> SpecCodingBundle.message(
                "spec.toolwindow.overview.focus.detail.archive.verification.latest",
                verificationStatusText(latestRun.conclusion),
                latestRun.runId,
            )

            verifyDeltaState?.verifyEnabled == true && verifyDeltaState.verificationDocumentAvailable -> {
                SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.archive.verification.documentOnly")
            }

            verifyDeltaState?.verifyEnabled == true -> {
                SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.archive.verification.pending")
            }

            else -> SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.archive.verification.disabled")
        }
        return listOf(archiveLine, verificationLine, buildDeltaFocusLine(verifyDeltaState))
    }

    private fun buildDeltaFocusLine(verifyDeltaState: SpecWorkflowVerifyDeltaState?): String {
        val deltaSummary = verifyDeltaState?.deltaSummary?.takeIf { it.isNotBlank() }
        return when {
            deltaSummary != null -> SpecCodingBundle.message(
                "spec.toolwindow.overview.focus.detail.delta.available",
                deltaSummary,
            )

            verifyDeltaState?.baselineChoices?.isNotEmpty() == true -> SpecCodingBundle.message(
                "spec.toolwindow.overview.focus.detail.delta.baselines",
                verifyDeltaState.baselineChoices.size,
            )

            else -> SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.delta.none")
        }
    }

    private fun buildBlockers(
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId,
        gateResult: GateResult?,
        completionChecks: List<SpecWorkflowStageCompletionCheck>,
    ): List<String> {
        val focusedStep = overviewState.stageStepper.stages.firstOrNull { it.stageId == focusedStage }
        if (focusedStep?.active == false) {
            return listOf(SpecCodingBundle.message("spec.toolwindow.overview.blockers.inactive"))
        }
        if (focusedStage != overviewState.currentStage) {
            return emptyList()
        }
        if (gateResult?.status == GateStatus.ERROR) {
            return limitBlockers(
                buildGateBlockers(
                    gateResult = gateResult,
                    overviewState = overviewState,
                ),
            )
        }
        val checkBlockers = completionChecks
            .filterNot(SpecWorkflowStageCompletionCheck::completed)
            .mapNotNull(SpecWorkflowStageCompletionCheck::blockerMessage)
        return limitBlockers(checkBlockers)
    }

    private fun buildGateBlockers(
        gateResult: GateResult,
        overviewState: SpecWorkflowOverviewState,
    ): List<String> {
        val gateBlockers = gateResult.aggregation.errorViolations
            .map { violation ->
                if (violation.fileName.isNotBlank() && violation.line > 0) {
                    SpecCodingBundle.message(
                        "spec.toolwindow.overview.blockers.gate.withLocation",
                        violation.ruleId,
                        violation.message,
                        violation.fileName,
                        violation.line,
                    )
                } else {
                    SpecCodingBundle.message(
                        "spec.toolwindow.overview.blockers.gate",
                        violation.ruleId,
                        violation.message,
                    )
                }
            }
        if (gateBlockers.isNotEmpty()) {
            return gateBlockers
        }
        return listOfNotNull(overviewState.gateSummary?.takeIf { it.isNotBlank() })
    }

    private fun limitBlockers(blockers: List<String>): List<String> {
        if (blockers.isEmpty()) {
            return emptyList()
        }
        val visible = blockers.take(BLOCKER_LIMIT).toMutableList()
        val remaining = blockers.size - visible.size
        if (remaining > 0) {
            visible += SpecCodingBundle.message("spec.toolwindow.overview.blockers.more", remaining)
        }
        return visible
    }

    private fun buildArtifactBinding(
        workflow: SpecWorkflow,
        focusedStage: StageId,
        tasks: List<StructuredTask>,
        verifyDeltaState: SpecWorkflowVerifyDeltaState?,
    ): SpecWorkflowStageArtifactBinding {
        val documentPhase = when (focusedStage) {
            StageId.REQUIREMENTS -> SpecPhase.SPECIFY
            StageId.DESIGN -> SpecPhase.DESIGN
            StageId.TASKS,
            StageId.IMPLEMENT,
            -> SpecPhase.IMPLEMENT

            StageId.VERIFY,
            StageId.ARCHIVE,
            -> null
        }
        val fileName = when (focusedStage) {
            StageId.IMPLEMENT -> StageId.TASKS.artifactFileName
            StageId.ARCHIVE -> StageId.VERIFY.artifactFileName
            else -> focusedStage.artifactFileName
        }
        val mode = if (documentPhase != null) {
            SpecWorkflowWorkbenchDocumentMode.PREVIEW
        } else {
            SpecWorkflowWorkbenchDocumentMode.READ_ONLY
        }
        val title = when (focusedStage) {
            StageId.IMPLEMENT -> fileName ?: SpecWorkflowOverviewPresenter.stageLabel(focusedStage)
            StageId.ARCHIVE -> fileName ?: SpecWorkflowOverviewPresenter.stageLabel(focusedStage)
            else -> fileName ?: SpecWorkflowOverviewPresenter.stageLabel(focusedStage)
        }

        return SpecWorkflowStageArtifactBinding(
            stageId = focusedStage,
            title = title,
            fileName = fileName,
            documentPhase = documentPhase,
            mode = mode,
            fallbackEditable = documentPhase != null && workflow.documents.containsKey(documentPhase),
            emptyStateMessage = stageArtifactEmptyStateMessage(
                stageId = focusedStage,
                tasks = tasks,
                verifyDeltaState = verifyDeltaState,
            ),
            unavailableMessage = stageArtifactUnavailableMessage(
                stageId = focusedStage,
                tasks = tasks,
                verifyDeltaState = verifyDeltaState,
            ),
        )
    }

    private fun buildCompletionChecks(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId,
        tasks: List<StructuredTask>,
        verifyDeltaState: SpecWorkflowVerifyDeltaState?,
        gateResult: GateResult?,
    ): List<SpecWorkflowStageCompletionCheck> {
        val taskIds = tasks.map(StructuredTask::id).toSet()
        val tasksDocument = workflow.documents[SpecPhase.IMPLEMENT]
        val requirementsDocument = workflow.documents[SpecPhase.SPECIFY]
        val designDocument = workflow.documents[SpecPhase.DESIGN]
        val clarificationPending = hasPendingClarificationForFocusedStage(
            workflow = workflow,
            focusedStage = focusedStage,
        )
        val gateReady = isGateReady(
            overviewState = overviewState,
            focusedStage = focusedStage,
            gateResult = gateResult,
        )
        val gateUnavailableBlocker = gateUnavailableBlocker(
            overviewState = overviewState,
            focusedStage = focusedStage,
            gateResult = gateResult,
        )
        return when (focusedStage) {
            StageId.REQUIREMENTS -> {
                val validation = requirementsDocument?.let { document ->
                    document.validationResult ?: SpecValidator.validate(document)
                }
                val hasSections = requirementsDocument?.content?.let(::hasRequirementsSections) == true
                listOf(
                    SpecWorkflowStageCompletionCheck(
                        id = "requirements-document",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.requirements.document"),
                        completed = requirementsDocument != null,
                        blockerMessage = if (requirementsDocument == null) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.requirements.document")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "requirements-sections",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.requirements.sections"),
                        completed = hasSections,
                        blockerMessage = if (requirementsDocument != null && !hasSections) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.requirements.sections")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "requirements-validation",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.requirements.validation"),
                        completed = validation?.hasErrors() == false,
                        blockerMessage = if (requirementsDocument != null && validation?.hasErrors() == true) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.requirements.validation")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "requirements-clarification",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.common.clarification"),
                        completed = !clarificationPending,
                        blockerMessage = if (clarificationPending) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.common.clarificationPending")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "requirements-gate",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.common.gate"),
                        completed = gateReady,
                        blockerMessage = gateUnavailableBlocker,
                    ),
                )
            }

            StageId.DESIGN -> {
                val validation = designDocument?.let { document ->
                    document.validationResult ?: SpecValidator.validate(document)
                }
                val hasSections = designDocument?.content?.let(::hasDesignSections) == true
                listOf(
                    SpecWorkflowStageCompletionCheck(
                        id = "design-document",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.design.document"),
                        completed = designDocument != null,
                        blockerMessage = if (designDocument == null) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.design.document")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "design-sections",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.design.sections"),
                        completed = hasSections,
                        blockerMessage = if (designDocument != null && !hasSections) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.design.sections")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "design-validation",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.design.validation"),
                        completed = validation?.hasErrors() == false,
                        blockerMessage = if (designDocument != null && validation?.hasErrors() == true) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.design.validation")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "design-clarification",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.common.clarification"),
                        completed = !clarificationPending,
                        blockerMessage = if (clarificationPending) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.common.clarificationPending")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "design-gate",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.common.gate"),
                        completed = gateReady,
                        blockerMessage = gateUnavailableBlocker,
                    ),
                )
            }

            StageId.TASKS -> {
                val hasValidDependencies = tasks.isNotEmpty() && tasks.all { task ->
                    task.dependsOn.all { dependencyId -> dependencyId != task.id && dependencyId in taskIds }
                }
                listOf(
                    SpecWorkflowStageCompletionCheck(
                        id = "tasks-document",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.tasks.document"),
                        completed = tasksDocument != null,
                        blockerMessage = if (tasksDocument == null) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.tasks.document")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "tasks-structured",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.tasks.structured"),
                        completed = tasks.isNotEmpty(),
                        blockerMessage = if (tasksDocument != null && tasks.isEmpty()) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.tasks.structured")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "tasks-dependencies",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.tasks.dependencies"),
                        completed = hasValidDependencies,
                        blockerMessage = if (tasks.isNotEmpty() && !hasValidDependencies) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.tasks.dependencies")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "tasks-clarification",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.common.clarification"),
                        completed = !clarificationPending,
                        blockerMessage = if (clarificationPending) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.common.clarificationPending")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "tasks-gate",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.common.gate"),
                        completed = gateReady,
                        blockerMessage = gateUnavailableBlocker,
                    ),
                )
            }

            StageId.IMPLEMENT -> {
                val implementReadiness = evaluateImplementStageReadiness(
                    tasksDocument = tasksDocument,
                    tasks = tasks,
                )
                listOf(
                    SpecWorkflowStageCompletionCheck(
                        id = "implement-task-source",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.implement.taskSource"),
                        completed = implementReadiness.taskSourceReady,
                        blockerMessage = if (!implementReadiness.taskSourceReady) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.taskSource")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "implement-progress",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.implement.progress"),
                        completed = implementReadiness.progressSatisfied,
                        blockerMessage = if (implementReadiness.taskCount > 0 && !implementReadiness.progressSatisfied) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.progress")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "implement-related-files",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.implement.relatedFiles"),
                        completed = implementReadiness.relatedFilesConfirmed,
                        blockerMessage = if (implementReadiness.relatedFilesBlocked) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.relatedFiles")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "implement-gate",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.common.gate"),
                        completed = gateReady,
                        blockerMessage = gateUnavailableBlocker,
                    ),
                )
            }

            StageId.VERIFY -> {
                val latestRun = verifyDeltaState?.verificationHistory?.firstOrNull()
                val verifyEnabled = verifyDeltaState?.verifyEnabled == true ||
                    workflow.stageStates[StageId.VERIFY]?.active == true
                listOf(
                    SpecWorkflowStageCompletionCheck(
                        id = "verify-enabled",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.verify.enabled"),
                        completed = verifyEnabled,
                        blockerMessage = if (!verifyEnabled) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.verify.enabled")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "verify-plan",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.verify.plan"),
                        completed = verifyEnabled,
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "verify-run",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.verify.run"),
                        completed = latestRun != null,
                        blockerMessage = if (verifyEnabled && latestRun == null) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.verify.run")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "verify-document",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.verify.document"),
                        completed = verifyDeltaState?.verificationDocumentAvailable == true,
                        blockerMessage = if (verifyEnabled && verifyDeltaState?.verificationDocumentAvailable != true) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.verify.document")
                        } else {
                            null
                        },
                    ),
                )
            }

            StageId.ARCHIVE -> {
                val activePreArchiveStages = overviewState.activeStages.filter { stageId -> stageId != StageId.ARCHIVE }
                val implementReadiness = evaluateImplementStageReadiness(
                    tasksDocument = tasksDocument,
                    tasks = tasks,
                )
                val verifySatisfied = verifyDeltaState?.verifyEnabled != true ||
                    verifyDeltaState.verificationDocumentAvailable ||
                    verifyDeltaState.verificationHistory.isNotEmpty()
                val previousStagesComplete = activePreArchiveStages.all { stageId ->
                    workflow.stageStates[stageId]?.status == StageProgress.DONE || workflow.currentStage == StageId.ARCHIVE
                }
                listOf(
                    SpecWorkflowStageCompletionCheck(
                        id = "archive-stages",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.archive.stages"),
                        completed = previousStagesComplete,
                        blockerMessage = if (!previousStagesComplete) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.archive.stages")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "archive-verification",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.archive.verification"),
                        completed = verifySatisfied,
                        blockerMessage = if (!verifySatisfied) {
                            SpecCodingBundle.message("spec.toolwindow.overview.blockers.archive.verification")
                        } else {
                            null
                        },
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "archive-implement-ready",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.archive.implementReady"),
                        completed = implementReadiness.readyForWorkflowCompletion,
                        blockerMessage = archiveImplementReadyBlocker(implementReadiness),
                    ),
                    SpecWorkflowStageCompletionCheck(
                        id = "archive-ready",
                        label = SpecCodingBundle.message("spec.toolwindow.overview.check.archive.ready"),
                        completed = workflow.currentStage == StageId.ARCHIVE || workflow.status == WorkflowStatus.COMPLETED,
                    ),
                )
            }
        }
    }

    private fun archiveImplementReadyBlocker(implementReadiness: ImplementStageReadiness): String? {
        return when {
            !implementReadiness.taskSourceReady -> {
                SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.taskSource")
            }

            implementReadiness.progressBlocked -> {
                SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.progress")
            }

            implementReadiness.relatedFilesBlocked -> {
                SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.relatedFiles")
            }

            !implementReadiness.readyForWorkflowCompletion -> {
                SpecCodingBundle.message("spec.toolwindow.overview.blockers.archive.implementReady")
            }

            else -> {
                null
            }
        }
    }

    private fun buildImplementationFocus(
        focusedStage: StageId,
        tasks: List<StructuredTask>,
        liveProgressByTaskId: Map<String, com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress>,
    ): SpecWorkflowImplementationFocus? {
        if (focusedStage != StageId.IMPLEMENT || tasks.isEmpty()) {
            return null
        }
        val tasksById = tasks.associateBy(StructuredTask::id)
        val focusedTask = tasks.firstOrNull { it.displayStatus == TaskStatus.IN_PROGRESS }
            ?: tasks.firstOrNull { task ->
                task.status == TaskStatus.PENDING && dependenciesCompleted(task, tasksById)
            }
            ?: tasks.firstOrNull { task ->
                task.status == TaskStatus.BLOCKED && dependenciesCompleted(task, tasksById)
            }
            ?: return null
        return SpecWorkflowImplementationFocus(
            taskId = focusedTask.id,
            title = focusedTask.title,
            status = focusedTask.displayStatus,
            progress = SpecWorkflowExecutionProgressUi.resolve(
                task = focusedTask,
                liveProgress = liveProgressByTaskId[focusedTask.id],
            ),
        )
    }

    private fun isGateReady(
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId,
        gateResult: GateResult?,
    ): Boolean {
        return when {
            focusedStage == overviewState.currentStage -> gateResult != null && gateResult.status != GateStatus.ERROR
            focusedStage.ordinal < overviewState.currentStage.ordinal ->
                overviewState.stageStepper.stages.firstOrNull { it.stageId == focusedStage }?.progress == StageProgress.DONE

            else -> false
        }
    }

    private fun gateUnavailableBlocker(
        overviewState: SpecWorkflowOverviewState,
        focusedStage: StageId,
        gateResult: GateResult?,
    ): String? {
        return if (
            focusedStage == overviewState.currentStage &&
            overviewState.stageStepper.canAdvance &&
            gateResult == null
        ) {
            SpecCodingBundle.message("spec.toolwindow.overview.blockers.common.gateUnavailable")
        } else {
            null
        }
    }

    private fun hasRequirementsSections(content: String): Boolean = RequirementsSectionSupport.hasRequiredSections(content)

    private fun hasDesignSections(content: String): Boolean {
        return REQUIRED_DESIGN_SECTIONS.all { markers -> containsAnyMarker(content, markers) }
    }

    private fun hasPendingClarificationForFocusedStage(
        workflow: SpecWorkflow,
        focusedStage: StageId,
    ): Boolean {
        val state = workflow.clarificationRetryState ?: return false
        if (state.confirmed) {
            return false
        }
        if (workflow.currentPhase.toStageId() != focusedStage) {
            return false
        }
        return state.questionsMarkdown.isNotBlank() || state.structuredQuestions.isNotEmpty()
    }

    private fun containsAnyMarker(content: String, markers: List<String>): Boolean {
        return markers.any { marker -> content.contains(marker, ignoreCase = true) }
    }

    private fun dependenciesCompleted(
        task: StructuredTask,
        tasksById: Map<String, StructuredTask>,
    ): Boolean {
        return SpecTaskDependencyRules.executionConstraint(task, tasksById.values).executable
    }

    private fun focusedStageRequiresCompletion(overviewState: SpecWorkflowOverviewState): Boolean {
        return overviewState.currentStage == StageId.ARCHIVE
    }

    private fun verificationStatusText(conclusion: com.eacape.speccodingplugin.spec.VerificationConclusion): String {
        return when (conclusion) {
            com.eacape.speccodingplugin.spec.VerificationConclusion.PASS ->
                SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.pass")

            com.eacape.speccodingplugin.spec.VerificationConclusion.WARN ->
                SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.warn")

            com.eacape.speccodingplugin.spec.VerificationConclusion.FAIL ->
                SpecCodingBundle.message("spec.toolwindow.verifyDelta.status.fail")
        }
    }

    private fun compactSummary(summary: String, maxLength: Int = 72): String {
        val normalized = summary
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.length <= maxLength) {
            return normalized
        }
        return normalized.take(maxLength - 3).trimEnd() + "..."
    }

    private fun disabledReason(
        enabled: Boolean,
        blockers: List<String>,
        fallbackMessage: String = SpecCodingBundle.message("spec.toolwindow.overview.primary.disabled.advanceUnavailable"),
    ): String? {
        if (enabled) {
            return null
        }
        return blockers.firstOrNull() ?: fallbackMessage
    }

    private fun artifactStateLine(fileName: String, available: Boolean): String {
        return if (available) {
            SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.document.ready", fileName)
        } else {
            SpecCodingBundle.message("spec.toolwindow.overview.focus.detail.document.missing", fileName)
        }
    }

    private fun stageArtifactEmptyStateMessage(
        stageId: StageId,
        tasks: List<StructuredTask>,
        verifyDeltaState: SpecWorkflowVerifyDeltaState?,
    ): String {
        return when (stageId) {
            StageId.REQUIREMENTS -> SpecCodingBundle.message("spec.detail.workbench.requirements.missing")
            StageId.DESIGN -> SpecCodingBundle.message("spec.detail.workbench.design.missing")
            StageId.TASKS -> if (tasks.isEmpty()) {
                SpecCodingBundle.message("spec.detail.workbench.tasks.missing")
            } else {
                SpecCodingBundle.message("spec.detail.workbench.tasks.empty")
            }

            StageId.IMPLEMENT -> SpecCodingBundle.message("spec.detail.workbench.implement.missing")
            StageId.VERIFY -> if (verifyDeltaState?.verifyEnabled == true) {
                SpecCodingBundle.message("spec.detail.workbench.verify.missing")
            } else {
                SpecCodingBundle.message("spec.detail.workbench.verify.disabled")
            }

            StageId.ARCHIVE -> SpecCodingBundle.message("spec.detail.workbench.archive.missing")
        }
    }

    private fun stageArtifactUnavailableMessage(
        stageId: StageId,
        tasks: List<StructuredTask>,
        verifyDeltaState: SpecWorkflowVerifyDeltaState?,
    ): String {
        return stageArtifactEmptyStateMessage(
            stageId = stageId,
            tasks = tasks,
            verifyDeltaState = verifyDeltaState,
        )
    }

    private val REQUIRED_REQUIREMENTS_SECTIONS = listOf(
        listOf("## 功能需求", "功能需求", "Functional Requirements"),
        listOf("## 非功能需求", "非功能需求", "Non-Functional Requirements"),
        listOf("## 用户故事", "用户故事", "User Stories"),
    )

    private val REQUIRED_DESIGN_SECTIONS = listOf(
        listOf("## 架构设计", "架构设计", "系统架构", "Architecture Design", "Architecture"),
        listOf("## 技术选型", "技术选型", "技术方案", "Technology Stack"),
        listOf("## 数据模型", "数据模型", "实体模型", "Data Model"),
    )

    private const val BLOCKER_LIMIT = 3
}
