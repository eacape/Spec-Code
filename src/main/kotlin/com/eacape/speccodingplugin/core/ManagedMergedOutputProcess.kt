package com.eacape.speccodingplugin.core

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class ManagedMergedOutputProcessCompletion(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean,
    val stoppedByUser: Boolean,
    val outputTruncated: Boolean,
)

internal class ManagedMergedOutputProcess private constructor(
    val process: Process,
    private val outputBuffer: StringBuilder,
    private val outputTruncated: AtomicBoolean,
    val stopRequested: AtomicBoolean,
    private val outputReaderThread: Thread,
) {

    fun awaitCompletion(
        timeout: Long,
        timeoutUnit: TimeUnit,
        joinTimeoutMillis: Long,
        timeoutDestroyWait: Long,
        timeoutDestroyWaitUnit: TimeUnit,
    ): ManagedMergedOutputProcessCompletion {
        val timedOut = !process.waitFor(timeout, timeoutUnit)
        if (timedOut) {
            stopRequested.set(true)
            process.destroyForcibly()
            process.waitFor(timeoutDestroyWait, timeoutDestroyWaitUnit)
        }

        outputReaderThread.join(joinTimeoutMillis)
        val exitCode = runCatching { process.exitValue() }.getOrNull()
        val output = synchronized(outputBuffer) { outputBuffer.toString().trim() }
        return ManagedMergedOutputProcessCompletion(
            exitCode = exitCode,
            output = output,
            timedOut = timedOut,
            stoppedByUser = stopRequested.get() && !timedOut,
            outputTruncated = outputTruncated.get(),
        )
    }

    fun destroy(graceTimeout: Long, graceTimeoutUnit: TimeUnit) {
        process.destroy()
        if (process.isAlive) {
            val exited = process.waitFor(graceTimeout, graceTimeoutUnit)
            if (!exited && process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    fun dispose() {
        stopRequested.set(true)
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }

    companion object {
        fun start(
            process: Process,
            outputLimitChars: Int,
            threadName: String,
            stopRequested: AtomicBoolean = AtomicBoolean(false),
        ): ManagedMergedOutputProcess {
            val outputBuffer = StringBuilder()
            val outputTruncated = AtomicBoolean(false)
            val outputReaderThread = Thread {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
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
                name = threadName
                start()
            }

            return ManagedMergedOutputProcess(
                process = process,
                outputBuffer = outputBuffer,
                outputTruncated = outputTruncated,
                stopRequested = stopRequested,
                outputReaderThread = outputReaderThread,
            )
        }
    }
}
