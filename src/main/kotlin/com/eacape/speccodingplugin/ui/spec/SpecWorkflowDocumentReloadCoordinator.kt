package com.eacape.speccodingplugin.ui.spec

internal fun interface SpecWorkflowDocumentReloadHandle {
    fun cancel()
}

internal class SpecWorkflowDocumentReloadCoordinator(
    private val debounceMillis: Long,
    private val scheduleDebounced: (Long, () -> Unit) -> SpecWorkflowDocumentReloadHandle,
) {
    private var pendingReload: SpecWorkflowDocumentReloadHandle? = null
    private var pendingGeneration: Long = 0

    fun schedule(
        workflowId: String,
        shouldReload: (String) -> Boolean,
        reload: () -> Unit,
    ) {
        val generation = nextGeneration()
        pendingReload?.cancel()
        pendingReload = scheduleDebounced(debounceMillis) {
            if (generation != pendingGeneration) {
                return@scheduleDebounced
            }
            pendingReload = null
            if (shouldReload(workflowId)) {
                reload()
            }
        }
    }

    fun cancelPending() {
        nextGeneration()
        pendingReload?.cancel()
        pendingReload = null
    }

    private fun nextGeneration(): Long {
        pendingGeneration += 1
        return pendingGeneration
    }
}
