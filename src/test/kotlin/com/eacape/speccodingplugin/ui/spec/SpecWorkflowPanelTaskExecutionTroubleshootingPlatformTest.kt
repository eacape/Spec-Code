package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecArtifactService
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecStorage
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class SpecWorkflowPanelTaskExecutionTroubleshootingPlatformTest : BasePlatformTestCase() {

    fun `test task execution precheck failure should surface runtime troubleshooting shortcuts`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val workflow = engine.createWorkflow(
            title = "Task Execution Troubleshooting",
            description = "surface runtime shortcuts on execution precheck failure",
        ).getOrThrow()
        val task = tasksService.addTask(workflow.id, "Run with missing model selection", TaskPriority.P1)
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = false,
            includeTasksDocument = true,
        )
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.clearToolbarModelSelectionForTest()
            assertFalse(panel.requestExecutionForTaskForTest(task.id))
        }

        waitUntil {
            panel.currentStatusActionLabelsForTest().isNotEmpty()
        }

        assertTrue(panel.currentStatusTextForTest().isNotBlank())
        assertEquals(
            listOf(
                "Open settings",
                "Open bundled demo",
            ),
            panel.currentStatusActionLabelsForTest(),
        )
    }

    private fun createPanel(): SpecWorkflowPanel {
        var panel: SpecWorkflowPanel? = null
        ApplicationManager.getApplication().invokeAndWait {
            panel = SpecWorkflowPanel(project)
            Disposer.register(testRootDisposable, panel!!)
        }
        return panel ?: error("Failed to create SpecWorkflowPanel")
    }

    private fun stageWorkflow(
        workflowId: String,
        currentStage: StageId,
        verifyEnabled: Boolean,
        includeTasksDocument: Boolean,
    ) {
        val storage = SpecStorage.getInstance(project)
        val current = storage.loadWorkflow(workflowId).getOrThrow()
        storage.saveWorkflow(
            current.copy(
                currentPhase = phaseForStage(currentStage),
                currentStage = currentStage,
                verifyEnabled = verifyEnabled,
                stageStates = buildStageStates(current.stageStates, currentStage, verifyEnabled),
                documents = buildDocuments(project, workflowId, includeTasksDocument),
                status = WorkflowStatus.IN_PROGRESS,
                updatedAt = System.currentTimeMillis(),
            ),
        ).getOrThrow()
    }

    private fun buildDocuments(
        project: Project,
        workflowId: String,
        includeTasksDocument: Boolean,
    ): Map<SpecPhase, SpecDocument> {
        if (!includeTasksDocument) {
            return emptyMap()
        }
        val tasksContent = SpecArtifactService(project).readArtifact(workflowId, StageId.TASKS)
            ?: return emptyMap()
        return mapOf(
            SpecPhase.IMPLEMENT to SpecDocument(
                id = "$workflowId-tasks",
                phase = SpecPhase.IMPLEMENT,
                content = tasksContent,
                metadata = SpecMetadata(
                    title = "tasks.md",
                    description = "Structured tasks for $workflowId",
                ),
            ),
        )
    }

    private fun buildStageStates(
        existing: Map<StageId, StageState>,
        currentStage: StageId,
        verifyEnabled: Boolean,
    ): Map<StageId, StageState> {
        val marker = "2026-03-13T00:00:00Z"
        return StageId.entries.associateWith { stageId ->
            val active = when (stageId) {
                StageId.VERIFY -> verifyEnabled
                else -> existing[stageId]?.active ?: true
            }
            when {
                !active -> StageState(active = false, status = StageProgress.NOT_STARTED)
                stageId.ordinal < currentStage.ordinal -> StageState(
                    active = true,
                    status = StageProgress.DONE,
                    enteredAt = marker,
                    completedAt = marker,
                )

                stageId == currentStage -> StageState(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                    enteredAt = marker,
                )

                else -> StageState(active = true, status = StageProgress.NOT_STARTED)
            }
        }
    }

    private fun phaseForStage(stageId: StageId): SpecPhase {
        return when (stageId) {
            StageId.REQUIREMENTS -> SpecPhase.SPECIFY
            StageId.DESIGN -> SpecPhase.DESIGN
            StageId.TASKS,
            StageId.IMPLEMENT,
            StageId.VERIFY,
            StageId.ARCHIVE,
            -> SpecPhase.IMPLEMENT
        }
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            if (condition()) {
                return
            }
            Thread.sleep(50)
        }
        fail("Condition was not met within ${timeoutMs}ms")
    }
}
