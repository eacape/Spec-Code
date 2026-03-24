package com.eacape.speccodingplugin.spec

internal data class TaskExecutionConstraint(
    val taskId: String,
    val unmetDependencyIds: List<String>,
) {
    val executable: Boolean
        get() = unmetDependencyIds.isEmpty()
}

internal data class TaskCancellationConstraint(
    val taskId: String,
    val blockingDependentTaskIds: List<String>,
) {
    val cancellable: Boolean
        get() = blockingDependentTaskIds.isEmpty()
}

internal object SpecTaskDependencyRules {
    fun executionConstraint(
        task: StructuredTask,
        tasks: Collection<StructuredTask>,
    ): TaskExecutionConstraint {
        val tasksById = tasks.associateBy(StructuredTask::id)
        val unmetDependencyIds = task.dependsOn
            .asSequence()
            .filter { dependencyId -> tasksById[dependencyId]?.status != TaskStatus.COMPLETED }
            .distinct()
            .sorted()
            .toList()
        return TaskExecutionConstraint(
            taskId = task.id,
            unmetDependencyIds = unmetDependencyIds,
        )
    }

    fun cancellationConstraint(
        task: StructuredTask,
        tasks: Collection<StructuredTask>,
    ): TaskCancellationConstraint {
        val blockingDependentTaskIds = tasks
            .asSequence()
            .filter { candidate ->
                candidate.id != task.id &&
                    candidate.dependsOn.contains(task.id) &&
                    candidate.status != TaskStatus.CANCELLED
            }
            .map(StructuredTask::id)
            .distinct()
            .sorted()
            .toList()
        return TaskCancellationConstraint(
            taskId = task.id,
            blockingDependentTaskIds = blockingDependentTaskIds,
        )
    }
}
