package com.eacape.speccodingplugin.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class McpServerProcessRuntimeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `start should pass prepared launch command and env to process starter`() {
        val capturedRequest = AtomicReference<McpServerProcessLaunchRequest>()
        val runtime = McpServerProcessRuntime(
            processStarter = { request ->
                capturedRequest.set(request)
                CompletedProcess()
            },
            osNameProvider = { "Linux" },
        )

        val config = config(
            command = "demo-mcp",
            args = listOf("--stdio"),
            env = mapOf("DEMO_TOKEN" to "secret"),
        )

        val process = runtime.start(config)

        assertInstanceOf(CompletedProcess::class.java, process)
        assertEquals(listOf("demo-mcp", "--stdio"), capturedRequest.get().launchCommand)
        assertEquals(mapOf("DEMO_TOKEN" to "secret"), capturedRequest.get().config.env)
    }

    @Test
    fun `prepare launch command should resolve windows wrappers from PATH`() {
        val nodeBin = tempDir.resolve("node-bin")
        Files.createDirectories(nodeBin)
        val runtime = McpServerProcessRuntime(
            osNameProvider = { "Windows 11" },
            envProvider = { key ->
                when (key) {
                    "PATH" -> nodeBin.toString()
                    else -> null
                }
            },
            regularFileChecker = { path ->
                path.normalize().toString() == nodeBin.resolve("npx.cmd").normalize().toString()
            },
        )

        val launchCommand = runtime.prepareLaunchCommand(
            config(
                command = "npx",
                args = listOf("-y", "demo-mcp"),
            ),
        )

        assertEquals(nodeBin.resolve("npx.cmd").toString(), launchCommand.first())
        assertEquals(listOf("-y", "demo-mcp"), launchCommand.drop(1))
    }

    @Test
    fun `start should surface missing executable as structured diagnostic`() {
        val runtime = McpServerProcessRuntime(
            processStarter = {
                error("CreateProcess error=2, The system cannot find the file specified")
            },
            osNameProvider = { "Windows 11" },
        )

        val error = assertInstanceOf(
            McpProcessLaunchException::class.java,
            runCatching { runtime.start(config(command = "npx")) }.exceptionOrNull(),
        )

        assertEquals(McpProcessLaunchFailureKind.EXECUTABLE_NOT_FOUND, error.diagnostic.kind)
        assertTrue(error.message.orEmpty().contains("npx.cmd"))
        assertTrue(error.diagnostic.renderDetail().contains("mcp executable was not found"))
    }

    @Test
    fun `start should classify access denied separately`() {
        val runtime = McpServerProcessRuntime(
            processStarter = {
                error("CreateProcess error=5, Access is denied")
            },
        )

        val error = assertInstanceOf(
            McpProcessLaunchException::class.java,
            runCatching { runtime.start(config(command = "denied-mcp")) }.exceptionOrNull(),
        )

        assertEquals(McpProcessLaunchFailureKind.ACCESS_DENIED, error.diagnostic.kind)
        assertTrue(error.diagnostic.renderDetail().contains("access denied"))
    }

    private fun config(
        command: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
    ): McpServerConfig {
        return McpServerConfig(
            id = "demo",
            name = "Demo MCP",
            command = command,
            args = args,
            env = env,
            trusted = true,
        )
    }

    private class CompletedProcess : Process() {
        private val input = ByteArrayInputStream(ByteArray(0))
        private val error = ByteArrayInputStream(ByteArray(0))
        private val output = ByteArrayOutputStream()
        private val alive = AtomicBoolean(true)

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = error

        override fun getOutputStream(): OutputStream = output

        override fun waitFor(): Int {
            alive.set(false)
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            alive.set(false)
            return true
        }

        override fun exitValue(): Int {
            check(!alive.get()) { "Process is still alive" }
            return 0
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
}
