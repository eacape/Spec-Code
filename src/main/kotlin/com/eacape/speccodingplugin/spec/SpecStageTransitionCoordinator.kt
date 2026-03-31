package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.hook.HookEvent
import com.eacape.speccodingplugin.hook.HookManager
import com.eacape.speccodingplugin.hook.HookTriggerContext
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.time.Instant

internal class SpecStageTransitionCoordinator(
    private val project: Project,
    private val storage: SpecStorage,
    private val activeWorkflows: MutableMap<String, SpecWorkflow>,
    private val projectConfigLoader: () -> SpecProjectConfig,
    private val resolveStagePlan: (SpecWorkflow, SpecProjectConfig) -> WorkflowStagePlan,
    private val stagePhaseResolver: (StageId) -> SpecPhase,
    private val stageGateEvaluator: SpecStageGateEvaluator,
    private val artifactService: SpecArtifactService,
    private val tasksService: SpecTasksService,
    private val verificationService: SpecVerificationService,
) {
    private val logger = thisLogger()

    fun advanceWorkflow(
        workflowId: String,
        confirmWarnings: ((GateResult) -> Boolean)? = null,
    ): Result<StageTransitionResult> {
        return runCatching {
            val prepared = prepareStageTransition(
                workflowId = workflowId,
                transitionType = StageTransitionType.ADVANCE,
            )
            performStageTransition(
                workflow = prepared.workflow,
                stagePlan = prepared.stagePlan,
                gatePolicy = prepared.gatePolicy,
                transitionType = StageTransitionType.ADVANCE,
                targetStage = prepared.targetStage,
                evaluatedStages = prepared.evaluatedStages,
                confirmWarnings = confirmWarnings,
            )
        }
    }

    fun jumpToStage(
        workflowId: String,
        targetStage: StageId,
        confirmWarnings: ((GateResult) -> Boolean)? = null,
    ): Result<StageTransitionResult> {
        return runCatching {
            val prepared = prepareStageTransition(
                workflowId = workflowId,
                transitionType = StageTransitionType.JUMP,
                requestedTargetStage = targetStage,
            )
            performStageTransition(
                workflow = prepared.workflow,
                stagePlan = prepared.stagePlan,
                gatePolicy = prepared.gatePolicy,
                transitionType = StageTransitionType.JUMP,
                targetStage = prepared.targetStage,
                evaluatedStages = prepared.evaluatedStages,
                confirmWarnings = confirmWarnings,
            )
        }
    }

    fun rollbackToStage(
        workflowId: String,
        targetStage: StageId,
    ): Result<WorkflowMeta> {
        return runCatching {
            val prepared = prepareStageTransition(
                workflowId = workflowId,
                transitionType = StageTransitionType.ROLLBACK,
                requestedTargetStage = targetStage,
            )

            if (prepared.targetStage == prepared.workflow.currentStage) {
                activeWorkflows[workflowId] = prepared.workflow
                return@runCatching prepared.workflow.toWorkflowMeta()
            }

            performStageTransition(
                workflow = prepared.workflow,
                stagePlan = prepared.stagePlan,
                gatePolicy = prepared.gatePolicy,
                transitionType = StageTransitionType.ROLLBACK,
                targetStage = prepared.targetStage,
                evaluatedStages = prepared.evaluatedStages,
                confirmWarnings = null,
            ).workflow.toWorkflowMeta()
        }
    }

    fun previewStageTransition(
        workflowId: String,
        transitionType: StageTransitionType,
        targetStage: StageId? = null,
    ): Result<StageTransitionGatePreview> {
        return runCatching {
            val prepared = prepareStageTransition(
                workflowId = workflowId,
                transitionType = transitionType,
                requestedTargetStage = targetStage,
            )
            val gateResult = if (transitionType == StageTransitionType.ROLLBACK) {
                GateResult.fromViolations(emptyList())
            } else {
                evaluateTransitionGate(
                    buildStageTransitionRequest(
                        workflow = prepared.workflow,
                        transitionType = transitionType,
                        targetStage = prepared.targetStage,
                        evaluatedStages = prepared.evaluatedStages,
                        stagePlan = prepared.stagePlan,
                    ),
                )
            }
            StageTransitionGatePreview(
                workflowId = prepared.workflow.id,
                transitionType = transitionType,
                fromStage = prepared.workflow.currentStage,
                targetStage = prepared.targetStage,
                evaluatedStages = prepared.evaluatedStages,
                gateResult = gateResult,
            )
        }
    }

    fun completeWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: throw IllegalStateException("Workflow not found: $workflowId")

            if (workflow.currentPhase != SpecPhase.IMPLEMENT) {
                throw IllegalStateException("Cannot complete workflow. Must be at Implement phase.")
            }

            val implementDoc = workflow.getDocument(SpecPhase.IMPLEMENT)
                ?: throw IllegalStateException("Implement phase document not found")

            val implementReadiness = evaluateImplementStageReadiness(
                tasksDocument = implementDoc,
                tasks = resolveImplementationTasks(workflow, implementDoc),
            )
            val blockers = implementCompletionBlockers(implementReadiness)
            if (blockers.isNotEmpty()) {
                throw IllegalStateException(
                    "Cannot complete workflow. ${blockers.joinToString(" ")}",
                )
            }

            val completedAt = System.currentTimeMillis()
            val completedStageStates = markCurrentStageCompleted(
                workflow = workflow,
                timestampMillis = completedAt,
            )
            val updatedWorkflow = workflow.copy(
                status = WorkflowStatus.COMPLETED,
                stageStates = completedStageStates,
                clarificationRetryState = null,
                updatedAt = completedAt,
            )

            activeWorkflows[workflowId] = updatedWorkflow
            storage.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId completed")
            updatedWorkflow
        }
    }

    private fun performStageTransition(
        workflow: SpecWorkflow,
        stagePlan: WorkflowStagePlan,
        gatePolicy: SpecGatePolicy,
        transitionType: StageTransitionType,
        targetStage: StageId,
        evaluatedStages: List<StageId>,
        confirmWarnings: ((GateResult) -> Boolean)?,
    ): StageTransitionResult {
        if (!stagePlan.isActive(workflow.currentStage)) {
            throw InactiveWorkflowStageError(workflow.currentStage)
        }
        if (!stagePlan.isActive(targetStage)) {
            throw InactiveWorkflowStageError(targetStage)
        }

        val gateResult = if (transitionType == StageTransitionType.ROLLBACK) {
            GateResult.fromViolations(emptyList())
        } else {
            evaluateTransitionGate(
                buildStageTransitionRequest(
                    workflow = workflow,
                    transitionType = transitionType,
                    targetStage = targetStage,
                    evaluatedStages = evaluatedStages,
                    stagePlan = stagePlan,
                ),
            )
        }
        recordGateRuleDowngradeAudit(
            workflow = workflow,
            transitionType = transitionType,
            targetStage = targetStage,
            evaluatedStages = evaluatedStages,
            gateResult = gateResult,
        )
        val warningConfirmed = resolveWarningDecision(
            gatePolicy = gatePolicy,
            gateResult = gateResult,
            fromStage = workflow.currentStage,
            targetStage = targetStage,
            confirmWarnings = confirmWarnings,
        )
        if (warningConfirmed) {
            recordGateWarningConfirmationAudit(
                workflow = workflow,
                transitionType = transitionType,
                targetStage = targetStage,
                evaluatedStages = evaluatedStages,
                gateResult = gateResult,
            )
        }

        val updatedAt = System.currentTimeMillis()
        val stageMetadata = applyStageTransitionMetadata(
            workflow = workflow,
            stagePlan = stagePlan,
            targetStage = targetStage,
            timestampMillis = updatedAt,
        )
        val updatedWorkflow = workflow.copy(
            currentPhase = stagePhaseResolver(stageMetadata.currentStage),
            stageStates = stageMetadata.stageStates,
            currentStage = stageMetadata.currentStage,
            verifyEnabled = stageMetadata.verifyEnabled,
            clarificationRetryState = null,
            updatedAt = updatedAt,
        )

        val saveResult = storage.saveWorkflowTransition(
            workflow = updatedWorkflow,
            eventType = transitionAuditEventType(transitionType),
            details = buildStageTransitionAuditDetails(
                workflow = workflow,
                transitionType = transitionType,
                targetStage = targetStage,
                gateResult = gateResult,
                evaluatedStages = evaluatedStages,
                warningConfirmed = warningConfirmed,
            ),
        ).getOrThrow()
        activeWorkflows[workflow.id] = updatedWorkflow

        emitSpecStageChangedHook(
            workflowId = workflow.id,
            previousPhase = workflow.currentPhase,
            currentPhase = updatedWorkflow.currentPhase,
            previousStage = workflow.currentStage,
            currentStage = updatedWorkflow.currentStage,
        )
        logger.info(
            "Workflow ${workflow.id} transitioned ${transitionType.name.lowercase()} " +
                "${workflow.currentStage} -> ${updatedWorkflow.currentStage} (gate=${gateResult.status})",
        )
        return StageTransitionResult(
            workflow = updatedWorkflow,
            transitionType = transitionType,
            fromStage = workflow.currentStage,
            targetStage = updatedWorkflow.currentStage,
            gateResult = gateResult,
            warningConfirmed = warningConfirmed,
            beforeSnapshotId = saveResult.beforeSnapshotId,
            afterSnapshotId = saveResult.afterSnapshotId,
        )
    }

    private fun applyStageTransitionMetadata(
        workflow: SpecWorkflow,
        stagePlan: WorkflowStagePlan,
        targetStage: StageId,
        timestampMillis: Long,
    ): StageMetadataState {
        val timestamp = Instant.ofEpochMilli(timestampMillis).toString()
        val completedStages = stagePlan.activeStagesBefore(targetStage).toSet()
        val updatedStates = linkedMapOf<StageId, StageState>()

        StageId.entries.forEach { stageId ->
            val previous = workflow.stageStates[stageId] ?: StageState(
                active = stagePlan.isActive(stageId),
                status = StageProgress.NOT_STARTED,
            )
            updatedStates[stageId] = when {
                !stagePlan.isActive(stageId) -> StageState(
                    active = false,
                    status = StageProgress.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                )

                stageId == targetStage -> previous.copy(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                    enteredAt = timestamp,
                    completedAt = null,
                )

                completedStages.contains(stageId) -> previous.copy(
                    active = true,
                    status = StageProgress.DONE,
                    enteredAt = previous.enteredAt ?: timestamp,
                    completedAt = previous.completedAt ?: timestamp,
                )

                else -> StageState(
                    active = true,
                    status = StageProgress.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                )
            }
        }

        return StageMetadataState(
            currentStage = targetStage,
            verifyEnabled = stagePlan.isActive(StageId.VERIFY),
            stageStates = updatedStates,
        )
    }

    private fun resolveWarningDecision(
        gatePolicy: SpecGatePolicy,
        gateResult: GateResult,
        fromStage: StageId,
        targetStage: StageId,
        confirmWarnings: ((GateResult) -> Boolean)?,
    ): Boolean {
        return when (gateResult.status) {
            GateStatus.ERROR -> throw StageTransitionBlockedByGateError(fromStage, targetStage, gateResult)
            GateStatus.WARNING -> {
                if (!gatePolicy.allowWarningAdvance) {
                    throw StageTransitionBlockedByGateError(fromStage, targetStage, gateResult)
                }
                if (!gatePolicy.requireWarningConfirmation) {
                    false
                } else {
                    val confirmed = confirmWarnings?.invoke(gateResult) == true
                    if (!confirmed) {
                        throw StageWarningConfirmationRequiredError(fromStage, targetStage, gateResult)
                    }
                    true
                }
            }

            GateStatus.PASS -> false
        }
    }

    private fun buildStageTransitionAuditDetails(
        workflow: SpecWorkflow,
        transitionType: StageTransitionType,
        targetStage: StageId,
        gateResult: GateResult,
        evaluatedStages: List<StageId>,
        warningConfirmed: Boolean,
    ): Map<String, String> {
        val details = linkedMapOf(
            "fromStage" to workflow.currentStage.name,
            "toStage" to targetStage.name,
            "transitionType" to transitionType.name,
            "gateStatus" to gateResult.status.name,
            "warningConfirmed" to warningConfirmed.toString(),
            "gateSummary" to gateResult.aggregation.summary,
            "warningCount" to gateResult.aggregation.warningCount.toString(),
            "errorCount" to gateResult.aggregation.errorCount.toString(),
        )
        if (evaluatedStages.isNotEmpty()) {
            details["evaluatedStages"] = evaluatedStages.joinToString(",") { stage -> stage.name }
        }
        return details
    }

    private fun recordGateRuleDowngradeAudit(
        workflow: SpecWorkflow,
        transitionType: StageTransitionType,
        targetStage: StageId,
        evaluatedStages: List<StageId>,
        gateResult: GateResult,
    ) {
        val downgradedViolations = gateResult.ruleResults
            .flatMap { evaluation ->
                evaluation.violations
                    .filter { violation ->
                        val originalSeverity = violation.originalSeverity ?: return@filter false
                        violation.severity.ordinal < originalSeverity.ordinal
                    }
                    .map { violation -> evaluation.ruleId to violation }
            }
        if (downgradedViolations.isEmpty()) {
            return
        }

        val details = linkedMapOf(
            "fromStage" to workflow.currentStage.name,
            "toStage" to targetStage.name,
            "transitionType" to transitionType.name,
            "gateStatus" to gateResult.status.name,
            "downgradeCount" to downgradedViolations.size.toString(),
            "downgradedRules" to downgradedViolations
                .map { (ruleId, violation) ->
                    val originalSeverity = violation.originalSeverity ?: violation.severity
                    "$ruleId:${originalSeverity.name}->${violation.severity.name}"
                }
                .distinct()
                .sorted()
                .joinToString(","),
            "downgradedViolations" to downgradedViolations
                .map { (ruleId, violation) ->
                    val originalSeverity = violation.originalSeverity ?: violation.severity
                    "$ruleId@${violation.fileName}:${violation.line}:${originalSeverity.name}->${violation.severity.name}"
                }
                .sorted()
                .joinToString(";"),
        )
        if (evaluatedStages.isNotEmpty()) {
            details["evaluatedStages"] = evaluatedStages.joinToString(",") { stage -> stage.name }
        }
        storage.appendAuditEvent(
            workflowId = workflow.id,
            eventType = SpecAuditEventType.GATE_RULE_DOWNGRADED,
            details = details,
        ).getOrThrow()
    }

    private fun recordGateWarningConfirmationAudit(
        workflow: SpecWorkflow,
        transitionType: StageTransitionType,
        targetStage: StageId,
        evaluatedStages: List<StageId>,
        gateResult: GateResult,
    ) {
        val confirmation = gateResult.warningConfirmation ?: return
        val warnings = confirmation.warnings
        if (warnings.isEmpty()) {
            return
        }

        val details = linkedMapOf(
            "fromStage" to workflow.currentStage.name,
            "toStage" to targetStage.name,
            "transitionType" to transitionType.name,
            "gateStatus" to gateResult.status.name,
            "warningCount" to gateResult.aggregation.warningCount.toString(),
            "warningRuleCount" to gateResult.aggregation.warningRuleCount.toString(),
            "gateSummary" to gateResult.aggregation.summary,
            "warningRules" to warnings.map(Violation::ruleId).distinct().sorted().joinToString(","),
            "warningViolations" to warnings
                .map { violation -> "${violation.ruleId}@${violation.fileName}:${violation.line}" }
                .sorted()
                .joinToString(";"),
        )
        if (evaluatedStages.isNotEmpty()) {
            details["evaluatedStages"] = evaluatedStages.joinToString(",") { stage -> stage.name }
        }
        storage.appendAuditEvent(
            workflowId = workflow.id,
            eventType = SpecAuditEventType.GATE_WARNING_CONFIRMED,
            details = details,
        ).getOrThrow()
    }

    private fun transitionAuditEventType(transitionType: StageTransitionType): SpecAuditEventType {
        return when (transitionType) {
            StageTransitionType.ADVANCE -> SpecAuditEventType.STAGE_ADVANCED
            StageTransitionType.JUMP -> SpecAuditEventType.STAGE_JUMPED
            StageTransitionType.ROLLBACK -> SpecAuditEventType.STAGE_ROLLED_BACK
        }
    }

    private fun evaluateTransitionGate(request: StageTransitionRequest): GateResult {
        val gateResult = stageGateEvaluator.evaluate(request)
        if (request.transitionType != StageTransitionType.ADVANCE) {
            return gateResult
        }

        val completionViolations = buildAdvanceCompletionViolations(request)
        if (completionViolations.isEmpty()) {
            return gateResult
        }

        val completionRule = RuleEvaluationResult(
            ruleId = STAGE_COMPLETION_RULE_ID,
            description = "Stage completion checks",
            enabled = true,
            appliedStages = listOf(request.fromStage),
            defaultSeverity = GateStatus.ERROR,
            effectiveSeverity = GateStatus.ERROR,
            severityOverridden = false,
            summary = "Completion checks are incomplete for ${request.fromStage.name}.",
            violations = completionViolations,
        )
        return GateResult.fromViolations(
            violations = gateResult.violations + completionViolations,
            ruleResults = gateResult.ruleResults + completionRule,
        )
    }

    private fun buildAdvanceCompletionViolations(request: StageTransitionRequest): List<Violation> {
        val workflow = request.workflow
        val currentStage = request.fromStage
        val tasksArtifactContent = artifactService.readArtifact(workflow.id, StageId.TASKS)
        val tasksRepairQuickFixes = tasksArtifactRepairQuickFixes(tasksArtifactContent)
        val tasksDocument = workflow.getDocument(SpecPhase.IMPLEMENT)
        val tasks = resolveImplementationTasks(workflow, tasksDocument)
        val requirementsDocument = workflow.getDocument(SpecPhase.SPECIFY)
        val designDocument = workflow.getDocument(SpecPhase.DESIGN)
        val verifyEnabled = request.stagePlan.isActive(StageId.VERIFY) || workflow.verifyEnabled
        val verificationDocumentAvailable = artifactService.readArtifact(workflow.id, StageId.VERIFY) != null
        val hasVerificationRun = verificationService.listRunHistory(workflow.id).isNotEmpty()

        return when (currentStage) {
            StageId.REQUIREMENTS -> buildList {
                val missingSections = requirementsDocument
                    ?.content
                    ?.let(RequirementsSectionSupport::missingSections)
                    .orEmpty()
                if (missingSections.isNotEmpty()) {
                    add(
                        completionViolation(
                            fileName = StageId.REQUIREMENTS.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.requirements.sections"),
                            quickFixes = requirementsMissingSectionQuickFixes(missingSections),
                        ),
                    )
                }
                if (hasPendingClarification(workflow, currentStage)) {
                    add(
                        completionViolation(
                            fileName = StageId.REQUIREMENTS.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.common.clarificationPending"),
                        ),
                    )
                }
            }

            StageId.DESIGN -> buildList {
                if (designDocument != null && !hasDesignSections(designDocument.content)) {
                    add(
                        completionViolation(
                            fileName = StageId.DESIGN.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.design.sections"),
                        ),
                    )
                }
                if (hasPendingClarification(workflow, currentStage)) {
                    add(
                        completionViolation(
                            fileName = StageId.DESIGN.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.common.clarificationPending"),
                        ),
                    )
                }
            }

            StageId.TASKS -> buildList {
                if (tasks.isEmpty()) {
                    add(
                        completionViolation(
                            fileName = StageId.TASKS.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.tasks.structured"),
                            quickFixes = tasksRepairQuickFixes,
                        ),
                    )
                }
                if (hasPendingClarification(workflow, currentStage)) {
                    add(
                        completionViolation(
                            fileName = StageId.TASKS.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.common.clarificationPending"),
                        ),
                    )
                }
            }

            StageId.IMPLEMENT -> {
                val implementReadiness = evaluateImplementStageReadiness(
                    tasksDocument = tasksDocument,
                    tasks = tasks,
                )
                buildList {
                    if (!implementReadiness.taskSourceReady) {
                        add(
                            completionViolation(
                                fileName = StageId.TASKS.artifactFileName.orEmpty(),
                                message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.taskSource"),
                                quickFixes = tasksRepairQuickFixes,
                            ),
                        )
                    }
                    if (implementReadiness.progressBlocked) {
                        add(
                            completionViolation(
                                fileName = StageId.TASKS.artifactFileName.orEmpty(),
                                message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.progress"),
                            ),
                        )
                    }
                    if (implementReadiness.relatedFilesBlocked) {
                        add(
                            completionViolation(
                                fileName = StageId.TASKS.artifactFileName.orEmpty(),
                                message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.relatedFiles"),
                            ),
                        )
                    }
                }
            }

            StageId.VERIFY -> buildList {
                if (!verifyEnabled) {
                    add(
                        completionViolation(
                            fileName = StageId.VERIFY.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.verify.enabled"),
                        ),
                    )
                }
                if (verifyEnabled && !hasVerificationRun) {
                    add(
                        completionViolation(
                            fileName = StageId.VERIFY.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.verify.run"),
                        ),
                    )
                }
                if (verifyEnabled && !verificationDocumentAvailable) {
                    add(
                        completionViolation(
                            fileName = StageId.VERIFY.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.verify.document"),
                        ),
                    )
                }
            }

            StageId.ARCHIVE -> emptyList()
        }
    }

    private fun completionViolation(
        fileName: String,
        message: String,
        quickFixes: List<GateQuickFixDescriptor> = emptyList(),
    ): Violation {
        return Violation(
            ruleId = STAGE_COMPLETION_RULE_ID,
            severity = GateStatus.ERROR,
            fileName = fileName,
            line = 1,
            message = message,
            fixHint = message,
            quickFixes = quickFixes,
        )
    }

    private fun hasPendingClarification(
        workflow: SpecWorkflow,
        stageId: StageId,
    ): Boolean {
        val state = workflow.clarificationRetryState ?: return false
        if (state.confirmed) {
            return false
        }
        if (workflow.currentPhase.toStageId() != stageId) {
            return false
        }
        return state.questionsMarkdown.isNotBlank() || state.structuredQuestions.isNotEmpty()
    }

    private fun hasDesignSections(content: String): Boolean {
        return REQUIRED_DESIGN_SECTION_MARKERS.all { markers ->
            markers.any { marker -> content.contains(marker, ignoreCase = true) }
        }
    }

    private fun implementCompletionBlockers(implementReadiness: ImplementStageReadiness): List<String> {
        return buildList {
            if (!implementReadiness.taskSourceReady) {
                add(SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.taskSource"))
            }
            if (implementReadiness.progressBlocked) {
                add(SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.progress"))
            }
            if (implementReadiness.relatedFilesBlocked) {
                add(SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.relatedFiles"))
            }
        }
    }

    private fun resolveImplementationTasks(
        workflow: SpecWorkflow,
        implementDocument: SpecDocument? = workflow.getDocument(SpecPhase.IMPLEMENT),
    ): List<StructuredTask> {
        val persistedTasks = tasksService.parse(workflow.id)
        if (persistedTasks.isNotEmpty()) {
            return persistedTasks
        }
        val currentDocument = implementDocument ?: return emptyList()
        return tasksService.parseDocument(currentDocument.content).tasksById
    }

    private fun requirementsMissingSectionQuickFixes(
        missingSections: List<RequirementsSectionId>,
    ): List<GateQuickFixDescriptor> {
        val payload = MissingRequirementsSectionsQuickFixPayload(missingSections)
        val aiUnavailableReason = RequirementsSectionAiSupport.unavailableReason()
        return listOf(
            GateQuickFixDescriptor(
                kind = GateQuickFixKind.AI_FILL_MISSING_REQUIREMENTS_SECTIONS,
                payload = payload,
                enabled = aiUnavailableReason == null,
                disabledReason = aiUnavailableReason,
            ),
            GateQuickFixDescriptor(
                kind = GateQuickFixKind.CLARIFY_THEN_FILL_REQUIREMENTS_SECTIONS,
                payload = payload,
            ),
            GateQuickFixDescriptor(
                kind = GateQuickFixKind.OPEN_FOR_MANUAL_EDIT,
                payload = payload,
            ),
        )
    }

    private fun tasksArtifactRepairQuickFixes(tasksMarkdown: String?): List<GateQuickFixDescriptor> {
        if (tasksMarkdown.isNullOrBlank()) {
            return emptyList()
        }
        if (SpecTaskMarkdownParser.parse(tasksMarkdown).issues.isEmpty()) {
            return emptyList()
        }
        return listOf(
            GateQuickFixDescriptor(
                kind = GateQuickFixKind.REPAIR_TASKS_ARTIFACT,
            ),
        )
    }

    private fun buildStageTransitionRequest(
        workflow: SpecWorkflow,
        transitionType: StageTransitionType,
        targetStage: StageId,
        evaluatedStages: List<StageId>,
        stagePlan: WorkflowStagePlan,
    ): StageTransitionRequest {
        return StageTransitionRequest(
            workflowId = workflow.id,
            transitionType = transitionType,
            fromStage = workflow.currentStage,
            targetStage = targetStage,
            evaluatedStages = evaluatedStages.distinct(),
            stagePlan = stagePlan,
            workflow = workflow,
        )
    }

    private fun hydrateMissingTransitionDocuments(
        workflow: SpecWorkflow,
        evaluatedStages: List<StageId>,
    ): SpecWorkflow {
        val hydratedDocuments = evaluatedStages
            .distinct()
            .mapNotNull { stageId ->
                val phase = stageDocumentPhaseOrNull(stageId) ?: return@mapNotNull null
                if (workflow.documents.containsKey(phase)) {
                    return@mapNotNull null
                }
                val content = artifactService.readArtifact(workflow.id, stageId) ?: return@mapNotNull null
                val draft = SpecDocument(
                    id = "hydrated-${workflow.id}-${phase.name.lowercase()}",
                    phase = phase,
                    content = content,
                    metadata = SpecMetadata(
                        title = "${phase.displayName} Document",
                        description = workflow.description.ifBlank { "Hydrated from persisted artifact" },
                    ),
                )
                phase to draft.copy(validationResult = SpecValidator.validate(draft))
            }
            .toMap()
        if (hydratedDocuments.isEmpty()) {
            return workflow
        }
        return workflow.copy(documents = workflow.documents + hydratedDocuments)
    }

    private fun stageDocumentPhaseOrNull(stageId: StageId): SpecPhase? {
        return when (stageId) {
            StageId.REQUIREMENTS -> SpecPhase.SPECIFY
            StageId.DESIGN -> SpecPhase.DESIGN
            StageId.TASKS -> SpecPhase.IMPLEMENT
            StageId.IMPLEMENT,
            StageId.VERIFY,
            StageId.ARCHIVE,
            -> null
        }
    }

    private fun prepareStageTransition(
        workflowId: String,
        transitionType: StageTransitionType,
        requestedTargetStage: StageId? = null,
    ): PreparedStageTransition {
        val loadedWorkflow = activeWorkflows[workflowId]
            ?: storage.loadWorkflow(workflowId).getOrThrow()
        val projectConfig = projectConfigLoader()
        val stagePlan = resolveStagePlan(loadedWorkflow, projectConfig)
        val workflow = hydrateMissingTransitionDocuments(
            workflow = loadedWorkflow,
            evaluatedStages = listOf(loadedWorkflow.currentStage),
        )

        return when (transitionType) {
            StageTransitionType.ADVANCE -> {
                val targetStage = stagePlan.nextActiveStage(workflow.currentStage)
                    ?: throw IllegalStateException("Already at the last active stage")
                PreparedStageTransition(
                    workflow = workflow,
                    stagePlan = stagePlan,
                    gatePolicy = projectConfig.gate,
                    targetStage = targetStage,
                    evaluatedStages = listOf(workflow.currentStage),
                )
            }

            StageTransitionType.JUMP -> {
                throw ManualStageMutationLockedError(workflowId, StageTransitionType.JUMP)
            }

            StageTransitionType.ROLLBACK -> {
                throw ManualStageMutationLockedError(workflowId, StageTransitionType.ROLLBACK)
            }
        }
    }

    private fun markCurrentStageCompleted(
        workflow: SpecWorkflow,
        timestampMillis: Long,
    ): Map<StageId, StageState> {
        val timestamp = Instant.ofEpochMilli(timestampMillis).toString()
        val updated = workflow.stageStates.toMutableMap()
        val currentState = updated[workflow.currentStage] ?: StageState(
            active = true,
            status = StageProgress.IN_PROGRESS,
        )
        updated[workflow.currentStage] = currentState.copy(
            active = true,
            status = StageProgress.DONE,
            enteredAt = currentState.enteredAt ?: timestamp,
            completedAt = timestamp,
        )
        return updated
    }

    private fun emitSpecStageChangedHook(
        workflowId: String,
        previousPhase: SpecPhase,
        currentPhase: SpecPhase,
        previousStage: StageId? = null,
        currentStage: StageId? = null,
    ) {
        runCatching {
            val metadata = linkedMapOf(
                "workflowId" to workflowId,
                "previousStage" to previousPhase.name,
                "currentStage" to currentPhase.name,
            )
            previousStage?.let { stage -> metadata["previousWorkflowStage"] = stage.name }
            currentStage?.let { stage -> metadata["currentWorkflowStage"] = stage.name }
            HookManager.getInstance(project).trigger(
                event = HookEvent.SPEC_STAGE_CHANGED,
                triggerContext = HookTriggerContext(
                    specStage = currentPhase.name,
                    metadata = metadata,
                ),
            )
        }.onFailure { error ->
            logger.warn(
                "Failed to emit SPEC_STAGE_CHANGED hook for workflow=$workflowId " +
                    "(${previousPhase.name} -> ${currentPhase.name})",
                error,
            )
        }
    }

    private data class StageMetadataState(
        val currentStage: StageId,
        val verifyEnabled: Boolean,
        val stageStates: Map<StageId, StageState>,
    )

    private data class PreparedStageTransition(
        val workflow: SpecWorkflow,
        val stagePlan: WorkflowStagePlan,
        val gatePolicy: SpecGatePolicy,
        val targetStage: StageId,
        val evaluatedStages: List<StageId>,
    )

    private companion object {
        private const val STAGE_COMPLETION_RULE_ID = "stage-completion-checks"
        private val REQUIRED_DESIGN_SECTION_MARKERS = listOf(
            listOf("## Architecture Design", "## 架构设计"),
            listOf("## Technology Choices", "## 技术选型"),
            listOf("## Data Model", "## 数据模型"),
            listOf("## API Design", "## API 设计"),
            listOf(
                "## Non-Functional Design",
                "## 非功能设计",
                "## Non-Functional Requirements",
                "## 非功能需求",
            ),
        )
    }
}
