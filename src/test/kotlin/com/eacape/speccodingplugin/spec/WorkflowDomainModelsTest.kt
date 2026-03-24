package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkflowDomainModelsTest {

    @Test
    fun `full spec template should keep order and allow verify toggle`() {
        val definition = WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC)

        assertEquals(
            listOf(
                StageId.REQUIREMENTS,
                StageId.DESIGN,
                StageId.TASKS,
                StageId.IMPLEMENT,
                StageId.VERIFY,
                StageId.ARCHIVE,
            ),
            definition.stagePlan.map { it.id },
        )

        assertEquals(
            listOf(
                StageId.REQUIREMENTS,
                StageId.DESIGN,
                StageId.TASKS,
                StageId.IMPLEMENT,
                StageId.ARCHIVE,
            ),
            definition.activeStages(verifyEnabled = false),
        )
    }

    @Test
    fun `design review template should support optional implement and verify`() {
        val definition = WorkflowTemplates.definitionOf(WorkflowTemplate.DESIGN_REVIEW)

        assertEquals(
            listOf(StageId.DESIGN, StageId.TASKS, StageId.IMPLEMENT, StageId.ARCHIVE),
            definition.activeStages(verifyEnabled = false, implementEnabled = true),
        )
        assertEquals(
            listOf(StageId.DESIGN, StageId.TASKS, StageId.VERIFY, StageId.ARCHIVE),
            definition.activeStages(verifyEnabled = true, implementEnabled = false),
        )
    }

    @Test
    fun `stage plan should keep gate scope limited to active artifact stages`() {
        val plan = WorkflowTemplates.definitionOf(WorkflowTemplate.DIRECT_IMPLEMENT)
            .buildStagePlan(StageActivationOptions.of(verifyEnabled = true))

        assertEquals(
            listOf(StageId.IMPLEMENT, StageId.VERIFY, StageId.ARCHIVE),
            plan.activeStages,
        )
        assertEquals(listOf(StageId.VERIFY), plan.gateArtifactStages)
        assertFalse(plan.participatesInGate(StageId.TASKS))
        assertFalse(plan.participatesInGate(StageId.IMPLEMENT))
        assertTrue(plan.participatesInGate(StageId.VERIFY))
    }

    @Test
    fun `stage plan should initialize states from first active stage in order`() {
        val plan = WorkflowTemplates.definitionOf(WorkflowTemplate.DESIGN_REVIEW)
            .buildStagePlan(
                StageActivationOptions.of(
                    verifyEnabled = true,
                    implementEnabled = false,
                ),
            )

        val states = plan.initialStageStates("2026-03-09T00:00:00Z")

        assertEquals(StageId.DESIGN, plan.firstActiveStage)
        assertEquals(StageProgress.IN_PROGRESS, states.getValue(StageId.DESIGN).status)
        assertFalse(states.getValue(StageId.IMPLEMENT).active)
        assertTrue(states.getValue(StageId.VERIFY).active)
        assertEquals(StageProgress.NOT_STARTED, states.getValue(StageId.VERIFY).status)
        assertEquals(StageId.VERIFY, plan.nextActiveStage(StageId.TASKS))
        assertEquals(StageId.TASKS, plan.previousActiveStage(StageId.VERIFY))
    }

    @Test
    fun `stage plan should expose active stage slices for transitions`() {
        val plan = WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC)
            .buildStagePlan(StageActivationOptions.of(verifyEnabled = true))

        assertEquals(
            listOf(StageId.REQUIREMENTS, StageId.DESIGN, StageId.TASKS),
            plan.activeStagesThrough(StageId.TASKS),
        )
        assertEquals(
            listOf(StageId.DESIGN, StageId.TASKS, StageId.IMPLEMENT),
            plan.activeStagesBetween(StageId.DESIGN, StageId.IMPLEMENT),
        )
        assertEquals(
            listOf(StageId.REQUIREMENTS, StageId.DESIGN),
            plan.activeStagesBefore(StageId.TASKS),
        )
    }

    @Test
    fun `stage plan should resolve inactive current stage to nearest active stage`() {
        val plan = WorkflowTemplates.definitionOf(WorkflowTemplate.DIRECT_IMPLEMENT)
            .buildStagePlan(StageActivationOptions.of(verifyEnabled = false))

        assertEquals(StageId.IMPLEMENT, plan.resolveCurrentStage(StageId.REQUIREMENTS))
        assertEquals(StageId.IMPLEMENT, plan.resolveCurrentStage(StageId.DESIGN))
        assertEquals(StageId.IMPLEMENT, plan.resolveCurrentStage(StageId.IMPLEMENT))
        assertEquals(StageId.ARCHIVE, plan.resolveCurrentStage(StageId.ARCHIVE))
    }

    @Test
    fun `gate result should escalate to highest severity`() {
        val warningOnly = GateResult.fromViolations(
            listOf(
                Violation(
                    ruleId = "R-WARN",
                    severity = GateStatus.WARNING,
                    fileName = "tasks.md",
                    line = 3,
                    message = "warn",
                ),
            ),
        )
        assertEquals(GateStatus.WARNING, warningOnly.status)
        assertEquals(1, warningOnly.aggregation.warningCount)
        assertTrue(warningOnly.aggregation.requiresWarningConfirmation)
        assertEquals("R-WARN", warningOnly.warningConfirmation?.warnings?.single()?.ruleId)

        val withError = GateResult.fromViolations(
            listOf(
                Violation("R1", GateStatus.WARNING, "a.md", 1, "warn"),
                Violation("R2", GateStatus.ERROR, "b.md", 2, "error"),
            ),
        )
        assertEquals(GateStatus.ERROR, withError.status)
        assertEquals(1, withError.aggregation.errorCount)
        assertEquals(1, withError.aggregation.warningCount)
        assertFalse(withError.aggregation.canProceed)
        assertEquals(null, withError.warningConfirmation)

        val pass = GateResult.fromViolations(emptyList())
        assertEquals(GateStatus.PASS, pass.status)
        assertTrue(pass.aggregation.canProceed)
        assertEquals(0, pass.aggregation.totalViolationCount)
    }

    @Test
    fun `task status should follow state machine`() {
        assertTrue(TaskStatus.PENDING.canTransitionTo(TaskStatus.BLOCKED))
        assertTrue(TaskStatus.PENDING.canTransitionTo(TaskStatus.COMPLETED))
        assertTrue(TaskStatus.PENDING.canTransitionTo(TaskStatus.CANCELLED))
        assertTrue(TaskStatus.IN_PROGRESS.canTransitionTo(TaskStatus.PENDING))
        assertTrue(TaskStatus.IN_PROGRESS.canTransitionTo(TaskStatus.BLOCKED))
        assertTrue(TaskStatus.BLOCKED.canTransitionTo(TaskStatus.PENDING))

        assertFalse(TaskStatus.PENDING.canTransitionTo(TaskStatus.IN_PROGRESS))
        assertFalse(TaskStatus.COMPLETED.canTransitionTo(TaskStatus.IN_PROGRESS))
        assertFalse(TaskStatus.CANCELLED.canTransitionTo(TaskStatus.PENDING))
    }

    @Test
    fun `structured task should derive in progress from active execution run`() {
        val task = StructuredTask(
            id = "T-001",
            title = "Demo",
            status = TaskStatus.PENDING,
            priority = TaskPriority.P1,
            activeExecutionRun = TaskExecutionRun(
                runId = "run-1",
                taskId = "T-001",
                status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
                trigger = ExecutionTrigger.USER_EXECUTE,
                startedAt = "2026-03-13T12:00:00Z",
            ),
        )

        assertEquals(TaskStatus.IN_PROGRESS, task.displayStatus)
        assertTrue(task.hasExecutionInFlight)
        assertTrue(task.awaitingCompletionConfirmation)
    }

    @Test
    fun `task execution run status should follow state machine`() {
        assertTrue(TaskExecutionRunStatus.QUEUED.canTransitionTo(TaskExecutionRunStatus.RUNNING))
        assertTrue(TaskExecutionRunStatus.RUNNING.canTransitionTo(TaskExecutionRunStatus.WAITING_CONFIRMATION))
        assertTrue(TaskExecutionRunStatus.WAITING_CONFIRMATION.canTransitionTo(TaskExecutionRunStatus.SUCCEEDED))
        assertTrue(TaskExecutionRunStatus.SUCCEEDED.isTerminal())
        assertFalse(TaskExecutionRunStatus.QUEUED.canTransitionTo(TaskExecutionRunStatus.SUCCEEDED))
        assertFalse(TaskExecutionRunStatus.CANCELLED.canTransitionTo(TaskExecutionRunStatus.RUNNING))
    }

    @Test
    fun `template definition should reject duplicate stages`() {
        assertThrows(IllegalArgumentException::class.java) {
            TemplateDefinition(
                template = WorkflowTemplate.QUICK_TASK,
                stagePlan = listOf(
                    StagePlanItem(StageId.TASKS),
                    StagePlanItem(StageId.TASKS),
                    StagePlanItem(StageId.ARCHIVE),
                ),
            )
        }
    }
}
