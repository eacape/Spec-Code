package com.eacape.speccodingplugin.session

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class SessionExportFormat(val extension: String) {
    MARKDOWN("md"),
    JSON("json"),
    HTML("html"),
    ;

    override fun toString(): String = when (this) {
        MARKDOWN -> "Markdown"
        JSON -> "JSON"
        HTML -> "HTML"
    }
}

data class SessionExportResult(
    val format: SessionExportFormat,
    val filePath: Path,
    val messageCount: Int,
    val bytesWritten: Long,
)

object SessionExporter {
    private val logger = thisLogger()
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    private val fileNameTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())

    fun exportSession(
        exportDir: Path,
        session: ConversationSession,
        messages: List<ConversationMessage>,
        format: SessionExportFormat,
        nowMillis: Long = System.currentTimeMillis(),
    ): Result<SessionExportResult> {
        return runCatching {
            Files.createDirectories(exportDir)

            val fileName = buildFileName(session, format, nowMillis)
            val filePath = exportDir.resolve(fileName)
            val content = renderContent(session, messages, format)

            Files.writeString(filePath, content, StandardCharsets.UTF_8)
            val bytesWritten = Files.size(filePath)

            logger.info("Exported session '${session.id}' to: $filePath")
            SessionExportResult(
                format = format,
                filePath = filePath,
                messageCount = messages.size,
                bytesWritten = bytesWritten,
            )
        }
    }

    private fun buildFileName(
        session: ConversationSession,
        format: SessionExportFormat,
        nowMillis: Long,
    ): String {
        val timestamp = fileNameTimeFormatter.format(Instant.ofEpochMilli(nowMillis))
        val safeTitle = session.title.trim()
            .ifBlank { "session" }
            .replace(Regex("[^a-zA-Z0-9._-]+"), "-")
            .trim('-')
            .lowercase()
            .take(40)
            .ifBlank { "session" }
        val shortId = session.id.take(8)
        return "$timestamp-$safeTitle-$shortId.${format.extension}"
    }

    private fun renderContent(
        session: ConversationSession,
        messages: List<ConversationMessage>,
        format: SessionExportFormat,
    ): String {
        return when (format) {
            SessionExportFormat.MARKDOWN -> renderMarkdown(session, messages)
            SessionExportFormat.JSON -> renderJson(session, messages)
            SessionExportFormat.HTML -> renderHtml(session, messages)
        }
    }

    private fun renderMarkdown(
        session: ConversationSession,
        messages: List<ConversationMessage>,
    ): String {
        return buildString {
            appendLine("# ${session.title}")
            appendLine()
            appendLine("- Session ID: `${session.id}`")
            appendLine("- Created At: ${formatTimestamp(session.createdAt)}")
            appendLine("- Updated At: ${formatTimestamp(session.updatedAt)}")
            appendLine("- Spec Task: ${session.specTaskId ?: "-"}")
            appendLine("- Workflow Binding: ${session.workflowChatBinding?.workflowId ?: "-"}")
            appendLine("- Focused Stage: ${session.workflowChatBinding?.focusedStage?.name ?: "-"}")
            appendLine("- Action Intent: ${session.workflowChatBinding?.actionIntent?.name ?: "-"}")
            appendLine("- Worktree: ${session.worktreeId ?: "-"}")
            appendLine("- Model Provider: ${session.modelProvider ?: "-"}")
            appendLine("- Message Count: ${messages.size}")
            appendLine()
            appendLine("## Messages")
            appendLine()

            if (messages.isEmpty()) {
                appendLine("_No messages_")
                return@buildString
            }

            messages.forEachIndexed { index, message ->
                appendLine("### ${index + 1}. ${message.role.name} @ ${formatTimestamp(message.createdAt)}")
                appendLine()
                appendLine(message.content.prependIndent("    "))
                appendLine()
            }
        }
    }

    private fun renderJson(
        session: ConversationSession,
        messages: List<ConversationMessage>,
    ): String {
        val payload = SessionExportPayload(
            id = session.id,
            title = session.title,
            specTaskId = session.specTaskId,
            workflowChatBinding = session.workflowChatBinding?.let { binding ->
                SessionExportWorkflowChatBinding(
                    workflowId = binding.workflowId,
                    focusedStage = binding.focusedStage?.name,
                    source = binding.source.name,
                    actionIntent = binding.actionIntent.name,
                )
            },
            worktreeId = session.worktreeId,
            modelProvider = session.modelProvider,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            messages = messages.map { message ->
                SessionExportMessage(
                    id = message.id,
                    role = message.role.name,
                    content = message.content,
                    tokenCount = message.tokenCount,
                    createdAt = message.createdAt,
                    metadataJson = message.metadataJson,
                )
            },
        )
        return json.encodeToString(payload)
    }

    private fun renderHtml(
        session: ConversationSession,
        messages: List<ConversationMessage>,
    ): String {
        val title = escapeHtml(session.title)
        val metadataRows = listOf(
            "Session ID" to escapeHtml(session.id),
            "Created At" to escapeHtml(formatTimestamp(session.createdAt)),
            "Updated At" to escapeHtml(formatTimestamp(session.updatedAt)),
            "Spec Task" to escapeHtml(session.specTaskId ?: "-"),
            "Workflow Binding" to escapeHtml(session.workflowChatBinding?.workflowId ?: "-"),
            "Focused Stage" to escapeHtml(session.workflowChatBinding?.focusedStage?.name ?: "-"),
            "Action Intent" to escapeHtml(session.workflowChatBinding?.actionIntent?.name ?: "-"),
            "Worktree" to escapeHtml(session.worktreeId ?: "-"),
            "Model Provider" to escapeHtml(session.modelProvider ?: "-"),
            "Message Count" to messages.size.toString(),
        )

        val messageBlocks = if (messages.isEmpty()) {
            "<p><em>No messages</em></p>"
        } else {
            messages.joinToString("\n") { message ->
                val roleClass = message.role.name.lowercase()
                val role = escapeHtml(message.role.name)
                val createdAt = escapeHtml(formatTimestamp(message.createdAt))
                val content = escapeHtml(message.content)
                """
                <article class=\"msg role-$roleClass\">
                  <header><strong>$role</strong> · <span>$createdAt</span></header>
                  <pre>$content</pre>
                </article>
                """.trimIndent()
            }
        }

        val metadataTable = metadataRows.joinToString("\n") { (key, value) ->
            "<tr><th>${escapeHtml(key)}</th><td>$value</td></tr>"
        }

        return """
        <!doctype html>
        <html lang=\"en\">
        <head>
          <meta charset=\"utf-8\" />
          <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
          <title>$title</title>
          <style>
            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 24px; color: #222; }
            h1 { margin-bottom: 8px; }
            table { border-collapse: collapse; margin: 12px 0 20px; }
            th, td { border: 1px solid #ddd; padding: 6px 10px; text-align: left; }
            .msg { border: 1px solid #ddd; border-radius: 6px; padding: 10px; margin-bottom: 10px; }
            .role-user { border-left: 4px solid #3b82f6; }
            .role-assistant { border-left: 4px solid #22c55e; }
            .role-system { border-left: 4px solid #6b7280; }
            .role-tool { border-left: 4px solid #a855f7; }
            pre { white-space: pre-wrap; margin: 8px 0 0; }
          </style>
        </head>
        <body>
          <h1>$title</h1>
          <table>
            <tbody>
              $metadataTable
            </tbody>
          </table>
          <section>
            <h2>Messages</h2>
            $messageBlocks
          </section>
        </body>
        </html>
        """.trimIndent()
    }

    private fun formatTimestamp(timestamp: Long): String {
        return Instant.ofEpochMilli(timestamp).toString()
    }

    private fun escapeHtml(text: String): String {
        if (text.isEmpty()) return text
        return buildString(text.length) {
            text.forEach { ch ->
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(ch)
                }
            }
        }
    }
}

@Serializable
private data class SessionExportPayload(
    val id: String,
    val title: String,
    val specTaskId: String? = null,
    val workflowChatBinding: SessionExportWorkflowChatBinding? = null,
    val worktreeId: String? = null,
    val modelProvider: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<SessionExportMessage>,
)

@Serializable
private data class SessionExportWorkflowChatBinding(
    val workflowId: String,
    val focusedStage: String? = null,
    val source: String,
    val actionIntent: String,
)

@Serializable
private data class SessionExportMessage(
    val id: String,
    val role: String,
    val content: String,
    val tokenCount: Int? = null,
    val createdAt: Long,
    val metadataJson: String? = null,
)
