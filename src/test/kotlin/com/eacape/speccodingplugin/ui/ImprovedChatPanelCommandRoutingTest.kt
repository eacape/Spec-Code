package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import org.junit.jupiter.api.Assertions.assertEquals
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
                ImprovedChatPanelComposerSubmissionCoordinator.shouldRouteToWorkflowCommand(
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
                ImprovedChatPanelComposerSubmissionCoordinator.shouldRouteToWorkflowCommand(
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
            ImprovedChatPanelComposerSubmissionCoordinator.shouldRouteToWorkflowCommand(
                normalizedInput = "执行 T-001",
                hasActiveWorkflow = false,
            ),
        )
    }

    @Test
    fun `resolve should route slash command and drop image attachments at command boundary`() {
        val plan = ImprovedChatPanelComposerSubmissionCoordinator.resolve(
            rawInput = "/compact latest response",
            visibleRawInput = "/compact latest response",
            selectedImagePaths = listOf("C:/tmp/context-image.png"),
            specMode = false,
            hasActiveWorkflow = false,
        )

        assertEquals(ImprovedChatPanelComposerSubmissionKind.SLASH_COMMAND, plan.kind)
        assertEquals("/compact latest response", plan.command)
        assertTrue(plan.clearImageAttachments)
        assertEquals(
            SpecCodingBundle.message("toolwindow.image.attach.ignored.command"),
            plan.statusMessage,
        )
    }

    @Test
    fun `resolve should route bare workflow command through workflow boundary and clear images`() {
        val plan = ImprovedChatPanelComposerSubmissionCoordinator.resolve(
            rawInput = "status",
            visibleRawInput = "status",
            selectedImagePaths = listOf("C:/tmp/context-image.png"),
            specMode = true,
            hasActiveWorkflow = true,
        )

        assertEquals(ImprovedChatPanelComposerSubmissionKind.WORKFLOW_COMMAND, plan.kind)
        assertEquals("/workflow status", plan.command)
        assertEquals("status", plan.workflowCommandSessionTitleSeed)
        assertTrue(plan.clearImageAttachments)
        assertEquals(
            SpecCodingBundle.message("toolwindow.workflow.attachment.boundary"),
            plan.statusMessage,
        )
    }

    @Test
    fun `resolve should keep bound task discussion on chat path`() {
        val plan = ImprovedChatPanelComposerSubmissionCoordinator.resolve(
            rawInput = "execute T-001",
            visibleRawInput = "execute T-001",
            selectedImagePaths = listOf("C:/tmp/context-image.png"),
            specMode = true,
            hasActiveWorkflow = true,
        )

        assertEquals(ImprovedChatPanelComposerSubmissionKind.CHAT_MESSAGE, plan.kind)
        assertFalse(plan.clearImageAttachments)
        assertEquals(null, plan.command)
    }

    @Test
    fun `append image paths to prompt should add context block and default prompt`() {
        val rendered = ImprovedChatPanelComposerSubmissionCoordinator.appendImagePathsToPrompt(
            prompt = "",
            imagePaths = listOf("C:/tmp/one.png", "C:/tmp/two.png"),
        )

        assertTrue(rendered.startsWith(SpecCodingBundle.message("toolwindow.image.default.prompt")))
        assertTrue(rendered.contains(SpecCodingBundle.message("toolwindow.image.context.header")))
        assertTrue(rendered.contains("- C:/tmp/one.png"))
        assertTrue(rendered.contains("- C:/tmp/two.png"))
    }

    @Test
    fun `build visible input should summarize image attachments when prompt is blank`() {
        val rendered = ImprovedChatPanelComposerSubmissionCoordinator.buildVisibleInput(
            rawInput = "",
            imagePaths = listOf("C:/tmp/one.png", "C:/tmp/two.png"),
        )

        assertEquals(
            SpecCodingBundle.message("toolwindow.image.visible.entry", "image#1, image#2"),
            rendered,
        )
    }
}
