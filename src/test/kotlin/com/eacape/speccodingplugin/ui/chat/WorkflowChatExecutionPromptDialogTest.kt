package com.eacape.speccodingplugin.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkflowChatExecutionPromptDialogTest {

    @Test
    fun `parse should split execution prompt into lead lines and sections`() {
        val overview = WorkflowChatExecutionPromptDebugOverview.parse(
            """
            Interaction mode: workflow
            Workflow=wf-152 (docs: .spec-coding/specs/wf-152/{requirements,design,tasks}.md)
            Execution action: EXECUTE_WITH_AI
            Run ID: run-152

            ## Task
            Task ID: T-152
            Task Title: Improve internal prompt viewer

            ## Stage Context
            Current phase: IMPLEMENT
            Current stage: TASKS

            ## Execution Request
            Use the task-scoped context above to execute this structured task.
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "Interaction mode: workflow",
                "Workflow=wf-152 (docs: .spec-coding/specs/wf-152/{requirements,design,tasks}.md)",
                "Execution action: EXECUTE_WITH_AI",
                "Run ID: run-152",
            ),
            overview.leadLines,
        )
        assertEquals(listOf("Task", "Stage Context", "Execution Request"), overview.sections.map { it.title })
        assertTrue(overview.sections.first().lines.joinToString("\n").contains("Task ID: T-152"))
        assertTrue(overview.sections.last().lines.joinToString("\n").contains("Use the task-scoped context above"))
    }

    @Test
    fun `section preview should compact lines and report hidden remainder`() {
        val section = WorkflowChatExecutionPromptDebugSection(
            title = "Candidate Related Files",
            lines = listOf(
                "",
                "- src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt",
                "2. src/test/kotlin/com/eacape/speccodingplugin/ui/WorkflowChatExecutionContextLinkPlatformTest.kt",
                "```",
                "   The summary view should keep only the first few meaningful lines and avoid stretching the dialog into unreadable blocks.   ",
                "Final note",
            ),
        )

        assertEquals(4, section.displayLineCount())
        val previewLines = section.previewLines(maxLines = 3, maxLineLength = 56)
        assertTrue(previewLines[0].startsWith("src/main/kotlin/com/eacape/speccodingplugin/ui/Improv"))
        assertTrue(previewLines[0].endsWith("..."))
        assertTrue(previewLines[1].startsWith("src/test/kotlin/com/eacape/speccodingplugin/ui/Workfl"))
        assertTrue(previewLines[1].endsWith("..."))
        assertTrue(previewLines[2].startsWith("The summary view should keep only the first few"))
        assertTrue(previewLines[2].endsWith("..."))
        assertEquals(1, section.hiddenLineCount(maxLines = 3))
    }
}
