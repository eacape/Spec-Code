package com.eacape.speccodingplugin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class WorkflowCommandProcessRuntimeTest {

    private val existingWorkingDirectory = Path.of("").toAbsolutePath().normalize().toFile()

    @Test
    fun `execute should delegate lifecycle to shared merged output runtime`() {
        val process = IdleProcess()
        var capturedDirectory: File? = null
        var capturedCommand: List<String>? = null
        var capturedProcess: Process? = null
        var capturedStartSpec: ExternalMergedOutputCommandSpec? = null
        var capturedAwaitSpec: ExternalMergedOutputCommandSpec? = null
        var capturedStopRequested: AtomicBoolean? = null
        val sharedRuntime = ExternalMergedOutputCommandRuntime(
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
                    exitCode = 0,
                    output = "verified",
                    timedOut = false,
                    stoppedByUser = false,
                    outputTruncated = true,
                )
            },
        )
        val runtime = WorkflowCommandProcessRuntime(
            processStarter = { workingDirectory, command ->
                capturedDirectory = workingDirectory
                capturedCommand = command
                process
            },
            mergedOutputRuntime = sharedRuntime,
            outputLimitChars = 256,
            outputJoinTimeoutMillis = 42,
            forceDestroyWaitSeconds = 3,
        )

        val result = runtime.execute(
            commandKey = "gradle verify",
            launchCommand = listOf("gradle", "verify"),
            workingDirectory = existingWorkingDirectory,
            timeoutSeconds = 9,
        )

        val completed = assertInstanceOf(WorkflowCommandProcessRunResult.Completed::class.java, result)
        assertSame(existingWorkingDirectory, capturedDirectory)
        assertEquals(listOf("gradle", "verify"), capturedCommand)
        assertSame(process, capturedProcess)
        assertEquals(capturedStartSpec, capturedAwaitSpec)
        assertEquals(
            ExternalMergedOutputCommandSpec(
                outputLimitChars = 256,
                threadName = "workflow-command-output-${"gradle verify".hashCode()}",
                timeout = 9,
                timeoutUnit = TimeUnit.SECONDS,
                outputJoinTimeoutMillis = 42,
                timeoutDestroyWait = 3,
                timeoutDestroyWaitUnit = TimeUnit.SECONDS,
            ),
            capturedStartSpec,
        )
        assertFalse(capturedStopRequested!!.get())
        assertEquals(0, completed.result.exitCode)
        assertEquals("verified", completed.result.output)
        assertTrue(completed.result.outputTruncated)
        assertFalse(runtime.isRunning("gradle verify"))
    }

    @Test
    fun `execute should surface executable startup failures with structured diagnostic`() {
        val runtime = WorkflowCommandProcessRuntime(
            processStarter = { _, _ -> error("missing executable") },
        )

        val result = runtime.execute(
            commandKey = "gradle verify",
            launchCommand = listOf("gradle", "verify"),
            workingDirectory = existingWorkingDirectory,
            timeoutSeconds = 1,
        )

        val failed = assertInstanceOf(WorkflowCommandProcessRunResult.FailedToStart::class.java, result)
        assertEquals(WorkflowCommandFailureKind.EXECUTABLE_NOT_FOUND, failed.diagnostic.kind)
        assertEquals("missing executable", failed.diagnostic.startupErrorMessage)
        assertTrue(failed.errorMessage.contains("workflow executable was not found"))
    }

    @Test
    fun `execute should classify unavailable working directory separately`() {
        val runtime = WorkflowCommandProcessRuntime(
            processStarter = { _, _ -> error("The directory name is invalid") },
        )
        val missingDir = Path.of("build", "workflow-missing-dir-${System.nanoTime()}").toAbsolutePath().toFile()

        val result = runtime.execute(
            commandKey = "gradle verify",
            launchCommand = listOf("gradle", "verify"),
            workingDirectory = missingDir,
            timeoutSeconds = 1,
        )

        val failed = assertInstanceOf(WorkflowCommandProcessRunResult.FailedToStart::class.java, result)
        assertEquals(WorkflowCommandFailureKind.WORKING_DIRECTORY_UNAVAILABLE, failed.diagnostic.kind)
        assertTrue(failed.diagnostic.renderDetail().contains("working directory is unavailable"))
    }

    @Test
    fun `execute should classify access denied separately`() {
        val runtime = WorkflowCommandProcessRuntime(
            processStarter = { _, _ -> error("CreateProcess error=5, Access is denied") },
        )

        val result = runtime.execute(
            commandKey = "gradle verify",
            launchCommand = listOf("gradle", "verify"),
            workingDirectory = existingWorkingDirectory,
            timeoutSeconds = 1,
        )

        val failed = assertInstanceOf(WorkflowCommandProcessRunResult.FailedToStart::class.java, result)
        assertEquals(WorkflowCommandFailureKind.ACCESS_DENIED, failed.diagnostic.kind)
        assertTrue(failed.diagnostic.renderDetail().contains("access denied"))
    }

    @Test
    fun `execute should reject duplicate command while one is already running`() {
        val hangingProcess = HangingProcess()
        val duplicateProcess = CompletedProcess(stdout = "")
        val processStarts = AtomicInteger(0)
        val runtime = WorkflowCommandProcessRuntime(
            processStarter = { _, _ ->
                if (processStarts.getAndIncrement() == 0) {
                    hangingProcess
                } else {
                    duplicateProcess
                }
            },
        )
        val firstOutcome = AtomicReference<WorkflowCommandProcessRunResult>()
        val worker = Thread {
            firstOutcome.set(
                runtime.execute(
                    commandKey = "gradle verify",
                    launchCommand = listOf("gradle", "verify"),
                    workingDirectory = existingWorkingDirectory,
                    timeoutSeconds = 5,
                )
            )
        }.apply {
            isDaemon = true
            start()
        }

        waitUntil { runtime.isRunning("gradle verify") }

        val duplicateOutcome = runtime.execute(
            commandKey = "gradle verify",
            launchCommand = listOf("gradle", "verify"),
            workingDirectory = existingWorkingDirectory,
            timeoutSeconds = 5,
        )

        assertEquals(WorkflowCommandProcessRunResult.AlreadyRunning, duplicateOutcome)
        assertFalse(duplicateProcess.isAlive())
        assertEquals(WorkflowCommandProcessStopResult.Stopping, runtime.stop("gradle verify"))
        worker.join(2_000)
        assertTrue(firstOutcome.get() is WorkflowCommandProcessRunResult.Completed)
    }

    @Test
    fun `dispose should destroy active command process and clear running state`() {
        val hangingProcess = HangingProcess()
        val runtime = WorkflowCommandProcessRuntime(
            processStarter = { _, _ -> hangingProcess },
        )
        val outcomeRef = AtomicReference<WorkflowCommandProcessRunResult>()
        val worker = Thread {
            outcomeRef.set(
                runtime.execute(
                    commandKey = "gradle verify",
                    launchCommand = listOf("gradle", "verify"),
                    workingDirectory = existingWorkingDirectory,
                    timeoutSeconds = 5,
                )
            )
        }.apply {
            isDaemon = true
            start()
        }

        waitUntil { runtime.isRunning("gradle verify") }

        runtime.dispose()

        worker.join(2_000)
        assertTrue(hangingProcess.destroyCalls.get() > 0)
        assertFalse(runtime.isRunning("gradle verify"))
        val completed = assertInstanceOf(WorkflowCommandProcessRunResult.Completed::class.java, outcomeRef.get())
        assertTrue(completed.result.stoppedByUser)
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
