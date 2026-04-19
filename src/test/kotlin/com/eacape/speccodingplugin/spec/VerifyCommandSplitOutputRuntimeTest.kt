package com.eacape.speccodingplugin.spec

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

class VerifyCommandSplitOutputRuntimeTest {

    @Test
    fun `execute should delegate process and spec to shared split output runner`() {
        val process = IdleProcess()
        var capturedProcess: Process? = null
        var capturedStartSpec: VerifyCommandSplitOutputSpec? = null
        var capturedAwaitSpec: VerifyCommandSplitOutputSpec? = null
        var capturedStopRequested: AtomicBoolean? = null
        val runtime = VerifyCommandSplitOutputRuntime(
            handleStarter = { startedProcess, spec, stopRequested ->
                capturedProcess = startedProcess
                capturedStartSpec = spec
                capturedStopRequested = stopRequested
                ManagedSplitOutputProcess.start(
                    process = startedProcess,
                    outputLimitChars = spec.outputLimitChars,
                    stdoutThreadName = spec.stdoutThreadName,
                    stderrThreadName = spec.stderrThreadName,
                    stopRequested = stopRequested,
                )
            },
            completionAwaiter = { _, spec ->
                capturedAwaitSpec = spec
                ManagedSplitOutputProcessCompletion(
                    exitCode = 7,
                    stdout = "stdout",
                    stderr = "stderr",
                    timedOut = false,
                    stoppedByUser = false,
                    stdoutTruncated = true,
                    stderrTruncated = false,
                )
            },
        )
        val spec = VerifyCommandSplitOutputSpec(
            outputLimitChars = 128,
            stdoutThreadName = "verify-stdout",
            stderrThreadName = "verify-stderr",
            timeout = 25,
            timeoutUnit = TimeUnit.MILLISECONDS,
            outputJoinTimeoutMillis = 50,
            timeoutDestroyGraceWait = 75,
            timeoutDestroyGraceWaitUnit = TimeUnit.MILLISECONDS,
            timeoutDestroyForceWait = 100,
            timeoutDestroyForceWaitUnit = TimeUnit.MILLISECONDS,
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
        assertEquals("stdout", result.stdout)
        assertEquals("stderr", result.stderr)
        assertTrue(result.stdoutTruncated)
        assertFalse(result.stderrTruncated)
    }

    @Test
    fun `start should allow caller provided stop signal to flow into managed handle`() {
        val process = IdleProcess()
        var capturedStopRequested: AtomicBoolean? = null
        val runtime = VerifyCommandSplitOutputRuntime(
            handleStarter = { startedProcess, spec, stopRequested ->
                capturedStopRequested = stopRequested
                ManagedSplitOutputProcess.start(
                    process = startedProcess,
                    outputLimitChars = spec.outputLimitChars,
                    stdoutThreadName = spec.stdoutThreadName,
                    stderrThreadName = spec.stderrThreadName,
                    stopRequested = stopRequested,
                )
            },
        )
        val spec = VerifyCommandSplitOutputSpec(
            outputLimitChars = 64,
            stdoutThreadName = "verify-stdout",
            stderrThreadName = "verify-stderr",
            timeout = 1,
            timeoutUnit = TimeUnit.SECONDS,
            outputJoinTimeoutMillis = 1,
            timeoutDestroyGraceWait = 1,
            timeoutDestroyGraceWaitUnit = TimeUnit.SECONDS,
            timeoutDestroyForceWait = 1,
            timeoutDestroyForceWaitUnit = TimeUnit.SECONDS,
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
    fun `execute should surface startup failures without invoking completion awaiter`() {
        var awaiterInvoked = false
        val runtime = VerifyCommandSplitOutputRuntime(
            completionAwaiter = { _, _ ->
                awaiterInvoked = true
                error("awaiter should not be called")
            },
        )
        val spec = VerifyCommandSplitOutputSpec(
            outputLimitChars = 64,
            stdoutThreadName = "verify-stdout",
            stderrThreadName = "verify-stderr",
            timeout = 1,
            timeoutUnit = TimeUnit.SECONDS,
            outputJoinTimeoutMillis = 1,
            timeoutDestroyGraceWait = 1,
            timeoutDestroyGraceWaitUnit = TimeUnit.SECONDS,
            timeoutDestroyForceWait = 1,
            timeoutDestroyForceWaitUnit = TimeUnit.SECONDS,
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
