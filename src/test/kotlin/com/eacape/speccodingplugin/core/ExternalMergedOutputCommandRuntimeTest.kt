package com.eacape.speccodingplugin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ExternalMergedOutputCommandRuntimeTest {

    @Test
    fun `execute should delegate process and spec to shared runtime`() {
        val process = IdleProcess()
        var capturedProcess: Process? = null
        var capturedStartSpec: ExternalMergedOutputCommandSpec? = null
        var capturedAwaitSpec: ExternalMergedOutputCommandSpec? = null
        var capturedStopRequested: AtomicBoolean? = null
        val runtime = ExternalMergedOutputCommandRuntime(
            handleStarter = { startedProcess, spec, stopRequested ->
                capturedProcess = startedProcess
                capturedStartSpec = spec
                capturedStopRequested = stopRequested
                ManagedMergedOutputProcess.start(
                    process = startedProcess,
                    outputLimitChars = spec.outputLimitChars,
                    threadName = spec.threadName,
                    stopRequested = stopRequested,
                )
            },
            completionAwaiter = { _, spec ->
                capturedAwaitSpec = spec
                ManagedMergedOutputProcessCompletion(
                    exitCode = 7,
                    output = "ok",
                    timedOut = false,
                    stoppedByUser = false,
                    outputTruncated = true,
                )
            },
        )
        val spec = ExternalMergedOutputCommandSpec(
            outputLimitChars = 128,
            threadName = "shared-runtime",
            timeout = 25,
            timeoutUnit = TimeUnit.MILLISECONDS,
            outputJoinTimeoutMillis = 50,
            timeoutDestroyWait = 75,
            timeoutDestroyWaitUnit = TimeUnit.MILLISECONDS,
        )

        val result = runtime.execute(
            processStarter = { process },
            spec = spec,
        ).getOrThrow()

        assertSame(process, capturedProcess)
        assertEquals(spec, capturedStartSpec)
        assertEquals(spec, capturedAwaitSpec)
        assertFalse(capturedStopRequested!!.get())
        assertEquals(7, result.exitCode)
        assertEquals("ok", result.output)
        assertTrue(result.outputTruncated)
    }

    @Test
    fun `start should allow caller provided stop signal to flow into managed handle`() {
        val process = IdleProcess()
        var capturedStopRequested: AtomicBoolean? = null
        val runtime = ExternalMergedOutputCommandRuntime(
            handleStarter = { startedProcess, spec, stopRequested ->
                capturedStopRequested = stopRequested
                ManagedMergedOutputProcess.start(
                    process = startedProcess,
                    outputLimitChars = spec.outputLimitChars,
                    threadName = spec.threadName,
                    stopRequested = stopRequested,
                )
            },
        )
        val spec = ExternalMergedOutputCommandSpec(
            outputLimitChars = 64,
            threadName = "shared-runtime",
            timeout = 1,
            timeoutUnit = TimeUnit.SECONDS,
            outputJoinTimeoutMillis = 1,
            timeoutDestroyWait = 1,
            timeoutDestroyWaitUnit = TimeUnit.SECONDS,
        )
        val stopRequested = AtomicBoolean(true)

        val handle = runtime.start(
            processStarter = { process },
            spec = spec,
            stopRequested = stopRequested,
        ).getOrThrow()

        assertSame(stopRequested, capturedStopRequested)
        assertSame(process, handle.process)
        assertTrue(handle.stopRequested.get())
    }

    @Test
    fun `execute should surface startup failures without invoking awaiter`() {
        var awaiterInvoked = false
        val runtime = ExternalMergedOutputCommandRuntime(
            completionAwaiter = { _, _ ->
                awaiterInvoked = true
                error("awaiter should not be called")
            },
        )
        val spec = ExternalMergedOutputCommandSpec(
            outputLimitChars = 64,
            threadName = "shared-runtime",
            timeout = 1,
            timeoutUnit = TimeUnit.SECONDS,
            outputJoinTimeoutMillis = 1,
            timeoutDestroyWait = 1,
            timeoutDestroyWaitUnit = TimeUnit.SECONDS,
        )

        val result = runtime.execute(
            processStarter = { error("missing executable") },
            spec = spec,
        )

        assertTrue(result.isFailure)
        assertEquals("missing executable", result.exceptionOrNull()?.message)
        assertFalse(awaiterInvoked)
    }

    private class IdleProcess : Process() {
        private val input = ByteArrayInputStream(ByteArray(0))
        private val error = ByteArrayInputStream(ByteArray(0))
        private val output = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = error

        override fun getOutputStream(): OutputStream = output

        override fun waitFor(): Int = 0

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

        override fun exitValue(): Int = 0

        override fun destroy() = Unit

        override fun destroyForcibly(): Process = this

        override fun isAlive(): Boolean = false
    }
}
