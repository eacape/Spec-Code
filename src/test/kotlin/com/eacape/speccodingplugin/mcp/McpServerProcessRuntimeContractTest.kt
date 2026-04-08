package com.eacape.speccodingplugin.mcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class McpServerProcessRuntimeContractTest {

    @Test
    fun `mcp process runtime should own process builder lifecycle and launch diagnostics`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/mcp/McpServerProcessRuntime.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ProcessBuilder(request.launchCommand)"))
        assertTrue(source.contains("redirectErrorStream(false)"))
        assertTrue(source.contains("McpProcessLaunchFailureDiagnostics.diagnoseLaunch("))
        assertFalse(source.contains("process.waitFor("))
        assertFalse(source.contains("process.inputStream.bufferedReader().use"))
    }
}
