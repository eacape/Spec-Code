package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelSendPerformanceContractTest {

    @Test
    fun `improved chat panel should capture assistant response workspace snapshots inside launchIo blocks`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertEquals(
            2,
            Regex("""val beforeSnapshot = captureWorkspaceSnapshot\(\)""").findAll(source).count(),
        )
        assertEquals(
            2,
            Regex(
                """activeOperationJob = taskCoordinator\.launchIo \{.{0,400}?val beforeSnapshot = captureWorkspaceSnapshot\(\)""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).findAll(source).count(),
        )
        assertEquals(
            0,
            Regex("""val assistantPanel = addAssistantMessage\(startedAtMillis = assistantStartedAtMillis\)""")
                .findAll(source)
                .count(),
        )
        assertEquals(
            2,
            Regex(
                """val assistantStartedAtMillis = System\.currentTimeMillis\(\)\s+currentAssistantPanel = null\s+val promptEchoFilter = PromptReferenceEchoFilter\.fromTextBlocks\(promptSystemMessages\)\s+activeOperationJob = taskCoordinator\.launchIo \{\s+var assistantPanel: ChatMessagePanel\? = null""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).findAll(source).count(),
        )
        assertFalse(
            source.contains(
                """
                clearComposerInput()

                        val beforeSnapshot = captureWorkspaceSnapshot()
                """.trimIndent(),
            ),
        )
        assertFalse(
            source.contains(
                """
                val operationMode = modeManager.getCurrentMode()
                        val beforeSnapshot = captureWorkspaceSnapshot()
                """.trimIndent(),
            ),
        )
    }
}
