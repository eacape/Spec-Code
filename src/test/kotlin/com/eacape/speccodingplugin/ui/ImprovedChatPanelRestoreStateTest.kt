package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.spec.ExecutionLivePhase
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.window.WindowRuntimeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ImprovedChatPanelRestoreStateTest {

    @Test
    fun `startup should fall back to compact composer height for suspiciously low stored proportion`() {
        val snapshot = WindowRuntimeState(
            selectedTabTitle = "Chat",
            activeSessionId = null,
            operationMode = null,
            chatInteractionMode = "workflow",
            chatSpecSidebarVisible = false,
            chatSpecSidebarDividerLocation = 0,
            chatComposerDividerProportion = 0.5277778f,
            updatedAt = 0L,
        )

        assertEquals(
            0.80f,
            ImprovedChatPanel.resolveInitialComposerDividerProportion(snapshot),
        )
    }

    @Test
    fun `startup should keep a valid user sized composer proportion`() {
        val snapshot = WindowRuntimeState(
            selectedTabTitle = "Chat",
            activeSessionId = null,
            operationMode = null,
            chatInteractionMode = "workflow",
            chatSpecSidebarVisible = false,
            chatSpecSidebarDividerLocation = 0,
            chatComposerDividerProportion = 0.64f,
            updatedAt = 0L,
        )

        assertEquals(
            0.64f,
            ImprovedChatPanel.resolveInitialComposerDividerProportion(snapshot),
        )
    }

    @Test
    fun `should replace pending task execution progress only when update is newer`() {
        val current = liveProgress(
            runId = "run-1",
            taskId = "T-001",
            updatedAt = "2026-03-19T10:00:00Z",
        )

        assertFalse(
            ImprovedChatPanel.shouldReplacePendingTaskExecutionLiveProgress(
                current = current,
                incoming = liveProgress(
                    runId = "run-1",
                    taskId = "T-001",
                    updatedAt = "2026-03-19T09:59:59Z",
                ),
            ),
        )
        assertTrue(
            ImprovedChatPanel.shouldReplacePendingTaskExecutionLiveProgress(
                current = current,
                incoming = liveProgress(
                    runId = "run-1",
                    taskId = "T-001",
                    updatedAt = "2026-03-19T10:00:01Z",
                ),
            ),
        )
    }

    @Test
    fun `ordered task execution live progress batch should sort by time then run id`() {
        val ordered = ImprovedChatPanel.orderedTaskExecutionLiveProgressBatch(
            listOf(
                liveProgress(
                    runId = "run-b",
                    taskId = "T-002",
                    updatedAt = "2026-03-19T10:00:02Z",
                ),
                liveProgress(
                    runId = "run-a",
                    taskId = "T-001",
                    updatedAt = "2026-03-19T10:00:00Z",
                ),
                liveProgress(
                    runId = "run-c",
                    taskId = "T-003",
                    updatedAt = "2026-03-19T10:00:02Z",
                ),
            ),
        )

        assertEquals(listOf("run-a", "run-b", "run-c"), ordered.map(TaskExecutionLiveProgress::runId))
    }

    private fun liveProgress(
        runId: String,
        taskId: String,
        updatedAt: String,
    ): TaskExecutionLiveProgress {
        val instant = Instant.parse(updatedAt)
        return TaskExecutionLiveProgress(
            workflowId = "wf-test",
            runId = runId,
            taskId = taskId,
            phase = ExecutionLivePhase.STREAMING,
            startedAt = instant.minusSeconds(30),
            lastUpdatedAt = instant,
        )
    }
}
