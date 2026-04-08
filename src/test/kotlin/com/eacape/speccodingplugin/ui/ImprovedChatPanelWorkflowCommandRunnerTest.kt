package com.eacape.speccodingplugin.ui

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
import java.util.concurrent.atomic.AtomicReference

class ImprovedChatPanelWorkflowCommandRunnerTest {

    @Test
    fun `execute should capture output and successful exit`() {
        val runner = ImprovedChatPanelWorkflowCommandRunner(
            timeoutSeconds = 1,
            outputLimitChars = 128,
            processStarter = { _, _ -> CompletedProcess(stdout = "line-1\nline-2\n") },
        )

        val outcome = runner.execute("gradle verify")

        assertTrue(outcome is ImprovedChatPanelWorkflowCommandRunOutcome.Completed)
        val execution = (outcome as ImprovedChatPanelWorkflowCommandRunOutcome.Completed).execution
        assertTrue(execution.success)
        assertEquals(0, execution.exitCode)
        assertEquals("line-1\nline-2", execution.output)
        assertFalse(execution.timedOut)
        assertFalse(execution.outputTruncated)
    }

    @Test
    fun `execute should report process start failure`() {
        val runner = ImprovedChatPanelWorkflowCommandRunner(
            processStarter = { _, _ -> throw IllegalStateException("shell unavailable") },
        )

        val outcome = runner.execute("gradle verify")

        assertTrue(outcome is ImprovedChatPanelWorkflowCommandRunOutcome.FailedToStart)
        val failedOutcome = outcome as ImprovedChatPanelWorkflowCommandRunOutcome.FailedToStart
        assertTrue(
            failedOutcome.errorMessage.contains("failed to start"),
        )
        assertTrue(
            failedOutcome.errorMessage.contains("shell unavailable"),
        )
    }

    @Test
    fun `execute should reject duplicate command while one is already running`() {
        val hangingProcess = HangingProcess()
        val duplicateProcess = CompletedProcess(stdout = "")
        val processStarts = AtomicInteger(0)
        val runner = ImprovedChatPanelWorkflowCommandRunner(
            timeoutSeconds = 5,
            processStarter = { _, _ ->
                if (processStarts.getAndIncrement() == 0) {
                    hangingProcess
                } else {
                    duplicateProcess
                }
            },
        )
        val firstOutcome = AtomicReference<ImprovedChatPanelWorkflowCommandRunOutcome>()
        val worker = Thread {
            firstOutcome.set(runner.execute("gradle verify"))
        }.apply {
            isDaemon = true
            start()
        }

        waitUntil { runner.isRunning("gradle verify") }

        val duplicateOutcome = runner.execute("gradle verify")

        assertEquals(ImprovedChatPanelWorkflowCommandRunOutcome.AlreadyRunning, duplicateOutcome)
        assertFalse(duplicateProcess.isAlive())
        assertEquals(
            ImprovedChatPanelWorkflowCommandStopOutcome.Stopping,
            runner.stop("gradle verify"),
        )
        worker.join(2_000)
        assertTrue(firstOutcome.get() is ImprovedChatPanelWorkflowCommandRunOutcome.Completed)
    }

    @Test
    fun `execute should mark timed out result when process exceeds timeout`() {
        val hangingProcess = HangingProcess()
        val runner = ImprovedChatPanelWorkflowCommandRunner(
            timeoutSeconds = 1,
            processStarter = { _, _ -> hangingProcess },
        )

        val outcome = runner.execute("gradle verify")

        assertTrue(outcome is ImprovedChatPanelWorkflowCommandRunOutcome.Completed)
        val execution = (outcome as ImprovedChatPanelWorkflowCommandRunOutcome.Completed).execution
        assertTrue(execution.timedOut)
        assertFalse(execution.success)
        assertEquals(143, execution.exitCode)
        assertTrue(hangingProcess.destroyCalls.get() > 0)
    }

    @Test
    fun `stop should terminate running command and mark result stopped by user`() {
        val hangingProcess = HangingProcess()
        val runner = ImprovedChatPanelWorkflowCommandRunner(
            timeoutSeconds = 5,
            processStarter = { _, _ -> hangingProcess },
        )
        val outcomeRef = AtomicReference<ImprovedChatPanelWorkflowCommandRunOutcome>()
        val worker = Thread {
            outcomeRef.set(runner.execute("gradle verify"))
        }.apply {
            isDaemon = true
            start()
        }

        waitUntil { runner.isRunning("gradle verify") }

        val stopOutcome = runner.stop("gradle verify")

        assertEquals(ImprovedChatPanelWorkflowCommandStopOutcome.Stopping, stopOutcome)
        worker.join(2_000)
        val outcome = outcomeRef.get()
        assertTrue(outcome is ImprovedChatPanelWorkflowCommandRunOutcome.Completed)
        val execution = (outcome as ImprovedChatPanelWorkflowCommandRunOutcome.Completed).execution
        assertTrue(execution.stoppedByUser)
        assertFalse(execution.timedOut)
        assertFalse(execution.success)
    }

    @Test
    fun `dispose should destroy active command processes`() {
        val hangingProcess = HangingProcess()
        val runner = ImprovedChatPanelWorkflowCommandRunner(
            timeoutSeconds = 5,
            processStarter = { _, _ -> hangingProcess },
        )
        val outcomeRef = AtomicReference<ImprovedChatPanelWorkflowCommandRunOutcome>()
        val worker = Thread {
            outcomeRef.set(runner.execute("gradle verify"))
        }.apply {
            isDaemon = true
            start()
        }

        waitUntil { runner.isRunning("gradle verify") }

        runner.dispose()

        worker.join(2_000)
        assertTrue(hangingProcess.destroyCalls.get() > 0)
        assertFalse(runner.isRunning("gradle verify"))
        assertTrue(outcomeRef.get() is ImprovedChatPanelWorkflowCommandRunOutcome.Completed)
    }

    private fun waitUntil(
        timeoutMillis: Long = 2_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(10)
        }
        error("Condition not met within ${timeoutMillis}ms")
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
        stdout: String = "",
        private val terminatedExitCode: Int = 143,
    ) : Process() {
        private val input = ByteArrayInputStream(stdout.toByteArray(StandardCharsets.UTF_8))
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
