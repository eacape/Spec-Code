package com.eacape.speccodingplugin.hook

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class HookExecutorProcessRuntimeContractTest {

    @Test
    fun `hook executor should delegate merged output process lifecycle to shared runtime`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/hook/HookExecutor.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ManagedMergedOutputProcess.start("))
        assertTrue(source.contains("runtime.awaitCompletion("))
        assertFalse(source.contains("val outputReaderThread = Thread"))
        assertFalse(source.contains("process.waitFor(action.timeoutMillis, TimeUnit.MILLISECONDS)"))
        assertFalse(source.contains("outputReaderThread.join(2_000)"))
    }
}
