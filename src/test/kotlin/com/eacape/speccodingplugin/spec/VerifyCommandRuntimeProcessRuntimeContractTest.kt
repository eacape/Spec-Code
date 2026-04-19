package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class VerifyCommandRuntimeProcessRuntimeContractTest {

    @Test
    fun `verify command runtime should delegate split output lifecycle to shared runtime`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/spec/VerifyCommandRuntime.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ExternalProcessLauncher.start("))
        assertTrue(source.contains("ExternalProcessLaunchSpec("))
        assertTrue(source.contains("VerifyCommandSplitOutputRuntime()"))
        assertTrue(source.contains("splitOutputRuntime.execute("))
        assertTrue(source.contains("VerifyCommandSplitOutputSpec("))
        assertTrue(source.contains("VerifyCommandFailureDiagnostics.diagnoseStartup("))
        assertTrue(source.contains("redirectErrorStream = false"))
        assertFalse(source.contains("ProcessBuilder("))
        assertFalse(source.contains("process.inputStream.bufferedReader().use"))
        assertFalse(source.contains("process.errorStream.bufferedReader().use"))
        assertFalse(source.contains("process.waitFor(request.timeoutMs.toLong(), TimeUnit.MILLISECONDS)"))
        assertFalse(source.contains("ManagedSplitOutputProcess.start("))
        assertFalse(source.contains("runtime.awaitCompletion("))
    }
}
