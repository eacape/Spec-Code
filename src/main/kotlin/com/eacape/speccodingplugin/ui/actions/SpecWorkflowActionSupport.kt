package com.eacape.speccodingplugin.ui.actions

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.SpecArtifactService
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.SpecVerificationService
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageTransitionBlockedByGateError
import com.eacape.speccodingplugin.spec.StageWarningConfirmationRequiredError
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.Violation
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.spec.VerifyPlan
import com.eacape.speccodingplugin.spec.VerifyPlanConfigSource
import com.eacape.speccodingplugin.spec.VerifyRunResult
import com.eacape.speccodingplugin.spec.WorkflowMeta
import com.eacape.speccodingplugin.ui.ChatToolWindowFactory
import com.eacape.speccodingplugin.ui.spec.SpecGateQuickFixSupport
import com.eacape.speccodingplugin.ui.spec.SpecToolWindowControlListener
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowChangedEvent
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowChangedListener
import com.intellij.CommonBundle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal object SpecWorkflowActionSupport {
    private const val TOOL_WINDOW_ID = ChatToolWindowFactory.TOOL_WINDOW_ID
    private const val MAX_GATE_VIOLATIONS = 5
    private const val MAX_VERIFY_COMMANDS = 5
    private const val MAX_VERIFY_TASKS = 5

    fun showCreateWorkflow(project: Project) {
        openSpecTab(project) {
            project.messageBus.syncPublisher(SpecToolWindowControlListener.TOPIC)
                .onCreateWorkflowRequested()
        }
    }

    fun showWorkflow(project: Project, workflowId: String) {
        val normalizedWorkflowId = workflowId.trim()
        if (normalizedWorkflowId.isEmpty()) return
        openSpecTab(project) {
            publishWorkflowSelection(project, normalizedWorkflowId)
        }
    }

    fun rememberWorkflow(project: Project, workflowId: String) {
        val normalizedWorkflowId = workflowId.trim()
        if (normalizedWorkflowId.isEmpty()) return
        project.messageBus.syncPublisher(SpecWorkflowChangedListener.TOPIC)
            .onWorkflowChanged(
                SpecWorkflowChangedEvent(
                    workflowId = normalizedWorkflowId,
                    reason = SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED,
                ),
            )
    }

    fun <T> runBackground(
        project: Project,
        title: String,
        cancellable: Boolean = true,
        task: () -> T,
        onSuccess: (T) -> Unit,
        onCancelRequested: (() -> Unit)? = null,
        onCancelled: (() -> Unit)? = null,
        onFailure: (Throwable) -> Unit = { error ->
            showErrorDialog(
                project = project,
                title = title,
                message = describeFailure(error),
            )
        },
    ) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, title, cancellable) {
                private var outcome: Result<T>? = null
                private val cancelHandled = AtomicBoolean(false)
                private val completionHandled = AtomicBoolean(false)

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = title
                    if (cancellable && onCancelRequested != null) {
                        val worker = ApplicationManager.getApplication().executeOnPooledThread(
                            Callable {
                                captureBackgroundTaskOutcome(task)
                            },
                        )
                        try {
                            while (true) {
                                if (indicator.isCanceled) {
                                    if (cancelHandled.compareAndSet(false, true)) {
                                        onCancelRequested.invoke()
                                    }
                                    worker.cancel(true)
                                    throw ProcessCanceledException()
                                }
                                try {
                                    outcome = unwrapBackgroundTaskOutcome(
                                        outcome = worker.get(BACKGROUND_TASK_POLL_MILLIS, TimeUnit.MILLISECONDS),
                                        indicatorCancelled = indicator.isCanceled,
                                    )
                                    return
                                } catch (_: TimeoutException) {
                                    continue
                                } catch (cancel: CancellationException) {
                                    if (indicator.isCanceled) {
                                        throw ProcessCanceledException()
                                    }
                                    outcome = Result.failure(cancel)
                                    return
                                } catch (execution: ExecutionException) {
                                    val cause = execution.cause ?: execution
                                    if (cause is ProcessCanceledException) {
                                        throw cause
                                    }
                                    if (cause is CancellationException && indicator.isCanceled) {
                                        throw ProcessCanceledException()
                                    }
                                    outcome = Result.failure(cause)
                                    return
                                }
                            }
                        } finally {
                            if (indicator.isCanceled && !worker.isDone) {
                                worker.cancel(true)
                            }
                        }
                    }
                    outcome = try {
                        Result.success(task())
                    } catch (cancel: ProcessCanceledException) {
                        Result.failure(cancel)
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }

                override fun onSuccess() {
                    deliverOutcome(cancelHandled.get())
                }

                override fun onCancel() {
                    if (onCancelRequested != null && cancelHandled.compareAndSet(false, true)) {
                        onCancelRequested.invoke()
                    }
                    if (outcome != null) {
                        deliverOutcome(cancelled = true)
                    }
                }

                private fun deliverOutcome(cancelled: Boolean) {
                    if (!completionHandled.compareAndSet(false, true)) {
                        return
                    }
                    val resolvedOutcome = outcome
                    if (resolvedOutcome == null) {
                        if (cancelled) {
                            onCancelled?.invoke()
                        }
                        return
                    }
                    resolvedOutcome
                        .onSuccess { result ->
                            if (cancelled) {
                                onCancelled?.invoke()
                            } else {
                                onSuccess(result)
                            }
                        }
                        .onFailure { error ->
                            if (cancelled || error is ProcessCanceledException || error is CancellationException) {
                                onCancelled?.invoke()
                            } else {
                                onFailure(error)
                            }
                        }
                }
            },
        )
    }

    fun chooseWorkflow(
        project: Project,
        workflows: List<WorkflowMeta>,
        title: String,
        onChosen: (WorkflowMeta) -> Unit,
    ) {
        showSelectionPopup(
            project = project,
            title = title,
            items = workflows,
            text = ::workflowLabel,
            onChosen = onChosen,
        )
    }

    fun chooseStage(
        project: Project,
        stages: List<StageId>,
        title: String,
        workflowMeta: WorkflowMeta,
        onChosen: (StageId) -> Unit,
    ) {
        showSelectionPopup(
            project = project,
            title = title,
            items = stages,
            text = { stage -> stageChoiceLabel(stage, workflowMeta) },
            onChosen = onChosen,
        )
    }

    fun confirmArchive(project: Project, workflowLabel: String): Boolean {
        return Messages.showOkCancelDialog(
            project,
            SpecCodingBundle.message("spec.action.archive.confirm.message", workflowLabel),
            SpecCodingBundle.message("spec.action.archive.confirm.title"),
            SpecCodingBundle.message("spec.action.archive.confirm.ok"),
            CommonBundle.getCancelButtonText(),
            Messages.getWarningIcon(),
        ) == Messages.OK
    }

    fun confirmWarnings(project: Project, workflowId: String, gateResult: GateResult): Boolean {
        while (true) {
            when (
                Messages.showDialog(
                    project,
                    gateSummary(gateResult),
                    SpecCodingBundle.message("spec.action.warning.title"),
                    arrayOf(
                        SpecCodingBundle.message("spec.action.warning.proceed"),
                        SpecCodingBundle.message("spec.action.gate.review.button"),
                        CommonBundle.getCancelButtonText(),
                    ),
                    0,
                    Messages.getWarningIcon(),
                )
            ) {
                0 -> return true
                1 -> reviewGateViolations(project, workflowId, gateResult)
                else -> return false
            }
        }
    }

    fun showGateBlocked(project: Project, workflowId: String, gateResult: GateResult) {
        while (true) {
            when (
                Messages.showDialog(
                    project,
                    gateSummary(gateResult),
                    SpecCodingBundle.message("spec.action.gate.blocked.title"),
                    arrayOf(
                        SpecCodingBundle.message("spec.action.gate.review.button"),
                        SpecCodingBundle.message("spec.action.gate.close"),
                    ),
                    0,
                    Messages.getErrorIcon(),
                )
            ) {
                0 -> reviewGateViolations(project, workflowId, gateResult)
                else -> return
            }
        }
    }

    fun notifySuccess(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SpecCoding.Notifications")
            .createNotification(
                SpecCodingBundle.message("spec.action.notification.title"),
                message,
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    fun showInfo(project: Project, title: String, message: String) {
        Messages.showInfoMessage(project, message, title)
    }

    fun jumpTargets(meta: WorkflowMeta): List<StageId> {
        return emptyList()
    }

    fun rollbackTargets(meta: WorkflowMeta): List<StageId> {
        return emptyList()
    }

    fun workflowLabel(meta: WorkflowMeta): String {
        val title = meta.title?.trim().orEmpty().ifBlank { meta.workflowId }
        return SpecCodingBundle.message(
            "spec.action.workflow.choice",
            title,
            meta.workflowId,
            stageLabel(meta.currentStage),
        )
    }

    fun stageChoiceLabel(stage: StageId, meta: WorkflowMeta): String {
        val state = meta.stageStates[stage]
        val statusText = when {
            stage == meta.currentStage -> SpecCodingBundle.message("spec.action.stage.state.current")
            state?.status == StageProgress.DONE -> SpecCodingBundle.message("spec.action.stage.state.done")
            state?.status == StageProgress.IN_PROGRESS -> SpecCodingBundle.message("spec.action.stage.state.inProgress")
            else -> SpecCodingBundle.message("spec.action.stage.state.pending")
        }
        return SpecCodingBundle.message(
            "spec.action.stage.choice",
            stageLabel(stage),
            statusText,
        )
    }

    fun stageLabel(stageId: StageId): String {
        return stageId.name
            .lowercase(Locale.ROOT)
            .split('_')
            .joinToString(" ") { token -> token.replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) } }
    }

    fun gateSummary(gateResult: GateResult, limit: Int = MAX_GATE_VIOLATIONS): String {
        if (gateResult.violations.isEmpty()) {
            return gateResult.aggregation.summary
        }
        val lines = mutableListOf<String>()
        lines += gateResult.aggregation.summary
        lines += ""
        gateResult.violations.take(limit).forEach { violation ->
            lines += formatViolation(violation)
            violation.fixHint
                ?.takeIf { it.isNotBlank() }
                ?.let { fixHint -> lines += SpecCodingBundle.message("spec.action.gate.fix", fixHint) }
        }
        val remaining = gateResult.violations.size - limit
        if (remaining > 0) {
            lines += SpecCodingBundle.message("spec.action.gate.more", remaining)
        }
        return lines.joinToString("\n").trim()
    }

    fun confirmVerificationPlan(project: Project, plan: VerifyPlan, scopeTasks: List<StructuredTask>): Boolean {
        val choice = Messages.showDialog(
            project,
            verificationPlanSummary(plan, scopeTasks),
            SpecCodingBundle.message("spec.action.verify.confirm.title"),
            arrayOf(
                SpecCodingBundle.message("spec.action.verify.confirm.run"),
                CommonBundle.getCancelButtonText(),
            ),
            0,
            Messages.getQuestionIcon(),
        )
        return choice == 0
    }

    fun runVerificationWorkflow(
        project: Project,
        verificationService: SpecVerificationService,
        tasksService: SpecTasksService,
        workflowId: String,
        onCompleted: ((VerifyRunResult) -> Unit)? = null,
    ) {
        runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.verify.preview"),
            task = {
                VerificationActionContext(
                    plan = verificationService.preview(workflowId),
                    scopeTasks = tasksService.parse(workflowId).sortedBy(StructuredTask::id),
                )
            },
            onSuccess = { context ->
                rememberWorkflow(project, workflowId)
                if (!confirmVerificationPlan(project, context.plan, context.scopeTasks)) {
                    return@runBackground
                }
                runBackground(
                    project = project,
                    title = SpecCodingBundle.message("spec.action.verify.executing"),
                    task = {
                        verificationService.run(
                            workflowId = workflowId,
                            planId = context.plan.planId,
                            scopeTaskIds = context.scopeTasks.map(StructuredTask::id),
                        )
                    },
                    onSuccess = { result ->
                        rememberWorkflow(project, workflowId)
                        showVerificationRunResult(project, result)
                        onCompleted?.invoke(result)
                    },
                )
            },
        )
    }

    fun showVerificationRunResult(project: Project, result: VerifyRunResult) {
        val choice = Messages.showDialog(
            project,
            verificationRunSummary(result),
            SpecCodingBundle.message("spec.action.verify.result.title", result.conclusion.name),
            arrayOf(
                SpecCodingBundle.message("spec.action.verify.result.open"),
                SpecCodingBundle.message("spec.action.verify.result.close"),
            ),
            0,
            verificationResultIcon(result.conclusion),
        )
        if (choice == 0 && !openFile(project, result.verificationDocumentPath)) {
            showInfo(
                project,
                SpecCodingBundle.message("spec.action.verify.document.unavailable.title"),
                normalizePath(result.verificationDocumentPath),
            )
        }
    }

    fun verificationPlanSummary(
        plan: VerifyPlan,
        scopeTasks: List<StructuredTask>,
        commandLimit: Int = MAX_VERIFY_COMMANDS,
        taskLimit: Int = MAX_VERIFY_TASKS,
    ): String {
        val lines = mutableListOf<String>()
        lines += "Plan ID: ${plan.planId}"
        lines += "Workflow: ${plan.workflowId}"
        lines += "Stage: ${stageLabel(plan.currentStage)}"
        lines += "Generated At: ${plan.generatedAt}"
        lines += "Task Scope: ${formatTaskScope(scopeTasks, taskLimit)}"
        lines += "Config Source: ${formatVerifyConfigSource(plan.policy.configSource)}"
        lines += "Effective Config Hash: ${plan.policy.effectiveConfigHash}"
        plan.policy.workflowConfigPinHash?.let { workflowConfigPinHash ->
            lines += "Workflow Pin Hash: $workflowConfigPinHash"
        }
        if (plan.policy.usesPinnedSnapshot) {
            lines += "Using the pinned workflow config snapshot for this verification run."
        }
        if (plan.policy.confirmationReasons.isNotEmpty()) {
            lines += ""
            lines += "Confirmation Reasons:"
            plan.policy.confirmationReasons.forEach { reason ->
                lines += "- $reason"
            }
        }
        lines += ""
        if (plan.commands.isEmpty()) {
            lines += "Commands: none configured"
            return lines.joinToString("\n")
        }
        lines += "Commands:"
        plan.commands.take(commandLimit).forEach { command ->
            val title = command.displayName?.trim().orEmpty().ifBlank { command.commandId }
            lines += "- $title [${command.commandId}]"
            lines += "  ${renderCommand(command.command)}"
            lines += "  dir=${normalizePath(command.workingDirectory)} · timeout=${command.timeoutMs} ms · output=${command.outputLimitChars} chars · redaction=${command.redactionPatterns.size}"
        }
        val remainingCommands = plan.commands.size - commandLimit
        if (remainingCommands > 0) {
            lines += "... and $remainingCommands more command(s)"
        }
        return lines.joinToString("\n")
    }

    fun verificationRunSummary(
        result: VerifyRunResult,
        commandLimit: Int = MAX_VERIFY_COMMANDS,
        taskLimit: Int = MAX_VERIFY_TASKS,
    ): String {
        val lines = mutableListOf<String>()
        lines += "Conclusion: ${result.conclusion.name}"
        lines += "Workflow: ${result.workflowId}"
        lines += "Plan ID: ${result.planId}"
        lines += "Run ID: ${result.runId}"
        lines += "Stage: ${stageLabel(result.currentStage)}"
        lines += "Executed At: ${result.executedAt}"
        lines += "Verification File: ${normalizePath(result.verificationDocumentPath)}"
        lines += "Updated Tasks: ${formatTaskScope(result.updatedTasks, taskLimit)}"
        lines += ""
        lines += result.summary
        lines += ""
        if (result.commandResults.isEmpty()) {
            lines += "Commands: none executed"
            return lines.joinToString("\n")
        }
        lines += "Commands:"
        result.commandResults.take(commandLimit).forEach { commandResult ->
            lines += "- ${commandResult.commandId}: ${formatVerificationOutcome(commandResult)} · ${commandResult.durationMs} ms${if (commandResult.truncated) " · truncated" else ""}${if (commandResult.redacted) " · redacted" else ""}"
        }
        val remainingCommands = result.commandResults.size - commandLimit
        if (remainingCommands > 0) {
            lines += "... and $remainingCommands more command(s)"
        }
        return lines.joinToString("\n")
    }

    fun describeFailure(error: Throwable): String {
        return when (error) {
            is StageTransitionBlockedByGateError -> gateSummary(error.gateResult)
            is StageWarningConfirmationRequiredError -> gateSummary(error.gateResult)
            else -> error.message ?: SpecCodingBundle.message("common.unknown")
        }
    }

    fun gateViolationOptionLabel(violation: Violation): String {
        return SpecCodingBundle.message(
            "spec.action.gate.review.option",
            severityLabel(violation),
            violation.ruleId,
            violation.fileName,
            violation.line,
        )
    }

    fun gateViolationDetails(violation: Violation): String {
        val lines = mutableListOf<String>()
        lines += formatViolation(violation)
        lines += SpecCodingBundle.message("spec.action.gate.review.rule", violation.ruleId)
        lines += SpecCodingBundle.message(
            "spec.action.gate.review.location",
            violation.fileName,
            violation.line,
        )
        violation.originalSeverity
            ?.takeIf { it != violation.severity }
            ?.let { originalSeverity ->
                lines += SpecCodingBundle.message(
                    "spec.action.gate.review.originalSeverity",
                    severityLabel(violation.copy(severity = originalSeverity)),
                )
            }
        violation.fixHint
            ?.takeIf { it.isNotBlank() }
            ?.let { fixHint -> lines += SpecCodingBundle.message("spec.action.gate.fix", fixHint) }
        SpecGateQuickFixSupport.summary(violation)
            ?.let { summary -> lines += SpecCodingBundle.message("spec.toolwindow.gate.quickFix.summary", summary) }
        return lines.joinToString("\n")
    }

    fun resolveGateViolationPath(project: Project, workflowId: String, violation: Violation): Path? {
        val normalizedFileName = violation.fileName.trim()
        if (normalizedFileName.isEmpty()) {
            return null
        }
        val artifactService = SpecArtifactService(project)
        return runCatching {
            artifactService.locateArtifact(workflowId, normalizedFileName)
        }.getOrNull()?.takeIf(Files::exists)
            ?: artifactService.listWorkflowMarkdownArtifacts(workflowId)
                .firstOrNull { path -> path.fileName.toString().equals(normalizedFileName, ignoreCase = true) }
    }

    fun openFile(project: Project, path: Path, line: Int = 1): Boolean {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return false
        OpenFileDescriptor(project, virtualFile, (line - 1).coerceAtLeast(0), 0).navigate(true)
        return true
    }

    fun openGateViolation(project: Project, workflowId: String, violation: Violation): Boolean {
        val path = resolveGateViolationPath(project, workflowId, violation) ?: return false
        return openFile(project, path, violation.line)
    }

    private fun formatViolation(violation: Violation): String {
        val severity = severityLabel(violation)
        return SpecCodingBundle.message(
            "spec.action.gate.violation",
            severity,
            violation.ruleId,
            violation.fileName,
            violation.line,
            violation.message,
        )
    }

    private fun severityLabel(violation: Violation): String {
        val severity = when (violation.severity) {
            GateStatus.ERROR -> SpecCodingBundle.message("spec.action.gate.severity.error")
            GateStatus.WARNING -> SpecCodingBundle.message("spec.action.gate.severity.warning")
            GateStatus.PASS -> SpecCodingBundle.message("spec.action.gate.severity.pass")
        }
        return severity
    }

    private fun reviewGateViolations(project: Project, workflowId: String, gateResult: GateResult) {
        if (gateResult.violations.isEmpty()) {
            return
        }
        val optionLabels = gateResult.violations.map(::gateViolationOptionLabel).toTypedArray()
        val selection = Messages.showChooseDialog(
            project,
            SpecCodingBundle.message("spec.action.gate.review.message"),
            SpecCodingBundle.message("spec.action.gate.review.title"),
            Messages.getInformationIcon(),
            optionLabels,
            optionLabels.firstOrNull(),
        ) ?: return
        val selectedLabel = selection.toString()
        val selectedIndex = optionLabels.indexOfFirst { option -> option == selectedLabel }
        if (selectedIndex < 0) {
            return
        }
        val violation = gateResult.violations[selectedIndex]
        if (!openGateViolation(project, workflowId, violation)) {
            showInfo(
                project,
                SpecCodingBundle.message("spec.action.gate.location.unavailable.title"),
                gateViolationDetails(violation),
            )
        }
    }

    private fun publishWorkflowSelection(project: Project, workflowId: String) {
        project.messageBus.syncPublisher(SpecToolWindowControlListener.TOPIC)
            .onSelectWorkflowRequested(workflowId)
        rememberWorkflow(project, workflowId)
    }

    private fun verificationResultIcon(conclusion: VerificationConclusion) = when (conclusion) {
        VerificationConclusion.PASS -> Messages.getInformationIcon()
        VerificationConclusion.WARN -> Messages.getWarningIcon()
        VerificationConclusion.FAIL -> Messages.getErrorIcon()
    }

    private fun formatVerifyConfigSource(configSource: VerifyPlanConfigSource): String {
        return when (configSource) {
            VerifyPlanConfigSource.WORKFLOW_PINNED -> "workflow-pinned snapshot"
            VerifyPlanConfigSource.PROJECT_CONFIG -> "project config"
        }
    }

    private fun formatTaskScope(tasks: List<StructuredTask>, limit: Int): String {
        if (tasks.isEmpty()) {
            return "none"
        }
        val taskIds = tasks
            .map(StructuredTask::id)
            .distinct()
            .sorted()
        return if (taskIds.size <= limit) {
            "${taskIds.size} task(s): ${taskIds.joinToString(", ")}"
        } else {
            "${taskIds.size} task(s): ${taskIds.take(limit).joinToString(", ")} ... +${taskIds.size - limit}"
        }
    }

    private fun renderCommand(command: List<String>): String {
        return command.joinToString(" ") { token ->
            if (token.any(Char::isWhitespace)) {
                "\"${token.replace("\"", "\\\"")}\""
            } else {
                token
            }
        }
    }

    private fun formatVerificationOutcome(commandResult: com.eacape.speccodingplugin.spec.VerifyCommandExecutionResult): String {
        return when {
            commandResult.timedOut -> "TIMEOUT"
            commandResult.exitCode == 0 -> "SUCCESS"
            else -> "EXIT ${commandResult.exitCode}"
        }
    }

    private fun normalizePath(path: Path): String {
        return path.toString().replace('\\', '/')
    }

    private data class VerificationActionContext(
        val plan: VerifyPlan,
        val scopeTasks: List<StructuredTask>,
    )

    internal sealed interface BackgroundTaskOutcome<out T> {
        data class Success<T>(val value: T) : BackgroundTaskOutcome<T>

        data class Failure(val error: Throwable) : BackgroundTaskOutcome<Nothing>
    }

    private const val BACKGROUND_TASK_POLL_MILLIS = 50L

    internal fun <T> captureBackgroundTaskOutcome(task: () -> T): BackgroundTaskOutcome<T> {
        return try {
            BackgroundTaskOutcome.Success(task())
        } catch (error: Throwable) {
            BackgroundTaskOutcome.Failure(error)
        }
    }

    internal fun <T> unwrapBackgroundTaskOutcome(
        outcome: BackgroundTaskOutcome<T>,
        indicatorCancelled: Boolean,
    ): Result<T> {
        return when (outcome) {
            is BackgroundTaskOutcome.Success -> Result.success(outcome.value)
            is BackgroundTaskOutcome.Failure -> {
                val error = outcome.error
                if (error is ProcessCanceledException) {
                    throw error
                }
                if (error is CancellationException && indicatorCancelled) {
                    throw ProcessCanceledException()
                }
                Result.failure(error)
            }
        }
    }

    private fun openSpecTab(project: Project, afterOpen: () -> Unit) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.show {
            ChatToolWindowFactory.ensurePrimaryContents(project, toolWindow)
            ChatToolWindowFactory.selectSpecContent(toolWindow, project)
            toolWindow.activate(null)
            ApplicationManager.getApplication().invokeLater(afterOpen)
        }
    }

    private fun showErrorDialog(project: Project, title: String, message: String) {
        Messages.showErrorDialog(project, message, title)
    }

    private fun <T> showSelectionPopup(
        project: Project,
        title: String,
        items: List<T>,
        text: (T) -> String,
        onChosen: (T) -> Unit,
    ) {
        when (items.size) {
            0 -> return
            1 -> {
                onChosen(items.first())
                return
            }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(
            object : BaseListPopupStep<T>(title, items) {
                override fun getTextFor(value: T): String = text(value)

                override fun isSpeedSearchEnabled(): Boolean = true

                override fun onChosen(selectedValue: T, finalChoice: Boolean): PopupStep<*>? {
                    if (finalChoice) {
                        ApplicationManager.getApplication().invokeLater {
                            onChosen(selectedValue)
                        }
                    }
                    return PopupStep.FINAL_CHOICE
                }
            },
        )
        popup.showCenteredInCurrentWindow(project)
    }
}
