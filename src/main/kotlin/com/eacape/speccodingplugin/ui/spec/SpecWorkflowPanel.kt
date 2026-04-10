package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.CodeGraphRenderer
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
import com.eacape.speccodingplugin.ui.worktree.NewWorktreeDialog
import com.eacape.speccodingplugin.worktree.WorktreeManager
import com.intellij.ide.BrowserUtil
import com.intellij.CommonBundle
import com.intellij.openapi.components.service
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.awt.CardLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.FlowLayout
import java.awt.Font
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.Timer

class SpecWorkflowPanel(
    private val project: Project,
    private val sourceFileChooser: (Project, WorkflowSourceImportConstraints) -> List<Path> = ::chooseWorkflowSourceFiles,
    private val sourceImportConstraints: WorkflowSourceImportConstraints = WorkflowSourceImportConstraints(),
    private val warningDialogPresenter: (Project, String, String) -> Unit = { dialogProject, message, title ->
        Messages.showWarningDialog(dialogProject, message, title)
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
    private val workspacePresentationTelemetry = SpecWorkflowWorkspacePresentationTelemetryTracker(logger)
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
        buildUiSnapshot = ::buildWorkflowUiSnapshot,
        buildTaskLiveProgressByTaskId = ::buildTaskLiveProgressByTaskId,
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
        buildRuntimeTroubleshootingActions = { workflowId, trigger ->
            buildRuntimeTroubleshootingActions(workflowId, trigger)
        },
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
        buildRuntimeTroubleshootingActions = { workflowId, trigger ->
            buildRuntimeTroubleshootingActions(workflowId, trigger)
        },
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
        showDeltaDialog = ::showDeltaDialog,
        reloadCurrentWorkflow = {
            reloadCurrentWorkflow()
        },
        setStatusText = ::setStatusText,
        showFailureStatus = ::setStatusWithTroubleshooting,
        buildRuntimeTroubleshootingActions = { workflowId, trigger ->
            buildRuntimeTroubleshootingActions(workflowId, trigger)
        },
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
        cancelActiveGenerationRequest = ::cancelActiveGenerationRequest,
        requestClarificationDraft = { request ->
            requestClarificationDraft(
                context = request.context,
                input = request.input,
                options = request.options,
                suggestedDetails = request.suggestedDetails,
                seedQuestionsMarkdown = request.seedQuestionsMarkdown,
                seedStructuredQuestions = request.seedStructuredQuestions,
                clarificationRound = request.clarificationRound,
            )
        },
        runGeneration = { request ->
            runGeneration(
                workflowId = request.workflowId,
                input = request.input,
                options = request.options,
            )
        },
        launchRequirementsRepairClarification = { request ->
            launchRequirementsRepairClarification(
                workflow = request.workflow,
                input = request.input,
                suggestedDetails = request.suggestedDetails,
                pendingRetry = request.pendingRetry,
                clarificationRound = request.clarificationRound,
            )
        },
        continueRequirementsRepairAfterClarification = { request ->
            continueRequirementsRepairAfterClarification(
                workflowId = request.workflowId,
                pendingRetry = request.pendingRetry,
                input = request.input,
                confirmedContext = request.confirmedContext,
            )
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
        onTemplateCloneRequested = ::onTemplateCloneRequested,
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
    private val workbenchCommandRouter = SpecWorkflowWorkbenchCommandRouter(
        callbacks = object : SpecWorkflowWorkbenchCommandCallbacks {
            override fun advance() = onAdvanceStageRequested()

            override fun jump(workflowId: String, targetStage: StageId) = previewAndJumpToStage(workflowId, targetStage)

            override fun jumpFallback() = onJumpStageRequested()

            override fun rollback(workflowId: String, targetStage: StageId) = executeRollbackStage(workflowId, targetStage)

            override fun rollbackFallback() = onRollbackStageRequested()

            override fun selectTask(taskId: String) = syncStructuredTaskSelection(taskId)

            override fun requestTaskExecution(taskId: String): Boolean = tasksPanel.requestExecutionForTask(taskId)

            override fun requestTaskCompletion(taskId: String): Boolean = tasksPanel.requestCompletionForTask(taskId)

            override fun cancelTaskExecution(taskId: String) = onTaskExecutionCancelRequested(taskId)

            override fun openTaskChat(workflowId: String, taskId: String) =
                taskChatCoordinator.openTaskWorkflowChat(workflowId, taskId)

            override fun runVerify(workflowId: String) = verifyDeltaCoordinator.runVerification(workflowId)

            override fun previewVerifyPlan(workflowId: String) = onPreviewVerificationPlanRequested(workflowId)

            override fun openVerification(workflowId: String) =
                verifyDeltaCoordinator.openVerificationDocument(workflowId)

            override fun showDelta() = onShowDelta()

            override fun completeWorkflow() = onComplete()

            override fun archiveWorkflow() = onArchiveWorkflow()

            override fun showStatus(text: String) = setStatusText(text)
        },
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
        onClarifyThenFillRequested = ::startRequirementsClarifyThenFill,
        onRepairRequirementsRequested = ::repairRequirementsArtifactFromGate,
        onRepairTasksRequested = ::repairTasksArtifactFromGate,
    )
    private val statusLabel = JBLabel("")
    private val statusActionPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
    private val statusChipPanel = JPanel(BorderLayout())
    private val statusTroubleshootingActionDispatcher = SpecWorkflowTroubleshootingActionDispatcher(
        SpecWorkflowRuntimeTroubleshootingActionCallbacks(
            openSettingsAction = ::openTroubleshootingSettings,
            openBundledDemoAction = ::openBundledDemoProject,
            openCreateWorkflowDialog = { template ->
                onCreateWorkflow(template)
            },
        ),
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
    private val documentWorkspaceViewButtons = linkedMapOf<DocumentWorkspaceView, JButton>()
    private lateinit var centerContentPanel: JPanel
    private lateinit var listSectionContainer: JPanel
    private lateinit var workspacePanelContainer: JPanel
    private lateinit var mainSplitPane: JSplitPane
    private val workspaceCardLayout = CardLayout()
    private val workspaceCardPanel = JPanel(workspaceCardLayout)
    private lateinit var documentWorkspaceViewLabel: JBLabel
    private val workspaceSummaryTitleLabel = JBLabel()
    private val workspaceSummaryMetaLabel = JBLabel()
    private val workspaceSummaryFocusLabel = JBLabel()
    private val workspaceSummaryHintLabel = JBLabel()
    private val workspaceStageMetric = createWorkspaceSummaryMetric()
    private val workspaceGateMetric = createWorkspaceSummaryMetric()
    private val workspaceTasksMetric = createWorkspaceSummaryMetric()
    private val workspaceVerifyMetric = createWorkspaceSummaryMetric()
    private lateinit var overviewSection: SpecCollapsibleWorkspaceSection
    private lateinit var tasksSection: SpecCollapsibleWorkspaceSection
    private lateinit var gateSection: SpecCollapsibleWorkspaceSection
    private lateinit var verifySection: SpecCollapsibleWorkspaceSection
    private lateinit var documentsSection: SpecCollapsibleWorkspaceSection
    private lateinit var documentWorkspaceViewTabsPanel: JPanel
    private lateinit var documentWorkspaceViewSwitcherPanel: JPanel
    private lateinit var documentWorkspaceViewCardPanel: JPanel
    private val workspaceSectionItems = mutableMapOf<SpecWorkflowWorkspaceSectionId, JPanel>()
    private val workspaceSectionOverrides = mutableMapOf<SpecWorkflowWorkspaceSectionId, Boolean>()
    private var workspaceSectionPresetToken: String? = null
    private var currentOverviewState: SpecWorkflowOverviewState? = null
    private var currentWorkbenchState: SpecWorkflowStageWorkbenchState? = null
    private var currentVerifyDeltaState: SpecWorkflowVerifyDeltaState? = null
    private var currentGateResult: GateResult? = null
    private var currentStructuredTasks: List<StructuredTask> = emptyList()
    private var currentTaskLiveProgressByTaskId: Map<String, TaskExecutionLiveProgress> = emptyMap()
    private var currentWorkflowSources: List<WorkflowSourceAsset> = emptyList()
    private val composerSelectedSourceIdsByWorkflowId = mutableMapOf<String, LinkedHashSet<String>>()
    private var activeGenerationJob: Job? = null
    private var activeGenerationRequest: SpecWorkflowActiveGenerationRequest? = null
    private var pendingDocumentReloadJob: Job? = null
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
    private var workflowSwitcherPopup: SpecWorkflowSwitcherPopup? = null
    private var initialWorkflowRefreshTriggered = false

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
            onAddWorkflowSourcesRequested = ::onAddWorkflowSourcesRequested,
            onRemoveWorkflowSourceRequested = ::onRemoveWorkflowSourceRequested,
            onRestoreWorkflowSourcesRequested = ::onRestoreWorkflowSourcesRequested,
            onClarificationConfirm = clarificationActionCoordinator::confirm,
            onClarificationRegenerate = clarificationActionCoordinator::regenerate,
            onClarificationSkip = clarificationActionCoordinator::skip,
            onClarificationCancel = clarificationActionCoordinator::cancel,
            onNextPhase = ::onNextPhase,
            onGoBack = ::onGoBack,
            onComplete = ::onComplete,
            onPauseResume = ::onPauseResume,
            onOpenInEditor = ::onOpenInEditor,
            onOpenArtifactInEditor = ::onOpenArtifactInEditor,
            onShowHistoryDiff = ::onShowHistoryDiff,
            onSaveDocument = ::onSaveDocument,
            onClarificationDraftAutosave = clarificationActionCoordinator::autosave,
        )

        setupUI()
        CliDiscoveryService.getInstance().addDiscoveryListener(discoveryListener)
        subscribeToLocaleEvents()
        subscribeToGlobalConfigEvents()
        subscribeToToolWindowControlEvents()
        subscribeToWorkflowEvents()
        subscribeToDocumentFileEvents()
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
        createWorktreeButton.isEnabled = false
        mergeWorktreeButton.isEnabled = false
        switchWorkflowButton.isEnabled = false
        createWorktreeButton.isVisible = false
        mergeWorktreeButton.isVisible = false
        deltaButton.isEnabled = false
        codeGraphButton.isEnabled = true
        archiveButton.isEnabled = false
        codeGraphButton.isVisible = false
        archiveButton.isVisible = false
        backToListButton.isEnabled = false
        switchWorkflowButton.addActionListener { onSwitchWorkflowRequested() }
        createWorkflowButton.addActionListener { onCreateWorkflow() }
        createWorktreeButton.addActionListener { onCreateWorktree() }
        mergeWorktreeButton.addActionListener { onMergeWorktree() }
        deltaButton.addActionListener { onShowDelta() }
        codeGraphButton.addActionListener { onShowCodeGraph() }
        archiveButton.addActionListener { onArchiveWorkflow() }
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

        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = STATUS_TEXT_FG
        setupGenerationControls()

        val controlsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(providerComboBox)
            add(modelLabel)
            add(modelComboBox)
            add(statusChipPanel)
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
        statusChipPanel.isOpaque = true
        statusChipPanel.background = STATUS_CHIP_BG
        statusChipPanel.border = SpecUiStyle.roundedCardBorder(
            lineColor = STATUS_CHIP_BORDER,
            arc = JBUI.scale(10),
            top = 1,
            left = 6,
            bottom = 1,
            right = 6,
        )
        statusActionPanel.isOpaque = false
        statusActionPanel.isVisible = false
        statusChipPanel.removeAll()
        statusChipPanel.add(statusLabel, BorderLayout.CENTER)
        statusChipPanel.add(statusActionPanel, BorderLayout.EAST)
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
        setStatusText(null)
        showWorkspaceEmptyState()
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

    private fun showWorkflowListOnlyMode() {
        isWorkspaceMode = false
        reparentToCenter(listSectionContainer)
    }

    private fun showWorkflowWorkspaceMode() {
        isWorkspaceMode = true
        reparentToCenter(workspacePanelContainer)
    }

    private fun reparentToCenter(component: Component) {
        detachFromParent(component)
        if (centerContentPanel.componentCount == 1 && centerContentPanel.getComponent(0) === component) {
            return
        }
        centerContentPanel.removeAll()
        centerContentPanel.add(component, BorderLayout.CENTER)
        centerContentPanel.revalidate()
        centerContentPanel.repaint()
    }

    private fun detachFromParent(component: Component) {
        component.parent?.remove(component)
    }

    private fun createWorkspaceSummaryMetric(): WorkspaceSummaryMetric {
        val titleLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont().deriveFont(10.5f)
            foreground = WORKSPACE_SUMMARY_LABEL_FG
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        val valueLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD, 10.5f)
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        val root = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)).apply {
            isOpaque = false
            isVisible = false
            add(titleLabel)
            add(valueLabel)
        }
        return WorkspaceSummaryMetric(
            root = root,
            titleLabel = titleLabel,
            valueLabel = valueLabel,
        )
    }

    private fun workspaceChipColors(tone: SpecWorkflowWorkspaceChipTone): WorkspaceChipColors {
        return when (tone) {
            SpecWorkflowWorkspaceChipTone.INFO -> WorkspaceChipColors(
                foreground = WORKSPACE_INFO_CHIP_FG,
            )

            SpecWorkflowWorkspaceChipTone.SUCCESS -> WorkspaceChipColors(
                foreground = WORKSPACE_SUCCESS_CHIP_FG,
            )

            SpecWorkflowWorkspaceChipTone.WARNING -> WorkspaceChipColors(
                foreground = WORKSPACE_WARNING_CHIP_FG,
            )

            SpecWorkflowWorkspaceChipTone.ERROR -> WorkspaceChipColors(
                foreground = WORKSPACE_ERROR_CHIP_FG,
            )

            SpecWorkflowWorkspaceChipTone.MUTED -> WorkspaceChipColors(
                foreground = WORKSPACE_MUTED_CHIP_FG,
            )
        }
    }

    private fun buildWorkspaceCardPanel(): JPanel {
        val sectionsStack = object : JPanel(), Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

            override fun getScrollableUnitIncrement(
                visibleRect: Rectangle,
                orientation: Int,
                direction: Int,
            ): Int = JBUI.scale(WORKSPACE_SCROLL_UNIT_INCREMENT)

            override fun getScrollableBlockIncrement(
                visibleRect: Rectangle,
                orientation: Int,
                direction: Int,
            ): Int {
                val unit = getScrollableUnitIncrement(visibleRect, orientation, direction)
                return if (orientation == SwingConstants.VERTICAL) {
                    (visibleRect.height - unit).coerceAtLeast(unit)
                } else {
                    (visibleRect.width - unit).coerceAtLeast(unit)
                }
            }

            override fun getScrollableTracksViewportWidth(): Boolean = true

            override fun getScrollableTracksViewportHeight(): Boolean = false
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }

        val summaryCard = buildWorkspaceSummaryCard()
        sectionsStack.add(createWorkspaceStackItem(summaryCard))
        sectionsStack.add(Box.createVerticalStrut(JBUI.scale(6)))

        overviewSection = createWorkspaceSection(
            id = SpecWorkflowWorkspaceSectionId.OVERVIEW,
            titleProvider = { SpecCodingBundle.message("spec.toolwindow.section.overview") },
            content = overviewPanel,
        )
        tasksSection = createWorkspaceSection(
            id = SpecWorkflowWorkspaceSectionId.TASKS,
            titleProvider = { SpecCodingBundle.message("spec.toolwindow.section.tasks") },
            content = tasksPanel,
            maxExpandedBodyHeight = SCROLLABLE_WORKSPACE_SECTION_MAX_HEIGHT,
        )
        gateSection = createWorkspaceSection(
            id = SpecWorkflowWorkspaceSectionId.GATE,
            titleProvider = { SpecCodingBundle.message("spec.toolwindow.section.gate") },
            content = gateDetailsPanel,
            maxExpandedBodyHeight = SCROLLABLE_WORKSPACE_SECTION_MAX_HEIGHT,
        )
        verifySection = createWorkspaceSection(
            id = SpecWorkflowWorkspaceSectionId.VERIFY,
            titleProvider = { SpecCodingBundle.message("spec.toolwindow.section.verify") },
            content = verifyDeltaPanel,
            maxExpandedBodyHeight = SCROLLABLE_WORKSPACE_SECTION_MAX_HEIGHT,
        )
        documentsSection = createWorkspaceSection(
            id = SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            titleProvider = { SpecCodingBundle.message("spec.toolwindow.section.documents") },
            content = buildDocumentWorkspaceContent(),
        )

        workspaceSectionItems.clear()
        listOf(
            SpecWorkflowWorkspaceSectionId.OVERVIEW to overviewSection,
            SpecWorkflowWorkspaceSectionId.TASKS to tasksSection,
            SpecWorkflowWorkspaceSectionId.GATE to gateSection,
            SpecWorkflowWorkspaceSectionId.VERIFY to verifySection,
            SpecWorkflowWorkspaceSectionId.DOCUMENTS to documentsSection,
        ).forEachIndexed { index, (sectionId, section) ->
            val item = createWorkspaceSectionItem(
                content = section,
                addBottomGap = index < 4,
            )
            workspaceSectionItems[sectionId] = item
            sectionsStack.add(item)
        }

        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            val workspaceScrollPane = JBScrollPane(sectionsStack).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                viewport.isOpaque = false
                isOpaque = false
                SpecUiStyle.applyFastVerticalScrolling(
                    scrollPane = this,
                    unitIncrement = WORKSPACE_SCROLL_UNIT_INCREMENT,
                    blockIncrement = WORKSPACE_SCROLL_BLOCK_INCREMENT,
                )
            }
            add(
                workspaceScrollPane,
                BorderLayout.CENTER,
            )
        }

        workspaceCardPanel.apply {
            isOpaque = false
            add(buildWorkspaceEmptyState(), WORKSPACE_CARD_EMPTY)
            add(contentPanel, WORKSPACE_CARD_CONTENT)
        }
        return workspaceCardPanel
    }

    private fun buildDocumentWorkspaceContent(): JPanel {
        documentWorkspaceViewButtons.clear()
        documentWorkspaceViewLabel = JBLabel(SpecCodingBundle.message("spec.toolwindow.documents.view.label")).apply {
            foreground = DOCUMENT_WORKSPACE_VIEW_LABEL_FG
            font = JBUI.Fonts.smallFont().deriveFont(DOCUMENT_WORKSPACE_VIEW_LABEL_FONT_SIZE)
        }
        documentWorkspaceViewSwitcherPanel = JPanel(
            FlowLayout(
                FlowLayout.LEFT,
                JBUI.scale(DOCUMENT_WORKSPACE_VIEW_SWITCHER_GAP),
                0,
            ),
        ).apply {
            isOpaque = true
            background = DOCUMENT_WORKSPACE_VIEW_GROUP_BG
            border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(
                    DOCUMENT_WORKSPACE_VIEW_GROUP_BORDER,
                    JBUI.scale(DOCUMENT_WORKSPACE_VIEW_GROUP_ARC),
                ),
                JBUI.Borders.empty(
                    DOCUMENT_WORKSPACE_VIEW_GROUP_INSET,
                    DOCUMENT_WORKSPACE_VIEW_GROUP_INSET,
                ),
            )
        }
        documentWorkspaceViewTabsPanel = JPanel(
            FlowLayout(
                FlowLayout.LEFT,
                JBUI.scale(DOCUMENT_WORKSPACE_VIEW_ROW_GAP),
                0,
            ),
        ).apply {
            isOpaque = false
            add(documentWorkspaceViewLabel)
            add(documentWorkspaceViewSwitcherPanel)
        }
        documentWorkspaceViewSwitcherPanel.add(
            createDocumentWorkspaceViewButton(
                view = DocumentWorkspaceView.DOCUMENT,
                labelKey = "spec.toolwindow.documents.view.document",
                tooltipKey = "spec.toolwindow.documents.view.document.tooltip",
            ),
        )
        documentWorkspaceViewSwitcherPanel.add(
            createDocumentWorkspaceViewButton(
                view = DocumentWorkspaceView.STRUCTURED_TASKS,
                labelKey = "spec.toolwindow.documents.view.structuredTasks",
                tooltipKey = "spec.toolwindow.documents.view.structuredTasks.tooltip",
            ),
        )
        documentWorkspaceViewCardPanel = JPanel(CardLayout()).apply {
            isOpaque = false
            add(detailPanel, DOCUMENT_WORKSPACE_CARD_DOCUMENT)
            add(detailTasksPanel, DOCUMENT_WORKSPACE_CARD_STRUCTURED_TASKS)
        }
        val container = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(documentWorkspaceViewTabsPanel, BorderLayout.NORTH)
            add(documentWorkspaceViewCardPanel, BorderLayout.CENTER)
        }
        updateDocumentWorkspaceViewPresentation(null)
        return container
    }

    private fun createDocumentWorkspaceViewButton(
        view: DocumentWorkspaceView,
        labelKey: String,
        tooltipKey: String,
    ): JButton {
        return JButton().apply {
            isFocusable = false
            isFocusPainted = false
            isOpaque = true
            isBorderPainted = true
            isContentAreaFilled = true
            isRolloverEnabled = true
            margin = JBUI.emptyInsets()
            installToolbarButtonCursorTracking(this)
            addActionListener {
                selectedDocumentWorkspaceView = view
                updateDocumentWorkspaceViewPresentation(currentWorkbenchState)
            }
            model.addChangeListener {
                refreshDocumentWorkspaceViewButtonStyle(this)
            }
            putClientProperty("documentWorkspaceView", view)
            text = SpecCodingBundle.message(labelKey)
            toolTipText = SpecCodingBundle.message(tooltipKey)
            font = JBUI.Fonts.smallFont()
        }
            .also { documentWorkspaceViewButtons[view] = it }
    }

    private fun createWorkspaceSectionItem(
        content: Component,
        addBottomGap: Boolean,
    ): JPanel {
        return createWorkspaceStackItem(
            component = createSectionContainer(
                content,
                padding = WORKSPACE_SECTION_CARD_PADDING,
                backgroundColor = DETAIL_SECTION_BG,
                borderColor = DETAIL_SECTION_BORDER,
            ),
            addBottomGap = addBottomGap,
        )
    }

    private fun createWorkspaceStackItem(
        component: Component,
        addBottomGap: Boolean = false,
    ): JPanel {
        return object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension {
                val preferred = preferredSize
                return Dimension(Int.MAX_VALUE, preferred.height)
            }
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            if (addBottomGap) {
                border = JBUI.Borders.emptyBottom(6)
            }
            add(component, BorderLayout.CENTER)
        }
    }

    private fun buildWorkspaceSummaryCard(): JPanel {
        workspaceSummaryTitleLabel.font = JBUI.Fonts.label().deriveFont(Font.BOLD, 13f)
        workspaceSummaryTitleLabel.foreground = WORKSPACE_SUMMARY_TITLE_FG
        workspaceSummaryMetaLabel.font = JBUI.Fonts.smallFont()
        workspaceSummaryMetaLabel.foreground = WORKSPACE_SUMMARY_META_FG
        workspaceSummaryFocusLabel.font = JBUI.Fonts.label().deriveFont(Font.BOLD, 12.5f)
        workspaceSummaryFocusLabel.foreground = WORKSPACE_SUMMARY_TITLE_FG
        workspaceSummaryHintLabel.font = JBUI.Fonts.smallFont()
        workspaceSummaryHintLabel.foreground = WORKSPACE_SUMMARY_META_FG

        val titleStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(workspaceSummaryTitleLabel)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(workspaceSummaryMetaLabel)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(workspaceSummaryFocusLabel)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(workspaceSummaryHintLabel)
        }
        val headerRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(backToListButton)
                },
                BorderLayout.WEST,
            )
            add(titleStack, BorderLayout.CENTER)
        }
        val chipRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(workspaceStageMetric.root)
            add(workspaceGateMetric.root)
            add(workspaceTasksMetric.root)
            add(workspaceVerifyMetric.root)
        }

        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            name = "workspaceSummaryCard"
            isOpaque = true
            background = WORKSPACE_SUMMARY_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = WORKSPACE_SUMMARY_BORDER,
                arc = JBUI.scale(16),
                top = 8,
                left = 10,
                bottom = 8,
                right = 10,
            )
            add(
                JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                    isOpaque = false
                    add(headerRow, BorderLayout.NORTH)
                    add(chipRow, BorderLayout.SOUTH)
                },
                BorderLayout.CENTER,
            )
        }
    }

    private fun buildWorkspaceEmptyState(): JPanel {
        val titleLabel = JBLabel(SpecCodingBundle.message("spec.detail.noWorkflow")).apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD, 13f)
            foreground = WORKSPACE_EMPTY_TITLE_FG
        }
        val descriptionLabel = JBLabel(
            "<html>${SpecCodingBundle.message("spec.toolwindow.overview.empty")}</html>",
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = WORKSPACE_EMPTY_DESCRIPTION_FG
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = true
            background = DETAIL_SECTION_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = DETAIL_SECTION_BORDER,
                arc = JBUI.scale(16),
                top = 18,
                left = 18,
                bottom = 18,
                right = 18,
            )
            add(titleLabel, BorderLayout.NORTH)
            add(descriptionLabel, BorderLayout.CENTER)
        }
    }

    private fun createWorkspaceSection(
        id: SpecWorkflowWorkspaceSectionId,
        titleProvider: () -> String,
        content: Component,
        maxExpandedBodyHeight: Int? = null,
    ): SpecCollapsibleWorkspaceSection {
        return SpecCollapsibleWorkspaceSection(
            titleProvider = titleProvider,
            content = content,
            expandedInitially = true,
            maxExpandedBodyHeight = maxExpandedBodyHeight,
            onExpandedChanged = { expanded ->
                workspaceSectionOverrides[id] = expanded
            },
        )
    }

    private fun showWorkspaceEmptyState() {
        showWorkflowListOnlyMode()
        backToListButton.isEnabled = false
        workspaceCardLayout.show(workspaceCardPanel, WORKSPACE_CARD_EMPTY)
        workspaceSectionOverrides.clear()
        workspaceSectionPresetToken = null
        currentOverviewState = null
        currentWorkbenchState = null
        currentVerifyDeltaState = null
        currentGateResult = null
        currentStructuredTasks = emptyList()
        currentTaskLiveProgressByTaskId = emptyMap()
        liveProgressRefreshTimer.stop()
        focusedStage = null
        workspaceSummaryTitleLabel.text = ""
        workspaceSummaryMetaLabel.text = ""
        workspaceSummaryFocusLabel.text = ""
        workspaceSummaryHintLabel.text = ""
        clearWorkspaceMetric(workspaceStageMetric)
        clearWorkspaceMetric(workspaceGateMetric)
        clearWorkspaceMetric(workspaceTasksMetric)
        clearWorkspaceMetric(workspaceVerifyMetric)
        if (::overviewSection.isInitialized) {
            workspaceSections().values.forEach { section ->
                section.setSummary(null)
                section.setExpanded(true, notify = false)
            }
        }
        workspaceSectionItems.values.forEach { item ->
            item.isVisible = true
        }
    }

    private fun showWorkspaceContent() {
        showWorkflowWorkspaceMode()
        backToListButton.isEnabled = true
        workspaceCardLayout.show(workspaceCardPanel, WORKSPACE_CARD_CONTENT)
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
        applyStatusPresentation(text, emptyList())
    }

    private fun setStatusWithTroubleshooting(
        text: String?,
        actions: List<SpecWorkflowTroubleshootingAction>,
    ) {
        applyStatusPresentation(text, actions)
    }

    private fun setRuntimeTroubleshootingStatus(
        workflowId: String?,
        text: String?,
        trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
    ) {
        val statusText = text?.trim().orEmpty()
        if (statusText.isBlank()) {
            setStatusText(text)
            return
        }
        val normalizedWorkflowId = workflowId?.trim().orEmpty()
        if (normalizedWorkflowId.isBlank()) {
            setStatusText(statusText)
            return
        }
        setStatusWithTroubleshooting(
            statusText,
            buildRuntimeTroubleshootingActions(normalizedWorkflowId, trigger),
        )
    }

    private fun applyStatusPresentation(
        text: String?,
        actions: List<SpecWorkflowTroubleshootingAction>,
    ) {
        val value = text?.trim().orEmpty()
        statusLabel.text = value
        updateStatusTroubleshootingActions(actions)
        statusChipPanel.isVisible = value.isNotEmpty()
        statusChipPanel.revalidate()
        statusChipPanel.repaint()
    }

    private fun updateStatusTroubleshootingActions(actions: List<SpecWorkflowTroubleshootingAction>) {
        statusActionPanel.removeAll()
        actions.forEach { action ->
            statusActionPanel.add(createStatusTroubleshootingButton(action))
        }
        statusActionPanel.isVisible = actions.isNotEmpty()
    }

    private fun createStatusTroubleshootingButton(action: SpecWorkflowTroubleshootingAction): JButton {
        return JButton(action.label).apply {
            addActionListener { statusTroubleshootingActionDispatcher.perform(action) }
            styleToolbarButton(this)
        }
    }

    private fun buildRuntimeTroubleshootingActions(
        workflowId: String,
        trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
    ): List<SpecWorkflowTroubleshootingAction> {
        val template = currentWorkflow
            ?.takeIf { workflow -> workflow.id == workflowId }
            ?.template
            ?: WorkflowTemplate.QUICK_TASK
        return SpecWorkflowRuntimeTroubleshootingCoordinator.build(
            trigger = trigger,
            readiness = LocalEnvironmentReadiness.inspect(project),
            tracking = SpecWorkflowFirstRunTrackingStore.getInstance(project).snapshot(),
            template = template,
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
        if (!::documentWorkspaceViewTabsPanel.isInitialized || !::documentWorkspaceViewCardPanel.isInitialized) {
            return
        }
        val supportsStructuredTasksView = supportsStructuredTasksDocumentWorkspaceView(workbenchState)
        documentWorkspaceViewTabsPanel.isVisible = supportsStructuredTasksView
        val effectiveView = if (supportsStructuredTasksView) {
            selectedDocumentWorkspaceView
        } else {
            DocumentWorkspaceView.DOCUMENT
        }
        (documentWorkspaceViewCardPanel.layout as CardLayout).show(
            documentWorkspaceViewCardPanel,
            when (effectiveView) {
                DocumentWorkspaceView.DOCUMENT -> DOCUMENT_WORKSPACE_CARD_DOCUMENT
                DocumentWorkspaceView.STRUCTURED_TASKS -> DOCUMENT_WORKSPACE_CARD_STRUCTURED_TASKS
            },
        )
        documentWorkspaceViewButtons.values.forEach(::refreshDocumentWorkspaceViewButtonStyle)
        if (supportsStructuredTasksView && effectiveView == DocumentWorkspaceView.STRUCTURED_TASKS) {
            syncStructuredTaskSelection(selectedStructuredTaskId)
        }
        documentWorkspaceViewTabsPanel.revalidate()
        documentWorkspaceViewTabsPanel.repaint()
        documentWorkspaceViewCardPanel.revalidate()
        documentWorkspaceViewCardPanel.repaint()
    }

    private fun refreshDocumentWorkspaceViewButtonStyle(button: JButton) {
        val view = button.getClientProperty("documentWorkspaceView") as? DocumentWorkspaceView ?: return
        val supportsStructuredTasksView = supportsStructuredTasksDocumentWorkspaceView(currentWorkbenchState)
        val effectiveView = if (supportsStructuredTasksView) {
            selectedDocumentWorkspaceView
        } else {
            DocumentWorkspaceView.DOCUMENT
        }
        val selected = view == effectiveView
        val enabled = view != DocumentWorkspaceView.STRUCTURED_TASKS || supportsStructuredTasksView
        val hovered = enabled && button.model.isRollover && !selected
        applyDocumentWorkspaceViewButtonStyle(
            button = button,
            selected = selected,
            enabled = enabled,
            hovered = hovered,
        )
    }

    private fun applyDocumentWorkspaceViewButtonStyle(
        button: JButton,
        selected: Boolean,
        enabled: Boolean,
        hovered: Boolean,
    ) {
        button.isEnabled = enabled
        button.background = when {
            !enabled -> DOCUMENT_WORKSPACE_VIEW_GROUP_BG
            selected -> DOCUMENT_WORKSPACE_VIEW_SELECTED_BG
            hovered -> DOCUMENT_WORKSPACE_VIEW_HOVER_BG
            else -> DOCUMENT_WORKSPACE_VIEW_IDLE_BG
        }
        button.foreground = when {
            !enabled -> DOCUMENT_WORKSPACE_VIEW_DISABLED_FG
            selected -> DOCUMENT_WORKSPACE_VIEW_SELECTED_FG
            hovered -> DOCUMENT_WORKSPACE_VIEW_HOVER_FG
            else -> DOCUMENT_WORKSPACE_VIEW_IDLE_FG
        }
        button.border = BorderFactory.createCompoundBorder(
            if (selected || hovered) {
                SpecUiStyle.roundedLineBorder(
                    if (selected) DOCUMENT_WORKSPACE_VIEW_SELECTED_BORDER else DOCUMENT_WORKSPACE_VIEW_HOVER_BORDER,
                    JBUI.scale(DOCUMENT_WORKSPACE_VIEW_BUTTON_ARC),
                )
            } else {
                JBUI.Borders.empty()
            },
            JBUI.Borders.empty(
                DOCUMENT_WORKSPACE_VIEW_BUTTON_VERTICAL_PADDING,
                DOCUMENT_WORKSPACE_VIEW_BUTTON_HORIZONTAL_PADDING,
            ),
        )
        button.font = JBUI.Fonts.smallFont().deriveFont(
            if (selected) Font.BOLD else Font.PLAIN,
            DOCUMENT_WORKSPACE_VIEW_BUTTON_FONT_SIZE,
        )
        val labelFont = JBUI.Fonts.smallFont().deriveFont(
            Font.BOLD,
            DOCUMENT_WORKSPACE_VIEW_BUTTON_FONT_SIZE,
        )
        val width = documentWorkspaceViewButtonTargetWidth(labelFont)
        val size = JBUI.size(width, JBUI.scale(DOCUMENT_WORKSPACE_VIEW_BUTTON_HEIGHT))
        button.preferredSize = size
        button.minimumSize = size
        button.maximumSize = size
    }

    private fun documentWorkspaceViewButtonTargetWidth(labelFont: Font): Int {
        val scaledMinWidth = JBUI.scale(DOCUMENT_WORKSPACE_VIEW_BUTTON_MIN_WIDTH)
        val scaledTextPadding = JBUI.scale(
            DOCUMENT_WORKSPACE_VIEW_BUTTON_HORIZONTAL_PADDING * 2 +
                DOCUMENT_WORKSPACE_VIEW_BUTTON_EXTRA_WIDTH_PADDING,
        )
        return maxOf(
            documentWorkspaceViewButtons.values.maxOfOrNull { candidate ->
                candidate.getFontMetrics(labelFont).stringWidth(candidate.text.orEmpty()) + scaledTextPadding
            } ?: scaledMinWidth,
            scaledMinWidth,
        )
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
        val workflow = currentWorkflow
        val overviewState = currentOverviewState
        val verifyDeltaState = currentVerifyDeltaState
        if (workflow == null || overviewState == null || verifyDeltaState == null) {
            if (selectedWorkflowId == null) {
                showWorkspaceEmptyState()
            }
            return
        }
        updateWorkspacePresentation(
            workflow = workflow,
            overviewState = overviewState,
            tasks = currentStructuredTasks,
            liveProgressByTaskId = currentTaskLiveProgressByTaskId,
            verifyDeltaState = verifyDeltaState,
            gateResult = currentGateResult,
        )
    }

    private fun applyCurrentLiveProgressPresentation(updatedLiveProgress: Map<String, TaskExecutionLiveProgress>) {
        val workflow = currentWorkflow ?: return
        val workflowId = selectedWorkflowId ?: return
        if (workflow.id != workflowId) {
            return
        }
        currentTaskLiveProgressByTaskId = updatedLiveProgress
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
        val workspacePresentationStartedAt = workspacePresentationTelemetry.markStart()
        val previousWorkbenchState = currentWorkbenchState
        val workbenchState = resolveWorkbenchState(
            workflow = workflow,
            state = SpecWorkflowStageWorkbenchBuilder.build(
                workflow = workflow,
                overviewState = overviewState,
                tasks = tasks,
                liveProgressByTaskId = liveProgressByTaskId,
                verifyDeltaState = verifyDeltaState,
                gateResult = gateResult,
                focusedStage = focusedStage,
            ),
        )
        focusedStage = workbenchState.focusedStage
        currentOverviewState = overviewState
        currentWorkbenchState = workbenchState
        currentVerifyDeltaState = verifyDeltaState
        currentGateResult = gateResult
        currentStructuredTasks = tasks
        currentTaskLiveProgressByTaskId = liveProgressByTaskId

        showWorkspaceContent()
        overviewPanel.updateOverview(
            state = overviewState,
            workbenchState = workbenchState,
        )
        val guidance = SpecWorkflowStageGuidanceBuilder.build(
            state = overviewState,
            workbenchState = workbenchState,
        )
        val summaryPresentation = SpecWorkflowWorkspaceSummaryPresenter.build(
            workflow = workflow,
            overviewState = overviewState,
            workbenchState = workbenchState,
            guidance = guidance,
            tasks = tasks,
            verifyDeltaState = verifyDeltaState,
            gateResult = gateResult,
        )
        workspaceSummaryTitleLabel.text = summaryPresentation.title
        workspaceSummaryMetaLabel.text = summaryPresentation.meta
        workspaceSummaryFocusLabel.text = summaryPresentation.focusTitle
        workspaceSummaryHintLabel.text = summaryPresentation.focusSummary

        updateWorkspaceMetric(metric = workspaceStageMetric, presentation = summaryPresentation.stageMetric)
        updateWorkspaceMetric(metric = workspaceGateMetric, presentation = summaryPresentation.gateMetric)
        updateWorkspaceMetric(metric = workspaceTasksMetric, presentation = summaryPresentation.tasksMetric)
        updateLiveProgressRefreshTimer(tasks, liveProgressByTaskId)
        updateWorkspaceMetric(metric = workspaceVerifyMetric, presentation = summaryPresentation.verifyMetric)

        overviewSection.setSummary(summaryPresentation.sectionSummaries.overview)
        tasksSection.setSummary(summaryPresentation.sectionSummaries.tasks)
        gateSection.setSummary(summaryPresentation.sectionSummaries.gate)
        verifySection.setSummary(summaryPresentation.sectionSummaries.verify)
        documentsSection.setSummary(summaryPresentation.sectionSummaries.documents)
        val shouldSyncWorkbenchSelection = previousWorkbenchState?.focusedStage != workbenchState.focusedStage ||
            previousWorkbenchState?.currentStage != workbenchState.currentStage
        val telemetryObservation = SpecWorkflowWorkspacePresentationObservation(
            workflowId = workflow.id,
            currentStage = workflow.currentStage,
            focusedStage = workbenchState.focusedStage,
            taskCount = tasks.size,
            liveTaskCount = liveProgressByTaskId.size,
            visibleSectionCount = workbenchState.visibleSections.size,
            syncSelection = shouldSyncWorkbenchSelection,
        )
        try {
            detailPanel.updateWorkbenchState(
                state = workbenchState,
                syncSelection = shouldSyncWorkbenchSelection,
            )
            syncWorkbenchTaskSelection(
                previousWorkbenchState = previousWorkbenchState,
                workbenchState = workbenchState,
            )
            updateDocumentWorkspaceViewPresentation(workbenchState)
            applyWorkspaceSectionVisibility(workbenchState)
            applyWorkspaceSectionPreset(workflow, workbenchState)
        } finally {
            workspacePresentationTelemetry.record(
                startedAtNanos = workspacePresentationStartedAt,
                observation = telemetryObservation,
            )
        }
    }

    private fun syncWorkbenchTaskSelection(
        previousWorkbenchState: SpecWorkflowStageWorkbenchState?,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ) {
        if (workbenchState.focusedStage != StageId.IMPLEMENT) {
            return
        }
        val taskId = workbenchState.implementationFocus?.taskId ?: return
        val shouldSyncSelection = previousWorkbenchState?.focusedStage != workbenchState.focusedStage ||
            previousWorkbenchState?.implementationFocus?.taskId != taskId
        if (shouldSyncSelection) {
            syncStructuredTaskSelection(taskId)
        }
    }

    private fun applyWorkspaceSectionPreset(
        workflow: SpecWorkflow,
        workbenchState: SpecWorkflowStageWorkbenchState,
    ) {
        val token = "${workflow.id}:${workflow.currentStage.name}:${workbenchState.focusedStage.name}:${workflow.status.name}"
        if (workspaceSectionPresetToken != token) {
            workspaceSectionPresetToken = token
            workspaceSectionOverrides.clear()
            val expanded = SpecWorkflowWorkspaceLayout.defaultExpandedSections(
                currentStage = workbenchState.focusedStage,
                status = workflow.status,
            )
            workspaceSections().forEach { (sectionId, section) ->
                section.setExpanded(expanded.contains(sectionId), notify = false)
            }
            return
        }

        workspaceSections().forEach { (sectionId, section) ->
            workspaceSectionOverrides[sectionId]?.let { expanded ->
                section.setExpanded(expanded, notify = false)
            }
        }
    }

    private fun applyWorkspaceSectionVisibility(workbenchState: SpecWorkflowStageWorkbenchState) {
        val visibleSections = workbenchState.visibleSections
        val supportsEmbeddedTasksView = supportsStructuredTasksDocumentWorkspaceView(workbenchState)
        workspaceSectionItems.forEach { (sectionId, item) ->
            item.isVisible = visibleSections.contains(sectionId) &&
                !(supportsEmbeddedTasksView && sectionId == SpecWorkflowWorkspaceSectionId.TASKS)
        }
        workspaceCardPanel.revalidate()
        workspaceCardPanel.repaint()
    }

    private fun workspaceSections(): Map<SpecWorkflowWorkspaceSectionId, SpecCollapsibleWorkspaceSection> {
        if (!::overviewSection.isInitialized) {
            return emptyMap()
        }
        return linkedMapOf(
            SpecWorkflowWorkspaceSectionId.OVERVIEW to overviewSection,
            SpecWorkflowWorkspaceSectionId.TASKS to tasksSection,
            SpecWorkflowWorkspaceSectionId.GATE to gateSection,
            SpecWorkflowWorkspaceSectionId.VERIFY to verifySection,
            SpecWorkflowWorkspaceSectionId.DOCUMENTS to documentsSection,
        )
    }

    private fun resolveWorkbenchState(
        workflow: SpecWorkflow,
        state: SpecWorkflowStageWorkbenchState,
    ): SpecWorkflowStageWorkbenchState {
        val binding = state.artifactBinding
        val resolvedBinding = when {
            binding.documentPhase != null -> binding.copy(
                available = workflow.documents.containsKey(binding.documentPhase),
                previewContent = workflow.documents[binding.documentPhase]?.content,
            )

            !binding.fileName.isNullOrBlank() -> {
                val path = runCatching {
                    artifactService.locateArtifact(workflow.id, binding.fileName)
                }.getOrNull()
                val available = path?.let(Files::exists) == true
                val previewContent = if (available) {
                    path?.let { artifactPath ->
                        runCatching { Files.readString(artifactPath, StandardCharsets.UTF_8) }.getOrNull()
                    }
                } else {
                    null
                }
                binding.copy(
                    available = available,
                    previewContent = previewContent,
                )
            }

            else -> binding
        }
        return state.copy(artifactBinding = resolvedBinding)
    }

    private fun updateWorkspaceMetric(
        metric: WorkspaceSummaryMetric,
        presentation: SpecWorkflowWorkspaceMetricPresentation,
    ) {
        val colors = workspaceChipColors(presentation.tone)
        metric.titleLabel.text = "${presentation.title}:"
        metric.valueLabel.text = presentation.value
        metric.valueLabel.foreground = colors.foreground
        metric.root.isVisible = presentation.value.isNotBlank()
    }

    private fun clearWorkspaceMetric(metric: WorkspaceSummaryMetric) {
        metric.titleLabel.text = ""
        metric.valueLabel.text = ""
        metric.root.isVisible = false
    }

    private fun phaseLabel(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> SpecCodingBundle.message("spec.detail.step.requirements")
            SpecPhase.DESIGN -> SpecCodingBundle.message("spec.detail.step.design")
            SpecPhase.IMPLEMENT -> SpecCodingBundle.message("spec.detail.step.taskList")
        }
    }

    private fun clearOpenedWorkflowUi(resetHighlight: Boolean = false) {
        workflowPanelState.clearOpenedWorkflow(resetHighlight = resetHighlight)
        currentWorkflow = null
        currentWorkflowSources = emptyList()
        currentWorkbenchState = null
        phaseIndicator.reset()
        overviewPanel.showEmpty()
        tasksPanel.showEmpty()
        detailTasksPanel.showEmpty()
        gateDetailsPanel.showEmpty()
        verifyDeltaPanel.showEmpty()
        detailPanel.showEmpty()
        updateDocumentWorkspaceViewPresentation(null)
        createWorktreeButton.isEnabled = false
        mergeWorktreeButton.isEnabled = false
        deltaButton.isEnabled = false
        archiveButton.isEnabled = false
        if (resetHighlight) {
            highlightedWorkflowId = null
            listPanel.setSelectedWorkflow(null)
        }
        showWorkspaceEmptyState()
    }

    private fun resolveDeleteRefreshTarget(workflowId: String): WorkflowRefreshTarget {
        val remainingItems = listPanel.currentItems()
            .filterNot { item -> item.workflowId == workflowId }
        val remainingIds = remainingItems.asSequence()
            .map { item -> item.workflowId }
            .toSet()
        val preservedSelectedWorkflowId = selectedWorkflowId
            ?.takeIf { candidate -> candidate != workflowId && candidate in remainingIds }
        if (preservedSelectedWorkflowId != null) {
            return WorkflowRefreshTarget(selectWorkflowId = preservedSelectedWorkflowId, preserveListMode = false)
        }
        if (selectedWorkflowId == workflowId) {
            return WorkflowRefreshTarget(
                selectWorkflowId = remainingItems.firstOrNull()?.workflowId,
                preserveListMode = false,
            )
        }
        return WorkflowRefreshTarget(selectWorkflowId = null, preserveListMode = true)
    }

    fun refreshWorkflows(
        selectWorkflowId: String? = null,
        showRefreshFeedback: Boolean = false,
        preserveListMode: Boolean = false,
    ) {
        taskCoordinator.launchIo {
            val items = specEngine.listWorkflowMetadata().map { meta ->
                SpecWorkflowListPanel.WorkflowListItem(
                    workflowId = meta.workflowId,
                    title = meta.title?.ifBlank { meta.workflowId } ?: meta.workflowId,
                    description = meta.description.orEmpty(),
                    currentPhase = meta.currentPhase,
                    currentStageLabel = SpecWorkflowOverviewPresenter.stageLabel(meta.currentStage),
                    status = meta.status,
                    updatedAt = meta.updatedAt,
                    changeIntent = meta.changeIntent,
                    baselineWorkflowId = meta.baselineWorkflowId,
                )
            }
            invokeLaterSafe {
                workflowSwitcherPopup?.cancel()
                listPanel.updateWorkflows(items)
                switchWorkflowButton.isEnabled = items.isNotEmpty()
                setStatusText(null)
                val workflowIds = items.asSequence().map { it.workflowId }.toSet()
                workflowPanelState.dropPendingOpenRequestIfInvalid(workflowIds)
                val validHighlightedWorkflowId = highlightedWorkflowId?.takeIf(workflowIds::contains)
                val targetOpenedWorkflowId = selectWorkflowId?.takeIf(workflowIds::contains)
                    ?: selectedWorkflowId?.takeIf(workflowIds::contains)
                    ?: items.firstOrNull()?.workflowId?.takeIf {
                        !preserveListMode && validHighlightedWorkflowId == null
                    }
                val targetHighlightedWorkflowId = targetOpenedWorkflowId
                    ?: if (preserveListMode) {
                        validHighlightedWorkflowId ?: items.firstOrNull()?.workflowId
                    } else {
                        validHighlightedWorkflowId
                    }
                highlightedWorkflowId = targetHighlightedWorkflowId
                listPanel.setSelectedWorkflow(targetHighlightedWorkflowId)
                if (targetOpenedWorkflowId != null) {
                    selectWorkflow(targetOpenedWorkflowId)
                } else {
                    clearOpenedWorkflowUi(resetHighlight = targetHighlightedWorkflowId == null)
                }
                if (showRefreshFeedback) {
                    val successText = SpecCodingBundle.message("common.refresh.success")
                    RefreshFeedback.flashButtonSuccess(refreshButton, successText)
                    RefreshFeedback.flashLabelSuccess(statusLabel, successText, STATUS_SUCCESS_FG)
                }
            }
        }
    }

    private fun onWorkflowFocusedByUser(workflowId: String) {
        workflowPanelState.highlightWorkflow(workflowId)
        if (selectedWorkflowId != null && selectedWorkflowId != workflowId) {
            clearOpenedWorkflowUi(resetHighlight = false)
        }
    }

    private fun onWorkflowOpenedByUser(workflowId: String) {
        workflowPanelState.highlightWorkflow(workflowId)
        selectWorkflow(workflowId)
        publishWorkflowSelection(workflowId)
    }

    private fun onBackToWorkflowListRequested() {
        clearOpenedWorkflowUi(resetHighlight = false)
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
        val previousSelectedWorkflowId = selectedWorkflowId
        workflowPanelState.selectWorkflow(workflowId)
        showWorkflowLoadInProgress()
        taskCoordinator.launchIo {
            val loadedState = loadCoordinator.load(
                SpecWorkflowPanelLoadRequest(
                    workflowId = workflowId,
                    includeSources = true,
                ),
            )
            applyLoadedWorkflow(
                workflowId = workflowId,
                loadedState = loadedState,
                previousSelectedWorkflowId = previousSelectedWorkflowId,
            )
        }
    }

    private fun onCreateWorkflow(preferredTemplate: WorkflowTemplate? = null) {
        val workflowOptions = listPanel.workflowOptionsForCreate()
        taskCoordinator.launchIo {
            val configuredDefaultTemplate = runCatching {
                SpecProjectConfigService(project).load().defaultTemplate
            }.getOrElse { error ->
                logger.warn("Failed to load default workflow template for create dialog", error)
                null
            }
            val defaultTemplate = SpecWorkflowEntryPaths.resolveDefaultTemplate(
                preferredTemplate = preferredTemplate,
                configuredDefault = configuredDefaultTemplate,
            )

            invokeLaterSafe {
                val dialog = NewSpecWorkflowDialog(
                    project = project,
                    workflowOptions = workflowOptions,
                    defaultTemplate = defaultTemplate,
                )
                if (!dialog.showAndGet()) {
                    return@invokeLaterSafe
                }
                val title = dialog.resultTitle ?: return@invokeLaterSafe
                val desc = dialog.resultDescription ?: ""
                val template = dialog.resultTemplate
                val verifyEnabled = dialog.resultVerifyEnabled
                val changeIntent = dialog.resultChangeIntent
                val baselineWorkflowId = dialog.resultBaselineWorkflowId
                taskCoordinator.launchIo {
                    workflowCreateCoordinator.create(
                        SpecWorkflowCreateRequest(
                            title = title,
                            description = desc,
                            template = template,
                            verifyEnabled = verifyEnabled,
                            changeIntent = changeIntent,
                            baselineWorkflowId = baselineWorkflowId,
                        ),
                    ).onSuccess { outcome ->
                        invokeLaterSafe {
                            highlightedWorkflowId = outcome.workflow.id
                            refreshWorkflows(selectWorkflowId = outcome.workflow.id)
                            publishWorkflowSelection(outcome.workflow.id)
                            if (!outcome.firstVisibleArtifactMaterialized) {
                                setStatusText(
                                    SpecCodingBundle.message(
                                        "spec.workflow.create.firstArtifactMissing",
                                        outcome.expectedFirstVisibleArtifactFileName,
                                        outcome.workflow.id,
                                    ),
                                )
                            }
                        }
                    }.onFailure { e ->
                        logger.warn("Failed to create workflow", e)
                        invokeLaterSafe {
                            val message = compactErrorMessage(e, SpecCodingBundle.message("common.unknown"))
                            setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                        }
                    }
                }
            }
        }
    }

    private fun onEditWorkflow(workflowId: String) {
        taskCoordinator.launchIo {
            val workflow = specEngine.loadWorkflow(workflowId).getOrNull()
            if (workflow == null) {
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message("spec.workflow.error", SpecCodingBundle.message("common.unknown")))
                }
                return@launchIo
            }

            invokeLaterSafe {
                val dialog = EditSpecWorkflowDialog(
                    initialTitle = workflow.title.ifBlank { workflow.id },
                    initialDescription = workflow.description,
                )
                if (!dialog.showAndGet()) {
                    return@invokeLaterSafe
                }
                val title = dialog.resultTitle ?: return@invokeLaterSafe
                val description = dialog.resultDescription ?: ""

                taskCoordinator.launchIo {
                    val result = specEngine.updateWorkflowMetadata(
                        workflowId = workflowId,
                        title = title,
                        description = description,
                    )

                    invokeLaterSafe {
                        result.onSuccess { updated ->
                            setStatusText(null)
                            val reopenWorkspace = selectedWorkflowId == workflowId
                            highlightedWorkflowId = workflowId
                            if (reopenWorkspace) {
                                currentWorkflow = updated
                                phaseIndicator.updatePhase(updated)
                                detailPanel.updateWorkflow(updated)
                                archiveButton.isEnabled = updated.status == WorkflowStatus.COMPLETED
                            }
                            refreshWorkflows(selectWorkflowId = workflowId.takeIf { reopenWorkspace })
                        }.onFailure { error ->
                            val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                            setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                        }
                    }
                }
            }
        }
    }

    private fun onDeleteWorkflow(workflowId: String) {
        val refreshTarget = resolveDeleteRefreshTarget(workflowId)
        taskCoordinator.launchIo {
            specEngine.deleteWorkflow(workflowId).onSuccess {
                invokeLaterSafe {
                    if (pendingOpenWorkflowRequest?.workflowId == workflowId) {
                        pendingOpenWorkflowRequest = null
                    }
                    refreshWorkflows(
                        selectWorkflowId = refreshTarget.selectWorkflowId,
                        preserveListMode = refreshTarget.preserveListMode,
                    )
                }
            }.onFailure { error ->
                invokeLaterSafe {
                    val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                    setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                }
            }
        }
    }

    private fun onCreateWorktree() {
        val workflow = currentWorkflow
        if (workflow == null) {
            setStatusText(SpecCodingBundle.message("spec.workflow.worktree.selectFirst"))
            return
        }

        val dialog = NewWorktreeDialog(
            specTaskId = workflow.id,
            specTitle = workflow.title.ifBlank { workflow.id },
            baseBranch = worktreeManager.suggestBaseBranch(),
        )
        if (!dialog.showAndGet()) {
            return
        }

        val specTaskId = dialog.resultSpecTaskId ?: workflow.id
        val shortName = dialog.resultShortName ?: return
        val baseBranch = dialog.resultBaseBranch ?: "main"

        worktreeCoordinator.createAndSwitch(
            SpecWorkflowWorktreeCreateRequest(
                specTaskId = specTaskId,
                shortName = shortName,
                baseBranch = baseBranch,
            ),
        )
    }

    private fun onMergeWorktree() {
        val workflow = currentWorkflow
        if (workflow == null) {
            setStatusText(SpecCodingBundle.message("spec.workflow.worktree.selectFirst"))
            return
        }

        worktreeCoordinator.mergeIntoBaseBranch(
            SpecWorkflowWorktreeMergeRequest(workflowId = workflow.id),
        )
    }

    private fun canGenerateWithEmptyInput(): Boolean {
        return clarificationRetryStore.hasInput(selectedWorkflowId)
    }

    private fun applyAutoCodeContextToDetailPanel(
        workflow: SpecWorkflow?,
        codeContextResult: Result<CodeContextPack>?,
    ) {
        if (workflow == null) {
            detailPanel.updateAutoCodeContext(workflowId = null, codeContextPack = null)
            return
        }

        val pack = codeContextResult?.getOrElse { error ->
            CodeContextPack(
                phase = workflow.currentPhase,
                strategy = CodeContextCollectionStrategy.forPhase(workflow.currentPhase),
                degradationReasons = listOf(
                    "Automatic code context collection failed: ${compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))}",
                ),
            )
        } ?: CodeContextPack(
            phase = workflow.currentPhase,
            strategy = CodeContextCollectionStrategy.forPhase(workflow.currentPhase),
            degradationReasons = listOf(
                "Automatic code context collection was skipped for this workflow refresh.",
            ),
        )
        detailPanel.updateAutoCodeContext(
            workflowId = workflow.id,
            codeContextPack = pack,
        )
    }

    private fun applyWorkflowSourcesToDetailPanel(
        workflow: SpecWorkflow?,
        assets: List<WorkflowSourceAsset>,
        preserveSelection: Boolean,
        preferredSourceIds: Set<String> = emptySet(),
    ) {
        val workflowId = workflow?.id
        currentWorkflowSources = assets.sortedBy(WorkflowSourceAsset::sourceId)
        if (workflowId == null) {
            detailPanel.updateWorkflowSources(
                workflowId = null,
                assets = emptyList(),
                selectedSourceIds = emptySet(),
                editable = false,
            )
            return
        }

        val existingSelection = composerSelectedSourceIdsByWorkflowId[workflowId]
        val resolvedSelection = when {
            !preserveSelection || existingSelection == null -> {
                currentWorkflowSources.mapTo(linkedSetOf<String>(), WorkflowSourceAsset::sourceId)
            }

            else -> existingSelection.filterTo(linkedSetOf<String>()) { candidate ->
                currentWorkflowSources.any { asset -> asset.sourceId == candidate }
            }
        }
        preferredSourceIds.forEach { preferredSourceId ->
            if (currentWorkflowSources.any { asset -> asset.sourceId == preferredSourceId }) {
                resolvedSelection += preferredSourceId
            }
        }
        composerSelectedSourceIdsByWorkflowId[workflowId] = resolvedSelection
        detailPanel.updateWorkflowSources(
            workflowId = workflowId,
            assets = currentWorkflowSources,
            selectedSourceIds = resolvedSelection,
            editable = workflow.currentStage in COMPOSER_SOURCE_EDITABLE_STAGES,
        )
    }

    private fun onAddWorkflowSourcesRequested() {
        val workflow = currentWorkflow ?: return
        if (workflow.currentStage !in COMPOSER_SOURCE_EDITABLE_STAGES) {
            return
        }

        val selectedPaths = sourceFileChooser(project, sourceImportConstraints)
        if (selectedPaths.isEmpty()) {
            return
        }

        val validation = WorkflowSourceImportSupport.validate(selectedPaths, sourceImportConstraints)
        if (validation.acceptedPaths.isEmpty()) {
            showWorkflowSourceValidation(validation.rejectedFiles)
            setStatusText(
                SpecCodingBundle.message(
                    "spec.detail.sources.status.rejected",
                    validation.rejectedFiles.size,
                ),
            )
            return
        }

        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.detail.sources.importing"),
            task = {
                validation.acceptedPaths.map { sourcePath ->
                    specEngine.importWorkflowSource(
                        workflowId = workflow.id,
                        importedFromStage = workflow.currentStage,
                        importedFromEntry = WORKFLOW_SOURCE_ENTRY_SPEC_COMPOSER,
                        sourcePath = sourcePath,
                    ).getOrThrow()
                }
            },
            onSuccess = { importedAssets ->
                if (selectedWorkflowId != workflow.id) {
                    return@runBackground
                }
                val mergedAssets = (currentWorkflowSources + importedAssets)
                    .associateBy(WorkflowSourceAsset::sourceId)
                    .values
                    .sortedBy(WorkflowSourceAsset::sourceId)
                applyWorkflowSourcesToDetailPanel(
                    workflow = workflow,
                    assets = mergedAssets,
                    preserveSelection = true,
                    preferredSourceIds = importedAssets.mapTo(linkedSetOf<String>(), WorkflowSourceAsset::sourceId),
                )
                if (validation.rejectedFiles.isNotEmpty()) {
                    showWorkflowSourceValidation(validation.rejectedFiles)
                }
                val statusKey = if (validation.rejectedFiles.isEmpty()) {
                    "spec.detail.sources.status.imported"
                } else {
                    "spec.detail.sources.status.importedPartial"
                }
                setStatusText(
                    SpecCodingBundle.message(
                        statusKey,
                        importedAssets.size,
                        validation.rejectedFiles.size,
                    ),
                )
            },
            onFailure = { error ->
                val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
            },
        )
    }

    private fun onRemoveWorkflowSourceRequested(sourceId: String) {
        val workflow = currentWorkflow ?: return
        val selection = composerSelectedSourceIdsByWorkflowId.getOrPut(workflow.id) {
            currentWorkflowSources.mapTo(linkedSetOf<String>(), WorkflowSourceAsset::sourceId)
        }
        if (!selection.remove(sourceId)) {
            return
        }
        detailPanel.updateWorkflowSources(
            workflowId = workflow.id,
            assets = currentWorkflowSources,
            selectedSourceIds = selection,
            editable = workflow.currentStage in COMPOSER_SOURCE_EDITABLE_STAGES,
        )
        setStatusText(
            SpecCodingBundle.message(
                "spec.detail.sources.status.removed",
                sourceId,
            ),
        )
    }

    private fun onRestoreWorkflowSourcesRequested() {
        val workflow = currentWorkflow ?: return
        applyWorkflowSourcesToDetailPanel(
            workflow = workflow,
            assets = currentWorkflowSources,
            preserveSelection = false,
        )
        setStatusText(
            SpecCodingBundle.message(
                "spec.detail.sources.status.restored",
                currentWorkflowSources.size,
            ),
        )
    }

    private fun showWorkflowSourceValidation(rejectedFiles: List<RejectedWorkflowSourceFile>) {
        if (rejectedFiles.isEmpty()) {
            return
        }
        val lines = rejectedFiles
            .take(MAX_SOURCE_IMPORT_VALIDATION_LINES)
            .map { rejectedFile ->
                val fileName = rejectedFile.path.fileName?.toString().orEmpty().ifBlank { rejectedFile.path.toString() }
                val reason = when (rejectedFile.reason) {
                    RejectedWorkflowSourceFile.Reason.NOT_A_FILE ->
                        SpecCodingBundle.message("spec.detail.sources.validation.notFile")

                    RejectedWorkflowSourceFile.Reason.UNSUPPORTED_EXTENSION ->
                        SpecCodingBundle.message(
                            "spec.detail.sources.validation.unsupported",
                            WorkflowSourceImportSupport.formatAllowedExtensions(sourceImportConstraints),
                        )

                    RejectedWorkflowSourceFile.Reason.FILE_TOO_LARGE ->
                        SpecCodingBundle.message(
                            "spec.detail.sources.validation.tooLarge",
                            WorkflowSourceImportSupport.formatFileSize(sourceImportConstraints.maxFileSizeBytes),
                        )
                }
                "- $fileName: $reason"
            }
            .toMutableList()
        val remaining = rejectedFiles.size - MAX_SOURCE_IMPORT_VALIDATION_LINES
        if (remaining > 0) {
            lines += SpecCodingBundle.message("spec.detail.sources.validation.more", remaining)
        }
        warningDialogPresenter(
            project,
            lines.joinToString(separator = "\n"),
            SpecCodingBundle.message("spec.detail.sources.validation.title"),
        )
    }

    private fun onGenerate(input: String) {
        val workflow = resolveSelectedWorkflowForClarification() ?: return
        val pendingRetry = clarificationRetryStore.current(workflow.id)
        if (pendingRetry?.followUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR) {
            val resumePlan = gateRequirementsRepairCoordinator.buildResumePlan(
                input = input,
                pendingRetry = pendingRetry,
            )
            if (resumePlan.resumeWithConfirmedContext) {
                detailPanel.appendProcessTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                    state = SpecDetailPanel.ProcessTimelineState.INFO,
                )
                continueRequirementsRepairAfterClarification(
                    workflowId = workflow.id,
                    pendingRetry = pendingRetry,
                    input = resumePlan.input,
                    confirmedContext = pendingRetry.confirmedContext,
                )
                return
            }
            if (resumePlan.shouldClearProcessTimeline) {
                detailPanel.clearProcessTimeline()
            }
            detailPanel.appendProcessTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.clarify.round", resumePlan.clarificationRound),
                state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
            )
            detailPanel.appendProcessTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                state = SpecDetailPanel.ProcessTimelineState.INFO,
            )
            rememberClarificationRetry(
                workflowId = workflow.id,
                input = resumePlan.input,
                confirmedContext = resumePlan.suggestedDetails,
                clarificationRound = resumePlan.clarificationRound,
                lastError = pendingRetry.lastError,
                confirmed = false,
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = pendingRetry.requirementsRepairSections,
            )
            launchRequirementsRepairClarification(
                workflow = workflow,
                input = resumePlan.input,
                suggestedDetails = resumePlan.suggestedDetails,
                pendingRetry = clarificationRetryStore.current(workflow.id),
                clarificationRound = resumePlan.clarificationRound,
            )
            return
        }

        val context = resolveGenerationContext() ?: return
        when (
            val launchPlan = generationCoordinator.buildLaunchPlan(
                input = input,
                pendingRetry = pendingRetry,
                context = context,
            )
        ) {
            is SpecWorkflowGenerationLaunchPlan.ResumeGeneration -> {
                if (launchPlan.shouldShowRetryContextReuse) {
                    detailPanel.appendProcessTimelineEntry(
                        text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                        state = SpecDetailPanel.ProcessTimelineState.INFO,
                    )
                }
                runGeneration(
                    workflowId = launchPlan.workflowId,
                    input = launchPlan.input,
                    options = launchPlan.options,
                )
            }

            is SpecWorkflowGenerationLaunchPlan.RequestClarification -> {
                if (launchPlan.shouldClearProcessTimeline) {
                    detailPanel.clearProcessTimeline()
                }
                detailPanel.appendProcessTimelineEntry(
                    text = SpecCodingBundle.message(
                        "spec.workflow.process.clarify.round",
                        launchPlan.clarificationRound,
                    ),
                    state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
                )
                if (launchPlan.shouldShowRetryContextReuse) {
                    detailPanel.appendProcessTimelineEntry(
                        text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                        state = SpecDetailPanel.ProcessTimelineState.INFO,
                    )
                }
                rememberClarificationRetry(
                    workflowId = launchPlan.context.workflowId,
                    input = launchPlan.input,
                    confirmedContext = launchPlan.suggestedDetails,
                    clarificationRound = launchPlan.clarificationRound,
                    lastError = launchPlan.retryLastError,
                    confirmed = false,
                    followUp = ClarificationFollowUp.GENERATION,
                    requirementsRepairSections = emptyList(),
                )
                requestClarificationDraft(
                    context = launchPlan.context,
                    input = launchPlan.input,
                    options = launchPlan.options,
                    suggestedDetails = launchPlan.suggestedDetails,
                    seedQuestionsMarkdown = launchPlan.seedQuestionsMarkdown,
                    seedStructuredQuestions = launchPlan.seedStructuredQuestions,
                    clarificationRound = launchPlan.clarificationRound,
                )
            }
        }
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

    private fun startRequirementsClarifyThenFill(
        workflowId: String,
        missingSections: List<RequirementsSectionId>,
    ): Boolean {
        val workflow = currentWorkflow?.takeIf { it.id == workflowId } ?: return false
        val previous = clarificationRetryStore.current(workflowId)
        val plan = gateRequirementsRepairCoordinator.buildClarifyThenFillPlan(
            missingSections = missingSections,
            previousRetry = previous,
        ) ?: return false
        showWorkspaceContent()
        focusStage(StageId.REQUIREMENTS)

        if (!plan.reusedPreviousRetry) {
            detailPanel.clearProcessTimeline()
        }
        detailPanel.appendProcessTimelineEntry(
            text = SpecCodingBundle.message("spec.workflow.process.clarify.round", plan.clarificationRound),
            state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
        )
        if (plan.reusedPreviousRetry) {
            detailPanel.appendProcessTimelineEntry(
                text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                state = SpecDetailPanel.ProcessTimelineState.INFO,
            )
        }
        rememberClarificationRetry(
            workflowId = workflowId,
            input = plan.input,
            confirmedContext = plan.suggestedDetails,
            questionsMarkdown = previous?.questionsMarkdown,
            structuredQuestions = previous?.structuredQuestions,
            clarificationRound = plan.clarificationRound,
            lastError = previous?.lastError,
            confirmed = false,
            followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
            requirementsRepairSections = plan.normalizedSections,
        )
        launchRequirementsRepairClarification(
            workflow = workflow,
            input = plan.input,
            suggestedDetails = plan.suggestedDetails,
            pendingRetry = clarificationRetryStore.current(workflowId),
            clarificationRound = plan.clarificationRound,
        )
        return true
    }

    private fun launchRequirementsRepairClarification(
        workflow: SpecWorkflow,
        input: String,
        suggestedDetails: String,
        pendingRetry: ClarificationRetryPayload?,
        clarificationRound: Int,
    ) {
        when (
            val launch = gateRequirementsRepairCoordinator.prepareClarificationLaunch(
                workflow = workflow,
                providerId = providerComboBox.selectedItem as? String,
                modelId = (modelComboBox.selectedItem as? ModelInfo)?.id,
                workflowSourceUsage = resolveComposerSourceUsage(workflow.id),
                pendingRetry = pendingRetry,
                input = input,
                suggestedDetails = suggestedDetails,
                clarificationRound = clarificationRound,
            )
        ) {
            is SpecWorkflowGateRequirementsClarificationLaunch.RequestDraft -> {
                requestClarificationDraft(
                    context = SpecWorkflowGenerationContext(
                        workflowId = launch.workflowId,
                        phase = launch.phase,
                        options = launch.options,
                    ),
                    input = launch.input,
                    options = launch.options,
                    suggestedDetails = launch.suggestedDetails,
                    seedQuestionsMarkdown = launch.seedQuestionsMarkdown,
                    seedStructuredQuestions = launch.seedStructuredQuestions,
                    clarificationRound = launch.clarificationRound,
                )
            }

            is SpecWorkflowGateRequirementsClarificationLaunch.ManualFallback -> {
                rememberClarificationRetry(
                    workflowId = launch.workflowId,
                    input = launch.input,
                    confirmedContext = launch.suggestedDetails,
                    questionsMarkdown = launch.questionsMarkdown,
                    structuredQuestions = emptyList(),
                    clarificationRound = launch.clarificationRound,
                    lastError = launch.reason,
                    confirmed = false,
                    followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                    requirementsRepairSections = clarificationRetryStore.current(launch.workflowId)?.requirementsRepairSections.orEmpty(),
                )
                detailPanel.showClarificationDraft(
                    phase = launch.phase,
                    input = launch.input,
                    questionsMarkdown = launch.questionsMarkdown,
                    suggestedDetails = launch.suggestedDetails,
                    structuredQuestions = emptyList(),
                )
                detailPanel.appendProcessTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.clarify.prepare"),
                    state = SpecDetailPanel.ProcessTimelineState.DONE,
                )
                detailPanel.appendProcessTimelineEntry(
                    text = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.manualFallback.timeline"),
                    state = SpecDetailPanel.ProcessTimelineState.INFO,
                )
                setStatusText(launch.statusMessage)
            }
        }
    }

    private fun continueRequirementsRepairAfterClarification(
        workflowId: String,
        pendingRetry: ClarificationRetryPayload,
        input: String,
        confirmedContext: String?,
    ) {
        when (
            val continuation = gateRequirementsRepairCoordinator.continueAfterClarification(
                SpecWorkflowGateRequirementsRepairAfterClarificationRequest(
                    workflowId = workflowId,
                    pendingRetry = pendingRetry,
                    confirmedContext = confirmedContext,
                ),
            )
        ) {
            is SpecWorkflowGateRequirementsRepairContinuation.Noop -> {
                detailPanel.unlockClarificationChecklistInteractions()
                setStatusText(continuation.statusMessage)
            }

            is SpecWorkflowGateRequirementsRepairContinuation.ManualFallback -> {
                detailPanel.unlockClarificationChecklistInteractions()
                detailPanel.exitClarificationMode(clearInput = false)
                setStatusText(continuation.statusMessage)
                continuation.requirementsDocumentPath?.let { path ->
                    runCatching {
                        SpecWorkflowActionSupport.openFile(
                            project,
                            path,
                        )
                    }
                }
                SpecWorkflowActionSupport.showInfo(
                    project,
                    continuation.infoTitle,
                    continuation.infoMessage,
                )
            }

            is SpecWorkflowGateRequirementsRepairContinuation.PreviewAndApply -> {
                RequirementsSectionRepairUiSupport.previewAndApply(
                    project = project,
                    workflowId = workflowId,
                    missingSections = continuation.missingSections,
                    confirmedContextOverride = continuation.confirmedContextOverride,
                    previewTitle = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.progress.preview"),
                    applyTitle = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.progress.apply"),
                    onPreviewCancelled = {
                        detailPanel.unlockClarificationChecklistInteractions()
                        setStatusText(SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.previewCancelled"))
                    },
                    onNoop = {
                        clearClarificationRetry(workflowId)
                        detailPanel.exitClarificationMode(clearInput = true)
                        detailPanel.unlockClarificationChecklistInteractions()
                        reloadCurrentWorkflow(followCurrentPhase = true)
                    },
                    onApplied = {
                        clearClarificationRetry(workflowId)
                        detailPanel.exitClarificationMode(clearInput = true)
                        detailPanel.unlockClarificationChecklistInteractions()
                        reloadCurrentWorkflow(followCurrentPhase = true) {
                            focusStage(StageId.REQUIREMENTS)
                        }
                    },
                    onFailure = { error ->
                        detailPanel.unlockClarificationChecklistInteractions()
                        rememberClarificationRetry(
                            workflowId = workflowId,
                            input = input,
                            confirmedContext = confirmedContext ?: pendingRetry.confirmedContext,
                            clarificationRound = pendingRetry.clarificationRound,
                            lastError = compactErrorMessage(error, SpecCodingBundle.message("common.unknown")),
                            confirmed = pendingRetry.confirmed,
                            followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                            requirementsRepairSections = continuation.missingSections,
                        )
                        setStatusText(
                            SpecCodingBundle.message(
                                "spec.workflow.error",
                                compactErrorMessage(error, SpecCodingBundle.message("common.unknown")),
                            ),
                        )
                    },
                )
            }
        }
    }

    private fun onSwitchWorkflowRequested() {
        val currentItems = listPanel.currentItems()
        if (currentItems.isEmpty()) {
            return
        }
        workflowSwitcherPopup?.cancel()
        workflowSwitcherPopup = SpecWorkflowSwitcherPopup(
            items = currentItems,
            initialSelectionWorkflowId = selectedWorkflowId ?: highlightedWorkflowId,
            onOpenWorkflow = { workflowId ->
                workflowSwitcherPopup = null
                onWorkflowOpenedByUser(workflowId)
            },
            onEditWorkflow = { workflowId ->
                workflowSwitcherPopup = null
                onEditWorkflow(workflowId)
            },
            onDeleteWorkflow = { workflowId ->
                workflowSwitcherPopup = null
                onDeleteWorkflow(workflowId)
            },
        ).also { popup ->
            popup.showUnderneathOf(switchWorkflowButton)
        }
    }

    private fun requestClarificationDraft(
        context: SpecWorkflowGenerationContext,
        input: String,
        options: GenerationOptions = context.options,
        suggestedDetails: String = input,
        seedQuestionsMarkdown: String? = null,
        seedStructuredQuestions: List<String> = emptyList(),
        clarificationRound: Int = 1,
    ) {
        cancelActiveGenerationRequest("Superseded by new clarification request")
        val prepared = generationCoordinator.prepareClarificationDraft(
            context = context,
            input = input,
            options = options,
            suggestedDetails = suggestedDetails,
            seedQuestionsMarkdown = seedQuestionsMarkdown,
            seedStructuredQuestions = seedStructuredQuestions,
            clarificationRound = clarificationRound,
        )
        activeGenerationRequest = prepared.activeRequest
        activeGenerationJob = taskCoordinator.launchIo {
            try {
                invokeLaterSafe {
                    if (selectedWorkflowId != prepared.context.workflowId) {
                        return@invokeLaterSafe
                    }
                    detailPanel.showClarificationGenerating(
                        phase = prepared.context.phase,
                        input = prepared.input,
                        suggestedDetails = prepared.safeSuggestedDetails,
                    )
                    appendProcessTimelineEntries(prepared.initialTimelineEntries)
                    setStatusText(prepared.loadingStatusText)
                }
                val draftResult = try {
                    Result.success(
                        specEngine.draftCurrentPhaseClarification(
                            workflowId = prepared.context.workflowId,
                            input = prepared.input,
                            options = prepared.requestOptions,
                        ).getOrThrow(),
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    Result.failure(error)
                }

                val draft = draftResult.getOrNull()
                val draftError = draftResult.exceptionOrNull()
                if (draft == null) {
                    logger.warn("Failed to draft clarification for workflow=${prepared.context.workflowId}", draftError)
                }
                val result = generationCoordinator.buildClarificationDraftResult(
                    prepared = prepared,
                    draft = draft,
                    error = draftError,
                )
                invokeLaterSafe {
                    if (selectedWorkflowId != prepared.context.workflowId) {
                        return@invokeLaterSafe
                    }
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
            } catch (cancel: CancellationException) {
                if (isActiveGenerationRequest(prepared.activeRequest)) {
                    handleGenerationInterrupted(
                        workflowId = prepared.context.workflowId,
                        input = input,
                        options = prepared.requestOptions,
                        processMessageKey = "spec.workflow.process.clarify.failed",
                    )
                }
                throw cancel
            } finally {
                clearActiveGenerationRequest(prepared.activeRequest)
            }
        }
    }

    private fun runGeneration(
        workflowId: String,
        input: String,
        options: GenerationOptions,
    ) {
        cancelActiveGenerationRequest("Superseded by new generation request")
        val prepared = generationCoordinator.prepareGeneration(
            workflowId = workflowId,
            phase = currentWorkflow?.currentPhase ?: SpecPhase.SPECIFY,
            input = input,
            options = options,
        )
        activeGenerationRequest = prepared.activeRequest
        activeGenerationJob = taskCoordinator.launchIo {
            try {
                var tracker = SpecWorkflowGenerationProgressTracker()
                specEngine.generateCurrentPhase(workflowId, input, prepared.requestOptions).collect { progress ->
                    val progressUpdate = generationCoordinator.advanceGenerationProgress(
                        prepared = prepared,
                        tracker = tracker,
                        progress = progress,
                    )
                    tracker = progressUpdate.tracker
                    invokeLaterSafe {
                        applyGenerationProgressUpdate(
                            workflowId = workflowId,
                            input = input,
                            update = progressUpdate,
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                if (isActiveGenerationRequest(prepared.activeRequest)) {
                    val interruptedUpdate = generationCoordinator.buildInterruptedProgressUpdate(
                        prepared = prepared,
                        interruptedMessage = SpecCodingBundle.message("spec.workflow.generation.interrupted"),
                    )
                    invokeLaterSafe {
                        applyGenerationProgressUpdate(
                            workflowId = workflowId,
                            input = input,
                            update = interruptedUpdate,
                        )
                    }
                }
                throw cancel
            } finally {
                clearActiveGenerationRequest(prepared.activeRequest)
            }
        }
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

    private fun handleGenerationInterrupted(
        workflowId: String,
        input: String,
        options: GenerationOptions,
        processMessageKey: String,
    ) {
        val interruptedMessage = SpecCodingBundle.message("spec.workflow.generation.interrupted")
        invokeLaterSafe {
            if (selectedWorkflowId != workflowId) {
                return@invokeLaterSafe
            }
            detailPanel.appendProcessTimelineEntry(
                text = SpecCodingBundle.message(processMessageKey, interruptedMessage),
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
    }

    private fun cancelActiveGenerationRequest(reason: String) {
        val activeRequest = activeGenerationRequest
        if (activeRequest != null && activeRequest.requestId.isNotBlank()) {
            cancelRequestAcrossProviders(
                providerId = activeRequest.providerId,
                requestId = activeRequest.requestId,
            )
        }
        activeGenerationJob?.cancel(CancellationException(reason))
        activeGenerationJob = null
        activeGenerationRequest = null
    }

    private fun cancelRequestAcrossProviders(
        providerId: String?,
        requestId: String,
    ) {
        llmRouter.cancel(providerId = providerId, requestId = requestId)
        llmRouter.cancel(providerId = ClaudeCliLlmProvider.ID, requestId = requestId)
        llmRouter.cancel(providerId = CodexCliLlmProvider.ID, requestId = requestId)
    }

    private fun isActiveGenerationRequest(request: SpecWorkflowActiveGenerationRequest): Boolean {
        val active = activeGenerationRequest ?: return false
        return active.workflowId == request.workflowId && active.requestId == request.requestId
    }

    private fun clearActiveGenerationRequest(request: SpecWorkflowActiveGenerationRequest) {
        if (!isActiveGenerationRequest(request)) {
            return
        }
        activeGenerationJob = null
        activeGenerationRequest = null
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
        val knownSourceIds = currentWorkflowSources
            .mapTo(linkedSetOf<String>(), WorkflowSourceAsset::sourceId)
        if (knownSourceIds.isEmpty()) {
            return WorkflowSourceUsage()
        }
        val selectedSourceIds = composerSelectedSourceIdsByWorkflowId[workflowId]
            ?.filterTo(linkedSetOf()) { candidate -> candidate in knownSourceIds }
            ?.takeIf { it.isNotEmpty() }
            ?: knownSourceIds
        return WorkflowSourceUsage(selectedSourceIds = selectedSourceIds.toList())
    }

    private data class WorkflowRefreshTarget(
        val selectWorkflowId: String?,
        val preserveListMode: Boolean,
    )

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

    private fun onShowDelta() {
        val targetWorkflow = currentWorkflow
        if (targetWorkflow == null) {
            setStatusText(SpecCodingBundle.message("spec.delta.error.noCurrentWorkflow"))
            return
        }

        taskCoordinator.launchIo {
            val candidateWorkflows = specEngine.listWorkflows()
                .filter { it != targetWorkflow.id }
                .mapNotNull { workflowId ->
                    specEngine.loadWorkflow(workflowId).getOrNull()?.let { workflow ->
                        SpecBaselineSelectDialog.WorkflowOption(
                            workflowId = workflow.id,
                            title = workflow.title.ifBlank { workflow.id },
                            description = workflow.description,
                        )
                    }
                }

            if (candidateWorkflows.isEmpty()) {
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message("spec.delta.emptyCandidates"))
                }
                return@launchIo
            }

            invokeLaterSafe {
                val selectDialog = SpecBaselineSelectDialog(
                    workflowOptions = candidateWorkflows,
                    currentWorkflowId = targetWorkflow.id,
                )
                if (!selectDialog.showAndGet()) {
                    return@invokeLaterSafe
                }

                val baselineWorkflowId = selectDialog.selectedBaselineWorkflowId
                if (baselineWorkflowId.isNullOrBlank()) {
                    setStatusText(SpecCodingBundle.message("spec.delta.selectBaseline.required"))
                    return@invokeLaterSafe
                }

                taskCoordinator.launchIo {
                    val result = specDeltaService.compareByWorkflowId(
                        baselineWorkflowId = baselineWorkflowId,
                        targetWorkflowId = targetWorkflow.id,
                    )

                    invokeLaterSafe {
                        result.onSuccess { delta ->
                            showDeltaDialog(targetWorkflow, delta)
                            setStatusText(SpecCodingBundle.message("spec.delta.generated"))
                        }.onFailure { error ->
                            setStatusText(SpecCodingBundle.message(
                                "spec.workflow.error",
                                error.message ?: SpecCodingBundle.message("common.unknown"),
                            ))
                        }
                    }
                }
            }
        }
    }

    private fun showDeltaDialog(
        targetWorkflow: SpecWorkflow,
        delta: SpecWorkflowDelta,
    ) {
        SpecDeltaDialog(
            project = project,
            delta = delta,
            onOpenHistoryDiff = { phase ->
                onShowHistoryDiffForWorkflow(
                    workflowId = targetWorkflow.id,
                    phase = phase,
                    currentDoc = targetWorkflow.documents[phase],
                )
            },
            onExportReport = { format -> specDeltaService.exportReport(delta, format) },
            onReportExported = { export ->
                setStatusText(SpecCodingBundle.message("spec.delta.export.done", export.fileName))
            },
        ).show()
    }

    private fun onArchiveWorkflow() {
        val workflow = currentWorkflow
        if (workflow == null) {
            setStatusText(SpecCodingBundle.message("spec.workflow.archive.selectFirst"))
            return
        }
        if (workflow.status != WorkflowStatus.COMPLETED) {
            setStatusText(SpecCodingBundle.message("spec.workflow.archive.onlyCompleted"))
            return
        }
        if (!SpecWorkflowActionSupport.confirmArchive(project, workflow.id)) {
            return
        }

        taskCoordinator.launchIo {
            val result = specEngine.archiveWorkflow(workflow.id)
            invokeLaterSafe {
                result.onSuccess {
                    if (selectedWorkflowId == workflow.id) {
                        clearOpenedWorkflowUi(resetHighlight = false)
                    }
                    refreshWorkflows()
                    setStatusText(SpecCodingBundle.message("spec.workflow.archive.done", workflow.id))
                }.onFailure { error ->
                    setStatusText(SpecCodingBundle.message(
                        "spec.workflow.archive.failed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    ))
                }
            }
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
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.advance.preview"),
            task = {
                specEngine.previewStageTransition(
                    workflowId = workflowId,
                    transitionType = StageTransitionType.ADVANCE,
                ).getOrThrow()
            },
            onSuccess = { preview ->
                when (preview.gateResult.status) {
                    GateStatus.ERROR -> SpecWorkflowActionSupport.showGateBlocked(project, workflowId, preview.gateResult)
                    GateStatus.WARNING -> {
                        if (!SpecWorkflowActionSupport.confirmWarnings(project, workflowId, preview.gateResult)) {
                            return@runBackground
                        }
                        executeAdvanceStage(workflowId)
                    }

                    GateStatus.PASS -> executeAdvanceStage(workflowId)
                }
            },
        )
    }

    private fun onJumpStageRequested() {
        val workflowMeta = currentWorkflow?.toWorkflowMeta() ?: return
        val targets = SpecWorkflowActionSupport.jumpTargets(workflowMeta)
        if (targets.isEmpty()) {
            SpecWorkflowActionSupport.showInfo(
                project,
                SpecCodingBundle.message("spec.action.jump.none.title"),
                SpecCodingBundle.message("spec.action.jump.none.message"),
            )
            return
        }
        SpecWorkflowActionSupport.chooseStage(
            project = project,
            stages = targets,
            title = SpecCodingBundle.message("spec.action.jump.stage.popup.title"),
            workflowMeta = workflowMeta,
            onChosen = { targetStage -> previewAndJumpToStage(workflowMeta.workflowId, targetStage) },
        )
    }

    private fun onRollbackStageRequested() {
        val workflowMeta = currentWorkflow?.toWorkflowMeta() ?: return
        val targets = SpecWorkflowActionSupport.rollbackTargets(workflowMeta)
        if (targets.isEmpty()) {
            SpecWorkflowActionSupport.showInfo(
                project,
                SpecCodingBundle.message("spec.action.rollback.none.title"),
                SpecCodingBundle.message("spec.action.rollback.none.message"),
            )
            return
        }
        SpecWorkflowActionSupport.chooseStage(
            project = project,
            stages = targets,
            title = SpecCodingBundle.message("spec.action.rollback.stage.popup.title"),
            workflowMeta = workflowMeta,
            onChosen = { targetStage -> executeRollbackStage(workflowMeta.workflowId, targetStage) },
        )
    }

    private fun onTemplateCloneRequested(targetTemplate: WorkflowTemplate) {
        val workflow = currentWorkflow ?: return
        if (workflow.template == targetTemplate) {
            return
        }
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.template.clone.preview"),
            task = {
                specEngine.previewTemplateSwitch(
                    workflowId = workflow.id,
                    toTemplate = targetTemplate,
                ).getOrThrow()
            },
            onSuccess = { preview ->
                val summary = buildTemplateClonePreviewSummary(workflow, preview)
                val hasBlockingImpact = preview.artifactImpacts.any { impact ->
                    impact.strategy == TemplateSwitchArtifactStrategy.BLOCK_SWITCH
                }
                if (hasBlockingImpact) {
                    Messages.showErrorDialog(
                        project,
                        summary,
                        SpecCodingBundle.message("spec.action.template.clone.confirm.title"),
                    )
                    return@runBackground
                }
                val choice = Messages.showDialog(
                    project,
                    summary,
                    SpecCodingBundle.message("spec.action.template.clone.confirm.title"),
                    arrayOf(
                        SpecCodingBundle.message("spec.action.template.clone.confirm.continue"),
                        CommonBundle.getCancelButtonText(),
                    ),
                    0,
                    Messages.getQuestionIcon(),
                )
                if (choice != 0) {
                    return@runBackground
                }
                val cloneDialog = EditSpecWorkflowDialog(
                    initialTitle = suggestedClonedWorkflowTitle(workflow, preview.toTemplate),
                    initialDescription = workflow.description,
                    dialogTitle = SpecCodingBundle.message("spec.action.template.clone.dialog.title"),
                )
                if (!cloneDialog.showAndGet()) {
                    return@runBackground
                }
                val clonedTitle = cloneDialog.resultTitle ?: return@runBackground
                executeTemplateClone(
                    workflowId = workflow.id,
                    previewId = preview.previewId,
                    title = clonedTitle,
                    description = cloneDialog.resultDescription,
                    targetTemplate = preview.toTemplate,
                )
            },
        )
    }

    private fun previewAndJumpToStage(workflowId: String, targetStage: StageId) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.jump.preview"),
            task = {
                specEngine.previewStageTransition(
                    workflowId = workflowId,
                    transitionType = StageTransitionType.JUMP,
                    targetStage = targetStage,
                ).getOrThrow()
            },
            onSuccess = { preview ->
                when (preview.gateResult.status) {
                    GateStatus.ERROR -> SpecWorkflowActionSupport.showGateBlocked(project, workflowId, preview.gateResult)
                    GateStatus.WARNING -> {
                        if (!SpecWorkflowActionSupport.confirmWarnings(project, workflowId, preview.gateResult)) {
                            return@runBackground
                        }
                        executeJumpStage(workflowId, targetStage)
                    }

                    GateStatus.PASS -> executeJumpStage(workflowId, targetStage)
                }
            },
        )
    }

    private fun executeAdvanceStage(workflowId: String) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.advance.executing"),
            task = { specEngine.advanceWorkflow(workflowId) { true }.getOrThrow() },
            onSuccess = { result ->
                handleStageTransitionCompleted(
                    workflowId = workflowId,
                    successMessage = SpecCodingBundle.message(
                        "spec.action.advance.success",
                        SpecWorkflowActionSupport.stageLabel(result.targetStage),
                    ),
                )
            },
        )
    }

    private fun executeJumpStage(workflowId: String, targetStage: StageId) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.jump.executing"),
            task = { specEngine.jumpToStage(workflowId, targetStage) { true }.getOrThrow() },
            onSuccess = { result ->
                handleStageTransitionCompleted(
                    workflowId = workflowId,
                    successMessage = SpecCodingBundle.message(
                        "spec.action.jump.success",
                        SpecWorkflowActionSupport.stageLabel(result.targetStage),
                    ),
                )
            },
        )
    }

    private fun executeRollbackStage(workflowId: String, targetStage: StageId) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.rollback.executing"),
            task = { specEngine.rollbackToStage(workflowId, targetStage).getOrThrow() },
            onSuccess = { meta ->
                handleStageTransitionCompleted(
                    workflowId = workflowId,
                    successMessage = SpecCodingBundle.message(
                        "spec.action.rollback.success",
                        SpecWorkflowActionSupport.stageLabel(meta.currentStage),
                    ),
                )
            },
        )
    }

    private fun executeTemplateClone(
        workflowId: String,
        previewId: String,
        title: String,
        description: String?,
        targetTemplate: WorkflowTemplate,
    ) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.template.clone.executing"),
            task = {
                specEngine.cloneWorkflowWithTemplate(
                    workflowId = workflowId,
                    previewId = previewId,
                    title = title,
                    description = description,
                ).getOrThrow()
            },
            onSuccess = { result ->
                SpecWorkflowActionSupport.notifySuccess(
                    project,
                    SpecCodingBundle.message(
                        "spec.action.template.clone.success",
                        result.workflow.title.ifBlank { result.workflow.id },
                        SpecWorkflowOverviewPresenter.templateLabel(targetTemplate),
                    ),
                )
                highlightedWorkflowId = result.workflow.id
                publishWorkflowSelection(result.workflow.id)
                refreshWorkflows(selectWorkflowId = result.workflow.id)
            },
        )
    }

    private fun handleStageTransitionCompleted(workflowId: String, successMessage: String) {
        SpecWorkflowActionSupport.notifySuccess(project, successMessage)
        focusedStage = null
        publishWorkflowSelection(workflowId)
        refreshWorkflows(selectWorkflowId = workflowId)
    }

    private fun buildTemplateClonePreviewSummary(
        workflow: SpecWorkflow,
        preview: TemplateSwitchPreview,
    ): String {
        val lines = mutableListOf<String>()
        lines += SpecCodingBundle.message("spec.action.template.clone.summary.workflow", workflow.id)
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.templates",
            SpecWorkflowOverviewPresenter.templateLabel(preview.fromTemplate),
            SpecWorkflowOverviewPresenter.templateLabel(preview.toTemplate),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.stage",
            SpecWorkflowActionSupport.stageLabel(preview.currentStage),
            SpecWorkflowActionSupport.stageLabel(preview.resultingStage),
        )
        if (preview.currentStageChanged) {
            lines += SpecCodingBundle.message("spec.action.template.clone.summary.stageChanged")
        }
        lines += ""
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.addedStages",
            formatStageList(preview.addedActiveStages),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.deactivatedStages",
            formatStageList(preview.deactivatedStages),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.gateAddedStages",
            formatStageList(preview.gateAddedStages),
        )
        lines += SpecCodingBundle.message(
            "spec.action.template.clone.summary.gateRemovedStages",
            formatStageList(preview.gateRemovedStages),
        )
        lines += ""
        lines += SpecCodingBundle.message("spec.action.template.clone.summary.artifacts")
        preview.artifactImpacts.forEach { impact ->
            lines += SpecCodingBundle.message(
                "spec.action.template.clone.summary.artifact",
                impact.fileName,
                SpecWorkflowActionSupport.stageLabel(impact.stageId),
                templateSwitchStrategyLabel(impact.strategy),
            )
        }
        if (preview.artifactImpacts.any { impact -> impact.strategy == TemplateSwitchArtifactStrategy.BLOCK_SWITCH }) {
            lines += ""
            lines += SpecCodingBundle.message("spec.action.template.clone.summary.blocked")
        }
        lines += ""
        lines += SpecCodingBundle.message("spec.action.template.clone.summary.note")
        return lines.joinToString("\n")
    }

    private fun formatStageList(stages: List<StageId>): String {
        if (stages.isEmpty()) {
            return SpecCodingBundle.message("spec.action.template.clone.summary.none")
        }
        return stages.joinToString(", ") { stage ->
            SpecWorkflowActionSupport.stageLabel(stage)
        }
    }

    private fun templateSwitchStrategyLabel(strategy: TemplateSwitchArtifactStrategy): String {
        return when (strategy) {
            TemplateSwitchArtifactStrategy.REUSE_EXISTING ->
                SpecCodingBundle.message("spec.action.template.clone.strategy.reuse")
            TemplateSwitchArtifactStrategy.GENERATE_SKELETON ->
                SpecCodingBundle.message("spec.action.template.clone.strategy.generate")
            TemplateSwitchArtifactStrategy.BLOCK_SWITCH ->
                SpecCodingBundle.message("spec.action.template.clone.strategy.block")
        }
    }

    private fun suggestedClonedWorkflowTitle(
        workflow: SpecWorkflow,
        targetTemplate: WorkflowTemplate,
    ): String {
        val baseTitle = workflow.title.ifBlank { workflow.id }
        return "$baseTitle (${SpecWorkflowOverviewPresenter.templateLabel(targetTemplate)})"
    }

    private fun onShowCodeGraph() {
        setStatusText(SpecCodingBundle.message("code.graph.status.generating"))
        taskCoordinator.launchDefault {
            val result = codeGraphService.buildFromActiveEditor()
            invokeLaterSafe {
                result.onSuccess { snapshot ->
                    if (snapshot.edges.isEmpty()) {
                        setStatusText(SpecCodingBundle.message("code.graph.status.empty"))
                        return@onSuccess
                    }

                    val summary = CodeGraphRenderer.renderSummary(snapshot)
                    val mermaid = CodeGraphRenderer.renderMermaid(snapshot)
                    CodeGraphDialog(summary = summary, mermaid = mermaid).show()
                    setStatusText(SpecCodingBundle.message(
                        "code.graph.status.generated",
                        snapshot.nodes.size,
                        snapshot.edges.size,
                    ))
                }.onFailure { error ->
                    setStatusText(SpecCodingBundle.message(
                        "code.graph.status.failed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    ))
                }
            }
        }
    }

    private fun onNextPhase() {
        val wfId = selectedWorkflowId ?: return
        taskCoordinator.launchIo {
            specEngine.proceedToNextPhase(wfId).onSuccess {
                invokeLaterSafe {
                    detailPanel.clearInput()
                    reloadCurrentWorkflow(followCurrentPhase = true)
                }
            }.onFailure { e ->
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message(
                        "spec.workflow.error",
                        e.message ?: SpecCodingBundle.message("common.unknown")
                    ))
                }
            }
        }
    }

    private fun onGoBack() {
        val wfId = selectedWorkflowId ?: return
        taskCoordinator.launchIo {
            specEngine.goBackToPreviousPhase(wfId).onSuccess {
                invokeLaterSafe {
                    detailPanel.clearInput()
                    reloadCurrentWorkflow(followCurrentPhase = true)
                }
            }.onFailure { e ->
                invokeLaterSafe {
                    setStatusText(SpecCodingBundle.message(
                        "spec.workflow.error",
                        e.message ?: SpecCodingBundle.message("common.unknown")
                    ))
                }
            }
        }
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

    private fun subscribeToToolWindowControlEvents() {
        project.messageBus.connect(this).subscribe(
            SpecToolWindowControlListener.TOPIC,
            object : SpecToolWindowControlListener {
                override fun onCreateWorkflowRequested(preferredTemplate: WorkflowTemplate?) {
                    invokeLaterSafe {
                        if (project.isDisposed || _isDisposed) return@invokeLaterSafe
                        onCreateWorkflow(preferredTemplate)
                    }
                }

                override fun onSelectWorkflowRequested(workflowId: String) {
                    invokeLaterSafe {
                        if (project.isDisposed || _isDisposed) return@invokeLaterSafe
                        openWorkflowFromRequest(SpecToolWindowOpenRequest(workflowId = workflowId))
                    }
                }

                override fun onOpenWorkflowRequested(request: SpecToolWindowOpenRequest) {
                    invokeLaterSafe {
                        if (project.isDisposed || _isDisposed) return@invokeLaterSafe
                        openWorkflowFromRequest(request)
                    }
                }
            },
        )
    }

    private fun subscribeToWorkflowEvents() {
        project.messageBus.connect(this).subscribe(
            SpecWorkflowChangedListener.TOPIC,
            object : SpecWorkflowChangedListener {
                override fun onWorkflowChanged(event: SpecWorkflowChangedEvent) {
                    if (event.reason == SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED) {
                        return
                    }
                    refreshWorkflows(selectWorkflowId = event.workflowId)
                }
            },
        )
    }

    private fun subscribeToDocumentFileEvents() {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val workflowId = selectedWorkflowId ?: return
                    val basePath = project.basePath ?: return
                    if (!containsCurrentWorkflowDocumentChange(events, basePath, workflowId)) {
                        return
                    }
                    scheduleDocumentReload(workflowId)
                }
            },
        )
    }

    private fun scheduleDocumentReload(workflowId: String) {
        pendingDocumentReloadJob?.cancel()
        pendingDocumentReloadJob = taskCoordinator.launchDefault {
            delay(DOCUMENT_RELOAD_DEBOUNCE_MILLIS)
            if (_isDisposed || project.isDisposed || selectedWorkflowId != workflowId) {
                return@launchDefault
            }
            reloadCurrentWorkflow()
        }
    }

    private fun containsCurrentWorkflowDocumentChange(
        events: List<VFileEvent>,
        basePath: String,
        workflowId: String,
    ): Boolean {
        if (events.isEmpty()) return false
        val normalizedBasePath = basePath
            .replace('\\', '/')
            .trimEnd('/')
            .lowercase(Locale.ROOT)
        val targetPrefix = "$normalizedBasePath/.spec-coding/specs/$workflowId/"
        return events.any { event ->
            val normalizedPath = event.path
                .replace('\\', '/')
                .lowercase(Locale.ROOT)
            if (!normalizedPath.startsWith(targetPrefix)) {
                return@any false
            }
            val fileName = normalizedPath.substringAfterLast('/')
            SPEC_DOCUMENT_FILE_NAMES.contains(fileName)
        }
    }

    private fun refreshLocalizedTexts() {
        workflowSwitcherPopup?.cancel()
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
        if (::documentWorkspaceViewTabsPanel.isInitialized) {
            if (::documentWorkspaceViewLabel.isInitialized) {
                documentWorkspaceViewLabel.text = SpecCodingBundle.message("spec.toolwindow.documents.view.label")
            }
            documentWorkspaceViewButtons.forEach { (view, button) ->
                when (view) {
                    DocumentWorkspaceView.DOCUMENT -> {
                        button.text = SpecCodingBundle.message("spec.toolwindow.documents.view.document")
                        button.toolTipText = SpecCodingBundle.message("spec.toolwindow.documents.view.document.tooltip")
                    }

                    DocumentWorkspaceView.STRUCTURED_TASKS -> {
                        button.text = SpecCodingBundle.message("spec.toolwindow.documents.view.structuredTasks")
                        button.toolTipText = SpecCodingBundle.message("spec.toolwindow.documents.view.structuredTasks.tooltip")
                    }
                }
            }
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

    private fun onComplete() {
        val wfId = selectedWorkflowId ?: return
        taskCoordinator.launchIo {
            specEngine.completeWorkflow(wfId)
                .onSuccess { workflow ->
                    invokeLaterSafe {
                        reloadCurrentWorkflow()
                        refreshWorkflows()
                        setStatusText(SpecCodingBundle.message("toolwindow.spec.command.completed", workflow.id))
                    }
                }
                .onFailure { error ->
                    val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                    invokeLaterSafe {
                        setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                    }
                }
        }
    }

    private fun onPauseResume() {
        val wfId = selectedWorkflowId ?: return
        val isPaused = currentWorkflow?.status == WorkflowStatus.PAUSED
        taskCoordinator.launchIo {
            val result = if (isPaused)
                specEngine.resumeWorkflow(wfId)
            else
                specEngine.pauseWorkflow(wfId)
            result.onSuccess {
                invokeLaterSafe {
                    reloadCurrentWorkflow()
                    refreshWorkflows()
                }
            }
        }
    }

    private fun onOpenInEditor(phase: SpecPhase) {
        val wfId = selectedWorkflowId ?: return
        val basePath = project.basePath ?: return
        val filePath = "$basePath/.spec-coding/specs/$wfId/${phase.outputFileName}"
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    private fun onOpenArtifactInEditor(fileName: String) {
        val workflowId = selectedWorkflowId ?: return
        val path = runCatching { artifactService.locateArtifact(workflowId, fileName) }.getOrNull() ?: return
        if (!Files.exists(path) || !SpecWorkflowActionSupport.openFile(project, path)) {
            setStatusText(SpecCodingBundle.message("spec.action.verify.document.unavailable.title"))
        }
    }

    private fun onShowHistoryDiff(phase: SpecPhase) {
        val workflow = currentWorkflow ?: return
        onShowHistoryDiffForWorkflow(
            workflowId = workflow.id,
            phase = phase,
            currentDoc = workflow.documents[phase],
        )
    }

    private fun onSaveDocument(
        phase: SpecPhase,
        content: String,
        onDone: (Result<SpecWorkflow>) -> Unit,
    ) {
        val wfId = selectedWorkflowId
        if (wfId.isNullOrBlank()) {
            onDone(Result.failure(IllegalStateException(SpecCodingBundle.message("spec.detail.noWorkflow"))))
            return
        }
        taskCoordinator.launchIo {
            val result = specEngine.updateDocumentContent(
                workflowId = wfId,
                phase = phase,
                content = content,
            )
            currentWorkflow = result.getOrNull() ?: currentWorkflow
            invokeLaterSafe {
                result.onSuccess { wf ->
                    phaseIndicator.updatePhase(wf)
                    archiveButton.isEnabled = wf.status == WorkflowStatus.COMPLETED
                    setStatusText(null)
                }.onFailure { error ->
                    val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                    setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                }
                onDone(result)
            }
        }
    }

    private fun onShowHistoryDiffForWorkflow(
        workflowId: String,
        phase: SpecPhase,
        currentDoc: SpecDocument?,
    ) {
        if (currentDoc == null) {
            setStatusText(SpecCodingBundle.message("spec.history.noCurrentDocument"))
            return
        }

        taskCoordinator.launchIo {
            val history = specEngine.listDocumentHistory(workflowId, phase)
            val snapshots = history.mapNotNull { entry ->
                specEngine.loadDocumentSnapshot(workflowId, phase, entry.snapshotId).getOrNull()?.let { snapshotDoc ->
                    SpecHistoryDiffDialog.SnapshotVersion(
                        snapshotId = entry.snapshotId,
                        createdAt = entry.createdAt,
                        document = snapshotDoc,
                    )
                }
            }

            invokeLaterSafe {
                if (snapshots.isEmpty()) {
                    setStatusText(SpecCodingBundle.message("spec.history.noSnapshot"))
                    return@invokeLaterSafe
                }

                SpecHistoryDiffDialog(
                    phase = phase,
                    currentDocument = currentDoc,
                    snapshots = snapshots,
                    onDeleteSnapshot = { snapshot ->
                        specEngine.deleteDocumentSnapshot(workflowId, phase, snapshot.snapshotId).isSuccess
                    },
                    onPruneSnapshots = { keepLatest ->
                        specEngine.pruneDocumentHistory(workflowId, phase, keepLatest).getOrElse { -1 }
                    },
                    onExportSummary = { content ->
                        exportHistoryDiffSummary(workflowId, phase, content)
                    },
                ).show()

                setStatusText(SpecCodingBundle.message(
                    "spec.history.diff.opened",
                    phase.displayName,
                    snapshots.size,
                ))
            }
        }
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
        val wfId = selectedWorkflowId ?: return
        if (followCurrentPhase) {
            focusedStage = null
        }
        invokeLaterSafe {
            showWorkflowLoadInProgress()
        }
        taskCoordinator.launchIo {
            val loadedState = loadCoordinator.load(
                SpecWorkflowPanelLoadRequest(
                    workflowId = wfId,
                    includeSources = false,
                ),
            )
            applyLoadedWorkflow(
                workflowId = wfId,
                loadedState = loadedState,
                followCurrentPhase = followCurrentPhase,
                onUpdated = onUpdated,
            )
        }
    }

    private data class WorkspaceChipColors(
        val foreground: Color,
    )

    private data class WorkspaceSummaryMetric(
        val root: JPanel,
        val titleLabel: JBLabel,
        val valueLabel: JBLabel,
    )

    private fun showWorkflowLoadInProgress() {
        overviewPanel.showLoading()
        verifyDeltaPanel.showLoading()
        tasksPanel.showLoading()
        detailTasksPanel.showLoading()
        gateDetailsPanel.showLoading()
        showWorkspaceContent()
    }

    private fun applyLoadedWorkflow(
        workflowId: String,
        loadedState: SpecWorkflowPanelLoadedState,
        followCurrentPhase: Boolean = false,
        previousSelectedWorkflowId: String? = null,
        onUpdated: ((SpecWorkflow) -> Unit)? = null,
    ) {
        invokeLaterSafe {
            if (selectedWorkflowId != workflowId) {
                return@invokeLaterSafe
            }
            val workflow = loadedState.workflow
            if (workflow == null) {
                clearOpenedWorkflowUi(resetHighlight = false)
                return@invokeLaterSafe
            }

            currentWorkflow = workflow
            syncClarificationRetryFromWorkflow(workflow)
            phaseIndicator.updatePhase(workflow)
            val snapshot = loadedState.uiSnapshot ?: buildWorkflowUiSnapshot(workflow)
            overviewPanel.updateOverview(snapshot.overviewState)
            verifyDeltaPanel.updateState(snapshot.verifyDeltaState)
            gateDetailsPanel.updateGateResult(
                workflowId = workflowId,
                gateResult = snapshot.gateResult,
                refreshedAtMillis = snapshot.refreshedAtMillis,
            )
            detailPanel.updateWorkflow(workflow, followCurrentPhase = followCurrentPhase)
            applyAutoCodeContextToDetailPanel(workflow, loadedState.codeContextResult)
            loadedState.sourcesResult
                ?.onSuccess { sources ->
                    applyWorkflowSourcesToDetailPanel(
                        workflow = workflow,
                        assets = sources,
                        preserveSelection = previousSelectedWorkflowId == workflowId,
                    )
                }
                ?.onFailure { error ->
                    applyWorkflowSourcesToDetailPanel(
                        workflow = workflow,
                        assets = emptyList(),
                        preserveSelection = false,
                    )
                    val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
                    setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
                }
            applyLoadedWorkflowTasks(
                workflow = workflow,
                snapshot = snapshot,
                tasksResult = loadedState.tasksResult,
                liveProgressByTaskId = loadedState.liveProgressByTaskId,
            )
            if (previousSelectedWorkflowId != null && previousSelectedWorkflowId != workflowId) {
                restorePendingClarificationState(workflowId)
            }
            applyPendingOpenWorkflowRequestIfNeeded(workflowId)
            createWorktreeButton.isEnabled = true
            mergeWorktreeButton.isEnabled = true
            deltaButton.isEnabled = true
            archiveButton.isEnabled = workflow.status == WorkflowStatus.COMPLETED
            onUpdated?.invoke(workflow)
        }
    }

    private fun applyLoadedWorkflowTasks(
        workflow: SpecWorkflow,
        snapshot: SpecWorkflowUiSnapshot,
        tasksResult: Result<List<StructuredTask>>,
        liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
    ) {
        val refreshedAtMillis = System.currentTimeMillis()
        tasksResult.onSuccess { tasks ->
            val decoratedTasks = decorateTasksWithExecutionState(
                workflow = workflow,
                tasks = tasks,
                liveProgressByTaskId = liveProgressByTaskId,
            )
            tasksPanel.updateTasks(
                workflowId = workflow.id,
                tasks = decoratedTasks,
                liveProgressByTaskId = liveProgressByTaskId,
                refreshedAtMillis = refreshedAtMillis,
            )
            detailTasksPanel.updateTasks(
                workflowId = workflow.id,
                tasks = decoratedTasks,
                liveProgressByTaskId = liveProgressByTaskId,
                refreshedAtMillis = refreshedAtMillis,
            )
            updateWorkspacePresentation(
                workflow = workflow,
                overviewState = snapshot.overviewState,
                tasks = decoratedTasks,
                liveProgressByTaskId = liveProgressByTaskId,
                verifyDeltaState = snapshot.verifyDeltaState,
                gateResult = snapshot.gateResult,
            )
        }.onFailure { error ->
            tasksPanel.updateTasks(
                workflowId = workflow.id,
                tasks = emptyList(),
                liveProgressByTaskId = emptyMap(),
                refreshedAtMillis = refreshedAtMillis,
            )
            detailTasksPanel.updateTasks(
                workflowId = workflow.id,
                tasks = emptyList(),
                liveProgressByTaskId = emptyMap(),
                refreshedAtMillis = refreshedAtMillis,
            )
            updateWorkspacePresentation(
                workflow = workflow,
                overviewState = snapshot.overviewState,
                tasks = emptyList(),
                liveProgressByTaskId = emptyMap(),
                verifyDeltaState = snapshot.verifyDeltaState,
                gateResult = snapshot.gateResult,
            )
            val message = compactErrorMessage(error, SpecCodingBundle.message("common.unknown"))
            setStatusText(SpecCodingBundle.message("spec.workflow.error", message))
        }
    }

    private fun buildWorkflowUiSnapshot(workflow: SpecWorkflow): SpecWorkflowUiSnapshot {
        val refreshedAtMillis = System.currentTimeMillis()
        val gatePreview = if (workflow.status == WorkflowStatus.COMPLETED || workflow.currentStage == StageId.ARCHIVE) {
            null
        } else {
            specEngine.previewStageTransition(
                workflowId = workflow.id,
                transitionType = StageTransitionType.ADVANCE,
            ).getOrElse { error ->
                logger.debug("Unable to preview advance gate for workflow ${workflow.id}", error)
                null
            }
        }
        return SpecWorkflowUiSnapshot(
            overviewState = SpecWorkflowOverviewPresenter.buildState(
                workflow = workflow,
                gatePreview = gatePreview,
                refreshedAtMillis = refreshedAtMillis,
            ),
            verifyDeltaState = buildVerifyDeltaState(
                workflow = workflow,
                refreshedAtMillis = refreshedAtMillis,
            ),
            gateResult = gatePreview?.gateResult,
            refreshedAtMillis = refreshedAtMillis,
        )
    }

    private fun buildVerifyDeltaState(
        workflow: SpecWorkflow,
        refreshedAtMillis: Long,
    ): SpecWorkflowVerifyDeltaState {
        val verificationHistory = runCatching {
            specVerificationService.listRunHistory(workflow.id)
        }.getOrElse { error ->
            logger.debug("Unable to load verification history for workflow ${workflow.id}", error)
            emptyList()
        }
        val baselineChoices = buildList {
            workflow.baselineWorkflowId
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != workflow.id }
                ?.let { baselineWorkflowId ->
                    val baselineTitle = specEngine.loadWorkflow(baselineWorkflowId).getOrNull()
                        ?.title
                        ?.ifBlank { baselineWorkflowId }
                        ?: baselineWorkflowId
                    add(
                        SpecWorkflowReferenceBaselineChoice(
                            workflowId = baselineWorkflowId,
                            title = baselineTitle,
                        ),
                    )
                }
            addAll(
                runCatching {
                    specEngine.listDeltaBaselines(workflow.id)
                }.getOrElse { error ->
                    logger.debug("Unable to load delta baselines for workflow ${workflow.id}", error)
                    emptyList()
                }.map(::SpecWorkflowPinnedDeltaBaselineChoice),
            )
        }
        val canPinBaseline = runCatching {
            specEngine.listWorkflowSnapshots(workflow.id).isNotEmpty()
        }.getOrElse { error ->
            logger.debug("Unable to inspect workflow snapshots for ${workflow.id}", error)
            false
        }
        val deltaSummary = baselineChoices.firstOrNull()?.let { preferredChoice ->
            runCatching {
                when (preferredChoice) {
                    is SpecWorkflowReferenceBaselineChoice -> specDeltaService.compareByWorkflowId(
                        baselineWorkflowId = preferredChoice.workflowId,
                        targetWorkflowId = workflow.id,
                    )

                    is SpecWorkflowPinnedDeltaBaselineChoice -> specDeltaService.compareByDeltaBaseline(
                        workflowId = workflow.id,
                        baselineId = preferredChoice.baseline.baselineId,
                    )
                }.getOrThrow()
            }.map(::buildPreferredDeltaSummary).getOrElse { error ->
                logger.debug("Unable to build delta summary for workflow ${workflow.id}", error)
                null
            }
        }
        return SpecWorkflowVerifyDeltaState(
            workflowId = workflow.id,
            verifyEnabled = workflow.verifyEnabled || workflow.stageStates[StageId.VERIFY]?.active == true,
            verificationDocumentAvailable = hasVerificationArtifact(workflow.id),
            verificationHistory = verificationHistory,
            baselineChoices = baselineChoices,
            deltaSummary = deltaSummary,
            preferredBaselineChoiceId = baselineChoices.firstOrNull()?.stableId,
            canPinBaseline = canPinBaseline,
            refreshedAtMillis = refreshedAtMillis,
        )
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

    internal fun isSwitchWorkflowPopupVisibleForTest(): Boolean {
        return workflowSwitcherPopup?.isVisibleForTest() == true
    }

    internal fun switchWorkflowPopupVisibleWorkflowIdsForTest(): List<String> {
        return workflowSwitcherPopup?.visibleWorkflowIdsForTest().orEmpty()
    }

    internal fun filterSwitchWorkflowPopupForTest(query: String) {
        workflowSwitcherPopup?.applySearchForTest(query)
    }

    internal fun confirmSwitchWorkflowPopupSelectionForTest() {
        workflowSwitcherPopup?.confirmSelectionForTest()
    }

    internal fun selectedSwitchWorkflowPopupSelectionForTest(): String? {
        return workflowSwitcherPopup?.selectedWorkflowIdForTest()
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
        val workflow = currentWorkflow ?: return
        val overviewState = currentOverviewState ?: buildWorkflowUiSnapshot(workflow).overviewState
        val verifyState = currentVerifyDeltaState ?: buildVerifyDeltaState(
            workflow = workflow,
            refreshedAtMillis = System.currentTimeMillis(),
        )
        updateWorkspacePresentation(
            workflow = workflow,
            overviewState = overviewState,
            tasks = currentStructuredTasks,
            liveProgressByTaskId = currentTaskLiveProgressByTaskId,
            verifyDeltaState = verifyState,
            gateResult = currentGateResult,
        )
    }

    internal fun focusStageForTest(stageId: StageId) {
        focusStage(stageId)
    }

    private fun openWorkflowFromRequest(request: SpecToolWindowOpenRequest) {
        val normalizedRequest = workflowPanelState.rememberPendingOpenRequest(request)
        val normalizedWorkflowId = normalizedRequest.workflowId.ifBlank {
            workflowPanelState.clearPendingOpenRequest()
            return
        }
        if (selectedWorkflowId == normalizedWorkflowId && currentWorkflow?.id == normalizedWorkflowId) {
            applyPendingOpenWorkflowRequestIfNeeded(normalizedWorkflowId)
            if (pendingOpenWorkflowRequest == null) {
                return
            }
        }
        refreshWorkflows(selectWorkflowId = normalizedWorkflowId)
    }

    private fun applyPendingOpenWorkflowRequestIfNeeded(workflowId: String) {
        val request = pendingOpenWorkflowRequest ?: return
        if (request.workflowId != workflowId) {
            return
        }
        request.focusedStage?.let(::focusStage)
        request.taskId?.let { taskId ->
            syncStructuredTaskSelection(taskId)
            if (supportsStructuredTasksDocumentWorkspaceView(currentWorkbenchState)) {
                selectedDocumentWorkspaceView = DocumentWorkspaceView.STRUCTURED_TASKS
                updateDocumentWorkspaceViewPresentation(currentWorkbenchState)
            }
        }
        workflowPanelState.clearPendingOpenRequest()
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

    private fun onPreviewVerificationPlanRequested(workflowId: String) {
        SpecWorkflowActionSupport.runBackground(
            project = project,
            title = SpecCodingBundle.message("spec.action.verify.preview"),
            task = {
                val plan = specVerificationService.preview(workflowId)
                val scopeTasks = specTasksService.parse(workflowId).sortedBy(StructuredTask::id)
                SpecWorkflowActionSupport.verificationPlanSummary(plan, scopeTasks)
            },
            onSuccess = { summary ->
                SpecWorkflowActionSupport.showInfo(
                    project = project,
                    title = SpecCodingBundle.message("spec.action.verify.confirm.title"),
                    message = summary,
                )
            },
        )
    }

    private fun buildPreferredDeltaSummary(delta: SpecWorkflowDelta): String {
        return SpecCodingBundle.message(
            "spec.delta.summary",
            delta.baselineWorkflowId,
            delta.targetWorkflowId,
            delta.count(SpecDeltaStatus.ADDED),
            delta.count(SpecDeltaStatus.MODIFIED),
            delta.count(SpecDeltaStatus.REMOVED),
            delta.count(SpecDeltaStatus.UNCHANGED),
        )
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

    internal fun documentWorkspaceViewForTest(): String =
        if (::documentWorkspaceViewTabsPanel.isInitialized && documentWorkspaceViewTabsPanel.isVisible) {
            selectedDocumentWorkspaceView.name
        } else {
            DocumentWorkspaceView.DOCUMENT.name
        }

    internal fun isDocumentWorkspaceViewTabsVisibleForTest(): Boolean =
        ::documentWorkspaceViewTabsPanel.isInitialized && documentWorkspaceViewTabsPanel.isVisible

    internal fun clickDocumentWorkspaceViewForTest(view: String) {
        val targetView = runCatching { DocumentWorkspaceView.valueOf(view) }.getOrNull() ?: return
        documentWorkspaceViewButtons[targetView]?.doClick()
    }

    internal fun documentWorkspaceViewButtonsForTest(): List<String> =
        DocumentWorkspaceView.entries.mapNotNull { view ->
            documentWorkspaceViewButtons[view]?.text?.takeIf(String::isNotBlank)?.let { label ->
                "${view.name}:$label"
            }
        }

    internal fun documentWorkspaceViewLabelForTest(): String =
        if (::documentWorkspaceViewLabel.isInitialized) {
            documentWorkspaceViewLabel.text.orEmpty()
        } else {
            ""
        }

    internal fun documentWorkspaceViewSwitcherHeightForTest(): Int =
        if (::documentWorkspaceViewSwitcherPanel.isInitialized) {
            documentWorkspaceViewSwitcherPanel.preferredSize.height
        } else {
            0
        }

    internal fun documentWorkspaceViewButtonHeightsForTest(): Map<String, Int> =
        DocumentWorkspaceView.entries.associate { view ->
            view.name to (documentWorkspaceViewButtons[view]?.preferredSize?.height ?: 0)
        }

    internal fun documentWorkspaceViewButtonWidthsForTest(): Map<String, Int> =
        DocumentWorkspaceView.entries.associate { view ->
            view.name to (documentWorkspaceViewButtons[view]?.preferredSize?.width ?: 0)
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

    internal fun composerSourceChipLabelsForTest(): List<String> = detailPanel.composerSourceChipLabelsForTest()

    internal fun composerSourceMetaTextForTest(): String = detailPanel.composerSourceMetaTextForTest()

    internal fun composerSourceHintTextForTest(): String = detailPanel.composerSourceHintTextForTest()

    internal fun composerCodeContextSummaryChipLabelsForTest(): List<String> =
        detailPanel.composerCodeContextSummaryChipLabelsForTest()

    internal fun composerCodeContextCandidateLabelsForTest(): List<String> =
        detailPanel.composerCodeContextCandidateLabelsForTest()

    internal fun composerCodeContextMetaTextForTest(): String = detailPanel.composerCodeContextMetaTextForTest()

    internal fun composerCodeContextHintTextForTest(): String = detailPanel.composerCodeContextHintTextForTest()

    internal fun currentStatusTextForTest(): String = statusLabel.text.orEmpty()

    internal fun currentStatusActionLabelsForTest(): List<String> {
        return statusActionPanel.components
            .filterIsInstance<JButton>()
            .map { button -> button.text.orEmpty() }
            .filter { label -> label.isNotBlank() }
    }

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

    internal fun startRequirementsClarifyThenFillForTest(
        workflowId: String,
        missingSections: List<RequirementsSectionId>,
    ): Boolean = startRequirementsClarifyThenFill(workflowId, missingSections)

    internal fun workspaceSummarySnapshotForTest(): Map<String, String> {
        return mapOf(
            "stageTitle" to workspaceStageMetric.titleLabel.text.orEmpty(),
            "stageValue" to workspaceStageMetric.valueLabel.text.orEmpty(),
            "gateTitle" to workspaceGateMetric.titleLabel.text.orEmpty(),
            "gateValue" to workspaceGateMetric.valueLabel.text.orEmpty(),
            "tasksTitle" to workspaceTasksMetric.titleLabel.text.orEmpty(),
            "tasksValue" to workspaceTasksMetric.valueLabel.text.orEmpty(),
            "verifyTitle" to workspaceVerifyMetric.titleLabel.text.orEmpty(),
            "verifyValue" to workspaceVerifyMetric.valueLabel.text.orEmpty(),
            "focusTitle" to workspaceSummaryFocusLabel.text.orEmpty(),
            "focusHint" to workspaceSummaryHintLabel.text.orEmpty(),
        )
    }

    override fun dispose() {
        _isDisposed = true
        pendingDocumentReloadJob?.cancel()
        CliDiscoveryService.getInstance().removeDiscoveryListener(discoveryListener)
        liveProgressEventCoalesceTimer.stop()
        liveProgressRefreshTimer.stop()
        specTaskExecutionService.removeLiveProgressListener(liveProgressListener)
        workflowSwitcherPopup?.cancel()
        cancelActiveGenerationRequest("Spec workflow panel disposed")
        taskCoordinator.dispose()
    }

    companion object {
        private val TOOLBAR_BG = JBColor(Color(248, 250, 254), Color(58, 64, 74))
        private val TOOLBAR_BORDER = JBColor(Color(212, 222, 239), Color(89, 100, 117))
        private val WORKSPACE_SUMMARY_BG = JBColor(Color(245, 249, 255), Color(56, 62, 72))
        private val WORKSPACE_SUMMARY_BORDER = JBColor(Color(201, 214, 235), Color(86, 96, 110))
        private val WORKSPACE_SUMMARY_TITLE_FG = JBColor(Color(42, 59, 94), Color(214, 223, 236))
        private val WORKSPACE_SUMMARY_META_FG = JBColor(Color(94, 110, 139), Color(160, 171, 188))
        private val WORKSPACE_SUMMARY_LABEL_FG = JBColor(Color(112, 124, 143), Color(172, 182, 196))
        private val WORKSPACE_EMPTY_TITLE_FG = JBColor(Color(57, 72, 104), Color(214, 223, 236))
        private val WORKSPACE_EMPTY_DESCRIPTION_FG = JBColor(Color(101, 117, 145), Color(166, 176, 193))
        private val WORKSPACE_INFO_CHIP_FG = JBColor(Color(48, 74, 112), Color(210, 220, 235))
        private val WORKSPACE_SUCCESS_CHIP_FG = JBColor(Color(42, 118, 71), Color(177, 225, 194))
        private val WORKSPACE_WARNING_CHIP_FG = JBColor(Color(140, 96, 28), Color(239, 210, 146))
        private val WORKSPACE_ERROR_CHIP_FG = JBColor(Color(152, 52, 52), Color(244, 182, 182))
        private val WORKSPACE_MUTED_CHIP_FG = JBColor(Color(98, 109, 126), Color(173, 181, 194))
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
        private val DOCUMENT_WORKSPACE_VIEW_LABEL_FG = JBColor(Color(112, 124, 143), Color(172, 182, 196))
        private val DOCUMENT_WORKSPACE_VIEW_GROUP_BG = JBColor(Color(242, 247, 255), Color(57, 63, 73))
        private val DOCUMENT_WORKSPACE_VIEW_GROUP_BORDER = JBColor(Color(202, 215, 236), Color(89, 100, 116))
        private val DOCUMENT_WORKSPACE_VIEW_SELECTED_BG = JBColor(Color(233, 242, 255), Color(71, 80, 95))
        private val DOCUMENT_WORKSPACE_VIEW_SELECTED_BORDER = JBColor(Color(174, 196, 229), Color(112, 126, 148))
        private val DOCUMENT_WORKSPACE_VIEW_SELECTED_FG = JBColor(Color(43, 67, 105), Color(214, 224, 238))
        private val DOCUMENT_WORKSPACE_VIEW_IDLE_BG = DOCUMENT_WORKSPACE_VIEW_GROUP_BG
        private val DOCUMENT_WORKSPACE_VIEW_IDLE_FG = JBColor(Color(89, 103, 130), Color(177, 188, 203))
        private val DOCUMENT_WORKSPACE_VIEW_HOVER_BG = JBColor(Color(247, 250, 255), Color(63, 71, 82))
        private val DOCUMENT_WORKSPACE_VIEW_HOVER_BORDER = JBColor(Color(198, 213, 237), Color(96, 108, 125))
        private val DOCUMENT_WORKSPACE_VIEW_HOVER_FG = JBColor(Color(71, 89, 122), Color(192, 202, 216))
        private val DOCUMENT_WORKSPACE_VIEW_DISABLED_FG = JBColor(Color(146, 156, 171), Color(124, 132, 145))
        private const val DOCUMENT_WORKSPACE_VIEW_LABEL_FONT_SIZE = 10.5f
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_FONT_SIZE = 10.5f
        private const val DOCUMENT_WORKSPACE_VIEW_ROW_GAP = 6
        private const val DOCUMENT_WORKSPACE_VIEW_SWITCHER_GAP = 2
        private const val DOCUMENT_WORKSPACE_VIEW_GROUP_INSET = 2
        private const val DOCUMENT_WORKSPACE_VIEW_GROUP_ARC = 11
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_ARC = 10
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_HEIGHT = 22
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_MIN_WIDTH = 52
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_HORIZONTAL_PADDING = 10
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_VERTICAL_PADDING = 2
        private const val DOCUMENT_WORKSPACE_VIEW_BUTTON_EXTRA_WIDTH_PADDING = 16
        private const val LIVE_PROGRESS_EVENT_COALESCE_MILLIS = 180
        private const val WORKSPACE_SECTION_CARD_PADDING = 12
        private val SCROLLABLE_WORKSPACE_SECTION_MAX_HEIGHT = JBUI.scale(320)
        private const val WORKSPACE_SCROLL_UNIT_INCREMENT = 24
        private const val WORKSPACE_SCROLL_BLOCK_INCREMENT = 96
        private const val DOCUMENT_WORKSPACE_CARD_DOCUMENT = "document"
        private const val DOCUMENT_WORKSPACE_CARD_STRUCTURED_TASKS = "structuredTasks"
        private const val DOCUMENT_WORKSPACE_VIEWPORT_HEIGHT = 360
        private val COMPOSER_SOURCE_EDITABLE_STAGES = setOf(
            StageId.REQUIREMENTS,
            StageId.DESIGN,
            StageId.TASKS,
        )
        private const val WORKFLOW_SOURCE_ENTRY_SPEC_COMPOSER = "SPEC_COMPOSER"
        private const val MAX_SOURCE_IMPORT_VALIDATION_LINES = 6
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
