package com.eacape.speccodingplugin.spec

import java.nio.file.Path

/**
 * Workflow 模板枚举。
 */
enum class WorkflowTemplate {
    FULL_SPEC,
    QUICK_TASK,
    DESIGN_REVIEW,
    DIRECT_IMPLEMENT,
}

/**
 * 标准化阶段标识。
 */
enum class StageId(val artifactFileName: String?) {
    REQUIREMENTS("requirements.md"),
    DESIGN("design.md"),
    TASKS("tasks.md"),
    IMPLEMENT(null),
    VERIFY("verification.md"),
    ARCHIVE(null),
    ;

    fun requiresArtifact(): Boolean = artifactFileName != null
}

data class StagePlanItem(
    val id: StageId,
    val optional: Boolean = false,
    val defaultEnabled: Boolean = !optional,
) {
    fun isEnabled(options: StageActivationOptions = StageActivationOptions()): Boolean {
        return if (!optional) {
            true
        } else {
            options.overrideFor(id) ?: defaultEnabled
        }
    }
}

data class StageActivationOptions(
    val stageOverrides: Map<StageId, Boolean> = emptyMap(),
) {
    fun overrideFor(stageId: StageId): Boolean? = stageOverrides[stageId]

    companion object {
        fun of(
            verifyEnabled: Boolean? = null,
            implementEnabled: Boolean? = null,
        ): StageActivationOptions {
            val overrides = linkedMapOf<StageId, Boolean>()
            verifyEnabled?.let { overrides[StageId.VERIFY] = it }
            implementEnabled?.let { overrides[StageId.IMPLEMENT] = it }
            return StageActivationOptions(stageOverrides = overrides)
        }

        fun fromStageStates(stageStates: Map<StageId, StageState>): StageActivationOptions {
            return StageActivationOptions(
                stageOverrides = stageStates.mapValues { (_, state) -> state.active },
            )
        }
    }
}

enum class StageProgress {
    NOT_STARTED,
    IN_PROGRESS,
    DONE,
}

data class StageState(
    val active: Boolean,
    val status: StageProgress,
    val enteredAt: String? = null,
    val completedAt: String? = null,
)

data class WorkflowMeta(
    val workflowId: String,
    val title: String?,
    val template: WorkflowTemplate,
    val stageStates: Map<StageId, StageState>,
    val currentStage: StageId,
    val verifyEnabled: Boolean,
    val configPinHash: String?,
    val baselineWorkflowId: String?,
    val artifactDraftStates: Map<StageId, ArtifactDraftState>,
    val status: WorkflowStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

data class WorkflowSourceAsset(
    val sourceId: String,
    val originalFileName: String,
    val storedRelativePath: String,
    val mediaType: String,
    val fileSize: Long,
    val contentHash: String,
    val importedAt: String,
    val importedFromStage: StageId,
    val importedFromEntry: String,
)

data class ArtifactSourceCitation(
    val sourceId: String,
    val storedRelativePath: String,
    val locator: String? = null,
    val note: String? = null,
)

data class WorkflowSnapshot(
    val meta: WorkflowMeta,
    val workflow: SpecWorkflow,
    val documents: Map<SpecPhase, SpecDocument>,
)

/**
 * 模板定义：固定阶段顺序 + 可选阶段默认启用策略。
 */
data class TemplateDefinition(
    val template: WorkflowTemplate,
    val stagePlan: List<StagePlanItem>,
) {
    init {
        require(stagePlan.isNotEmpty()) { "stagePlan must not be empty" }
        require(stagePlan.map { it.id }.distinct().size == stagePlan.size) {
            "stagePlan contains duplicated stage ids"
        }
        require(stagePlan.last().id == StageId.ARCHIVE) {
            "template $template must end with ARCHIVE stage"
        }
    }

    fun activeStages(
        verifyEnabled: Boolean? = null,
        implementEnabled: Boolean? = null,
    ): List<StageId> {
        return buildStagePlan(
            StageActivationOptions.of(
                verifyEnabled = verifyEnabled,
                implementEnabled = implementEnabled,
            ),
        ).activeStages
    }

    fun buildStagePlan(
        options: StageActivationOptions = StageActivationOptions(),
    ): WorkflowStagePlan {
        val orderedStages = stagePlan.map { it.id }
        val activeStages = stagePlan
            .filter { item -> item.isEnabled(options) }
            .map { it.id }
        return WorkflowStagePlan(
            template = template,
            orderedStages = orderedStages,
            activeStages = activeStages,
        )
    }
}

data class WorkflowStagePlan(
    val template: WorkflowTemplate,
    val orderedStages: List<StageId>,
    val activeStages: List<StageId>,
) {
    init {
        require(orderedStages.isNotEmpty()) { "orderedStages must not be empty" }
        require(activeStages.isNotEmpty()) { "activeStages must not be empty for template $template" }
        require(activeStages.all { orderedStages.contains(it) }) {
            "activeStages must be a subset of orderedStages for template $template"
        }
    }

    val inactiveStages: List<StageId>
        get() = orderedStages.filterNot { isActive(it) }

    val gateArtifactStages: List<StageId>
        get() = activeStages.filter { it.requiresArtifact() }

    val firstActiveStage: StageId
        get() = activeStages.first()

    fun isActive(stageId: StageId): Boolean = activeStages.contains(stageId)

    fun activeStageIndex(stageId: StageId): Int {
        val index = activeStages.indexOf(stageId)
        require(index >= 0) {
            "stage $stageId is not active for template $template"
        }
        return index
    }

    fun activeStagesBefore(stageId: StageId): List<StageId> {
        return activeStages.take(activeStageIndex(stageId))
    }

    fun activeStagesThrough(stageId: StageId): List<StageId> {
        return activeStages.take(activeStageIndex(stageId) + 1)
    }

    fun activeStagesBetween(from: StageId, to: StageId): List<StageId> {
        val fromIndex = activeStageIndex(from)
        val toIndex = activeStageIndex(to)
        return if (fromIndex <= toIndex) {
            activeStages.subList(fromIndex, toIndex + 1)
        } else {
            activeStages.subList(toIndex, fromIndex + 1)
        }
    }

    fun participatesInGate(stageId: StageId): Boolean {
        return stageId.requiresArtifact() && isActive(stageId)
    }

    fun nextActiveStage(current: StageId): StageId? {
        return orderedStages
            .dropWhile { it != current }
            .drop(1)
            .firstOrNull { isActive(it) }
    }

    fun previousActiveStage(current: StageId): StageId? {
        return orderedStages
            .takeWhile { it != current }
            .lastOrNull { isActive(it) }
    }

    fun resolveCurrentStage(preferred: StageId): StageId {
        if (isActive(preferred)) {
            return preferred
        }
        val preferredIndex = StageId.entries.indexOf(preferred)
        return activeStages.firstOrNull { StageId.entries.indexOf(it) > preferredIndex }
            ?: activeStages.lastOrNull { StageId.entries.indexOf(it) < preferredIndex }
            ?: firstActiveStage
    }

    fun initialStageStates(enteredAt: String): Map<StageId, StageState> {
        val states = linkedMapOf<StageId, StageState>()
        StageId.entries.forEach { stageId ->
            val active = isActive(stageId)
            states[stageId] = if (stageId == firstActiveStage) {
                StageState(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                    enteredAt = enteredAt,
                    completedAt = null,
                )
            } else {
                StageState(
                    active = active,
                    status = StageProgress.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                )
            }
        }
        return states
    }
}

object WorkflowTemplates {
    private val defaultDefinitions: Map<WorkflowTemplate, TemplateDefinition> = mapOf(
        WorkflowTemplate.FULL_SPEC to TemplateDefinition(
            template = WorkflowTemplate.FULL_SPEC,
            stagePlan = listOf(
                StagePlanItem(StageId.REQUIREMENTS),
                StagePlanItem(StageId.DESIGN),
                StagePlanItem(StageId.TASKS),
                StagePlanItem(StageId.IMPLEMENT),
                StagePlanItem(StageId.VERIFY, optional = true, defaultEnabled = false),
                StagePlanItem(StageId.ARCHIVE),
            ),
        ),
        WorkflowTemplate.QUICK_TASK to TemplateDefinition(
            template = WorkflowTemplate.QUICK_TASK,
            stagePlan = listOf(
                StagePlanItem(StageId.TASKS),
                StagePlanItem(StageId.IMPLEMENT),
                StagePlanItem(StageId.VERIFY, optional = true, defaultEnabled = false),
                StagePlanItem(StageId.ARCHIVE),
            ),
        ),
        WorkflowTemplate.DESIGN_REVIEW to TemplateDefinition(
            template = WorkflowTemplate.DESIGN_REVIEW,
            stagePlan = listOf(
                StagePlanItem(StageId.DESIGN),
                StagePlanItem(StageId.TASKS),
                StagePlanItem(StageId.IMPLEMENT, optional = true, defaultEnabled = true),
                StagePlanItem(StageId.VERIFY, optional = true, defaultEnabled = false),
                StagePlanItem(StageId.ARCHIVE),
            ),
        ),
        WorkflowTemplate.DIRECT_IMPLEMENT to TemplateDefinition(
            template = WorkflowTemplate.DIRECT_IMPLEMENT,
            stagePlan = listOf(
                StagePlanItem(StageId.IMPLEMENT),
                StagePlanItem(StageId.VERIFY, optional = true, defaultEnabled = false),
                StagePlanItem(StageId.ARCHIVE),
            ),
        ),
    )

    fun definitionOf(template: WorkflowTemplate): TemplateDefinition = defaultDefinitions.getValue(template)

    fun defaultDefinitions(): Map<WorkflowTemplate, TemplateDefinition> = defaultDefinitions.toMap()
}

enum class GateStatus {
    PASS,
    WARNING,
    ERROR,
}

enum class GateQuickFixKind {
    REPAIR_REQUIREMENTS_ARTIFACT,
    REPAIR_TASKS_ARTIFACT,
    AI_FILL_MISSING_REQUIREMENTS_SECTIONS,
    CLARIFY_THEN_FILL_REQUIREMENTS_SECTIONS,
    OPEN_FOR_MANUAL_EDIT,
}

sealed interface GateQuickFixPayload

data class MissingRequirementsSectionsQuickFixPayload(
    val missingSections: List<RequirementsSectionId>,
) : GateQuickFixPayload {
    init {
        require(missingSections.isNotEmpty()) { "missingSections cannot be empty." }
    }
}

data class GateQuickFixDescriptor(
    val kind: GateQuickFixKind,
    val payload: GateQuickFixPayload? = null,
    val enabled: Boolean = true,
    val disabledReason: String? = null,
) {
    init {
        if (!enabled) {
            require(!disabledReason.isNullOrBlank()) { "disabledReason is required when quick fix is disabled." }
        }
    }
}

data class Violation(
    val ruleId: String,
    val severity: GateStatus,
    val fileName: String,
    val line: Int,
    val message: String,
    val fixHint: String? = null,
    val originalSeverity: GateStatus? = null,
    val quickFixes: List<GateQuickFixDescriptor> = emptyList(),
)

data class GateAggregation(
    val totalViolationCount: Int,
    val errorCount: Int,
    val warningCount: Int,
    val passedRuleCount: Int,
    val warningRuleCount: Int,
    val errorRuleCount: Int,
    val errorViolations: List<Violation>,
    val warningViolations: List<Violation>,
    val summary: String,
    val canProceed: Boolean,
    val requiresWarningConfirmation: Boolean,
)

data class GateWarningConfirmation(
    val title: String,
    val message: String,
    val warnings: List<Violation>,
)

data class GateResult(
    val status: GateStatus,
    val violations: List<Violation>,
    val ruleResults: List<RuleEvaluationResult> = emptyList(),
    val aggregation: GateAggregation = GateAggregation(
        totalViolationCount = 0,
        errorCount = 0,
        warningCount = 0,
        passedRuleCount = 0,
        warningRuleCount = 0,
        errorRuleCount = 0,
        errorViolations = emptyList(),
        warningViolations = emptyList(),
        summary = "Gate passed.",
        canProceed = true,
        requiresWarningConfirmation = false,
    ),
    val warningConfirmation: GateWarningConfirmation? = null,
) {
    companion object {
        fun fromViolations(
            violations: List<Violation>,
            ruleResults: List<RuleEvaluationResult> = emptyList(),
        ): GateResult {
            val status = when {
                violations.any { it.severity == GateStatus.ERROR } -> GateStatus.ERROR
                violations.any { it.severity == GateStatus.WARNING } -> GateStatus.WARNING
                else -> GateStatus.PASS
            }
            val sortedViolations = violations.sortedWith(compareBy(Violation::fileName, Violation::line, Violation::ruleId))
            val aggregation = buildAggregation(
                status = status,
                violations = sortedViolations,
                ruleResults = ruleResults,
            )
            return GateResult(
                status = status,
                violations = sortedViolations,
                ruleResults = ruleResults.sortedBy(RuleEvaluationResult::ruleId),
                aggregation = aggregation,
                warningConfirmation = buildWarningConfirmation(aggregation),
            )
        }

        fun fromRuleResults(ruleResults: List<RuleEvaluationResult>): GateResult {
            return fromViolations(
                violations = ruleResults.flatMap(RuleEvaluationResult::violations),
                ruleResults = ruleResults,
            )
        }

        private fun buildAggregation(
            status: GateStatus,
            violations: List<Violation>,
            ruleResults: List<RuleEvaluationResult>,
        ): GateAggregation {
            val errorViolations = violations.filter { it.severity == GateStatus.ERROR }
            val warningViolations = violations.filter { it.severity == GateStatus.WARNING }
            val passRuleCount = if (ruleResults.isNotEmpty()) {
                ruleResults.count { evaluation -> evaluation.violations.isEmpty() }
            } else {
                0
            }
            val warningRuleCount = if (ruleResults.isNotEmpty()) {
                ruleResults.count { evaluation ->
                    evaluation.violations.any { violation -> violation.severity == GateStatus.WARNING } &&
                        evaluation.violations.none { violation -> violation.severity == GateStatus.ERROR }
                }
            } else {
                warningViolations.map(Violation::ruleId).distinct().size
            }
            val errorRuleCount = if (ruleResults.isNotEmpty()) {
                ruleResults.count { evaluation ->
                    evaluation.violations.any { violation -> violation.severity == GateStatus.ERROR }
                }
            } else {
                errorViolations.map(Violation::ruleId).distinct().size
            }
            val summary = when (status) {
                GateStatus.PASS -> "Gate passed with ${passRuleCount.coerceAtLeast(ruleResults.size)} rule(s)."
                GateStatus.WARNING -> "Gate requires warning confirmation: ${warningViolations.size} warning(s) across ${warningRuleCount.coerceAtLeast(1)} rule(s)."
                GateStatus.ERROR -> "Gate blocked: ${errorViolations.size} error(s) and ${warningViolations.size} warning(s)."
            }
            return GateAggregation(
                totalViolationCount = violations.size,
                errorCount = errorViolations.size,
                warningCount = warningViolations.size,
                passedRuleCount = passRuleCount,
                warningRuleCount = warningRuleCount,
                errorRuleCount = errorRuleCount,
                errorViolations = errorViolations,
                warningViolations = warningViolations,
                summary = summary,
                canProceed = status != GateStatus.ERROR,
                requiresWarningConfirmation = status == GateStatus.WARNING,
            )
        }

        private fun buildWarningConfirmation(aggregation: GateAggregation): GateWarningConfirmation? {
            if (!aggregation.requiresWarningConfirmation || aggregation.warningViolations.isEmpty()) {
                return null
            }
            return GateWarningConfirmation(
                title = "Confirm gate warnings",
                message = aggregation.summary,
                warnings = aggregation.warningViolations,
            )
        }
    }
}

enum class StageTransitionType {
    ADVANCE,
    JUMP,
    ROLLBACK,
}

data class StageTransitionRequest(
    val workflowId: String,
    val transitionType: StageTransitionType,
    val fromStage: StageId,
    val targetStage: StageId,
    val evaluatedStages: List<StageId>,
    val stagePlan: WorkflowStagePlan,
    val workflow: SpecWorkflow,
)

fun interface SpecStageGateEvaluator {
    fun evaluate(request: StageTransitionRequest): GateResult
}

data class StageTransitionResult(
    val workflow: SpecWorkflow,
    val transitionType: StageTransitionType,
    val fromStage: StageId,
    val targetStage: StageId,
    val gateResult: GateResult,
    val warningConfirmed: Boolean,
    val beforeSnapshotId: String? = null,
    val afterSnapshotId: String? = null,
)

data class StageTransitionGatePreview(
    val workflowId: String,
    val transitionType: StageTransitionType,
    val fromStage: StageId,
    val targetStage: StageId,
    val evaluatedStages: List<StageId>,
    val gateResult: GateResult,
)

enum class TemplateSwitchArtifactStrategy {
    REUSE_EXISTING,
    GENERATE_SKELETON,
    BLOCK_SWITCH,
}

data class TemplateSwitchArtifactImpact(
    val stageId: StageId,
    val fileName: String,
    val exists: Boolean,
    val strategy: TemplateSwitchArtifactStrategy,
)

data class TemplateSwitchPreview(
    val previewId: String,
    val workflowId: String,
    val fromTemplate: WorkflowTemplate,
    val toTemplate: WorkflowTemplate,
    val currentStage: StageId,
    val resultingStage: StageId,
    val addedActiveStages: List<StageId>,
    val deactivatedStages: List<StageId>,
    val gateAddedStages: List<StageId>,
    val gateRemovedStages: List<StageId>,
    val artifactImpacts: List<TemplateSwitchArtifactImpact>,
) {
    val currentStageChanged: Boolean
        get() = currentStage != resultingStage

    val missingRequiredArtifacts: List<TemplateSwitchArtifactImpact>
        get() = artifactImpacts.filterNot { it.exists }
}

data class TemplateSwitchApplyResult(
    val switchId: String,
    val previewId: String,
    val workflow: SpecWorkflow,
    val generatedArtifacts: List<String>,
    val beforeSnapshotId: String? = null,
    val afterSnapshotId: String? = null,
)

data class TemplateSwitchRollbackResult(
    val switchId: String,
    val workflow: SpecWorkflow,
    val restoredFromSnapshotId: String,
    val beforeSnapshotId: String? = null,
    val afterSnapshotId: String? = null,
)

data class TemplateCloneResult(
    val sourceWorkflowId: String,
    val previewId: String,
    val workflow: SpecWorkflow,
    val copiedArtifacts: List<String>,
    val generatedArtifacts: List<String>,
)

data class TemplateSwitchHistoryEntry(
    val switchId: String,
    val previewId: String? = null,
    val fromTemplate: WorkflowTemplate,
    val toTemplate: WorkflowTemplate,
    val occurredAt: String,
    val occurredAtEpochMs: Long,
    val beforeSnapshotId: String? = null,
    val generatedArtifacts: List<String> = emptyList(),
    val rolledBack: Boolean = false,
)

enum class TaskLifecycleStatus {
    PENDING,
    BLOCKED,
    COMPLETED,
    CANCELLED,
}

enum class TaskExecutionRunStatus {
    QUEUED,
    RUNNING,
    WAITING_CONFIRMATION,
    FAILED,
    CANCELLED,
    SUCCEEDED,
    ;

    fun isTerminal(): Boolean {
        return when (this) {
            FAILED,
            CANCELLED,
            SUCCEEDED,
            -> true
            QUEUED,
            RUNNING,
            WAITING_CONFIRMATION,
            -> false
        }
    }

    fun canTransitionTo(next: TaskExecutionRunStatus): Boolean {
        return when (this) {
            QUEUED -> next == RUNNING || next == CANCELLED || next == FAILED
            RUNNING -> next == WAITING_CONFIRMATION || next == SUCCEEDED || next == FAILED || next == CANCELLED
            WAITING_CONFIRMATION -> next == SUCCEEDED || next == FAILED || next == CANCELLED
            FAILED,
            CANCELLED,
            SUCCEEDED,
            -> false
        }
    }
}

enum class ExecutionTrigger {
    USER_EXECUTE,
    USER_RETRY,
    SYSTEM_RECOVERY,
}

enum class TaskPriority {
    P0,
    P1,
    P2,
}

enum class TaskStatus {
    PENDING,
    IN_PROGRESS,
    BLOCKED,
    COMPLETED,
    CANCELLED,
    ;

    fun canTransitionTo(next: TaskStatus): Boolean {
        return when (this) {
            PENDING -> next == BLOCKED || next == COMPLETED || next == CANCELLED
            IN_PROGRESS -> next == PENDING || next == BLOCKED || next == COMPLETED || next == CANCELLED
            BLOCKED -> next == PENDING || next == COMPLETED || next == CANCELLED
            COMPLETED -> false
            CANCELLED -> false
        }
    }
}

enum class VerificationConclusion {
    PASS,
    WARN,
    FAIL,
}

data class TaskVerificationResult(
    val conclusion: VerificationConclusion,
    val runId: String,
    val summary: String,
    val at: String,
)

data class TaskExecutionRun(
    val runId: String,
    val taskId: String,
    val status: TaskExecutionRunStatus,
    val trigger: ExecutionTrigger,
    val startedAt: String,
    val finishedAt: String? = null,
    val summary: String? = null,
)

enum class ExecutionLivePhase {
    QUEUED,
    PREPARING_CONTEXT,
    REQUEST_DISPATCHED,
    STREAMING,
    WAITING_CONFIRMATION,
    CANCELLING,
    TERMINAL,
}

data class TaskExecutionLiveProgress(
    val workflowId: String,
    val runId: String,
    val taskId: String,
    val phase: ExecutionLivePhase,
    val startedAt: java.time.Instant,
    val lastUpdatedAt: java.time.Instant,
    val lastDetail: String? = null,
    val recentEvents: List<com.eacape.speccodingplugin.stream.ChatStreamEvent> = emptyList(),
    val providerId: String? = null,
    val modelId: String? = null,
)

data class VerifyCommandExecutionRequest(
    val commandId: String,
    val displayName: String?,
    val command: List<String>,
    val workingDirectory: Path,
    val timeoutMs: Int,
    val outputLimitChars: Int,
    val redactionPatterns: List<String> = emptyList(),
)

data class VerifyCommandExecutionResult(
    val commandId: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val redacted: Boolean,
) {
    val truncated: Boolean
        get() = stdoutTruncated || stderrTruncated
}

enum class VerifyPlanConfigSource {
    WORKFLOW_PINNED,
    PROJECT_CONFIG,
}

data class VerifyPlanPolicy(
    val configSource: VerifyPlanConfigSource,
    val workflowConfigPinHash: String?,
    val effectiveConfigHash: String,
    val usesPinnedSnapshot: Boolean,
    val confirmationRequired: Boolean,
    val confirmationReasons: List<String>,
)

data class VerifyPlanCommand(
    val commandId: String,
    val displayName: String?,
    val command: List<String>,
    val workingDirectory: Path,
    val timeoutMs: Int,
    val outputLimitChars: Int,
    val redactionPatterns: List<String>,
)

data class VerifyPlan(
    val planId: String,
    val workflowId: String,
    val currentStage: StageId,
    val generatedAt: String,
    val commands: List<VerifyPlanCommand>,
    val policy: VerifyPlanPolicy,
)

data class VerifyRunResult(
    val runId: String,
    val workflowId: String,
    val planId: String,
    val currentStage: StageId,
    val executedAt: String,
    val conclusion: VerificationConclusion,
    val summary: String,
    val verificationDocumentPath: Path,
    val commandResults: List<VerifyCommandExecutionResult>,
    val updatedTasks: List<StructuredTask>,
)

data class VerifyRunHistoryEntry(
    val runId: String,
    val planId: String,
    val executedAt: String,
    val occurredAtEpochMs: Long,
    val currentStage: StageId,
    val conclusion: VerificationConclusion,
    val summary: String,
    val commandCount: Int,
    val scopeTaskIds: List<String> = emptyList(),
    val failedCommandIds: List<String> = emptyList(),
    val truncatedCommandIds: List<String> = emptyList(),
    val redactedCommandIds: List<String> = emptyList(),
)

data class StructuredTask(
    val id: String,
    val title: String,
    val status: TaskStatus,
    val priority: TaskPriority,
    val dependsOn: List<String> = emptyList(),
    val relatedFiles: List<String> = emptyList(),
    val verificationResult: TaskVerificationResult? = null,
    val activeExecutionRun: TaskExecutionRun? = null,
) {
    val displayStatus: TaskStatus
        get() = when {
            status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED -> status
            hasExecutionInFlight -> TaskStatus.IN_PROGRESS
            else -> status
        }

    val hasExecutionInFlight: Boolean
        get() = activeExecutionRun?.status?.isTerminal() == false

    val awaitingCompletionConfirmation: Boolean
        get() = activeExecutionRun?.status == TaskExecutionRunStatus.WAITING_CONFIRMATION
}

fun List<StructuredTask>.attachActiveExecutionRuns(runs: List<TaskExecutionRun>): List<StructuredTask> {
    if (isEmpty()) {
        return emptyList()
    }
    val activeRunsByTaskId = runs
        .asSequence()
        .filter { run -> !run.status.isTerminal() }
        .sortedBy(TaskExecutionRun::startedAt)
        .associateBy(TaskExecutionRun::taskId)
    return map { task ->
        task.copy(activeExecutionRun = activeRunsByTaskId[task.id])
    }
}

sealed class WorkflowDomainError(message: String) : IllegalStateException(message)

class InvalidStageTransitionError(from: StageId, to: StageId) :
    WorkflowDomainError("Invalid stage transition: $from -> $to")

class InactiveWorkflowStageError(stageId: StageId) :
    WorkflowDomainError("Stage $stageId is not active for the current workflow")

class StageTransitionPolicyError(message: String) :
    WorkflowDomainError(message)

class StageWarningConfirmationRequiredError(
    from: StageId,
    to: StageId,
    val gateResult: GateResult,
) :
    WorkflowDomainError("Stage transition $from -> $to requires explicit warning confirmation")

class StageTransitionBlockedByGateError(
    from: StageId,
    to: StageId,
    val gateResult: GateResult,
) : WorkflowDomainError("Stage transition $from -> $to blocked by gate status ${gateResult.status}")

class InvalidTaskStateTransitionError(taskId: String, from: TaskStatus, to: TaskStatus) :
    WorkflowDomainError("Invalid task state transition for $taskId: $from -> $to")

class TaskExecutionBlockedByDependenciesError(taskId: String, dependencyIds: List<String>) :
    WorkflowDomainError(
        "Task $taskId cannot execute until dependencies are COMPLETED: ${dependencyIds.joinToString(", ")}",
    )

class TaskCancellationBlockedByDependentsError(taskId: String, dependentTaskIds: List<String>) :
    WorkflowDomainError(
        "Task $taskId cannot be cancelled while dependent tasks are not CANCELLED: " +
            dependentTaskIds.joinToString(", "),
    )

class DuplicateTaskIdError(taskId: String) :
    WorkflowDomainError("Duplicate task id detected: $taskId")

class MissingTaskDependencyError(taskId: String, missingDependencyId: String) :
    WorkflowDomainError("Task $taskId depends on missing task id: $missingDependencyId")

class TaskSelfDependencyError(taskId: String) :
    WorkflowDomainError("Task $taskId cannot depend on itself")

class InvalidTaskRelatedFileError(taskId: String, rawPath: String, details: String? = null) :
    WorkflowDomainError(
        buildString {
            append("Task ")
            append(taskId)
            append(" has invalid related file path: ")
            append(rawPath)
            details
                ?.takeIf(String::isNotBlank)
                ?.let { reason ->
                    append(" (")
                    append(reason)
                    append(')')
                }
        },
    )

class RelatedFileOutsideProjectRootError(taskId: String, rawPath: String, projectRoot: String) :
    WorkflowDomainError("Task $taskId related file escapes project root $projectRoot: $rawPath")

class InvalidTaskVerificationResultError(taskId: String, fieldName: String, details: String? = null) :
    WorkflowDomainError(
        buildString {
            append("Task ")
            append(taskId)
            append(" has invalid verificationResult field `")
            append(fieldName)
            append('`')
            details
                ?.takeIf(String::isNotBlank)
                ?.let { reason ->
                    append(": ")
                    append(reason)
                }
        },
    )

class MissingTaskExecutionRunError(runId: String) :
    WorkflowDomainError("Task execution run $runId was not found")

class ActiveTaskExecutionRunExistsError(taskId: String) :
    WorkflowDomainError("Task $taskId already has an active execution run")

class InvalidTaskExecutionRunTransitionError(
    runId: String,
    from: TaskExecutionRunStatus,
    to: TaskExecutionRunStatus,
) : WorkflowDomainError("Invalid task execution run transition: $runId $from -> $to")

class InvalidVerifyCommandError(commandId: String?, details: String) :
    WorkflowDomainError(
        buildString {
            append("Verify command")
            commandId
                ?.takeIf(String::isNotBlank)
                ?.let { normalizedId ->
                    append(' ')
                    append(normalizedId)
                }
            append(" is invalid: ")
            append(details)
        },
    )

class VerifyCommandWorkingDirectoryError(commandId: String, workingDirectory: String, projectRoot: String) :
    WorkflowDomainError(
        "Verify command $commandId working directory escapes project root $projectRoot: $workingDirectory",
    )

class VerifyStageDisabledError(workflowId: String) :
    WorkflowDomainError("Workflow $workflowId does not have VERIFY enabled")

class MissingVerifyPlanError(planId: String) :
    WorkflowDomainError("Verify plan not found: $planId")

class StaleVerifyPlanError(planId: String, workflowId: String) :
    WorkflowDomainError("Verify plan $planId is stale for workflow $workflowId")

class InvalidTasksArtifactEditError(details: String) :
    WorkflowDomainError("tasks.md cannot be edited safely: $details")

class MissingStructuredTaskError(taskId: String) :
    WorkflowDomainError("Task not found: $taskId")

class MissingTemplateSwitchPreviewError(previewId: String) :
    WorkflowDomainError("Template switch preview not found: $previewId")

class StaleTemplateSwitchPreviewError(previewId: String, workflowId: String) :
    WorkflowDomainError("Template switch preview $previewId is stale for workflow $workflowId")

class MissingTemplateSwitchHistoryError(workflowId: String, switchId: String) :
    WorkflowDomainError("Template switch $switchId not found for workflow $workflowId")

class TemplateMutationLockedError(workflowId: String) :
    WorkflowDomainError(
        "Workflow $workflowId locks its template after creation; create a migrated copy instead of switching in place",
    )

class ManualStageMutationLockedError(workflowId: String, transitionType: StageTransitionType) :
    WorkflowDomainError(
        "Workflow $workflowId no longer allows manual ${transitionType.name.lowercase()} stage changes; " +
            "revise artifacts or tasks and advance through completion checks instead",
    )
