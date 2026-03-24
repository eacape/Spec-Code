package com.eacape.speccodingplugin.spec

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant

@Service(Service.Level.PROJECT)
class SpecVerificationService(private val project: Project) {
    private var _storageOverride: SpecStorage? = null
    private var _projectConfigServiceOverride: SpecProjectConfigService? = null
    private var _processRunnerOverride: SpecProcessRunner? = null
    private var _planIdGeneratorOverride: WorkflowIdGenerator? = null
    private var _runIdGeneratorOverride: WorkflowIdGenerator? = null

    private val storage: SpecStorage by lazy { _storageOverride ?: SpecStorage.getInstance(project) }
    private val projectConfigService: SpecProjectConfigService by lazy {
        _projectConfigServiceOverride ?: SpecProjectConfigService(project)
    }
    private val artifactService: SpecArtifactService by lazy { SpecArtifactService(project) }
    private val tasksService: SpecTasksService by lazy { SpecTasksService(project) }
    private val processRunner: SpecProcessRunner by lazy { _processRunnerOverride ?: SpecProcessRunner() }
    private val planIdGenerator: WorkflowIdGenerator by lazy {
        _planIdGeneratorOverride ?: WorkflowIdGenerator(prefix = VERIFY_PLAN_ID_PREFIX)
    }
    private val runIdGenerator: WorkflowIdGenerator by lazy {
        _runIdGeneratorOverride ?: WorkflowIdGenerator(prefix = VERIFY_RUN_ID_PREFIX)
    }
    private val pendingPlans = mutableMapOf<String, PendingVerifyPlan>()

    internal constructor(
        project: Project,
        storage: SpecStorage,
        projectConfigService: SpecProjectConfigService = SpecProjectConfigService(project),
        processRunner: SpecProcessRunner = SpecProcessRunner(),
        planIdGenerator: WorkflowIdGenerator = WorkflowIdGenerator(prefix = VERIFY_PLAN_ID_PREFIX),
        runIdGenerator: WorkflowIdGenerator = WorkflowIdGenerator(prefix = VERIFY_RUN_ID_PREFIX),
    ) : this(project) {
        _storageOverride = storage
        _projectConfigServiceOverride = projectConfigService
        _processRunnerOverride = processRunner
        _planIdGeneratorOverride = planIdGenerator
        _runIdGeneratorOverride = runIdGenerator
    }

    fun preview(workflowId: String): VerifyPlan {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        if (!isVerifyEnabled(workflow)) {
            throw VerifyStageDisabledError(workflowId)
        }

        val effectiveConfig = resolveEffectiveConfig(workflow)
        val projectRoot = resolveProjectRoot()
        val requests = effectiveConfig.config.verify.commands.map { command ->
            processRunner.prepare(projectRoot = projectRoot, verifyConfig = effectiveConfig.config.verify, command = command)
        }
        val plan = VerifyPlan(
            planId = planIdGenerator.nextId(),
            workflowId = workflowId,
            currentStage = workflow.currentStage,
            generatedAt = Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
            commands = requests.map { request ->
                VerifyPlanCommand(
                    commandId = request.commandId,
                    displayName = request.displayName,
                    command = request.command,
                    workingDirectory = request.workingDirectory,
                    timeoutMs = request.timeoutMs,
                    outputLimitChars = request.outputLimitChars,
                    redactionPatterns = request.redactionPatterns,
                )
            },
            policy = effectiveConfig.policy,
        )

        pendingPlans.entries.removeIf { (_, pending) -> pending.plan.workflowId == workflowId }
        pendingPlans[plan.planId] = PendingVerifyPlan(
            plan = plan,
            requests = requests,
            currentStage = workflow.currentStage,
            verifyEnabled = isVerifyEnabled(workflow),
            configPinHash = workflow.configPinHash?.trim()?.takeIf { it.isNotEmpty() },
        )
        return plan
    }

    fun run(workflowId: String, planId: String, scopeTaskIds: List<String>): VerifyRunResult {
        val pendingPlan = consumePendingPlan(workflowId, planId)
        val scopeTasks = resolveScopeTasks(workflowId, scopeTaskIds)
        val executedAt = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
        val runId = runIdGenerator.nextId()
        val commandResults = pendingPlan.requests.map(processRunner::execute)
        val conclusion = determineConclusion(commandResults)
        val summary = buildSummary(commandResults, conclusion)
        val verificationDocumentPath = artifactService.writeArtifact(
            workflowId,
            StageId.VERIFY,
            renderVerificationDocument(
                plan = pendingPlan.plan,
                scopeTasks = scopeTasks,
                runId = runId,
                executedAt = executedAt,
                commandResults = commandResults,
                conclusion = conclusion,
                summary = summary,
            ),
        )
        val verificationResult = TaskVerificationResult(
            conclusion = conclusion,
            runId = runId,
            summary = summary,
            at = executedAt,
        )
        val updatedTasks = scopeTasks.map { task ->
            tasksService.updateVerificationResult(workflowId, task.id, verificationResult)
        }

        storage.appendAuditEvent(
            workflowId = workflowId,
            eventType = SpecAuditEventType.VERIFICATION_RUN_COMPLETED,
            details = buildAuditDetails(
                plan = pendingPlan.plan,
                runId = runId,
                executedAt = executedAt,
                scopeTasks = scopeTasks,
                commandResults = commandResults,
                conclusion = conclusion,
                summary = summary,
                verificationDocumentPath = verificationDocumentPath,
            ),
        ).getOrThrow()

        return VerifyRunResult(
            runId = runId,
            workflowId = workflowId,
            planId = pendingPlan.plan.planId,
            currentStage = pendingPlan.plan.currentStage,
            executedAt = executedAt,
            conclusion = conclusion,
            summary = summary,
            verificationDocumentPath = verificationDocumentPath,
            commandResults = commandResults,
            updatedTasks = updatedTasks,
        )
    }

    fun listRunHistory(workflowId: String): List<VerifyRunHistoryEntry> {
        return storage.listAuditEvents(workflowId).getOrThrow()
            .asSequence()
            .filter { event -> event.eventType == SpecAuditEventType.VERIFICATION_RUN_COMPLETED }
            .mapNotNull(::parseRunHistoryEntry)
            .sortedByDescending(VerifyRunHistoryEntry::occurredAtEpochMs)
            .toList()
    }

    internal fun resolvePlan(workflowId: String, planId: String): VerifyPlan {
        return resolvePendingPlan(workflowId, planId).plan
    }

    internal fun resolveExecutionRequests(workflowId: String, planId: String): List<VerifyCommandExecutionRequest> {
        return resolvePendingPlan(workflowId, planId).requests
    }

    private fun consumePendingPlan(workflowId: String, planId: String): PendingVerifyPlan {
        val pendingPlan = resolvePendingPlan(workflowId, planId)
        pendingPlans.remove(planId)
        return pendingPlan
    }

    private fun resolvePendingPlan(workflowId: String, planId: String): PendingVerifyPlan {
        val pending = pendingPlans[planId] ?: throw MissingVerifyPlanError(planId)
        if (pending.plan.workflowId != workflowId) {
            throw StaleVerifyPlanError(planId, workflowId)
        }

        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val configPinHash = workflow.configPinHash?.trim()?.takeIf { it.isNotEmpty() }
        if (
            pending.currentStage != workflow.currentStage ||
            pending.verifyEnabled != isVerifyEnabled(workflow) ||
            pending.configPinHash != configPinHash
        ) {
            pendingPlans.remove(planId)
            throw StaleVerifyPlanError(planId, workflowId)
        }
        return pending
    }

    private fun resolveEffectiveConfig(workflow: SpecWorkflow): EffectiveVerifyConfig {
        val currentConfig = projectConfigService.load()
        val currentConfigPin = projectConfigService.createConfigPin(currentConfig)
        val workflowConfigPinHash = workflow.configPinHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val confirmationReasons = mutableListOf(DEFAULT_CONFIRMATION_REASON)

        if (workflowConfigPinHash == null) {
            return EffectiveVerifyConfig(
                config = currentConfig,
                policy = VerifyPlanPolicy(
                    configSource = VerifyPlanConfigSource.PROJECT_CONFIG,
                    workflowConfigPinHash = null,
                    effectiveConfigHash = currentConfigPin.hash,
                    usesPinnedSnapshot = false,
                    confirmationRequired = true,
                    confirmationReasons = confirmationReasons,
                ),
            )
        }

        if (workflowConfigPinHash == currentConfigPin.hash) {
            return EffectiveVerifyConfig(
                config = currentConfig,
                policy = VerifyPlanPolicy(
                    configSource = VerifyPlanConfigSource.WORKFLOW_PINNED,
                    workflowConfigPinHash = workflowConfigPinHash,
                    effectiveConfigHash = workflowConfigPinHash,
                    usesPinnedSnapshot = false,
                    confirmationRequired = true,
                    confirmationReasons = confirmationReasons,
                ),
            )
        }

        val pinnedSnapshot = storage.loadConfigPinSnapshot(workflow.id, workflowConfigPinHash).getOrThrow()
        confirmationReasons += PINNED_SNAPSHOT_CONFIRMATION_REASON
        return EffectiveVerifyConfig(
            config = projectConfigService.parseSnapshotYaml(pinnedSnapshot.snapshotYaml),
            policy = VerifyPlanPolicy(
                configSource = VerifyPlanConfigSource.WORKFLOW_PINNED,
                workflowConfigPinHash = workflowConfigPinHash,
                effectiveConfigHash = workflowConfigPinHash,
                usesPinnedSnapshot = true,
                confirmationRequired = true,
                confirmationReasons = confirmationReasons,
            ),
        )
    }

    private fun resolveScopeTasks(workflowId: String, scopeTaskIds: List<String>): List<StructuredTask> {
        if (scopeTaskIds.isEmpty()) {
            return emptyList()
        }

        val tasksById = tasksService.parse(workflowId).associateBy(StructuredTask::id)
        val normalizedIds = scopeTaskIds
            .map(::normalizeTaskId)
            .distinct()
            .sorted()
        normalizedIds.firstOrNull { taskId -> taskId !in tasksById }
            ?.let { missingTaskId ->
                throw MissingStructuredTaskError(missingTaskId)
            }
        return normalizedIds.map(tasksById::getValue)
    }

    private fun normalizeTaskId(rawTaskId: String): String {
        val normalizedTaskId = rawTaskId.trim().uppercase()
        if (!TASK_ID_REGEX.matches(normalizedTaskId)) {
            throw MissingStructuredTaskError(normalizedTaskId)
        }
        return normalizedTaskId
    }

    private fun parseRunHistoryEntry(event: SpecAuditEvent): VerifyRunHistoryEntry? {
        val runId = event.details["runId"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val planId = event.details["planId"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val stage = parseEnum<StageId>(event.details["currentStage"]) ?: return null
        val conclusion = parseEnum<VerificationConclusion>(event.details["conclusion"]) ?: return null
        val executedAt = event.details["executedAt"]?.trim()?.takeIf { it.isNotEmpty() } ?: event.occurredAt
        return VerifyRunHistoryEntry(
            runId = runId,
            planId = planId,
            executedAt = executedAt,
            occurredAtEpochMs = event.occurredAtEpochMs,
            currentStage = stage,
            conclusion = conclusion,
            summary = event.details["summary"]?.trim().orEmpty(),
            commandCount = event.details["commandCount"]?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            scopeTaskIds = parseCsvList(event.details["scopeTaskIds"]),
            failedCommandIds = parseCsvList(event.details["failedCommandIds"]),
            truncatedCommandIds = parseCsvList(event.details["truncatedCommandIds"]),
            redactedCommandIds = parseCsvList(event.details["redactedCommandIds"]),
        )
    }

    private fun determineConclusion(commandResults: List<VerifyCommandExecutionResult>): VerificationConclusion {
        if (commandResults.isEmpty()) {
            return VerificationConclusion.WARN
        }
        return when {
            commandResults.any { result -> result.timedOut || result.exitCode != 0 } -> VerificationConclusion.FAIL
            commandResults.any(VerifyCommandExecutionResult::truncated) -> VerificationConclusion.WARN
            else -> VerificationConclusion.PASS
        }
    }

    private fun buildSummary(
        commandResults: List<VerifyCommandExecutionResult>,
        conclusion: VerificationConclusion,
    ): String {
        if (commandResults.isEmpty()) {
            return "No verify commands were configured; review verification scope manually."
        }

        val failedCommandIds = commandResults
            .filter { result -> result.timedOut || result.exitCode != 0 }
            .map(VerifyCommandExecutionResult::commandId)
        val truncatedCommandIds = commandResults
            .filter(VerifyCommandExecutionResult::truncated)
            .map(VerifyCommandExecutionResult::commandId)
        val redactedCount = commandResults.count(VerifyCommandExecutionResult::redacted)

        return buildString {
            when (conclusion) {
                VerificationConclusion.FAIL -> {
                    append("${failedCommandIds.size}/${commandResults.size} verify command(s) failed or timed out")
                    if (failedCommandIds.isNotEmpty()) {
                        append(": ${failedCommandIds.joinToString(", ")}")
                    }
                    append('.')
                }

                VerificationConclusion.WARN -> {
                    append("${commandResults.size}/${commandResults.size} verify command(s) completed, but output was truncated")
                    if (truncatedCommandIds.isNotEmpty()) {
                        append(" for ${truncatedCommandIds.joinToString(", ")}")
                    }
                    append('.')
                }

                VerificationConclusion.PASS -> {
                    append("${commandResults.size}/${commandResults.size} verify command(s) completed successfully.")
                }
            }
            if (redactedCount > 0) {
                append(" Sensitive output was redacted.")
            }
        }
    }

    private fun buildAuditDetails(
        plan: VerifyPlan,
        runId: String,
        executedAt: String,
        scopeTasks: List<StructuredTask>,
        commandResults: List<VerifyCommandExecutionResult>,
        conclusion: VerificationConclusion,
        summary: String,
        verificationDocumentPath: Path,
    ): Map<String, String> {
        val failedCommandIds = commandResults
            .filter { result -> result.timedOut || result.exitCode != 0 }
            .map(VerifyCommandExecutionResult::commandId)
        val truncatedCommandIds = commandResults
            .filter(VerifyCommandExecutionResult::truncated)
            .map(VerifyCommandExecutionResult::commandId)
        val redactedCommandIds = commandResults
            .filter(VerifyCommandExecutionResult::redacted)
            .map(VerifyCommandExecutionResult::commandId)
        val commandSecurityAudits = plan.commands.map(::buildCommandSecurityAudit)
        return linkedMapOf(
            "planId" to plan.planId,
            "runId" to runId,
            "executedAt" to executedAt,
            "currentStage" to plan.currentStage.name,
            "conclusion" to conclusion.name,
            "commandCount" to commandResults.size.toString(),
            "scopeTaskCount" to scopeTasks.size.toString(),
            "scopeTaskIds" to scopeTasks.joinToString(", ") { task -> task.id },
            "failedCommandIds" to failedCommandIds.joinToString(", "),
            "truncatedCommandIds" to truncatedCommandIds.joinToString(", "),
            "redactedCommandIds" to redactedCommandIds.joinToString(", "),
            "commandExecutables" to commandSecurityAudits.joinToString(", ") { audit ->
                "${audit.commandId}=${audit.executable}"
            },
            "commandWorkingDirectories" to commandSecurityAudits.joinToString(", ") { audit ->
                "${audit.commandId}=${audit.workingDirectory}"
            },
            "commandSecurityDigests" to commandSecurityAudits.joinToString(", ") { audit ->
                "${audit.commandId}=${audit.digest}"
            },
            "commandRedactionRuleCounts" to commandSecurityAudits.joinToString(", ") { audit ->
                "${audit.commandId}=${audit.redactionRuleCount}"
            },
            "commandPreviewRedactedIds" to commandSecurityAudits
                .filter(CommandSecurityAudit::previewRedacted)
                .joinToString(", ") { audit -> audit.commandId },
            "verificationFile" to verificationDocumentPath.fileName.toString(),
            "summary" to summary,
        )
    }

    private fun renderVerificationDocument(
        plan: VerifyPlan,
        scopeTasks: List<StructuredTask>,
        runId: String,
        executedAt: String,
        commandResults: List<VerifyCommandExecutionResult>,
        conclusion: VerificationConclusion,
        summary: String,
    ): String {
        val commandsById = plan.commands.associateBy(VerifyPlanCommand::commandId)
        return buildString {
            append("# Verification Document\n\n")
            append("## Verification Scope\n")
            append("- Workflow: `${plan.workflowId}`\n")
            append("- Plan ID: `${plan.planId}`\n")
            append("- Run ID: `$runId`\n")
            append("- Stage at execution: `${plan.currentStage}`\n")
            if (scopeTasks.isEmpty()) {
                append("- Related tasks: none selected\n")
            } else {
                append("- Related tasks:\n")
                scopeTasks.forEach { task ->
                    append("  - `${task.id}` ${task.title}\n")
                }
            }

            append("\n## Verification Method\n")
            append("- Execution mode: local parameterized commands via `SpecProcessRunner`\n")
            append("- Config source: `${plan.policy.configSource}`\n")
            append("- Effective config hash: `${plan.policy.effectiveConfigHash}`\n")
            plan.policy.workflowConfigPinHash?.let { workflowConfigPinHash ->
                append("- Workflow config pin: `$workflowConfigPinHash`\n")
            }
            append("- Project root: `${normalizePath(resolveProjectRoot())}`\n")
            append("- Environment: `${System.getProperty("os.name")} ${System.getProperty("os.arch")}`, Java `${System.getProperty("java.version")}`\n")
            append("- Generated at: `${plan.generatedAt}`\n")
            append("- Executed at: `$executedAt`\n")

            append("\n## Commands\n")
            if (commandResults.isEmpty()) {
                append("- No verify commands configured.\n")
            } else {
                commandResults.forEach { commandResult ->
                    val planCommand = commandsById.getValue(commandResult.commandId)
                    val sanitizedCommand = processRunner.sanitizeCommandForDisplay(
                        commandId = planCommand.commandId,
                        command = planCommand.command,
                        customPatterns = planCommand.redactionPatterns,
                    )
                    append("\n### ${planCommand.commandId}: ${planCommand.displayName ?: planCommand.commandId}\n")
                    append("- Working directory: `${normalizePath(planCommand.workingDirectory)}`\n")
                    append("- Timeout: `${planCommand.timeoutMs} ms`\n")
                    append("- Output limit: `${planCommand.outputLimitChars}` chars\n")
                    append("- Outcome: `${formatOutcome(commandResult)}`\n")
                    append("- Duration: `${commandResult.durationMs} ms`\n")
                    append("- Redacted: `${if (commandResult.redacted) "yes" else "no"}`\n")
                    append("- Truncated: `${if (commandResult.truncated) "yes" else "no"}`\n\n")
                    append("- Command preview redacted: `${if (sanitizedCommand.redacted) "yes" else "no"}`\n\n")
                    append(renderCodeBlock("bash", sanitizedCommand.text))
                    if (commandResult.stdout.isNotBlank()) {
                        append("\n#### stdout\n\n")
                        append(renderCodeBlock("text", commandResult.stdout))
                    }
                    if (commandResult.stderr.isNotBlank()) {
                        append("\n#### stderr\n\n")
                        append(renderCodeBlock("text", commandResult.stderr))
                    }
                }
            }

            append("\n## Result\n")
            append(
                SpecYamlCodec.encodeMap(
                    linkedMapOf(
                        "conclusion" to conclusion.name,
                        "runId" to runId,
                        "at" to executedAt,
                        "summary" to summary,
                    ),
                ),
            )
        }
    }

    private fun buildCommandSecurityAudit(command: VerifyPlanCommand): CommandSecurityAudit {
        val sanitizedPreview = processRunner.sanitizeCommandForDisplay(
            commandId = command.commandId,
            command = command.command,
            customPatterns = command.redactionPatterns,
        )
        return CommandSecurityAudit(
            commandId = command.commandId,
            executable = executableName(command.command),
            workingDirectory = normalizeAuditWorkingDirectory(command.workingDirectory),
            digest = processRunner.commandFingerprint(command.command),
            redactionRuleCount = processRunner.effectiveRedactionRuleCount(command.redactionPatterns),
            previewRedacted = sanitizedPreview.redacted,
        )
    }

    private fun formatOutcome(commandResult: VerifyCommandExecutionResult): String {
        return when {
            commandResult.timedOut -> "TIMEOUT"
            commandResult.exitCode == 0 -> "SUCCESS"
            else -> "EXIT ${commandResult.exitCode}"
        }
    }

    private fun executableName(command: List<String>): String {
        val executable = command.firstOrNull().orEmpty()
        return try {
            Path.of(executable).fileName?.toString().orEmpty().ifBlank { executable }
        } catch (_: InvalidPathException) {
            executable
        }
    }

    private fun renderCodeBlock(language: String, content: String): String {
        val normalizedContent = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val fenceLength = maxOf(
            3,
            (BACKTICK_RUN_REGEX.findAll(normalizedContent).maxOfOrNull { match -> match.value.length } ?: 0) + 1,
        )
        val fence = "`".repeat(fenceLength)
        return buildString {
            append(fence)
            append(language)
            append('\n')
            append(normalizedContent)
            if (!normalizedContent.endsWith("\n")) {
                append('\n')
            }
            append(fence)
            append('\n')
        }
    }

    private fun normalizePath(path: Path): String {
        return path.toString().replace('\\', '/')
    }

    private fun normalizeAuditWorkingDirectory(path: Path): String {
        val projectRoot = resolveProjectRoot()
        if (!path.startsWith(projectRoot)) {
            return normalizePath(path)
        }
        val relative = projectRoot.relativize(path)
            .joinToString(separator = "/") { segment -> segment.toString() }
            .trim()
        return if (relative.isEmpty()) "." else relative
    }

    private fun parseCsvList(raw: String?): List<String> {
        return raw
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            ?: emptyList()
    }

    private inline fun <reified E : Enum<E>> parseEnum(raw: String?): E? {
        val normalized = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return enumValues<E>().firstOrNull { candidate -> candidate.name == normalized }
    }

    private fun isVerifyEnabled(workflow: SpecWorkflow): Boolean {
        return workflow.verifyEnabled || workflow.stageStates[StageId.VERIFY]?.active == true
    }

    private fun resolveProjectRoot(): Path {
        val basePath = project.basePath ?: throw IllegalStateException("Project base path is null")
        return Path.of(basePath).toAbsolutePath().normalize()
    }

    private data class EffectiveVerifyConfig(
        val config: SpecProjectConfig,
        val policy: VerifyPlanPolicy,
    )

    private data class PendingVerifyPlan(
        val plan: VerifyPlan,
        val requests: List<VerifyCommandExecutionRequest>,
        val currentStage: StageId,
        val verifyEnabled: Boolean,
        val configPinHash: String?,
    )

    private data class CommandSecurityAudit(
        val commandId: String,
        val executable: String,
        val workingDirectory: String,
        val digest: String,
        val redactionRuleCount: Int,
        val previewRedacted: Boolean,
    )

    companion object {
        private const val VERIFY_PLAN_ID_PREFIX = "verify-plan"
        private const val VERIFY_RUN_ID_PREFIX = "verify-run"
        private const val DEFAULT_CONFIRMATION_REASON = "Review verify commands before execution."
        private const val PINNED_SNAPSHOT_CONFIRMATION_REASON =
            "Current project config differs from the workflow pin; using the pinned config snapshot for this plan."
        private val TASK_ID_REGEX = Regex("""^T-\d{3}$""")
        private val BACKTICK_RUN_REGEX = Regex("`+")

        fun getInstance(project: Project): SpecVerificationService = project.service()
    }
}
