package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelWorkflowCommandRunnerProcessRuntimeContractTest {

    @Test
    fun `workflow command runner should delegate merged output runtime details to shared core runtime`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanelWorkflowCommandRunner.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ManagedMergedOutputProcess.start("))
        assertTrue(source.contains("started.handle.awaitCompletion("))
        assertFalse(source.contains("val outputReaderThread = Thread"))
        assertFalse(source.contains("started.process.waitFor(timeoutSeconds, TimeUnit.SECONDS)"))
        assertFalse(source.contains("started.outputReaderThread.join(joinTimeoutMillis)"))
    }
}
