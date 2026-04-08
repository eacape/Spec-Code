package com.eacape.speccodingplugin.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class GitCliProcessRuntimeContractTest {

    @Test
    fun `shared git runtime should delegate merged output lifecycle to managed runtime`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/core/GitCliProcessRuntime.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ManagedMergedOutputProcess.start("))
        assertTrue(source.contains("runtime.awaitCompletion("))
        assertFalse(source.contains("process.waitFor("))
        assertFalse(source.contains("process.inputStream.bufferedReader().use"))
        assertFalse(source.contains("process.destroyForcibly()"))
    }
}
