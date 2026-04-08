package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.core.WorkflowCommandProcessRunResult
import com.eacape.speccodingplugin.core.WorkflowCommandProcessRuntime
import com.eacape.speccodingplugin.core.WorkflowCommandProcessStopResult
import java.io.File
import java.util.Locale

internal sealed interface ImprovedChatPanelWorkflowCommandRunOutcome {
    data class Completed(
        val execution: ImprovedChatPanelWorkflowCommandExecutionResult,
    ) : ImprovedChatPanelWorkflowCommandRunOutcome

    data class FailedToStart(
        val errorMessage: String,
    ) : ImprovedChatPanelWorkflowCommandRunOutcome

    data object AlreadyRunning : ImprovedChatPanelWorkflowCommandRunOutcome
}

internal sealed interface ImprovedChatPanelWorkflowCommandStopOutcome {
    data object Stopping : ImprovedChatPanelWorkflowCommandStopOutcome

    data object AlreadyStopping : ImprovedChatPanelWorkflowCommandStopOutcome

    data object NotRunning : ImprovedChatPanelWorkflowCommandStopOutcome

    data class Failed(
        val error: Throwable,
    ) : ImprovedChatPanelWorkflowCommandStopOutcome
}

internal data class ImprovedChatPanelWorkflowCommandExecutionResult(
    val success: Boolean,
    val exitCode: Int? = null,
    val output: String = "",
    val timedOut: Boolean = false,
    val stoppedByUser: Boolean = false,
    val error: String? = null,
    val outputTruncated: Boolean = false,
)

internal class ImprovedChatPanelWorkflowCommandRunner private constructor(
    private val workingDirectory: File? = null,
    val timeoutSeconds: Long,
    val outputLimitChars: Int,
    private val processRuntime: WorkflowCommandProcessRuntime,
    private val shellCommandBuilder: (String) -> List<String>,
) {

    internal constructor(
        workingDirectory: File? = null,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        joinTimeoutMillis: Long = DEFAULT_JOIN_TIMEOUT_MILLIS,
        stopGraceSeconds: Long = DEFAULT_STOP_GRACE_SECONDS,
        outputLimitChars: Int = DEFAULT_OUTPUT_MAX_CHARS,
        processStarter: ((List<String>, File?) -> Process)? = null,
        shellCommandBuilder: (String) -> List<String> = { command ->
            buildShellCommand(command)
        },
    ) : this(
        workingDirectory = workingDirectory,
        timeoutSeconds = timeoutSeconds,
        outputLimitChars = outputLimitChars,
        processRuntime = createProcessRuntime(
            processStarter = processStarter,
            outputLimitChars = outputLimitChars,
            joinTimeoutMillis = joinTimeoutMillis,
            stopGraceSeconds = stopGraceSeconds,
        ),
        shellCommandBuilder = shellCommandBuilder,
    )

    fun isRunning(command: String): Boolean {
        return processRuntime.isRunning(command)
    }

    fun execute(
        command: String,
        onStarted: (() -> Unit)? = null,
    ): ImprovedChatPanelWorkflowCommandRunOutcome {
        return when (
            val outcome = processRuntime.execute(
                commandKey = command,
                launchCommand = shellCommandBuilder(command),
                workingDirectory = workingDirectory,
                timeoutSeconds = timeoutSeconds,
                onStarted = onStarted,
            )
        ) {
            WorkflowCommandProcessRunResult.AlreadyRunning ->
                ImprovedChatPanelWorkflowCommandRunOutcome.AlreadyRunning
            is WorkflowCommandProcessRunResult.FailedToStart ->
                ImprovedChatPanelWorkflowCommandRunOutcome.FailedToStart(outcome.errorMessage)
            is WorkflowCommandProcessRunResult.Completed ->
                ImprovedChatPanelWorkflowCommandRunOutcome.Completed(
                    ImprovedChatPanelWorkflowCommandExecutionResult(
                        success = !outcome.result.timedOut &&
                            !outcome.result.stoppedByUser &&
                            outcome.result.exitCode == 0,
                        exitCode = outcome.result.exitCode,
                        output = outcome.result.output,
                        timedOut = outcome.result.timedOut,
                        stoppedByUser = outcome.result.stoppedByUser,
                        outputTruncated = outcome.result.outputTruncated,
                    ),
                )
        }
    }

    fun stop(command: String): ImprovedChatPanelWorkflowCommandStopOutcome {
        return when (val outcome = processRuntime.stop(command)) {
            WorkflowCommandProcessStopResult.AlreadyStopping ->
                ImprovedChatPanelWorkflowCommandStopOutcome.AlreadyStopping
            is WorkflowCommandProcessStopResult.Failed ->
                ImprovedChatPanelWorkflowCommandStopOutcome.Failed(outcome.error)
            WorkflowCommandProcessStopResult.NotRunning ->
                ImprovedChatPanelWorkflowCommandStopOutcome.NotRunning
            WorkflowCommandProcessStopResult.Stopping ->
                ImprovedChatPanelWorkflowCommandStopOutcome.Stopping
        }
    }

    fun dispose() {
        processRuntime.dispose()
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 1800L
        const val DEFAULT_OUTPUT_MAX_CHARS = 12_000

        private const val DEFAULT_JOIN_TIMEOUT_MILLIS = 2000L
        private const val DEFAULT_STOP_GRACE_SECONDS = 3L
        private const val TIMEOUT_DESTROY_WAIT_SECONDS = 2L

        private fun createProcessRuntime(
            processStarter: ((List<String>, File?) -> Process)?,
            outputLimitChars: Int,
            joinTimeoutMillis: Long,
            stopGraceSeconds: Long,
        ): WorkflowCommandProcessRuntime {
            val delegate = processStarter ?: return WorkflowCommandProcessRuntime(
                outputLimitChars = outputLimitChars,
                outputJoinTimeoutMillis = joinTimeoutMillis,
                stopGraceSeconds = stopGraceSeconds,
                forceDestroyWaitSeconds = TIMEOUT_DESTROY_WAIT_SECONDS,
            )
            return WorkflowCommandProcessRuntime(
                processStarter = { directory, command -> delegate(command, directory) },
                outputLimitChars = outputLimitChars,
                outputJoinTimeoutMillis = joinTimeoutMillis,
                stopGraceSeconds = stopGraceSeconds,
                forceDestroyWaitSeconds = TIMEOUT_DESTROY_WAIT_SECONDS,
            )
        }

        private fun buildShellCommand(command: String): List<String> {
            return if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")) {
                listOf("cmd", "/c", command)
            } else {
                listOf("bash", "-lc", command)
            }
        }
    }
}
