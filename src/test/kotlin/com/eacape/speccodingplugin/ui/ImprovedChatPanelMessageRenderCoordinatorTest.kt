package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.llm.LlmRole
import com.eacape.speccodingplugin.session.ConversationMessage
import com.eacape.speccodingplugin.session.ConversationRole
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.TaskExecutionRun
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.eacape.speccodingplugin.spec.TaskExecutionSessionMetadataCodec
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchDebugPayload
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchPresentation
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchSurface
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import com.eacape.speccodingplugin.ui.chat.SpecCardMetadata
import com.eacape.speccodingplugin.ui.chat.SpecCardMetadataCodec
import com.eacape.speccodingplugin.ui.chat.TraceEventMetadataCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImprovedChatPanelMessageRenderCoordinatorTest {

    @Test
    fun `session restore should keep active execution launch card`() {
        val rawPrompt = "Interaction mode: workflow\n## Execution Request\nExecute task T-007"
        val message = conversationMessage(
            role = ConversationRole.USER,
            content = "## Execution Request\n- Task: T-007",
            metadataJson = executionMessageMetadata(rawPrompt = rawPrompt),
        )

        val decision = ImprovedChatPanelMessageRenderCoordinator.planRestoredMessage(
            message = message,
            fromSessionRestore = true,
            activeExecutionLaunchRunIds = setOf("run-007"),
            buildSpecCardFallbackMarkdown = { error("Spec card fallback should not be used") },
        )

        assertTrue(decision.plan is ImprovedChatPanelMessageRenderPlan.UserExecutionLaunch)
        val plan = decision.plan as ImprovedChatPanelMessageRenderPlan.UserExecutionLaunch
        assertEquals(message.content, plan.visibleContent)
        assertEquals(rawPrompt, plan.rawContent)
        assertEquals(LlmRole.USER, decision.historyMessage?.role)
        assertEquals(rawPrompt, decision.historyMessage?.content)
        assertTrue(decision.marksTaskExecutionMessageRendered)
    }

    @Test
    fun `session restore should degrade inactive execution launch card into plain user message`() {
        val rawPrompt = "Interaction mode: workflow\n## Execution Request\nExecute task T-007"
        val message = conversationMessage(
            role = ConversationRole.USER,
            content = "## Execution Request\n- Task: T-007",
            metadataJson = executionMessageMetadata(rawPrompt = rawPrompt),
        )

        val decision = ImprovedChatPanelMessageRenderCoordinator.planRestoredMessage(
            message = message,
            fromSessionRestore = true,
            activeExecutionLaunchRunIds = emptySet(),
            buildSpecCardFallbackMarkdown = { error("Spec card fallback should not be used") },
        )

        assertTrue(decision.plan is ImprovedChatPanelMessageRenderPlan.UserPlain)
        val plan = decision.plan as ImprovedChatPanelMessageRenderPlan.UserPlain
        assertEquals(message.content, plan.visibleContent)
        assertEquals(rawPrompt, plan.rawContent)
        assertEquals(rawPrompt, decision.historyMessage?.content)
        assertTrue(decision.marksTaskExecutionMessageRendered)
    }

    @Test
    fun `assistant restore should prefer spec card fallback markdown when trace is empty`() {
        val metadata = specCardMetadata()
        val message = conversationMessage(
            role = ConversationRole.ASSISTANT,
            content = "",
            metadataJson = SpecCardMetadataCodec.encode(metadata),
        )

        val decision = ImprovedChatPanelMessageRenderCoordinator.planRestoredMessage(
            message = message,
            buildSpecCardFallbackMarkdown = { fallbackMetadata ->
                "fallback:${fallbackMetadata.workflowId}:${fallbackMetadata.phase.name}"
            },
        )

        assertTrue(decision.plan is ImprovedChatPanelMessageRenderPlan.AssistantSpecCard)
        val plan = decision.plan as ImprovedChatPanelMessageRenderPlan.AssistantSpecCard
        assertEquals("fallback:wf-restore:IMPLEMENT", plan.cardMarkdown)
        assertEquals(metadata, plan.metadata)
        assertEquals(LlmRole.ASSISTANT, decision.historyMessage?.role)
        assertEquals("fallback:wf-restore:IMPLEMENT", decision.historyMessage?.content)
        assertFalse(decision.marksTaskExecutionMessageRendered)
    }

    @Test
    fun `assistant restore should keep chat panel when trace events exist`() {
        val metadata = specCardMetadata()
        val event = ChatStreamEvent(
            kind = ChatTraceKind.TASK,
            detail = "Streaming implementation plan",
            status = ChatTraceStatus.RUNNING,
        )
        val traceMetadata = TraceEventMetadataCodec.DecodedMetadata(
            events = listOf(event),
            startedAtMillis = 1200L,
            finishedAtMillis = 3400L,
        )
        val message = conversationMessage(
            role = ConversationRole.ASSISTANT,
            content = "",
            metadataJson = SpecCardMetadataCodec.encode(metadata),
        )

        val decision = ImprovedChatPanelMessageRenderCoordinator.planRestoredMessage(
            message = message,
            specCardMetadata = metadata,
            traceMetadata = traceMetadata,
            buildSpecCardFallbackMarkdown = { "fallback-card" },
        )

        assertTrue(decision.plan is ImprovedChatPanelMessageRenderPlan.AssistantPanel)
        val plan = decision.plan as ImprovedChatPanelMessageRenderPlan.AssistantPanel
        assertEquals("fallback-card", plan.content)
        assertEquals(listOf(event), plan.traceEvents)
        assertEquals(1200L, plan.startedAtMillis)
        assertEquals(3400L, plan.finishedAtMillis)
        assertEquals("fallback-card", decision.historyMessage?.content)
    }

    @Test
    fun `tool restore should format tool entry without conversation history`() {
        val message = conversationMessage(
            role = ConversationRole.TOOL,
            content = "git status",
        )

        val decision = ImprovedChatPanelMessageRenderCoordinator.planRestoredMessage(
            message = message,
            buildSpecCardFallbackMarkdown = { error("Spec card fallback should not be used") },
        )

        assertTrue(decision.plan is ImprovedChatPanelMessageRenderPlan.ToolMessage)
        val plan = decision.plan as ImprovedChatPanelMessageRenderPlan.ToolMessage
        assertTrue(plan.content.contains("git status"))
        assertNull(decision.historyMessage)
    }

    private fun conversationMessage(
        role: ConversationRole,
        content: String,
        metadataJson: String? = null,
    ): ConversationMessage {
        return ConversationMessage(
            id = "msg-1",
            sessionId = "session-1",
            role = role,
            content = content,
            createdAt = 0L,
            metadataJson = metadataJson,
        )
    }

    private fun executionMessageMetadata(rawPrompt: String): String {
        return TaskExecutionSessionMetadataCodec.encode(
            run = TaskExecutionRun(
                runId = "run-007",
                taskId = "T-007",
                status = TaskExecutionRunStatus.RUNNING,
                trigger = ExecutionTrigger.USER_EXECUTE,
                startedAt = "2026-03-31T00:00:00Z",
            ),
            workflowId = "wf-restore",
            requestId = "request-007",
            providerId = "mock-provider",
            modelId = "mock-model",
            previousRunId = null,
            launchPresentation = WorkflowChatExecutionLaunchPresentation(
                workflowId = "wf-restore",
                taskId = "T-007",
                taskTitle = "Restore execution launch card",
                runId = "run-007",
                focusedStage = StageId.TASKS,
                trigger = ExecutionTrigger.USER_EXECUTE,
                launchSurface = WorkflowChatExecutionLaunchSurface.WORKFLOW_CHAT,
            ),
            launchDebugPayload = WorkflowChatExecutionLaunchDebugPayload(rawPrompt = rawPrompt),
        )
    }

    private fun specCardMetadata(): SpecCardMetadata {
        return SpecCardMetadata(
            workflowId = "wf-restore",
            phase = SpecPhase.IMPLEMENT,
            status = WorkflowStatus.IN_PROGRESS,
            title = "Restore workflow",
            revision = 42L,
            sourceCommand = "/workflow implement",
        )
    }
}
