package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VerifyCommandRuntimeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `execute should capture stdout and stderr independently with truncation`() {
        val runtime = VerifyCommandRuntime(
            processStarter = {
                CompletedProcess(
                    stdout = "stdout-line-1\nstdout-line-2\n",
                    stderr = "stderr-line-1\nstderr-line-2\n",
                )
            },
        )

        val result = runtime.execute(request(outputLimitChars = 12))

        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.stdout.startsWith("stdout-line-"))
        assertTrue(result.stderr.startsWith("stderr-line-"))
        assertTrue(result.stdout.contains("[truncated"))
        assertTrue(result.stderr.contains("[truncated"))
        assertTrue(result.stdoutTruncated)
        assertTrue(result.stderrTruncated)
        assertNull(result.startupDiagnostic)
    }

    @Test
    fun `execute should destroy timed out process and surface timeout state`() {
        val process = HangingProcess()
        val runtime = VerifyCommandRuntime(
            processStarter = { process },
        )

        val result = runtime.execute(request(timeoutMs = 1))

        assertTrue(result.timedOut)
        assertNull(result.exitCode)
        assertTrue(process.destroyCalls.get() > 0)
    }

    @Test
    fun `execute should surface executable startup failures with structured diagnostic`() {
        val runtime = VerifyCommandRuntime(
            processStarter = {
                error("missing executable")
            },
        )

        val result = runtime.execute(request())

        assertEquals(VerifyCommandFailureKind.EXECUTABLE_NOT_FOUND, result.startupDiagnostic?.kind)
        assertEquals("missing executable", result.startupDiagnostic?.startupErrorMessage)
        assertTrue(result.startupDiagnostic?.renderDetail().orEmpty().contains("verify executable was not found"))
        assertFalse(result.timedOut)
        assertNull(result.exitCode)
        assertEquals("", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun `execute should classify permission denied startup failures`() {
        val runtime = VerifyCommandRuntime(
            processStarter = {
                error("CreateProcess error=5, Access is denied")
            },
        )

        val result = runtime.execute(request())

        assertEquals(VerifyCommandFailureKind.ACCESS_DENIED, result.startupDiagnostic?.kind)
        assertTrue(result.startupDiagnostic?.renderDetail().orEmpty().contains("access denied"))
    }

    @Test
    fun `execute should classify missing working directory startup failures`() {
        val runtime = VerifyCommandRuntime(
            processStarter = {
                error("The directory name is invalid")
            },
        )

        val workingDirectory = tempDir.resolve("deleted-project")
        Files.createDirectories(workingDirectory)
        Files.delete(workingDirectory)
        val result = runtime.execute(request(workingDirectory = workingDirectory))

        assertEquals(VerifyCommandFailureKind.WORKING_DIRECTORY_UNAVAILABLE, result.startupDiagnostic?.kind)
        assertTrue(result.startupDiagnostic?.renderDetail().orEmpty().contains("working directory is unavailable"))
    }

    private fun request(
        workingDirectory: Path = Files.createDirectories(tempDir.resolve("project")),
        timeoutMs: Int = 1_000,
        outputLimitChars: Int = 128,
    ): VerifyCommandExecutionRequest {
        return VerifyCommandExecutionRequest(
            commandId = "verify-fixture",
            displayName = "Verify fixture",
            command = listOf("gradle", "test"),
            workingDirectory = workingDirectory,
            timeoutMs = timeoutMs,
            outputLimitChars = outputLimitChars,
            redactionPatterns = emptyList(),
        )
    }

    private class CompletedProcess(
        stdout: String,
        stderr: String,
        private val completedExitCode: Int = 0,
    ) : Process() {
        private val input = ByteArrayInputStream(stdout.toByteArray())
        private val error = ByteArrayInputStream(stderr.toByteArray())
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
