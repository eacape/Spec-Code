package com.eacape.speccodingplugin.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class WorkflowCommandProcessRuntimeContractTest {

    @Test
    fun `workflow process runtime should own merged output process lifecycle`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/core/WorkflowCommandProcessRuntime.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ProcessBuilder(command)"))
        assertTrue(source.contains("ManagedMergedOutputProcess.start("))
        assertTrue(source.contains("runningCommands = ConcurrentHashMap"))
        assertTrue(source.contains("fun stop(commandKey: String): WorkflowCommandProcessStopResult"))
    }
}
