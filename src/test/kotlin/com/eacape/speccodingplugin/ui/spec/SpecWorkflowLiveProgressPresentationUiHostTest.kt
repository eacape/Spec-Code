package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.ExecutionLivePhase
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class SpecWorkflowLiveProgressPresentationUiHostTest {

    @Test
    fun `apply should update task panels from latest structured tasks snapshot and refresh workspace presentation`() {
        val tasks = listOf(
            StructuredTask(
                id = "T-101",
                title = "Keep progress visible",
                status = TaskStatus.PENDING,
                priority = TaskPriority.P0,
            ),
        )
        val liveProgress = mapOf(
            "T-101" to TaskExecutionLiveProgress(
                workflowId = "wf-1",
                runId = "run-1",
                taskId = "T-101",
                phase = ExecutionLivePhase.STREAMING,
                startedAt = Instant.parse("2026-04-16T00:00:00Z"),
                lastUpdatedAt = Instant.parse("2026-04-16T00:00:05Z"),
                lastDetail = "Streaming response",
            ),
        )
        val taskPanelCalls = mutableListOf<UiCall>()
        val detailPanelCalls = mutableListOf<UiCall>()
        var workspaceRefreshCount = 0
        var currentTasks = emptyList<StructuredTask>()
        val host = SpecWorkflowLiveProgressPresentationUiHost(
            resolveStructuredTasks = { currentTasks },
            updateTasksUi = { resolvedTasks, resolvedProgress ->
                taskPanelCalls += UiCall(resolvedTasks, resolvedProgress)
            },
            updateDetailTasksUi = { resolvedTasks, resolvedProgress ->
                detailPanelCalls += UiCall(resolvedTasks, resolvedProgress)
            },
            refreshWorkspacePresentationUi = {
                workspaceRefreshCount += 1
            },
        )

        currentTasks = tasks
        host.apply(liveProgress)

        assertEquals(listOf(UiCall(tasks, liveProgress)), taskPanelCalls)
        assertEquals(listOf(UiCall(tasks, liveProgress)), detailPanelCalls)
        assertEquals(1, workspaceRefreshCount)
    }

    private data class UiCall(
        val tasks: List<StructuredTask>,
        val liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
    )
}
