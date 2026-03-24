package com.eacape.speccodingplugin.spec

/**
 * Task 00 architecture contract:
 * - defines layering boundaries for the spec subsystem
 * - tracks dependency decisions (Markdown AST / YAML / security / ULID)
 * - provides import guardrails enforced by tests
 */
object SpecArchitectureContract {

    enum class Layer {
        DOMAIN,
        APPLICATION,
        INFRASTRUCTURE,
    }

    enum class AdoptionStatus {
        ADOPTED,
        PLANNED,
    }

    data class DependencyDecision(
        val key: String,
        val capability: String,
        val selection: String,
        val rationale: String,
        val status: AdoptionStatus,
    )

    data class SourceRule(
        val fileName: String,
        val layer: Layer,
        val blockedImportPrefixes: Set<String> = emptySet(),
    )

    private val globalBlockedImportPrefixes = setOf(
        "com.eacape.speccodingplugin.ui.",
        "com.eacape.speccodingplugin.window.",
    )

    private val domainBlockedImportPrefixes = setOf(
        "com.intellij.",
        "com.eacape.speccodingplugin.core.",
        "com.eacape.speccodingplugin.hook.",
        "com.eacape.speccodingplugin.llm.",
        "kotlinx.coroutines.",
    )

    private val infrastructureBlockedImportPrefixes = setOf(
        "com.eacape.speccodingplugin.core.",
        "com.eacape.speccodingplugin.hook.",
        "com.eacape.speccodingplugin.llm.",
    )

    val dependencyDecisions: List<DependencyDecision> = listOf(
        DependencyDecision(
            key = "workflow-id",
            capability = "workflow id generation with sortable and low-collision format",
            selection = "built-in ULID style generator `WorkflowIdGenerator`",
            rationale = "avoid extra dependencies and keep offline deterministic behavior.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "yaml-codec",
            capability = "project config and spec-task yaml encoding/decoding",
            selection = "SnakeYAML with `SpecYamlCodec` safety restrictions",
            rationale = "allow only scalar/sequence/mapping and stable serialization.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "project-config",
            capability = "project-level config loading, validation, and default merge",
            selection = "SpecProjectConfigService + typed domain config models",
            rationale = "Task 09 requires schema-versioned parsing for template/gate/rule strategies.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "config-pin",
            capability = "workflow-bound config hash pinning and snapshot archival",
            selection = "SpecProjectConfigService deterministic SHA-256 pin + workflow `.history/config` snapshots",
            rationale = "Task 10 requires config hash binding to workflow metadata and immutable snapshot traceability.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "markdown-ast",
            capability = "markdown structural parsing and stable source locations",
            selection = "org.intellij.markdown AST + `SpecMarkdownAstParser`",
            rationale = "stable line mapping and code fence extraction with CRLF/LF consistency.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "security",
            capability = "storage and audit security hardening",
            selection = "atomic writes + lock manager + append-only YAML audit stream + project-root-safe path normalization + sanitized verify command auditing",
            rationale = "Task 04/05/08 establish baseline hardening; Task 51 extends it with relatedFiles path input guards, non-secret verify audit metadata, broader redaction coverage, and stricter YAML stream restrictions.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "artifact-service",
            capability = "artifact locate/read/write and template-driven skeleton bootstrap",
            selection = "SpecArtifactService + AtomicFileIO + workflow lock",
            rationale = "Task 11 requires reusable artifact operations and deterministic missing-artifact scaffolding.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "workflow-snapshot",
            capability = "before/after operation snapshots and delta baseline references",
            selection = "SpecStorage snapshots in `.history/snapshots` + baseline pointers in `.history/baselines`",
            rationale = "Task 12 requires key operation snapshots and reusable delta baseline references.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "workflow-metadata",
            capability = "workflow metadata persistence and retrieval for create/list/open operations",
            selection = "workflow.yaml stage metadata (`template/currentStage/stageStates`) + storage query APIs",
            rationale = "Task 13 requires durable template and stage-state records with list/open retrieval.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "schema-evolution",
            capability = "versioned config/workflow metadata parsing with bounded compatibility and explicit upgrade hooks",
            selection = "SpecSchemaVersioning registry + config/workflow metadata upgrade steps wired into SpecProjectConfigService and SpecStorage",
            rationale = "Task 50 requires schemaVersion evolution to stay non-destructive for older `.spec-coding` data while keeping current writes explicit and testable.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "stage-plan",
            capability = "template-aware ordered stage planning with optional-stage activation and gate scoping",
            selection = "domain `WorkflowStagePlan` + `StageActivationOptions` derived from template definitions and persisted stage states",
            rationale = "Task 14 requires a reusable stage-plan engine so optional stages and non-active artifacts stay out of gate scope.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "stage-transition",
            capability = "workflow stage advance/jump/rollback state machine with gate integration and auditable metadata-only persistence",
            selection = "SpecEngine transition coordinator + typed `StageTransitionResult` + storage-backed transition snapshots",
            rationale = "Task 15 requires stage-aware transitions, warning confirmation, and dedicated transition audit records without rewriting artifacts.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "template-switch-preview",
            capability = "template switch impact preview with stable preview id and artifact backfill strategies",
            selection = "SpecEngine preview cache + domain `TemplateSwitchPreview` + artifact requirement inspection",
            rationale = "Task 16 requires previewing stage activation diffs, gate scope changes, and missing artifact handling before apply/rollback lands in Task 17.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "template-switch-apply",
            capability = "template switch apply/rollback with snapshot-backed metadata restore and audit traceability",
            selection = "SpecEngine apply/rollback + SpecStorage audit event lookup + workflow metadata snapshots",
            rationale = "Task 17 requires confirmed preview application, non-destructive artifact preservation, and rollback to pre-switch metadata.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "rule-framework",
            capability = "configurable gate rule abstraction with explainable results and severity overrides",
            selection = "SpecGateRuleEngine + Rule/RuleContext abstractions + per-rule config severity overrides",
            rationale = "Task 18 requires reusable rule evaluation, config-driven enable/disable, and structured outputs with remediation hints.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "artifact-gate-rules",
            capability = "baseline gate rules for required artifacts and canonical fixed file naming",
            selection = "RequiredArtifactRule + FixedArtifactNamingRule backed by SpecArtifactService workflow artifact inspection",
            rationale = "Task 19 requires active-stage artifact presence checks and precise rename guidance when users create task.md/verify.md or case-mismatched files.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "tasks-gate-rules",
            capability = "structured tasks.md parsing plus task syntax/dependency/state gate rules",
            selection = "SpecTaskMarkdownParser + TasksSyntaxRule/TaskUniqueIdRule/TaskDependencyExistsRule/TaskStateConsistencyRule",
            rationale = "Task 20 requires tasks.md to be machine-validated for spec-task syntax, unique ids, dependency integrity, and dependency-aware status consistency.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "verify-gate-rules",
            capability = "VERIFY gate mapping from task verification conclusions with auditable downgrade handling",
            selection = "VerifyConclusionRule + task verificationResult parsing + SpecEngine downgrade audit event emission",
            rationale = "Task 21 requires PASS/WARN/FAIL verification conclusions to map into gate outcomes while recording any severity downgrade in audit history.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "gate-aggregation",
            capability = "structured gate aggregation and explicit warning confirmation audit for UI-facing workflows",
            selection = "GateResult aggregation/warning confirmation models + SpecEngine gate preview API + dedicated warning confirmation audit event",
            rationale = "Task 22 requires warning pass-through callbacks, auditable confirmation records, and structured gate output that later UI actions/tool windows can render directly.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "tasks-service",
            capability = "tasks.md parsing and task mutation while preserving handwritten markdown sections",
            selection = "SpecTasksService backed by SpecTaskMarkdownParser + Markdown section slicing",
            rationale = "Task 23/24 require structured task parsing plus add/remove edits with max+1 id allocation and deterministic tasks.md output without losing user-authored markdown bodies.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "task-status-transitions",
            capability = "strict task status transitions with spec-task-only writes and audit history",
            selection = "SpecTasksService transitionStatus + metadata-only code fence patching + TASK_STATUS_CHANGED audit append",
            rationale = "Task 25 requires enforcing the task state machine while preserving handwritten markdown and recording auditable before/after status changes with optional reasons.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "task-reference-normalization",
            capability = "canonical dependsOn and relatedFiles updates with project-root-safe path normalization",
            selection = "SpecTasksService updateDependsOn/updateRelatedFiles + sorted distinct dependency ids + project-relative relatedFiles canonicalization",
            rationale = "Task 26 requires deduplicated dependency ids, stable ordering, and related file paths normalized under the project root before writing back to tasks.md.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "task-verification-results",
            capability = "task-scoped verificationResult writes and clearing with stable YAML serialization",
            selection = "SpecTasksService updateVerificationResult/clearVerificationResult + SpecYamlCodec-backed verificationResult rendering",
            rationale = "Task 27 requires task-associated verification summaries to be written back or cleared safely, while preserving handwritten markdown and serializing special characters deterministically.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "verify-command-runner",
            capability = "parameterized local VERIFY command execution with timeout, truncation, redaction, and project-root-safe working directories",
            selection = "SpecProcessRunner + typed verify config commands + structured execution request/result models",
            rationale = "Task 28 requires a reusable process runner primitive that stays offline, avoids shell injection, and normalizes execution behavior across operating systems.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "verify-plan-preview",
            capability = "VERIFY plan preview with plan ids, policy summaries, and workflow-pin-aware command resolution",
            selection = "SpecVerificationService + pending plan cache + SpecProcessRunner.prepare() + pinned config snapshot fallback",
            rationale = "Task 29 requires users to preview normalized VERIFY commands, inspect working directory/timeout/redaction policy, and carry a planId into the later confirmed execution flow.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "verify-run-execution",
            capability = "confirmed VERIFY execution with verification.md generation, task verificationResult updates, and audit logging",
            selection = "SpecVerificationService.run() + SpecProcessRunner.execute() + verification.md renderer + task-scoped verificationResult writes",
            rationale = "Task 30 requires the confirmed verify plan to execute locally, persist structured verification evidence, update scoped tasks, and leave an auditable completion event.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "verify-action-entry",
            capability = "IDE action entry for VERIFY preview, explicit confirmation, execution summary, and verification.md navigation",
            selection = "RunSpecWorkflowVerificationAction + SpecWorkflowActionSupport verification summaries/dialogs + background execution via SpecVerificationService",
            rationale = "Task 33 requires a minimal but usable IDE entry point that previews VERIFY plans, keeps I/O off the EDT, and lets users jump straight to verification.md after execution.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "toolwindow-overview-mvp",
            capability = "ToolWindow workflow overview with current stage, advance gate summary, and refresh-aware state rendering",
            selection = "SpecWorkflowOverviewPresenter/Panel + SpecWorkflowPanel refresh hooks + advance gate preview via SpecEngine.previewStageTransition()",
            rationale = "Task 34 requires the existing Specs ToolWindow to surface a minimal workflow dashboard without blocking the EDT, while staying aligned with the real stage metadata and Gate aggregation results.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "toolwindow-stage-stepper",
            capability = "ToolWindow stage stepper with active/inactive stage visibility and in-place advance/jump/rollback controls",
            selection = "SpecWorkflowOverviewPresenter stepper state + SpecWorkflowStageStepperPanel + SpecWorkflowPanel callbacks reusing SpecEngine transition previews/execution and existing Gate dialogs",
            rationale = "Task 35 requires the ToolWindow to expose the real workflow stage plan, including inactive optional stages, while reusing the same transition and Gate semantics already established by the Actions flow.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "delta-report-computation",
            capability = "baseline-aware delta computation with artifact diff, task summary, relatedFiles summary, verification summary, and auditable baseline selection",
            selection = "SpecDeltaService + SpecDeltaCalculator + workflow snapshot/baseline artifact loading for verification.md + task metadata comparison",
            rationale = "Task 43 requires repeatable delta reports derived from persisted artifacts, tasks metadata, and selected baselines without relying on transient UI state.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "delta-report-export",
            capability = "offline delta report export in replayable markdown and html formats",
            selection = "SpecDeltaService exportMarkdown/exportHtml/exportReport + deterministic YAML metadata + `.spec-coding/exports/delta` files + DELTA_EXPORTED audit",
            rationale = "Task 44 requires stable offline review artifacts that can be regenerated from persisted baseline references and current workflow files.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "performance-caching",
            capability = "content-addressed markdown/task parsing cache, incremental gate reuse, and cancellable long-running background work",
            selection = "small LRU caches in SpecMarkdownAstParser/SpecTaskMarkdownParser/SpecTasksService + rule fingerprint reuse in SpecGateRuleEngine + cancellable SpecWorkflowActionSupport background tasks",
            rationale = "Task 49 requires keeping 200-task workflows responsive by avoiding repeat parsing, reusing unchanged gate rule results, and letting users cancel long-running background spec actions.",
            status = AdoptionStatus.ADOPTED,
        ),
        DependencyDecision(
            key = "workspace-recovery",
            capability = "startup crash recovery and workspace self-check for orphan temp files, stale locks, and snapshot consistency",
            selection = "SpecWorkspaceRecoveryService + startup ProjectManagerListener + SpecFileLockManager/SpecStorage inspections",
            rationale = "Task 48 requires a bounded startup recovery pass that does not rewrite user artifacts, but surfaces stale locks and corrupted snapshots before they block later spec operations.",
            status = AdoptionStatus.ADOPTED,
        ),
    )

    val sourceRules: List<SourceRule> = listOf(
        SourceRule(
            fileName = "ArtifactDraftStateSupport.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "AtomicFileIO.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "RequirementsSectionAiSupport.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "RequirementsSectionRepairService.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "RequirementsSectionSupport.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecAuditLogCodec.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecArchitectureContract.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecArtifactQuickFixService.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecArtifactSourceCitationWriteback.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecRequirementsQuickFixService.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecArtifactService.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecArchiveModels.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecCodeContextModels.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecCodeContextService.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecCodeContextPromptRenderer.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecClarificationWriteback.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecDeltaModels.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecDeltaService.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecEngine.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecGenerator.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecGateRuleEngine.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecHistoryModels.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecMarkdownAstParser.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecMarkdownSanitizer.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecModels.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecProjectConfigModels.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecProjectConfigService.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecProcessRunner.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecRelatedFilesService.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecSchemaVersioning.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecStorage.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecTaskCompletionService.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecTaskDependencyRules.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecTaskExecutionService.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecTaskPlanningGroundingSupport.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecTaskMarkdownParser.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecTasksService.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecTasksQuickFixService.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecVerificationQuickFixService.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecVerificationService.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecFileLockManager.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecWorkspaceInitializer.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecWorkspaceRecoveryService.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "SpecYamlCodec.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "SpecValidator.kt",
            layer = Layer.APPLICATION,
        ),
        SourceRule(
            fileName = "WorkflowDomainModels.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "WorkflowChatExecutionLaunchPresentation.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "WorkflowIdGenerator.kt",
            layer = Layer.INFRASTRUCTURE,
            blockedImportPrefixes = infrastructureBlockedImportPrefixes,
        ),
        SourceRule(
            fileName = "WorkflowSourceImportSupport.kt",
            layer = Layer.DOMAIN,
            blockedImportPrefixes = domainBlockedImportPrefixes,
        ),
    )

    fun ruleFor(fileName: String): SourceRule? {
        return sourceRules.firstOrNull { it.fileName == fileName }
    }

    fun blockedImportPrefixesFor(fileName: String): Set<String> {
        val custom = ruleFor(fileName)?.blockedImportPrefixes.orEmpty()
        return globalBlockedImportPrefixes + custom
    }
}
