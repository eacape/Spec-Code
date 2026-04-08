package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelWorkflowCommandRunnerProcessRuntimeContractTest {

    @Test
    fun `workflow command runner should delegate process lifecycle details to shared core workflow runtime`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanelWorkflowCommandRunner.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("private val processRuntime: WorkflowCommandProcessRuntime"))
        assertTrue(source.contains("processRuntime = createProcessRuntime("))
        assertTrue(source.contains("processRuntime.execute("))
        assertTrue(source.contains("processRuntime.stop(command)"))
        assertFalse(source.contains("ManagedMergedOutputProcess.start("))
        assertFalse(source.contains("awaitCompletion("))
        assertFalse(source.contains("ProcessBuilder(command)"))
        assertFalse(source.contains("private val runningCommands = ConcurrentHashMap"))
    }
}
