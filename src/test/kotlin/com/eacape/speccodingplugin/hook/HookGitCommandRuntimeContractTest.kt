package com.eacape.speccodingplugin.hook

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class HookGitCommandRuntimeContractTest {

    @Test
    fun `hook git command runtime should delegate merged output lifecycle to shared runtime`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/hook/HookGitCommandRuntime.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ManagedMergedOutputProcess.start("))
        assertTrue(source.contains("runtime.awaitCompletion("))
        assertFalse(source.contains("process.waitFor("))
        assertFalse(source.contains("process.inputStream.bufferedReader().use"))
        assertFalse(source.contains("process.destroyForcibly()"))
    }
}
