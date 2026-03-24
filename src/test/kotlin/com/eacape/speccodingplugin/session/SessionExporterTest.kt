package com.eacape.speccodingplugin.session

import com.eacape.speccodingplugin.spec.StageId
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SessionExporterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `exportSession should write markdown json and html files`() {
        val session = ConversationSession(
            id = "session-12345678",
            title = "My Session",
            specTaskId = "spec-1",
            worktreeId = "wt-1",
            modelProvider = "openai",
            createdAt = 1_700_000_000_000,
            updatedAt = 1_700_000_100_000,
            workflowChatBinding = WorkflowChatBinding(
                workflowId = "spec-1",
                focusedStage = StageId.IMPLEMENT,
                source = WorkflowChatEntrySource.TASK_PANEL,
                actionIntent = WorkflowChatActionIntent.EXECUTE_TASK,
            ),
        )
        val messages = listOf(
            ConversationMessage(
                id = "m1",
                sessionId = session.id,
                role = ConversationRole.USER,
                content = "hello",
                createdAt = 1_700_000_000_100,
            ),
            ConversationMessage(
                id = "m2",
                sessionId = session.id,
                role = ConversationRole.ASSISTANT,
                content = "world",
                createdAt = 1_700_000_000_200,
            ),
        )

        SessionExportFormat.entries.forEach { format ->
            val result = SessionExporter.exportSession(
                exportDir = tempDir,
                session = session,
                messages = messages,
                format = format,
                nowMillis = 1_700_000_200_000,
            ).getOrThrow()

            assertTrue(Files.exists(result.filePath))
            assertTrue(result.filePath.fileName.toString().endsWith(".${format.extension}"))

            val content = Files.readString(result.filePath)
            assertTrue(content.isNotBlank())
            assertTrue(content.contains(session.title))
            assertTrue(content.contains("spec-1"))
        }
    }
}
