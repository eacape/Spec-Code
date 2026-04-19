package com.eacape.speccodingplugin.engine

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class CliCommandRuntimeLauncherContractTest {

    @Test
    fun `cli command runtime should use shared external launcher`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/engine/CliCommandRuntime.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ExternalProcessLauncher.start("))
        assertTrue(source.contains("ExternalProcessLaunchSpec("))
        assertTrue(source.contains("ExternalMergedOutputCommandRuntime()"))
        assertTrue(source.contains("mergedOutputRuntime.execute("))
        assertTrue(source.contains("ExternalMergedOutputCommandSpec("))
        assertFalse(source.contains("ProcessBuilder("))
        assertFalse(source.contains("ManagedMergedOutputProcess.start("))
    }
}
