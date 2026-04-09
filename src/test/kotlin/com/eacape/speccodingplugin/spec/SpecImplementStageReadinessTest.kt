package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecImplementStageReadinessTest {

    @Test
    fun `readiness should block completion when task source is missing`() {
        val readiness = evaluateImplementStageReadiness(
            tasksDocument = null,
            tasks = listOf(
                task(
                    id = "T-001",
                    status = TaskStatus.COMPLETED,
                    relatedFiles = listOf("src/main/kotlin/com/example/App.kt"),
                ),
            ),
        )

        assertFalse(readiness.taskSourceReady)
        assertTrue(readiness.allWorkSettled)
        assertFalse(readiness.progressBlocked)
        assertFalse(readiness.relatedFilesBlocked)
        assertFalse(readiness.readyForWorkflowCompletion)
    }

    @Test
    fun `readiness should block completion while implementation work is still in flight`() {
        val readiness = evaluateImplementStageReadiness(
            tasksDocument = tasksDocument(),
            tasks = listOf(
                task(
                    id = "T-001",
                    status = TaskStatus.IN_PROGRESS,
                    activeExecutionRun = TaskExecutionRun(
                        runId = "run-1",
                        taskId = "T-001",
                        status = TaskExecutionRunStatus.RUNNING,
                        trigger = ExecutionTrigger.USER_EXECUTE,
                        startedAt = "2026-04-09T00:00:00Z",
                    ),
                ),
            ),
        )

        assertTrue(readiness.taskSourceReady)
        assertTrue(readiness.hasExecutionInFlight)
        assertTrue(readiness.progressSatisfied)
        assertTrue(readiness.progressBlocked)
        assertFalse(readiness.readyForWorkflowCompletion)
    }

    @Test
    fun `readiness should block completion when completed tasks are missing related files`() {
        val readiness = evaluateImplementStageReadiness(
            tasksDocument = tasksDocument(),
            tasks = listOf(
                task(
                    id = "T-001",
                    status = TaskStatus.COMPLETED,
                    relatedFiles = emptyList(),
                ),
                task(
                    id = "T-002",
                    status = TaskStatus.CANCELLED,
                ),
            ),
        )

        assertTrue(readiness.taskSourceReady)
        assertTrue(readiness.allWorkSettled)
        assertFalse(readiness.progressBlocked)
        assertTrue(readiness.relatedFilesBlocked)
        assertFalse(readiness.readyForWorkflowCompletion)
    }

    @Test
    fun `readiness should allow completion when tasks are settled and related files are confirmed`() {
        val readiness = evaluateImplementStageReadiness(
            tasksDocument = tasksDocument(),
            tasks = listOf(
                task(
                    id = "T-001",
                    status = TaskStatus.COMPLETED,
                    relatedFiles = listOf("src/main/kotlin/com/example/App.kt"),
                ),
                task(
                    id = "T-002",
                    status = TaskStatus.CANCELLED,
                ),
            ),
        )

        assertTrue(readiness.taskSourceReady)
        assertTrue(readiness.allWorkSettled)
        assertFalse(readiness.progressBlocked)
        assertFalse(readiness.relatedFilesBlocked)
        assertTrue(readiness.readyForWorkflowCompletion)
    }

    private fun tasksDocument(): SpecDocument {
        return SpecDocument(
            id = "implement-doc",
            phase = SpecPhase.IMPLEMENT,
            content = "## Tasks\n",
            metadata = SpecMetadata(
                title = "Tasks",
                description = "Implement stage tasks",
            ),
        )
    }

    private fun task(
        id: String,
        status: TaskStatus,
        relatedFiles: List<String> = emptyList(),
        activeExecutionRun: TaskExecutionRun? = null,
    ): StructuredTask {
        return StructuredTask(
            id = id,
            title = id,
            status = status,
            priority = TaskPriority.P0,
            relatedFiles = relatedFiles,
            activeExecutionRun = activeExecutionRun,
        )
    }
}
