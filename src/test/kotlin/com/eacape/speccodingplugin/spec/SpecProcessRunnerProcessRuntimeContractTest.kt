package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class SpecProcessRunnerProcessRuntimeContractTest {

    @Test
    fun `spec process runner should delegate verify process lifecycle to dedicated runtime`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/spec/SpecProcessRunner.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("private val runtime: VerifyCommandRuntime"))
        assertTrue(source.contains("runtime = VerifyCommandRuntime()"))
        assertTrue(source.contains("val execution = runtime.execute(request)"))
        assertTrue(source.contains("execution.startupDiagnostic?.let"))
        assertFalse(source.contains("ProcessBuilder("))
        assertFalse(source.contains("ManagedSplitOutputProcess.start("))
        assertFalse(source.contains("runtime.awaitCompletion("))
        assertFalse(source.contains("process.destroyForcibly()"))
        assertFalse(source.contains("startupErrorMessage"))
    }
}
