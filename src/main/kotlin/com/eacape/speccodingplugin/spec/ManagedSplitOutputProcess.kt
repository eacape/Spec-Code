package com.eacape.speccodingplugin.spec

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class ManagedSplitOutputProcessCompletion(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val stoppedByUser: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
)

internal class ManagedSplitOutputProcess private constructor(
    val process: Process,
    private val stdoutCapture: BoundedTextCapture,
    private val stderrCapture: BoundedTextCapture,
    val stopRequested: AtomicBoolean,
    private val stdoutReaderThread: Thread,
    private val stderrReaderThread: Thread,
) {

    fun awaitCompletion(
        timeout: Long,
        timeoutUnit: TimeUnit,
        joinTimeoutMillis: Long,
        timeoutDestroyGraceWait: Long,
        timeoutDestroyGraceWaitUnit: TimeUnit,
        timeoutDestroyForceWait: Long,
        timeoutDestroyForceWaitUnit: TimeUnit,
    ): ManagedSplitOutputProcessCompletion {
        val timedOut = !process.waitFor(timeout, timeoutUnit)
        if (timedOut) {
            stopRequested.set(true)
            process.destroy()
            if (process.isAlive) {
                val exited = process.waitFor(timeoutDestroyGraceWait, timeoutDestroyGraceWaitUnit)
                if (!exited && process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(timeoutDestroyForceWait, timeoutDestroyForceWaitUnit)
                }
            }
        }

        stdoutReaderThread.join(joinTimeoutMillis)
        stderrReaderThread.join(joinTimeoutMillis)
        return ManagedSplitOutputProcessCompletion(
            exitCode = if (timedOut) null else runCatching { process.exitValue() }.getOrNull(),
            stdout = stdoutCapture.finish(),
            stderr = stderrCapture.finish(),
            timedOut = timedOut,
            stoppedByUser = stopRequested.get() && !timedOut,
            stdoutTruncated = stdoutCapture.truncated,
            stderrTruncated = stderrCapture.truncated,
        )
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
            stdoutThreadName: String,
            stderrThreadName: String,
            stopRequested: AtomicBoolean = AtomicBoolean(false),
        ): ManagedSplitOutputProcess {
            val stdoutCapture = BoundedTextCapture(outputLimitChars)
            val stderrCapture = BoundedTextCapture(outputLimitChars)
            val stdoutReaderThread = startReaderThread(
                inputStream = process.inputStream,
                capture = stdoutCapture,
                threadName = stdoutThreadName,
            )
            val stderrReaderThread = startReaderThread(
                inputStream = process.errorStream,
                capture = stderrCapture,
                threadName = stderrThreadName,
            )
            return ManagedSplitOutputProcess(
                process = process,
                stdoutCapture = stdoutCapture,
                stderrCapture = stderrCapture,
                stopRequested = stopRequested,
                stdoutReaderThread = stdoutReaderThread,
                stderrReaderThread = stderrReaderThread,
            )
        }

        private fun startReaderThread(
            inputStream: InputStream,
            capture: BoundedTextCapture,
            threadName: String,
        ): Thread {
            return Thread {
                InputStreamReader(inputStream, StandardCharsets.UTF_8).use { reader ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) {
                            break
                        }
                        capture.append(buffer, read)
                    }
                }
            }.apply {
                isDaemon = true
                name = threadName
                start()
            }
        }
    }

    private class BoundedTextCapture(
        private val maxChars: Int,
    ) {
        private val lock = Any()
        private val buffer = StringBuilder()
        private var omittedChars: Int = 0

        var truncated: Boolean = false
            private set

        fun append(chunk: CharArray, length: Int) {
            synchronized(lock) {
                val remainingCapacity = (maxChars - buffer.length).coerceAtLeast(0)
                val charsToAppend = minOf(remainingCapacity, length)
                if (charsToAppend > 0) {
                    buffer.append(chunk, 0, charsToAppend)
                }
                if (charsToAppend < length) {
                    truncated = true
                    omittedChars += length - charsToAppend
                }
            }
        }

        fun finish(): String {
            synchronized(lock) {
                if (!truncated) {
                    return buffer.toString()
                }
                val suffix = "...[truncated $omittedChars chars]"
                return buildString {
                    append(buffer)
                    if (isNotEmpty() && this[length - 1] != '\n') {
                        append('\n')
                    }
                    append(suffix)
                }
            }
        }
    }
}
