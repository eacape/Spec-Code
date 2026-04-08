package com.eacape.speccodingplugin.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface WorkflowCommandProcessRunResult {
    data class Completed(
        val result: WorkflowCommandProcessExecutionResult,
    ) : WorkflowCommandProcessRunResult

    data class FailedToStart(
        val errorMessage: String,
    ) : WorkflowCommandProcessRunResult

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
        ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()
    },
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
        val started = try {
            start(commandKey, launchCommand, workingDirectory)
        } catch (_: CommandAlreadyRunningException) {
            return WorkflowCommandProcessRunResult.AlreadyRunning
        } catch (error: Exception) {
            return WorkflowCommandProcessRunResult.FailedToStart(
                error.message ?: error::class.java.simpleName,
            )
        }
        onStarted?.invoke()
        val completion = started.handle.awaitCompletion(
            timeout = timeoutSeconds,
            timeoutUnit = TimeUnit.SECONDS,
            joinTimeoutMillis = outputJoinTimeoutMillis,
            timeoutDestroyWait = forceDestroyWaitSeconds,
            timeoutDestroyWaitUnit = TimeUnit.SECONDS,
        )
        runningCommands.remove(commandKey, started)
        return WorkflowCommandProcessRunResult.Completed(
            WorkflowCommandProcessExecutionResult(
                exitCode = completion.exitCode,
                output = completion.output,
                timedOut = completion.timedOut,
                stoppedByUser = completion.stoppedByUser,
                outputTruncated = completion.outputTruncated,
            ),
        )
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
    ): RunningWorkflowCommand {
        val process = processStarter(workingDirectory, launchCommand)
        val handle = ManagedMergedOutputProcess.start(
            process = process,
            outputLimitChars = outputLimitChars,
            threadName = "workflow-command-output-${commandKey.hashCode()}",
            stopRequested = AtomicBoolean(false),
        )
        val running = RunningWorkflowCommand(
            commandKey = commandKey,
            handle = handle,
        )
        val previous = runningCommands.putIfAbsent(commandKey, running)
        if (previous != null && previous.handle.process.isAlive) {
            process.destroyForcibly()
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
