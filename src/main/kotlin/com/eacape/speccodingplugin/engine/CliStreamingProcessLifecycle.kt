package com.eacape.speccodingplugin.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class CliTimeoutWatchdog(
    val timedOut: AtomicBoolean,
    val future: CompletableFuture<Void>?,
)

internal class CliStreamingProcessLifecycle(
    private val logInfo: (String) -> Unit = {},
    private val sleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    private val asyncRunner: (() -> Unit) -> CompletableFuture<Void> = { task ->
        CompletableFuture.runAsync { task() }
    },
    private val exitAfterDrainMillis: Long = STREAM_PROCESS_EXIT_AFTER_DRAIN_MILLIS,
    private val forceExitAfterDrainMillis: Long = STREAM_PROCESS_FORCE_EXIT_AFTER_DRAIN_MILLIS,
    private val exitAfterIdleMillis: Long = STREAM_PROCESS_EXIT_AFTER_IDLE_MILLIS,
    private val forceExitAfterIdleMillis: Long = STREAM_PROCESS_FORCE_EXIT_AFTER_IDLE_MILLIS,
) {

    fun timeoutMessage(timeoutSeconds: Long?): String {
        return if (timeoutSeconds != null && timeoutSeconds > 0) {
            "CLI request timed out after $timeoutSeconds seconds"
        } else {
            "CLI request timed out"
        }
    }

    fun startTimeoutWatchdog(
        process: Process,
        timeoutSeconds: Long?,
    ): CliTimeoutWatchdog {
        val timedOut = AtomicBoolean(false)
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return CliTimeoutWatchdog(timedOut, null)
        }

        val future = asyncRunner {
            try {
                sleeper(TimeUnit.SECONDS.toMillis(timeoutSeconds))
                if (process.isAlive) {
                    timedOut.set(true)
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return CliTimeoutWatchdog(timedOut, future)
    }

    fun terminateAfterStreamDrain(process: Process, requestId: String): Boolean {
        logInfo("CLI process for request=$requestId kept running after stdout/stderr drained; terminating it")
        process.destroy()
        if (waitForProcessExit(process, exitAfterDrainMillis)) {
            return true
        }
        process.destroyForcibly()
        return waitForProcessExit(process, forceExitAfterDrainMillis)
    }

    fun terminateAfterInactivity(
        process: Process,
        requestId: String,
        inactivityTimeoutMillis: Long,
    ): Boolean {
        logInfo(
            "CLI process for request=$requestId became idle for ${inactivityTimeoutMillis}ms " +
                "after stdout activity; terminating it",
        )
        process.destroy()
        if (waitForProcessExit(process, exitAfterIdleMillis)) {
            return true
        }
        process.destroyForcibly()
        return waitForProcessExit(process, forceExitAfterIdleMillis)
    }

    private fun waitForProcessExit(process: Process, timeoutMillis: Long): Boolean {
        if (!process.isAlive) {
            return true
        }
        return runCatching {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        }.getOrDefault(!process.isAlive)
    }

    private companion object {
        private const val STREAM_PROCESS_EXIT_AFTER_DRAIN_MILLIS = 200L
        private const val STREAM_PROCESS_FORCE_EXIT_AFTER_DRAIN_MILLIS = 400L
        private const val STREAM_PROCESS_EXIT_AFTER_IDLE_MILLIS = 200L
        private const val STREAM_PROCESS_FORCE_EXIT_AFTER_IDLE_MILLIS = 400L
    }
}
