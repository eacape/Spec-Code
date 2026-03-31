package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelTaskCoordinatorContractTest {

    @Test
    fun `improved chat panel should route background entrypoints through shared task coordinator`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("private val taskCoordinator = SwingPanelTaskCoordinator("))
        assertTrue(source.contains("taskCoordinator.launchIo"))
        assertTrue(source.contains("taskCoordinator.dispose()"))
        assertFalse(source.contains("private val scope = CoroutineScope("))
        assertFalse(source.contains("scope.launch(Dispatchers.IO)"))
        assertFalse(source.contains("scope.cancel()"))
    }
}
