package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.ExecutionLivePhase
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.TaskExecutionRun
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SpecWorkflowLiveProgressRefreshCoordinatorTest {

    @Test
    fun `listener should request coalesced refresh only for selected workflow`() {
        val harness = Harness()

        harness.coordinator.listener.onLiveProgressUpdated(progress("wf-2", "T-002"))
        harness.coordinator.listener.onLiveProgressUpdated(progress("wf-1", "T-001"))

        assertEquals(1, harness.scheduler.coalescedRequests)
    }

    @Test
    fun `flushPendingRefresh should reload live progress again after in flight refresh completes`() {
        val harness = Harness()
        val firstRefresh = mapOf("T-001" to progress("wf-1", "T-001", detail = "first"))
        val secondRefresh = mapOf("T-001" to progress("wf-1", "T-001", detail = "second"))
        harness.loadedSnapshots += firstRefresh
        harness.loadedSnapshots += secondRefresh

        harness.coordinator.flushPendingRefresh(force = true)
        harness.coordinator.flushPendingRefresh(force = true)

        assertEquals(1, harness.backgroundLoads.size)

        harness.runNextBackgroundLoad()

        assertEquals(listOf(firstRefresh), harness.appliedSnapshots)
        assertEquals(1, harness.backgroundLoads.size)

        harness.runNextBackgroundLoad()

        assertEquals(listOf(firstRefresh, secondRefresh), harness.appliedSnapshots)
        assertEquals(listOf("wf-1", "wf-1"), harness.loadRequests)
    }

    @Test
    fun `updateRefreshTracking should start polling when execution is active and stop refresh when activity clears`() {
        val harness = Harness()

        harness.coordinator.updateRefreshTracking(
            tasks = listOf(task("T-001", inFlight = true)),
            liveProgressByTaskId = emptyMap(),
        )
        harness.coordinator.listener.onLiveProgressUpdated(progress("wf-1", "T-001"))

        assertEquals(1, harness.scheduler.startPollingCalls)
        assertEquals(1, harness.scheduler.coalescedRequests)

        harness.coordinator.updateRefreshTracking(
            tasks = emptyList(),
            liveProgressByTaskId = emptyMap(),
        )
        harness.scheduler.triggerFlush(force = false)

        assertEquals(1, harness.scheduler.stopAllCalls)
        assertTrue(harness.backgroundLoads.isEmpty())
    }

    @Test
    fun `refresh completion should ignore stale workflow selection`() {
        val harness = Harness()
        harness.loadedSnapshots += mapOf("T-001" to progress("wf-1", "T-001"))

        harness.coordinator.flushPendingRefresh(force = true)
        harness.selectedWorkflowId = "wf-2"
        harness.currentWorkflowId = "wf-2"
        harness.runNextBackgroundLoad()

        assertTrue(harness.appliedSnapshots.isEmpty())
        assertEquals(listOf("wf-1"), harness.loadRequests)
    }

    private class Harness {
        var selectedWorkflowId: String? = "wf-1"
        var currentWorkflowId: String? = "wf-1"
        var disposed = false
        val scheduler = FakeScheduler()
        val backgroundLoads = mutableListOf<() -> Unit>()
        val loadedSnapshots = ArrayDeque<Map<String, TaskExecutionLiveProgress>>()
        val loadRequests = mutableListOf<String>()
        val appliedSnapshots = mutableListOf<Map<String, TaskExecutionLiveProgress>>()

        val coordinator = SpecWorkflowLiveProgressRefreshCoordinator(
            invokeLaterOnUi = { action -> action() },
            launchLoad = { action ->
                backgroundLoads += action
            },
            isDisposed = { disposed },
            resolveSelectedWorkflowId = { selectedWorkflowId },
            resolveCurrentWorkflowId = { currentWorkflowId },
            isWorkflowCurrentAndSelected = { workflowId ->
                selectedWorkflowId == workflowId && currentWorkflowId == workflowId
            },
            loadLiveProgressByTaskId = { workflowId ->
                loadRequests += workflowId
                loadedSnapshots.removeFirstOrNull().orEmpty()
            },
            applyLiveProgressPresentation = { snapshot ->
                appliedSnapshots += snapshot
            },
            schedulerFactory = SpecWorkflowLiveProgressRefreshSchedulerFactory { onFlush ->
                scheduler.onFlush = onFlush
                scheduler
            },
        )

        fun runNextBackgroundLoad() {
            val action = backgroundLoads.removeFirst()
            action()
        }
    }

    private class FakeScheduler : SpecWorkflowLiveProgressRefreshScheduler {
        override var isPolling: Boolean = false
        var coalescedRequests = 0
        var startPollingCalls = 0
        var stopAllCalls = 0
        lateinit var onFlush: (Boolean) -> Unit

        override fun requestCoalescedFlush() {
            coalescedRequests += 1
        }

        override fun startPolling() {
            startPollingCalls += 1
            isPolling = true
        }

        override fun stopAll() {
            stopAllCalls += 1
            isPolling = false
        }

        fun triggerFlush(force: Boolean) {
            onFlush(force)
        }
    }

    private companion object {
        fun progress(
            workflowId: String,
            taskId: String,
            detail: String = "streaming",
        ): TaskExecutionLiveProgress {
            return TaskExecutionLiveProgress(
                workflowId = workflowId,
                runId = "run-$taskId",
                taskId = taskId,
                phase = ExecutionLivePhase.STREAMING,
                startedAt = Instant.parse("2026-04-16T00:00:00Z"),
                lastUpdatedAt = Instant.parse("2026-04-16T00:00:05Z"),
                lastDetail = detail,
            )
        }

        fun task(taskId: String, inFlight: Boolean): StructuredTask {
            return StructuredTask(
                id = taskId,
                title = "Task $taskId",
                status = TaskStatus.PENDING,
                priority = TaskPriority.P1,
                activeExecutionRun = if (inFlight) {
                    TaskExecutionRun(
                        runId = "run-$taskId",
                        taskId = taskId,
                        status = TaskExecutionRunStatus.RUNNING,
                        trigger = com.eacape.speccodingplugin.spec.ExecutionTrigger.USER_EXECUTE,
                        startedAt = "2026-04-16T00:00:00Z",
                    )
                } else {
                    null
                },
            )
        }
    }
}
