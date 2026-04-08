package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class VerifyCommandRuntimeProcessRuntimeContractTest {

    @Test
    fun `verify command runtime should own split output process lifecycle`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/spec/VerifyCommandRuntime.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ProcessBuilder(request.command)"))
        assertTrue(source.contains("ManagedSplitOutputProcess.start("))
        assertTrue(source.contains("runtime.awaitCompletion("))
        assertTrue(source.contains("VerifyCommandFailureDiagnostics.diagnoseStartup("))
        assertFalse(source.contains("process.inputStream.bufferedReader().use"))
        assertFalse(source.contains("process.errorStream.bufferedReader().use"))
        assertFalse(source.contains("process.waitFor(request.timeoutMs.toLong(), TimeUnit.MILLISECONDS)"))
    }
}
