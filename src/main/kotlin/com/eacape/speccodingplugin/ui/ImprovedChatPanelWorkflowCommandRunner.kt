package com.eacape.speccodingplugin.ui

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
        if (running.process.isAlive) {
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

        val timedOut = !started.process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (timedOut) {
            started.stopRequested.set(true)
            started.process.destroyForcibly()
            started.process.waitFor(2, TimeUnit.SECONDS)
        }

        started.outputReaderThread.join(joinTimeoutMillis)
        runningCommands.remove(command, started)

        val exitCode = runCatching { started.process.exitValue() }.getOrNull()
        return ImprovedChatPanelWorkflowCommandRunOutcome.Completed(
            ImprovedChatPanelWorkflowCommandExecutionResult(
                success = !timedOut && !started.stopRequested.get() && exitCode == 0,
                exitCode = exitCode,
                output = started.outputBuffer.toString().trim(),
                timedOut = timedOut,
                stoppedByUser = started.stopRequested.get() && !timedOut,
                outputTruncated = started.outputTruncated.get(),
            ),
        )
    }

    fun stop(command: String): ImprovedChatPanelWorkflowCommandStopOutcome {
        val running = runningCommands[command] ?: return ImprovedChatPanelWorkflowCommandStopOutcome.NotRunning
        if (!running.process.isAlive) {
            runningCommands.remove(command, running)
            return ImprovedChatPanelWorkflowCommandStopOutcome.NotRunning
        }
        if (!running.stopRequested.compareAndSet(false, true)) {
            return ImprovedChatPanelWorkflowCommandStopOutcome.AlreadyStopping
        }
        return runCatching {
            running.process.destroy()
            if (running.process.isAlive) {
                val exited = running.process.waitFor(stopGraceSeconds, TimeUnit.SECONDS)
                if (!exited && running.process.isAlive) {
                    running.process.destroyForcibly()
                }
            }
            ImprovedChatPanelWorkflowCommandStopOutcome.Stopping
        }.getOrElse { error ->
            ImprovedChatPanelWorkflowCommandStopOutcome.Failed(error)
        }
    }

    fun dispose() {
        runningCommands.values.forEach { running ->
            runCatching {
                running.stopRequested.set(true)
                if (running.process.isAlive) {
                    running.process.destroyForcibly()
                }
            }
        }
        runningCommands.clear()
    }

    private fun start(command: String): RunningWorkflowCommand {
        val process = processStarter(shellCommandBuilder(command), workingDirectory)
        val outputBuffer = StringBuilder()
        val outputTruncated = AtomicBoolean(false)
        val stopRequested = AtomicBoolean(false)
        val outputReaderThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(outputBuffer) {
                        if (outputBuffer.length < outputLimitChars) {
                            if (outputBuffer.isNotEmpty()) {
                                outputBuffer.append('\n')
                            }
                            outputBuffer.append(line)
                        } else {
                            outputTruncated.set(true)
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "workflow-command-output-${command.hashCode()}"
            start()
        }

        val running = RunningWorkflowCommand(
            command = command,
            process = process,
            outputBuffer = outputBuffer,
            outputTruncated = outputTruncated,
            stopRequested = stopRequested,
            outputReaderThread = outputReaderThread,
        )
        val previous = runningCommands.putIfAbsent(command, running)
        if (previous != null && previous.process.isAlive) {
            process.destroyForcibly()
            throw CommandAlreadyRunningException()
        }
        if (previous != null && !previous.process.isAlive) {
            runningCommands[command] = running
        }
        return running
    }

    private data class RunningWorkflowCommand(
        val command: String,
        val process: Process,
        val outputBuffer: StringBuilder,
        val outputTruncated: AtomicBoolean,
        val stopRequested: AtomicBoolean,
        val outputReaderThread: Thread,
    )

    private class CommandAlreadyRunningException : IllegalStateException("Command already running")

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 1800L
        const val DEFAULT_OUTPUT_MAX_CHARS = 12_000

        private const val DEFAULT_JOIN_TIMEOUT_MILLIS = 2000L
        private const val DEFAULT_STOP_GRACE_SECONDS = 3L

        private fun buildShellCommand(command: String): List<String> {
            return if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")) {
                listOf("cmd", "/c", command)
            } else {
                listOf("bash", "-lc", command)
            }
        }
    }
}
