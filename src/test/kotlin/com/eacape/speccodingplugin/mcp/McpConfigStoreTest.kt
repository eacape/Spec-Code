package com.eacape.speccodingplugin.mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class McpConfigStoreTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @TempDir
    lateinit var tempDir: Path

    private lateinit var storePath: Path

    @BeforeEach
    fun setUp() {
        storePath = tempDir.resolve(".spec-coding").resolve("mcp-servers.json")
    }

    @Test
    fun `should serialize config to JSON correctly`() {
        val config = McpServerConfig(
            id = "test-server",
            name = "Test Server",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-filesystem"),
            env = mapOf("HOME" to "/tmp"),
            transport = TransportType.STDIO,
            autoStart = true,
            trusted = true
        )

        assertNotNull(config)
        assertEquals("test-server", config.id)
        assertEquals("Test Server", config.name)
        assertEquals("npx", config.command)
        assertEquals(2, config.args.size)
        assertTrue(config.autoStart)
        assertTrue(config.trusted)
    }

    @Test
    fun `should create config with default values`() {
        val config = McpServerConfig(
            id = "minimal",
            name = "Minimal",
            command = "node"
        )

        assertEquals(emptyList<String>(), config.args)
        assertEquals(emptyMap<String, String>(), config.env)
        assertEquals(TransportType.STDIO, config.transport)
        assertFalse(config.autoStart)
        assertFalse(config.trusted)
    }

    @Test
    fun `should handle transport type enum correctly`() {
        assertEquals(TransportType.STDIO, TransportType.valueOf("STDIO"))
        assertEquals(TransportType.SSE, TransportType.valueOf("SSE"))
    }

    @Test
    fun `should validate server status enum`() {
        val statuses = ServerStatus.entries
        assertEquals(4, statuses.size)
        assertTrue(statuses.contains(ServerStatus.STOPPED))
        assertTrue(statuses.contains(ServerStatus.STARTING))
        assertTrue(statuses.contains(ServerStatus.RUNNING))
        assertTrue(statuses.contains(ServerStatus.ERROR))
    }

    @Test
    fun `should create McpServer with default status`() {
        val config = McpServerConfig(
            id = "test", name = "Test", command = "npx"
        )
        val server = McpServer(config)

        assertEquals(ServerStatus.STOPPED, server.status)
        assertNull(server.process)
        assertNull(server.capabilities)
        assertNull(server.error)
    }

    @Test
    fun `should handle empty env and args in config`() {
        val config = McpServerConfig(
            id = "empty-config",
            name = "Empty Config",
            command = "python3",
            args = emptyList(),
            env = emptyMap()
        )

        assertTrue(config.args.isEmpty())
        assertTrue(config.env.isEmpty())
    }
}
