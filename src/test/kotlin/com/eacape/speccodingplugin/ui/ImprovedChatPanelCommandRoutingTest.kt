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

    @Test
    fun `build visible input should append context markers after prompt`() {
        val rendered = ImprovedChatPanelComposerSubmissionCoordinator.buildVisibleInput(
            rawInput = "这个是什么文档",
            imagePaths = emptyList(),
            contextLabels = listOf("README.md", "src/main/"),
        )

        assertEquals(
            """
            这个是什么文档
            ${SpecCodingBundle.message("toolwindow.context.visible.entry", "README.md")}
            ${SpecCodingBundle.message("toolwindow.context.visible.entry", "src/main/")}
            """.trimIndent(),
            rendered,
        )
    }

    @Test
    fun `append context labels to prompt should add reference block for model grounding`() {
        val rendered = ImprovedChatPanelComposerSubmissionCoordinator.appendContextLabelsToPrompt(
            prompt = "这个是什么文档",
            contextLabels = listOf("spec-design-review.md"),
        )

        assertTrue(rendered.startsWith("这个是什么文档"), rendered)
        assertTrue(
            rendered.contains("The user's deictic reference"),
            rendered,
        )
        assertTrue(
            rendered.contains("Identify what the referenced document/file is in the first sentence."),
            rendered,
        )
        assertTrue(
            rendered.contains(SpecCodingBundle.message("toolwindow.context.prompt.header")),
            rendered,
        )
        assertTrue(rendered.contains("- spec-design-review.md"), rendered)
    }

    @Test
    fun `append context labels to prompt should use generic grounding rules for non identity questions`() {
        val rendered = ImprovedChatPanelComposerSubmissionCoordinator.appendContextLabelsToPrompt(
            prompt = "请根据这些文件给我一个修改建议",
            contextLabels = listOf("README.md", "src/main/"),
        )

        assertTrue(rendered.startsWith("请根据这些文件给我一个修改建议"), rendered)
        assertTrue(rendered.contains("The referenced context items below are the subject and evidence for this request."), rendered)
        assertTrue(rendered.contains("- README.md"), rendered)
        assertTrue(rendered.contains("- src/main/"), rendered)
    }
}
