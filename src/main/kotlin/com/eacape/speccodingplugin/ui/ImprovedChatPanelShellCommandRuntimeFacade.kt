package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.core.OperationRequest
import com.eacape.speccodingplugin.rollback.WorkspaceChangesetCollector
import java.io.File

internal class ImprovedChatPanelShellCommandRuntimeFacade(
    private val prepareExecutionDelegate: (ImprovedChatPanelShellCommandExecutionRequest) -> ImprovedChatPanelShellCommandExecutionPlan,
    private val executeInBackgroundDelegate: (ImprovedChatPanelWorkflowCommandBackgroundRequest) -> ImprovedChatPanelWorkflowCommandBackgroundResult,
    private val prepareStopDelegate: (String) -> ImprovedChatPanelWorkflowCommandStopExecutionPlan?,
    private val performStopDelegate: (ImprovedChatPanelWorkflowCommandStopExecutionPlan) -> ImprovedChatPanelWorkflowCommandExecutionOutcomePlan?,
    private val disposeRuntime: () -> Unit,
) {

    fun prepareExecution(
        request: ImprovedChatPanelShellCommandExecutionRequest,
    ): ImprovedChatPanelShellCommandExecutionPlan = prepareExecutionDelegate(request)

    fun executeInBackground(
        request: ImprovedChatPanelWorkflowCommandBackgroundRequest,
    ): ImprovedChatPanelWorkflowCommandBackgroundResult = executeInBackgroundDelegate(request)

    fun prepareStop(
        command: String,
    ): ImprovedChatPanelWorkflowCommandStopExecutionPlan? = prepareStopDelegate(command)

    fun performStop(
        stopPlan: ImprovedChatPanelWorkflowCommandStopExecutionPlan,
    ): ImprovedChatPanelWorkflowCommandExecutionOutcomePlan? = performStopDelegate(stopPlan)

    fun dispose() {
        disposeRuntime()
    }

    companion object {
        fun create(
            workingDirectory: File?,
            authorizeCommandExecution: (OperationRequest, String) -> ImprovedChatPanelWorkflowCommandPermissionOutcome,
            captureBeforeSnapshot: () -> WorkspaceChangesetCollector.Snapshot?,
            sanitizeDisplayOutput: (String) -> String,
            showRunningStatus: (String) -> Unit,
            executeInIdeTerminal: (String, String) -> Unit,
        ): ImprovedChatPanelShellCommandRuntimeFacade {
            val workflowCommandRunner = ImprovedChatPanelWorkflowCommandRunner(
                workingDirectory = workingDirectory,
            )
            val workflowCommandExecutionCoordinator = ImprovedChatPanelWorkflowCommandExecutionCoordinator(
                timeoutSeconds = workflowCommandRunner.timeoutSeconds,
                outputLimitChars = workflowCommandRunner.outputLimitChars,
                captureBeforeSnapshot = captureBeforeSnapshot,
                executeCommand = workflowCommandRunner::execute,
                sanitizeDisplayOutput = sanitizeDisplayOutput,
                showRunningStatus = showRunningStatus,
            )
            val terminalCommandExecutionCoordinator = ImprovedChatPanelTerminalCommandExecutionCoordinator(
                executeInIdeTerminal = executeInIdeTerminal,
            )
            val shellCommandExecutionCoordinator = ImprovedChatPanelShellCommandExecutionCoordinator(
                authorizeCommandExecution = authorizeCommandExecution,
                isWorkflowCommandRunning = workflowCommandRunner::isRunning,
                executeTerminalCommand = terminalCommandExecutionCoordinator::execute,
            )
            val workflowCommandStopCoordinator = ImprovedChatPanelWorkflowCommandStopCoordinator(
                isWorkflowCommandRunning = workflowCommandRunner::isRunning,
                stopWorkflowCommand = workflowCommandRunner::stop,
            )
            return ImprovedChatPanelShellCommandRuntimeFacade(
                prepareExecutionDelegate = shellCommandExecutionCoordinator::execute,
                executeInBackgroundDelegate = workflowCommandExecutionCoordinator::executeInBackground,
                prepareStopDelegate = workflowCommandStopCoordinator::prepareStop,
                performStopDelegate = workflowCommandStopCoordinator::performStop,
                disposeRuntime = workflowCommandRunner::dispose,
            )
        }
    }
}
