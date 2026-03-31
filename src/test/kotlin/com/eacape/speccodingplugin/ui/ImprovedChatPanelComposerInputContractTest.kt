package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelComposerInputContractTest {

    @Test
    fun `improved chat panel should delegate composer pasted text state to coordinator`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("private var composerInputState = ImprovedChatPanelComposerInputState()"))
        assertTrue(source.contains("ImprovedChatPanelComposerInputCoordinator.prepareSetComposerInput"))
        assertTrue(source.contains("ImprovedChatPanelComposerInputCoordinator.resolveAutoCollapse"))
        assertFalse(source.contains("private val pendingPastedTextBlocks = linkedMapOf<String, String>()"))
        assertFalse(source.contains("private var pastedTextSequence = 0"))
        assertFalse(source.contains("private var lastComposerTextSnapshot = \"\""))
    }
}
