package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.CodeGraphSnapshot
import com.eacape.speccodingplugin.context.CodeGraphService
import com.eacape.speccodingplugin.core.OperationModeManager
import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.llm.MockLlmProvider
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.llm.ModelRegistry
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.spec.*
import com.eacape.speccodingplugin.ui.ChatToolWindowControlListener
import com.eacape.speccodingplugin.ui.ChatToolWindowFactory
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadiness
import com.eacape.speccodingplugin.ui.RefreshFeedback
import com.eacape.speccodingplugin.ui.SwingPanelTaskCoordinator
import com.eacape.speccodingplugin.ui.WorkflowChatRefreshEvent
import com.eacape.speccodingplugin.ui.WorkflowChatRefreshListener
import com.eacape.speccodingplugin.ui.history.HistorySessionOpenListener
import com.eacape.speccodingplugin.ui.actions.SpecWorkflowActionSupport
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.eacape.speccodingplugin.window.GlobalConfigChangedEvent
import com.eacape.speccodingplugin.window.GlobalConfigSyncListener
import com.eacape.speccodingplugin.window.WindowSessionIsolationService
import com.eacape.speccodingplugin.worktree.WorktreeManager
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.awt.CardLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.FlowLayout
import java.awt.Font
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.Timer

class SpecWorkflowPanel(
    private val project: Project,
    private val sourceFileChooser: (Project, WorkflowSourceImportConstraints) -> List<Path> = ::chooseWorkflowSourceFiles,
    private val sourceImportConstraints: WorkflowSourceImportConstraints = WorkflowSourceImportConstraints(),
    private val warningDialogPresenter: (Project, String, String) -> Unit = { dialogProject, message, title ->
        Messages.showWarningDialog(dialogProject, message, title)
    },
    private val confirmArchiveUi: (Project, String) -> Boolean = { dialogProject, workflowId ->
        SpecWorkflowActionSupport.confirmArchive(dialogProject, workflowId)
    },
    private val codeGraphBuilder: (() -> Result<CodeGraphSnapshot>)? = null,
    private val codeGraphDialogPresenter: (String, String) -> Unit = { summary, mermaid ->
        CodeGraphDialog(summary = summary, mermaid = mermaid).show()
    },
    private val deferInitialWorkflowRefresh: Boolean = false,
) : JBPanel<SpecWorkflowPanel>(BorderLayout()), Disposable {

    private val logger = thisLogger()
    private val workflowPanelState = SpecWorkflowPanelState()
    private val specEngine = SpecEngine.getInstance(project)
    private val specDeltaService = SpecDeltaService.getInstance(project)
    private val specTasksService = SpecTasksService.getInstance(project)
    private val specTaskExecutionService = SpecTaskExecutionService.getInstance(project)
    private val specTaskCompletionService = SpecTaskCompletionService.getInstance(project)
    private val specCodeContextService = project.service<SpecCodeContextService>()
    private val specRelatedFilesService = SpecRelatedFilesService.getInstance(project)
    private val specVerificationService = SpecVerificationService.getInstance(project)
    private val specRequirementsQuickFixService = SpecRequirementsQuickFixService(project)
    private val specTasksQuickFixService = SpecTasksQuickFixService(project)
    private val sessionManager = SessionManager.getInstance(project)
    private val sessionIsolationService = WindowSessionIsolationService.getInstance(project)
    private val artifactService = SpecArtifactService(project)
    private val codeGraphService = CodeGraphService.getInstance(project)
    private val worktreeManager = WorktreeManager.getInstance(project)
    private val llmRouter = LlmRouter.getInstance()
    private val modelRegistry = ModelRegistry.getInstance()
    private val modeManager = OperationModeManager.getInstance(project)
    private val taskCoordinator = SwingPanelTaskCoordinator(
        isDisposed = { _isDisposed || project.isDisposed },
    )
    private val runtimeTroubleshootingActionBuilder = SpecWorkflowRuntimeTroubleshootingActionBuilder(
        readinessSnapshot = {
            LocalEnvironmentReadiness.inspect(project)
        },
        trackingSnapshot = {
            SpecWorkflowFirstRunTrackingStore.getInstance(project).snapshot()
        },
        resolveTemplate = { workflowId ->
            currentWorkflow
                ?.takeIf { workflow -> workflow.id == workflowId }
                ?.template
                ?: WorkflowTemplate.QUICK_TASK
        },
    )
    private val runtimeTroubleshootingStatusCoordinator = SpecWorkflowRuntimeTroubleshootingStatusCoordinator(
        buildActions = runtimeTroubleshootingActionBuilder::build,
    )
    private val workspacePresentationTelemetry = SpecWorkflowWorkspacePresentationTelemetryTracker(logger)
    private val workbenchArtifactBindingResolver = SpecWorkflowWorkbenchArtifactBindingResolver(
        locateArtifact = { workflowId, fileName ->
            artifactService.locateArtifact(workflowId, fileName)
        },
    )
    private val workflowArtifactNavigationCoordinator = SpecWorkflowArtifactNavigationCoordinator(
        resolvePhaseDocumentPath = { workflowId, phase ->
            project.basePath?.let { basePath ->
                Path.of(basePath, ".spec-coding", "specs", workflowId, phase.outputFileName)
            }
        },
        locateArtifact = { workflowId, fileName ->
            artifactService.locateArtifact(workflowId, fileName)
        },
        openFile = { path ->
            SpecWorkflowActionSupport.openFile(project, path)
        },
        runIo = { task ->
            taskCoordinator.launchIo {
                task()
            }
        },
        invokeLater = ::invokeLaterSafe,
        listDocumentHistory = specEngine::listDocumentHistory,
        loadDocumentSnapshot = specEngine::loadDocumentSnapshot,
        deleteDocumentSnapshot = specEngine::deleteDocumentSnapshot,
        pruneDocumentHistory = specEngine::pruneDocumentHistory,
        exportHistoryDiffSummary = ::exportHistoryDiffSummary,
        showHistoryDiffDialog = ::showHistoryDiffDialog,
        setStatusText = ::setStatusText,
    )
    private val workflowDeltaCoordinator = SpecWorkflowDeltaCoordinator(
        listWorkflowIds = specEngine::listWorkflows,
        loadWorkflow = specEngine::loadWorkflow,
        compareByWorkflowId = { baselineWorkflowId, targetWorkflowId ->
            specDeltaService.compareByWorkflowId(
                baselineWorkflowId = baselineWorkflowId,
                targetWorkflowId = targetWorkflowId,
            )
        },
        runIo = { task ->
            taskCoordinator.launchIo {
                task()
            }
        },
        invokeLater = ::invokeLaterSafe,
        selectBaselineWorkflow = { currentWorkflowId, workflowOptions ->
            val selectDialog = SpecBaselineSelectDialog(
                workflowOptions = workflowOptions,
                currentWorkflowId = currentWorkflowId,
            )
            if (!selectDialog.showAndGet()) {
                SpecWorkflowDeltaBaselineSelectionResult(confirmed = false)
            } else {
                SpecWorkflowDeltaBaselineSelectionResult(
                    confirmed = true,
                    baselineWorkflowId = selectDialog.selectedBaselineWorkflowId,
                )
            }
        },
        showDeltaDialog = { request ->
            SpecDeltaDialog(
                project = project,
                delta = request.delta,
                onOpenHistoryDiff = request.onOpenHistoryDiff,
                onExportReport = request.onExportReport,
                onReportExported = request.onReportExported,
            ).show()
        },
        showHistoryDiff = workflowArtifactNavigationCoordinator::showHistoryDiff,
        exportReport = specDeltaService::exportReport,
        setStatusText = ::setStatusText,
        renderFailureMessage = { error ->
            compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
        },
    )
    private val workflowVerifyDeltaStateBuilder = SpecWorkflowVerifyDeltaStateBuilder(
        listVerificationHistory = specVerificationService::listRunHistory,
        resolveBaselineWorkflowTitle = { workflowId ->
            specEngine.loadWorkflow(workflowId).getOrNull()?.title
        },
        listDeltaBaselines = specEngine::listDeltaBaselines,
        hasWorkflowSnapshots = { workflowId ->
            specEngine.listWorkflowSnapshots(workflowId).isNotEmpty()
        },
        compareByWorkflowId = { baselineWorkflowId, targetWorkflowId ->
            specDeltaService.compareByWorkflowId(
                baselineWorkflowId = baselineWorkflowId,
                targetWorkflowId = targetWorkflowId,
            )
        },
        compareByDeltaBaseline = { workflowId, baselineId ->
            specDeltaService.compareByDeltaBaseline(
                workflowId = workflowId,
                baselineId = baselineId,
            )
        },
        hasVerificationArtifact = ::hasVerificationArtifact,
        logFailure = { message, error ->
            logger.debug(message, error)
        },
    )
    private val workflowUiSnapshotBuilder = SpecWorkflowUiSnapshotBuilder(
        previewAdvanceGate = { workflowId ->
            specEngine.previewStageTransition(
                workflowId = workflowId,
                transitionType = StageTransitionType.ADVANCE,
            )
        },
        buildOverviewState = { workflow, gatePreview, refreshedAtMillis ->
            SpecWorkflowOverviewPresenter.buildState(
                workflow = workflow,
                gatePreview = gatePreview,
                refreshedAtMillis = refreshedAtMillis,
            )
        },
        buildVerifyDeltaState = workflowVerifyDeltaStateBuilder::build,
        logGatePreviewFailure = { message, error ->
            logger.debug(message, error)
        },
    )
    private val workflowWorkspacePresentationRequestBuilder = SpecWorkflowWorkspacePresentationRequestBuilder(
        buildOverviewState = workflowUiSnapshotBuilder::buildOverview,
        buildVerifyDeltaState = workflowVerifyDeltaStateBuilder::build,
    )
    private val workflowTemplateCloneCoordinator = SpecWorkflowTemplateCloneCoordinator(
        workflowProvider = { currentWorkflow },
        previewTemplateSwitch = specEngine::previewTemplateSwitch,
        cloneWorkflowWithTemplate = specEngine::cloneWorkflowWithTemplate,
        runPreviewInBackground = { title, task, onSuccess ->
            SpecWorkflowActionSupport.runBackground(
                project = project,
                title = title,
                task = task,
                onSuccess = onSuccess,
            )
        },
        runCloneInBackground = { title, task, onSuccess ->
            SpecWorkflowActionSupport.runBackground(
                project = project,
                title = title,
                task = task,
                onSuccess = onSuccess,
            )
        },
        showBlockedPreview = { title, message ->
            Messages.showErrorDialog(project, message, title)
        },
        confirmPreview = { title, message ->
            Messages.showDialog(
                project,
                message,
                title,
                arrayOf(
                    SpecCodingBundle.message("spec.action.template.clone.confirm.continue"),
                    com.intellij.CommonBundle.getCancelButtonText(),
                ),
                0,
                Messages.getQuestionIcon(),
            ) == 0
        },
        editClone = { request ->
            val dialog = EditSpecWorkflowDialog(
                initialTitle = request.initialTitle,
                initialDescription = request.initialDescription,
                dialogTitle = request.dialogTitle,
            )
            if (!dialog.showAndGet()) {
                null
            } else {
                SpecWorkflowTemplateCloneEditResult(
                    title = dialog.resultTitle.orEmpty(),
                    description = dialog.resultDescription,
                )
            }
        },
        notifySuccess = { message ->
            SpecWorkflowActionSupport.notifySuccess(project, message)
        },
        onCloneCreated = { workflowId ->
            highlightedWorkflowId = workflowId
            publishWorkflowSelection(workflowId)
            refreshWorkflows(selectWorkflowId = workflowId)
        },
    )
    private val workflowCreateCoordinator = SpecWorkflowCreateCoordinator(
        createWorkflow = { request ->
            specEngine.createWorkflow(
                title = request.title,
                description = request.description,
                template = request.template,
                verifyEnabled = request.verifyEnabled,
                changeIntent = request.changeIntent,
                baselineWorkflowId = request.baselineWorkflowId,
            )
        },
        recordCreateAttempt = { template, timestamp ->
            SpecWorkflowFirstRunTrackingStore.getInstance(project).recordWorkflowCreateAttempt(template, timestamp)
        },
        recordCreateSuccess = { workflowId, template, timestamp ->
            SpecWorkflowFirstRunTrackingStore.getInstance(project)
                .recordWorkflowCreateSuccess(workflowId = workflowId, template = template, timestampMillis = timestamp)
        },
        firstVisibleArtifactExists = { workflowId, artifactFileName ->
            Files.isRegularFile(artifactService.locateArtifact(workflowId, artifactFileName))
        },
    )
    private val workflowCreateEditUiHost = SpecWorkflowCreateEditUiHost(
        project = project,
        highlightWorkflow = { workflowId ->
            highlightedWorkflowId = workflowId
        },
        refreshWorkflows = { workflowId ->
            refreshWorkflows(selectWorkflowId = workflowId)
        },
        publishWorkflowSelection = ::publishWorkflowSelection,
        isWorkflowOpened = { workflowId ->
            selectedWorkflowId == workflowId
        },
        applyUpdatedWorkflowToOpenedUi = { updated ->
            currentWorkflow = updated
            phaseIndicator.updatePhase(updated)
            detailPanel.updateWorkflow(updated)
            applyToolbarActionAvailability(
                SpecWorkflowToolbarActionAvailabilityBuilder.build(updated),
            )
        },
        setStatusText = ::setStatusText,
        renderFailureMessage = { error ->
            compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
        },
        logFailure = { message, error ->
            logger.warn(message, error)
        },
    )
    private val workflowDocumentSaveCoordinator = SpecWorkflowDocumentSaveCoordinator(
        backgroundRunner = object : SpecWorkflowDocumentSaveBackgroundRunner {
            override fun <T> run(request: SpecWorkflowDocumentSaveBackgroundRequest<T>) {
                taskCoordinator.launchIo {
                    runCatching { request.task() }
                        .onSuccess { outcome ->
                            invokeLaterSafe {
                                request.onSuccess(outcome)
                            }
                        }
                        .onFailure { error ->
                            invokeLaterSafe {
                                request.onFailure(error)
                            }
                        }
                }
            }
        },
        selectedWorkflowId = { selectedWorkflowId },
        saveDocumentContent = specEngine::updateDocumentContent,
        applySavedWorkflowState = { updated ->
            currentWorkflow = updated
            phaseIndicator.updatePhase(updated)
            applyToolbarActionAvailability(
                SpecWorkflowToolbarActionAvailabilityBuilder.build(updated),
            )
        },
        setStatusText = ::setStatusText,
        renderFailureMessage = { error ->
            compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
        },
    )
    private val workflowPhaseNavigationCoordinator = SpecWorkflowPhaseNavigationCoordinator(
        backgroundRunner = object : SpecWorkflowPhaseNavigationBackgroundRunner {
            override fun <T> run(request: SpecWorkflowPhaseNavigationBackgroundRequest<T>) {
                taskCoordinator.launchIo {
                    runCatching { request.task() }
                        .onSuccess { outcome ->
                            invokeLaterSafe {
                                request.onSuccess(outcome)
                            }
                        }
                        .onFailure { error ->
                            invokeLaterSafe {
                                request.onFailure(error)
                            }
                        }
                }
            }
        },
        selectedWorkflowId = { selectedWorkflowId },
        proceedToNextPhase = specEngine::proceedToNextPhase,
        goBackToPreviousPhase = specEngine::goBackToPreviousPhase,
        clearInput = {
            detailPanel.clearInput()
        },
        reloadCurrentWorkflow = {
            reloadCurrentWorkflow(followCurrentPhase = true)
        },
        setStatusText = ::setStatusText,
        renderFailureMessage = { error ->
            compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
        },
    )
    private val workflowCreateEditCoordinator = SpecWorkflowCreateEditCoordinator(
        backgroundRunner = object : SpecWorkflowCreateEditBackgroundRunner {
            override fun <T> run(request: SpecWorkflowCreateEditBackgroundRequest<T>) {
                taskCoordinator.launchIo {
                    runCatching { request.task() }
                        .onSuccess { outcome ->
                            invokeLaterSafe {
                                request.onSuccess(outcome)
                            }
                        }
                        .onFailure { error ->
                            invokeLaterSafe {
                                request.onFailure(error)
                            }
                        }
                }
            }
        },
        ui = workflowCreateEditUiHost,
        createWorkflow = workflowCreateCoordinator::create,
        loadWorkflowForEdit = { workflowId ->
            specEngine.loadWorkflow(workflowId).getOrNull()
        },
        updateWorkflowMetadata = specEngine::updateWorkflowMetadata,
    )
    private val loadCoordinator = SpecWorkflowPanelLoadCoordinator(
        reloadWorkflow = { workflowId -> specEngine.reloadWorkflow(workflowId) },
        parseTasks = { workflowId -> specTasksService.parse(workflowId) },
        buildCodeContext = { workflow ->
            specCodeContextService.buildCodeContextPack(
                workflow = workflow,
                phase = workflow.currentPhase,
            )
        },
        listWorkflowSources = { workflowId -> specEngine.listWorkflowSources(workflowId) },
        buildUiSnapshot = workflowUiSnapshotBuilder::build,
        buildTaskLiveProgressByTaskId = ::buildTaskLiveProgressByTaskId,
    )
    private val loadedStateCoordinator = SpecWorkflowLoadedStateCoordinator(
        buildUiSnapshot = workflowUiSnapshotBuilder::build,
        decorateTasksWithExecutionState = ::decorateTasksWithExecutionState,
        renderFailureMessage = { error ->
            compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
        },
    )
    private val workflowDetailStateHost by lazy(LazyThreadSafetyMode.NONE) {
        SpecWorkflowDetailStateApplicationHost(
            detailUi = object : SpecWorkflowDetailStateApplicationUi {
                override fun updateAutoCodeContext(
                    workflowId: String?,
                    codeContextPack: CodeContextPack?,
                ) {
                    detailPanel.updateAutoCodeContext(
                        workflowId = workflowId,
                        codeContextPack = codeContextPack,
                    )
                }

                override fun updateWorkflowSources(
                    workflowId: String?,
                    assets: List<WorkflowSourceAsset>,
                    selectedSourceIds: Set<String>,
                    editable: Boolean,
                ) {
                    detailPanel.updateWorkflowSources(
                        workflowId = workflowId,
                        assets = assets,
                        selectedSourceIds = selectedSourceIds,
                        editable = editable,
                    )
                }
            },
            composerSourceCoordinator = composerSourceCoordinator,
            renderFailureMessage = { error ->
                compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
            },
        )
    }
    private val workflowComposerSourceUiHost by lazy(LazyThreadSafetyMode.NONE) {
        SpecWorkflowComposerSourceUiHost(
            currentWorkflow = { currentWorkflow },
            currentWorkflowSources = workflowDetailStateHost::currentWorkflowSources,
            selectedSourceIds = workflowDetailStateHost::selectedSourceIds,
            chooseSourcePaths = { sourceFileChooser(project, sourceImportConstraints) },
            applyWorkflowSourcesPresentation = { workflowId, presentation ->
                workflowDetailStateHost.applyWorkflowSourcesPresentation(workflowId, presentation)
            },
            isWorkflowStillSelected = { workflowId ->
                workflowId == selectedWorkflowId
            },
            showValidationDialogUi = { validationDialog ->
                warningDialogPresenter(
                    project,
                    validationDialog.message,
                    validationDialog.title,
                )
            },
            setStatusText = ::setStatusText,
            composerSourceCoordinator = composerSourceCoordinator,
        )
    }
    private val documentWorkspaceViewAdapter by lazy(LazyThreadSafetyMode.NONE) {
        SpecWorkflowDocumentWorkspaceViewAdapter(
            ui = documentWorkspaceViewChrome.uiHost,
            selectedView = { selectedDocumentWorkspaceView },
            selectedStructuredTaskId = { selectedStructuredTaskId },
            supportsStructuredTasksDocumentWorkspaceView = ::supportsStructuredTasksDocumentWorkspaceView,
        )
    }
    private val workflowWorkspaceStateHost by lazy(LazyThreadSafetyMode.NONE) {
        SpecWorkflowWorkspaceStateApplicationHost(
            workspaceUi = object : SpecWorkflowWorkspaceStateApplicationUi {
                override fun showWorkspaceContent() {
                    workspaceChromeUiHost.showWorkspaceContent()
                }

                override fun updateOverview(
                    overviewState: SpecWorkflowOverviewState,
                    workbenchState: SpecWorkflowStageWorkbenchState,
                ) {
                    overviewPanel.updateOverview(
                        state = overviewState,
                        workbenchState = workbenchState,
                    )
                }

                override fun applySummary(presentation: SpecWorkflowWorkspaceSummaryPresentation) {
                    workspacePresentationUiHost.applySummary(presentation)
                }

                override fun updateWorkbenchState(
                    state: SpecWorkflowStageWorkbenchState,
                    syncSelection: Boolean,
                ) {
                    detailPanel.updateWorkbenchState(
                        state = state,
                        syncSelection = syncSelection,
                    )
                }

                override fun syncStructuredTaskSelection(taskId: String) {
                    this@SpecWorkflowPanel.syncStructuredTaskSelection(taskId)
                }

                override fun updateDocumentWorkspaceViewPresentation(workbenchState: SpecWorkflowStageWorkbenchState) {
                    documentWorkspaceViewAdapter.updatePresentation(workbenchState)
                }

                override fun applyWorkspaceSectionPresentation(
                    summaries: SpecWorkflowWorkspaceSectionSummaries,
                    visibleSectionIds: Set<SpecWorkflowWorkspaceSectionId>,
                    expandedStates: Map<SpecWorkflowWorkspaceSectionId, Boolean>,
                ) {
                    workspacePresentationUiHost.applyWorkspaceSectionPresentation(
                        summaries = summaries,
                        visibleSectionIds = visibleSectionIds,
                        expandedStates = expandedStates,
                    )
                }
            },
            workspacePresentationTelemetry = workspacePresentationTelemetry,
            resolveWorkbenchState = workbenchArtifactBindingResolver::resolve,
            supportsStructuredTasksDocumentWorkspaceView = ::supportsStructuredTasksDocumentWorkspaceView,
        )
    }
    private val workflowWorkspaceEmptyStateAdapter by lazy(LazyThreadSafetyMode.NONE) {
        SpecWorkflowWorkspaceEmptyStateAdapter(
            ui = object : SpecWorkflowWorkspaceEmptyStateUi {
                override fun showWorkflowListOnlyMode() {
                    workspaceChromeUiHost.showWorkflowListOnlyMode()
                }

                override fun setBackToListEnabled(enabled: Boolean) {
                    workspaceChromeUiHost.setBackToListEnabled(enabled)
                }

                override fun showWorkspaceEmptyCard() {
                    workspaceChromeUiHost.showWorkspaceEmptyCard()
                }

                override fun clearWorkspaceState() {
                    workflowWorkspaceStateHost.clear()
                }

                override fun stopLiveProgressRefresh() {
                    liveProgressRefreshTimer.stop()
                }

                override fun clearFocusedStage() {
                    focusedStage = null
                }

                override fun clearWorkspaceSummary() {
                    workspacePresentationUiHost.clearSummary()
                }

                override fun resetWorkspaceSections() {
                    workspacePresentationUiHost.resetWorkspaceSections()
                }

                override fun showAllWorkspaceSections() {
                    workspacePresentationUiHost.showAllWorkspaceSections()
                }

                override fun resetDocumentWorkspaceView() {
                    documentWorkspaceViewAdapter.updatePresentation(null)
                }
            },
        )
    }
    private val openedWorkflowResetHost by lazy(LazyThreadSafetyMode.NONE) {
        SpecWorkflowOpenedWorkflowResetHost(
            workflowPanelState = workflowPanelState,
            ui = object : SpecWorkflowOpenedWorkflowResetUi {
                override fun clearCurrentWorkflow() {
                    currentWorkflow = null
                }

                override fun clearCurrentWorkflowSources() {
                    workflowDetailStateHost.clearCurrentWorkflowSources()
                }

                override fun resetWorkflowViewsToEmpty() {
                    phaseIndicator.reset()
                    overviewPanel.showEmpty()
                    tasksPanel.showEmpty()
                    detailTasksPanel.showEmpty()
                    gateDetailsPanel.showEmpty()
                    verifyDeltaPanel.showEmpty()
                    detailPanel.showEmpty()
                }

                override fun applyToolbarActionAvailability(availability: SpecWorkflowToolbarActionAvailability) {
                    this@SpecWorkflowPanel.applyToolbarActionAvailability(availability)
                }

                override fun clearWorkflowListHighlight() {
                    highlightedWorkflowId = null
                    listPanel.setSelectedWorkflow(null)
                }

                override fun showWorkspaceEmptyState() {
                    workflowWorkspaceEmptyStateAdapter.showEmptyState()
                }
            },
        )
    }
    private val workflowSwitcherUiHost by lazy(LazyThreadSafetyMode.NONE) {
        SpecWorkflowSwitcherUiHost(switchWorkflowButton)
    }
    private val workflowStateApplicationUi by lazy(LazyThreadSafetyMode.NONE) {
        SpecWorkflowPanelStateApplicationUiFacade(
            workflowPanelState = workflowPanelState,
            panels = SpecWorkflowStateApplicationUiPanels(
                listPanel = listPanel,
                phaseIndicator = phaseIndicator,
                overviewPanel = overviewPanel,
                verifyDeltaPanel = verifyDeltaPanel,
                tasksPanel = tasksPanel,
                detailTasksPanel = detailTasksPanel,
                gateDetailsPanel = gateDetailsPanel,
                detailPanel = detailPanel,
            ),
            buttons = SpecWorkflowStateApplicationUiButtons(
                switchWorkflowButton = switchWorkflowButton,
                createWorktreeButton = createWorktreeButton,
                mergeWorktreeButton = mergeWorktreeButton,
                deltaButton = deltaButton,
                archiveButton = archiveButton,
            ),
            onCancelWorkflowSwitcherPopup = {
                workflowSwitcherUiHost.cancel()
            },
            updateStatusText = ::setStatusText,
            onLoadWorkflow = { workflowId ->
                workflowLoadEntryCoordinator.selectWorkflow(workflowId)
            },
            onClearOpenedWorkflowUi = ::clearOpenedWorkflowUi,
            setCurrentWorkflow = { workflow ->
                currentWorkflow = workflow
            },
            syncClarificationRetryFromWorkflow = ::syncClarificationRetryFromWorkflow,
            detailStateHost = workflowDetailStateHost,
            updateWorkspacePresentation = ::updateWorkspacePresentation,
            onRestorePendingClarificationState = ::restorePendingClarificationState,
            onApplyPendingOpenWorkflowRequest = { workflowId ->
                workflowLoadEntryCoordinator.applyPendingOpenWorkflowRequestIfNeeded(workflowId)
            },
            showWorkspaceContent = { workspaceChromeUiHost.showWorkspaceContent() },
        )
    }
    private val workflowStateApplicationAdapter by lazy(LazyThreadSafetyMode.NONE) {
        SpecWorkflowStateApplicationAdapter(
            ui = workflowStateApplicationUi,
        )
    }
    private val workflowLoadExecutionCoordinator = SpecWorkflowLoadExecutionCoordinator(
        loadCoordinator = loadCoordinator,
        showLoadInProgress = {
            invokeLaterSafe {
                showWorkflowLoadInProgress()
            }
        },
        launchLoad = { loadAction ->
            taskCoordinator.launchIo {
                loadAction()
            }
        },
        applyLoadedWorkflow = ::applyLoadedWorkflow,
    )
    private val workflowNavigationCoordinator = SpecWorkflowNavigationCoordinator()
    private val workflowLoadEntryCoordinator = SpecWorkflowLoadEntryCoordinator(
        panelState = workflowPanelState,
        navigationCoordinator = workflowNavigationCoordinator,
        requestWorkflowLoad = workflowLoadExecutionCoordinator::requestWorkflowLoad,
        refreshWorkflows = { workflowId -> refreshWorkflows(selectWorkflowId = workflowId) },
        applyOpenRequestToCurrentWorkflow = ::applyOpenRequestToCurrentWorkflow,
    )
    private val workflowSelectionCoordinator = SpecWorkflowSelectionCoordinator()
    private val workflowListActionCoordinator = SpecWorkflowListActionCoordinator(
        currentItems = { listPanel.currentItems() },
        selectedWorkflowId = { selectedWorkflowId },
        highlightedWorkflowId = { highlightedWorkflowId },
        showWorkflowSwitcher = { request ->
            workflowSwitcherUiHost.show(request)
        },
        cancelWorkflowSwitcher = {
            workflowSwitcherUiHost.cancel()
        },
        selectionCoordinator = workflowSelectionCoordinator,
        openWorkflow = ::onWorkflowOpenedByUser,
        editWorkflow = ::onEditWorkflow,
        deleteWorkflow = specEngine::deleteWorkflow,
        launchDeleteInBackground = { task ->
            taskCoordinator.launchIo {
                task()
            }
        },
        invokeLater = ::invokeLaterSafe,
        onDeleteSuccess = { workflowId, refreshTarget ->
            if (pendingOpenWorkflowRequest?.workflowId == workflowId) {
                pendingOpenWorkflowRequest = null
            }
            refreshWorkflows(
                selectWorkflowId = refreshTarget.selectWorkflowId,
                preserveListMode = refreshTarget.preserveListMode,
            )
        },
        onDeleteFailure = { error ->
            val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
            setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
        },
    )
    private val workflowListRefreshCoordinator = SpecWorkflowListRefreshCoordinator(
        listWorkflowMetadata = specEngine::listWorkflowMetadata,
        stageLabel = SpecWorkflowOverviewPresenter::stageLabel,
        selectionCoordinator = workflowSelectionCoordinator,
    )
    private val workflowListRefreshExecutionCoordinator = SpecWorkflowListRefreshExecutionCoordinator(
        loadRefreshState = workflowListRefreshCoordinator::load,
        selectedWorkflowId = { selectedWorkflowId },
        highlightedWorkflowId = { highlightedWorkflowId },
        launchRefreshLoad = { loadAction, onLoaded ->
            taskCoordinator.launchIo {
                val loadedState = loadAction()
                invokeLaterSafe {
                    onLoaded(loadedState)
                }
            }
        },
        applyLoadedState = { request ->
            workflowListRefreshCoordinator.apply(request, workflowStateApplicationAdapter.listRefreshCallbacks)
        },
        showRefreshFeedback = {
            val successText = SpecCodingBundle.message("common.refresh.success")
            RefreshFeedback.flashButtonSuccess(refreshButton, successText)
            RefreshFeedback.flashLabelSuccess(statusStripUiHost.statusLabel, successText, STATUS_SUCCESS_FG)
        },
    )
    private val workflowExternalEventCoordinator = SpecWorkflowExternalEventCoordinator(SPEC_DOCUMENT_FILE_NAMES)
    private val workflowExternalEventActionCoordinator = SpecWorkflowExternalEventActionCoordinator(
        documentReloadCoordinator = SpecWorkflowDocumentReloadCoordinator(
            debounceMillis = DOCUMENT_RELOAD_DEBOUNCE_MILLIS,
            scheduleDebounced = { delayMillis, action ->
                val job = taskCoordinator.launchDefault {
                    delay(delayMillis)
                    action()
                }
                SpecWorkflowDocumentReloadHandle { job.cancel() }
            },
        ),
        isDisposed = { project.isDisposed || _isDisposed },
        selectedWorkflowId = { selectedWorkflowId },
        createWorkflow = ::onCreateWorkflow,
        openWorkflow = ::openWorkflowFromRequest,
        refreshWorkflows = { workflowId -> refreshWorkflows(selectWorkflowId = workflowId) },
        reloadCurrentWorkflow = ::reloadCurrentWorkflow,
    )
    private val workflowExternalEventSubscriptionAdapter = SpecWorkflowExternalEventSubscriptionAdapter(
        subscriber = MessageBusSpecWorkflowExternalEventSubscriber(project, this),
        externalEventCoordinator = workflowExternalEventCoordinator,
        selectedWorkflowId = { selectedWorkflowId },
        projectBasePath = { project.basePath },
        invokeLater = ::invokeLaterSafe,
        isDisposed = { project.isDisposed || _isDisposed },
        onAction = workflowExternalEventActionCoordinator::handle,
    )
    private val composerSourceCoordinator = SpecWorkflowComposerSourceCoordinator(
        sourceImportConstraints = sourceImportConstraints,
        runBackground = { request ->
            SpecWorkflowActionSupport.runBackground(
                project = project,
                title = request.title,
                task = {
                    request.task()
                },
                onSuccess = { importedAssets ->
                    request.onSuccess(importedAssets)
                },
                onFailure = { error ->
                    request.onFailure(error)
                },
            )
        },
        importWorkflowSource = { workflowId, importedFromStage, importedFromEntry, sourcePath ->
            specEngine.importWorkflowSource(
                workflowId = workflowId,
                importedFromStage = importedFromStage,
                importedFromEntry = importedFromEntry,
                sourcePath = sourcePath,
            )
        },
        renderFailureMessage = { error ->
            compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
        },
    )
    private val taskMutationCoordinator = SpecWorkflowTaskMutationCoordinator(
        runBackground = { request ->
            SpecWorkflowActionSupport.runBackground(
                project = project,
                title = request.title,
                task = {
                    request.task()
                    Unit
                },
                onSuccess = {
                    request.onSuccess()
                },
            )
        },
        applyStatusTransition = { workflowId, taskId, to, auditContext ->
            specTasksService.transitionStatus(
                workflowId = workflowId,
                taskId = taskId,
                to = to,
                auditContext = auditContext,
            )
        },
        persistDependsOn = { workflowId, taskId, dependsOn ->
            specTasksService.updateDependsOn(
                workflowId = workflowId,
                taskId = taskId,
                dependsOn = dependsOn,
            )
        },
        applyTaskCompletion = { workflowId, taskId, relatedFiles, verificationResult, auditContext ->
            specTaskCompletionService.completeTask(
                workflowId = workflowId,
                taskId = taskId,
                relatedFiles = relatedFiles,
                verificationResult = verificationResult,
                auditContext = auditContext,
                completionRunSummary = "Completed from spec workflow task action.",
            )
        },
        storeVerificationResult = { workflowId, taskId, verificationResult, auditContext ->
            specTasksService.updateVerificationResult(
                workflowId = workflowId,
                taskId = taskId,
                verificationResult = verificationResult,
                auditContext = auditContext,
            )
        },
        removeVerificationResult = { workflowId, taskId, auditContext ->
            specTasksService.clearVerificationResult(
                workflowId = workflowId,
                taskId = taskId,
                auditContext = auditContext,
            )
        },
        setStatusText = ::setStatusText,
        publishWorkflowChatRefresh = { workflowId, taskId, reason ->
            publishWorkflowChatRefresh(workflowId, taskId, reason)
        },
        reloadCurrentWorkflow = {
            reloadCurrentWorkflow()
        },
    )
    private val taskChatCoordinator = SpecWorkflowTaskChatCoordinator(
        activateChatToolWindow = ::activateChatToolWindow,
        invokeLater = { action ->
            invokeLaterSafe(action)
        },
        isDisposed = {
            project.isDisposed || _isDisposed
        },
        openHistorySession = { sessionId ->
            project.messageBus.syncPublisher(HistorySessionOpenListener.TOPIC)
                .onSessionOpenRequested(sessionId)
        },
        publishWorkflowChatRefresh = { workflowId, taskId, reason ->
            publishWorkflowChatRefresh(workflowId, taskId, reason)
        },
        openWorkflowChat = { request ->
            project.messageBus.syncPublisher(ChatToolWindowControlListener.TOPIC)
                .onOpenWorkflowChatRequested(request)
        },
    )
    private val taskExecutionCoordinator = SpecWorkflowTaskExecutionCoordinator(
        backgroundRunner = object : SpecWorkflowTaskExecutionBackgroundRunner {
            override fun <T> run(request: SpecWorkflowTaskExecutionBackgroundRequest<T>) {
                val onFailure = request.onFailure
                if (onFailure != null) {
                    SpecWorkflowActionSupport.runBackground(
                        project = project,
                        title = request.title,
                        task = request.task,
                        onSuccess = request.onSuccess,
                        onCancelRequested = request.onCancelRequested,
                        onCancelled = request.onCancelled,
                        onFailure = onFailure,
                    )
                } else {
                    SpecWorkflowActionSupport.runBackground(
                        project = project,
                        title = request.title,
                        task = request.task,
                        onSuccess = request.onSuccess,
                        onCancelRequested = request.onCancelRequested,
                        onCancelled = request.onCancelled,
                    )
                }
            }
        },
        listRuns = { workflowId, taskId ->
            specTaskExecutionService.listRuns(workflowId, taskId)
        },
        startExecution = { request, onRequestRegistered ->
            specTaskExecutionService.startAiExecution(
                workflowId = request.workflowId,
                taskId = request.taskId,
                providerId = request.providerId,
                modelId = request.modelId,
                operationMode = request.operationMode,
                sessionId = request.sessionId,
                auditContext = request.auditContext,
                onRequestRegistered = onRequestRegistered,
            )
        },
        retryExecution = { request, previousRunId, onRequestRegistered ->
            specTaskExecutionService.retryAiExecution(
                workflowId = request.workflowId,
                taskId = request.taskId,
                providerId = request.providerId,
                modelId = request.modelId,
                operationMode = request.operationMode,
                previousRunId = previousRunId,
                sessionId = request.sessionId,
                auditContext = request.auditContext,
                onRequestRegistered = onRequestRegistered,
            )
        },
        cancelExecutionRun = { workflowId, runId ->
            specTaskExecutionService.cancelExecutionRun(
                workflowId = workflowId,
                runId = runId,
            )
        },
        cancelExecution = { workflowId, taskId ->
            specTaskExecutionService.cancelExecution(
                workflowId = workflowId,
                taskId = taskId,
            )
        },
        openWorkflowChatExecutionSession = { sessionId, workflowId ->
            taskChatCoordinator.openExecutionSession(sessionId, workflowId)
        },
        setStatusText = ::setStatusText,
        showFailureStatus = ::setStatusWithTroubleshooting,
        setCancelRequestedStatusText = { text ->
            invokeLaterSafe {
                setStatusText(text)
            }
        },
        publishWorkflowChatRefresh = { workflowId, taskId, reason ->
            publishWorkflowChatRefresh(workflowId, taskId, reason)
        },
        reloadCurrentWorkflow = {
            reloadCurrentWorkflow()
        },
        buildRuntimeTroubleshootingActions = runtimeTroubleshootingActionBuilder::build,
        renderFailureMessage = { error, fallback ->
            compactErrorMessage(error, fallback)
        },
        showExecutionFailureDialog = { title, message ->
            Messages.showErrorDialog(project, message, title)
        },
    )
    private val taskExecutionEntryCoordinator = SpecWorkflowTaskExecutionEntryCoordinator(
        activeSessionId = {
            sessionIsolationService.activeSessionId()
        },
        findReusableWorkflowChatSessionId = { workflowId, preferredSessionId ->
            sessionManager.findReusableWorkflowChatSession(
                workflowId = workflowId,
                preferredSessionId = preferredSessionId,
            )?.id
        },
        providerDisplayName = ::providerDisplayName,
        setStatusText = ::setStatusText,
        showFailureStatus = ::setStatusWithTroubleshooting,
        buildRuntimeTroubleshootingActions = runtimeTroubleshootingActionBuilder::build,
        execute = taskExecutionCoordinator::execute,
    )
    private val verifyDeltaCoordinator = SpecWorkflowVerifyDeltaCoordinator(
        runVerificationWorkflow = { workflowId, onCompleted, onFailure ->
            SpecWorkflowActionSupport.runVerificationWorkflow(
                project = project,
                verificationService = specVerificationService,
                tasksService = specTasksService,
                workflowId = workflowId,
                onCompleted = { result ->
                    onCompleted(result.summary)
                },
                onFailure = onFailure,
            )
        },
        locateArtifact = { workflowId, stageId ->
            artifactService.locateArtifact(workflowId, stageId)
        },
        openFile = { path ->
            SpecWorkflowActionSupport.openFile(project, path)
        },
        runIo = { action ->
            taskCoordinator.launchIo {
                action()
            }
        },
        invokeLater = { action ->
            invokeLaterSafe(action)
        },
        compareByWorkflowId = { baselineWorkflowId, targetWorkflowId ->
            specDeltaService.compareByWorkflowId(
                baselineWorkflowId = baselineWorkflowId,
                targetWorkflowId = targetWorkflowId,
            )
        },
        compareByDeltaBaseline = { workflowId, baselineId ->
            specDeltaService.compareByDeltaBaseline(
                workflowId = workflowId,
                baselineId = baselineId,
            )
        },
        listWorkflowSnapshots = { workflowId ->
            specEngine.listWorkflowSnapshots(workflowId)
        },
        pinDeltaBaseline = { workflowId, snapshotId, label ->
            specEngine.pinDeltaBaseline(
                workflowId = workflowId,
                snapshotId = snapshotId,
                label = label,
            )
        },
        showDeltaDialog = workflowDeltaCoordinator::showComparisonResult,
        reloadCurrentWorkflow = {
            reloadCurrentWorkflow()
        },
        setStatusText = ::setStatusText,
        showFailureStatus = ::setStatusWithTroubleshooting,
        buildRuntimeTroubleshootingActions = runtimeTroubleshootingActionBuilder::build,
        renderFailureMessage = { error ->
            compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
        },
    )
    private val generationCoordinator = SpecWorkflowGenerationCoordinator(
        providerDisplayName = ::providerDisplayName,
        renderFailureMessage = { error, fallback ->
            compactErrorMessage(error, fallback)
        },
    )
    private val generationExecutionCoordinator = SpecWorkflowGenerationExecutionCoordinator(
        backgroundRunner = object : SpecWorkflowGenerationExecutionBackgroundRunner {
            override fun launch(task: suspend () -> Unit): SpecWorkflowGenerationExecutionHandle {
                val job = taskCoordinator.launchIo {
                    task()
                }
                return SpecWorkflowGenerationExecutionHandle { reason ->
                    job.cancel(CancellationException(reason))
                }
            }
        },
        ui = object : SpecWorkflowGenerationExecutionUi {
            override fun invokeLater(action: () -> Unit) {
                invokeLaterSafe(action)
            }

            override fun isWorkflowSelected(workflowId: String): Boolean {
                return selectedWorkflowId == workflowId
            }

            override fun showClarificationGenerating(prepared: SpecWorkflowPreparedClarificationDraftRequest) {
                detailPanel.showClarificationGenerating(
                    phase = prepared.context.phase,
                    input = prepared.input,
                    suggestedDetails = prepared.safeSuggestedDetails,
                )
                appendProcessTimelineEntries(prepared.initialTimelineEntries)
                setStatusText(prepared.loadingStatusText)
            }

            override fun applyClarificationDraftResult(
                prepared: SpecWorkflowPreparedClarificationDraftRequest,
                result: SpecWorkflowClarificationDraftResult,
            ) {
                detailPanel.showClarificationDraft(
                    phase = result.phase,
                    input = prepared.input,
                    questionsMarkdown = result.questionsMarkdown,
                    suggestedDetails = prepared.safeSuggestedDetails,
                    structuredQuestions = result.structuredQuestions,
                )
                rememberClarificationRetry(
                    workflowId = prepared.context.workflowId,
                    input = prepared.input,
                    confirmedContext = prepared.safeSuggestedDetails,
                    questionsMarkdown = result.questionsMarkdown,
                    structuredQuestions = result.structuredQuestions,
                    clarificationRound = prepared.clarificationRound,
                    lastError = result.errorText,
                )
                appendProcessTimelineEntries(listOf(result.timelineEntry))
                if (result.troubleshootingTrigger != null) {
                    setRuntimeTroubleshootingStatus(
                        prepared.context.workflowId,
                        result.statusText,
                        result.troubleshootingTrigger,
                    )
                } else {
                    setStatusText(result.statusText)
                }
            }

            override fun applyGenerationProgress(
                workflowId: String,
                input: String,
                update: SpecWorkflowGenerationProgressUpdate,
            ) {
                applyGenerationProgressUpdate(
                    workflowId = workflowId,
                    input = input,
                    update = update,
                )
            }

            override fun handleClarificationInterrupted(
                workflowId: String,
                input: String,
                options: GenerationOptions,
            ) {
                val interruptedMessage = SpecCodingBundle.message("spec.workflow.generation.interrupted")
                if (selectedWorkflowId != workflowId) {
                    return
                }
                detailPanel.appendProcessTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.clarify.failed", interruptedMessage),
                    state = SpecDetailPanel.ProcessTimelineState.FAILED,
                )
                rememberClarificationRetry(
                    workflowId = workflowId,
                    input = input,
                    confirmedContext = options.confirmedContext,
                    clarificationRound = clarificationRetryStore.current(workflowId)?.clarificationRound,
                    lastError = interruptedMessage,
                )
                detailPanel.showGenerationFailed()
                setStatusText(SpecCodingBundle.message("spec.workflow.error", interruptedMessage))
            }
        },
        generationCoordinator = generationCoordinator,
        draftClarification = { workflowId, input, options ->
            specEngine.draftCurrentPhaseClarification(
                workflowId = workflowId,
                input = input,
                options = options,
            )
        },
        generateCurrentPhase = specEngine::generateCurrentPhase,
        cancelRequestAcrossProviders = { providerId, requestId ->
            llmRouter.cancel(providerId = providerId, requestId = requestId)
            llmRouter.cancel(providerId = ClaudeCliLlmProvider.ID, requestId = requestId)
            llmRouter.cancel(providerId = CodexCliLlmProvider.ID, requestId = requestId)
        },
        logClarificationDraftFailure = { workflowId, error ->
            logger.warn("Failed to draft clarification for workflow=$workflowId", error)
        },
    )
    private val clarificationRetryStore = SpecWorkflowClarificationRetryStore(
        persistState = { workflowId, state ->
            taskCoordinator.launchIo {
                specEngine.saveClarificationRetryState(
                    workflowId = workflowId,
                    state = state,
                ).onFailure { error ->
                    logger.warn("Failed to persist clarification retry state for workflow=$workflowId", error)
                }
            }
        },
    )
    private val gateRequirementsRepairCoordinator = SpecWorkflowGateRequirementsRepairCoordinator(
        aiUnavailableReason = { providerHint ->
            RequirementsSectionAiSupport.unavailableReason(providerHint = providerHint)
        },
        locateRequirementsArtifact = { workflowId ->
            artifactService.locateArtifact(workflowId, StageId.REQUIREMENTS)
        },
        renderClarificationFailureMarkdown = { error ->
            generationCoordinator.buildClarificationMarkdown(
                draft = null,
                error = error,
            )
        },
    )
    private val requirementsRepairClarificationCoordinator = SpecWorkflowRequirementsRepairClarificationCoordinator(
        retryStore = clarificationRetryStore,
        gateRequirementsRepairCoordinator = gateRequirementsRepairCoordinator,
        resolveProviderId = { providerComboBox.selectedItem as? String },
        resolveModelId = { (modelComboBox.selectedItem as? ModelInfo)?.id },
        resolveWorkflowSourceUsage = ::resolveComposerSourceUsage,
        requestClarificationDraft = generationExecutionCoordinator::requestClarificationDraft,
        previewAndApply = { request ->
            RequirementsSectionRepairUiSupport.previewAndApply(
                project = project,
                workflowId = request.workflowId,
                missingSections = request.missingSections,
                confirmedContextOverride = request.confirmedContextOverride,
                previewTitle = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.progress.preview"),
                applyTitle = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.progress.apply"),
                onPreviewCancelled = request.onPreviewCancelled,
                onNoop = request.onNoop,
                onApplied = {
                    request.onApplied()
                },
                onFailure = request.onFailure,
            )
        },
        appendTimelineEntry = { entry ->
            appendProcessTimelineEntries(listOf(entry))
        },
        showClarificationManualFallback = { fallback ->
            detailPanel.showClarificationDraft(
                phase = fallback.phase,
                input = fallback.input,
                questionsMarkdown = fallback.questionsMarkdown,
                suggestedDetails = fallback.suggestedDetails,
                structuredQuestions = emptyList(),
            )
        },
        setStatusText = ::setStatusText,
        unlockClarificationChecklistInteractions = {
            detailPanel.unlockClarificationChecklistInteractions()
        },
        exitClarificationMode = { clearInput ->
            detailPanel.exitClarificationMode(clearInput = clearInput)
        },
        reloadRequirementsWorkflow = { focusRequirements ->
            reloadCurrentWorkflow(followCurrentPhase = true) {
                if (focusRequirements) {
                    focusStage(StageId.REQUIREMENTS)
                }
            }
        },
        openRequirementsDocument = { path ->
            runCatching {
                SpecWorkflowActionSupport.openFile(project, path)
            }
        },
        showInfo = { title, message ->
            SpecWorkflowActionSupport.showInfo(
                project,
                title,
                message,
            )
        },
        renderFailureMessage = { error ->
            compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
        },
    )
    private val requirementsRepairEntryCoordinator = SpecWorkflowRequirementsRepairEntryCoordinator(
        retryStore = clarificationRetryStore,
        resolveWorkflow = { workflowId ->
            currentWorkflow?.takeIf { workflow -> workflow.id == workflowId }
        },
        gateRequirementsRepairCoordinator = gateRequirementsRepairCoordinator,
        showWorkspaceContent = {
            workspaceChromeUiHost.showWorkspaceContent()
        },
        focusStage = ::focusStage,
        clearProcessTimeline = {
            detailPanel.clearProcessTimeline()
        },
        appendTimelineEntry = { entry ->
            appendProcessTimelineEntries(listOf(entry))
        },
        launchClarification = requirementsRepairClarificationCoordinator::launchClarification,
    )
    private val clarificationActionCoordinator = SpecWorkflowClarificationActionCoordinator(
        retryStore = clarificationRetryStore,
        resolveSelectedWorkflow = ::resolveSelectedWorkflowForClarification,
        resolveGenerationContext = ::resolveGenerationContext,
        selectedWorkflowId = { selectedWorkflowId },
        currentWorkflow = { currentWorkflow },
        appendTimelineEntry = { entry ->
            appendProcessTimelineEntries(listOf(entry))
        },
        setStatusText = ::setStatusText,
        unlockClarificationChecklistInteractions = {
            detailPanel.unlockClarificationChecklistInteractions()
        },
        cancelActiveGenerationRequest = generationExecutionCoordinator::cancelActiveRequest,
        requestClarificationDraft = generationExecutionCoordinator::requestClarificationDraft,
        runGeneration = { request ->
            generationExecutionCoordinator.runGeneration(
                SpecWorkflowGenerationExecutionRequest(
                    workflowId = request.workflowId,
                    phase = currentWorkflow?.currentPhase ?: SpecPhase.SPECIFY,
                    input = request.input,
                    options = request.options,
                ),
            )
        },
        launchRequirementsRepairClarification = requirementsRepairClarificationCoordinator::launchClarification,
        continueRequirementsRepairAfterClarification = requirementsRepairClarificationCoordinator::continueAfterClarification,
    )
    private val generationLaunchCoordinator = SpecWorkflowGenerationLaunchCoordinator(
        retryStore = clarificationRetryStore,
        resolveSelectedWorkflow = ::resolveSelectedWorkflowForClarification,
        resolveGenerationContext = ::resolveGenerationContext,
        clearProcessTimeline = {
            detailPanel.clearProcessTimeline()
        },
        appendTimelineEntry = { entry ->
            appendProcessTimelineEntries(listOf(entry))
        },
        generationCoordinator = generationCoordinator,
        gateRequirementsRepairCoordinator = gateRequirementsRepairCoordinator,
        runGeneration = generationExecutionCoordinator::runGeneration,
        requestClarificationDraft = generationExecutionCoordinator::requestClarificationDraft,
        launchRequirementsRepairClarification = requirementsRepairClarificationCoordinator::launchClarification,
        continueRequirementsRepairAfterClarification = requirementsRepairClarificationCoordinator::continueAfterClarification,
    )
    private val gateArtifactRepairCoordinator = SpecWorkflowGateArtifactRepairCoordinator(
        backgroundRunner = object : SpecWorkflowGateArtifactRepairBackgroundRunner {
            override fun <T> run(request: SpecWorkflowGateArtifactRepairBackgroundRequest<T>) {
                SpecWorkflowActionSupport.runBackground(
                    project = project,
                    title = request.title,
                    task = request.task,
                    onSuccess = request.onSuccess,
                )
            }
        },
        runTasksRepair = { workflowId, trigger ->
            specTasksQuickFixService.repairTasksArtifact(
                workflowId = workflowId,
                trigger = trigger,
            )
        },
        runRequirementsRepair = { workflowId, trigger ->
            specRequirementsQuickFixService.repairRequirementsArtifact(
                workflowId = workflowId,
                trigger = trigger,
            )
        },
        rememberWorkflow = { workflowId ->
            SpecWorkflowActionSupport.rememberWorkflow(project, workflowId)
        },
        showInfo = { title, message ->
            SpecWorkflowActionSupport.showInfo(
                project = project,
                title = title,
                message = message,
            )
        },
        notifySuccess = { message ->
            SpecWorkflowActionSupport.notifySuccess(
                project = project,
                message = message,
            )
        },
        openFile = { path, line ->
            if (line == null) {
                SpecWorkflowActionSupport.openFile(project, path)
            } else {
                SpecWorkflowActionSupport.openFile(project, path, line)
            }
        },
        refreshWorkflows = { workflowId ->
            refreshWorkflows(selectWorkflowId = workflowId)
        },
    )
    private val worktreeCoordinator = SpecWorkflowWorktreeCoordinator(
        runIo = { action ->
            taskCoordinator.launchIo {
                action()
            }
        },
        invokeLater = { action ->
            invokeLaterSafe(action)
        },
        createWorktree = { specTaskId, shortName, baseBranch ->
            worktreeManager.createWorktree(specTaskId, shortName, baseBranch)
        },
        switchWorktree = { worktreeId ->
            worktreeManager.switchWorktree(worktreeId)
        },
        listBindings = { includeInactive ->
            worktreeManager.listBindings(includeInactive = includeInactive)
        },
        mergeWorktree = { worktreeId, targetBranch ->
            worktreeManager.mergeWorktree(worktreeId, targetBranch)
        },
        renderFailureMessage = { error ->
            compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
        },
        setStatusText = ::setStatusText,
        showFailureStatus = ::setStatusWithTroubleshooting,
        buildRuntimeTroubleshootingActions = runtimeTroubleshootingActionBuilder::build,
    )
    private val worktreeUiHost = SpecWorkflowWorktreeUiHost(
        currentWorkflow = { currentWorkflow },
        suggestBaseBranch = worktreeManager::suggestBaseBranch,
        createWorktree = worktreeCoordinator::createAndSwitch,
        mergeWorktree = worktreeCoordinator::mergeIntoBaseBranch,
        setStatusText = ::setStatusText,
    )
    private val codeGraphUiHost = SpecWorkflowCodeGraphUiHost(
        buildCodeGraph = codeGraphBuilder ?: { codeGraphService.buildFromActiveEditor() },
        runBackground = { task, onResult ->
            taskCoordinator.launchDefault {
                val result = task()
                invokeLaterSafe {
                    onResult(result)
                }
            }
        },
        showDialogUi = { request ->
            codeGraphDialogPresenter(request.summary, request.mermaid)
        },
        setStatusText = ::setStatusText,
    )
    private val stageTransitionCoordinator = SpecWorkflowStageTransitionCoordinator(
        backgroundRunner = object : SpecWorkflowStageTransitionBackgroundRunner {
            override fun <T> run(request: SpecWorkflowStageTransitionBackgroundRequest<T>) {
                SpecWorkflowActionSupport.runBackground(
                    project = project,
                    title = request.title,
                    task = request.task,
                    onSuccess = request.onSuccess,
                )
            }
        },
        previewStageTransition = { workflowId, transitionType, targetStage ->
            specEngine.previewStageTransition(
                workflowId = workflowId,
                transitionType = transitionType,
                targetStage = targetStage,
            )
        },
        advanceWorkflow = { workflowId ->
            specEngine.advanceWorkflow(workflowId) { true }
        },
        jumpToStage = { workflowId, targetStage ->
            specEngine.jumpToStage(workflowId, targetStage) { true }
        },
        rollbackToStage = { workflowId, targetStage ->
            specEngine.rollbackToStage(workflowId, targetStage)
        },
        showGateBlocked = { workflowId, gateResult ->
            SpecWorkflowActionSupport.showGateBlocked(project, workflowId, gateResult)
        },
        confirmWarnings = { workflowId, gateResult ->
            SpecWorkflowActionSupport.confirmWarnings(project, workflowId, gateResult)
        },
        onTransitionCompleted = ::handleStageTransitionCompleted,
    )
    private val workflowLifecycleCoordinator = SpecWorkflowLifecycleCoordinator(
        backgroundRunner = object : SpecWorkflowLifecycleBackgroundRunner {
            override fun <T> run(request: SpecWorkflowLifecycleBackgroundRequest<T>) {
                taskCoordinator.launchIo {
                    runCatching { request.task() }
                        .onSuccess { outcome ->
                            invokeLaterSafe {
                                request.onSuccess(outcome)
                            }
                        }
                        .onFailure { error ->
                            invokeLaterSafe {
                                request.onFailure(error)
                            }
                        }
                }
            }
        },
        completeWorkflow = specEngine::completeWorkflow,
        pauseWorkflow = specEngine::pauseWorkflow,
        resumeWorkflow = specEngine::resumeWorkflow,
        archiveWorkflow = specEngine::archiveWorkflow,
        confirmArchive = { workflowId ->
            confirmArchiveUi(project, workflowId)
        },
        reloadCurrentWorkflow = {
            reloadCurrentWorkflow()
        },
        refreshWorkflows = {
            refreshWorkflows()
        },
        clearOpenedWorkflowIfSelected = { workflowId ->
            if (selectedWorkflowId == workflowId) {
                clearOpenedWorkflowUi(resetHighlight = false)
            }
        },
        setStatusText = ::setStatusText,
        renderFailureMessage = { error ->
            compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
        },
    )
    private val workflowLifecycleUiHost = SpecWorkflowLifecycleUiHost(
        selectedWorkflowId = { selectedWorkflowId },
        currentWorkflow = { currentWorkflow },
        completeWorkflow = workflowLifecycleCoordinator::complete,
        togglePauseResume = workflowLifecycleCoordinator::togglePauseResume,
        archiveWorkflow = workflowLifecycleCoordinator::archive,
    )
    private val discoveryListener: () -> Unit = {
        llmRouter.refreshProviders()
        modelRegistry.refreshFromDiscovery()
        invokeLaterSafe {
            refreshProviderCombo(preserveSelection = true)
        }
    }

    @Volatile
    private var _isDisposed = false

    private val phaseIndicator = SpecPhaseIndicatorPanel()
    private val listPanel: SpecWorkflowListPanel
    private val detailPanel: SpecDetailPanel
    private val overviewPanel = SpecWorkflowOverviewPanel(
        onStageSelected = ::onOverviewStageSelected,
        onWorkbenchActionRequested = ::onWorkbenchActionRequested,
        onTemplateCloneRequested = workflowTemplateCloneCoordinator::requestClone,
    )
    private val tasksPanel = SpecWorkflowTasksPanel(
        onTransitionStatus = ::onTaskStatusTransitionRequested,
        onCancelExecution = ::onTaskExecutionCancelRequested,
        onExecuteTask = ::onTaskExecutionRequested,
        onOpenWorkflowChat = taskChatCoordinator::openTaskWorkflowChat,
        onUpdateDependsOn = ::onTaskDependsOnUpdateRequested,
        onCompleteWithRelatedFiles = ::onTaskCompleteRequested,
        onUpdateVerificationResult = ::onTaskVerificationResultUpdateRequested,
        suggestRelatedFiles = { taskId, existingRelatedFiles ->
            specRelatedFilesService.suggestRelatedFiles(taskId, existingRelatedFiles)
        },
        onTaskSelected = ::onStructuredTaskSelectionChanged,
        showHeader = false,
    )
    private val detailTasksPanel = SpecWorkflowTasksPanel(
        onTransitionStatus = ::onTaskStatusTransitionRequested,
        onCancelExecution = ::onTaskExecutionCancelRequested,
        onExecuteTask = ::onTaskExecutionRequested,
        onOpenWorkflowChat = taskChatCoordinator::openTaskWorkflowChat,
        onUpdateDependsOn = ::onTaskDependsOnUpdateRequested,
        onCompleteWithRelatedFiles = ::onTaskCompleteRequested,
        onUpdateVerificationResult = ::onTaskVerificationResultUpdateRequested,
        suggestRelatedFiles = { taskId, existingRelatedFiles ->
            specRelatedFilesService.suggestRelatedFiles(taskId, existingRelatedFiles)
        },
        onTaskSelected = ::onStructuredTaskSelectionChanged,
        showHeader = false,
        fixedViewportHeight = DOCUMENT_WORKSPACE_VIEWPORT_HEIGHT,
    )
    private val workbenchStageSelectionCoordinator = SpecWorkflowWorkbenchStageSelectionCoordinator(
        workflowMetaProvider = {
            currentWorkflow?.toWorkflowMeta()
        },
        showInfo = { title, message ->
            SpecWorkflowActionSupport.showInfo(
                project = project,
                title = title,
                message = message,
            )
        },
        chooseStage = { request ->
            SpecWorkflowActionSupport.chooseStage(
                project = project,
                stages = request.stages,
                title = request.title,
                workflowMeta = request.workflowMeta,
                onChosen = request.onChosen,
            )
        },
        onJumpSelected = stageTransitionCoordinator::jump,
        onRollbackSelected = stageTransitionCoordinator::rollback,
    )
    private val workbenchActionCoordinator = SpecWorkflowWorkbenchActionCoordinator(
        onAdvance = ::onAdvanceStageRequested,
        onJump = stageTransitionCoordinator::jump,
        onJumpFallback = workbenchStageSelectionCoordinator::requestJumpSelection,
        onRollback = stageTransitionCoordinator::rollback,
        onRollbackFallback = workbenchStageSelectionCoordinator::requestRollbackSelection,
        onSelectTask = ::syncStructuredTaskSelection,
        requestTaskExecutionAction = tasksPanel::requestExecutionForTask,
        requestTaskCompletionAction = tasksPanel::requestCompletionForTask,
        onCancelTaskExecution = ::onTaskExecutionCancelRequested,
        onOpenTaskChat = taskChatCoordinator::openTaskWorkflowChat,
        onRunVerify = verifyDeltaCoordinator::runVerification,
        buildVerifyPlanPreviewSummary = { workflowId ->
            val plan = specVerificationService.preview(workflowId)
            val scopeTasks = specTasksService.parse(workflowId).sortedBy(StructuredTask::id)
            SpecWorkflowActionSupport.verificationPlanSummary(plan, scopeTasks)
        },
        runPreviewInBackground = { title, task, onSuccess ->
            SpecWorkflowActionSupport.runBackground(
                project = project,
                title = title,
                task = task,
                onSuccess = onSuccess,
            )
        },
        showPreviewSummary = { title, message ->
            SpecWorkflowActionSupport.showInfo(
                project = project,
                title = title,
                message = message,
            )
        },
        onOpenVerification = verifyDeltaCoordinator::openVerificationDocument,
        onShowDelta = {
            workflowDeltaCoordinator.show(currentWorkflow)
        },
        onCompleteWorkflow = workflowLifecycleUiHost::requestComplete,
        onArchiveWorkflow = workflowLifecycleUiHost::requestArchive,
        onShowStatus = ::setStatusText,
    )
    private val workbenchCommandRouter = SpecWorkflowWorkbenchCommandRouter(
        callbacks = workbenchActionCoordinator,
        taskExecutionFailedMessage = { taskId ->
            SpecCodingBundle.message("spec.toolwindow.tasks.execute.failed", taskId)
        },
        taskCompletionFailedMessage = { taskId ->
            SpecCodingBundle.message("spec.toolwindow.tasks.complete.failed", taskId)
        },
    )
    private val verifyDeltaPanel = SpecWorkflowVerifyDeltaPanel(
        onRunVerifyRequested = verifyDeltaCoordinator::runVerification,
        onOpenVerificationRequested = verifyDeltaCoordinator::openVerificationDocument,
        onCompareBaselineRequested = { workflowId, choice ->
            currentWorkflow
                ?.takeIf { workflow -> workflow.id == workflowId }
                ?.let { workflow ->
                    verifyDeltaCoordinator.compareBaseline(
                        SpecWorkflowVerifyDeltaCompareRequest(
                            targetWorkflow = workflow,
                            choice = choice,
                        ),
                    )
                }
        },
        onPinBaselineRequested = verifyDeltaCoordinator::pinBaseline,
        showHeader = false,
    )
    private val gateDetailsPanel = SpecWorkflowGateDetailsPanel(
        project = project,
        showHeader = false,
        onClarifyThenFillRequested = requirementsRepairEntryCoordinator::startClarifyThenFill,
        onRepairRequirementsRequested = ::repairRequirementsArtifactFromGate,
        onRepairTasksRequested = ::repairTasksArtifactFromGate,
    )
    private val workflowSelectionCallbacks = object : SpecWorkflowSelectionCallbacks {
        override fun highlightWorkflow(workflowId: String?) {
            workflowPanelState.highlightWorkflow(workflowId)
        }

        override fun clearOpenedWorkflowUi(resetHighlight: Boolean) {
            this@SpecWorkflowPanel.clearOpenedWorkflowUi(resetHighlight)
        }

        override fun loadWorkflow(workflowId: String) {
            workflowLoadEntryCoordinator.selectWorkflow(workflowId)
        }

        override fun publishWorkflowSelection(workflowId: String) {
            this@SpecWorkflowPanel.publishWorkflowSelection(workflowId)
        }
    }
    private val statusTroubleshootingActionDispatcher = SpecWorkflowTroubleshootingActionDispatcher(
        SpecWorkflowRuntimeTroubleshootingActionCallbacks(
            openSettingsAction = ::openTroubleshootingSettings,
            openBundledDemoAction = ::openBundledDemoProject,
            openCreateWorkflowDialog = { template ->
                onCreateWorkflow(template)
            },
        ),
    )
    private val statusStripUiHost = SpecWorkflowStatusStripUiHost(
        statusChipBackground = STATUS_CHIP_BG,
        statusChipBorder = STATUS_CHIP_BORDER,
        statusTextColor = STATUS_TEXT_FG,
        styleActionButton = ::styleToolbarButton,
        performAction = statusTroubleshootingActionDispatcher::perform,
    )
    private val modelLabel = JBLabel(SpecCodingBundle.message("toolwindow.model.label"))
    private val providerComboBox = ComboBox<String>()
    private val modelComboBox = ComboBox<ModelInfo>()
    private val createWorktreeButton = JButton()
    private val mergeWorktreeButton = JButton()
    private val switchWorkflowButton = JButton()
    private val createWorkflowButton = JButton()
    private val deltaButton = JButton()
    private val codeGraphButton = JButton()
    private val archiveButton = JButton()
    private val backToListButton = JButton()
    private val refreshButton = JButton()
    private lateinit var centerContentPanel: JPanel
    private lateinit var workspaceChromeUiHost: SpecWorkflowWorkspaceChromeUiHost
    private lateinit var workspacePresentationUiHost: SpecWorkflowWorkspacePresentationUiHost
    private lateinit var documentWorkspaceViewChrome: SpecWorkflowDocumentWorkspaceViewChrome
    private lateinit var listSectionContainer: JPanel
    private lateinit var workspacePanelContainer: JPanel
    private lateinit var mainSplitPane: JSplitPane
    private val workspaceCardLayout = CardLayout()
    private val workspaceCardPanel = JPanel(workspaceCardLayout)
    private lateinit var workspaceSummaryUi: SpecWorkflowWorkspaceSummaryUi
    private lateinit var overviewSection: SpecCollapsibleWorkspaceSection
    private lateinit var tasksSection: SpecCollapsibleWorkspaceSection
    private lateinit var gateSection: SpecCollapsibleWorkspaceSection
    private lateinit var verifySection: SpecCollapsibleWorkspaceSection
    private lateinit var documentsSection: SpecCollapsibleWorkspaceSection
    private val workspaceSectionItems = mutableMapOf<SpecWorkflowWorkspaceSectionId, JPanel>()
    private val currentWorkspaceState: SpecWorkflowWorkspaceAppliedState?
        get() = workflowWorkspaceStateHost.currentState()
    private val currentWorkbenchState: SpecWorkflowStageWorkbenchState?
        get() = currentWorkspaceState?.workbenchState
    private val currentStructuredTasks: List<StructuredTask>
        get() = currentWorkspaceState?.tasks.orEmpty()
    private val liveProgressRefreshDispatchQueued = AtomicBoolean(false)
    private val liveProgressRefreshLoadInFlight = AtomicBoolean(false)
    @Volatile
    private var liveProgressRefreshPending = false
    private val liveProgressListener = TaskExecutionLiveProgressListener { progress ->
        if (progress.workflowId == selectedWorkflowId) {
            requestLiveProgressPresentationRefresh()
        }
    }
    private val liveProgressEventCoalesceTimer = Timer(LIVE_PROGRESS_EVENT_COALESCE_MILLIS) {
        flushPendingLiveProgressPresentationRefresh()
    }.apply {
        isRepeats = false
    }
    private val liveProgressRefreshTimer = Timer(1_000) {
        flushPendingLiveProgressPresentationRefresh(force = true)
    }.apply {
        isRepeats = true
    }

    private var currentWorkflow: SpecWorkflow? = null
    private var isSynchronizingStructuredTaskSelection: Boolean = false
    private var isWorkspaceMode: Boolean = false
    private var detailDividerLocation: Int = 210
    private var initialWorkflowRefreshTriggered = false
    private var lastDocumentSaveResultForTest: Result<SpecWorkflow>? = null

    private var selectedWorkflowId: String?
        get() = workflowPanelState.selectedWorkflowId
        set(value) {
            workflowPanelState.selectedWorkflowId = value
        }

    private var highlightedWorkflowId: String?
        get() = workflowPanelState.highlightedWorkflowId
        set(value) {
            workflowPanelState.highlightedWorkflowId = value
        }

    private var focusedStage: StageId?
        get() = workflowPanelState.focusedStage
        set(value) {
            workflowPanelState.focusedStage = value
        }

    private var selectedDocumentWorkspaceView: DocumentWorkspaceView
        get() = workflowPanelState.selectedDocumentWorkspaceView
        set(value) {
            workflowPanelState.selectedDocumentWorkspaceView = value
        }

    private var selectedStructuredTaskId: String?
        get() = workflowPanelState.selectedStructuredTaskId
        set(value) {
            workflowPanelState.selectedStructuredTaskId = value
        }

    private var pendingOpenWorkflowRequest: SpecToolWindowOpenRequest?
        get() = workflowPanelState.pendingOpenWorkflowRequest
        set(value) {
            if (value == null) {
                workflowPanelState.clearPendingOpenRequest()
            } else {
                workflowPanelState.rememberPendingOpenRequest(value)
            }
        }

    init {
        border = JBUI.Borders.empty(8)

        listPanel = SpecWorkflowListPanel(
            onWorkflowFocused = ::onWorkflowFocusedByUser,
            onOpenWorkflow = ::onWorkflowOpenedByUser,
            onCreateWorkflow = ::onCreateWorkflow,
            onEditWorkflow = ::onEditWorkflow,
            onDeleteWorkflow = ::onDeleteWorkflow,
            showCreateButton = false,
        )

        detailPanel = SpecDetailPanel(
            onGenerate = ::onGenerate,
            canGenerateWithEmptyInput = ::canGenerateWithEmptyInput,
            onAddWorkflowSourcesRequested = { workflowComposerSourceUiHost.requestAdd() },
            onRemoveWorkflowSourceRequested = { sourceId -> workflowComposerSourceUiHost.requestRemove(sourceId) },
            onRestoreWorkflowSourcesRequested = { workflowComposerSourceUiHost.requestRestore() },
            onClarificationConfirm = clarificationActionCoordinator::confirm,
            onClarificationRegenerate = clarificationActionCoordinator::regenerate,
            onClarificationSkip = clarificationActionCoordinator::skip,
            onClarificationCancel = clarificationActionCoordinator::cancel,
            onNextPhase = workflowPhaseNavigationCoordinator::next,
            onGoBack = workflowPhaseNavigationCoordinator::goBack,
            onComplete = workflowLifecycleUiHost::requestComplete,
            onPauseResume = workflowLifecycleUiHost::requestPauseResume,
            onOpenInEditor = { phase ->
                workflowArtifactNavigationCoordinator.openPhaseDocument(selectedWorkflowId, phase)
            },
            onOpenArtifactInEditor = { fileName ->
                workflowArtifactNavigationCoordinator.openArtifactInEditor(selectedWorkflowId, fileName)
            },
            onShowHistoryDiff = { phase ->
                workflowArtifactNavigationCoordinator.showHistoryDiff(
                    workflowId = currentWorkflow?.id,
                    phase = phase,
                    currentDocument = currentWorkflow?.documents?.get(phase),
                )
            },
            onSaveDocument = workflowDocumentSaveCoordinator::save,
            onClarificationDraftAutosave = clarificationActionCoordinator::autosave,
        )

        setupUI()
        CliDiscoveryService.getInstance().addDiscoveryListener(discoveryListener)
        subscribeToLocaleEvents()
        subscribeToGlobalConfigEvents()
        workflowExternalEventSubscriptionAdapter.register()
        specTaskExecutionService.addLiveProgressListener(liveProgressListener)
        if (deferInitialWorkflowRefresh) {
            initialWorkflowRefreshTriggered = false
        } else {
            initialWorkflowRefreshTriggered = true
            refreshWorkflows()
        }
    }

    fun ensureInitialWorkflowRefresh() {
        if (initialWorkflowRefreshTriggered) {
            return
        }
        initialWorkflowRefreshTriggered = true
        refreshWorkflows()
    }

    private fun setupUI() {
        refreshButton.addActionListener { refreshWorkflows(showRefreshFeedback = true) }
        switchWorkflowButton.isEnabled = false
        createWorktreeButton.isVisible = false
        mergeWorktreeButton.isVisible = false
        codeGraphButton.isEnabled = true
        applyToolbarActionAvailability(SpecWorkflowToolbarActionAvailabilityBuilder.empty())
        codeGraphButton.isVisible = false
        archiveButton.isVisible = false
        backToListButton.isEnabled = false
        switchWorkflowButton.addActionListener { onSwitchWorkflowRequested() }
        createWorkflowButton.addActionListener { onCreateWorkflow() }
        createWorktreeButton.addActionListener { worktreeUiHost.requestCreate() }
        mergeWorktreeButton.addActionListener { worktreeUiHost.requestMerge() }
        deltaButton.addActionListener {
            workflowDeltaCoordinator.show(currentWorkflow)
        }
        codeGraphButton.addActionListener { codeGraphUiHost.requestShow() }
        archiveButton.addActionListener { workflowLifecycleUiHost.requestArchive() }
        backToListButton.addActionListener { onBackToWorkflowListRequested() }

        configureToolbarIconButton(
            button = backToListButton,
            icon = SpecWorkflowIcons.Back,
            tooltipKey = "spec.workflow.backToList",
        )
        styleToolbarButton(backToListButton)

        applyToolbarButtonPresentation()
        styleToolbarButton(switchWorkflowButton)
        styleToolbarButton(createWorkflowButton)
        styleToolbarButton(refreshButton)
        styleToolbarButton(createWorktreeButton)
        styleToolbarButton(mergeWorktreeButton)
        styleToolbarButton(deltaButton)
        styleToolbarButton(codeGraphButton)
        styleToolbarButton(archiveButton)

        setupGenerationControls()

        val controlsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(providerComboBox)
            add(modelLabel)
            add(modelComboBox)
            add(statusStripUiHost.statusChipPanel)
        }
        val actionsRow = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
            isOpaque = false
            add(switchWorkflowButton)
            add(createWorkflowButton)
            add(refreshButton)
            add(deltaButton)
        }
        val toolbarRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(1, 0)
            add(controlsRow, BorderLayout.CENTER)
            add(actionsRow, BorderLayout.EAST)
        }
        val toolbarCard = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = TOOLBAR_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = TOOLBAR_BORDER,
                arc = JBUI.scale(12),
                top = 4,
                left = 6,
                bottom = 4,
                right = 6,
            )
            add(toolbarRow, BorderLayout.CENTER)
        }
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(3)
                add(toolbarCard, BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )

        listSectionContainer = createSectionContainer(
            listPanel,
            backgroundColor = LIST_SECTION_BG,
            borderColor = LIST_SECTION_BORDER,
        )
        workspacePanelContainer = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = true
            background = DETAIL_COLUMN_BG
            add(buildWorkspaceCardPanel(), BorderLayout.CENTER)
        }

        mainSplitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            listSectionContainer,
            workspacePanelContainer,
        ).apply {
            dividerLocation = detailDividerLocation
            resizeWeight = 0.26
            isContinuousLayout = true
            border = JBUI.Borders.empty()
            background = PANEL_SECTION_BG
            SpecUiStyle.applyChatLikeSpecDivider(
                splitPane = this,
                dividerSize = JBUI.scale(4),
            )
        }
        mainSplitPane.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    clampDividerLocation(mainSplitPane)
                }
            },
        )
        mainSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            if (isWorkspaceMode && mainSplitPane.dividerLocation > 0) {
                detailDividerLocation = mainSplitPane.dividerLocation
            }
        }
        centerContentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        add(centerContentPanel, BorderLayout.CENTER)
        workspaceChromeUiHost = SpecWorkflowWorkspaceChromeUiHost(
            centerContentPanel = centerContentPanel,
            listSectionContainer = listSectionContainer,
            workspacePanelContainer = workspacePanelContainer,
            workspaceCardLayout = workspaceCardLayout,
            workspaceCardPanel = workspaceCardPanel,
            backToListButton = backToListButton,
            setWorkspaceMode = { isWorkspaceMode = it },
            emptyCardId = WORKSPACE_CARD_EMPTY,
            contentCardId = WORKSPACE_CARD_CONTENT,
        )
        workspacePresentationUiHost = SpecWorkflowWorkspacePresentationUiHostBuilder(
            summaryUi = workspaceSummaryUi,
            sections = SpecWorkflowWorkspaceCardSections(
                overview = overviewSection,
                tasks = tasksSection,
                gate = gateSection,
                verify = verifySection,
                documents = documentsSection,
            ),
            sectionItems = workspaceSectionItems,
            sectionContainer = workspaceCardPanel,
        ).build()
        setStatusText(null)
        workflowWorkspaceEmptyStateAdapter.showEmptyState()
    }

    private fun clampDividerLocation(split: JSplitPane) {
        if (!isWorkspaceMode || split.parent == null) return
        val total = split.width - split.dividerSize
        if (total <= 0) return
        val minLeft = JBUI.scale(120)
        val minRight = JBUI.scale(240)
        val maxLeft = (total - minRight).coerceAtLeast(minLeft)
        val current = split.dividerLocation
        val clamped = current.coerceIn(minLeft, maxLeft)
        if (clamped != current) {
            split.dividerLocation = clamped
        }
        detailDividerLocation = clamped
    }

    private fun buildWorkspaceCardPanel(): JPanel {
        val workspaceCardChrome = SpecWorkflowWorkspaceCardBuilder(
            workspaceCardPanel = workspaceCardPanel,
            backToListButton = backToListButton,
            overviewContent = overviewPanel,
            tasksContent = tasksPanel,
            gateContent = gateDetailsPanel,
            verifyContent = verifyDeltaPanel,
            documentsContent = buildDocumentWorkspaceContent(),
            sectionItems = workspaceSectionItems,
            onSectionExpandedChanged = { id, expanded ->
                workflowWorkspaceStateHost.rememberSectionOverride(id, expanded)
            },
            emptyCardId = WORKSPACE_CARD_EMPTY,
            contentCardId = WORKSPACE_CARD_CONTENT,
        ).build()
        workspaceSummaryUi = workspaceCardChrome.summaryUi
        val sections = workspaceCardChrome.sections
        overviewSection = sections.overview
        tasksSection = sections.tasks
        gateSection = sections.gate
        verifySection = sections.verify
        documentsSection = sections.documents
        return workspaceCardPanel
    }

    private fun buildDocumentWorkspaceContent(): JPanel {
        documentWorkspaceViewChrome = SpecWorkflowDocumentWorkspaceViewChromeBuilder(
            documentContent = detailPanel,
            structuredTasksContent = detailTasksPanel,
            installToolbarButtonCursorTracking = ::installToolbarButtonCursorTracking,
            onViewSelected = { view ->
                selectedDocumentWorkspaceView = view
                updateDocumentWorkspaceViewPresentation(currentWorkbenchState)
            },
            resolvePresentation = {
                documentWorkspaceViewAdapter.resolvePresentation(currentWorkbenchState)
            },
            message = SpecCodingBundle::message,
            syncStructuredTaskSelectionUi = ::syncStructuredTaskSelection,
        ).build()
        updateDocumentWorkspaceViewPresentation(null)
        return documentWorkspaceViewChrome.container
    }

    private fun styleToolbarButton(button: JButton) {
        val iconOnly = button.icon != null && button.text.isNullOrBlank()
        button.isFocusable = false
        button.isFocusPainted = false
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = if (iconOnly) JBUI.insets(0, 0, 0, 0) else JBUI.insets(1, 4, 1, 4)
        button.foreground = BUTTON_FG
        if (iconOnly) {
            installToolbarButtonCursorTracking(button)
            button.putClientProperty("JButton.buttonType", "toolbar")
            button.isContentAreaFilled = false
            button.isOpaque = false
            button.border = JBUI.Borders.empty(1)
            val size = JBUI.size(JBUI.scale(22), JBUI.scale(22))
            button.preferredSize = size
            button.minimumSize = size
        } else {
            button.isContentAreaFilled = true
            button.isOpaque = true
            SpecUiStyle.applyRoundRect(button, arc = 10)
            installToolbarButtonCursorTracking(button)
            button.background = BUTTON_BG
            button.border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
                JBUI.Borders.empty(1, 5, 1, 5),
            )
            val textWidth = button.getFontMetrics(button.font).stringWidth(button.text ?: "")
            val insets = button.insets
            val lafWidth = button.preferredSize?.width ?: 0
            val width = maxOf(
                lafWidth,
                textWidth + insets.left + insets.right + JBUI.scale(10),
                JBUI.scale(40),
            )
            button.preferredSize = JBUI.size(width, JBUI.scale(26))
            button.minimumSize = button.preferredSize
        }
    }

    private fun installToolbarButtonCursorTracking(button: JButton) {
        if (button.getClientProperty("spec.toolbar.cursorTrackingInstalled") == true) return
        button.putClientProperty("spec.toolbar.cursorTrackingInstalled", true)
        updateToolbarButtonCursor(button)
        button.addPropertyChangeListener("enabled") { updateToolbarButtonCursor(button) }
    }

    private fun updateToolbarButtonCursor(button: JButton) {
        button.cursor = if (button.isEnabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private fun applyToolbarButtonPresentation() {
        configureToolbarIconButton(
            button = switchWorkflowButton,
            icon = SpecWorkflowIcons.SwitchWorkflow,
            tooltipKey = "spec.workflow.switch",
        )
        configureToolbarIconButton(
            button = createWorkflowButton,
            icon = SpecWorkflowIcons.Add,
            tooltipKey = "spec.workflow.new",
        )
        configureToolbarIconButton(
            button = refreshButton,
            icon = SpecWorkflowIcons.Refresh,
            tooltipKey = "spec.workflow.refresh",
        )
        configureToolbarIconButton(
            button = createWorktreeButton,
            icon = SpecWorkflowIcons.Branch,
            tooltipKey = "spec.workflow.createWorktree",
        )
        configureToolbarIconButton(
            button = mergeWorktreeButton,
            icon = SpecWorkflowIcons.Complete,
            tooltipKey = "spec.workflow.mergeWorktree",
        )
        configureToolbarIconButton(
            button = deltaButton,
            icon = SpecWorkflowIcons.History,
            tooltipKey = "spec.workflow.delta",
        )
        configureToolbarIconButton(
            button = codeGraphButton,
            icon = SpecWorkflowIcons.OpenToolWindow,
            tooltipKey = "spec.workflow.codeGraph",
        )
        configureToolbarIconButton(
            button = archiveButton,
            icon = SpecWorkflowIcons.Save,
            tooltipKey = "spec.workflow.archive",
        )
    }

    private fun configureToolbarIconButton(button: JButton, icon: Icon, tooltipKey: String) {
        val tooltip = SpecCodingBundle.message(tooltipKey)
        SpecUiStyle.configureIconActionButton(
            button = button,
            icon = icon,
            tooltip = tooltip,
            accessibleName = tooltip,
        )
    }

    private fun setupGenerationControls() {
        modelLabel.font = JBUI.Fonts.smallFont()
        modelLabel.foreground = STATUS_TEXT_FG

        providerComboBox.renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
            label.text = providerDisplayName(value)
        }
        modelComboBox.renderer = SimpleListCellRenderer.create<ModelInfo> { label, value, _ ->
            label.text = toUiLowercase(value?.name ?: "")
        }
        providerComboBox.addActionListener {
            refreshModelCombo(preserveSelection = true)
        }
        modelComboBox.addActionListener {
            updateSelectorTooltips()
        }
        configureToolbarCombo(providerComboBox, minWidth = 56, maxWidth = 160)
        configureToolbarCombo(modelComboBox, minWidth = 72, maxWidth = 260)
        refreshProviderCombo(preserveSelection = false)
    }

    private fun configureToolbarCombo(comboBox: ComboBox<*>, minWidth: Int, maxWidth: Int) {
        comboBox.putClientProperty("JComponent.roundRect", false)
        comboBox.putClientProperty("JComboBox.isBorderless", true)
        comboBox.putClientProperty("ComboBox.isBorderless", true)
        comboBox.putClientProperty("JComponent.outline", null)
        comboBox.background = BUTTON_BG
        comboBox.foreground = BUTTON_FG
        comboBox.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
            JBUI.Borders.empty(0, 5, 0, 5),
        )
        comboBox.isOpaque = true
        comboBox.font = JBUI.Fonts.smallFont()
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = comboBox,
            minWidth = JBUI.scale(minWidth),
            maxWidth = JBUI.scale(maxWidth),
            height = JBUI.scale(24),
        )
    }

    private fun refreshProviderCombo(preserveSelection: Boolean) {
        val settings = SpecCodingSettingsState.getInstance()
        val previousSelection = if (preserveSelection) providerComboBox.selectedItem as? String else null
        val providers = llmRouter.availableUiProviders()
            .ifEmpty { llmRouter.availableProviders() }
            .ifEmpty { listOf(MockLlmProvider.ID) }

        providerComboBox.removeAllItems()
        providers.forEach { providerComboBox.addItem(it) }

        val preferred = when {
            !previousSelection.isNullOrBlank() -> previousSelection
            settings.defaultProvider.isNotBlank() -> settings.defaultProvider
            else -> llmRouter.defaultProviderId()
        }
        val selectedProvider = providers.firstOrNull { it == preferred } ?: providers.firstOrNull()
        providerComboBox.selectedItem = selectedProvider
        refreshModelCombo(preserveSelection = preserveSelection)
    }

    private fun refreshModelCombo(preserveSelection: Boolean) {
        val selectedProvider = providerComboBox.selectedItem as? String
        val previousModelId = if (preserveSelection) {
            (modelComboBox.selectedItem as? ModelInfo)?.id?.trim().orEmpty()
        } else {
            ""
        }
        modelComboBox.removeAllItems()

        if (selectedProvider.isNullOrBlank()) {
            modelComboBox.isEnabled = false
            updateSelectorTooltips()
            return
        }

        val settings = SpecCodingSettingsState.getInstance()
        val savedModelId = settings.selectedCliModel.trim()
        val models = modelRegistry.getModelsForProvider(selectedProvider)
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .toMutableList()

        if (models.isEmpty() && savedModelId.isNotBlank() && selectedProvider == settings.defaultProvider) {
            models += ModelInfo(
                id = savedModelId,
                name = savedModelId,
                provider = selectedProvider,
                contextWindow = 0,
                capabilities = emptySet(),
            )
        }

        models.forEach { modelComboBox.addItem(it) }
        val preferredModelId = when {
            previousModelId.isNotBlank() -> previousModelId
            savedModelId.isNotBlank() -> savedModelId
            else -> ""
        }
        val selectedModel = models.firstOrNull { it.id == preferredModelId } ?: models.firstOrNull()
        if (selectedModel != null) {
            modelComboBox.selectedItem = selectedModel
        }
        modelComboBox.isEnabled = models.isNotEmpty()
        updateSelectorTooltips()
    }

    private fun updateSelectorTooltips() {
        providerComboBox.toolTipText = providerDisplayName(providerComboBox.selectedItem as? String)
        modelComboBox.toolTipText = (modelComboBox.selectedItem as? ModelInfo)?.name
    }

    internal fun syncToolbarSelectionFromSettings() {
        refreshProviderCombo(preserveSelection = false)
    }

    private fun providerDisplayName(providerId: String?): String {
        return when (providerId) {
            ClaudeCliLlmProvider.ID -> "claude"
            CodexCliLlmProvider.ID -> "codex"
            MockLlmProvider.ID -> toUiLowercase(SpecCodingBundle.message("toolwindow.model.mock"))
            null -> ""
            else -> toUiLowercase(providerId)
        }
    }

    private fun toUiLowercase(value: String): String = value.lowercase(Locale.ROOT)

    private fun setStatusText(text: String?) {
        statusStripUiHost.apply(runtimeTroubleshootingStatusCoordinator.plain(text))
    }

    private fun setStatusWithTroubleshooting(
        text: String?,
        actions: List<SpecWorkflowTroubleshootingAction>,
    ) {
        statusStripUiHost.apply(
            runtimeTroubleshootingStatusCoordinator.withActions(
                text = text,
                actions = actions,
            ),
        )
    }

    private fun applyToolbarActionAvailability(availability: SpecWorkflowToolbarActionAvailability) {
        createWorktreeButton.isEnabled = availability.createWorktreeEnabled
        mergeWorktreeButton.isEnabled = availability.mergeWorktreeEnabled
        deltaButton.isEnabled = availability.deltaEnabled
        archiveButton.isEnabled = availability.archiveEnabled
    }

    private fun setRuntimeTroubleshootingStatus(
        workflowId: String?,
        text: String?,
        trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
    ) {
        statusStripUiHost.apply(
            runtimeTroubleshootingStatusCoordinator.runtime(
                SpecWorkflowRuntimeTroubleshootingStatusRequest(
                    workflowId = workflowId,
                    text = text,
                    trigger = trigger,
                ),
            ),
        )
    }

    private fun openTroubleshootingSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            "com.eacape.speccodingplugin.settings",
        )
        syncToolbarSelectionFromSettings()
    }

    private fun openBundledDemoProject() {
        val demoProject = runCatching {
            SpecWorkflowBundledDemoProjectSupport.materializeDefault()
        }.getOrElse { error ->
            showBundledDemoOpenError(error)
            return
        }
        val readmePath = demoProject.readmePath
        if (!SpecWorkflowActionSupport.openFile(project, readmePath)) {
            BrowserUtil.browse(readmePath.toUri())
        }
    }

    private fun showBundledDemoOpenError(error: Throwable) {
        Messages.showErrorDialog(
            project,
            SpecCodingBundle.message(
                "spec.dialog.demo.open.failed",
                error.message ?: error.javaClass.simpleName,
            ),
            SpecCodingBundle.message("spec.dialog.demo.title"),
        )
    }

    private fun createSectionContainer(
        content: Component,
        padding: Int = 2,
        backgroundColor: Color = PANEL_SECTION_BG,
        borderColor: Color = PANEL_SECTION_BORDER,
    ): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = backgroundColor
            border = SpecUiStyle.roundedCardBorder(
                lineColor = borderColor,
                arc = JBUI.scale(14),
                top = padding,
                left = padding,
                bottom = padding,
                right = padding,
            )
            add(content, BorderLayout.CENTER)
        }
    }

    private fun updateDocumentWorkspaceViewPresentation(workbenchState: SpecWorkflowStageWorkbenchState?) {
        documentWorkspaceViewAdapter.updatePresentation(workbenchState)
    }

    private fun supportsStructuredTasksDocumentWorkspaceView(
        workbenchState: SpecWorkflowStageWorkbenchState?,
    ): Boolean {
        val binding = workbenchState?.artifactBinding ?: return false
        return workbenchState.focusedStage in setOf(StageId.TASKS, StageId.IMPLEMENT) &&
            binding.documentPhase == SpecPhase.IMPLEMENT &&
            binding.fileName == StageId.TASKS.artifactFileName
    }

    private fun onStructuredTaskSelectionChanged(taskId: String?) {
        if (isSynchronizingStructuredTaskSelection) {
            return
        }
        syncStructuredTaskSelection(taskId)
    }

    private fun syncStructuredTaskSelection(taskId: String?) {
        selectedStructuredTaskId = taskId?.takeIf { it.isNotBlank() }
        val selectedTaskId = selectedStructuredTaskId
        val previous = isSynchronizingStructuredTaskSelection
        isSynchronizingStructuredTaskSelection = true
        try {
            if (selectedTaskId == null) {
                tasksPanel.clearTaskSelection()
                detailTasksPanel.clearTaskSelection()
                return
            }
            if (tasksPanel.selectedTaskId() != selectedTaskId) {
                tasksPanel.selectTask(selectedTaskId)
            }
            if (detailTasksPanel.selectedTaskId() != selectedTaskId) {
                detailTasksPanel.selectTask(selectedTaskId)
            }
        } finally {
            isSynchronizingStructuredTaskSelection = previous
        }
    }

    private fun refreshWorkspacePresentation() {
        val request = workflowWorkspacePresentationRequestBuilder.buildRefreshRequest(
            workflow = currentWorkflow,
            appliedState = currentWorkspaceState,
        )
        if (request == null) {
            if (selectedWorkflowId == null) {
                workflowWorkspaceEmptyStateAdapter.showEmptyState()
            }
            return
        }
        applyWorkspacePresentationRequest(request)
    }

    private fun applyCurrentLiveProgressPresentation(updatedLiveProgress: Map<String, TaskExecutionLiveProgress>) {
        val workflow = currentWorkflow ?: return
        val workflowId = selectedWorkflowId ?: return
        if (workflow.id != workflowId) {
            return
        }
        tasksPanel.updateLiveProgress(
            tasks = currentStructuredTasks,
            liveProgressByTaskId = updatedLiveProgress,
        )
        detailTasksPanel.updateLiveProgress(
            tasks = currentStructuredTasks,
            liveProgressByTaskId = updatedLiveProgress,
        )
        refreshWorkspacePresentation()
    }

    private fun requestLiveProgressPresentationRefresh() {
        liveProgressRefreshPending = true
        if (!liveProgressRefreshDispatchQueued.compareAndSet(false, true)) {
            return
        }
        invokeLaterSafe {
            liveProgressRefreshDispatchQueued.set(false)
            if (project.isDisposed || _isDisposed || !liveProgressRefreshPending) {
                return@invokeLaterSafe
            }
            if (liveProgressEventCoalesceTimer.isRunning) {
                liveProgressEventCoalesceTimer.restart()
            } else {
                liveProgressEventCoalesceTimer.start()
            }
        }
    }

    private fun flushPendingLiveProgressPresentationRefresh(force: Boolean = false) {
        if (!force && !liveProgressRefreshPending) {
            return
        }
        liveProgressRefreshPending = false
        refreshCurrentLiveProgressPresentationAsync()
    }

    private fun refreshCurrentLiveProgressPresentationAsync() {
        val workflow = currentWorkflow ?: return
        val workflowId = selectedWorkflowId ?: return
        if (workflow.id != workflowId) {
            return
        }
        if (!liveProgressRefreshLoadInFlight.compareAndSet(false, true)) {
            liveProgressRefreshPending = true
            return
        }
        taskCoordinator.launchIo {
            val updatedLiveProgress = buildTaskLiveProgressByTaskId(workflowId)
            invokeLaterSafe {
                liveProgressRefreshLoadInFlight.set(false)
                if (selectedWorkflowId == workflowId && currentWorkflow?.id == workflowId) {
                    applyCurrentLiveProgressPresentation(updatedLiveProgress)
                }
                if (liveProgressRefreshPending) {
                    refreshCurrentLiveProgressPresentationAsync()
                }
            }
        }
    }

    private fun decorateTasksWithExecutionState(
        workflow: SpecWorkflow,
        tasks: List<StructuredTask>,
        liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
    ): List<StructuredTask> {
        if (tasks.isEmpty()) {
            return emptyList()
        }
        val activeRunsByTaskId = workflow.taskExecutionRuns
            .asSequence()
            .filter { run -> !run.status.isTerminal() }
            .sortedBy(TaskExecutionRun::startedAt)
            .associateBy(TaskExecutionRun::taskId)
        return tasks.map { task ->
            task.copy(activeExecutionRun = activeRunsByTaskId[task.id])
        }
    }

    private fun buildTaskLiveProgressByTaskId(workflowId: String): Map<String, TaskExecutionLiveProgress> {
        return runCatching {
            specTaskExecutionService.listLiveProgress(workflowId)
        }.getOrElse { error ->
            logger.debug("Unable to load live execution progress for workflow $workflowId", error)
            emptyList()
        }.associateBy(TaskExecutionLiveProgress::taskId)
    }

    private fun updateLiveProgressRefreshTimer(
        tasks: List<StructuredTask>,
        liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
    ) {
        val hasLiveProgress = liveProgressByTaskId.isNotEmpty() || tasks.any(StructuredTask::hasExecutionInFlight)
        if (hasLiveProgress) {
            if (!liveProgressRefreshTimer.isRunning) {
                liveProgressRefreshTimer.start()
            }
        } else {
            liveProgressRefreshPending = false
            liveProgressRefreshDispatchQueued.set(false)
            liveProgressEventCoalesceTimer.stop()
            liveProgressRefreshTimer.stop()
        }
    }

    private fun updateWorkspacePresentation(
        workflow: SpecWorkflow,
        overviewState: SpecWorkflowOverviewState,
        tasks: List<StructuredTask>,
        liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
        verifyDeltaState: SpecWorkflowVerifyDeltaState,
        gateResult: GateResult?,
    ) {
        updateLiveProgressRefreshTimer(tasks, liveProgressByTaskId)
        focusedStage = workflowWorkspaceStateHost.apply(
            workflow = workflow,
            overviewState = overviewState,
            tasks = tasks,
            liveProgressByTaskId = liveProgressByTaskId,
            verifyDeltaState = verifyDeltaState,
            gateResult = gateResult,
            focusedStage = focusedStage,
        ).workbenchState.focusedStage
    }

    private fun applyWorkspacePresentationRequest(request: SpecWorkflowWorkspacePresentationRequest) {
        updateWorkspacePresentation(
            workflow = request.workflow,
            overviewState = request.overviewState,
            tasks = request.tasks,
            liveProgressByTaskId = request.liveProgressByTaskId,
            verifyDeltaState = request.verifyDeltaState,
            gateResult = request.gateResult,
        )
    }

    private fun phaseLabel(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> SpecCodingBundle.message("spec.detail.step.requirements")
            SpecPhase.DESIGN -> SpecCodingBundle.message("spec.detail.step.design")
            SpecPhase.IMPLEMENT -> SpecCodingBundle.message("spec.detail.step.taskList")
        }
    }

    private fun clearOpenedWorkflowUi(resetHighlight: Boolean = false) {
        openedWorkflowResetHost.clear(resetHighlight = resetHighlight)
    }

    fun refreshWorkflows(
        selectWorkflowId: String? = null,
        showRefreshFeedback: Boolean = false,
        preserveListMode: Boolean = false,
    ) {
        workflowListRefreshExecutionCoordinator.refreshWorkflows(
            SpecWorkflowListRefreshExecutionRequest(
                selectWorkflowId = selectWorkflowId,
                showRefreshFeedback = showRefreshFeedback,
                preserveListMode = preserveListMode,
            ),
        )
    }

    private fun onWorkflowFocusedByUser(workflowId: String) {
        workflowSelectionCoordinator.focus(
            workflowId = workflowId,
            selectedWorkflowId = selectedWorkflowId,
            callbacks = workflowSelectionCallbacks,
        )
    }

    private fun onWorkflowOpenedByUser(workflowId: String) {
        workflowSelectionCoordinator.open(workflowId, workflowSelectionCallbacks)
    }

    private fun onBackToWorkflowListRequested() {
        workflowSelectionCoordinator.backToList(workflowSelectionCallbacks)
    }

    private fun publishWorkflowSelection(workflowId: String) {
        runCatching {
            project.messageBus.syncPublisher(SpecWorkflowChangedListener.TOPIC).onWorkflowChanged(
                SpecWorkflowChangedEvent(
                    workflowId = workflowId,
                    reason = SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED,
                ),
            )
        }.onFailure { error ->
            logger.warn("Failed to publish workflow selection event: $workflowId", error)
        }
    }

    private fun selectWorkflow(workflowId: String) {
        workflowLoadEntryCoordinator.selectWorkflow(workflowId)
    }

    private fun onCreateWorkflow(preferredTemplate: WorkflowTemplate? = null) {
        workflowCreateEditCoordinator.requestCreate(
            preferredTemplate = preferredTemplate,
            workflowOptions = listPanel.workflowOptionsForCreate(),
        )
    }

    private fun onEditWorkflow(workflowId: String) {
        workflowCreateEditCoordinator.requestEdit(workflowId)
    }

    private fun onDeleteWorkflow(workflowId: String) {
        workflowListActionCoordinator.requestDelete(workflowId)
    }

    private fun canGenerateWithEmptyInput(): Boolean {
        return clarificationRetryStore.hasInput(selectedWorkflowId)
    }

    private fun onGenerate(input: String) {
        generationLaunchCoordinator.generate(input)
    }

    private fun resolveSelectedWorkflowForClarification(): SpecWorkflow? {
        val workflowId = selectedWorkflowId ?: return null
        val workflow = currentWorkflow?.takeIf { it.id == workflowId }
        if (workflow == null) {
            setStatusText(SpecCodingBundle.message("spec.workflow.error", SpecCodingBundle.message("common.unknown")))
        }
        return workflow
    }

    private fun repairTasksArtifactFromGate(workflowId: String): Boolean {
        gateArtifactRepairCoordinator.repairTasksArtifact(workflowId)
        return true
    }

    private fun repairRequirementsArtifactFromGate(workflowId: String): Boolean {
        gateArtifactRepairCoordinator.repairRequirementsArtifact(workflowId)
        return true
    }

    private fun onSwitchWorkflowRequested() {
        workflowListActionCoordinator.requestSwitch()
    }

    private fun applyGenerationProgressUpdate(
        workflowId: String,
        input: String,
        update: SpecWorkflowGenerationProgressUpdate,
    ) {
        if (selectedWorkflowId != workflowId) {
            return
        }
        appendProcessTimelineEntries(update.timelineEntries)
        update.progressFraction?.let(detailPanel::showGenerating)
        if (update.shouldClearRetry) {
            clearClarificationRetry(workflowId)
        }
        if (update.retryLastError != null) {
            rememberClarificationRetry(
                workflowId = workflowId,
                input = input,
                confirmedContext = update.retryConfirmedContext,
                clarificationRound = clarificationRetryStore.current(workflowId)?.clarificationRound,
                lastError = update.retryLastError,
            )
        }
        if (update.validationFailure != null) {
            setStatusText(update.statusText)
            if (update.shouldReloadWorkflow) {
                reloadCurrentWorkflow { updated ->
                    detailPanel.showValidationFailureInteractive(
                        phase = updated.currentPhase,
                        validation = update.validationFailure,
                    )
                }
            }
            return
        }
        if (update.shouldShowGenerationFailed) {
            detailPanel.showGenerationFailed()
        }
        if (update.shouldExitClarificationMode) {
            detailPanel.exitClarificationMode(clearInput = update.clearInputOnExit)
        }
        if (update.statusText != null) {
            if (update.troubleshootingTrigger != null) {
                setRuntimeTroubleshootingStatus(
                    workflowId,
                    update.statusText,
                    update.troubleshootingTrigger,
                )
            } else {
                setStatusText(update.statusText)
            }
        }
        if (update.shouldReloadWorkflow) {
            reloadCurrentWorkflow()
        }
    }

    private fun appendProcessTimelineEntries(entries: List<SpecWorkflowTimelineEntry>) {
        entries.forEach { entry ->
            detailPanel.appendProcessTimelineEntry(
                text = entry.text,
                state = entry.state.toProcessTimelineState(),
            )
        }
    }

    private fun SpecWorkflowTimelineEntryState.toProcessTimelineState(): SpecDetailPanel.ProcessTimelineState {
        return when (this) {
            SpecWorkflowTimelineEntryState.ACTIVE -> SpecDetailPanel.ProcessTimelineState.ACTIVE
            SpecWorkflowTimelineEntryState.DONE -> SpecDetailPanel.ProcessTimelineState.DONE
            SpecWorkflowTimelineEntryState.FAILED -> SpecDetailPanel.ProcessTimelineState.FAILED
            SpecWorkflowTimelineEntryState.INFO -> SpecDetailPanel.ProcessTimelineState.INFO
        }
    }

    private fun resolveGenerationContext(): SpecWorkflowGenerationContext? {
        val selectedWorkflowId = selectedWorkflowId
        return when (
            val resolution = generationCoordinator.resolveGenerationContext(
                selectedWorkflowId = selectedWorkflowId,
                currentWorkflow = currentWorkflow,
                providerId = providerComboBox.selectedItem as? String,
                modelId = (modelComboBox.selectedItem as? ModelInfo)?.id,
                workflowSourceUsage = selectedWorkflowId
                    ?.let(::resolveComposerSourceUsage)
                    ?: WorkflowSourceUsage(),
            )
        ) {
            is SpecWorkflowGenerationContextResolution.Success -> resolution.context
            is SpecWorkflowGenerationContextResolution.Failure -> {
                setRuntimeTroubleshootingStatus(
                    selectedWorkflowId,
                    resolution.statusMessage,
                    SpecWorkflowRuntimeTroubleshootingTrigger.GENERATION_PRECHECK,
                )
                null
            }
        }
    }

    private fun resolveComposerSourceUsage(workflowId: String): WorkflowSourceUsage {
        return workflowDetailStateHost.resolveSourceUsage(workflowId)
    }

    private fun rememberClarificationRetry(
        workflowId: String,
        input: String,
        confirmedContext: String?,
        questionsMarkdown: String? = null,
        structuredQuestions: List<String>? = null,
        clarificationRound: Int? = null,
        lastError: String? = null,
        confirmed: Boolean? = null,
        followUp: ClarificationFollowUp? = null,
        requirementsRepairSections: List<RequirementsSectionId>? = null,
        persist: Boolean = true,
    ) {
        clarificationRetryStore.remember(
            SpecWorkflowClarificationRetryRememberRequest(
                workflowId = workflowId,
                input = input,
                confirmedContext = confirmedContext,
                questionsMarkdown = questionsMarkdown,
                structuredQuestions = structuredQuestions,
                clarificationRound = clarificationRound,
                lastError = lastError,
                confirmed = confirmed,
                followUp = followUp,
                requirementsRepairSections = requirementsRepairSections,
                persist = persist,
            ),
        )
    }

    private fun clearClarificationRetry(workflowId: String, persist: Boolean = true) {
        clarificationRetryStore.clear(workflowId, persist)
    }

    private fun syncClarificationRetryFromWorkflow(workflow: SpecWorkflow) {
        clarificationRetryStore.syncFromWorkflow(workflow)
    }

    private fun restorePendingClarificationState(workflowId: String) {
        val payload = clarificationRetryStore.current(workflowId) ?: return
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message(
                "spec.workflow.process.retryRestored",
                payload.clarificationRound,
            ),
            state = SpecDetailPanel.ProcessTimelineState.INFO,
        )
        payload.lastError
            ?.takeIf { it.isNotBlank() }
            ?.let { error ->
                detailPanel.appendProcessTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.retryLastError", error),
                    state = SpecDetailPanel.ProcessTimelineState.FAILED,
                )
            }
    }

    private fun onTaskStatusTransitionRequested(taskId: String, to: TaskStatus) {
        val workflowId = selectedWorkflowId ?: return
        taskMutationCoordinator.transitionStatus(
            workflowId = workflowId,
            taskId = taskId,
            to = to,
            auditContext = buildTaskAuditContext(taskId, "STATUS_${to.name}"),
        )
    }

    private fun onTaskDependsOnUpdateRequested(taskId: String, dependsOn: List<String>) {
        val workflowId = selectedWorkflowId ?: return
        taskMutationCoordinator.updateDependsOn(
            workflowId = workflowId,
            taskId = taskId,
            dependsOn = dependsOn,
        )
    }

    private fun onTaskCompleteRequested(
        taskId: String,
        files: List<String>,
        verificationResult: TaskVerificationResult?,
    ) {
        val workflowId = selectedWorkflowId ?: return
        taskMutationCoordinator.completeTask(
            workflowId = workflowId,
            taskId = taskId,
            files = files,
            verificationResult = verificationResult,
            auditContext = buildTaskAuditContext(taskId, "COMPLETE"),
        )
    }

    private fun onTaskExecutionRequested(taskId: String, retry: Boolean) {
        val workflowId = selectedWorkflowId ?: return
        taskExecutionEntryCoordinator.requestExecution(
            SpecWorkflowTaskExecutionLaunchRequest(
                workflowId = workflowId,
                taskId = taskId,
                providerId = providerComboBox.selectedItem as? String,
                modelId = (modelComboBox.selectedItem as? ModelInfo)?.id,
                operationMode = modeManager.getCurrentMode(),
                retry = retry,
                auditContext = buildTaskAuditContext(
                    taskId,
                    if (retry) "RETRY_EXECUTION" else "EXECUTE_WITH_AI",
                ),
            ),
        )
    }

    private fun onTaskExecutionCancelRequested(taskId: String) {
        val workflowId = selectedWorkflowId ?: return
        taskExecutionCoordinator.cancel(
            SpecWorkflowTaskExecutionCancelRequest(
                workflowId = workflowId,
                taskId = taskId,
            ),
        )
    }

    private fun onTaskVerificationResultUpdateRequested(taskId: String, verificationResult: TaskVerificationResult?) {
        val workflowId = selectedWorkflowId ?: return
        val existingTask = currentStructuredTasks.firstOrNull { task -> task.id == taskId }
        taskMutationCoordinator.updateVerificationResult(
            workflowId = workflowId,
            taskId = taskId,
            verificationResult = verificationResult,
            existingVerificationResult = existingTask?.verificationResult,
            auditContext = buildTaskAuditContext(taskId, "UPDATE_VERIFICATION_RESULT"),
        )
    }

    private fun buildTaskAuditContext(taskId: String, action: String): Map<String, String> {
        val task = currentStructuredTasks.firstOrNull { candidate -> candidate.id == taskId }
        val workbenchState = currentWorkbenchState
        val binding = workbenchState?.artifactBinding
        val summary = binding?.previewContent
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull { line -> line.isNotEmpty() && !line.startsWith("#") && !line.startsWith("```") }
            ?.take(180)
            .orEmpty()

        return linkedMapOf<String, String>().apply {
            put("triggerSource", "SPEC_PAGE_TASK_BUTTON")
            put("taskAction", action)
            put("currentStage", currentWorkflow?.currentStage?.name ?: "")
            put("focusedStage", workbenchState?.focusedStage?.name ?: "")
            put("documentBinding", binding?.fileName ?: binding?.title.orEmpty())
            put("documentSummary", summary)
            put("taskLifecycleStatus", task?.status?.name ?: "")
            put("taskDisplayStatus", task?.displayStatus?.name ?: "")
            put("taskExecutionRunId", task?.activeExecutionRun?.runId ?: "")
            put("taskExecutionRunStatus", task?.activeExecutionRun?.status?.name ?: "")
            put("dependsOn", task?.dependsOn?.joinToString(", ").orEmpty())
        }.filterValues { value -> value.isNotBlank() }
    }

    private fun onAdvanceStageRequested() {
        val workflowId = selectedWorkflowId ?: return
        stageTransitionCoordinator.advance(workflowId)
    }

    private fun handleStageTransitionCompleted(workflowId: String, successMessage: String) {
        SpecWorkflowActionSupport.notifySuccess(project, successMessage)
        focusedStage = null
        publishWorkflowSelection(workflowId)
        refreshWorkflows(selectWorkflowId = workflowId)
    }

    private fun subscribeToLocaleEvents() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    invokeLaterSafe {
                        refreshLocalizedTexts()
                    }
                }
            },
        )
    }

    private fun subscribeToGlobalConfigEvents() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            GlobalConfigSyncListener.TOPIC,
            object : GlobalConfigSyncListener {
                override fun onGlobalConfigChanged(event: GlobalConfigChangedEvent) {
                    invokeLaterSafe {
                        syncToolbarSelectionFromSettings()
                    }
                }
            },
        )
    }

    private fun refreshLocalizedTexts() {
        workflowSwitcherUiHost.cancel()
        listPanel.refreshLocalizedTexts()
        detailPanel.refreshLocalizedTexts()
        overviewPanel.refreshLocalizedTexts()
        verifyDeltaPanel.refreshLocalizedTexts()
        tasksPanel.refreshLocalizedTexts()
        detailTasksPanel.refreshLocalizedTexts()
        gateDetailsPanel.refreshLocalizedTexts()
        if (::overviewSection.isInitialized) {
            overviewSection.refreshLocalizedTexts()
            tasksSection.refreshLocalizedTexts()
            gateSection.refreshLocalizedTexts()
            verifySection.refreshLocalizedTexts()
            documentsSection.refreshLocalizedTexts()
        }
        if (::documentWorkspaceViewChrome.isInitialized) {
            documentWorkspaceViewChrome.uiHost.refreshLocalizedTexts()
            updateDocumentWorkspaceViewPresentation(currentWorkbenchState)
        }
        applyToolbarButtonPresentation()
        modelLabel.text = SpecCodingBundle.message("toolwindow.model.label")
        styleToolbarButton(switchWorkflowButton)
        styleToolbarButton(createWorkflowButton)
        styleToolbarButton(refreshButton)
        styleToolbarButton(createWorktreeButton)
        styleToolbarButton(mergeWorktreeButton)
        styleToolbarButton(deltaButton)
        styleToolbarButton(codeGraphButton)
        styleToolbarButton(archiveButton)
        refreshProviderCombo(preserveSelection = true)
        refreshWorkspacePresentation()
        setStatusText(null)
    }

    private fun showHistoryDiffDialog(request: SpecWorkflowHistoryDiffDialogRequest) {
        SpecHistoryDiffDialog(
            phase = request.phase,
            currentDocument = request.currentDocument,
            snapshots = request.snapshots,
            onDeleteSnapshot = request.onDeleteSnapshot,
            onPruneSnapshots = request.onPruneSnapshots,
            onExportSummary = request.onExportSummary,
        ).show()
    }

    private fun exportHistoryDiffSummary(
        workflowId: String,
        phase: SpecPhase,
        content: String,
    ): Result<String> {
        return runCatching {
            val basePath = project.basePath
                ?: throw IllegalStateException(SpecCodingBundle.message("history.error.projectBasePathUnavailable"))
            val exportDir = Path.of(basePath, ".spec-coding", "exports")
            Files.createDirectories(exportDir)

            val safeWorkflowId = workflowId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val fileName = "spec-history-diff-${safeWorkflowId}-${phase.name.lowercase()}-${System.currentTimeMillis()}.md"
            val target = exportDir.resolve(fileName)
            Files.writeString(target, content, StandardCharsets.UTF_8)
            fileName
        }
    }

    private fun reloadCurrentWorkflow(
        followCurrentPhase: Boolean = false,
        onUpdated: ((SpecWorkflow) -> Unit)? = null,
    ) {
        workflowLoadEntryCoordinator.reloadCurrentWorkflow(
            followCurrentPhase = followCurrentPhase,
            onUpdated = onUpdated,
        )
    }

    private fun showWorkflowLoadInProgress() {
        workflowStateApplicationAdapter.showWorkflowLoadInProgress()
    }

    private fun applyLoadedWorkflow(
        workflowId: String,
        loadedState: SpecWorkflowPanelLoadedState,
        followCurrentPhase: Boolean = false,
        previousSelectedWorkflowId: String? = null,
        onUpdated: ((SpecWorkflow) -> Unit)? = null,
    ) {
        invokeLaterSafe {
            loadedStateCoordinator.apply(
                request = SpecWorkflowLoadedStateApplyRequest(
                    workflowId = workflowId,
                    selectedWorkflowId = selectedWorkflowId,
                    loadedState = loadedState,
                    followCurrentPhase = followCurrentPhase,
                    previousSelectedWorkflowId = previousSelectedWorkflowId,
                ),
                callbacks = workflowStateApplicationAdapter.loadedStateCallbacks,
                onUpdated = onUpdated,
            )
        }
    }

    private fun hasVerificationArtifact(workflowId: String): Boolean {
        return runCatching {
            Files.exists(artifactService.locateArtifact(workflowId, StageId.VERIFY))
        }.getOrDefault(false)
    }

    private fun compactErrorMessage(error: Throwable?, fallback: String, maxLength: Int = 220): String {
        val compact = generateSequence(error) { it.cause }
            .mapNotNull { throwable ->
                throwable.message
                    ?.replace('\n', ' ')
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
            .firstOrNull { candidate -> isMeaningfulErrorMessage(candidate) }
            ?: fallback
        if (compact.length <= maxLength) {
            return compact
        }
        return compact.take((maxLength - 3).coerceAtLeast(0)).trimEnd() + "..."
    }

    private fun isMeaningfulErrorMessage(message: String): Boolean {
        val normalized = message.trim()
        if (normalized.isBlank()) {
            return false
        }
        if (normalized.lowercase(Locale.ROOT) in PLACEHOLDER_ERROR_MESSAGES) {
            return false
        }
        if (normalized.length <= 3 && PLACEHOLDER_SYMBOLS_REGEX.matches(normalized)) {
            return false
        }
        return ERROR_TEXT_CONTENT_REGEX.containsMatchIn(normalized)
    }

    private fun invokeLaterSafe(action: () -> Unit) {
        taskCoordinator.invokeLater(action)
    }

    internal fun isListModeForTest(): Boolean {
        return centerContentPanel.componentCount == 1 && centerContentPanel.getComponent(0) === listSectionContainer
    }

    internal fun isDetailModeForTest(): Boolean {
        return centerContentPanel.componentCount == 1 && centerContentPanel.getComponent(0) === workspacePanelContainer
    }

    internal fun selectedWorkflowIdForTest(): String? = selectedWorkflowId

    internal fun highlightedWorkflowIdForTest(): String? = highlightedWorkflowId

    internal fun workflowIdsForTest(): List<String> {
        return listPanel.itemsForTest().map { it.workflowId }
    }

    internal fun openWorkflowForTest(workflowId: String) {
        highlightedWorkflowId = workflowId
        listPanel.setSelectedWorkflow(workflowId)
        onWorkflowOpenedByUser(workflowId)
    }

    internal fun clickBackToListForTest() {
        backToListButton.doClick()
    }

    internal fun clickSwitchWorkflowForTest() {
        switchWorkflowButton.doClick()
    }

    internal fun clickCodeGraphForTest() {
        codeGraphButton.doClick()
    }

    internal fun isSwitchWorkflowPopupVisibleForTest(): Boolean {
        return workflowSwitcherUiHost.isVisibleForTest()
    }

    internal fun switchWorkflowPopupVisibleWorkflowIdsForTest(): List<String> {
        return workflowSwitcherUiHost.visibleWorkflowIdsForTest()
    }

    internal fun filterSwitchWorkflowPopupForTest(query: String) {
        workflowSwitcherUiHost.applySearchForTest(query)
    }

    internal fun confirmSwitchWorkflowPopupSelectionForTest() {
        workflowSwitcherUiHost.confirmSelectionForTest()
    }

    internal fun selectedSwitchWorkflowPopupSelectionForTest(): String? {
        return workflowSwitcherUiHost.selectedWorkflowIdForTest()
    }

    internal fun isBackButtonInlineForTest(): Boolean {
        return javax.swing.SwingUtilities.isDescendingFrom(backToListButton, workspaceCardPanel)
    }

    internal fun visibleWorkspaceSectionIdsForTest(): Set<SpecWorkflowWorkspaceSectionId> {
        return workspaceSectionItems
            .filterValues { it.isVisible }
            .keys
            .toCollection(linkedSetOf())
    }

    private fun focusStage(stageId: StageId) {
        if (focusedStage == stageId) {
            return
        }
        if (!detailPanel.allowStageFocusChange(stageId)) {
            return
        }
        focusedStage = stageId
        workflowWorkspacePresentationRequestBuilder.buildFocusStageRequest(
            workflow = currentWorkflow,
            appliedState = currentWorkspaceState,
        )?.let(::applyWorkspacePresentationRequest)
    }

    internal fun focusStageForTest(stageId: StageId) {
        focusStage(stageId)
    }

    private fun openWorkflowFromRequest(request: SpecToolWindowOpenRequest) {
        workflowLoadEntryCoordinator.openWorkflowFromRequest(
            request = request,
            currentWorkflowId = currentWorkflow?.id,
        )
    }

    private fun applyOpenRequestToCurrentWorkflow(request: SpecToolWindowOpenRequest) {
        request.focusedStage?.let(::focusStage)
        request.taskId?.let { taskId ->
            syncStructuredTaskSelection(taskId)
            if (supportsStructuredTasksDocumentWorkspaceView(currentWorkbenchState)) {
                selectedDocumentWorkspaceView = DocumentWorkspaceView.STRUCTURED_TASKS
                updateDocumentWorkspaceViewPresentation(currentWorkbenchState)
            }
        }
    }

    private fun publishWorkflowChatRefresh(
        workflowId: String,
        taskId: String? = null,
        reason: String,
        focusedStage: StageId? = StageId.IMPLEMENT,
    ) {
        runCatching {
            project.messageBus.syncPublisher(WorkflowChatRefreshListener.TOPIC)
                .onWorkflowChatRefreshRequested(
                    WorkflowChatRefreshEvent(
                        workflowId = workflowId,
                        taskId = taskId,
                        focusedStage = focusedStage,
                        reason = reason,
                    ),
                )
        }.onFailure { error ->
            logger.warn("Failed to publish workflow chat refresh event", error)
        }
    }

    private fun activateChatToolWindow(): Boolean {
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(ChatToolWindowFactory.TOOL_WINDOW_ID)
            ?: return false
        ChatToolWindowFactory.ensurePrimaryContents(project, toolWindow)
        if (!ChatToolWindowFactory.selectChatContent(toolWindow, project)) {
            return false
        }
        toolWindow.activate(null)
        return true
    }

    private fun onOverviewStageSelected(stageId: StageId) {
        focusStage(stageId)
    }

    private fun onWorkbenchActionRequested(action: SpecWorkflowWorkbenchAction) {
        workbenchCommandRouter.dispatch(action, currentWorkflow?.id)
    }

    internal fun focusedStageForTest(): StageId? = currentWorkbenchState?.focusedStage

    internal fun currentPrimaryActionKindForTest(): SpecWorkflowWorkbenchActionKind? =
        currentWorkbenchState?.primaryAction?.kind

    internal fun currentOverflowActionKindsForTest(): List<SpecWorkflowWorkbenchActionKind> =
        currentWorkbenchState?.overflowActions?.map { it.kind }.orEmpty()

    internal fun overviewSnapshotForTest(): Map<String, String> = overviewPanel.snapshotForTest()

    internal fun clickOverviewPrimaryActionForTest() {
        overviewPanel.clickPrimaryActionForTest()
    }

    internal fun clickOverviewStageForTest(stageId: StageId) {
        overviewPanel.clickStageForTest(stageId)
    }

    internal fun tasksSnapshotForTest(): Map<String, String> = tasksPanel.snapshotForTest()

    internal fun detailTasksSnapshotForTest(): Map<String, String> = detailTasksPanel.snapshotForTest()

    internal fun detailButtonStatesForTest(): Map<String, Any> = detailPanel.buttonStatesForTest()

    internal fun documentWorkspaceViewForTest(): String =
        if (::documentWorkspaceViewChrome.isInitialized && documentWorkspaceViewChrome.tabsPanel.isVisible) {
            selectedDocumentWorkspaceView.name
        } else {
            DocumentWorkspaceView.DOCUMENT.name
        }

    internal fun isDocumentWorkspaceViewTabsVisibleForTest(): Boolean =
        ::documentWorkspaceViewChrome.isInitialized && documentWorkspaceViewChrome.tabsPanel.isVisible

    internal fun clickDocumentWorkspaceViewForTest(view: String) {
        val targetView = runCatching { DocumentWorkspaceView.valueOf(view) }.getOrNull() ?: return
        documentWorkspaceViewChrome.buttons[targetView]?.doClick()
    }

    internal fun documentWorkspaceViewButtonsForTest(): List<String> =
        DocumentWorkspaceView.entries.mapNotNull { view ->
            documentWorkspaceViewChrome.buttons[view]?.text?.takeIf(String::isNotBlank)?.let { label ->
                "${view.name}:$label"
            }
        }

    internal fun documentWorkspaceViewLabelForTest(): String =
        if (::documentWorkspaceViewChrome.isInitialized) {
            documentWorkspaceViewChrome.label.text.orEmpty()
        } else {
            ""
        }

    internal fun documentWorkspaceViewSwitcherHeightForTest(): Int =
        if (::documentWorkspaceViewChrome.isInitialized) {
            documentWorkspaceViewChrome.switcherPanel.preferredSize.height
        } else {
            0
        }

    internal fun documentWorkspaceViewButtonHeightsForTest(): Map<String, Int> =
        DocumentWorkspaceView.entries.associate { view ->
            view.name to (documentWorkspaceViewChrome.buttons[view]?.preferredSize?.height ?: 0)
        }

    internal fun documentWorkspaceViewButtonWidthsForTest(): Map<String, Int> =
        DocumentWorkspaceView.entries.associate { view ->
            view.name to (documentWorkspaceViewChrome.buttons[view]?.preferredSize?.width ?: 0)
        }

    internal fun selectTaskForTest(taskId: String): Boolean {
        syncStructuredTaskSelection(taskId)
        return tasksPanel.selectedTaskId() == taskId
    }

    internal fun requestExecutionForTaskForTest(taskId: String): Boolean = tasksPanel.requestExecutionForTask(taskId)

    internal fun requestExecutionForDetailTaskForTest(taskId: String): Boolean =
        detailTasksPanel.requestExecutionForTask(taskId)

    internal fun clickOpenWorkflowChatForSelectedTaskForTest() {
        tasksPanel.clickOpenWorkflowChatForTest()
    }

    internal fun toolbarSnapshotForTest(): Map<String, String> {
        fun snapshot(button: JButton) = mapOf(
            "text" to button.text.orEmpty(),
            "iconId" to SpecWorkflowIcons.debugId(button.icon),
            "tooltip" to button.toolTipText.orEmpty(),
            "focusable" to button.isFocusable.toString(),
            "accessibleName" to button.accessibleContext.accessibleName.orEmpty(),
            "accessibleDescription" to button.accessibleContext.accessibleDescription.orEmpty(),
            "enabled" to button.isEnabled.toString(),
            "visible" to button.isVisible.toString(),
        )

        return buildMap {
            snapshot(backToListButton).forEach { (key, value) -> put("back.$key", value) }
            snapshot(switchWorkflowButton).forEach { (key, value) -> put("switch.$key", value) }
            snapshot(createWorkflowButton).forEach { (key, value) -> put("create.$key", value) }
            snapshot(refreshButton).forEach { (key, value) -> put("refresh.$key", value) }
            snapshot(deltaButton).forEach { (key, value) -> put("delta.$key", value) }
            snapshot(codeGraphButton).forEach { (key, value) -> put("codeGraph.$key", value) }
            snapshot(archiveButton).forEach { (key, value) -> put("archive.$key", value) }
        }
    }

    internal fun selectedProviderIdForTest(): String? = providerComboBox.selectedItem as? String

    internal fun selectedModelIdForTest(): String? = (modelComboBox.selectedItem as? ModelInfo)?.id

    internal fun clearToolbarModelSelectionForTest() {
        modelComboBox.selectedItem = null
    }

    internal fun selectToolbarModelForTest(providerId: String, modelId: String) {
        providerComboBox.selectedItem = providerId
        val targetModel = (0 until modelComboBox.itemCount)
            .map { index -> modelComboBox.getItemAt(index) }
            .firstOrNull { it.id == modelId }
        if (targetModel != null) {
            modelComboBox.selectedItem = targetModel
        }
    }

    internal fun selectedDocumentPhaseForTest(): String? = detailPanel.selectedPhaseNameForTest()

    internal fun currentDocumentPreviewTextForTest(): String = detailPanel.currentPreviewTextForTest()

    internal fun selectDocumentPhaseForTest(phase: SpecPhase) {
        detailPanel.selectPhaseForTest(phase)
    }

    internal fun clickDetailNextPhaseForTest() {
        detailPanel.clickNextPhaseForTest()
    }

    internal fun clickDetailGoBackForTest() {
        detailPanel.clickGoBackForTest()
    }

    internal fun clickArchiveForTest() {
        archiveButton.doClick()
    }

    internal fun saveDocumentForTest(phase: SpecPhase, content: String) {
        lastDocumentSaveResultForTest = null
        workflowDocumentSaveCoordinator.save(phase, content) { result ->
            lastDocumentSaveResultForTest = result
            result.onSuccess { updated ->
                detailPanel.updateWorkflow(updated)
            }
        }
    }

    internal fun lastDocumentSaveResultForTest(): Result<SpecWorkflow>? = lastDocumentSaveResultForTest

    internal fun composerSourceChipLabelsForTest(): List<String> = detailPanel.composerSourceChipLabelsForTest()

    internal fun composerSourceMetaTextForTest(): String = detailPanel.composerSourceMetaTextForTest()

    internal fun composerSourceHintTextForTest(): String = detailPanel.composerSourceHintTextForTest()

    internal fun composerCodeContextSummaryChipLabelsForTest(): List<String> =
        detailPanel.composerCodeContextSummaryChipLabelsForTest()

    internal fun composerCodeContextCandidateLabelsForTest(): List<String> =
        detailPanel.composerCodeContextCandidateLabelsForTest()

    internal fun composerCodeContextMetaTextForTest(): String = detailPanel.composerCodeContextMetaTextForTest()

    internal fun composerCodeContextHintTextForTest(): String = detailPanel.composerCodeContextHintTextForTest()

    internal fun currentStatusTextForTest(): String = statusStripUiHost.currentStatusTextForTest()

    internal fun currentStatusActionLabelsForTest(): List<String> = statusStripUiHost.currentStatusActionLabelsForTest()

    internal fun isComposerSourceRestoreVisibleForTest(): Boolean = detailPanel.isComposerSourceRestoreVisibleForTest()

    internal fun clickAddWorkflowSourcesForTest() {
        detailPanel.clickAddWorkflowSourcesForTest()
    }

    internal fun clickRestoreWorkflowSourcesForTest() {
        detailPanel.clickRestoreWorkflowSourcesForTest()
    }

    internal fun clickRemoveWorkflowSourceForTest(sourceId: String): Boolean {
        return detailPanel.clickRemoveWorkflowSourceForTest(sourceId)
    }

    internal fun isClarifyingForTest(): Boolean = detailPanel.isClarifyingForTest()

    internal fun pendingOpenWorkflowRequestForTest(): SpecToolWindowOpenRequest? = pendingOpenWorkflowRequest

    internal fun deleteWorkflowForTest(workflowId: String) {
        onDeleteWorkflow(workflowId)
    }

    internal fun currentClarificationQuestionsTextForTest(): String = detailPanel.clarificationQuestionsTextForTest()

    internal fun setComposerInputTextForTest(text: String) {
        detailPanel.setInputTextForTest(text)
    }

    internal fun clickGenerateForTest() {
        detailPanel.clickGenerateForTest()
    }

    internal fun requestGenerationForTest(input: String) {
        onGenerate(input)
    }

    internal fun runVerificationForTest(workflowId: String) {
        verifyDeltaCoordinator.runVerification(workflowId)
    }

    internal fun createWorktreeForTest(shortName: String = "feature", baseBranch: String = "main") {
        currentWorkflow?.let { workflow ->
            worktreeCoordinator.createAndSwitch(
                SpecWorkflowWorktreeCreateRequest(
                    specTaskId = workflow.id,
                    shortName = shortName,
                    baseBranch = baseBranch,
                ),
            )
        }
    }

    internal fun compareWorkflowBaselineForTest(baselineWorkflowId: String, title: String = "Baseline") {
        currentWorkflow?.let { workflow ->
            verifyDeltaCoordinator.compareBaseline(
                SpecWorkflowVerifyDeltaCompareRequest(
                    targetWorkflow = workflow,
                    choice = SpecWorkflowReferenceBaselineChoice(
                        workflowId = baselineWorkflowId,
                        title = title,
                    ),
                ),
            )
        }
    }

    internal fun startRequirementsClarifyThenFillForTest(
        workflowId: String,
        missingSections: List<RequirementsSectionId>,
    ): Boolean = requirementsRepairEntryCoordinator.startClarifyThenFill(workflowId, missingSections)

    internal fun workspaceSummarySnapshotForTest(): Map<String, String> {
        return workspacePresentationUiHost.summarySnapshotForTest()
    }

    override fun dispose() {
        _isDisposed = true
        workflowExternalEventActionCoordinator.cancelPendingDocumentReload()
        CliDiscoveryService.getInstance().removeDiscoveryListener(discoveryListener)
        liveProgressEventCoalesceTimer.stop()
        liveProgressRefreshTimer.stop()
        specTaskExecutionService.removeLiveProgressListener(liveProgressListener)
        workflowSwitcherUiHost.cancel()
        generationExecutionCoordinator.cancelActiveRequest("Spec workflow panel disposed")
        taskCoordinator.dispose()
    }

    companion object {
        private val TOOLBAR_BG = JBColor(Color(248, 250, 254), Color(58, 64, 74))
        private val TOOLBAR_BORDER = JBColor(Color(212, 222, 239), Color(89, 100, 117))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val STATUS_CHIP_BG = JBColor(Color(236, 244, 255), Color(66, 76, 91))
        private val STATUS_CHIP_BORDER = JBColor(Color(178, 198, 226), Color(99, 116, 140))
        private val STATUS_TEXT_FG = JBColor(Color(52, 72, 106), Color(201, 213, 232))
        private val STATUS_SUCCESS_FG = JBColor(Color(42, 128, 74), Color(131, 208, 157))
        private val PANEL_SECTION_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val PANEL_SECTION_BORDER = JBColor(Color(204, 215, 233), Color(84, 92, 105))
        private val DETAIL_COLUMN_BG = JBColor(Color(244, 249, 255), Color(56, 62, 72))
        private val LIST_SECTION_BG = JBColor(Color(242, 248, 255), Color(59, 66, 77))
        private val LIST_SECTION_BORDER = JBColor(Color(198, 212, 234), Color(89, 100, 117))
        private val PHASE_SECTION_BG = JBColor(Color(240, 246, 255), Color(62, 69, 80))
        private val DETAIL_SECTION_BG = JBColor(Color(249, 252, 255), Color(50, 56, 65))
        private val DETAIL_SECTION_BORDER = JBColor(Color(204, 217, 236), Color(84, 94, 109))
        private const val LIVE_PROGRESS_EVENT_COALESCE_MILLIS = 180
        private const val DOCUMENT_WORKSPACE_VIEWPORT_HEIGHT = 360
        private val PLACEHOLDER_ERROR_MESSAGES = setOf("-", "--", "...", "null", "none", "unknown")
        private val PLACEHOLDER_SYMBOLS_REGEX = Regex("""^[\p{Punct}\s]+$""")
        private val ERROR_TEXT_CONTENT_REGEX = Regex("""[A-Za-z0-9\p{IsHan}]""")
        private const val DOCUMENT_RELOAD_DEBOUNCE_MILLIS = 300L
        private const val WORKSPACE_CARD_EMPTY = "empty"
        private const val WORKSPACE_CARD_CONTENT = "content"
        private val SPEC_DOCUMENT_FILE_NAMES = (SpecPhase.entries
            .map { it.outputFileName } + listOfNotNull(StageId.VERIFY.artifactFileName))
            .toSet()

        private fun chooseWorkflowSourceFiles(
            project: Project,
            constraints: WorkflowSourceImportConstraints,
        ): List<Path> {
            val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor().apply {
                title = SpecCodingBundle.message("spec.detail.sources.chooser.title")
                description = SpecCodingBundle.message(
                    "spec.detail.sources.chooser.description",
                    WorkflowSourceImportSupport.formatAllowedExtensions(constraints),
                    WorkflowSourceImportSupport.formatFileSize(constraints.maxFileSizeBytes),
                )
            }
            return FileChooser.chooseFiles(descriptor, project, null)
                .map { virtualFile -> Path.of(virtualFile.path) }
        }
    }
}
