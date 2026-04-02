package com.eacape.speccodingplugin.hook

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class HookExecutorProcessRuntimeContractTest {

    @Test
    fun `hook executor should delegate run command process lifecycle to hook runtime`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/hook/HookExecutor.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("commandRuntime: HookCommandRuntime = HookCommandRuntime()"))
        assertTrue(source.contains("commandRuntime.execute("))
        assertFalse(source.contains("ProcessBuilder("))
        assertFalse(source.contains("ManagedMergedOutputProcess.start("))
        assertFalse(source.contains("runtime.awaitCompletion("))
        assertFalse(source.contains("redirectErrorStream(true)"))
    }
}
