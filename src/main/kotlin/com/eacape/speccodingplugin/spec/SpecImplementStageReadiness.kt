package com.eacape.speccodingplugin.spec

internal data class ImplementStageReadiness(
    val taskSourceReady: Boolean,
    val taskCount: Int,
    val completedTaskCount: Int,
    val hasExecutionInFlight: Boolean,
    val allWorkSettled: Boolean,
    val relatedFilesConfirmed: Boolean,
) {
    val progressSatisfied: Boolean
        get() = hasExecutionInFlight || allWorkSettled

    val progressBlocked: Boolean
        get() = taskCount > 0 && !allWorkSettled

    val relatedFilesBlocked: Boolean
        get() = completedTaskCount > 0 && !relatedFilesConfirmed

    val readyForWorkflowCompletion: Boolean
        get() = taskSourceReady && allWorkSettled && relatedFilesConfirmed
}

internal fun evaluateImplementStageReadiness(
    tasksDocument: SpecDocument?,
    tasks: List<StructuredTask>,
): ImplementStageReadiness {
    val completedTasks = tasks.filter { it.status == TaskStatus.COMPLETED }
    return ImplementStageReadiness(
        taskSourceReady = tasksDocument != null && tasks.isNotEmpty(),
        taskCount = tasks.size,
        completedTaskCount = completedTasks.size,
        hasExecutionInFlight = tasks.any(StructuredTask::hasExecutionInFlight),
        allWorkSettled = tasks.isNotEmpty() && tasks.all(::isImplementationTaskSettled),
        relatedFilesConfirmed = completedTasks.isEmpty() || completedTasks.all { it.relatedFiles.isNotEmpty() },
    )
}

private fun isImplementationTaskSettled(task: StructuredTask): Boolean {
    return task.status == TaskStatus.COMPLETED || task.status == TaskStatus.CANCELLED
}
