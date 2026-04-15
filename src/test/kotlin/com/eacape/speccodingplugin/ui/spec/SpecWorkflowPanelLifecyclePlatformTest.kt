package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecStorage
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
class SpecWorkflowPanelLifecyclePlatformTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        val specEngine = SpecEngine.getInstance(project)
        specEngine.listWorkflows().forEach { workflowId ->
            specEngine.deleteWorkflow(workflowId).getOrThrow()
        }
    }

    fun `test archive stage lifecycle actions should stay wired through lifecycle ui host`() {
        val specEngine = SpecEngine.getInstance(project)
        val storage = SpecStorage.getInstance(project)
        val workflow = specEngine.createWorkflow(
            title = "Lifecycle Host Workflow",
            description = "lifecycle ui host smoke",
            template = WorkflowTemplate.QUICK_TASK,
        ).getOrThrow()
        persistArchiveReadyWorkflow(workflow.id)

        val panel = createPanel(confirmArchiveUi = { _, _ -> true })

        waitUntil(30_000) {
            workflow.id in panel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitUntil(30_000) {
            panel.focusedStageForTest() == StageId.ARCHIVE &&
                panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.COMPLETE_WORKFLOW
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickOverviewPrimaryActionForTest()
        }

        waitUntil(30_000) {
            storage.loadWorkflow(workflow.id).getOrThrow().status == WorkflowStatus.COMPLETED
        }

        waitUntil(30_000) {
            panel.toolbarSnapshotForTest().getValue("archive.enabled") == "true"
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickArchiveForTest()
        }

        waitUntil(30_000) {
            workflow.id !in specEngine.listWorkflows()
        }

        waitUntil(30_000) {
            workflow.id !in panel.workflowIdsForTest()
        }
    }

    private fun persistArchiveReadyWorkflow(workflowId: String) {
        val storage = SpecStorage.getInstance(project)
        val specEngine = SpecEngine.getInstance(project)
        val requirements = requirementsDocument(workflowId)
        val design = designDocument(workflowId)
        val tasks = tasksDocument(workflowId)
        val current = storage.loadWorkflow(workflowId).getOrThrow()
        storage.saveWorkflow(
            current.copy(
                currentPhase = SpecPhase.IMPLEMENT,
                currentStage = StageId.ARCHIVE,
                verifyEnabled = false,
                stageStates = buildStageStates(current.stageStates, StageId.ARCHIVE),
                documents = mapOf(
                    SpecPhase.SPECIFY to requirements,
                    SpecPhase.DESIGN to design,
                    SpecPhase.IMPLEMENT to tasks,
                ),
                status = WorkflowStatus.IN_PROGRESS,
                updatedAt = System.currentTimeMillis(),
            ),
        ).getOrThrow()
        specEngine.reloadWorkflow(workflowId).getOrThrow()
        specEngine.updateDocumentContent(workflowId, SpecPhase.SPECIFY, requirements.content).getOrThrow()
        specEngine.updateDocumentContent(workflowId, SpecPhase.DESIGN, design.content).getOrThrow()
        specEngine.updateDocumentContent(workflowId, SpecPhase.IMPLEMENT, tasks.content).getOrThrow()
        specEngine.reloadWorkflow(workflowId).getOrThrow()
    }

    private fun buildStageStates(
        existing: Map<StageId, StageState>,
        currentStage: StageId,
    ): Map<StageId, StageState> {
        val marker = "2026-03-13T00:00:00Z"
        return StageId.entries.associateWith { stageId ->
            val active = when (stageId) {
                StageId.VERIFY -> false
                else -> existing[stageId]?.active ?: true
            }
            when {
                !active -> StageState(
                    active = false,
                    status = StageProgress.NOT_STARTED,
                )

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

                else -> StageState(
                    active = true,
                    status = StageProgress.NOT_STARTED,
                )
            }
        }
    }

    private fun requirementsDocument(workflowId: String): SpecDocument {
        return SpecDocument(
            id = "$workflowId-requirements",
            phase = SpecPhase.SPECIFY,
            content = """
                # Requirements Document

                ## Functional Requirements
                - The lifecycle toolbar should archive completed workflows from the panel.

                ## Acceptance Criteria
                - Archive-stage completion stays wired through the workspace primary action.
            """.trimIndent(),
            metadata = SpecMetadata(
                title = "requirements.md",
                description = "lifecycle host requirements",
            ),
        )
    }

    private fun designDocument(workflowId: String): SpecDocument {
        return SpecDocument(
            id = "$workflowId-design",
            phase = SpecPhase.DESIGN,
            content = """
                # Design Document

                ## Architecture Design
                - The panel should route lifecycle button actions through a dedicated UI host.
            """.trimIndent(),
            metadata = SpecMetadata(
                title = "design.md",
                description = "lifecycle host design",
            ),
        )
    }

    private fun tasksDocument(workflowId: String): SpecDocument {
        return SpecDocument(
            id = "$workflowId-tasks",
            phase = SpecPhase.IMPLEMENT,
            content = """
                ## Tasks

                ### T-001: Finish lifecycle bridge extraction
                ```spec-task
                status: COMPLETED
                priority: P0
                dependsOn: []
                relatedFiles:
                  - src/main/kotlin/com/eacape/speccodingplugin/ui/spec/SpecWorkflowPanel.kt
                verificationResult:
                  conclusion: PASS
                  runId: verify-001
                  summary: lifecycle bridge extracted
                  at: "2026-03-13T12:00:00Z"
                ```
            """.trimIndent() + "\n",
            metadata = SpecMetadata(
                title = "tasks.md",
                description = "archive lifecycle tasks",
            ),
        )
    }

    private fun createPanel(
        confirmArchiveUi: (com.intellij.openapi.project.Project, String) -> Boolean,
    ): SpecWorkflowPanel {
        var panel: SpecWorkflowPanel? = null
        ApplicationManager.getApplication().invokeAndWait {
            panel = SpecWorkflowPanel(
                project = project,
                confirmArchiveUi = confirmArchiveUi,
            )
            Disposer.register(testRootDisposable, panel!!)
        }
        return panel ?: error("Failed to create SpecWorkflowPanel")
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
