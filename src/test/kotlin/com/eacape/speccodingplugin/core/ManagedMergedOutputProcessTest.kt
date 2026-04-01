package com.eacape.speccodingplugin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

class ManagedMergedOutputProcessTest {

    @Test
    fun `awaitCompletion should capture merged output and truncation`() {
        val runtime = ManagedMergedOutputProcess.start(
            process = CompletedProcess(stdout = "line-1\nline-2\n"),
            outputLimitChars = 6,
            threadName = "managed-process-test-output",
        )

        val completion = runtime.awaitCompletion(
            timeout = 1,
            timeoutUnit = TimeUnit.SECONDS,
            joinTimeoutMillis = 500,
            timeoutDestroyWait = 100,
            timeoutDestroyWaitUnit = TimeUnit.MILLISECONDS,
        )

        assertEquals(0, completion.exitCode)
        assertEquals("line-1", completion.output)
        assertFalse(completion.timedOut)
        assertFalse(completion.stoppedByUser)
        assertTrue(completion.outputTruncated)
    }

    @Test
    fun `awaitCompletion should force destroy timed out process`() {
        val runtime = ManagedMergedOutputProcess.start(
            process = HangingProcess(),
            outputLimitChars = 32,
            threadName = "managed-process-timeout-test",
        )

        val completion = runtime.awaitCompletion(
            timeout = 1,
            timeoutUnit = TimeUnit.MILLISECONDS,
            joinTimeoutMillis = 500,
            timeoutDestroyWait = 100,
            timeoutDestroyWaitUnit = TimeUnit.MILLISECONDS,
        )

        assertTrue(completion.timedOut)
        assertFalse(completion.stoppedByUser)
        assertEquals(143, completion.exitCode)
    }

    @Test
    fun `dispose should mark stop requested and destroy alive process`() {
        val stopRequested = AtomicBoolean(false)
        val process = HangingProcess()
        val runtime = ManagedMergedOutputProcess.start(
            process = process,
            outputLimitChars = 32,
            threadName = "managed-process-dispose-test",
            stopRequested = stopRequested,
        )

        runtime.dispose()

        assertTrue(stopRequested.get())
        assertTrue(process.destroyCalls.get() > 0)
        assertFalse(process.isAlive())
    }

    private class CompletedProcess(
        stdout: String,
        private val completedExitCode: Int = 0,
    ) : Process() {
        private val input = ByteArrayInputStream(stdout.toByteArray(StandardCharsets.UTF_8))
        private val error = ByteArrayInputStream(ByteArray(0))
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
