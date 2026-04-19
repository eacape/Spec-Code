package com.eacape.speccodingplugin.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CliStreamingProcessLifecycleTest {

    @Test
    fun `start timeout watchdog should force destroy alive process`() {
        val process = ControllableProcess()
        val lifecycle = CliStreamingProcessLifecycle(
            sleeper = {},
            asyncRunner = { task -> CompletableFuture.runAsync { task() } },
        )

        val (timedOut, future) = lifecycle.startTimeoutWatchdog(process, 5)
        future!!.get(1, TimeUnit.SECONDS)

        assertTrue(timedOut.get())
        assertEquals(1, process.forceDestroyCalls.get())
        assertFalse(process.isAlive)
    }

    @Test
    fun `start timeout watchdog should stay idle when timeout is disabled`() {
        val process = ControllableProcess()
        val lifecycle = CliStreamingProcessLifecycle()

        val (timedOut, future) = lifecycle.startTimeoutWatchdog(process, null)

        assertFalse(timedOut.get())
        assertNull(future)
        assertTrue(process.isAlive)
        assertEquals(0, process.forceDestroyCalls.get())
    }

    @Test
    fun `terminate after stream drain should stop process without force when destroy exits`() {
        val logs = mutableListOf<String>()
        val process = ControllableProcess(exitOnDestroy = true)
        val lifecycle = CliStreamingProcessLifecycle(logInfo = logs::add)

        val terminated = lifecycle.terminateAfterStreamDrain(process, "req-1")

        assertTrue(terminated)
        assertEquals(1, process.destroyCalls.get())
        assertEquals(0, process.forceDestroyCalls.get())
        assertTrue(logs.single().contains("stdout/stderr drained"))
    }

    @Test
    fun `terminate after inactivity should force destroy when graceful stop does not exit`() {
        val logs = mutableListOf<String>()
        val process = ControllableProcess(exitOnDestroy = false, exitOnForceDestroy = true)
        val lifecycle = CliStreamingProcessLifecycle(logInfo = logs::add)

        val terminated = lifecycle.terminateAfterInactivity(
            process = process,
            requestId = "req-2",
            inactivityTimeoutMillis = 250,
        )

        assertTrue(terminated)
        assertEquals(1, process.destroyCalls.get())
        assertEquals(1, process.forceDestroyCalls.get())
        assertFalse(process.isAlive)
        assertTrue(logs.single().contains("became idle for 250ms"))
    }

    private class ControllableProcess(
        private val exitOnDestroy: Boolean = false,
        private val exitOnForceDestroy: Boolean = true,
    ) : Process() {
        private val input = ByteArrayInputStream(ByteArray(0))
        private val error = ByteArrayInputStream(ByteArray(0))
        private val output = ByteArrayOutputStream()
        private val alive = AtomicBoolean(true)
        val destroyCalls = AtomicInteger(0)
        val forceDestroyCalls = AtomicInteger(0)

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = error

        override fun getOutputStream(): OutputStream = output

        override fun waitFor(): Int = exitValue()

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive.get()

        override fun exitValue(): Int {
            check(!alive.get()) { "Process is still alive" }
            return 0
        }

        override fun destroy() {
            destroyCalls.incrementAndGet()
            if (exitOnDestroy) {
                alive.set(false)
            }
        }

        override fun destroyForcibly(): Process {
            forceDestroyCalls.incrementAndGet()
            if (exitOnForceDestroy) {
                alive.set(false)
            }
            return this
        }

        override fun isAlive(): Boolean = alive.get()
    }
}
