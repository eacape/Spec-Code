package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class SpecProcessRunnerProcessRuntimeContractTest {

    @Test
    fun `spec process runner should delegate split output process lifecycle to shared core runtime`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/spec/SpecProcessRunner.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ManagedSplitOutputProcess.start("))
        assertTrue(source.contains("runtime.awaitCompletion("))
        assertFalse(source.contains("private fun consumeStream("))
        assertFalse(source.contains("private fun joinCaptureThread("))
        assertFalse(source.contains("private data class OutputCapture("))
        assertFalse(source.contains("process.waitFor(request.timeoutMs.toLong(), TimeUnit.MILLISECONDS)"))
        assertFalse(source.contains("process.destroyForcibly()"))
    }
}
