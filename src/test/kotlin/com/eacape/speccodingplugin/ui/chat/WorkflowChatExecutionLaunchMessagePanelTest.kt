package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchFallbackReason
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLegacyCompactNotice
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchPresentation
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchRestorePayload
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionLaunchSurface
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionPresentationSection
import com.eacape.speccodingplugin.spec.WorkflowChatExecutionPresentationSectionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class WorkflowChatExecutionLaunchMessagePanelTest {

    @Test
    fun `presentation payload should render execution launch summary card`() {
        val inspectedPrompts = mutableListOf<String>()
        val panel = runOnEdtResult {
            WorkflowChatExecutionLaunchMessagePanel(
                payload = WorkflowChatExecutionLaunchRestorePayload.Presentation(
                    WorkflowChatExecutionLaunchPresentation(
                        workflowId = "wf-149",
                        taskId = "T-149",
                        taskTitle = "Render execution launch card in workflow chat",
                        runId = "run-149",
                        focusedStage = StageId.TASKS,
                        trigger = ExecutionTrigger.USER_EXECUTE,
                        launchSurface = WorkflowChatExecutionLaunchSurface.TASK_ROW,
                        taskStatusBeforeExecution = TaskStatus.PENDING,
                        taskPriority = TaskPriority.P1,
                        sections = listOf(
                            WorkflowChatExecutionPresentationSection(
                                kind = WorkflowChatExecutionPresentationSectionKind.ARTIFACT_SUMMARIES,
                                itemCount = 2,
                                previewItems = listOf(
                                    "requirements.md: execution requests should render as cards.",
                                    "design.md: restore should prefer card reconstruction.",
                                ),
                            ),
                            WorkflowChatExecutionPresentationSection(
                                kind = WorkflowChatExecutionPresentationSectionKind.CODE_CONTEXT,
                                itemCount = 1,
                                previewItems = listOf("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
                            ),
                        ),
                        supplementalInstruction = "Keep the summary compact.",
                        rawPromptDebugAvailable = true,
                    ),
                ),
                visibleContent = "## Execution Request",
                rawPromptContent = "Interaction mode: workflow\n## Execution Request\nExecute task T-149",
                inspectRawPrompt = { inspectedPrompts += it },
            )
        }

        val snapshot = panel.snapshotForTest()
        assertEquals("presentation", snapshot.getValue("kind"))
        assertEquals("wf-149", snapshot.getValue("workflowId"))
        assertEquals("T-149", snapshot.getValue("taskId"))
        assertEquals("run-149", snapshot.getValue("runId"))
        assertEquals(StageId.TASKS.name, snapshot.getValue("focusedStage"))
        assertEquals("true", snapshot.getValue("userNoteVisible"))
        assertEquals("false", snapshot.getValue("titleHasIcon"))
        assertEquals("false", snapshot.getValue("systemContextExpanded"))
        assertEquals("true", snapshot.getValue("debugEntryVisible"))
        assertEquals("false", snapshot.getValue("rawPromptVisible"))
        assertEquals("0", snapshot.getValue("rawPromptInspectInvocations"))

        val renderedText = collectDescendants(panel)
            .filterIsInstance<JTextArea>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(renderedText.contains("requirements.md"))
        assertTrue(renderedText.contains("Keep the summary compact."))
        assertTrue(renderedText.contains(SpecCodingBundle.message("chat.execution.launch.note.rawPromptHidden")))
        assertFalse(renderedText.contains("Interaction mode: workflow"))

        runOnEdtResult {
            panel.toggleSystemContextForTest()
            panel.toggleRawPromptForTest()
        }

        val expandedSnapshot = panel.snapshotForTest()
        assertEquals("true", expandedSnapshot.getValue("systemContextExpanded"))
        assertEquals("false", expandedSnapshot.getValue("rawPromptVisible"))
        assertEquals("1", expandedSnapshot.getValue("rawPromptInspectInvocations"))
        assertEquals(
            "Interaction mode: workflow\n## Execution Request\nExecute task T-149",
            inspectedPrompts.single(),
        )

        val expandedText = collectDescendants(panel)
            .filterIsInstance<JTextArea>()
            .joinToString("\n") { it.text.orEmpty() }
        assertFalse(expandedText.contains("Interaction mode: workflow"))
    }

    @Test
    fun `system context summary should normalize markdown table previews`() {
        val panel = runOnEdtResult {
            WorkflowChatExecutionLaunchMessagePanel(
                payload = WorkflowChatExecutionLaunchRestorePayload.Presentation(
                    WorkflowChatExecutionLaunchPresentation(
                        workflowId = "wf-table",
                        taskId = "T-007",
                        taskTitle = "AI Enhancer async retry module",
                        runId = "run-table",
                        focusedStage = StageId.TASKS,
                        trigger = ExecutionTrigger.USER_EXECUTE,
                        launchSurface = WorkflowChatExecutionLaunchSurface.TASK_ROW,
                        sections = listOf(
                            WorkflowChatExecutionPresentationSection(
                                kind = WorkflowChatExecutionPresentationSectionKind.ARTIFACT_SUMMARIES,
                                itemCount = 3,
                                previewItems = listOf(
                                    "requirements.md: | 字段 | 内容 | |----------------------------|--------------------------| | 版本 | v1.0.0.1 | 创建日期 | 2026-03-18 | 状态 | 草稿 | | 澄清结论 | No confirmed clarifications recorded. |",
                                ),
                            ),
                        ),
                    ),
                ),
                visibleContent = "## Execution Request",
            )
        }

        val renderedText = collectDescendants(panel)
            .filterIsInstance<JTextArea>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(renderedText.contains("requirements.md"))
        assertTrue(renderedText.contains("版本 v1.0.0.1"))
        assertTrue(renderedText.contains("澄清结论 No confirmed clarifications recorded."))
        assertFalse(renderedText.contains("|----------------------------|"))
        assertFalse(renderedText.contains("| 字段 | 内容 |"))
    }

    @Test
    fun `legacy payload should render compact fallback notice instead of raw prompt`() {
        val inspectedPrompts = mutableListOf<String>()
        val panel = runOnEdtResult {
            WorkflowChatExecutionLaunchMessagePanel(
                payload = WorkflowChatExecutionLaunchRestorePayload.LegacyCompact(
                    WorkflowChatExecutionLegacyCompactNotice(
                        workflowId = "wf-legacy",
                        taskId = "T-019",
                        taskTitle = "Legacy launch restore",
                        runId = "run-019",
                        focusedStage = StageId.TASKS,
                        trigger = ExecutionTrigger.USER_RETRY,
                        launchSurface = WorkflowChatExecutionLaunchSurface.UNKNOWN,
                        sectionKinds = setOf(
                            WorkflowChatExecutionPresentationSectionKind.ARTIFACT_SUMMARIES,
                            WorkflowChatExecutionPresentationSectionKind.CODE_CONTEXT,
                        ),
                        supplementalInstructionPresent = true,
                        fallbackReason = WorkflowChatExecutionLaunchFallbackReason.MISSING_PRESENTATION_METADATA,
                        rawPromptDebugAvailable = true,
                    ),
                ),
                visibleContent = "## Execution Request",
                rawPromptContent = "Interaction mode: workflow\n## Execution Request\nLegacy task execution",
                inspectRawPrompt = { inspectedPrompts += it },
            )
        }

        val snapshot = panel.snapshotForTest()
        assertEquals("legacy", snapshot.getValue("kind"))
        assertEquals("wf-legacy", snapshot.getValue("workflowId"))
        assertEquals("T-019", snapshot.getValue("taskId"))
        assertEquals("run-019", snapshot.getValue("runId"))
        assertEquals("MISSING_PRESENTATION_METADATA", snapshot.getValue("fallbackReason"))
        assertEquals("true", snapshot.getValue("userNoteVisible"))
        assertEquals("false", snapshot.getValue("titleHasIcon"))
        assertEquals("false", snapshot.getValue("systemContextExpanded"))
        assertEquals("true", snapshot.getValue("debugEntryVisible"))
        assertEquals("false", snapshot.getValue("rawPromptVisible"))
        assertEquals("0", snapshot.getValue("rawPromptInspectInvocations"))

        val renderedText = collectDescendants(panel)
            .filterIsInstance<JTextArea>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(renderedText.contains(SpecCodingBundle.message("chat.execution.launch.note.legacy")))
        assertTrue(renderedText.contains(SpecCodingBundle.message("chat.execution.launch.section.codeContext")))
        assertTrue(renderedText.contains(SpecCodingBundle.message("chat.execution.launch.section.artifactSummaries")))
        assertTrue(renderedText.contains(SpecCodingBundle.message("chat.execution.launch.note.legacy.userNote")))
        assertFalse(renderedText.contains("Interaction mode: workflow"))

        runOnEdtResult {
            panel.toggleRawPromptForTest()
        }
        assertEquals("false", panel.snapshotForTest().getValue("rawPromptVisible"))
        assertEquals("1", panel.snapshotForTest().getValue("rawPromptInspectInvocations"))
        assertEquals("Interaction mode: workflow\n## Execution Request\nLegacy task execution", inspectedPrompts.single())
    }

    private fun <T> runOnEdtResult(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) {
            return action()
        }
        var result: T? = null
        SwingUtilities.invokeAndWait {
            result = action()
        }
        return result ?: error("Expected EDT result")
    }

    private fun collectDescendants(component: Component): List<Component> {
        val result = mutableListOf<Component>()
        result += component
        val container = component as? Container ?: return result
        container.components.forEach { child ->
            result += collectDescendants(child)
        }
        return result
    }
}
