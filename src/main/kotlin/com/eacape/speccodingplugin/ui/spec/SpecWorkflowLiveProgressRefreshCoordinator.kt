package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgressListener
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer

internal fun interface SpecWorkflowLiveProgressRefreshSchedulerFactory {
    fun create(onFlush: (Boolean) -> Unit): SpecWorkflowLiveProgressRefreshScheduler
}

internal interface SpecWorkflowLiveProgressRefreshScheduler {
    val isPolling: Boolean

    fun requestCoalescedFlush()

    fun startPolling()

    fun stopAll()
}

internal class SpecWorkflowLiveProgressRefreshCoordinator(
    private val invokeLaterOnUi: ((() -> Unit) -> Unit),
    private val launchLoad: (() -> Unit) -> Unit,
    private val isDisposed: () -> Boolean,
    private val resolveSelectedWorkflowId: () -> String?,
    private val resolveCurrentWorkflowId: () -> String?,
    private val isWorkflowCurrentAndSelected: (String) -> Boolean,
    private val loadLiveProgressByTaskId: (String) -> Map<String, TaskExecutionLiveProgress>,
    private val applyLiveProgressPresentation: (Map<String, TaskExecutionLiveProgress>) -> Unit,
    schedulerFactory: SpecWorkflowLiveProgressRefreshSchedulerFactory = SpecWorkflowLiveProgressRefreshSchedulerFactory { onFlush ->
        SpecWorkflowSwingLiveProgressRefreshScheduler(onFlush)
    },
) {
    private val refreshDispatchQueued = AtomicBoolean(false)
    private val refreshLoadInFlight = AtomicBoolean(false)
    private val scheduler = schedulerFactory.create(::flushPendingRefresh)

    @Volatile
    private var refreshPending = false

    val listener = TaskExecutionLiveProgressListener { progress ->
        if (progress.workflowId == resolveSelectedWorkflowId()) {
            requestRefresh()
        }
    }

    fun updateRefreshTracking(
        tasks: List<StructuredTask>,
        liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
    ) {
        val hasLiveProgress = liveProgressByTaskId.isNotEmpty() || tasks.any(StructuredTask::hasExecutionInFlight)
        if (hasLiveProgress) {
            if (!scheduler.isPolling) {
                scheduler.startPolling()
            }
        } else {
            stopRefresh()
        }
    }

    fun stopRefresh() {
        refreshPending = false
        refreshDispatchQueued.set(false)
        scheduler.stopAll()
    }

    private fun requestRefresh() {
        refreshPending = true
        if (!refreshDispatchQueued.compareAndSet(false, true)) {
            return
        }
        invokeLaterOnUi {
            refreshDispatchQueued.set(false)
            if (isDisposed() || !refreshPending) {
                return@invokeLaterOnUi
            }
            scheduler.requestCoalescedFlush()
        }
    }

    internal fun flushPendingRefresh(force: Boolean = false) {
        if (!force && !refreshPending) {
            return
        }
        refreshPending = false
        refreshCurrentLiveProgressAsync()
    }

    private fun refreshCurrentLiveProgressAsync() {
        val workflowId = resolveSelectedWorkflowId() ?: return
        if (resolveCurrentWorkflowId() != workflowId) {
            return
        }
        if (!refreshLoadInFlight.compareAndSet(false, true)) {
            refreshPending = true
            return
        }
        launchLoad {
            val updatedLiveProgress = loadLiveProgressByTaskId(workflowId)
            invokeLaterOnUi {
                refreshLoadInFlight.set(false)
                if (!isDisposed() && isWorkflowCurrentAndSelected(workflowId)) {
                    applyLiveProgressPresentation(updatedLiveProgress)
                }
                if (refreshPending) {
                    refreshCurrentLiveProgressAsync()
                }
            }
        }
    }
}

internal class SpecWorkflowSwingLiveProgressRefreshScheduler(
    private val onFlush: (Boolean) -> Unit,
) : SpecWorkflowLiveProgressRefreshScheduler {
    private val eventCoalesceTimer = Timer(LIVE_PROGRESS_EVENT_COALESCE_MILLIS) {
        onFlush(false)
    }.apply {
        isRepeats = false
    }
    private val refreshTimer = Timer(LIVE_PROGRESS_REFRESH_MILLIS) {
        onFlush(true)
    }.apply {
        isRepeats = true
    }

    override val isPolling: Boolean
        get() = refreshTimer.isRunning

    override fun requestCoalescedFlush() {
        if (eventCoalesceTimer.isRunning) {
            eventCoalesceTimer.restart()
        } else {
            eventCoalesceTimer.start()
        }
    }

    override fun startPolling() {
        if (!refreshTimer.isRunning) {
            refreshTimer.start()
        }
    }

    override fun stopAll() {
        eventCoalesceTimer.stop()
        refreshTimer.stop()
    }

    private companion object {
        private const val LIVE_PROGRESS_EVENT_COALESCE_MILLIS = 180
        private const val LIVE_PROGRESS_REFRESH_MILLIS = 1_000
    }
}
