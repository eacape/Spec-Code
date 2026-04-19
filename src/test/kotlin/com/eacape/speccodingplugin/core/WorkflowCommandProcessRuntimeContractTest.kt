package com.eacape.speccodingplugin.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class WorkflowCommandProcessRuntimeContractTest {

    @Test
    fun `workflow process runtime should delegate merged output process lifecycle to shared runtime`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/core/WorkflowCommandProcessRuntime.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ExternalProcessLauncher.start("))
        assertTrue(source.contains("ExternalProcessLaunchSpec("))
        assertTrue(source.contains("ExternalMergedOutputCommandRuntime()"))
        assertTrue(source.contains("mergedOutputRuntime.start("))
        assertTrue(source.contains("mergedOutputRuntime.await("))
        assertTrue(source.contains("ExternalMergedOutputCommandSpec("))
        assertTrue(source.contains("runningCommands = ConcurrentHashMap"))
        assertTrue(source.contains("WorkflowCommandFailureDiagnostics.diagnoseStartup("))
        assertTrue(source.contains("fun stop(commandKey: String): WorkflowCommandProcessStopResult"))
        assertTrue(source.contains("redirectErrorStream = true"))
        assertFalse(source.contains("ProcessBuilder("))
        assertFalse(source.contains("ManagedMergedOutputProcess.start("))
    }
}
