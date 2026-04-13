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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class SpecWorkflowPanelStageTransitionPlatformTest : BasePlatformTestCase() {

    fun `test stage advance should refresh workflow summary and document binding`() {
        val storage = SpecStorage.getInstance(project)
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Stage Transition Smoke",
            description = "stage advance should stay wired through the panel",
        ).getOrThrow()
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.DESIGN,
        )
        val panel = createPanel()

        waitUntil(30_000) {
            workflow.id in panel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitForAdvanceReady(panel, workflow.id)

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickOverviewPrimaryActionForTest()
        }

        waitUntil(30_000) {
            storage.loadWorkflow(workflow.id).getOrThrow().currentStage == StageId.TASKS
        }

        waitUntil(30_000) {
            panel.focusedStageForTest() == StageId.TASKS &&
                panel.selectedDocumentPhaseForTest() == SpecPhase.IMPLEMENT.name &&
                panel.workspaceSummarySnapshotForTest().getValue("stageValue").contains(
                    SpecWorkflowOverviewPresenter.stageLabel(StageId.TASKS),
                )
        }

        val persistedWorkflow = storage.loadWorkflow(workflow.id).getOrThrow()
        val overviewSnapshot = panel.overviewSnapshotForTest()

        assertEquals(StageId.TASKS, persistedWorkflow.currentStage)
        assertEquals(StageId.TASKS.name, overviewSnapshot.getValue("focusedStage"))
        assertTrue(
            panel.workspaceSummarySnapshotForTest().getValue("stageValue").contains(
                SpecWorkflowOverviewPresenter.stageLabel(StageId.TASKS),
            ),
        )
    }

    private fun stageWorkflow(
        workflowId: String,
        currentStage: StageId,
    ) {
        val storage = SpecStorage.getInstance(project)
        val current = storage.loadWorkflow(workflowId).getOrThrow()
        storage.saveWorkflow(
            current.copy(
                currentPhase = phaseForStage(currentStage),
                currentStage = currentStage,
                verifyEnabled = false,
                stageStates = buildStageStates(current.stageStates, currentStage),
                documents = mapOf(
                    SpecPhase.SPECIFY to requirementsDocument(workflowId),
                    SpecPhase.DESIGN to designDocument(workflowId),
                ),
                status = WorkflowStatus.IN_PROGRESS,
                updatedAt = System.currentTimeMillis(),
            ),
        ).getOrThrow()
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

    private fun requirementsDocument(workflowId: String): SpecDocument {
        return SpecDocument(
            id = "$workflowId-requirements",
            phase = SpecPhase.SPECIFY,
            content = """
                # Requirements Document

                ## Functional Requirements
                - The stage workspace should move to the next stage after a successful advance.

                ## Non-Functional Requirements
                - The document workspace should refresh in the same UI cycle.
                - Stage and document state must remain consistent.

                ## User Stories
                As a reviewer, I want the document workspace to follow the current stage, so that I can continue editing without manually changing tabs.

                ## Acceptance Criteria
                - Advancing from design updates the current stage and document workspace together.
            """.trimIndent(),
            metadata = SpecMetadata(
                title = "requirements.md",
                description = "stage transition smoke requirements",
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
                - Stage advance refreshes workflow metadata and document bindings together.

                ## Technology Stack
                - Kotlin, IntelliJ Platform Swing UI, and coroutine-backed refreshes.

                ## Data Model
                - The panel maps the focused stage to a document binding and preview state.

                ## API Design
                - Stage advance refreshes workflow metadata and document bindings together.

                ## Non-Functional Design
                - Stage advance should keep summary refreshes deterministic and non-blocking.
            """.trimIndent(),
            metadata = SpecMetadata(
                title = "design.md",
                description = "stage transition smoke design",
            ),
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

    private fun waitForAdvanceReady(panel: SpecWorkflowPanel, workflowId: String) {
        val expectedStageLabel = SpecWorkflowOverviewPresenter.stageLabel(StageId.DESIGN)
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            val overview = panel.overviewSnapshotForTest()
            if (
                panel.isDetailModeForTest() &&
                panel.selectedWorkflowIdForTest() == workflowId &&
                panel.focusedStageForTest() == StageId.DESIGN &&
                panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.ADVANCE &&
                overview.getValue("primaryActionEnabled") == "true" &&
                panel.workspaceSummarySnapshotForTest().getValue("stageValue").contains(expectedStageLabel)
            ) {
                return
            }
            Thread.sleep(50)
        }
        val overview = panel.overviewSnapshotForTest()
        fail(
            "Advance action was not ready: " +
                "detailMode=${panel.isDetailModeForTest()}, " +
                "selected=${panel.selectedWorkflowIdForTest()}, " +
                "focusedStage=${panel.focusedStageForTest()}, " +
                "primaryAction=${panel.currentPrimaryActionKindForTest()}, " +
                "primaryEnabled=${overview["primaryActionEnabled"]}, " +
                "stageValue=${panel.workspaceSummarySnapshotForTest()["stageValue"]}, " +
                "documentPhase=${panel.selectedDocumentPhaseForTest()}, " +
                "blockers=${overview["blockers"]}, " +
                "checklist=${overview["checklist"]}",
        )
    }
}
