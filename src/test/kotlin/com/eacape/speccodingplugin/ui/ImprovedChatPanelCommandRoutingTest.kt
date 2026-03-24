package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImprovedChatPanelCommandRoutingTest {

    @Test
    fun shouldRouteToWorkflowCommandShouldKeepTaskKeywordsAsDiscussionWhenWorkflowExists() {
        listOf(
            "execute T-001",
            "retry current task",
            "complete current task",
            "执行 T-001",
            "重试当前任务",
            "完成当前任务",
        ).forEach { input ->
            assertFalse(
                ImprovedChatPanel.shouldRouteToWorkflowCommand(
                    normalizedInput = input,
                    hasActiveWorkflow = true,
                ),
                "Expected '$input' to stay on the discussion path",
            )
        }
    }

    @Test
    fun shouldRouteToWorkflowCommandShouldKeepBareWorkflowCommands() {
        listOf("status", "open wf-001", "next", "back", "generate", "complete", "help").forEach { input ->
            assertTrue(
                ImprovedChatPanel.shouldRouteToWorkflowCommand(
                    normalizedInput = input,
                    hasActiveWorkflow = true,
                ),
                "Expected '$input' to stay on the workflow command path",
            )
        }
    }

    @Test
    fun shouldRouteToWorkflowCommandShouldFallbackToWorkflowCommandWhenNoWorkflowIsActive() {
        assertTrue(
            ImprovedChatPanel.shouldRouteToWorkflowCommand(
                normalizedInput = "执行 T-001",
                hasActiveWorkflow = false,
            ),
        )
    }
}
