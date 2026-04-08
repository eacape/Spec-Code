package com.eacape.speccodingplugin.engine

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class CliProcessRuntimeContractTest {

    @Test
    fun `cli engine should delegate startup and probes to shared runtime`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/engine/CliEngine.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("private val commandRuntime = CliCommandRuntime()"))
        assertTrue(source.contains("commandRuntime.execute("))
        assertTrue(source.contains("commandRuntime.start("))
        assertFalse(source.contains("ProcessBuilder("))
    }

    @Test
    fun `cli discovery service should delegate probes to shared runtime`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/engine/CliDiscoveryService.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("private val commandRuntime = CliCommandRuntime("))
        assertTrue(source.contains("commandRuntime.execute("))
        assertFalse(source.contains("ProcessBuilder("))
    }
}
