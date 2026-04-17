package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress

internal class SpecWorkflowLiveProgressPresentationUiHost(
    private val resolveStructuredTasks: () -> List<StructuredTask>,
    private val updateTasksUi: (List<StructuredTask>, Map<String, TaskExecutionLiveProgress>) -> Unit,
    private val updateDetailTasksUi: (List<StructuredTask>, Map<String, TaskExecutionLiveProgress>) -> Unit,
    private val refreshWorkspacePresentationUi: () -> Unit,
) {

    fun apply(updatedLiveProgress: Map<String, TaskExecutionLiveProgress>) {
        val tasks = resolveStructuredTasks()
        updateTasksUi(tasks, updatedLiveProgress)
        updateDetailTasksUi(tasks, updatedLiveProgress)
        refreshWorkspacePresentationUi()
    }
}
