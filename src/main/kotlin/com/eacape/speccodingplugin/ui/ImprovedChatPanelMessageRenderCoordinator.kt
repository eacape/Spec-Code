package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.llm.LlmMessage
import com.eacape.speccodingplugin.llm.LlmRole
import com.eacape.speccodingplugin.session.ConversationMessage
import com.eacape.speccodingplugin.session.ConversationRole
import com.eacape.speccodingplugin.spec.TaskExecutionSessionMetadataCodec
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchRestorePayload
import com.eacape.speccodingplugin.spec.resolveExecutionLaunchRawPrompt
import com.eacape.speccodingplugin.spec.resolveExecutionLaunchRestorePayload
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.ui.chat.SpecCardMetadata
import com.eacape.speccodingplugin.ui.chat.SpecCardMetadataCodec
import com.eacape.speccodingplugin.ui.chat.TraceEventMetadataCodec

internal data class ImprovedChatPanelRestoredMessageRenderDecision(
    val plan: ImprovedChatPanelMessageRenderPlan,
    val historyMessage: LlmMessage? = null,
    val marksTaskExecutionMessageRendered: Boolean = false,
)

internal sealed interface ImprovedChatPanelMessageRenderPlan {

    data class UserPlain(
        val visibleContent: String,
        val rawContent: String,
    ) : ImprovedChatPanelMessageRenderPlan

    data class UserExecutionLaunch(
        val visibleContent: String,
        val rawContent: String,
        val payload: WorkflowChatExecutionLaunchRestorePayload,
        val compact: Boolean,
    ) : ImprovedChatPanelMessageRenderPlan

    data class AssistantSpecCard(
        val cardMarkdown: String,
        val metadata: SpecCardMetadata,
    ) : ImprovedChatPanelMessageRenderPlan

    data class AssistantPanel(
        val content: String,
        val traceEvents: List<ChatStreamEvent>,
        val startedAtMillis: Long?,
        val finishedAtMillis: Long?,
    ) : ImprovedChatPanelMessageRenderPlan

    data class SystemMessage(
        val content: String,
    ) : ImprovedChatPanelMessageRenderPlan

    data class ToolMessage(
        val content: String,
    ) : ImprovedChatPanelMessageRenderPlan
}

internal object ImprovedChatPanelMessageRenderCoordinator {

    fun planRestoredMessage(
        message: ConversationMessage,
        fromSessionRestore: Boolean = false,
        activeExecutionLaunchRunIds: Set<String> = emptySet(),
        executionMetadata: TaskExecutionSessionMetadataCodec.DecodedMetadata =
            TaskExecutionSessionMetadataCodec.decode(message.metadataJson),
        specCardMetadata: SpecCardMetadata? = SpecCardMetadataCodec.decode(message.metadataJson),
        traceMetadata: TraceEventMetadataCodec.DecodedMetadata =
            TraceEventMetadataCodec.decodePayload(message.metadataJson),
        sanitizeTraceEvent: (ChatStreamEvent) -> ChatStreamEvent? = { event -> event },
        buildSpecCardFallbackMarkdown: (SpecCardMetadata) -> String,
        formatToolMessage: (String) -> String = { content ->
            SpecCodingBundle.message("toolwindow.message.tool.entry", content)
        },
    ): ImprovedChatPanelRestoredMessageRenderDecision {
        val plan = when (message.role) {
            ConversationRole.USER -> resolveUserMessagePlan(
                message = message,
                fromSessionRestore = fromSessionRestore,
                activeExecutionLaunchRunIds = activeExecutionLaunchRunIds,
                executionMetadata = executionMetadata,
            )

            ConversationRole.ASSISTANT -> resolveAssistantMessagePlan(
                message = message,
                specCardMetadata = specCardMetadata,
                traceMetadata = traceMetadata,
                sanitizeTraceEvent = sanitizeTraceEvent,
                buildSpecCardFallbackMarkdown = buildSpecCardFallbackMarkdown,
            )

            ConversationRole.SYSTEM -> ImprovedChatPanelMessageRenderPlan.SystemMessage(message.content)
            ConversationRole.TOOL -> ImprovedChatPanelMessageRenderPlan.ToolMessage(
                formatToolMessage(message.content),
            )
        }

        val historyMessage = when (plan) {
            is ImprovedChatPanelMessageRenderPlan.UserPlain ->
                LlmMessage(LlmRole.USER, plan.rawContent)

            is ImprovedChatPanelMessageRenderPlan.UserExecutionLaunch ->
                LlmMessage(LlmRole.USER, plan.rawContent)

            is ImprovedChatPanelMessageRenderPlan.AssistantSpecCard ->
                LlmMessage(LlmRole.ASSISTANT, plan.cardMarkdown)

            is ImprovedChatPanelMessageRenderPlan.AssistantPanel ->
                LlmMessage(LlmRole.ASSISTANT, plan.content)

            is ImprovedChatPanelMessageRenderPlan.SystemMessage ->
                LlmMessage(LlmRole.SYSTEM, plan.content)

            is ImprovedChatPanelMessageRenderPlan.ToolMessage -> null
        }

        return ImprovedChatPanelRestoredMessageRenderDecision(
            plan = plan,
            historyMessage = historyMessage,
            marksTaskExecutionMessageRendered = executionMetadata.isTaskExecutionMessage(),
        )
    }

    private fun resolveUserMessagePlan(
        message: ConversationMessage,
        fromSessionRestore: Boolean,
        activeExecutionLaunchRunIds: Set<String>,
        executionMetadata: TaskExecutionSessionMetadataCodec.DecodedMetadata,
    ): ImprovedChatPanelMessageRenderPlan {
        val executionLaunchPayload = executionMetadata.resolveExecutionLaunchRestorePayload(message.content)
        val rawUserContent = executionMetadata.resolveExecutionLaunchRawPrompt() ?: message.content
        return if (executionLaunchPayload != null) {
            val compact = fromSessionRestore &&
                executionMetadata.runId?.trim()?.takeIf(String::isNotBlank) !in activeExecutionLaunchRunIds
            ImprovedChatPanelMessageRenderPlan.UserExecutionLaunch(
                visibleContent = message.content,
                rawContent = rawUserContent,
                payload = executionLaunchPayload,
                compact = compact,
            )
        } else {
            ImprovedChatPanelMessageRenderPlan.UserPlain(
                visibleContent = message.content,
                rawContent = rawUserContent,
            )
        }
    }

    private fun resolveAssistantMessagePlan(
        message: ConversationMessage,
        specCardMetadata: SpecCardMetadata?,
        traceMetadata: TraceEventMetadataCodec.DecodedMetadata,
        sanitizeTraceEvent: (ChatStreamEvent) -> ChatStreamEvent?,
        buildSpecCardFallbackMarkdown: (SpecCardMetadata) -> String,
    ): ImprovedChatPanelMessageRenderPlan {
        val restoredContent = if (specCardMetadata != null) {
            message.content.ifBlank {
                buildSpecCardFallbackMarkdown(specCardMetadata)
            }
        } else {
            message.content
        }
        val restoredTraceEvents = traceMetadata.events.mapNotNull(sanitizeTraceEvent)
        return if (specCardMetadata != null && restoredTraceEvents.isEmpty()) {
            ImprovedChatPanelMessageRenderPlan.AssistantSpecCard(
                cardMarkdown = restoredContent,
                metadata = specCardMetadata,
            )
        } else {
            ImprovedChatPanelMessageRenderPlan.AssistantPanel(
                content = restoredContent,
                traceEvents = restoredTraceEvents,
                startedAtMillis = traceMetadata.startedAtMillis,
                finishedAtMillis = traceMetadata.finishedAtMillis,
            )
        }
    }

    private fun TaskExecutionSessionMetadataCodec.DecodedMetadata.isTaskExecutionMessage(): Boolean {
        return !runId.isNullOrBlank() || !requestId.isNullOrBlank()
    }
}
