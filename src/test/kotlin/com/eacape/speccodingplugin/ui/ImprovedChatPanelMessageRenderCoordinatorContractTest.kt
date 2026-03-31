package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelMessageRenderCoordinatorContractTest {

    @Test
    fun `improved chat panel should delegate restored message planning to coordinator`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("ImprovedChatPanelMessageRenderCoordinator.planRestoredMessage("))
        assertFalse(source.contains("val executionLaunchPayload = executionMetadata.resolveExecutionLaunchRestorePayload(message.content)"))
        assertFalse(source.contains("val restoredSpecMetadata = SpecCardMetadataCodec.decode(message.metadataJson)"))
        assertFalse(source.contains("val restoredTraceMetadata = TraceEventMetadataCodec.decodePayload(message.metadataJson)"))
    }
}
