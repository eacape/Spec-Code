package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelExecutionStateContractTest {

    @Test
    fun `improved chat panel should delegate execution ui state to coordinator`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("private var executionState = ImprovedChatPanelExecutionState()"))
        assertTrue(source.contains("ImprovedChatPanelExecutionStateCoordinator.resolve(executionState)"))
        assertFalse(source.contains("private var isGenerating = false"))
        assertFalse(source.contains("private var isFinalizingResponse = false"))
        assertFalse(source.contains("private var isRestoringSession = false"))
    }
}
