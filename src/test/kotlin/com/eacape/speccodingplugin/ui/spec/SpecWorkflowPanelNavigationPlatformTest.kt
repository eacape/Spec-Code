package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.SpecArtifactService
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecStorage
import com.eacape.speccodingplugin.spec.SpecTaskExecutionService
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.eacape.speccodingplugin.spec.TaskPriority
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.WorkflowSourceImportConstraints
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class SpecWorkflowPanelNavigationPlatformTest : BasePlatformTestCase() {

    fun `test workflow panel should default to most recently updated workflow and expose toolbar entries`() {
        val specEngine = SpecEngine.getInstance(project)
        val olderWorkflow = specEngine.createWorkflow(
            title = "Navigation Demo Older",
            description = "older workflow",
        ).getOrThrow()
        val workflow = specEngine.createWorkflow(
            title = "Navigation Demo Latest",
            description = "latest workflow",
        ).getOrThrow()
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest() &&
                panel.isDetailModeForTest() &&
                panel.selectedWorkflowIdForTest() == workflow.id
        }
        assertTrue(panel.isBackButtonInlineForTest())
        waitUntil {
            panel.workspaceSummarySnapshotForTest().getValue("stageValue").isNotBlank()
        }

        val summary = panel.workspaceSummarySnapshotForTest()
        assertEquals("${SpecCodingBundle.message("spec.toolwindow.overview.currentStage")}:", summary.getValue("stageTitle"))
        assertTrue(summary.getValue("stageValue").contains(SpecWorkflowOverviewPresenter.stageLabel(workflow.currentStage)))
        assertEquals("${SpecCodingBundle.message("spec.toolwindow.section.gate")}:", summary.getValue("gateTitle"))
        assertFalse(summary.getValue("gateValue").isBlank())
        assertEquals("${SpecCodingBundle.message("spec.toolwindow.tasks.title")}:", summary.getValue("tasksTitle"))
        assertEquals("0/0", summary.getValue("tasksValue"))
        assertEquals("${SpecCodingBundle.message("spec.toolwindow.section.verify")}:", summary.getValue("verifyTitle"))
        assertFalse(summary.getValue("verifyValue").isBlank())

        val toolbar = panel.toolbarSnapshotForTest()
        assertEquals("", toolbar.getValue("back.text"))
        assertEquals("back", toolbar.getValue("back.iconId"))
        assertEquals("true", toolbar.getValue("back.focusable"))
        assertEquals("", toolbar.getValue("switch.text"))
        assertEquals("switchWorkflow", toolbar.getValue("switch.iconId"))
        assertEquals("true", toolbar.getValue("switch.enabled"))
        assertEquals("", toolbar.getValue("create.text"))
        assertEquals("add", toolbar.getValue("create.iconId"))
        assertEquals("true", toolbar.getValue("create.enabled"))
        assertEquals("", toolbar.getValue("refresh.text"))
        assertEquals("refresh", toolbar.getValue("refresh.iconId"))
        assertEquals("true", toolbar.getValue("refresh.focusable"))
        assertEquals("", toolbar.getValue("delta.text"))
        assertEquals("history", toolbar.getValue("delta.iconId"))
        assertEquals("true", toolbar.getValue("delta.focusable"))
        assertEquals("false", toolbar.getValue("codeGraph.visible"))
        assertEquals("false", toolbar.getValue("archive.visible"))

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickSwitchWorkflowForTest()
        }
        waitUntil {
            panel.isSwitchWorkflowPopupVisibleForTest()
        }
        assertTrue(panel.isDetailModeForTest())
        assertEquals(listOf(workflow.id, olderWorkflow.id), panel.switchWorkflowPopupVisibleWorkflowIdsForTest())
        assertEquals(workflow.id, panel.selectedSwitchWorkflowPopupSelectionForTest())

        ApplicationManager.getApplication().invokeAndWait {
            panel.filterSwitchWorkflowPopupForTest("Older")
        }
        waitUntil {
            panel.switchWorkflowPopupVisibleWorkflowIdsForTest() == listOf(olderWorkflow.id)
        }
        assertEquals(olderWorkflow.id, panel.selectedSwitchWorkflowPopupSelectionForTest())

        ApplicationManager.getApplication().invokeAndWait {
            panel.confirmSwitchWorkflowPopupSelectionForTest()
        }
        waitUntil {
            panel.selectedWorkflowIdForTest() == olderWorkflow.id
        }
        assertEquals(olderWorkflow.id, panel.highlightedWorkflowIdForTest())
    }

    fun `test workflow panel should keep empty state when no workflows exist`() {
        val panel = createPanel()

        waitUntil {
            panel.isListModeForTest() && panel.workflowIdsForTest().isEmpty()
        }

        assertNull(panel.selectedWorkflowIdForTest())
        val toolbar = panel.toolbarSnapshotForTest()
        assertEquals("false", toolbar.getValue("switch.enabled"))
        assertEquals("true", toolbar.getValue("create.enabled"))
    }

    fun `test tool window selection event should still open detail view directly`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Selection Event",
            description = "external open",
        ).getOrThrow()
        val panel = createPanel()

        waitUntil {
            workflow.id in panel.workflowIdsForTest()
        }

        project.messageBus.syncPublisher(SpecToolWindowControlListener.TOPIC)
            .onSelectWorkflowRequested(workflow.id)

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }
    }

    fun `test workflow panel should import remove restore and reopen persisted composer sources`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Source Import",
            description = "composer source persistence",
        ).getOrThrow()
        val importPath = Path.of(project.basePath!!).resolve("incoming/client-prd.md")
        Files.createDirectories(importPath.parent)
        Files.writeString(
            importPath,
            "# Client PRD\n\n- Keep workflow artifacts file-first.\n",
            StandardCharsets.UTF_8,
        )
        val panel = createPanel(sourceFileChooser = { _, _ -> listOf(importPath) })

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickAddWorkflowSourcesForTest()
        }

        waitUntil {
            panel.composerSourceChipLabelsForTest()
                .any { label -> label.contains("SRC-001") && label.contains("client-prd.md") }
        }
        assertTrue(panel.composerSourceMetaTextForTest().contains("1"))

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(panel.clickRemoveWorkflowSourceForTest("SRC-001"))
        }

        waitUntil {
            panel.composerSourceChipLabelsForTest().isEmpty() &&
                panel.isComposerSourceRestoreVisibleForTest()
        }
        assertTrue(panel.composerSourceHintTextForTest().isNotBlank())
        assertEquals(
            listOf("SRC-001"),
            SpecEngine.getInstance(project)
                .listWorkflowSources(workflow.id)
                .getOrThrow()
                .map { it.sourceId },
        )

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickRestoreWorkflowSourcesForTest()
        }

        waitUntil {
            panel.composerSourceChipLabelsForTest().any { it.contains("SRC-001") }
        }

        ApplicationManager.getApplication().invokeAndWait {
            Disposer.dispose(panel)
        }

        val reopenedPanel = createPanel()
        waitUntil {
            reopenedPanel.isDetailModeForTest() && reopenedPanel.selectedWorkflowIdForTest() == workflow.id
        }
        waitUntil {
            reopenedPanel.composerSourceChipLabelsForTest()
                .any { label -> label.contains("SRC-001") && label.contains("client-prd.md") }
        }
    }

    fun `test workflow panel should show automatic code context summary separately from persisted sources`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Auto Code Context",
            description = "composer repo context summary",
        ).getOrThrow()
        val readmePath = Path.of(project.basePath!!).resolve("README.md")
        Files.writeString(
            readmePath,
            "# Demo Project\n\n- repo context should appear in composer.\n",
            StandardCharsets.UTF_8,
        )
        val panel = createPanel()

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }
        waitUntil {
            panel.composerCodeContextCandidateLabelsForTest().any { label -> label.contains("README.md") }
        }

        assertTrue(
            panel.composerCodeContextSummaryChipLabelsForTest().contains(
                SpecCodingBundle.message("spec.detail.codeContext.summary.repo"),
            ),
        )
        assertTrue(panel.composerCodeContextMetaTextForTest().isNotBlank())
        assertTrue(panel.composerCodeContextHintTextForTest().isNotBlank())
        assertTrue(panel.composerSourceChipLabelsForTest().isEmpty())
    }

    fun `test workflow panel should show validation feedback when composer sources are rejected`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Rejected Sources",
            description = "composer source validation",
        ).getOrThrow()
        val unsupportedPath = Path.of(project.basePath!!).resolve("incoming/archive.zip")
        val oversizedPath = Path.of(project.basePath!!).resolve("incoming/requirements.pdf")
        Files.createDirectories(unsupportedPath.parent)
        Files.write(unsupportedPath, ByteArray(16) { 2 })
        Files.write(oversizedPath, ByteArray(96) { 3 })
        val missingPath = Path.of(project.basePath!!).resolve("incoming/missing.txt")
        val warnings = mutableListOf<Pair<String, String>>()
        val panel = createPanel(
            sourceFileChooser = { _, _ -> listOf(unsupportedPath, oversizedPath, missingPath) },
            sourceImportConstraints = WorkflowSourceImportConstraints(maxFileSizeBytes = 64L),
            warningDialogPresenter = { _, message, title ->
                warnings += title to message
            },
        )

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickAddWorkflowSourcesForTest()
        }

        assertEquals(1, warnings.size)
        assertEquals(SpecCodingBundle.message("spec.detail.sources.validation.title"), warnings.single().first)
        assertTrue(warnings.single().second.contains("archive.zip"))
        assertTrue(warnings.single().second.contains("requirements.pdf"))
        assertTrue(warnings.single().second.contains("missing.txt"))
        assertEquals(
            SpecCodingBundle.message("spec.detail.sources.status.rejected", 3),
            panel.currentStatusTextForTest(),
        )
        assertTrue(panel.composerSourceChipLabelsForTest().isEmpty())
        assertTrue(SpecEngine.getInstance(project).listWorkflowSources(workflow.id).getOrThrow().isEmpty())
    }

    fun `test deleting opened workflow should reopen next recent workflow`() {
        val specEngine = SpecEngine.getInstance(project)
        val olderWorkflow = specEngine.createWorkflow(
            title = "Delete Older",
            description = "first workflow",
        ).getOrThrow()
        val latestWorkflow = specEngine.createWorkflow(
            title = "Delete Latest",
            description = "second workflow",
        ).getOrThrow()
        val panel = createPanel()

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == latestWorkflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.deleteWorkflowForTest(latestWorkflow.id)
        }

        waitUntil {
            panel.isDetailModeForTest() &&
                panel.selectedWorkflowIdForTest() == olderWorkflow.id &&
                panel.highlightedWorkflowIdForTest() == olderWorkflow.id
        }

        assertEquals(listOf(olderWorkflow.id), panel.workflowIdsForTest())
    }

    fun `test deleting workflow from list mode should stay in list mode and fall back to remaining item`() {
        val specEngine = SpecEngine.getInstance(project)
        val olderWorkflow = specEngine.createWorkflow(
            title = "List Delete Older",
            description = "first workflow",
        ).getOrThrow()
        val latestWorkflow = specEngine.createWorkflow(
            title = "List Delete Latest",
            description = "second workflow",
        ).getOrThrow()
        val panel = createPanel()

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == latestWorkflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickBackToListForTest()
        }

        waitUntil {
            panel.isListModeForTest() &&
                panel.selectedWorkflowIdForTest() == null &&
                panel.highlightedWorkflowIdForTest() == latestWorkflow.id
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.deleteWorkflowForTest(latestWorkflow.id)
        }

        waitUntil {
            panel.isListModeForTest() &&
                panel.selectedWorkflowIdForTest() == null &&
                panel.highlightedWorkflowIdForTest() == olderWorkflow.id
        }

        assertEquals(listOf(olderWorkflow.id), panel.workflowIdsForTest())

        ApplicationManager.getApplication().invokeAndWait {
            panel.deleteWorkflowForTest(olderWorkflow.id)
        }

        waitUntil {
            panel.isListModeForTest() &&
                panel.workflowIdsForTest().isEmpty() &&
                panel.selectedWorkflowIdForTest() == null &&
                panel.highlightedWorkflowIdForTest() == null
        }
    }

    fun `test external open request should focus current workflow and clear missing request`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Open Request Compatibility",
            description = "keep external open stable",
        ).getOrThrow()
        val panel = createPanel()

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }

        project.messageBus.syncPublisher(SpecToolWindowControlListener.TOPIC)
            .onOpenWorkflowRequested(
                SpecToolWindowOpenRequest(
                    workflowId = workflow.id,
                    focusedStage = StageId.DESIGN,
                ),
            )

        waitUntil {
            panel.focusedStageForTest() == StageId.DESIGN &&
                panel.pendingOpenWorkflowRequestForTest() == null
        }

        project.messageBus.syncPublisher(SpecToolWindowControlListener.TOPIC)
            .onOpenWorkflowRequested(
                SpecToolWindowOpenRequest(
                    workflowId = "wf-missing-open-request",
                    focusedStage = StageId.VERIFY,
                ),
            )

        waitUntil {
            panel.selectedWorkflowIdForTest() == workflow.id &&
                panel.pendingOpenWorkflowRequestForTest() == null
        }

        assertTrue(panel.isDetailModeForTest())
        assertEquals(StageId.DESIGN, panel.focusedStageForTest())
    }

    fun `test requirements workflow should keep continue checks visible for repair actions`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Section Visibility",
            description = "requirements stage visibility",
        ).getOrThrow()
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

        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.TASKS,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            panel.visibleWorkspaceSectionIdsForTest(),
        )
    }

    fun `test focused stage should drive workspace sections and document selection`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Focused Stage",
            description = "stage workbench focus",
        ).getOrThrow()
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
            panel.focusStageForTest(StageId.IMPLEMENT)
        }

        waitUntil {
            panel.focusedStageForTest() == StageId.IMPLEMENT
        }

        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.VERIFY,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            panel.visibleWorkspaceSectionIdsForTest(),
        )
        assertTrue(
            panel.workspaceSummarySnapshotForTest().getValue("stageValue").contains(
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
            ),
        )
        assertTrue(
            panel.workspaceSummarySnapshotForTest().getValue("focusTitle").contains(
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
            ),
        )
        assertEquals("IMPLEMENT", panel.selectedDocumentPhaseForTest())
        assertTrue(panel.isDocumentWorkspaceViewTabsVisibleForTest())
        assertEquals("DOCUMENT", panel.documentWorkspaceViewForTest())
    }

    fun `test verify focused stage should switch document preview to verification artifact`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Verify Preview",
            description = "stage artifact binding",
        ).getOrThrow()
        SpecArtifactService(project).writeArtifact(
            workflow.id,
            StageId.VERIFY,
            """
                # Verification Document

                ## Result
                conclusion: PASS
                summary: verification preview
            """.trimIndent(),
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
            panel.focusStageForTest(StageId.VERIFY)
        }

        waitUntil {
            panel.focusedStageForTest() == StageId.VERIFY &&
                panel.selectedDocumentPhaseForTest() == null &&
                panel.currentDocumentPreviewTextForTest().contains("verification preview")
        }

        assertEquals("verification.md", panel.currentDocumentMetaTextForTest())
        assertTrue(panel.currentDocumentPreviewTextForTest().contains("# Verification Document"))
    }

    fun `test clicking stage chip should sync overview progress primary action documents and workspace sections`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val artifactService = SpecArtifactService(project)
        val workflow = engine.createWorkflow(
            title = "Stage Sync",
            description = "task 65 stage click regression",
        ).getOrThrow()
        tasksService.addTask(workflow.id, "Wire stage workbench", TaskPriority.P1)
        artifactService.writeArtifact(
            workflow.id,
            StageId.VERIFY,
            """
                # Verification Document

                ## Result
                conclusion: PASS
                summary: verification preview
            """.trimIndent(),
        )
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.TASKS,
            verifyEnabled = true,
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
                panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.ADVANCE &&
                panel.selectedDocumentPhaseForTest() == SpecPhase.IMPLEMENT.name
        }

        val tasksSnapshot = panel.overviewSnapshotForTest()
        val tasksProgress = tasksSnapshot.getValue("progress")
        assertEquals(StageId.TASKS.name, tasksSnapshot.getValue("focusedStage"))
        assertFalse(panel.visibleWorkspaceSectionIdsForTest().contains(SpecWorkflowWorkspaceSectionId.VERIFY))
        assertEquals(SpecWorkflowWorkbenchActionKind.ADVANCE, panel.currentPrimaryActionKindForTest())

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickOverviewStageForTest(StageId.VERIFY)
        }

        waitUntil {
            panel.focusedStageForTest() == StageId.VERIFY &&
                panel.selectedDocumentPhaseForTest() == null &&
                panel.currentDocumentPreviewTextForTest().contains("verification preview")
        }

        val verifySnapshot = panel.overviewSnapshotForTest()
        assertEquals(StageId.VERIFY.name, verifySnapshot.getValue("focusedStage"))
        assertTrue(verifySnapshot.getValue("focusTitle").contains(SpecWorkflowOverviewPresenter.stageLabel(StageId.VERIFY)))
        assertNotEquals(tasksProgress, verifySnapshot.getValue("progress"))
        assertNull(panel.currentPrimaryActionKindForTest())
        assertTrue(panel.visibleWorkspaceSectionIdsForTest().contains(SpecWorkflowWorkspaceSectionId.VERIFY))
        assertEquals("verification.md", panel.currentDocumentMetaTextForTest())
    }


    fun `test advancing stage should sync document workspace to the new current stage`() {
        val engine = SpecEngine.getInstance(project)
        val storage = SpecStorage.getInstance(project)
        val workflow = engine.createWorkflow(
            title = "Advance Sync",
            description = "document workspace should follow stage advance",
        ).getOrThrow()
        val current = storage.loadWorkflow(workflow.id).getOrThrow()
        storage.saveWorkflow(
            current.copy(
                currentPhase = SpecPhase.DESIGN,
                currentStage = StageId.DESIGN,
                verifyEnabled = false,
                stageStates = buildStageStates(current.stageStates, StageId.DESIGN, verifyEnabled = false),
                documents = mapOf(
                    SpecPhase.SPECIFY to requirementsDocument(workflow.id),
                    SpecPhase.DESIGN to designDocument(workflow.id),
                ),
                status = WorkflowStatus.IN_PROGRESS,
                updatedAt = System.currentTimeMillis(),
            ),
        ).getOrThrow()
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
                panel.focusedStageForTest() == StageId.DESIGN &&
                panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.ADVANCE &&
                panel.selectedDocumentPhaseForTest() == SpecPhase.DESIGN.name
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickOverviewPrimaryActionForTest()
        }

        waitUntil {
            panel.focusedStageForTest() == StageId.TASKS &&
                panel.selectedDocumentPhaseForTest() == SpecPhase.IMPLEMENT.name &&
                panel.workspaceSummarySnapshotForTest().getValue("stageValue").contains(
                    SpecWorkflowOverviewPresenter.stageLabel(StageId.TASKS),
                )
        }

        assertEquals(StageId.TASKS.name, panel.overviewSnapshotForTest().getValue("focusedStage"))
    }

    fun `test implement workbench primary action should follow task lifecycle states`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val workflow = engine.createWorkflow(
            title = "Implement Action States",
            description = "task 65 implement states",
        ).getOrThrow()
        val task = tasksService.addTask(workflow.id, "Ship stage workbench", TaskPriority.P1)
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = true,
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
                panel.tasksSnapshotForTest().getValue("selectedTaskId") == task.id &&
                panel.overviewSnapshotForTest().getValue("primaryActionVisible") == "false"
        }

        assertEquals(task.id, panel.tasksSnapshotForTest().getValue("selectedTaskId"))

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(panel.requestExecutionForTaskForTest(task.id))
        }

        waitUntil {
            panel.tasksSnapshotForTest().getValue("tasks").contains("${task.id}:IN_PROGRESS") &&
                panel.overviewSnapshotForTest().getValue("primaryActionVisible") == "false"
        }

        assertEquals(task.id, panel.tasksSnapshotForTest().getValue("selectedTaskId"))

        tasksService.updateRelatedFiles(workflow.id, task.id, listOf("src/main/kotlin/App.kt"))
        tasksService.transitionStatus(workflow.id, task.id, TaskStatus.COMPLETED)
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = true,
            includeTasksDocument = true,
        )

        ApplicationManager.getApplication().invokeAndWait {
            panel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.ADVANCE &&
                panel.tasksSnapshotForTest().getValue("tasks").contains("${task.id}:COMPLETED")
        }

        assertEquals(SpecWorkflowWorkbenchActionKind.ADVANCE, panel.currentPrimaryActionKindForTest())
        assertEquals("true", panel.overviewSnapshotForTest().getValue("primaryActionEnabled"))
    }

    fun `test tasks document workspace should switch between document and structured tasks views`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val workflow = engine.createWorkflow(
            title = "Tasks Dual View",
            description = "document workspace should expose structured task tab",
        ).getOrThrow()
        val task = tasksService.addTask(workflow.id, "Drive dual view", TaskPriority.P1)
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
        assertEquals("视图", panel.documentWorkspaceViewLabelForTest())
        assertEquals(
            listOf("DOCUMENT:文档", "STRUCTURED_TASKS:结构化任务"),
            panel.documentWorkspaceViewButtonsForTest(),
        )

        assertEquals(JBUI.scale(26), panel.documentWorkspaceViewSwitcherHeightForTest())
        assertEquals(
            mapOf(
                "DOCUMENT" to JBUI.scale(22),
                "STRUCTURED_TASKS" to JBUI.scale(22),
            ),
            panel.documentWorkspaceViewButtonHeightsForTest(),
        )
        val buttonWidths = panel.documentWorkspaceViewButtonWidthsForTest()
        assertEquals(
            buttonWidths.getValue("DOCUMENT"),
            buttonWidths.getValue("STRUCTURED_TASKS"),
        )
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

    fun `test structured tasks tab should reuse task execution actions`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val workflow = engine.createWorkflow(
            title = "Tasks Dual View Execution",
            description = "embedded task view should reuse execution actions",
        ).getOrThrow()
        val task = tasksService.addTask(workflow.id, "Execute from embedded tab", TaskPriority.P1)
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
            panel.isDetailModeForTest() &&
                panel.selectedWorkflowIdForTest() == workflow.id &&
                panel.focusedStageForTest() == StageId.IMPLEMENT &&
                panel.isDocumentWorkspaceViewTabsVisibleForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickDocumentWorkspaceViewForTest("STRUCTURED_TASKS")
        }

        waitUntil {
            panel.documentWorkspaceViewForTest() == "STRUCTURED_TASKS"
        }

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(panel.requestExecutionForDetailTaskForTest(task.id))
        }

        waitUntil {
            panel.detailTasksSnapshotForTest().getValue("tasks").contains("${task.id}:IN_PROGRESS") &&
                panel.tasksSnapshotForTest().getValue("tasks").contains("${task.id}:IN_PROGRESS")
        }
    }

    fun `test stage navigation should stay read only while current stage keeps the primary path`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val workflow = engine.createWorkflow(
            title = "Overflow Navigation",
            description = "task 65 overflow actions",
        ).getOrThrow()
        tasksService.addTask(workflow.id, "Protect primary path", TaskPriority.P1)
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.TASKS,
            verifyEnabled = true,
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
                panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.ADVANCE
        }

        assertTrue(panel.currentOverflowActionKindsForTest().isEmpty())
        assertEquals("true", panel.overviewSnapshotForTest().getValue("primaryActionVisible"))

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickOverviewStageForTest(StageId.IMPLEMENT)
        }

        waitUntil {
            panel.focusedStageForTest() == StageId.IMPLEMENT
        }

        assertNull(panel.currentPrimaryActionKindForTest())
        assertTrue(panel.currentOverflowActionKindsForTest().isEmpty())
        assertEquals("false", panel.overviewSnapshotForTest().getValue("primaryActionVisible"))

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickOverviewStageForTest(StageId.TASKS)
        }

        waitUntil {
            panel.focusedStageForTest() == StageId.TASKS &&
                panel.currentPrimaryActionKindForTest() == SpecWorkflowWorkbenchActionKind.ADVANCE
        }
    }

    fun `test clicking another stage should only change focused stage and keep persisted current stage`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val workflow = engine.createWorkflow(
            title = "Read Only Stage Navigation",
            description = "task 70 focused stage regression",
        ).getOrThrow()
        tasksService.addTask(workflow.id, "Keep stage read only", TaskPriority.P1)
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.TASKS,
            verifyEnabled = true,
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
                panel.overviewSnapshotForTest().getValue("currentStage") ==
                SpecWorkflowOverviewPresenter.stageLabel(StageId.TASKS)
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickOverviewStageForTest(StageId.IMPLEMENT)
        }

        waitUntil {
            panel.focusedStageForTest() == StageId.IMPLEMENT &&
                panel.overviewSnapshotForTest().getValue("focusedStage") == StageId.IMPLEMENT.name
        }

        val persistedWorkflow = engine.reloadWorkflow(workflow.id).getOrThrow()
        val overviewSnapshot = panel.overviewSnapshotForTest()

        assertEquals(StageId.TASKS, persistedWorkflow.currentStage)
        assertEquals(
            SpecWorkflowOverviewPresenter.stageLabel(StageId.TASKS),
            overviewSnapshot.getValue("currentStage"),
        )
        assertEquals(StageId.IMPLEMENT.name, overviewSnapshot.getValue("focusedStage"))
        assertTrue(
            overviewSnapshot.getValue("focusTitle").contains(
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
            ),
        )
        assertNull(panel.currentPrimaryActionKindForTest())
    }

    fun `test implement workbench should resume blocked task from spec page button`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val workflow = engine.createWorkflow(
            title = "Resume Blocked Task",
            description = "task 68 resume",
        ).getOrThrow()
        tasksService.addTask(workflow.id, "Finish dependency", TaskPriority.P0)
        val blockedTask = tasksService.addTask(
            workflow.id,
            "Resume blocked implementation",
            TaskPriority.P1,
            dependsOn = listOf("T-001"),
        )
        tasksService.updateRelatedFiles(workflow.id, "T-001", listOf("src/main/kotlin/Dependency.kt"))
        tasksService.transitionStatus(workflow.id, "T-001", TaskStatus.COMPLETED)
        tasksService.transitionStatus(workflow.id, blockedTask.id, TaskStatus.BLOCKED)
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = true,
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
                panel.tasksSnapshotForTest().getValue("selectedTaskId") == blockedTask.id &&
                panel.overviewSnapshotForTest().getValue("primaryActionVisible") == "false"
        }

        assertEquals(blockedTask.id, panel.tasksSnapshotForTest().getValue("selectedTaskId"))
        assertEquals("", panel.tasksSnapshotForTest().getValue("executeText"))
        assertEquals("refresh", panel.tasksSnapshotForTest().getValue("executeIconId"))

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(panel.requestExecutionForTaskForTest(blockedTask.id))
        }

        waitUntil {
            panel.tasksSnapshotForTest().getValue("tasks").contains("${blockedTask.id}:IN_PROGRESS") &&
                panel.overviewSnapshotForTest().getValue("primaryActionVisible") == "false"
        }
    }

    fun `test execution run should derive in progress UI state without persisting task status`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val executionService = SpecTaskExecutionService.getInstance(project)
        val artifactService = SpecArtifactService(project)
        val workflow = engine.createWorkflow(
            title = "Execution Run Derived State",
            description = "task 76 derived in progress regression",
        ).getOrThrow()
        val task = tasksService.addTask(workflow.id, "Drive UI from run state", TaskPriority.P0)
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = false,
            includeTasksDocument = true,
        )
        executionService.createRun(
            workflowId = workflow.id,
            taskId = task.id,
            status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-13T12:00:00Z",
            summary = "Waiting for confirmation",
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
                panel.tasksSnapshotForTest().getValue("tasks").contains("${task.id}:IN_PROGRESS:P0") &&
                panel.overviewSnapshotForTest().getValue("primaryActionVisible") == "false"
        }

        val tasksSnapshot = panel.tasksSnapshotForTest()
        val persistedTasks = artifactService.readArtifact(workflow.id, StageId.TASKS).orEmpty()

        assertEquals(task.id, tasksSnapshot.getValue("selectedTaskId"))
        assertEquals("waitingComplete", tasksSnapshot.getValue("executeIconId"))
        assertEquals("true", tasksSnapshot.getValue("executeFocusable"))
        assertEquals("false", panel.overviewSnapshotForTest().getValue("overflowVisible"))
        assertTrue(persistedTasks.contains("status: PENDING"))
        assertFalse(persistedTasks.contains("status: IN_PROGRESS"))
    }

    fun `test reopening panel should restore waiting confirmation ui from persisted run without mutating tasks document`() {
        val engine = SpecEngine.getInstance(project)
        val tasksService = SpecTasksService(project)
        val executionService = SpecTaskExecutionService.getInstance(project)
        val artifactService = SpecArtifactService(project)
        val workflow = engine.createWorkflow(
            title = "Execution Run Reopen Restore",
            description = "task 91 waiting confirmation recovery",
        ).getOrThrow()
        val task = tasksService.addTask(workflow.id, "Recover waiting confirmation", TaskPriority.P0)
        stageWorkflow(
            workflowId = workflow.id,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = false,
            includeTasksDocument = true,
        )
        val tasksBeforeOpen = artifactService.readArtifact(workflow.id, StageId.TASKS).orEmpty()
        executionService.createRun(
            workflowId = workflow.id,
            taskId = task.id,
            status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
            trigger = ExecutionTrigger.SYSTEM_RECOVERY,
            startedAt = "2026-03-15T10:00:00Z",
            summary = "Recovered run awaiting confirmation.",
        )

        val firstPanel = createPanel()
        waitUntil {
            workflow.id in firstPanel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            firstPanel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            firstPanel.isDetailModeForTest() &&
                firstPanel.selectedWorkflowIdForTest() == workflow.id &&
                firstPanel.tasksSnapshotForTest().getValue("selectedTaskPhase") == "WAITING_CONFIRMATION" &&
                firstPanel.overviewSnapshotForTest().getValue("primaryActionVisible") == "false"
        }

        val firstTasksSnapshot = firstPanel.tasksSnapshotForTest()
        val firstOverviewSnapshot = firstPanel.overviewSnapshotForTest()
        assertEquals(task.id, firstTasksSnapshot.getValue("selectedTaskId"))
        assertEquals("waitingComplete", firstTasksSnapshot.getValue("executeIconId"))
        assertEquals(
            "Recovered run awaiting confirmation.",
            firstTasksSnapshot.getValue("selectedTaskExecutionDetail"),
        )
        assertEquals("false", firstOverviewSnapshot.getValue("primaryActionVisible"))
        assertEquals("false", firstOverviewSnapshot.getValue("overflowVisible"))

        ApplicationManager.getApplication().invokeAndWait {
            Disposer.dispose(firstPanel)
        }

        val reopenedPanel = createPanel()
        waitUntil {
            workflow.id in reopenedPanel.workflowIdsForTest()
        }

        ApplicationManager.getApplication().invokeAndWait {
            reopenedPanel.openWorkflowForTest(workflow.id)
        }

        waitUntil {
            reopenedPanel.isDetailModeForTest() &&
                reopenedPanel.selectedWorkflowIdForTest() == workflow.id &&
                reopenedPanel.tasksSnapshotForTest().getValue("selectedTaskPhase") == "WAITING_CONFIRMATION" &&
                reopenedPanel.overviewSnapshotForTest().getValue("primaryActionVisible") == "false"
        }

        val reopenedTasksSnapshot = reopenedPanel.tasksSnapshotForTest()
        val reopenedOverviewSnapshot = reopenedPanel.overviewSnapshotForTest()
        val tasksAfterReopen = artifactService.readArtifact(workflow.id, StageId.TASKS).orEmpty()

        assertEquals(task.id, reopenedTasksSnapshot.getValue("selectedTaskId"))
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.execution.chip.waitingConfirmation"),
            reopenedTasksSnapshot.getValue("selectedTaskChip"),
        )
        assertEquals(
            "Recovered run awaiting confirmation.",
            reopenedTasksSnapshot.getValue("selectedTaskExecutionDetail"),
        )
        assertEquals("false", reopenedOverviewSnapshot.getValue("primaryActionVisible"))
        assertEquals("false", reopenedOverviewSnapshot.getValue("overflowVisible"))
        assertEquals(tasksBeforeOpen, tasksAfterReopen)
        assertTrue(tasksAfterReopen.contains("status: PENDING"))
        assertFalse(tasksAfterReopen.contains("status: IN_PROGRESS"))
    }

    fun `test clarify then fill should reuse clarification ui and persist repair retry metadata`() {
        val engine = SpecEngine.getInstance(project)
        val workflow = engine.createWorkflow(
            title = "Requirements Clarify Repair",
            description = "task 86 clarify then fill",
        ).getOrThrow()
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
            assertTrue(
                panel.startRequirementsClarifyThenFillForTest(
                    workflow.id,
                    listOf(RequirementsSectionId.USER_STORIES, RequirementsSectionId.ACCEPTANCE_CRITERIA),
                ),
            )
        }

        waitUntil {
            panel.isClarifyingForTest() && panel.focusedStageForTest() == StageId.REQUIREMENTS
        }

        val questionsText = panel.currentClarificationQuestionsTextForTest()
        val retryState = engine.reloadWorkflow(workflow.id).getOrThrow().clarificationRetryState

        assertTrue(questionsText.contains(SpecCodingBundle.message("spec.workflow.clarify.noQuestions")))
        assertTrue(
            questionsText.contains(
                SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.manualFallback.phase"),
            ),
        )
        assertEquals(ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR, retryState?.followUp)
        assertEquals(
            listOf(RequirementsSectionId.USER_STORIES, RequirementsSectionId.ACCEPTANCE_CRITERIA),
            retryState?.requirementsRepairSections,
        )
    }

    private fun createPanel(
        sourceFileChooser: ((Project, WorkflowSourceImportConstraints) -> List<Path>)? = null,
        sourceImportConstraints: WorkflowSourceImportConstraints = WorkflowSourceImportConstraints(),
        warningDialogPresenter: ((Project, String, String) -> Unit)? = null,
    ): SpecWorkflowPanel {
        var panel: SpecWorkflowPanel? = null
        ApplicationManager.getApplication().invokeAndWait {
            panel = when {
                sourceFileChooser != null && warningDialogPresenter != null -> {
                    SpecWorkflowPanel(
                        project,
                        sourceFileChooser = sourceFileChooser,
                        sourceImportConstraints = sourceImportConstraints,
                        warningDialogPresenter = warningDialogPresenter,
                    )
                }

                sourceFileChooser != null -> {
                    SpecWorkflowPanel(
                        project,
                        sourceFileChooser = sourceFileChooser,
                        sourceImportConstraints = sourceImportConstraints,
                    )
                }

                warningDialogPresenter != null -> {
                    SpecWorkflowPanel(
                        project,
                        sourceImportConstraints = sourceImportConstraints,
                        warningDialogPresenter = warningDialogPresenter,
                    )
                }

                sourceImportConstraints != WorkflowSourceImportConstraints() -> {
                    SpecWorkflowPanel(
                        project,
                        sourceImportConstraints = sourceImportConstraints,
                    )
                }

                else -> {
                    SpecWorkflowPanel(project)
                }
            }
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
                description = "advance sync requirements",
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
                - `SpecWorkflowPanel` rebuilds the workbench state from the current workflow stage.

                ## Technology Stack
                - Kotlin, IntelliJ Platform Swing UI, and coroutine-backed refreshes.

                ## Data Model
                - The panel maps the focused stage to a document binding and preview state.

                ## API Design
                - Stage advance refreshes workflow metadata and document bindings together.
            """.trimIndent(),
            metadata = SpecMetadata(
                title = "design.md",
                description = "advance sync design",
            ),
        )
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

