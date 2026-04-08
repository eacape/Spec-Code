package com.eacape.speccodingplugin.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpClientTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `start should surface runtime launch diagnostics through result and runtime log`() = runBlocking {
        val server = McpServer(
            config = McpServerConfig(
                id = "demo",
                name = "Demo MCP",
                command = "missing-mcp",
                trusted = true,
            ),
        )
        val runtime = McpServerProcessRuntime(
            processStarter = {
                error("missing executable")
            },
            osNameProvider = { "Linux" },
        )
        val client = McpClient(
            server = server,
            scope = scope,
            processRuntime = runtime,
        )
        val runtimeLogs = mutableListOf<McpRuntimeLogEvent>()
        client.setRuntimeLogListener { runtimeLogs += it }

        val result = client.start()

        assertTrue(result.isFailure)
        val error = assertInstanceOf(McpProcessLaunchException::class.java, result.exceptionOrNull())
        assertEquals(McpProcessLaunchFailureKind.EXECUTABLE_NOT_FOUND, error.diagnostic.kind)
        assertEquals(ServerStatus.STOPPED, server.status)
        assertTrue(
            runtimeLogs.any { event ->
                event.level == McpRuntimeLogLevel.ERROR &&
                    event.message.contains("executable-not-found")
            },
        )
    }
}
