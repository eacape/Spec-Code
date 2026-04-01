package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.core.ManagedMergedOutputProcess
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

internal class ImprovedChatPanelWorkflowCommandRunner(
    private val workingDirectory: File? = null,
    val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    private val joinTimeoutMillis: Long = DEFAULT_JOIN_TIMEOUT_MILLIS,
    private val stopGraceSeconds: Long = DEFAULT_STOP_GRACE_SECONDS,
    val outputLimitChars: Int = DEFAULT_OUTPUT_MAX_CHARS,
    private val processStarter: (List<String>, File?) -> Process = { command, directory ->
        ProcessBuilder(command)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
    },
    private val shellCommandBuilder: (String) -> List<String> = { command ->
        buildShellCommand(command)
    },
) {

    private val runningCommands = ConcurrentHashMap<String, RunningWorkflowCommand>()

    fun isRunning(command: String): Boolean {
        val running = runningCommands[command] ?: return false
        if (running.handle.process.isAlive) {
            return true
        }
        runningCommands.remove(command, running)
        return false
    }

    fun execute(
        command: String,
        onStarted: (() -> Unit)? = null,
    ): ImprovedChatPanelWorkflowCommandRunOutcome {
        val started = try {
            start(command)
        } catch (_: CommandAlreadyRunningException) {
            return ImprovedChatPanelWorkflowCommandRunOutcome.AlreadyRunning
        } catch (error: Exception) {
            return ImprovedChatPanelWorkflowCommandRunOutcome.FailedToStart(
                error.message ?: error::class.java.simpleName,
            )
        }
        onStarted?.invoke()
        val completion = started.handle.awaitCompletion(
            timeout = timeoutSeconds,
            timeoutUnit = TimeUnit.SECONDS,
            joinTimeoutMillis = joinTimeoutMillis,
            timeoutDestroyWait = TIMEOUT_DESTROY_WAIT_SECONDS,
            timeoutDestroyWaitUnit = TimeUnit.SECONDS,
        )
        runningCommands.remove(command, started)
        return ImprovedChatPanelWorkflowCommandRunOutcome.Completed(
            ImprovedChatPanelWorkflowCommandExecutionResult(
                success = !completion.timedOut && !completion.stoppedByUser && completion.exitCode == 0,
                exitCode = completion.exitCode,
                output = completion.output,
                timedOut = completion.timedOut,
                stoppedByUser = completion.stoppedByUser,
                outputTruncated = completion.outputTruncated,
            ),
        )
    }

    fun stop(command: String): ImprovedChatPanelWorkflowCommandStopOutcome {
        val running = runningCommands[command] ?: return ImprovedChatPanelWorkflowCommandStopOutcome.NotRunning
        if (!running.handle.process.isAlive) {
            runningCommands.remove(command, running)
            return ImprovedChatPanelWorkflowCommandStopOutcome.NotRunning
        }
        if (!running.handle.stopRequested.compareAndSet(false, true)) {
            return ImprovedChatPanelWorkflowCommandStopOutcome.AlreadyStopping
        }
        return runCatching {
            running.handle.destroy(stopGraceSeconds, TimeUnit.SECONDS)
            ImprovedChatPanelWorkflowCommandStopOutcome.Stopping
        }.getOrElse { error ->
            ImprovedChatPanelWorkflowCommandStopOutcome.Failed(error)
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

    private fun start(command: String): RunningWorkflowCommand {
        val process = processStarter(shellCommandBuilder(command), workingDirectory)
        val handle = ManagedMergedOutputProcess.start(
            process = process,
            outputLimitChars = outputLimitChars,
            threadName = "workflow-command-output-${command.hashCode()}",
            stopRequested = AtomicBoolean(false),
        )
        val running = RunningWorkflowCommand(
            command = command,
            handle = handle,
        )
        val previous = runningCommands.putIfAbsent(command, running)
        if (previous != null && previous.handle.process.isAlive) {
            process.destroyForcibly()
            throw CommandAlreadyRunningException()
        }
        if (previous != null && !previous.handle.process.isAlive) {
            runningCommands[command] = running
        }
        return running
    }

    private data class RunningWorkflowCommand(
        val command: String,
        val handle: ManagedMergedOutputProcess,
    )

    private class CommandAlreadyRunningException : IllegalStateException("Command already running")

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 1800L
        const val DEFAULT_OUTPUT_MAX_CHARS = 12_000

        private const val DEFAULT_JOIN_TIMEOUT_MILLIS = 2000L
        private const val DEFAULT_STOP_GRACE_SECONDS = 3L
        private const val TIMEOUT_DESTROY_WAIT_SECONDS = 2L

        private fun buildShellCommand(command: String): List<String> {
            return if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")) {
                listOf("cmd", "/c", command)
            } else {
                listOf("bash", "-lc", command)
            }
        }
    }
}
