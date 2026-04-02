package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ManagedSplitOutputProcessTest {

    @Test
    fun `awaitCompletion should capture stdout and stderr independently with truncation`() {
        val runtime = ManagedSplitOutputProcess.start(
            process = CompletedProcess(
                stdout = "stdout-line-1\nstdout-line-2\n",
                stderr = "stderr-line-1\nstderr-line-2\n",
            ),
            outputLimitChars = 12,
            stdoutThreadName = "managed-split-stdout-test",
            stderrThreadName = "managed-split-stderr-test",
        )

        val completion = runtime.awaitCompletion(
            timeout = 1,
            timeoutUnit = TimeUnit.SECONDS,
            joinTimeoutMillis = 500,
            timeoutDestroyGraceWait = 100,
            timeoutDestroyGraceWaitUnit = TimeUnit.MILLISECONDS,
            timeoutDestroyForceWait = 100,
            timeoutDestroyForceWaitUnit = TimeUnit.MILLISECONDS,
        )

        assertEquals(0, completion.exitCode)
        assertFalse(completion.timedOut)
        assertFalse(completion.stoppedByUser)
        assertTrue(completion.stdout.startsWith("stdout-line-"))
        assertTrue(completion.stderr.startsWith("stderr-line-"))
        assertTrue(completion.stdout.contains("[truncated"))
        assertTrue(completion.stderr.contains("[truncated"))
        assertTrue(completion.stdoutTruncated)
        assertTrue(completion.stderrTruncated)
    }

    @Test
    fun `awaitCompletion should destroy timed out process and surface timeout state`() {
        val process = HangingProcess()
        val runtime = ManagedSplitOutputProcess.start(
            process = process,
            outputLimitChars = 32,
            stdoutThreadName = "managed-split-timeout-stdout",
            stderrThreadName = "managed-split-timeout-stderr",
        )

        val completion = runtime.awaitCompletion(
            timeout = 1,
            timeoutUnit = TimeUnit.MILLISECONDS,
            joinTimeoutMillis = 500,
            timeoutDestroyGraceWait = 50,
            timeoutDestroyGraceWaitUnit = TimeUnit.MILLISECONDS,
            timeoutDestroyForceWait = 50,
            timeoutDestroyForceWaitUnit = TimeUnit.MILLISECONDS,
        )

        assertTrue(completion.timedOut)
        assertFalse(completion.stoppedByUser)
        assertNull(completion.exitCode)
        assertTrue(process.destroyCalls.get() > 0)
    }

    @Test
    fun `dispose should mark stop requested and destroy alive process`() {
        val stopRequested = AtomicBoolean(false)
        val process = HangingProcess()
        val runtime = ManagedSplitOutputProcess.start(
            process = process,
            outputLimitChars = 32,
            stdoutThreadName = "managed-split-dispose-stdout",
            stderrThreadName = "managed-split-dispose-stderr",
            stopRequested = stopRequested,
        )

        runtime.dispose()

        assertTrue(stopRequested.get())
        assertTrue(process.destroyCalls.get() > 0)
        assertFalse(process.isAlive())
    }

    private class CompletedProcess(
        stdout: String,
        stderr: String,
        private val completedExitCode: Int = 0,
    ) : Process() {
        private val input = ByteArrayInputStream(stdout.toByteArray(StandardCharsets.UTF_8))
        private val error = ByteArrayInputStream(stderr.toByteArray(StandardCharsets.UTF_8))
        private val output = ByteArrayOutputStream()
        private val alive = AtomicBoolean(true)

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = error

        override fun getOutputStream(): OutputStream = output

        override fun waitFor(): Int {
            alive.set(false)
            return completedExitCode
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            alive.set(false)
            return true
        }

        override fun exitValue(): Int {
            check(!alive.get()) { "Process is still alive" }
            return completedExitCode
        }

        override fun destroy() {
            alive.set(false)
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = alive.get()
    }

    private class HangingProcess(
        private val terminatedExitCode: Int = 143,
    ) : Process() {
        private val input = ByteArrayInputStream(ByteArray(0))
        private val error = ByteArrayInputStream(ByteArray(0))
        private val output = ByteArrayOutputStream()
        private val alive = AtomicBoolean(true)
        private val terminated = CountDownLatch(1)
        val destroyCalls = AtomicInteger(0)

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = error

        override fun getOutputStream(): OutputStream = output

        override fun waitFor(): Int {
            terminated.await(2, TimeUnit.SECONDS)
            return exitValue()
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (!alive.get()) {
                return true
            }
            return terminated.await(timeout, unit)
        }

        override fun exitValue(): Int {
            check(!alive.get()) { "Process is still alive" }
            return terminatedExitCode
        }

        override fun destroy() {
            destroyCalls.incrementAndGet()
            if (alive.compareAndSet(true, false)) {
                terminated.countDown()
            }
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = alive.get()
    }
}
