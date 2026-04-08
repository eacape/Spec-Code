package com.eacape.speccodingplugin.hook

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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HookCommandRuntimeTest {

    private val existingBasePath = Path.of("").toAbsolutePath().normalize().toString()

    @Test
    fun `execute should start command in provided working directory and trim output`() {
        val startedBasePath = AtomicReference<String?>()
        val startedCommand = AtomicReference<List<String>>()
        val runtime = HookCommandRuntime(
            processStarter = { basePath, command ->
                startedBasePath.set(basePath)
                startedCommand.set(command)
                CompletedProcess(stdout = "command ok\n")
            },
        )

        val result = runtime.execute(
            basePath = "D:/repo",
            executable = "gradle",
            args = listOf("test"),
            timeoutMs = 100,
        )

        assertEquals("D:/repo", startedBasePath.get())
        assertEquals(listOf("gradle", "test"), startedCommand.get())
        assertEquals("command ok", result.output)
        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertNull(result.startupDiagnostic)
    }

    @Test
    fun `execute should surface non zero exit with captured output`() {
        val runtime = HookCommandRuntime(
            processStarter = { _, _ -> CompletedProcess(stdout = "hook failed\n", completedExitCode = 17) },
        )

        val result = runtime.execute(
            basePath = "D:/repo",
            executable = "gradle",
            args = listOf("lint"),
            timeoutMs = 100,
        )

        assertEquals("hook failed", result.output)
        assertEquals(17, result.exitCode)
        assertFalse(result.timedOut)
        assertNull(result.startupDiagnostic)
    }

    @Test
    fun `execute should mark timeout and destroy hanging process`() {
        val hangingProcess = HangingProcess()
        val runtime = HookCommandRuntime(
            processStarter = { _, _ -> hangingProcess },
        )

        val result = runtime.execute(
            basePath = "D:/repo",
            executable = "gradle",
            args = listOf("test"),
            timeoutMs = 20,
        )

        assertTrue(result.timedOut)
        assertTrue(hangingProcess.destroyCalls.get() > 0)
        assertNull(result.startupDiagnostic)
    }

    @Test
    fun `execute should surface executable startup failures with structured diagnostic`() {
        val runtime = HookCommandRuntime(
            processStarter = { _, _ -> error("missing tool") },
        )

        val result = runtime.execute(
            basePath = existingBasePath,
            executable = "gradle",
            args = listOf("test"),
            timeoutMs = 100,
        )

        assertFalse(result.timedOut)
        assertNull(result.output)
        assertNull(result.exitCode)
        assertEquals(HookCommandFailureKind.EXECUTABLE_NOT_FOUND, result.startupDiagnostic?.kind)
        assertEquals("missing tool", result.startupDiagnostic?.startupErrorMessage)
        assertTrue(result.startupDiagnostic?.renderDetail().orEmpty().contains("hook executable was not found"))
    }

    @Test
    fun `execute should classify missing working directory startup failures`() {
        val runtime = HookCommandRuntime(
            processStarter = { _, _ -> error("The directory name is invalid") },
        )
        val missingDir = Path.of("build", "hook-missing-dir-${System.nanoTime()}").toAbsolutePath()
        Files.createDirectories(missingDir.parent)

        val result = runtime.execute(
            basePath = missingDir.toString(),
            executable = "gradle",
            args = listOf("test"),
            timeoutMs = 100,
        )

        assertEquals(HookCommandFailureKind.WORKING_DIRECTORY_UNAVAILABLE, result.startupDiagnostic?.kind)
        assertTrue(result.startupDiagnostic?.renderDetail().orEmpty().contains("working directory is unavailable"))
    }

    @Test
    fun `execute should classify permission denied startup failures`() {
        val runtime = HookCommandRuntime(
            processStarter = { _, _ -> error("CreateProcess error=5, Access is denied") },
        )

        val result = runtime.execute(
            basePath = existingBasePath,
            executable = "gradle",
            args = listOf("test"),
            timeoutMs = 100,
        )

        assertEquals(HookCommandFailureKind.ACCESS_DENIED, result.startupDiagnostic?.kind)
        assertTrue(result.startupDiagnostic?.renderDetail().orEmpty().contains("access denied"))
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
