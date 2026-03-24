package com.eacape.speccodingplugin.engine

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
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
import kotlin.test.assertFailsWith

class CliEngineStreamTerminationTest {

    @Test
    fun `stream should finish when output drains before process exits`() = runBlocking {
        val process = HangingProcess(stdout = "final answer\n")
        val engine = FakeCliEngine(process)

        val chunks = withTimeout(3_000) {
            engine.stream(EngineRequest(prompt = "hello")).toList()
        }

        assertEquals("final answer\n", chunks.first().delta)
        assertTrue(chunks.last().isLast)
        assertTrue(process.destroyCalls.get() > 0, "Expected drained process to be terminated")
    }

    @Test
    fun `stream should fail when drained process never produced stdout`() = runBlocking {
        val process = HangingProcess(stdout = "")
        val engine = FakeCliEngine(process)

        val error = assertFailsWith<RuntimeException> {
            withTimeout(3_000) {
                engine.stream(EngineRequest(prompt = "hello")).toList()
            }
        }

        assertTrue(error.message.orEmpty().contains("CLI"))
        assertTrue(process.destroyCalls.get() > 0, "Expected drained process to be terminated")
    }

    private class FakeCliEngine(
        private val process: Process,
    ) : CliEngine(
        id = "fake-cli",
        displayName = "Fake CLI",
        capabilities = emptySet(),
        cliPath = "fake",
    ) {
        override fun buildCommandArgs(request: EngineRequest): List<String> = emptyList()

        override fun parseStreamLine(line: String): EngineChunk? = EngineChunk(delta = line)

        override suspend fun getVersion(): String? = "test"

        override fun startProcess(
            args: List<String>,
            workingDir: String?,
            environmentOverrides: Map<String, String>,
        ): Process = process
    }

    private class HangingProcess(
        stdout: String,
        stderr: String = "",
        private val terminatedExitCode: Int = 143,
    ) : Process() {
        private val input = ByteArrayInputStream(stdout.toByteArray(StandardCharsets.UTF_8))
        private val error = ByteArrayInputStream(stderr.toByteArray(StandardCharsets.UTF_8))
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
