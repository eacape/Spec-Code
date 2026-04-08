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
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HookGitCommandRuntimeTest {

    @Test
    fun `execute should start git command in provided working directory and trim output`() {
        val startedBasePath = AtomicReference<String>()
        val startedCommand = AtomicReference<List<String>>()
        val runtime = HookGitCommandRuntime(
            processStarter = { basePath, command ->
                startedBasePath.set(basePath)
                startedCommand.set(command)
                CompletedProcess(stdout = "abc123\n")
            },
        )

        val result = runtime.execute(
            "D:/repo",
            100,
            "rev-parse",
            "HEAD",
        )

        assertEquals(File("D:/repo").path, startedBasePath.get())
        assertEquals(listOf("git", "rev-parse", "HEAD"), startedCommand.get())
        assertEquals("abc123", result.output)
        assertFalse(result.failed)
        assertFalse(result.timedOut)
    }

    @Test
    fun `execute should treat non zero exit as failed`() {
        val runtime = HookGitCommandRuntime(
            processStarter = { _, _ -> CompletedProcess(stdout = "fatal: missing ref\n", completedExitCode = 128) },
        )

        val result = runtime.execute(
            "D:/repo",
            100,
            "rev-parse",
            "HEAD",
        )

        assertTrue(result.failed)
        assertFalse(result.timedOut)
        assertNull(result.output)
    }

    @Test
    fun `execute should mark timeout and destroy hanging process`() {
        val hangingProcess = HangingProcess()
        val runtime = HookGitCommandRuntime(
            processStarter = { _, _ -> hangingProcess },
        )

        val result = runtime.execute(
            "D:/repo",
            20,
            "reflog",
            "-1",
        )

        assertTrue(result.failed)
        assertTrue(result.timedOut)
        assertNull(result.output)
        assertTrue(hangingProcess.destroyCalls.get() > 0)
    }

    @Test
    fun `execute should return failed when process startup throws`() {
        val runtime = HookGitCommandRuntime(
            processStarter = { _, _ -> error("git unavailable") },
        )

        val result = runtime.execute(
            "D:/repo",
            100,
            "rev-parse",
            "HEAD",
        )

        assertTrue(result.failed)
        assertFalse(result.timedOut)
        assertNull(result.output)
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
