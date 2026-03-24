package com.eacape.speccodingplugin.mcp

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

class McpHubAcceptanceTest {

    private lateinit var project: Project
    private lateinit var scope: CoroutineScope
    private lateinit var hub: McpHub

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.basePath } returns "D:/tmp/mcp-test"
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDown() {
        if (::hub.isInitialized) {
            hub.dispose()
        }
        if (::scope.isInitialized) {
            scope.cancel()
        }
    }

    @Test
    fun `can register start discover call and stop server`() = runBlocking {
        val fakeClient = FakeMcpClientAdapter(
            startResult = Result.success(Unit),
            listToolsResult = Result.success(
                listOf(
                    McpTool(
                        name = "echo",
                        description = "Echo message",
                        inputSchema = Json.parseToJsonElement("{}")
                    )
                )
            ),
            callToolResult = Result.success(
                ToolCallResult.Success(
                    content = listOf(ToolContent(type = "text", text = "pong")),
                    isError = false
                )
            )
        )
        val hubWithFake = createHubWithFakeClient(mapOf("demo" to fakeClient))
        hub = hubWithFake

        val config = McpServerConfig(
            id = "demo",
            name = "Demo MCP",
            command = "npx",
            args = listOf("-y", "demo-server"),
            trusted = true
        )

        hub.registerServer(config).getOrThrow()
        hub.startServer(config.id).getOrThrow()

        val server = hub.getServer(config.id)
        assertNotNull(server)
        assertEquals(ServerStatus.RUNNING, server?.status)
        assertEquals(null, server?.error)

        val tools = hub.getServerTools(config.id)
        assertEquals(1, tools.size)
        assertEquals("echo", tools.first().name)

        val result = hub.callTool(
            ToolCallRequest(
                serverId = config.id,
                toolName = "echo",
                arguments = mapOf("message" to "ping")
            )
        ).getOrThrow()

        val success = assertInstanceOf(ToolCallResult.Success::class.java, result)
        assertEquals(false, success.isError)
        assertEquals("pong", success.content.firstOrNull()?.text)

        hub.stopServer(config.id).getOrThrow()
        assertEquals(ServerStatus.STOPPED, hub.getServer(config.id)?.status)
        assertTrue(hub.getServerTools(config.id).isEmpty())
        assertTrue(fakeClient.stopped)
    }

    @Test
    fun `start should fail when server is untrusted`() = runBlocking {
        var createdClientCount = 0
        hub = McpHub(
            project = project,
            scope = scope,
            configStoreProvider = { error("unused") },
            clientFactory = { _, _ ->
                createdClientCount += 1
                FakeMcpClientAdapter()
            },
            toolRegistry = McpToolRegistry()
        )

        val config = McpServerConfig(
            id = "untrusted",
            name = "Untrusted",
            command = "npx",
            trusted = false
        )

        hub.registerServer(config).getOrThrow()
        val result = hub.startServer(config.id)

        assertTrue(result.isFailure)
        assertInstanceOf(SecurityException::class.java, result.exceptionOrNull())
        assertEquals(0, createdClientCount)
        assertEquals(ServerStatus.STOPPED, hub.getServer(config.id)?.status)
    }

    @Test
    fun `start should mark error status when client start fails`() = runBlocking {
        val fakeClient = FakeMcpClientAdapter(
            startResult = Result.failure(IllegalStateException("start failed"))
        )
        hub = createHubWithFakeClient(mapOf("broken" to fakeClient))

        val config = McpServerConfig(
            id = "broken",
            name = "Broken MCP",
            command = "npx",
            trusted = true
        )

        hub.registerServer(config).getOrThrow()
        val result = hub.startServer(config.id)

        assertTrue(result.isFailure)
        assertEquals(ServerStatus.ERROR, hub.getServer(config.id)?.status)
        assertTrue(hub.getServer(config.id)?.error?.contains("start failed") == true)
        assertTrue(fakeClient.stopped)
    }

    @Test
    fun `start should mark error status when discover tools fails`() = runBlocking {
        val fakeClient = FakeMcpClientAdapter(
            startResult = Result.success(Unit),
            listToolsResult = Result.failure(IllegalStateException("discover failed"))
        )
        hub = createHubWithFakeClient(mapOf("discover-broken" to fakeClient))

        val config = McpServerConfig(
            id = "discover-broken",
            name = "Discover Broken MCP",
            command = "npx",
            trusted = true
        )

        hub.registerServer(config).getOrThrow()
        val result = hub.startServer(config.id)

        assertTrue(result.isFailure)
        assertEquals(ServerStatus.ERROR, hub.getServer(config.id)?.status)
        assertTrue(hub.getServer(config.id)?.error?.contains("discover failed") == true)
        assertTrue(fakeClient.stopped)
    }

    @Test
    fun `stop during startup keeps server stopped`() = runBlocking {
        val startEntered = CompletableDeferred<Unit>()
        val allowListTools = CompletableDeferred<Unit>()
        val fakeClient = FakeMcpClientAdapter(
            startResult = Result.success(Unit),
            listToolsResult = Result.success(emptyList()),
            onStart = { startEntered.complete(Unit) },
            onListTools = { allowListTools.await() },
            onStop = { allowListTools.complete(Unit) },
        )
        hub = createHubWithFakeClient(mapOf("race" to fakeClient))

        val config = McpServerConfig(
            id = "race",
            name = "Race MCP",
            command = "npx",
            trusted = true,
        )

        hub.registerServer(config).getOrThrow()
        val startJob = launch {
            hub.startServer(config.id).getOrThrow()
        }
        startEntered.await()
        hub.stopServer(config.id).getOrThrow()
        allowListTools.complete(Unit)
        startJob.join()

        assertEquals(ServerStatus.STOPPED, hub.getServer(config.id)?.status)
        assertEquals(null, hub.getServer(config.id)?.error)
        assertTrue(hub.getServerTools(config.id).isEmpty())
        assertTrue(fakeClient.stopped)
    }

    @Test
    fun `updating running server config restarts server with new runtime config`() = runBlocking {
        val fakeClient = FakeMcpClientAdapter(
            startResult = Result.success(Unit),
            listToolsResult = Result.success(emptyList()),
        )
        hub = createHubWithFakeClient(mapOf("demo" to fakeClient))

        val config = McpServerConfig(
            id = "demo",
            name = "Demo MCP",
            command = "npx",
            args = listOf("-y", "demo-server"),
            trusted = true,
        )

        hub.registerServer(config).getOrThrow()
        hub.startServer(config.id).getOrThrow()

        val updated = config.copy(
            command = "node",
            args = listOf("server.js"),
        )
        hub.updateServerConfig(updated).getOrThrow()

        assertEquals(listOf("npx", "node"), fakeClient.startedCommands)
        assertTrue(fakeClient.stopCount >= 1)
        assertEquals(updated, hub.getServer(config.id)?.config)
        assertEquals(ServerStatus.RUNNING, hub.getServer(config.id)?.status)
    }

    @Test
    fun `unexpected client termination marks server as error and clears tools`() = runBlocking {
        val fakeClient = FakeMcpClientAdapter(
            startResult = Result.success(Unit),
            listToolsResult = Result.success(
                listOf(
                    McpTool(
                        name = "echo",
                        description = "Echo message",
                        inputSchema = Json.parseToJsonElement("{}"),
                    )
                )
            ),
        )
        hub = createHubWithFakeClient(mapOf("demo" to fakeClient))

        val config = McpServerConfig(
            id = "demo",
            name = "Demo MCP",
            command = "npx",
            trusted = true,
        )

        hub.registerServer(config).getOrThrow()
        hub.startServer(config.id).getOrThrow()
        fakeClient.emitUnexpectedTermination("Server process exited unexpectedly (exit=1)", exitCode = 1)

        assertEquals(ServerStatus.ERROR, hub.getServer(config.id)?.status)
        assertTrue(hub.getServer(config.id)?.error?.contains("exit=1") == true)
        assertTrue(hub.getServerTools(config.id).isEmpty())
    }

    private fun createHubWithFakeClient(fakeClients: Map<String, FakeMcpClientAdapter>): McpHub {
        val fakeClientMap = ConcurrentHashMap(fakeClients)
        return McpHub(
            project = project,
            scope = scope,
            configStoreProvider = { error("unused") },
            clientFactory = { server, _ ->
                fakeClientMap[server.config.id]
                    ?.apply { bindTo(server) }
                    ?: error("No fake client prepared for server: ${server.config.id}")
            },
            toolRegistry = McpToolRegistry()
        )
    }

    private class FakeMcpClientAdapter(
        private val startResult: Result<Unit> = Result.success(Unit),
        private val listToolsResult: Result<List<McpTool>> = Result.success(emptyList()),
        private val callToolResult: Result<ToolCallResult> = Result.success(
            ToolCallResult.Success(emptyList())
        ),
        private val onStart: (suspend () -> Unit)? = null,
        private val onListTools: (suspend () -> Unit)? = null,
        private val onStop: (() -> Unit)? = null,
    ) : McpClientAdapter {

        private lateinit var boundServer: McpServer
        private var lifecycleListener: ((McpClientUnexpectedTermination) -> Unit)? = null

        var stopped: Boolean = false
            private set
        var stopCount: Int = 0
            private set
        val startedCommands = mutableListOf<String>()

        fun bindTo(server: McpServer) {
            boundServer = server
        }

        override suspend fun start(): Result<Unit> {
            onStart?.invoke()
            startedCommands += boundServer.config.command
            stopped = false
            return startResult
        }

        override suspend fun listTools(): Result<List<McpTool>> {
            onListTools?.invoke()
            return listToolsResult
        }

        override suspend fun callTool(
            toolName: String,
            arguments: Map<String, Any>
        ): Result<ToolCallResult> = callToolResult

        override fun stop() {
            stopped = true
            stopCount += 1
            onStop?.invoke()
        }

        override fun isRunning(): Boolean = startResult.isSuccess && !stopped

        override fun setLifecycleListener(listener: (McpClientUnexpectedTermination) -> Unit) {
            lifecycleListener = listener
        }

        fun emitUnexpectedTermination(message: String, exitCode: Int? = null) {
            lifecycleListener?.invoke(
                McpClientUnexpectedTermination(
                    message = message,
                    exitCode = exitCode,
                )
            )
        }
    }
}
