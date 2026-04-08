package com.eacape.speccodingplugin.mcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class McpClientProcessRuntimeContractTest {

    @Test
    fun `mcp client should delegate process launch to runtime`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/mcp/McpClient.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("private val processRuntime: McpServerProcessRuntime"))
        assertTrue(source.contains("processRuntime.prepareLaunchCommand(server.config)"))
        assertTrue(source.contains("processRuntime.start("))
        assertTrue(source.contains("error.diagnostic.renderMessage()"))
        assertFalse(source.contains("ProcessBuilder("))
        assertFalse(source.contains("buildLaunchFailureMessage("))
        assertFalse(source.contains("error.message ?: \"Process launch failed\""))
    }
}
