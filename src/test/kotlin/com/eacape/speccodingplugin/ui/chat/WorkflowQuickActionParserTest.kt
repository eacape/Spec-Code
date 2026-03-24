package com.eacape.speccodingplugin.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkflowQuickActionParserTest {

    @Test
    fun `parse should extract file references with locations`() {
        val content = """
            - Update `src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt:328`.
            - Verify src/test/kotlin/com/eacape/speccodingplugin/ui/chat/WorkflowSectionParserTest.kt#L24.
            - Ignore url: `https://example.com/path/file.kt`.
        """.trimIndent()

        val result = WorkflowQuickActionParser.parse(content)

        assertEquals(2, result.files.size)
        assertEquals("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt", result.files[0].path)
        assertEquals(328, result.files[0].line)
        assertEquals("src/test/kotlin/com/eacape/speccodingplugin/ui/chat/WorkflowSectionParserTest.kt", result.files[1].path)
        assertEquals(24, result.files[1].line)
    }

    @Test
    fun `parse should extract commands from shell blocks and command lines`() {
        val content = """
            ## Execute
            ```bash
            ./gradlew.bat test --tests "com.eacape.speccodingplugin.ui.chat.*"
            git status --short
            ```
            - 命令: `rg -n "Workflow" src/main/kotlin`
            - command: npm run lint
        """.trimIndent()

        val result = WorkflowQuickActionParser.parse(content)

        assertTrue(result.commands.contains("./gradlew.bat test --tests \"com.eacape.speccodingplugin.ui.chat.*\""))
        assertTrue(result.commands.contains("git status --short"))
        assertTrue(result.commands.contains("rg -n \"Workflow\" src/main/kotlin"))
        assertTrue(result.commands.contains("npm run lint"))
    }

    @Test
    fun `parse should dedupe and avoid treating command as file`() {
        val content = """
            - `src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt`
            - src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt
            - `./gradlew.bat test`
        """.trimIndent()

        val result = WorkflowQuickActionParser.parse(content)

        assertEquals(1, result.files.size)
        assertEquals("src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt", result.files[0].path)
        assertTrue(result.commands.contains("./gradlew.bat test"))
        assertFalse(result.files.any { it.path.contains("gradlew.bat") })
    }

    @Test
    fun `parse should detect workflow and legacy spec slash commands as runnable commands`() {
        val content = """
            ## Verify
            - Next step: `/workflow next`
            - Legacy alias: `/spec generate refine design section`
        """.trimIndent()

        val result = WorkflowQuickActionParser.parse(content)

        assertTrue(result.commands.contains("/workflow next"))
        assertTrue(result.commands.contains("/workflow generate refine design section"))
    }
}
