package com.eacape.speccodingplugin.hook

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class HookCommandRuntimeContractTest {

    @Test
    fun `hook command runtime should delegate merged output lifecycle to shared runtime`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/hook/HookCommandRuntime.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ExternalMergedOutputCommandRuntime()"))
        assertTrue(source.contains("mergedOutputRuntime.execute("))
        assertTrue(source.contains("ExternalMergedOutputCommandSpec("))
        assertTrue(source.contains("HookCommandFailureDiagnostics.diagnoseStartup("))
        assertFalse(source.contains("ManagedMergedOutputProcess.start("))
        assertFalse(source.contains("process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)"))
        assertFalse(source.contains("process.inputStream.bufferedReader().use"))
        assertFalse(source.contains("process.destroyForcibly()"))
    }
}
