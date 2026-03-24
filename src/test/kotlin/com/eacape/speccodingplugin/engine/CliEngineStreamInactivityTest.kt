package com.eacape.speccodingplugin.engine

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CliEngineStreamInactivityTest {

    @Test
    fun `stream should finish when stdout becomes idle before eof`() = runBlocking {
        val process = HangingOpenStreamsProcess(stdout = "final answer\n")
        val engine = FakeCliEngine(process, inactivityTimeoutMillis = 250)

        val chunks = withTimeout(3_000) {
            engine.stream(EngineRequest(prompt = "hello")).toList()
        }

        assertEquals("final answer\n", chunks.first().delta)
        assertTrue(chunks.last().isLast)
        assertTrue(process.destroyCalls.get() > 0, "Expected idle process to be terminated")
    }

    private class FakeCliEngine(
        private val process: Process,
        private val inactivityTimeoutMillis: Long?,
    ) : CliEngine(
        id = "fake-cli-idle",
        displayName = "Fake CLI",
        capabilities = emptySet(),
        cliPath = "fake",
    ) {
        override fun buildCommandArgs(request: EngineRequest): List<String> = emptyList()

        override fun parseStreamLine(line: String): EngineChunk? = EngineChunk(delta = line)

        override fun streamInactivityTimeoutMillis(request: EngineRequest): Long? = inactivityTimeoutMillis

        override suspend fun getVersion(): String? = "test"

        override fun startProcess(
            args: List<String>,
            workingDir: String?,
            environmentOverrides: Map<String, String>,
        ): Process = process
    }

    private class HangingOpenStreamsProcess(
        stdout: String,
        stderr: String = "",
        private val terminatedExitCode: Int = 143,
    ) : Process() {
        private val alive = AtomicBoolean(true)
        private val input = BlockingUntilDestroyedInputStream(stdout, alive)
        private val error = BlockingUntilDestroyedInputStream(stderr, alive)
        private val output = ByteArrayOutputStream()
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
                input.unblock()
                error.unblock()
                terminated.countDown()
            }
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = alive.get()
    }

    private class BlockingUntilDestroyedInputStream(
        content: String,
        private val alive: AtomicBoolean,
    ) : InputStream() {
        private val lock = Object()
        private val bytes = content.toByteArray(StandardCharsets.UTF_8)
        private var index = 0

        override fun read(): Int {
            val single = ByteArray(1)
            val read = read(single, 0, 1)
            return if (read <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            synchronized(lock) {
                while (true) {
                    if (index < bytes.size) {
                        val chunkSize = minOf(length, bytes.size - index)
                        System.arraycopy(bytes, index, target, offset, chunkSize)
                        index += chunkSize
                        return chunkSize
                    }
                    if (!alive.get()) {
                        return -1
                    }
                    lock.wait(25L)
                }
            }
        }

        fun unblock() {
            synchronized(lock) {
                lock.notifyAll()
            }
        }
    }
}
