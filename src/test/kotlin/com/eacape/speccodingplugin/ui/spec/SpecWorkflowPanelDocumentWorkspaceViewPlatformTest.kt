package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
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
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil

class SpecWorkflowPanelDocumentWorkspaceViewPlatformTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        val specEngine = SpecEngine.getInstance(project)
        specEngine.listWorkflows().forEach { workflowId ->
            specEngine.deleteWorkflow(workflowId).getOrThrow()
        }
    }

    fun `test document workspace view should switch between document and structured tasks through ui host`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val workflow = engine.createWorkflow(
            title = "Document Workspace View",
            description = "document workspace smoke",
        ).getOrThrow()
        val task = tasksService.addTask(workflow.id, "Drive structured view", TaskPriority.P1)
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.TASKS,
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
            panel.isDetailModeForTest() &&
                panel.selectedWorkflowIdForTest() == workflow.id &&
                panel.focusedStageForTest() == StageId.TASKS &&
                panel.isDocumentWorkspaceViewTabsVisibleForTest()
        }

        assertFalse(panel.visibleWorkspaceSectionIdsForTest().contains(SpecWorkflowWorkspaceSectionId.TASKS))
        assertEquals("DOCUMENT", panel.documentWorkspaceViewForTest())
        assertEquals("", panel.documentWorkspaceViewLabelForTest())
        assertEquals(
            listOf(
                "DOCUMENT:${SpecCodingBundle.message("spec.toolwindow.documents.view.document")}",
                "STRUCTURED_TASKS:${SpecCodingBundle.message("spec.toolwindow.documents.view.structuredTasks")}",
            ),
            panel.documentWorkspaceViewButtonsForTest(),
        )
        assertTrue(panel.documentWorkspaceInlineActionTextsForTest().isEmpty())
        assertEquals(
            mapOf(
                "DOCUMENT" to JBUI.scale(22),
                "STRUCTURED_TASKS" to JBUI.scale(22),
            ),
            panel.documentWorkspaceViewButtonHeightsForTest(),
        )
        val buttonWidths = panel.documentWorkspaceViewButtonWidthsForTest()
        assertEquals(buttonWidths.getValue("DOCUMENT"), buttonWidths.getValue("STRUCTURED_TASKS"))
        assertTrue(buttonWidths.getValue("STRUCTURED_TASKS") >= JBUI.scale(80))

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(panel.selectTaskForTest(task.id))
            panel.clickDocumentWorkspaceViewForTest("STRUCTURED_TASKS")
        }

        waitUntil {
            panel.documentWorkspaceViewForTest() == "STRUCTURED_TASKS" &&
                panel.detailTasksSnapshotForTest().getValue("selectedTaskId") == task.id
        }

        assertTrue(panel.detailTasksSnapshotForTest().getValue("tasks").contains("${task.id}:PENDING"))
        assertEquals(task.id, panel.tasksSnapshotForTest().getValue("selectedTaskId"))
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
                documents = buildDocuments(workflowId, includeTasksDocument),
                status = WorkflowStatus.IN_PROGRESS,
                updatedAt = System.currentTimeMillis(),
            ),
        ).getOrThrow()
    }

    private fun buildDocuments(workflowId: String, includeTasksDocument: Boolean): Map<SpecPhase, SpecDocument> {
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

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
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
