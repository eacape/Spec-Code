package com.eacape.speccodingplugin.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class WorkflowCommandFailureKind(val label: String) {
    EXECUTABLE_NOT_FOUND("executable-not-found"),
    WORKING_DIRECTORY_UNAVAILABLE("working-directory-unavailable"),
    ACCESS_DENIED("access-denied"),
    STARTUP_FAILED("startup-failed"),
}

internal data class WorkflowCommandStartupDiagnostic(
    val kind: WorkflowCommandFailureKind,
    val commandKey: String,
    val launchCommand: List<String>,
    val workingDirectory: File?,
    val startupErrorMessage: String? = null,
) {
    fun renderMessage(): String {
        return "Workflow command '$commandKey' failed to start (${kind.label}): ${renderDetail()}"
    }

    fun renderDetail(): String {
        return when (kind) {
            WorkflowCommandFailureKind.EXECUTABLE_NOT_FOUND ->
                "workflow executable was not found: ${renderExecutable()}"

            WorkflowCommandFailureKind.WORKING_DIRECTORY_UNAVAILABLE ->
                "working directory is unavailable: ${renderWorkingDirectory()}"

            WorkflowCommandFailureKind.ACCESS_DENIED ->
                "access denied while starting workflow command in ${renderWorkingDirectory()}"

            WorkflowCommandFailureKind.STARTUP_FAILED ->
                startupErrorMessage?.ifBlank { null }
                    ?: "workflow process could not be started"
        }
    }

    private fun renderExecutable(): String {
        return launchCommand.firstOrNull()
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.ifBlank { launchCommand.firstOrNull().orEmpty() }
            ?: "unknown"
    }

    private fun renderWorkingDirectory(): String {
        return workingDirectory?.absolutePath ?: "default process working directory"
    }
}

internal sealed interface WorkflowCommandProcessRunResult {
    data class Completed(
        val result: WorkflowCommandProcessExecutionResult,
    ) : WorkflowCommandProcessRunResult

    data class FailedToStart(
        val diagnostic: WorkflowCommandStartupDiagnostic,
    ) : WorkflowCommandProcessRunResult {
        val errorMessage: String
            get() = diagnostic.renderMessage()
    }

    data object AlreadyRunning : WorkflowCommandProcessRunResult
}

internal sealed interface WorkflowCommandProcessStopResult {
    data object Stopping : WorkflowCommandProcessStopResult

    data object AlreadyStopping : WorkflowCommandProcessStopResult

    data object NotRunning : WorkflowCommandProcessStopResult

    data class Failed(
        val error: Throwable,
    ) : WorkflowCommandProcessStopResult
}

internal data class WorkflowCommandProcessExecutionResult(
    val exitCode: Int? = null,
    val output: String = "",
    val timedOut: Boolean = false,
    val stoppedByUser: Boolean = false,
    val outputTruncated: Boolean = false,
)

internal class WorkflowCommandProcessRuntime(
    private val processStarter: (File?, List<String>) -> Process = { workingDirectory, command ->
        ExternalProcessLauncher.start(
            ExternalProcessLaunchSpec(
                command = command,
                workingDirectory = workingDirectory,
                redirectErrorStream = true,
            ),
        )
    },
    private val mergedOutputRuntime: ExternalMergedOutputCommandRuntime = ExternalMergedOutputCommandRuntime(),
    private val outputLimitChars: Int = DEFAULT_OUTPUT_LIMIT_CHARS,
    private val outputJoinTimeoutMillis: Long = DEFAULT_OUTPUT_JOIN_TIMEOUT_MILLIS,
    private val stopGraceSeconds: Long = DEFAULT_STOP_GRACE_SECONDS,
    private val forceDestroyWaitSeconds: Long = DEFAULT_FORCE_DESTROY_WAIT_SECONDS,
) {

    private val runningCommands = ConcurrentHashMap<String, RunningWorkflowCommand>()

    fun isRunning(commandKey: String): Boolean {
        val running = runningCommands[commandKey] ?: return false
        if (running.handle.process.isAlive) {
            return true
        }
        runningCommands.remove(commandKey, running)
        return false
    }

    fun execute(
        commandKey: String,
        launchCommand: List<String>,
        workingDirectory: File?,
        timeoutSeconds: Long,
        onStarted: (() -> Unit)? = null,
    ): WorkflowCommandProcessRunResult {
        val runtimeSpec = ExternalMergedOutputCommandSpec(
            outputLimitChars = outputLimitChars,
            threadName = "workflow-command-output-${commandKey.hashCode()}",
            timeout = timeoutSeconds,
            timeoutUnit = TimeUnit.SECONDS,
            outputJoinTimeoutMillis = outputJoinTimeoutMillis,
            timeoutDestroyWait = forceDestroyWaitSeconds,
            timeoutDestroyWaitUnit = TimeUnit.SECONDS,
        )
        val started = try {
            start(commandKey, launchCommand, workingDirectory, runtimeSpec)
        } catch (_: CommandAlreadyRunningException) {
            return WorkflowCommandProcessRunResult.AlreadyRunning
        } catch (error: Exception) {
            return WorkflowCommandProcessRunResult.FailedToStart(
                diagnostic = WorkflowCommandFailureDiagnostics.diagnoseStartup(
                    commandKey = commandKey,
                    launchCommand = launchCommand,
                    workingDirectory = workingDirectory,
                    startupErrorMessage = error.message ?: error::class.java.simpleName,
                ),
            )
        }
        onStarted?.invoke()
        try {
            val completion = mergedOutputRuntime.await(
                handle = started.handle,
                spec = runtimeSpec,
            )
            return WorkflowCommandProcessRunResult.Completed(
                WorkflowCommandProcessExecutionResult(
                    exitCode = completion.exitCode,
                    output = completion.output,
                    timedOut = completion.timedOut,
                    stoppedByUser = completion.stoppedByUser,
                    outputTruncated = completion.outputTruncated,
                ),
            )
        } finally {
            runningCommands.remove(commandKey, started)
        }
    }

    fun stop(commandKey: String): WorkflowCommandProcessStopResult {
        val running = runningCommands[commandKey] ?: return WorkflowCommandProcessStopResult.NotRunning
        if (!running.handle.process.isAlive) {
            runningCommands.remove(commandKey, running)
            return WorkflowCommandProcessStopResult.NotRunning
        }
        if (!running.handle.stopRequested.compareAndSet(false, true)) {
            return WorkflowCommandProcessStopResult.AlreadyStopping
        }
        return runCatching {
            running.handle.destroy(stopGraceSeconds, TimeUnit.SECONDS)
            WorkflowCommandProcessStopResult.Stopping
        }.getOrElse { error ->
            WorkflowCommandProcessStopResult.Failed(error)
        }
    }

    fun dispose() {
        runningCommands.values.forEach { running ->
            runCatching {
                running.handle.dispose()
            }
        }
        runningCommands.clear()
    }

    private fun start(
        commandKey: String,
        launchCommand: List<String>,
        workingDirectory: File?,
        runtimeSpec: ExternalMergedOutputCommandSpec,
    ): RunningWorkflowCommand {
        val handle = mergedOutputRuntime.start(
            processStarter = { processStarter(workingDirectory, launchCommand) },
            spec = runtimeSpec,
            stopRequested = AtomicBoolean(false),
        ).getOrElse { error ->
            throw error
        }
        val running = RunningWorkflowCommand(
            commandKey = commandKey,
            handle = handle,
        )
        val previous = runningCommands.putIfAbsent(commandKey, running)
        if (previous != null && previous.handle.process.isAlive) {
            handle.dispose()
            throw CommandAlreadyRunningException()
        }
        if (previous != null && !previous.handle.process.isAlive) {
            runningCommands[commandKey] = running
        }
        return running
    }

    private data class RunningWorkflowCommand(
        val commandKey: String,
        val handle: ManagedMergedOutputProcess,
    )

    private class CommandAlreadyRunningException : IllegalStateException("Command already running")

    private companion object {
        private const val DEFAULT_OUTPUT_LIMIT_CHARS = 12_000
        private const val DEFAULT_OUTPUT_JOIN_TIMEOUT_MILLIS = 2_000L
        private const val DEFAULT_STOP_GRACE_SECONDS = 3L
        private const val DEFAULT_FORCE_DESTROY_WAIT_SECONDS = 2L
    }
}

internal object WorkflowCommandFailureDiagnostics {

    fun diagnoseStartup(
        commandKey: String,
        launchCommand: List<String>,
        workingDirectory: File?,
        startupErrorMessage: String,
    ): WorkflowCommandStartupDiagnostic {
        return WorkflowCommandStartupDiagnostic(
            kind = classifyStartupFailureKind(
                workingDirectory = workingDirectory,
                startupErrorMessage = startupErrorMessage,
            ),
            commandKey = commandKey,
            launchCommand = launchCommand,
            workingDirectory = workingDirectory,
            startupErrorMessage = startupErrorMessage,
        )
    }

    private fun classifyStartupFailureKind(
        workingDirectory: File?,
        startupErrorMessage: String,
    ): WorkflowCommandFailureKind {
        return when (
            ExternalProcessStartupFailureClassifier.classify(
                startupErrorMessage = startupErrorMessage,
                workingDirectory = workingDirectory,
            )
        ) {
            ExternalProcessStartupFailureKind.EXECUTABLE_NOT_FOUND ->
                WorkflowCommandFailureKind.EXECUTABLE_NOT_FOUND

            ExternalProcessStartupFailureKind.WORKING_DIRECTORY_UNAVAILABLE ->
                WorkflowCommandFailureKind.WORKING_DIRECTORY_UNAVAILABLE

            ExternalProcessStartupFailureKind.ACCESS_DENIED ->
                WorkflowCommandFailureKind.ACCESS_DENIED

            ExternalProcessStartupFailureKind.STARTUP_FAILED ->
                WorkflowCommandFailureKind.STARTUP_FAILED
        }
    }
}
