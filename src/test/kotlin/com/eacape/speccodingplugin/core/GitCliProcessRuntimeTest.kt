package com.eacape.speccodingplugin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GitCliProcessRuntimeTest {

    @Test
    fun `execute should delegate merged output lifecycle to shared runtime`() {
        val startedWorkingDir = AtomicReference<File?>()
        val startedArgs = AtomicReference<List<String>>()
        val process = CompletedProcess(stdout = "abc123\n")
        var capturedProcess: Process? = null
        var capturedStartSpec: ExternalMergedOutputCommandSpec? = null
        var capturedAwaitSpec: ExternalMergedOutputCommandSpec? = null
        val runtime = GitCliProcessRuntime(
            processStarter = { workingDir, args ->
                startedWorkingDir.set(workingDir)
                startedArgs.set(args)
                process
            },
            mergedOutputRuntime = ExternalMergedOutputCommandRuntime(
                handleStarter = { startedProcess, spec, stopRequested ->
                    capturedProcess = startedProcess
                    capturedStartSpec = spec
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
                        output = "git ok",
                        timedOut = false,
                        stoppedByUser = false,
                        outputTruncated = true,
                    )
                },
            ),
        )

        val result = runtime.execute(
            workingDir = File("D:/repo"),
            timeoutMs = 250,
            args = listOf("rev-parse", "HEAD"),
        )

        assertEquals(File("D:/repo"), startedWorkingDir.get())
        assertEquals(listOf("rev-parse", "HEAD"), startedArgs.get())
        assertSame(process, capturedProcess)
        assertEquals(
            ExternalMergedOutputCommandSpec(
                outputLimitChars = 16_384,
                threadName = "git-cli-output-${GitCliProcessRuntime.buildCommand(listOf("rev-parse", "HEAD")).joinToString(" ").hashCode()}",
                timeout = 250,
                timeoutUnit = TimeUnit.MILLISECONDS,
                outputJoinTimeoutMillis = 2_000L,
                timeoutDestroyWait = 1_000L,
                timeoutDestroyWaitUnit = TimeUnit.MILLISECONDS,
            ),
            capturedStartSpec,
        )
        assertEquals(capturedStartSpec, capturedAwaitSpec)
        assertEquals("git ok", result.output)
        assertEquals(7, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.outputTruncated)
    }

    @Test
    fun `execute should start git command in provided working directory and trim output`() {
        val startedWorkingDir = AtomicReference<File?>()
        val startedArgs = AtomicReference<List<String>>()
        val runtime = GitCliProcessRuntime(
            processStarter = { workingDir, args ->
                startedWorkingDir.set(workingDir)
                startedArgs.set(args)
                CompletedProcess(stdout = "abc123\n")
            },
        )

        val result = runtime.execute(
            workingDir = File("D:/repo"),
            timeoutMs = 100,
            args = listOf("rev-parse", "HEAD"),
        )

        assertEquals(File("D:/repo"), startedWorkingDir.get())
        assertEquals(listOf("rev-parse", "HEAD"), startedArgs.get())
        assertEquals("abc123", result.output)
        assertEquals(0, result.exitCode)
        assertFalse(result.failed)
        assertFalse(result.timedOut)
    }

    @Test
    fun `execute should mark timeout and destroy hanging process`() {
        val hangingProcess = HangingProcess()
        val runtime = GitCliProcessRuntime(
            processStarter = { _, _ -> hangingProcess },
        )

        val result = runtime.execute(
            workingDir = File("D:/repo"),
            timeoutMs = 20,
            args = listOf("reflog", "-1"),
        )

        assertTrue(result.failed)
        assertTrue(result.timedOut)
        assertNull(result.output)
        assertTrue(hangingProcess.destroyCalls.get() > 0)
    }

    @Test
    fun `cli git command executor should surface startup failure separately from exit failure`() {
        val workingDir = Paths.get("").toAbsolutePath().normalize()
        val executor = CliGitCommandExecutor(
            runtime = GitCliProcessRuntime(
                processStarter = { _, _ -> error("git unavailable") },
            ),
            timeoutMs = 100,
        )

        val result = executor.run(
            workingDir = workingDir,
            args = listOf("status", "--porcelain"),
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        val diagnosticError = assertInstanceOf(GitCommandExecutionException::class.java, error)
        assertNotNull(diagnosticError)
        assertEquals(GitCliFailureKind.STARTUP_FAILED, diagnosticError.diagnostic.kind)
        assertTrue(diagnosticError.message.orEmpty().contains("Failed to start git command"))
        assertTrue(diagnosticError.diagnostic.renderDetail().contains("git unavailable"))
    }

    @Test
    fun `cli git command executor should include exit output in failure message`() {
        val workingDir = Paths.get("").toAbsolutePath().normalize()
        val executor = CliGitCommandExecutor(
            runtime = GitCliProcessRuntime(
                processStarter = { _, _ -> CompletedProcess(stdout = "fatal: bad revision\n", completedExitCode = 128) },
            ),
            timeoutMs = 100,
        )

        val result = executor.run(
            workingDir = workingDir,
            args = listOf("rev-parse", "HEAD"),
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        val diagnosticError = assertInstanceOf(GitCommandExecutionException::class.java, error)
        assertNotNull(diagnosticError)
        assertEquals(GitCliFailureKind.EXIT_FAILURE, diagnosticError.diagnostic.kind)
        assertTrue(diagnosticError.message.orEmpty().contains("Git command failed"))
        assertTrue(diagnosticError.message.orEmpty().contains("fatal: bad revision"))
    }

    @Test
    fun `cli git command executor should classify missing executable separately`() {
        val workingDir = Paths.get("").toAbsolutePath().normalize()
        val executor = CliGitCommandExecutor(
            runtime = GitCliProcessRuntime(
                processStarter = { _, _ -> error("CreateProcess error=2, The system cannot find the file specified") },
            ),
            timeoutMs = 100,
        )

        val result = executor.run(
            workingDir = workingDir,
            args = listOf("status"),
        )

        assertTrue(result.isFailure)
        val diagnosticError = assertInstanceOf(GitCommandExecutionException::class.java, result.exceptionOrNull())
        assertEquals(GitCliFailureKind.EXECUTABLE_NOT_FOUND, diagnosticError.diagnostic.kind)
        assertTrue(diagnosticError.message.orEmpty().contains("git executable was not found"))
    }

    @Test
    fun `cli git command executor should classify unavailable working directory separately`() {
        val missingDir = Paths.get("build", "git-missing-dir-${System.nanoTime()}").toAbsolutePath()
        val executor = CliGitCommandExecutor(
            runtime = GitCliProcessRuntime(
                processStarter = { _, _ -> error("CreateProcess error=267, The directory name is invalid") },
            ),
            timeoutMs = 100,
        )

        val result = executor.run(
            workingDir = missingDir,
            args = listOf("status"),
        )

        assertTrue(result.isFailure)
        val diagnosticError = assertInstanceOf(GitCommandExecutionException::class.java, result.exceptionOrNull())
        assertEquals(GitCliFailureKind.WORKING_DIRECTORY_UNAVAILABLE, diagnosticError.diagnostic.kind)
        assertTrue(diagnosticError.message.orEmpty().contains(missingDir.toString()))
    }

    @Test
    fun `cli git command executor should classify access denied separately`() {
        val workingDir = Paths.get("").toAbsolutePath().normalize()
        val executor = CliGitCommandExecutor(
            runtime = GitCliProcessRuntime(
                processStarter = { _, _ -> error("CreateProcess error=5, Access is denied") },
            ),
            timeoutMs = 100,
        )

        val result = executor.run(
            workingDir = workingDir,
            args = listOf("status"),
        )

        assertTrue(result.isFailure)
        val diagnosticError = assertInstanceOf(GitCommandExecutionException::class.java, result.exceptionOrNull())
        assertEquals(GitCliFailureKind.ACCESS_DENIED, diagnosticError.diagnostic.kind)
        assertTrue(diagnosticError.message.orEmpty().contains("access denied"))
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
