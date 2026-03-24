package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.OperationMode
import com.eacape.speccodingplugin.hook.HookEvent
import com.eacape.speccodingplugin.hook.HookManager
import com.eacape.speccodingplugin.hook.HookTriggerContext
import com.eacape.speccodingplugin.llm.LlmRouter
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Spec 工作流引擎
 * 负责管理 Spec 工作流的状态机和阶段流转
 */
@Service(Service.Level.PROJECT)
class SpecEngine(private val project: Project) {
    private val logger = thisLogger()
    private val workflowIdGenerator = WorkflowIdGenerator()
    private val templateSwitchPreviewIdGenerator = WorkflowIdGenerator(prefix = "preview")
    private val templateSwitchIdGenerator = WorkflowIdGenerator(prefix = "switch")
    private val projectConfigDelegate: SpecProjectConfigService by lazy { SpecProjectConfigService(project) }
    private val artifactServiceDelegate: SpecArtifactService by lazy { SpecArtifactService(project) }
    private val codeContextServiceDelegate: SpecCodeContextService by lazy { SpecCodeContextService(project) }
    private val tasksServiceDelegate: SpecTasksService by lazy { SpecTasksService(project) }
    private val taskExecutionServiceDelegate: SpecTaskExecutionService by lazy { SpecTaskExecutionService(project) }
    private val verificationServiceDelegate: SpecVerificationService by lazy { SpecVerificationService(project) }
    private val gateRuleEngine: SpecGateRuleEngine by lazy {
        SpecGateRuleEngine(
            artifactService = artifactServiceDelegate,
            rules = listOf(
                FixedArtifactNamingRule(),
                RequiredArtifactRule(),
                TasksSyntaxRule(),
                TaskUniqueIdRule(),
                TaskDependencyExistsRule(),
                TaskStateConsistencyRule(),
                VerifyConclusionRule(),
                DocumentValidationRule(),
            ),
        )
    }

    // Overridable by test constructor; lazy to avoid service lookups during construction
    private var _storageOverride: SpecStorage? = null
    private var _generationOverride: (suspend (SpecGenerationRequest) -> SpecGenerationResult)? = null
    private var _clarificationOverride: (suspend (SpecGenerationRequest) -> Result<SpecClarificationDraft>)? = null
    private var _stageGateEvaluatorOverride: SpecStageGateEvaluator? = null

    private val storageDelegate: SpecStorage by lazy { _storageOverride ?: SpecStorage.getInstance(project) }
    private val generationHandler: suspend (SpecGenerationRequest) -> SpecGenerationResult by lazy {
        _generationOverride ?: SpecGenerator(LlmRouter.getInstance())::generate
    }
    private val clarificationHandler: suspend (SpecGenerationRequest) -> Result<SpecClarificationDraft> by lazy {
        _clarificationOverride ?: SpecGenerator(LlmRouter.getInstance())::draftClarification
    }
    private val stageGateEvaluator: SpecStageGateEvaluator by lazy {
        _stageGateEvaluatorOverride ?: SpecStageGateEvaluator { request ->
            evaluateStageGate(request)
        }
    }

    internal constructor(
        project: Project,
        storage: SpecStorage,
        generationHandler: suspend (SpecGenerationRequest) -> SpecGenerationResult,
        clarificationHandler: suspend (SpecGenerationRequest) -> Result<SpecClarificationDraft>,
    ) : this(project) {
        this._storageOverride = storage
        this._generationOverride = generationHandler
        this._clarificationOverride = clarificationHandler
    }

    internal constructor(
        project: Project,
        storage: SpecStorage,
        generationHandler: suspend (SpecGenerationRequest) -> SpecGenerationResult,
        clarificationHandler: suspend (SpecGenerationRequest) -> Result<SpecClarificationDraft>,
        stageGateEvaluator: SpecStageGateEvaluator,
    ) : this(project, storage, generationHandler, clarificationHandler) {
        this._stageGateEvaluatorOverride = stageGateEvaluator
    }

    internal constructor(
        project: Project,
        storage: SpecStorage,
        generationHandler: suspend (SpecGenerationRequest) -> SpecGenerationResult,
    ) : this(
        project = project,
        storage = storage,
        generationHandler = generationHandler,
        clarificationHandler = {
            Result.success(
                SpecClarificationDraft(
                    phase = it.phase,
                    questions = emptyList(),
                    rawContent = "",
                )
            )
        },
    )

    internal constructor(
        project: Project,
        storage: SpecStorage,
        generationHandler: suspend (SpecGenerationRequest) -> SpecGenerationResult,
        stageGateEvaluator: SpecStageGateEvaluator,
    ) : this(
        project = project,
        storage = storage,
        generationHandler = generationHandler,
        clarificationHandler = {
            Result.success(
                SpecClarificationDraft(
                    phase = it.phase,
                    questions = emptyList(),
                    rawContent = "",
                )
            )
        },
        stageGateEvaluator = stageGateEvaluator,
    )

    // 当前活跃的工作流
    private val activeWorkflows = mutableMapOf<String, SpecWorkflow>()
    private val pendingTemplateSwitchPreviews = mutableMapOf<String, TemplateSwitchPreview>()

    /**
     * 创建新的工作流
     */
    fun createWorkflow(
        title: String,
        description: String,
        template: WorkflowTemplate? = null,
        verifyEnabled: Boolean? = null,
        changeIntent: SpecChangeIntent = SpecChangeIntent.FULL,
        baselineWorkflowId: String? = null,
    ): Result<SpecWorkflow> {
        return runCatching {
            val projectConfig = projectConfigDelegate.load()
            val configPin = projectConfigDelegate.createConfigPin(projectConfig)
            val selectedTemplate = template ?: projectConfig.defaultTemplate
            val templatePolicy = projectConfig.policyFor(selectedTemplate)
            logger.debug(
                "Loaded spec project config: schemaVersion=${projectConfig.schemaVersion}, " +
                    "defaultTemplate=${projectConfig.defaultTemplate}, selectedTemplate=$selectedTemplate",
            )
            val normalizedBaselineWorkflowId = baselineWorkflowId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (changeIntent == SpecChangeIntent.INCREMENTAL && normalizedBaselineWorkflowId != null) {
                val baselineId = normalizedBaselineWorkflowId
                val baselineExists = activeWorkflows.containsKey(baselineId) ||
                    storageDelegate.loadWorkflow(baselineId).isSuccess
                require(baselineExists) {
                    "Baseline workflow not found: $baselineId"
                }
            }
            val workflowId = generateWorkflowId()
            val createdAt = System.currentTimeMillis()
            val stageMetadata = initializeStageMetadata(
                templatePolicy = templatePolicy,
                timestampMillis = createdAt,
                verifyEnabled = verifyEnabled,
            )
            val workflow = SpecWorkflow(
                id = workflowId,
                currentPhase = initialPhaseForStage(stageMetadata.currentStage),
                documents = emptyMap(),
                status = WorkflowStatus.IN_PROGRESS,
                title = title,
                description = description,
                changeIntent = changeIntent,
                template = selectedTemplate,
                stageStates = stageMetadata.stageStates,
                currentStage = stageMetadata.currentStage,
                verifyEnabled = stageMetadata.verifyEnabled,
                baselineWorkflowId = if (changeIntent == SpecChangeIntent.INCREMENTAL) {
                    normalizedBaselineWorkflowId
                } else {
                    null
                },
                configPinHash = configPin.hash,
                artifactDraftStates = ArtifactDraftStateSupport.initializeForStageStates(stageMetadata.stageStates),
                createdAt = createdAt,
                updatedAt = createdAt,
            )

            storageDelegate.saveConfigPinSnapshot(workflowId, configPin).getOrThrow()
            storageDelegate.saveWorkflow(workflow).getOrThrow()
            val artifactWrites = artifactServiceDelegate.ensureMissingArtifacts(
                workflowId = workflowId,
                template = selectedTemplate,
                templatePolicy = templatePolicy,
                verifyEnabled = stageMetadata.verifyEnabled,
            )
            activeWorkflows[workflowId] = workflow

            logger.info(
                "Created workflow: $workflowId, template=$selectedTemplate, " +
                    "scaffoldedArtifacts=${artifactWrites.count { it.created }}",
            )
            workflow
        }
    }

    /**
     * 加载工作流
     */
    fun loadWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            // 先从内存缓存查找
            activeWorkflows[workflowId]?.let { cachedWorkflow ->
                val hydratedWorkflow = hydrateLegacyTaskExecutionRuns(cachedWorkflow)
                activeWorkflows[workflowId] = hydratedWorkflow
                return@runCatching hydratedWorkflow
            }

            // 从存储加载
            val workflow = storageDelegate.loadWorkflow(workflowId).getOrThrow()
            val hydratedWorkflow = hydrateLegacyTaskExecutionRuns(workflow)
            activeWorkflows[workflowId] = hydratedWorkflow
            hydratedWorkflow
        }
    }

    /**
     * 强制从存储重载工作流，覆盖内存缓存。
     */
    fun reloadWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = storageDelegate.loadWorkflow(workflowId).getOrThrow()
            val hydratedWorkflow = hydrateLegacyTaskExecutionRuns(workflow)
            activeWorkflows[workflowId] = hydratedWorkflow
            hydratedWorkflow
        }
    }

    /**
     * 列出所有工作流
     */
    fun listWorkflows(): List<String> {
        return storageDelegate.listWorkflows()
    }

    fun listWorkflowMetadata(): List<WorkflowMeta> {
        return storageDelegate.listWorkflowMetadata()
    }

    fun listWorkflowSources(workflowId: String): Result<List<WorkflowSourceAsset>> {
        return storageDelegate.listWorkflowSources(workflowId)
    }

    fun importWorkflowSource(
        workflowId: String,
        importedFromStage: StageId,
        importedFromEntry: String,
        sourcePath: Path,
    ): Result<WorkflowSourceAsset> {
        return storageDelegate.importWorkflowSource(
            workflowId = workflowId,
            importedFromStage = importedFromStage,
            importedFromEntry = importedFromEntry,
            sourcePath = sourcePath,
        )
    }

    fun openWorkflow(workflowId: String): Result<WorkflowSnapshot> {
        return runCatching {
            val snapshot = storageDelegate.openWorkflow(workflowId).getOrThrow()
            val hydratedWorkflow = hydrateLegacyTaskExecutionRuns(snapshot.workflow)
            activeWorkflows[workflowId] = hydratedWorkflow
            snapshot.copy(
                meta = hydratedWorkflow.toWorkflowMeta(),
                workflow = hydratedWorkflow,
                documents = hydratedWorkflow.documents,
            )
        }
    }

    private fun hydrateLegacyTaskExecutionRuns(workflow: SpecWorkflow): SpecWorkflow {
        return taskExecutionServiceDelegate.migrateLegacyInProgressTasks(workflow).workflow
    }

    fun previewTemplateSwitch(
        workflowId: String,
        toTemplate: WorkflowTemplate,
    ): Result<TemplateSwitchPreview> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()
            activeWorkflows[workflowId] = workflow

            val projectConfig = projectConfigDelegate.load()
            val currentStagePlan = resolveStagePlan(workflow, projectConfig)
            val targetPolicy = projectConfig.policyFor(toTemplate)
            val targetStagePlan = targetPolicy.defaultStagePlan()
            val preview = TemplateSwitchPreview(
                previewId = templateSwitchPreviewIdGenerator.nextId(),
                workflowId = workflowId,
                fromTemplate = workflow.template,
                toTemplate = toTemplate,
                currentStage = workflow.currentStage,
                resultingStage = targetStagePlan.resolveCurrentStage(workflow.currentStage),
                addedActiveStages = targetStagePlan.activeStages.filterNot { currentStagePlan.isActive(it) },
                deactivatedStages = currentStagePlan.activeStages.filterNot { targetStagePlan.isActive(it) },
                gateAddedStages = targetStagePlan.gateArtifactStages.filterNot { currentStagePlan.participatesInGate(it) },
                gateRemovedStages = currentStagePlan.gateArtifactStages.filterNot { targetStagePlan.participatesInGate(it) },
                artifactImpacts = artifactServiceDelegate.previewRequiredArtifacts(
                    workflowId = workflowId,
                    template = toTemplate,
                    templatePolicy = targetPolicy,
                ),
            )
            pendingTemplateSwitchPreviews[preview.previewId] = preview
            logger.info(
                "Prepared template migration preview ${preview.previewId} for workflow=$workflowId " +
                    "${workflow.template} -> $toTemplate",
            )
            preview
        }
    }

    fun cloneWorkflowWithTemplate(
        workflowId: String,
        previewId: String,
        title: String? = null,
        description: String? = null,
    ): Result<TemplateCloneResult> {
        return runCatching {
            val preview = pendingTemplateSwitchPreviews[previewId]
                ?: throw MissingTemplateSwitchPreviewError(previewId)
            val sourceWorkflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()
            activeWorkflows[workflowId] = sourceWorkflow

            if (
                preview.workflowId != workflowId ||
                preview.fromTemplate != sourceWorkflow.template ||
                preview.currentStage != sourceWorkflow.currentStage
            ) {
                pendingTemplateSwitchPreviews.remove(previewId)
                throw StaleTemplateSwitchPreviewError(previewId, workflowId)
            }

            val projectConfig = projectConfigDelegate.load()
            val configPin = projectConfigDelegate.createConfigPin(projectConfig)
            val targetPolicy = projectConfig.policyFor(preview.toTemplate)
            val currentImpacts = artifactServiceDelegate.previewRequiredArtifacts(
                workflowId = workflowId,
                template = preview.toTemplate,
                templatePolicy = targetPolicy,
            )
            val blockedImpact = currentImpacts.firstOrNull { impact ->
                impact.strategy == TemplateSwitchArtifactStrategy.BLOCK_SWITCH
            }
            if (blockedImpact != null) {
                throw StageTransitionPolicyError(
                    "Template migration blocked by missing non-scaffoldable artifact ${blockedImpact.fileName} for ${blockedImpact.stageId}",
                )
            }

            val clonedWorkflowId = generateWorkflowId()
            val createdAt = System.currentTimeMillis()
            val targetStagePlan = targetPolicy.defaultStagePlan()
            val targetStage = targetStagePlan.resolveCurrentStage(sourceWorkflow.currentStage)
            val stageMetadata = applyTemplateSwitchMetadata(
                workflow = sourceWorkflow,
                targetStagePlan = targetStagePlan,
                targetStage = targetStage,
                timestampMillis = createdAt,
            )
            val clonedWorkflow = sourceWorkflow.copy(
                id = clonedWorkflowId,
                currentPhase = initialPhaseForStage(stageMetadata.currentStage),
                documents = sourceWorkflow.documents.mapValues { (phase, document) ->
                    document.copy(id = "$clonedWorkflowId-${phase.name.lowercase()}")
                },
                status = WorkflowStatus.IN_PROGRESS,
                title = title?.trim()?.takeIf { it.isNotEmpty() }
                    ?: defaultClonedWorkflowTitle(sourceWorkflow, preview.toTemplate),
                description = description?.trim() ?: sourceWorkflow.description,
                template = preview.toTemplate,
                stageStates = stageMetadata.stageStates,
                currentStage = stageMetadata.currentStage,
                verifyEnabled = stageMetadata.verifyEnabled,
                configPinHash = configPin.hash,
                artifactDraftStates = buildMap {
                    ArtifactDraftStateSupport.initializeForStageStates(stageMetadata.stageStates)
                        .forEach { (stageId, state) -> put(stageId, state) }
                    SpecPhase.entries.forEach { phase ->
                        put(phase.toStageId(), sourceWorkflow.resolveArtifactDraftState(phase))
                    }
                },
                clarificationRetryState = sourceWorkflow.clarificationRetryState,
                createdAt = createdAt,
                updatedAt = createdAt,
            )

            storageDelegate.saveConfigPinSnapshot(clonedWorkflowId, configPin).getOrThrow()

            val copiedArtifacts = artifactServiceDelegate.listWorkflowMarkdownArtifacts(workflowId)
                .map { sourcePath ->
                    val fileName = sourcePath.fileName.toString()
                    val content = Files.readString(sourcePath, Charsets.UTF_8)
                    artifactServiceDelegate.writeArtifact(clonedWorkflowId, fileName, content)
                    fileName
                }
                .distinct()
                .sorted()

            val generatedArtifacts = artifactServiceDelegate.ensureMissingArtifacts(
                workflowId = clonedWorkflowId,
                template = preview.toTemplate,
                templatePolicy = targetPolicy,
            )
                .filter { write -> write.created }
                .map { write -> write.path.fileName.toString() }
                .distinct()
                .sorted()

            storageDelegate.saveWorkflowTransition(
                workflow = clonedWorkflow,
                eventType = SpecAuditEventType.WORKFLOW_CLONED_WITH_TEMPLATE,
                details = buildTemplateCloneAuditDetails(
                    sourceWorkflow = sourceWorkflow,
                    clonedWorkflow = clonedWorkflow,
                    preview = preview,
                    copiedArtifacts = copiedArtifacts,
                    generatedArtifacts = generatedArtifacts,
                ),
            ).getOrThrow()

            storageDelegate.appendAuditEvent(
                workflowId = workflowId,
                eventType = SpecAuditEventType.WORKFLOW_CLONED_WITH_TEMPLATE,
                details = buildSourceWorkflowCloneAuditDetails(
                    sourceWorkflow = sourceWorkflow,
                    clonedWorkflow = clonedWorkflow,
                    preview = preview,
                ),
            ).getOrThrow()

            clearTemplateSwitchPreviews(workflowId)
            val reloadedWorkflow = storageDelegate.loadWorkflow(clonedWorkflowId).getOrThrow()
            activeWorkflows[clonedWorkflowId] = reloadedWorkflow
            logger.info(
                "Workflow $workflowId created migrated copy $clonedWorkflowId: ${preview.fromTemplate} -> ${preview.toTemplate}",
            )
            TemplateCloneResult(
                sourceWorkflowId = workflowId,
                previewId = preview.previewId,
                workflow = reloadedWorkflow,
                copiedArtifacts = copiedArtifacts,
                generatedArtifacts = generatedArtifacts,
            )
        }
    }

    fun applyTemplateSwitch(
        workflowId: String,
        previewId: String,
    ): Result<TemplateSwitchApplyResult> {
        return Result.failure(TemplateMutationLockedError(workflowId))
    }

    fun rollbackTemplateSwitch(
        workflowId: String,
        switchId: String,
    ): Result<TemplateSwitchRollbackResult> {
        return Result.failure(TemplateMutationLockedError(workflowId))
    }

    fun listTemplateSwitchHistory(workflowId: String): Result<List<TemplateSwitchHistoryEntry>> {
        return runCatching {
            val events = storageDelegate.listAuditEvents(workflowId).getOrThrow()
            val rolledBackSwitchIds = events
                .asSequence()
                .filter { event -> event.eventType == SpecAuditEventType.TEMPLATE_SWITCH_ROLLED_BACK }
                .mapNotNull { event ->
                    event.details["switchId"]
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
                .toSet()

            events
                .asSequence()
                .filter { event -> event.eventType == SpecAuditEventType.TEMPLATE_SWITCHED }
                .mapNotNull(::parseTemplateSwitchHistoryEntry)
                .map { entry ->
                    entry.copy(rolledBack = rolledBackSwitchIds.contains(entry.switchId))
                }
                .sortedByDescending(TemplateSwitchHistoryEntry::occurredAtEpochMs)
                .toList()
        }
    }

    fun listDocumentHistory(
        workflowId: String,
        phase: SpecPhase,
    ): List<SpecDocumentHistoryEntry> {
        return storageDelegate.listDocumentHistory(workflowId, phase)
    }

    fun loadDocumentSnapshot(
        workflowId: String,
        phase: SpecPhase,
        snapshotId: String,
    ): Result<SpecDocument> {
        return storageDelegate.loadDocumentSnapshot(workflowId, phase, snapshotId)
    }

    fun deleteDocumentSnapshot(
        workflowId: String,
        phase: SpecPhase,
        snapshotId: String,
    ): Result<Unit> {
        return storageDelegate.deleteDocumentSnapshot(workflowId, phase, snapshotId)
    }

    fun pruneDocumentHistory(
        workflowId: String,
        phase: SpecPhase,
        keepLatest: Int,
    ): Result<Int> {
        return storageDelegate.pruneDocumentHistory(workflowId, phase, keepLatest)
    }

    fun listWorkflowSnapshots(workflowId: String): List<SpecWorkflowSnapshotEntry> {
        return storageDelegate.listWorkflowSnapshots(workflowId)
    }

    fun loadWorkflowSnapshot(
        workflowId: String,
        snapshotId: String,
    ): Result<SpecWorkflow> {
        return storageDelegate.loadWorkflowSnapshot(workflowId, snapshotId)
    }

    fun pinDeltaBaseline(
        workflowId: String,
        snapshotId: String,
        label: String? = null,
    ): Result<SpecDeltaBaselineRef> {
        return storageDelegate.pinDeltaBaseline(workflowId, snapshotId, label)
    }

    fun listDeltaBaselines(workflowId: String): List<SpecDeltaBaselineRef> {
        return storageDelegate.listDeltaBaselines(workflowId)
    }

    fun loadDeltaBaselineWorkflow(
        workflowId: String,
        baselineId: String,
    ): Result<SpecWorkflow> {
        return storageDelegate.loadDeltaBaselineWorkflow(workflowId, baselineId)
    }

    /**
     * 生成当前阶段的文档
     */
    suspend fun generateCurrentPhase(
        workflowId: String,
        input: String,
    ): Flow<SpecGenerationProgress> {
        return generateCurrentPhase(
            workflowId = workflowId,
            input = input,
            options = GenerationOptions(),
        )
    }

    suspend fun draftCurrentPhaseClarification(
        workflowId: String,
        input: String,
        options: GenerationOptions = GenerationOptions(),
    ): Result<SpecClarificationDraft> {
        val workflow = activeWorkflows[workflowId]
            ?: storageDelegate.loadWorkflow(workflowId).getOrElse { return Result.failure(it) }
        activeWorkflows[workflowId] = workflow

        val preparedRequest = prepareGenerationRequest(
            workflow = workflow,
            input = input,
            options = options,
        )
        val effectiveOptions = preparedRequest.options
        val request = preparedRequest.request
        val result = clarificationHandler(request)
        appendWorkflowSourceUsageAudit(
            workflowId = workflowId,
            phase = workflow.currentPhase,
            actionType = AUDIT_ACTION_DRAFT_CLARIFICATION,
            options = effectiveOptions,
            status = if (result.isSuccess) {
                AUDIT_STATUS_SUCCESS
            } else {
                AUDIT_STATUS_FAILED
            },
            extraDetails = buildMap {
                result.getOrNull()?.let { draft ->
                    put("questionCount", draft.questions.size.toString())
                }
                result.exceptionOrNull()?.message
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { errorMessage ->
                        put("error", errorMessage)
                    }
            },
        )
        return result
    }

    suspend fun generateCurrentPhase(
        workflowId: String,
        input: String,
        options: GenerationOptions = GenerationOptions()
    ): Flow<SpecGenerationProgress> = flow {
        val workflow = activeWorkflows[workflowId]
            ?: throw IllegalStateException("Workflow not found: $workflowId")

        emit(SpecGenerationProgress.Started(workflow.currentPhase))

        var effectiveOptions = options
        try {
            // 获取前一阶段的文档（如果有）
            val preparedRequest = prepareGenerationRequest(
                workflow = workflow,
                input = input,
                options = options,
            )
            val currentDocument = preparedRequest.currentDocument
            effectiveOptions = preparedRequest.options
            val auditActionType = auditActionTypeForComposeMode(
                effectiveOptions.composeActionMode ?: ArtifactComposeActionMode.GENERATE,
            )

            // 构建生成请求
            val request = preparedRequest.request

            emit(SpecGenerationProgress.Generating(workflow.currentPhase, 0.3))

            // 生成文档
            val result = generationHandler(request)

            when (result) {
                is SpecGenerationResult.Success -> {
                    emit(SpecGenerationProgress.Generating(workflow.currentPhase, 0.7))

                    // 保存文档
                    val savedDocument = persistGeneratedDocumentWithClarificationWriteback(
                        workflowId = workflowId,
                        phase = workflow.currentPhase,
                        document = result.document,
                        currentDocument = currentDocument,
                        options = effectiveOptions,
                    )

                    // 更新工作流
                    val updatedWorkflow = workflow.copy(
                        documents = workflow.documents + (workflow.currentPhase to savedDocument),
                        artifactDraftStates = workflow.artifactDraftStates + (
                            workflow.currentPhase.toStageId() to ArtifactDraftState.MATERIALIZED
                        ),
                        clarificationRetryState = null,
                        updatedAt = System.currentTimeMillis()
                    )
                    activeWorkflows[workflowId] = updatedWorkflow
                    storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()
                    appendWorkflowSourceUsageAudit(
                        workflowId = workflowId,
                        phase = workflow.currentPhase,
                        actionType = auditActionType,
                        options = effectiveOptions,
                        status = AUDIT_STATUS_SUCCESS,
                    )

                    emit(SpecGenerationProgress.Completed(savedDocument))
                }

                is SpecGenerationResult.ValidationFailed -> {
                    // 保存文档（即使验证失败）
                    val savedDocument = persistGeneratedDocumentWithClarificationWriteback(
                        workflowId = workflowId,
                        phase = workflow.currentPhase,
                        document = result.document,
                        currentDocument = currentDocument,
                        options = effectiveOptions,
                    )
                    val validation = savedDocument.validationResult ?: result.validation

                    val updatedWorkflow = workflow.copy(
                        documents = workflow.documents + (workflow.currentPhase to savedDocument),
                        artifactDraftStates = workflow.artifactDraftStates + (
                            workflow.currentPhase.toStageId() to ArtifactDraftState.MATERIALIZED
                        ),
                        updatedAt = System.currentTimeMillis()
                    )
                    activeWorkflows[workflowId] = updatedWorkflow
                    storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()
                    appendWorkflowSourceUsageAudit(
                        workflowId = workflowId,
                        phase = workflow.currentPhase,
                        actionType = auditActionType,
                        options = effectiveOptions,
                        status = AUDIT_STATUS_VALIDATION_FAILED,
                        extraDetails = mapOf(
                            "validationErrorCount" to validation.errors.size.toString(),
                        ),
                    )

                    emit(SpecGenerationProgress.ValidationFailed(savedDocument, validation))
                }

                is SpecGenerationResult.Failure -> {
                    appendWorkflowSourceUsageAudit(
                        workflowId = workflowId,
                        phase = workflow.currentPhase,
                        actionType = auditActionType,
                        options = effectiveOptions,
                        status = AUDIT_STATUS_FAILED,
                        extraDetails = buildMap {
                            put("error", result.error)
                            result.details
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { details ->
                                    put("details", details)
                                }
                        },
                    )
                    emit(SpecGenerationProgress.Failed(result.error, result.details))
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to generate ${workflow.currentPhase} document", e)
            appendWorkflowSourceUsageAudit(
                workflowId = workflowId,
                phase = workflow.currentPhase,
                actionType = auditActionTypeForComposeMode(
                    effectiveOptions.composeActionMode ?: ArtifactComposeActionMode.GENERATE,
                ),
                options = effectiveOptions,
                status = AUDIT_STATUS_FAILED,
                extraDetails = mapOf(
                    "error" to (e.message ?: "Unknown error"),
                ),
            )
            emit(SpecGenerationProgress.Failed(e.message ?: "Unknown error", e.stackTraceToString()))
        }
    }

    /**
     * 更新指定阶段文档内容（用于 Chat Spec 卡片内编辑）。
     * 可选 expectedRevision 用于并发冲突检测。
     */
    fun updateDocumentContent(
        workflowId: String,
        phase: SpecPhase,
        content: String,
        expectedRevision: Long? = null,
    ): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()

            val existingDocument = workflow.getDocument(phase)
                ?: SpecDocument(
                    id = "$workflowId-${phase.name.lowercase()}",
                    phase = phase,
                    content = "",
                    metadata = SpecMetadata(
                        title = "${phase.displayName} Document",
                        description = "Manually edited ${phase.displayName} document",
                    ),
                    validationResult = null,
                )

            expectedRevision?.let { expected ->
                val actual = existingDocument.metadata.updatedAt
                if (actual != expected) {
                    throw DocumentRevisionConflictException(
                        workflowId = workflowId,
                        phase = phase,
                        expectedRevision = expected,
                        actualRevision = actual,
                    )
                }
            }

            val normalizedContent = content
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
            require(normalizedContent.isNotBlank()) { "Document content cannot be blank" }

            val now = System.currentTimeMillis()
            val draftDocument = existingDocument.copy(
                content = normalizedContent,
                metadata = existingDocument.metadata.copy(updatedAt = now),
            )
            val validation = SpecValidator.validate(draftDocument)
            val updatedDocument = draftDocument.copy(validationResult = validation)

            storageDelegate.saveDocument(workflowId, updatedDocument).getOrThrow()

            val updatedWorkflow = workflow.copy(
                documents = workflow.documents + (phase to updatedDocument),
                artifactDraftStates = workflow.artifactDraftStates + (phase.toStageId() to ArtifactDraftState.MATERIALIZED),
                updatedAt = now,
            )
            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId updated ${phase.displayName} document")
            updatedWorkflow
        }
    }

    fun updateWorkflowMetadata(
        workflowId: String,
        title: String,
        description: String,
    ): Result<SpecWorkflow> {
        return runCatching {
            val normalizedTitle = title
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\n', ' ')
                .trim()
            require(normalizedTitle.isNotBlank()) { "Workflow title cannot be blank" }

            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()

            val now = System.currentTimeMillis()
            val updatedWorkflow = workflow.copy(
                title = normalizedTitle,
                description = description
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim(),
                updatedAt = now,
            )
            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId updated metadata")
            updatedWorkflow
        }
    }

    fun saveClarificationRetryState(
        workflowId: String,
        state: ClarificationRetryState?,
    ): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()
            val normalizedState = state?.normalize()
            if (workflow.clarificationRetryState == normalizedState) {
                activeWorkflows[workflowId] = workflow
                return@runCatching workflow
            }
            val updatedWorkflow = workflow.copy(
                clarificationRetryState = normalizedState,
            )
            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()
            updatedWorkflow
        }
    }

    private fun ClarificationRetryState.normalize(): ClarificationRetryState? {
        val normalizedInput = input
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        val normalizedContext = confirmedContext
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        val normalizedQuestions = questionsMarkdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        val normalizedStructuredQuestions = structuredQuestions
            .map {
                it.replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim()
            }
            .filter { it.isNotBlank() }
            .distinct()
        val normalizedRepairSections = requirementsRepairSections.distinct()
        val normalizedFollowUp = when {
            followUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR && normalizedRepairSections.isNotEmpty() ->
                ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR

            else -> ClarificationFollowUp.GENERATION
        }
        val normalizedLastError = lastError
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (normalizedInput.isBlank() &&
            normalizedContext.isBlank() &&
            normalizedQuestions.isBlank() &&
            normalizedStructuredQuestions.isEmpty()
        ) {
            return null
        }
        return copy(
            input = normalizedInput,
            confirmedContext = normalizedContext,
            questionsMarkdown = normalizedQuestions,
            structuredQuestions = normalizedStructuredQuestions,
            clarificationRound = clarificationRound.coerceAtLeast(1),
            lastError = normalizedLastError,
            followUp = normalizedFollowUp,
            requirementsRepairSections = if (normalizedFollowUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR) {
                normalizedRepairSections
            } else {
                emptyList()
            },
        )
    }

    private fun persistGeneratedDocumentWithClarificationWriteback(
        workflowId: String,
        phase: SpecPhase,
        document: SpecDocument,
        currentDocument: SpecDocument?,
        options: GenerationOptions,
    ): SpecDocument {
        var updatedDocument = document
        val payload = options.clarificationWriteback
        var clarificationResult: SpecClarificationWritebackResult? = null

        if (payload != null) {
            val writeback = SpecClarificationWriteback.apply(
                phase = phase,
                existingContent = updatedDocument.content,
                payload = payload,
                atMillis = System.currentTimeMillis(),
            )
            if (writeback != null && writeback.content != updatedDocument.content) {
                updatedDocument = revalidateDocumentContent(
                    document = updatedDocument,
                    content = writeback.content,
                )
                clarificationResult = writeback
            }
        }

        val citationWriteback = SpecArtifactSourceCitationWriteback.apply(
            phase = phase,
            existingContent = updatedDocument.content,
            citations = mergeArtifactSourceCitations(
                currentDocument = currentDocument,
                generatedCitations = buildArtifactSourceCitations(workflowId, options),
            ),
        )
        if (citationWriteback != null && citationWriteback.content != updatedDocument.content) {
            updatedDocument = revalidateDocumentContent(
                document = updatedDocument,
                content = citationWriteback.content,
            )
        }

        storageDelegate.saveDocument(workflowId, updatedDocument).getOrThrow()
        if (payload != null && clarificationResult != null) {
            appendClarificationWritebackAudit(
                workflowId = workflowId,
                phase = phase,
                payload = payload,
                result = clarificationResult,
            )
        }
        return updatedDocument
    }

    private fun mergeArtifactSourceCitations(
        currentDocument: SpecDocument?,
        generatedCitations: List<ArtifactSourceCitation>,
    ): List<ArtifactSourceCitation> {
        val existingCitations = currentDocument
            ?.content
            ?.let(SpecArtifactSourceCitationWriteback::extract)
            .orEmpty()
        if (existingCitations.isEmpty()) {
            return generatedCitations
        }
        if (generatedCitations.isEmpty()) {
            return existingCitations
        }
        return (generatedCitations + existingCitations)
            .distinctBy(ArtifactSourceCitation::sourceId)
    }

    private fun appendClarificationWritebackAudit(
        workflowId: String,
        phase: SpecPhase,
        payload: ConfirmedClarificationPayload,
        result: SpecClarificationWritebackResult,
    ) {
        storageDelegate.appendAuditEvent(
            workflowId = workflowId,
            eventType = SpecAuditEventType.CLARIFICATION_CONFIRMED,
            details = buildMap {
                put("phase", phase.name)
                put("file", phase.outputFileName)
                put("section", result.sectionTitle)
                put("clarificationRound", payload.clarificationRound.toString())
                put("structuredQuestionCount", payload.structuredQuestions.size.toString())
                put("confirmedCount", result.confirmedCount.toString())
                put("notApplicableCount", result.notApplicableCount.toString())
                if (payload.questionsMarkdown.isNotBlank()) {
                    put("questionsMarkdown", payload.questionsMarkdown)
                }
                put("confirmedContext", payload.confirmedContext)
                put("summary", result.summary)
            },
        ).getOrThrow()
    }

    private fun buildArtifactSourceCitations(
        workflowId: String,
        options: GenerationOptions,
    ): List<ArtifactSourceCitation> {
        val consumedSourceIds = options.workflowSourceUsage.consumedSourceIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (consumedSourceIds.isEmpty()) {
            return emptyList()
        }

        val assets = storageDelegate.listWorkflowSources(workflowId)
            .getOrElse { error ->
                logger.warn("Failed to list workflow sources for artifact citation writeback: $workflowId", error)
                return emptyList()
            }
        val assetsById = assets.associateBy(WorkflowSourceAsset::sourceId)
        return consumedSourceIds.mapNotNull { sourceId ->
            assetsById[sourceId]?.toArtifactSourceCitation()
        }
    }

    private fun WorkflowSourceAsset.toArtifactSourceCitation(): ArtifactSourceCitation {
        return ArtifactSourceCitation(
            sourceId = sourceId,
            storedRelativePath = storedRelativePath,
            note = originalFileName,
        )
    }

    private fun revalidateDocumentContent(
        document: SpecDocument,
        content: String,
    ): SpecDocument {
        val candidate = document.copy(
            content = content,
            metadata = document.metadata.copy(updatedAt = System.currentTimeMillis()),
        )
        return candidate.copy(validationResult = SpecValidator.validate(candidate))
    }

    fun advanceWorkflow(
        workflowId: String,
        confirmWarnings: ((GateResult) -> Boolean)? = null,
    ): Result<StageTransitionResult> {
        return runCatching {
            val prepared = prepareStageTransition(
                workflowId = workflowId,
                transitionType = StageTransitionType.ADVANCE,
            )
            performStageTransition(
                workflow = prepared.workflow,
                stagePlan = prepared.stagePlan,
                gatePolicy = prepared.gatePolicy,
                transitionType = StageTransitionType.ADVANCE,
                targetStage = prepared.targetStage,
                evaluatedStages = prepared.evaluatedStages,
                confirmWarnings = confirmWarnings,
            )
        }
    }

    fun jumpToStage(
        workflowId: String,
        targetStage: StageId,
        confirmWarnings: ((GateResult) -> Boolean)? = null,
    ): Result<StageTransitionResult> {
        return runCatching {
            val prepared = prepareStageTransition(
                workflowId = workflowId,
                transitionType = StageTransitionType.JUMP,
                requestedTargetStage = targetStage,
            )
            performStageTransition(
                workflow = prepared.workflow,
                stagePlan = prepared.stagePlan,
                gatePolicy = prepared.gatePolicy,
                transitionType = StageTransitionType.JUMP,
                targetStage = prepared.targetStage,
                evaluatedStages = prepared.evaluatedStages,
                confirmWarnings = confirmWarnings,
            )
        }
    }

    fun rollbackToStage(
        workflowId: String,
        targetStage: StageId,
    ): Result<WorkflowMeta> {
        return runCatching {
            val prepared = prepareStageTransition(
                workflowId = workflowId,
                transitionType = StageTransitionType.ROLLBACK,
                requestedTargetStage = targetStage,
            )

            if (prepared.targetStage == prepared.workflow.currentStage) {
                activeWorkflows[workflowId] = prepared.workflow
                return@runCatching prepared.workflow.toWorkflowMeta()
            }

            performStageTransition(
                workflow = prepared.workflow,
                stagePlan = prepared.stagePlan,
                gatePolicy = prepared.gatePolicy,
                transitionType = StageTransitionType.ROLLBACK,
                targetStage = prepared.targetStage,
                evaluatedStages = prepared.evaluatedStages,
                confirmWarnings = null,
            ).workflow.toWorkflowMeta()
        }
    }

    /**
     * 进入下一阶段
     */
    fun proceedToNextPhase(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()
            val currentDocument = workflow.getCurrentDocument()
                ?: throw IllegalStateException(
                    "Cannot proceed to next phase. Current phase document is missing. " +
                        "Run /workflow generate <input> first.",
                )
            val currentValidation = SpecValidator.validate(currentDocument)
            if (!currentValidation.valid) {
                val detail = currentValidation.errors
                    .take(3)
                    .joinToString("；")
                    .ifBlank { "unknown validation error" }
                throw IllegalStateException(
                    "Cannot proceed to next phase. Current phase validation failed: $detail",
                )
            }
            advanceWorkflow(workflowId).getOrThrow().workflow
        }
    }

    /**
     * 返回上一阶段
     */
    fun goBackToPreviousPhase(workflowId: String): Result<SpecWorkflow> {
        return Result.failure(ManualStageMutationLockedError(workflowId, StageTransitionType.ROLLBACK))
    }

    /**
     * 完成工作流
     */
    fun previewStageTransition(
        workflowId: String,
        transitionType: StageTransitionType,
        targetStage: StageId? = null,
    ): Result<StageTransitionGatePreview> {
        return runCatching {
            val prepared = prepareStageTransition(
                workflowId = workflowId,
                transitionType = transitionType,
                requestedTargetStage = targetStage,
            )
            val gateResult = if (transitionType == StageTransitionType.ROLLBACK) {
                GateResult.fromViolations(emptyList())
            } else {
                evaluateTransitionGate(
                    buildStageTransitionRequest(
                        workflow = prepared.workflow,
                        transitionType = transitionType,
                        targetStage = prepared.targetStage,
                        evaluatedStages = prepared.evaluatedStages,
                        stagePlan = prepared.stagePlan,
                    ),
                )
            }
            StageTransitionGatePreview(
                workflowId = prepared.workflow.id,
                transitionType = transitionType,
                fromStage = prepared.workflow.currentStage,
                targetStage = prepared.targetStage,
                evaluatedStages = prepared.evaluatedStages,
                gateResult = gateResult,
            )
        }
    }

    fun completeWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: throw IllegalStateException("Workflow not found: $workflowId")

            if (workflow.currentPhase != SpecPhase.IMPLEMENT) {
                throw IllegalStateException("Cannot complete workflow. Must be at Implement phase.")
            }

            val implementDoc = workflow.getDocument(SpecPhase.IMPLEMENT)
                ?: throw IllegalStateException("Implement phase document not found")

            val implementReadiness = evaluateImplementStageReadiness(
                tasksDocument = implementDoc,
                tasks = resolveImplementationTasks(workflow, implementDoc),
            )
            val blockers = implementCompletionBlockers(implementReadiness)
            if (blockers.isNotEmpty()) {
                throw IllegalStateException(
                    "Cannot complete workflow. ${blockers.joinToString(" ")}",
                )
            }

            val completedAt = System.currentTimeMillis()
            val completedStageStates = markCurrentStageCompleted(
                workflow = workflow,
                timestampMillis = completedAt,
            )
            val updatedWorkflow = workflow.copy(
                status = WorkflowStatus.COMPLETED,
                stageStates = completedStageStates,
                clarificationRetryState = null,
                updatedAt = completedAt
            )

            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId completed")
            updatedWorkflow
        }
    }

    /**
     * 暂停工作流
     */
    fun pauseWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: throw IllegalStateException("Workflow not found: $workflowId")

            val updatedWorkflow = workflow.copy(
                status = WorkflowStatus.PAUSED,
                updatedAt = System.currentTimeMillis()
            )

            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId paused")
            updatedWorkflow
        }
    }

    /**
     * 恢复工作流
     */
    fun resumeWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: throw IllegalStateException("Workflow not found: $workflowId")

            if (workflow.status != WorkflowStatus.PAUSED) {
                throw IllegalStateException("Workflow is not paused")
            }

            val updatedWorkflow = workflow.copy(
                status = WorkflowStatus.IN_PROGRESS,
                updatedAt = System.currentTimeMillis()
            )

            activeWorkflows[workflowId] = updatedWorkflow
            storageDelegate.saveWorkflow(updatedWorkflow).getOrThrow()

            logger.info("Workflow $workflowId resumed")
            updatedWorkflow
        }
    }

    /**
     * 删除工作流
     */
    fun deleteWorkflow(workflowId: String): Result<Unit> {
        return runCatching {
            activeWorkflows.remove(workflowId)
            storageDelegate.deleteWorkflow(workflowId).getOrThrow()
            logger.info("Workflow $workflowId deleted")
        }
    }

    fun archiveWorkflow(workflowId: String): Result<SpecArchiveResult> {
        return runCatching {
            val workflow = activeWorkflows[workflowId]
                ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()

            if (workflow.status != WorkflowStatus.COMPLETED) {
                throw IllegalStateException("Only completed workflow can be archived")
            }

            val result = storageDelegate.archiveWorkflow(workflow).getOrThrow()
            activeWorkflows.remove(workflowId)
            logger.info("Workflow $workflowId archived to ${result.archivePath}")
            result
        }
    }

    private fun resolveStagePlan(
        workflow: SpecWorkflow,
        projectConfig: SpecProjectConfig,
    ): WorkflowStagePlan {
        val templatePolicy = projectConfig.policyFor(workflow.template)
        return if (workflow.stageStates.isNotEmpty()) {
            templatePolicy.definition.buildStagePlan(
                StageActivationOptions.fromStageStates(workflow.stageStates),
            )
        } else {
            templatePolicy.defaultStagePlan()
        }
    }

    private fun performStageTransition(
        workflow: SpecWorkflow,
        stagePlan: WorkflowStagePlan,
        gatePolicy: SpecGatePolicy,
        transitionType: StageTransitionType,
        targetStage: StageId,
        evaluatedStages: List<StageId>,
        confirmWarnings: ((GateResult) -> Boolean)?,
    ): StageTransitionResult {
        if (!stagePlan.isActive(workflow.currentStage)) {
            throw InactiveWorkflowStageError(workflow.currentStage)
        }
        if (!stagePlan.isActive(targetStage)) {
            throw InactiveWorkflowStageError(targetStage)
        }

        val gateResult = if (transitionType == StageTransitionType.ROLLBACK) {
            GateResult.fromViolations(emptyList())
        } else {
            evaluateTransitionGate(
                buildStageTransitionRequest(
                    workflow = workflow,
                    transitionType = transitionType,
                    targetStage = targetStage,
                    evaluatedStages = evaluatedStages,
                    stagePlan = stagePlan,
                ),
            )
        }
        recordGateRuleDowngradeAudit(
            workflow = workflow,
            transitionType = transitionType,
            targetStage = targetStage,
            evaluatedStages = evaluatedStages,
            gateResult = gateResult,
        )
        val warningConfirmed = resolveWarningDecision(
            gatePolicy = gatePolicy,
            gateResult = gateResult,
            fromStage = workflow.currentStage,
            targetStage = targetStage,
            confirmWarnings = confirmWarnings,
        )
        if (warningConfirmed) {
            recordGateWarningConfirmationAudit(
                workflow = workflow,
                transitionType = transitionType,
                targetStage = targetStage,
                evaluatedStages = evaluatedStages,
                gateResult = gateResult,
            )
        }

        val updatedAt = System.currentTimeMillis()
        val stageMetadata = applyStageTransitionMetadata(
            workflow = workflow,
            stagePlan = stagePlan,
            targetStage = targetStage,
            timestampMillis = updatedAt,
        )
        val updatedWorkflow = workflow.copy(
            currentPhase = initialPhaseForStage(stageMetadata.currentStage),
            stageStates = stageMetadata.stageStates,
            currentStage = stageMetadata.currentStage,
            verifyEnabled = stageMetadata.verifyEnabled,
            clarificationRetryState = null,
            updatedAt = updatedAt,
        )

        val saveResult = storageDelegate.saveWorkflowTransition(
            workflow = updatedWorkflow,
            eventType = transitionAuditEventType(transitionType),
            details = buildStageTransitionAuditDetails(
                workflow = workflow,
                transitionType = transitionType,
                targetStage = targetStage,
                gateResult = gateResult,
                evaluatedStages = evaluatedStages,
                warningConfirmed = warningConfirmed,
            ),
        ).getOrThrow()
        activeWorkflows[workflow.id] = updatedWorkflow

        emitSpecStageChangedHook(
            workflowId = workflow.id,
            previousPhase = workflow.currentPhase,
            currentPhase = updatedWorkflow.currentPhase,
            previousStage = workflow.currentStage,
            currentStage = updatedWorkflow.currentStage,
        )
        logger.info(
            "Workflow ${workflow.id} transitioned ${transitionType.name.lowercase()} " +
                "${workflow.currentStage} -> ${updatedWorkflow.currentStage} (gate=${gateResult.status})",
        )
        return StageTransitionResult(
            workflow = updatedWorkflow,
            transitionType = transitionType,
            fromStage = workflow.currentStage,
            targetStage = updatedWorkflow.currentStage,
            gateResult = gateResult,
            warningConfirmed = warningConfirmed,
            beforeSnapshotId = saveResult.beforeSnapshotId,
            afterSnapshotId = saveResult.afterSnapshotId,
        )
    }

    private fun applyStageTransitionMetadata(
        workflow: SpecWorkflow,
        stagePlan: WorkflowStagePlan,
        targetStage: StageId,
        timestampMillis: Long,
    ): StageMetadataState {
        val timestamp = Instant.ofEpochMilli(timestampMillis).toString()
        val completedStages = stagePlan.activeStagesBefore(targetStage).toSet()
        val updatedStates = linkedMapOf<StageId, StageState>()

        StageId.entries.forEach { stageId ->
            val previous = workflow.stageStates[stageId] ?: StageState(
                active = stagePlan.isActive(stageId),
                status = StageProgress.NOT_STARTED,
            )
            updatedStates[stageId] = when {
                !stagePlan.isActive(stageId) -> StageState(
                    active = false,
                    status = StageProgress.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                )

                stageId == targetStage -> previous.copy(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                    enteredAt = timestamp,
                    completedAt = null,
                )

                completedStages.contains(stageId) -> previous.copy(
                    active = true,
                    status = StageProgress.DONE,
                    enteredAt = previous.enteredAt ?: timestamp,
                    completedAt = previous.completedAt ?: timestamp,
                )

                else -> StageState(
                    active = true,
                    status = StageProgress.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                )
            }
        }

        return StageMetadataState(
            currentStage = targetStage,
            verifyEnabled = stagePlan.isActive(StageId.VERIFY),
            stageStates = updatedStates,
        )
    }

    private fun applyTemplateSwitchMetadata(
        workflow: SpecWorkflow,
        targetStagePlan: WorkflowStagePlan,
        targetStage: StageId,
        timestampMillis: Long,
    ): StageMetadataState {
        val timestamp = Instant.ofEpochMilli(timestampMillis).toString()
        val updatedStates = linkedMapOf<StageId, StageState>()

        StageId.entries.forEach { stageId ->
            val previous = workflow.stageStates[stageId] ?: StageState(
                active = false,
                status = StageProgress.NOT_STARTED,
            )
            updatedStates[stageId] = when {
                !targetStagePlan.isActive(stageId) -> StageState(
                    active = false,
                    status = StageProgress.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                )

                stageId == targetStage -> previous.copy(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                    enteredAt = previous.enteredAt ?: timestamp,
                    completedAt = null,
                )

                previous.status == StageProgress.DONE -> previous.copy(active = true)

                else -> StageState(
                    active = true,
                    status = StageProgress.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                )
            }
        }

        return StageMetadataState(
            currentStage = targetStage,
            verifyEnabled = targetStagePlan.isActive(StageId.VERIFY),
            stageStates = updatedStates,
        )
    }

    private fun resolveWarningDecision(
        gatePolicy: SpecGatePolicy,
        gateResult: GateResult,
        fromStage: StageId,
        targetStage: StageId,
        confirmWarnings: ((GateResult) -> Boolean)?,
    ): Boolean {
        return when (gateResult.status) {
            GateStatus.ERROR -> throw StageTransitionBlockedByGateError(fromStage, targetStage, gateResult)
            GateStatus.WARNING -> {
                if (!gatePolicy.allowWarningAdvance) {
                    throw StageTransitionBlockedByGateError(fromStage, targetStage, gateResult)
                }
                if (!gatePolicy.requireWarningConfirmation) {
                    false
                } else {
                    val confirmed = confirmWarnings?.invoke(gateResult) == true
                    if (!confirmed) {
                        throw StageWarningConfirmationRequiredError(fromStage, targetStage, gateResult)
                    }
                    true
                }
            }

            GateStatus.PASS -> false
        }
    }

    private fun buildStageTransitionAuditDetails(
        workflow: SpecWorkflow,
        transitionType: StageTransitionType,
        targetStage: StageId,
        gateResult: GateResult,
        evaluatedStages: List<StageId>,
        warningConfirmed: Boolean,
    ): Map<String, String> {
        val details = linkedMapOf(
            "fromStage" to workflow.currentStage.name,
            "toStage" to targetStage.name,
            "transitionType" to transitionType.name,
            "gateStatus" to gateResult.status.name,
            "warningConfirmed" to warningConfirmed.toString(),
            "gateSummary" to gateResult.aggregation.summary,
            "warningCount" to gateResult.aggregation.warningCount.toString(),
            "errorCount" to gateResult.aggregation.errorCount.toString(),
        )
        if (evaluatedStages.isNotEmpty()) {
            details["evaluatedStages"] = evaluatedStages.joinToString(",") { stage -> stage.name }
        }
        return details
    }

    private fun recordGateRuleDowngradeAudit(
        workflow: SpecWorkflow,
        transitionType: StageTransitionType,
        targetStage: StageId,
        evaluatedStages: List<StageId>,
        gateResult: GateResult,
    ) {
        val downgradedViolations = gateResult.ruleResults
            .flatMap { evaluation ->
                evaluation.violations
                    .filter { violation ->
                        val originalSeverity = violation.originalSeverity ?: return@filter false
                        violation.severity.ordinal < originalSeverity.ordinal
                    }
                    .map { violation -> evaluation.ruleId to violation }
            }
        if (downgradedViolations.isEmpty()) {
            return
        }

        val details = linkedMapOf(
            "fromStage" to workflow.currentStage.name,
            "toStage" to targetStage.name,
            "transitionType" to transitionType.name,
            "gateStatus" to gateResult.status.name,
            "downgradeCount" to downgradedViolations.size.toString(),
            "downgradedRules" to downgradedViolations
                .map { (ruleId, violation) ->
                    val originalSeverity = violation.originalSeverity ?: violation.severity
                    "$ruleId:${originalSeverity.name}->${violation.severity.name}"
                }
                .distinct()
                .sorted()
                .joinToString(","),
            "downgradedViolations" to downgradedViolations
                .map { (ruleId, violation) ->
                    val originalSeverity = violation.originalSeverity ?: violation.severity
                    "$ruleId@${violation.fileName}:${violation.line}:${originalSeverity.name}->${violation.severity.name}"
                }
                .sorted()
                .joinToString(";"),
        )
        if (evaluatedStages.isNotEmpty()) {
            details["evaluatedStages"] = evaluatedStages.joinToString(",") { stage -> stage.name }
        }
        storageDelegate.appendAuditEvent(
            workflowId = workflow.id,
            eventType = SpecAuditEventType.GATE_RULE_DOWNGRADED,
            details = details,
        ).getOrThrow()
    }

    private fun recordGateWarningConfirmationAudit(
        workflow: SpecWorkflow,
        transitionType: StageTransitionType,
        targetStage: StageId,
        evaluatedStages: List<StageId>,
        gateResult: GateResult,
    ) {
        val confirmation = gateResult.warningConfirmation ?: return
        val warnings = confirmation.warnings
        if (warnings.isEmpty()) {
            return
        }

        val details = linkedMapOf(
            "fromStage" to workflow.currentStage.name,
            "toStage" to targetStage.name,
            "transitionType" to transitionType.name,
            "gateStatus" to gateResult.status.name,
            "warningCount" to gateResult.aggregation.warningCount.toString(),
            "warningRuleCount" to gateResult.aggregation.warningRuleCount.toString(),
            "gateSummary" to gateResult.aggregation.summary,
            "warningRules" to warnings.map(Violation::ruleId).distinct().sorted().joinToString(","),
            "warningViolations" to warnings
                .map { violation -> "${violation.ruleId}@${violation.fileName}:${violation.line}" }
                .sorted()
                .joinToString(";"),
        )
        if (evaluatedStages.isNotEmpty()) {
            details["evaluatedStages"] = evaluatedStages.joinToString(",") { stage -> stage.name }
        }
        storageDelegate.appendAuditEvent(
            workflowId = workflow.id,
            eventType = SpecAuditEventType.GATE_WARNING_CONFIRMED,
            details = details,
        ).getOrThrow()
    }

    private fun buildTemplateSwitchAuditDetails(
        preview: TemplateSwitchPreview,
        switchId: String,
        generatedArtifacts: List<String>,
    ): Map<String, String> {
        val details = linkedMapOf(
            "switchId" to switchId,
            "previewId" to preview.previewId,
            "fromTemplate" to preview.fromTemplate.name,
            "toTemplate" to preview.toTemplate.name,
            "fromStage" to preview.currentStage.name,
            "toStage" to preview.resultingStage.name,
            "currentStageChanged" to preview.currentStageChanged.toString(),
        )
        if (preview.addedActiveStages.isNotEmpty()) {
            details["addedActiveStages"] = preview.addedActiveStages.joinToString(",") { stage -> stage.name }
        }
        if (preview.deactivatedStages.isNotEmpty()) {
            details["deactivatedStages"] = preview.deactivatedStages.joinToString(",") { stage -> stage.name }
        }
        if (preview.gateAddedStages.isNotEmpty()) {
            details["gateAddedStages"] = preview.gateAddedStages.joinToString(",") { stage -> stage.name }
        }
        if (preview.gateRemovedStages.isNotEmpty()) {
            details["gateRemovedStages"] = preview.gateRemovedStages.joinToString(",") { stage -> stage.name }
        }
        if (generatedArtifacts.isNotEmpty()) {
            details["generatedArtifacts"] = generatedArtifacts.joinToString(",")
        }
        return details
    }

    private fun buildTemplateCloneAuditDetails(
        sourceWorkflow: SpecWorkflow,
        clonedWorkflow: SpecWorkflow,
        preview: TemplateSwitchPreview,
        copiedArtifacts: List<String>,
        generatedArtifacts: List<String>,
    ): Map<String, String> {
        val details = linkedMapOf(
            "cloneRole" to "TARGET",
            "sourceWorkflowId" to sourceWorkflow.id,
            "sourceTemplate" to sourceWorkflow.template.name,
            "targetTemplate" to clonedWorkflow.template.name,
            "previewId" to preview.previewId,
            "fromStage" to sourceWorkflow.currentStage.name,
            "toStage" to clonedWorkflow.currentStage.name,
            "currentStageChanged" to preview.currentStageChanged.toString(),
        )
        if (copiedArtifacts.isNotEmpty()) {
            details["copiedArtifacts"] = copiedArtifacts.joinToString(",")
        }
        if (generatedArtifacts.isNotEmpty()) {
            details["generatedArtifacts"] = generatedArtifacts.joinToString(",")
        }
        if (preview.addedActiveStages.isNotEmpty()) {
            details["addedActiveStages"] = preview.addedActiveStages.joinToString(",") { stage -> stage.name }
        }
        if (preview.deactivatedStages.isNotEmpty()) {
            details["deactivatedStages"] = preview.deactivatedStages.joinToString(",") { stage -> stage.name }
        }
        return details
    }

    private fun buildSourceWorkflowCloneAuditDetails(
        sourceWorkflow: SpecWorkflow,
        clonedWorkflow: SpecWorkflow,
        preview: TemplateSwitchPreview,
    ): Map<String, String> {
        return linkedMapOf(
            "cloneRole" to "SOURCE",
            "targetWorkflowId" to clonedWorkflow.id,
            "sourceTemplate" to sourceWorkflow.template.name,
            "targetTemplate" to clonedWorkflow.template.name,
            "previewId" to preview.previewId,
            "fromStage" to sourceWorkflow.currentStage.name,
            "toStage" to clonedWorkflow.currentStage.name,
            "currentStageChanged" to preview.currentStageChanged.toString(),
        )
    }

    private fun buildTemplateSwitchRollbackAuditDetails(
        currentWorkflow: SpecWorkflow,
        restoredWorkflow: SpecWorkflow,
        switchId: String,
        rollbackSnapshotId: String,
        switchEvent: SpecAuditEvent,
    ): Map<String, String> {
        val details = linkedMapOf(
            "switchId" to switchId,
            "restoredFromSnapshotId" to rollbackSnapshotId,
            "fromTemplate" to currentWorkflow.template.name,
            "toTemplate" to restoredWorkflow.template.name,
            "fromStage" to currentWorkflow.currentStage.name,
            "toStage" to restoredWorkflow.currentStage.name,
            "switchEventId" to switchEvent.eventId,
        )
        switchEvent.details["previewId"]?.let { previewId ->
            details["previewId"] = previewId
        }
        return details
    }

    private fun parseTemplateSwitchHistoryEntry(event: SpecAuditEvent): TemplateSwitchHistoryEntry? {
        val switchId = event.details["switchId"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val fromTemplate = parseTemplateSwitchHistoryTemplate(event.details["fromTemplate"]) ?: return null
        val toTemplate = parseTemplateSwitchHistoryTemplate(event.details["toTemplate"]) ?: return null
        return TemplateSwitchHistoryEntry(
            switchId = switchId,
            previewId = event.details["previewId"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
            fromTemplate = fromTemplate,
            toTemplate = toTemplate,
            occurredAt = event.occurredAt,
            occurredAtEpochMs = event.occurredAtEpochMs,
            beforeSnapshotId = event.details["beforeSnapshotId"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
            generatedArtifacts = parseTemplateSwitchHistoryList(event.details["generatedArtifacts"]),
        )
    }

    private fun parseTemplateSwitchHistoryTemplate(raw: String?): WorkflowTemplate? {
        val normalized = raw
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return WorkflowTemplate.entries.firstOrNull { template -> template.name == normalized }
    }

    private fun parseTemplateSwitchHistoryList(raw: String?): List<String> {
        return raw
            ?.split(',')
            ?.mapNotNull { item ->
                item.trim()
                    .takeIf { it.isNotEmpty() }
            }
            ?.distinct()
            ?: emptyList()
    }

    private fun clearTemplateSwitchPreviews(workflowId: String) {
        pendingTemplateSwitchPreviews.entries.removeIf { (_, preview) ->
            preview.workflowId == workflowId
        }
    }

    private fun defaultClonedWorkflowTitle(
        sourceWorkflow: SpecWorkflow,
        targetTemplate: WorkflowTemplate,
    ): String {
        val baseTitle = sourceWorkflow.title.ifBlank { sourceWorkflow.id }
        return "$baseTitle (${targetTemplate.name})"
    }

    private fun transitionAuditEventType(transitionType: StageTransitionType): SpecAuditEventType {
        return when (transitionType) {
            StageTransitionType.ADVANCE -> SpecAuditEventType.STAGE_ADVANCED
            StageTransitionType.JUMP -> SpecAuditEventType.STAGE_JUMPED
            StageTransitionType.ROLLBACK -> SpecAuditEventType.STAGE_ROLLED_BACK
        }
    }

    private fun evaluateStageGate(request: StageTransitionRequest): GateResult {
        return gateRuleEngine.evaluate(
            request = request,
            projectConfig = projectConfigDelegate.load(),
        )
    }

    private fun evaluateTransitionGate(request: StageTransitionRequest): GateResult {
        val gateResult = stageGateEvaluator.evaluate(request)
        if (request.transitionType != StageTransitionType.ADVANCE) {
            return gateResult
        }

        val completionViolations = buildAdvanceCompletionViolations(request)
        if (completionViolations.isEmpty()) {
            return gateResult
        }

        val completionRule = RuleEvaluationResult(
            ruleId = STAGE_COMPLETION_RULE_ID,
            description = "Stage completion checks",
            enabled = true,
            appliedStages = listOf(request.fromStage),
            defaultSeverity = GateStatus.ERROR,
            effectiveSeverity = GateStatus.ERROR,
            severityOverridden = false,
            summary = "Completion checks are incomplete for ${request.fromStage.name}.",
            violations = completionViolations,
        )
        return GateResult.fromViolations(
            violations = gateResult.violations + completionViolations,
            ruleResults = gateResult.ruleResults + completionRule,
        )
    }

    private fun buildAdvanceCompletionViolations(request: StageTransitionRequest): List<Violation> {
        val workflow = request.workflow
        val currentStage = request.fromStage
        val tasksArtifactContent = artifactServiceDelegate.readArtifact(workflow.id, StageId.TASKS)
        val tasksRepairQuickFixes = tasksArtifactRepairQuickFixes(tasksArtifactContent)
        val tasksDocument = workflow.getDocument(SpecPhase.IMPLEMENT)
        val tasks = resolveImplementationTasks(workflow, tasksDocument)
        val requirementsDocument = workflow.getDocument(SpecPhase.SPECIFY)
        val designDocument = workflow.getDocument(SpecPhase.DESIGN)
        val verifyEnabled = request.stagePlan.isActive(StageId.VERIFY) || workflow.verifyEnabled
        val verificationDocumentAvailable = artifactServiceDelegate.readArtifact(workflow.id, StageId.VERIFY) != null
        val hasVerificationRun = verificationServiceDelegate.listRunHistory(workflow.id).isNotEmpty()

        return when (currentStage) {
            StageId.REQUIREMENTS -> buildList {
                val missingSections = requirementsDocument
                    ?.content
                    ?.let(RequirementsSectionSupport::missingSections)
                    .orEmpty()
                if (missingSections.isNotEmpty()) {
                    add(
                        completionViolation(
                            fileName = StageId.REQUIREMENTS.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.requirements.sections"),
                            quickFixes = requirementsMissingSectionQuickFixes(missingSections),
                        ),
                    )
                }
                if (hasPendingClarification(workflow, currentStage)) {
                    add(
                        completionViolation(
                            fileName = StageId.REQUIREMENTS.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.common.clarificationPending"),
                        ),
                    )
                }
            }

            StageId.DESIGN -> buildList {
                if (designDocument != null && !hasDesignSections(designDocument.content)) {
                    add(
                        completionViolation(
                            fileName = StageId.DESIGN.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.design.sections"),
                        ),
                    )
                }
                if (hasPendingClarification(workflow, currentStage)) {
                    add(
                        completionViolation(
                            fileName = StageId.DESIGN.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.common.clarificationPending"),
                        ),
                    )
                }
            }

            StageId.TASKS -> buildList {
                if (tasks.isEmpty()) {
                    add(
                        completionViolation(
                            fileName = StageId.TASKS.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.tasks.structured"),
                            quickFixes = tasksRepairQuickFixes,
                        ),
                    )
                }
                if (hasPendingClarification(workflow, currentStage)) {
                    add(
                        completionViolation(
                            fileName = StageId.TASKS.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.common.clarificationPending"),
                        ),
                    )
                }
            }

            StageId.IMPLEMENT -> {
                val implementReadiness = evaluateImplementStageReadiness(
                    tasksDocument = tasksDocument,
                    tasks = tasks,
                )
                buildList {
                    if (!implementReadiness.taskSourceReady) {
                        add(
                            completionViolation(
                                fileName = StageId.TASKS.artifactFileName.orEmpty(),
                                message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.taskSource"),
                                quickFixes = tasksRepairQuickFixes,
                            ),
                        )
                    }
                    if (implementReadiness.progressBlocked) {
                        add(
                            completionViolation(
                                fileName = StageId.TASKS.artifactFileName.orEmpty(),
                                message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.progress"),
                            ),
                        )
                    }
                    if (implementReadiness.relatedFilesBlocked) {
                        add(
                            completionViolation(
                                fileName = StageId.TASKS.artifactFileName.orEmpty(),
                                message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.relatedFiles"),
                            ),
                        )
                    }
                }
            }

            StageId.VERIFY -> buildList {
                if (!verifyEnabled) {
                    add(
                        completionViolation(
                            fileName = StageId.VERIFY.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.verify.enabled"),
                        ),
                    )
                }
                if (verifyEnabled && !hasVerificationRun) {
                    add(
                        completionViolation(
                            fileName = StageId.VERIFY.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.verify.run"),
                        ),
                    )
                }
                if (verifyEnabled && !verificationDocumentAvailable) {
                    add(
                        completionViolation(
                            fileName = StageId.VERIFY.artifactFileName.orEmpty(),
                            message = SpecCodingBundle.message("spec.toolwindow.overview.blockers.verify.document"),
                        ),
                    )
                }
            }

            StageId.ARCHIVE -> emptyList()
        }
    }

    private fun completionViolation(
        fileName: String,
        message: String,
        quickFixes: List<GateQuickFixDescriptor> = emptyList(),
    ): Violation {
        return Violation(
            ruleId = STAGE_COMPLETION_RULE_ID,
            severity = GateStatus.ERROR,
            fileName = fileName,
            line = 1,
            message = message,
            fixHint = message,
            quickFixes = quickFixes,
        )
    }

    private fun hasPendingClarification(
        workflow: SpecWorkflow,
        stageId: StageId,
    ): Boolean {
        val state = workflow.clarificationRetryState ?: return false
        if (state.confirmed) {
            return false
        }
        if (workflow.currentPhase.toStageId() != stageId) {
            return false
        }
        return state.questionsMarkdown.isNotBlank() || state.structuredQuestions.isNotEmpty()
    }

    private fun hasRequirementsSections(content: String): Boolean = RequirementsSectionSupport.hasRequiredSections(content)

    private fun hasDesignSections(content: String): Boolean {
        return REQUIRED_DESIGN_SECTION_MARKERS.all { markers ->
            markers.any { marker -> content.contains(marker, ignoreCase = true) }
        }
    }

    private fun implementCompletionBlockers(implementReadiness: ImplementStageReadiness): List<String> {
        return buildList {
            if (!implementReadiness.taskSourceReady) {
                add(SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.taskSource"))
            }
            if (implementReadiness.progressBlocked) {
                add(SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.progress"))
            }
            if (implementReadiness.relatedFilesBlocked) {
                add(SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.relatedFiles"))
            }
        }
    }

    private fun resolveImplementationTasks(
        workflow: SpecWorkflow,
        implementDocument: SpecDocument? = workflow.getDocument(SpecPhase.IMPLEMENT),
    ): List<StructuredTask> {
        val persistedTasks = tasksServiceDelegate.parse(workflow.id)
        if (persistedTasks.isNotEmpty()) {
            return persistedTasks
        }
        val currentDocument = implementDocument ?: return emptyList()
        return tasksServiceDelegate.parseDocument(currentDocument.content).tasksById
    }

    private fun requirementsMissingSectionQuickFixes(
        missingSections: List<RequirementsSectionId>,
    ): List<GateQuickFixDescriptor> {
        val payload = MissingRequirementsSectionsQuickFixPayload(missingSections)
        val aiUnavailableReason = RequirementsSectionAiSupport.unavailableReason()
        return listOf(
            GateQuickFixDescriptor(
                kind = GateQuickFixKind.AI_FILL_MISSING_REQUIREMENTS_SECTIONS,
                payload = payload,
                enabled = aiUnavailableReason == null,
                disabledReason = aiUnavailableReason,
            ),
            GateQuickFixDescriptor(
                kind = GateQuickFixKind.CLARIFY_THEN_FILL_REQUIREMENTS_SECTIONS,
                payload = payload,
            ),
            GateQuickFixDescriptor(
                kind = GateQuickFixKind.OPEN_FOR_MANUAL_EDIT,
                payload = payload,
            ),
        )
    }

    private fun tasksArtifactRepairQuickFixes(tasksMarkdown: String?): List<GateQuickFixDescriptor> {
        if (tasksMarkdown.isNullOrBlank()) {
            return emptyList()
        }
        if (SpecTaskMarkdownParser.parse(tasksMarkdown).issues.isEmpty()) {
            return emptyList()
        }
        return listOf(
            GateQuickFixDescriptor(
                kind = GateQuickFixKind.REPAIR_TASKS_ARTIFACT,
            ),
        )
    }

    private fun emitSpecStageChangedHook(
        workflowId: String,
        previousPhase: SpecPhase,
        currentPhase: SpecPhase,
        previousStage: StageId? = null,
        currentStage: StageId? = null,
    ) {
        runCatching {
            val metadata = linkedMapOf(
                "workflowId" to workflowId,
                "previousStage" to previousPhase.name,
                "currentStage" to currentPhase.name,
            )
            previousStage?.let { stage -> metadata["previousWorkflowStage"] = stage.name }
            currentStage?.let { stage -> metadata["currentWorkflowStage"] = stage.name }
            HookManager.getInstance(project).trigger(
                event = HookEvent.SPEC_STAGE_CHANGED,
                triggerContext = HookTriggerContext(
                    specStage = currentPhase.name,
                    metadata = metadata,
                ),
            )
        }.onFailure { error ->
            logger.warn(
                "Failed to emit SPEC_STAGE_CHANGED hook for workflow=$workflowId " +
                    "(${previousPhase.name} -> ${currentPhase.name})",
                error,
            )
        }
    }

    private fun buildStageTransitionRequest(
        workflow: SpecWorkflow,
        transitionType: StageTransitionType,
        targetStage: StageId,
        evaluatedStages: List<StageId>,
        stagePlan: WorkflowStagePlan,
    ): StageTransitionRequest {
        return StageTransitionRequest(
            workflowId = workflow.id,
            transitionType = transitionType,
            fromStage = workflow.currentStage,
            targetStage = targetStage,
            evaluatedStages = evaluatedStages.distinct(),
            stagePlan = stagePlan,
            workflow = workflow,
        )
    }

    private fun prepareStageTransition(
        workflowId: String,
        transitionType: StageTransitionType,
        requestedTargetStage: StageId? = null,
    ): PreparedStageTransition {
        val workflow = activeWorkflows[workflowId]
            ?: storageDelegate.loadWorkflow(workflowId).getOrThrow()
        val projectConfig = projectConfigDelegate.load()
        val stagePlan = resolveStagePlan(workflow, projectConfig)

        return when (transitionType) {
            StageTransitionType.ADVANCE -> {
                val targetStage = stagePlan.nextActiveStage(workflow.currentStage)
                    ?: throw IllegalStateException("Already at the last active stage")
                PreparedStageTransition(
                    workflow = workflow,
                    stagePlan = stagePlan,
                    gatePolicy = projectConfig.gate,
                    targetStage = targetStage,
                    evaluatedStages = listOf(workflow.currentStage),
                )
            }

            StageTransitionType.JUMP -> {
                throw ManualStageMutationLockedError(workflowId, StageTransitionType.JUMP)
            }

            StageTransitionType.ROLLBACK -> {
                throw ManualStageMutationLockedError(workflowId, StageTransitionType.ROLLBACK)
            }
        }
    }

    private data class StageMetadataState(
        val currentStage: StageId,
        val verifyEnabled: Boolean,
        val stageStates: Map<StageId, StageState>,
    )

    private data class PreparedStageTransition(
        val workflow: SpecWorkflow,
        val stagePlan: WorkflowStagePlan,
        val gatePolicy: SpecGatePolicy,
        val targetStage: StageId,
        val evaluatedStages: List<StageId>,
    )

    private fun initialPhaseForStage(stage: StageId): SpecPhase {
        return when (stage) {
            StageId.REQUIREMENTS -> SpecPhase.SPECIFY
            StageId.DESIGN -> SpecPhase.DESIGN
            StageId.TASKS,
            StageId.IMPLEMENT,
            StageId.VERIFY,
            StageId.ARCHIVE,
            -> SpecPhase.IMPLEMENT
        }
    }

    private fun initializeStageMetadata(
        templatePolicy: SpecTemplatePolicy,
        timestampMillis: Long,
        verifyEnabled: Boolean? = null,
    ): StageMetadataState {
        val stagePlan = templatePolicy.stagePlan(verifyEnabled = verifyEnabled)
        val enteredAt = Instant.ofEpochMilli(timestampMillis).toString()
        return StageMetadataState(
            currentStage = stagePlan.firstActiveStage,
            verifyEnabled = stagePlan.isActive(StageId.VERIFY),
            stageStates = stagePlan.initialStageStates(enteredAt),
        )
    }

    private fun advanceStageMetadata(
        workflow: SpecWorkflow,
        toPhase: SpecPhase,
        timestampMillis: Long,
    ): StageMetadataState {
        val toStage = toPhase.toStageId()
        val beforeStage = workflow.currentStage
        val timestamp = Instant.ofEpochMilli(timestampMillis).toString()
        val updated = workflow.stageStates.toMutableMap()

        val previousState = updated[beforeStage] ?: StageState(
            active = true,
            status = StageProgress.NOT_STARTED,
        )
        updated[beforeStage] = previousState.copy(
            active = true,
            status = StageProgress.DONE,
            enteredAt = previousState.enteredAt ?: timestamp,
            completedAt = timestamp,
        )

        val nextState = updated[toStage] ?: StageState(
            active = true,
            status = StageProgress.NOT_STARTED,
        )
        updated[toStage] = nextState.copy(
            active = true,
            status = StageProgress.IN_PROGRESS,
            enteredAt = nextState.enteredAt ?: timestamp,
            completedAt = null,
        )
        return StageMetadataState(
            currentStage = toStage,
            verifyEnabled = workflow.verifyEnabled,
            stageStates = updated,
        )
    }

    private fun rollbackStageMetadata(
        workflow: SpecWorkflow,
        toPhase: SpecPhase,
        timestampMillis: Long,
    ): StageMetadataState {
        val toStage = toPhase.toStageId()
        val fromStage = workflow.currentStage
        val timestamp = Instant.ofEpochMilli(timestampMillis).toString()
        val updated = workflow.stageStates.toMutableMap()

        val fromState = updated[fromStage] ?: StageState(
            active = true,
            status = StageProgress.IN_PROGRESS,
        )
        updated[fromStage] = fromState.copy(
            active = fromState.active,
            status = StageProgress.NOT_STARTED,
            completedAt = null,
        )

        val targetState = updated[toStage] ?: StageState(
            active = true,
            status = StageProgress.NOT_STARTED,
        )
        updated[toStage] = targetState.copy(
            active = true,
            status = StageProgress.IN_PROGRESS,
            enteredAt = targetState.enteredAt ?: timestamp,
            completedAt = null,
        )
        return StageMetadataState(
            currentStage = toStage,
            verifyEnabled = workflow.verifyEnabled,
            stageStates = updated,
        )
    }

    private fun markCurrentStageCompleted(
        workflow: SpecWorkflow,
        timestampMillis: Long,
    ): Map<StageId, StageState> {
        val timestamp = Instant.ofEpochMilli(timestampMillis).toString()
        val updated = workflow.stageStates.toMutableMap()
        val currentState = updated[workflow.currentStage] ?: StageState(
            active = true,
            status = StageProgress.IN_PROGRESS,
        )
        updated[workflow.currentStage] = currentState.copy(
            active = true,
            status = StageProgress.DONE,
            enteredAt = currentState.enteredAt ?: timestamp,
            completedAt = timestamp,
        )
        return updated
    }

    private fun enrichGenerationOptions(
        workflow: SpecWorkflow,
        options: GenerationOptions,
    ): GenerationOptions {
        val resolvedComposeActionMode = options.composeActionMode
            ?: workflow.resolveComposeActionMode(workflow.currentPhase)
        val normalizedWorkingDirectory = options.workingDirectory
            ?.trim()
            ?.ifBlank { null }
            ?: project.basePath
                ?.trim()
                ?.ifBlank { null }
        val normalizedOperationMode = options.operationMode
            ?.trim()
            ?.ifBlank { null }
            ?: OperationMode.PLAN.name
        val enrichedOptions = options.copy(
            workingDirectory = normalizedWorkingDirectory,
            operationMode = normalizedOperationMode,
            composeActionMode = resolvedComposeActionMode,
        )
        val workflowSourceUsage = buildWorkflowSourceUsage(
            workflow = workflow,
            requestedUsage = enrichedOptions.workflowSourceUsage,
        )
        return enrichedOptions.copy(workflowSourceUsage = workflowSourceUsage)
    }

    private fun prepareGenerationRequest(
        workflow: SpecWorkflow,
        input: String,
        options: GenerationOptions,
    ): PreparedGenerationRequest {
        val effectiveOptions = enrichGenerationOptions(workflow, options)
        val previousDocument = workflow.currentPhase.previous()?.let { phase -> workflow.getDocument(phase) }
        val currentDocument = workflow.getCurrentDocument()
        val request = SpecGenerationRequest(
            phase = workflow.currentPhase,
            input = input,
            previousDocument = previousDocument,
            currentDocument = currentDocument,
            incrementalBaselineContext = buildIncrementalBaselineContext(workflow),
            codeContextPack = codeContextServiceDelegate.buildCodeContextPack(
                workflow = workflow,
                phase = workflow.currentPhase,
            ),
            options = effectiveOptions,
        )
        return PreparedGenerationRequest(
            options = effectiveOptions,
            currentDocument = currentDocument,
            request = request,
        )
    }

    private data class PreparedGenerationRequest(
        val options: GenerationOptions,
        val currentDocument: SpecDocument?,
        val request: SpecGenerationRequest,
    )

    private fun buildWorkflowSourceUsage(
        workflow: SpecWorkflow,
        requestedUsage: WorkflowSourceUsage,
    ): WorkflowSourceUsage {
        val selectedSourceIds = requestedUsage.selectedSourceIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (selectedSourceIds.isEmpty()) {
            return WorkflowSourceUsage()
        }

        val assetsById = storageDelegate.listWorkflowSources(workflow.id)
            .getOrElse { error ->
                logger.warn("Failed to list workflow sources for workflow=${workflow.id}", error)
                return WorkflowSourceUsage(selectedSourceIds = selectedSourceIds)
            }
            .associateBy(WorkflowSourceAsset::sourceId)
        val consumedAssets = selectedSourceIds.mapNotNull(assetsById::get)
        if (consumedAssets.isEmpty()) {
            return WorkflowSourceUsage(selectedSourceIds = selectedSourceIds)
        }

        return WorkflowSourceUsage(
            selectedSourceIds = selectedSourceIds,
            consumedSourceIds = consumedAssets.map(WorkflowSourceAsset::sourceId),
            renderedContext = buildWorkflowSourceContext(workflow.id, consumedAssets),
        )
    }

    private fun buildWorkflowSourceContext(
        workflowId: String,
        assets: List<WorkflowSourceAsset>,
    ): String {
        return buildString {
            appendLine("## Workflow reference sources")
            appendLine("Use these persisted workflow sources as additional context for the current request.")
            assets.forEach { asset ->
                appendLine()
                appendLine("### ${asset.sourceId} | ${asset.originalFileName}")
                appendLine("- Path: `${asset.storedRelativePath}`")
                appendLine("- Media type: ${asset.mediaType}")
                appendLine("- Imported from: ${asset.importedFromStage.name} / ${asset.importedFromEntry}")
                val excerpt = loadWorkflowSourceExcerpt(workflowId, asset)
                if (excerpt == null) {
                    appendLine("- Extracted text excerpt: (not available; rely on metadata only)")
                } else {
                    appendLine("- Extracted text excerpt:")
                    appendLine("```text")
                    appendLine(excerpt)
                    appendLine("```")
                }
            }
        }.trimEnd()
    }

    private fun loadWorkflowSourceExcerpt(
        workflowId: String,
        asset: WorkflowSourceAsset,
    ): String? {
        val rawContent = storageDelegate.readWorkflowSourceText(workflowId, asset)
            .getOrElse { error ->
                logger.debug("Skip workflow source text extraction for ${asset.sourceId}", error)
                return null
            }
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            .orEmpty()
        if (rawContent.isBlank()) {
            return null
        }
        return clipContextText(
            content = rawContent,
            maxLines = MAX_WORKFLOW_SOURCE_EXCERPT_LINES,
            maxChars = MAX_WORKFLOW_SOURCE_EXCERPT_CHARS,
        )
    }

    private fun appendWorkflowSourceUsageAudit(
        workflowId: String,
        phase: SpecPhase,
        actionType: String,
        options: GenerationOptions,
        status: String,
        extraDetails: Map<String, String> = emptyMap(),
    ) {
        val selectedSourceIds = options.workflowSourceUsage.selectedSourceIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (selectedSourceIds.isEmpty()) {
            return
        }

        val consumedSourceIds = options.workflowSourceUsage.consumedSourceIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        storageDelegate.appendAuditEvent(
            workflowId = workflowId,
            eventType = SpecAuditEventType.SOURCE_USAGE_RECORDED,
            details = buildMap {
                put("phase", phase.name)
                put("file", phase.outputFileName)
                put("actionType", actionType)
                put("status", status)
                put("selectedSourceCount", selectedSourceIds.size.toString())
                put("selectedSourceIds", selectedSourceIds.joinToString(","))
                put("consumedSourceCount", consumedSourceIds.size.toString())
                put("consumedSourceIds", consumedSourceIds.joinToString(","))
                put("sourceConsumed", consumedSourceIds.isNotEmpty().toString())
                options.requestId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { requestId ->
                        put("requestId", requestId)
                    }
                extraDetails.forEach { (key, value) ->
                    val normalizedValue = value.trim()
                    if (normalizedValue.isNotBlank()) {
                        put(key, normalizedValue)
                    }
                }
            },
        ).getOrThrow()
    }

    private fun auditActionTypeForComposeMode(mode: ArtifactComposeActionMode): String {
        return when (mode) {
            ArtifactComposeActionMode.GENERATE -> AUDIT_ACTION_GENERATE_CURRENT_PHASE
            ArtifactComposeActionMode.REVISE -> AUDIT_ACTION_REVISE_CURRENT_PHASE
        }
    }

    private fun buildIncrementalBaselineContext(workflow: SpecWorkflow): String? {
        if (!workflow.isIncrementalWorkflow()) {
            return null
        }
        val baselineWorkflowId = workflow.baselineWorkflowId?.trim().orEmpty()
        if (baselineWorkflowId.isBlank()) {
            return null
        }
        val baseline = activeWorkflows[baselineWorkflowId]
            ?: storageDelegate.loadWorkflow(baselineWorkflowId).getOrElse { error ->
                logger.warn("Failed to load baseline workflow for incremental generation: $baselineWorkflowId", error)
                return null
            }
        activeWorkflows[baselineWorkflowId] = baseline

        fun baselineDoc(phase: SpecPhase): String {
            return baseline.getDocument(phase)?.content?.trim().takeUnless { it.isNullOrEmpty() } ?: "(无)"
        }

        return buildString {
            appendLine("## 增量需求基线上下文")
            appendLine("当前工作流是增量需求，请在输出中明确区分“新增 / 修改 / 保持不变”。")
            appendLine("基线工作流 ID: ${baseline.id}")
            appendLine("基线标题: ${baseline.title.ifBlank { baseline.id }}")
            if (workflow.description.isNotBlank()) {
                appendLine("当前工作流描述（变更目标）: ${workflow.description.trim()}")
            }
            appendLine()
            appendLine("## 基线 requirements.md")
            appendLine("```")
            appendLine(baselineDoc(SpecPhase.SPECIFY))
            appendLine("```")
            appendLine()
            appendLine("## 基线 design.md")
            appendLine("```")
            appendLine(baselineDoc(SpecPhase.DESIGN))
            appendLine("```")
            appendLine()
            appendLine("## 基线 tasks.md")
            appendLine("```")
            appendLine(baselineDoc(SpecPhase.IMPLEMENT))
            appendLine("```")
        }
    }

    private fun buildIncrementalProjectContext(workflow: SpecWorkflow): String? {
        if (!workflow.isIncrementalWorkflow() || workflow.currentPhase != SpecPhase.SPECIFY) {
            return null
        }
        val basePath = project.basePath
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val root = runCatching { Path.of(basePath) }.getOrNull() ?: return null
        if (!Files.isDirectory(root)) {
            return null
        }

        val topLevelDirectories = listTopLevelEntries(root, directoriesOnly = true)
        val topLevelFiles = listTopLevelEntries(root, directoriesOnly = false)
        val keyFileSnippets = readKeyProjectFiles(root)
        val sourceSnapshot = buildSourceSnapshot(root)

        if (topLevelDirectories.isEmpty() && topLevelFiles.isEmpty() && keyFileSnippets.isEmpty() && sourceSnapshot.isBlank()) {
            return null
        }

        return buildString {
            appendLine("## 现有项目上下文（增量需求生成要求）")
            appendLine("生成 requirements.md 时，请结合当前代码结构和已有配置，优先复用现有模块与命名。")
            if (topLevelDirectories.isNotEmpty()) {
                appendLine("顶层目录: ${topLevelDirectories.joinToString(", ")}")
            }
            if (topLevelFiles.isNotEmpty()) {
                appendLine("顶层文件: ${topLevelFiles.joinToString(", ")}")
            }
            if (sourceSnapshot.isNotBlank()) {
                appendLine()
                appendLine("### 现有源码文件（节选）")
                appendLine(sourceSnapshot)
            }
            if (keyFileSnippets.isNotEmpty()) {
                appendLine()
                appendLine("### 关键项目文件（节选）")
                keyFileSnippets.forEach { snippet ->
                    appendLine("#### ${snippet.relativePath}")
                    appendLine("```")
                    appendLine(snippet.content)
                    appendLine("```")
                    appendLine()
                }
            }
        }.trimEnd()
    }

    private fun listTopLevelEntries(root: Path, directoriesOnly: Boolean): List<String> {
        return runCatching {
            Files.list(root).use { stream ->
                stream
                    .filter { path ->
                        val name = path.fileName?.toString().orEmpty()
                        if (name.isBlank() || shouldIgnoreProjectEntry(name)) {
                            return@filter false
                        }
                        if (directoriesOnly) {
                            Files.isDirectory(path)
                        } else {
                            Files.isRegularFile(path)
                        }
                    }
                    .map { it.fileName.toString() }
                    .sorted()
                    .limit(MAX_TOP_LEVEL_ENTRIES.toLong())
                    .toList()
            }
        }.getOrElse { emptyList() }
    }

    private fun readKeyProjectFiles(root: Path): List<ProjectContextSnippet> {
        return KEY_PROJECT_CONTEXT_FILES.mapNotNull { relativePath ->
            val path = root.resolve(relativePath)
            if (!Files.isRegularFile(path)) {
                return@mapNotNull null
            }
            val raw = runCatching {
                Files.readString(path, DEFAULT_PROJECT_CONTEXT_CHARSET)
            }.getOrElse { error ->
                logger.debug("Skip project context file due to read failure: $relativePath", error)
                return@mapNotNull null
            }
            val normalized = raw
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
            if (normalized.isBlank()) {
                return@mapNotNull null
            }
            ProjectContextSnippet(
                relativePath = relativePath,
                content = clipContextText(normalized, MAX_KEY_FILE_SNIPPET_LINES, MAX_KEY_FILE_SNIPPET_CHARS),
            )
        }
    }

    private fun buildSourceSnapshot(root: Path): String {
        val lines = mutableListOf<String>()
        for (relativeDir in SOURCE_CONTEXT_DIRS) {
            val dir = root.resolve(relativeDir)
            if (!Files.isDirectory(dir)) {
                continue
            }
            val files = runCatching {
                Files.walk(dir, SOURCE_SNAPSHOT_DEPTH).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .map { root.relativize(it).toString().replace('\\', '/') }
                        .sorted()
                        .limit(MAX_SOURCE_FILES_PER_DIR.toLong())
                        .toList()
                }
            }.getOrElse { error ->
                logger.debug("Skip source snapshot for $relativeDir", error)
                emptyList()
            }
            if (files.isEmpty()) {
                continue
            }
            lines += "- $relativeDir"
            files.forEach { path -> lines += "  - `$path`" }
        }
        return lines.joinToString("\n")
    }

    private fun clipContextText(content: String, maxLines: Int, maxChars: Int): String {
        val lines = content.lines()
        val clippedByLines = lines.size > maxLines
        val linesWithinBudget = lines.take(maxLines).joinToString("\n")
        val clippedByChars = linesWithinBudget.length > maxChars
        val clipped = if (clippedByChars) {
            linesWithinBudget.take(maxChars).trimEnd()
        } else {
            linesWithinBudget
        }
        return if (clippedByLines || clippedByChars) {
            "$clipped\n...(截断)"
        } else {
            clipped
        }
    }

    private fun shouldIgnoreProjectEntry(name: String): Boolean {
        return name in PROJECT_CONTEXT_IGNORED_ENTRIES
    }

    private data class ProjectContextSnippet(
        val relativePath: String,
        val content: String,
    )

    /**
     * 生成工作流 ID
     */
    private fun generateWorkflowId(): String {
        return workflowIdGenerator.nextId()
    }

    companion object {
        private val PROJECT_CONTEXT_IGNORED_ENTRIES = setOf(
            ".git",
            ".idea",
            ".gradle",
            ".spec-coding",
            "build",
            "out",
            "node_modules",
            "__pycache__",
            ".DS_Store",
            "Thumbs.db",
        )
        private val KEY_PROJECT_CONTEXT_FILES = listOf(
            "README.md",
            "README.zh-CN.md",
            "docs/spec-code-plan.md",
            "docs/dev-checklist.md",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "src/main/resources/META-INF/plugin.xml",
            "package.json",
            "pom.xml",
            "pyproject.toml",
        )
        private val SOURCE_CONTEXT_DIRS = listOf(
            "src/main/kotlin",
            "src/main/java",
            "src/main/resources",
            "src/test/kotlin",
            "src/test/java",
        )
        private val DEFAULT_PROJECT_CONTEXT_CHARSET: Charset = Charsets.UTF_8
        private const val MAX_TOP_LEVEL_ENTRIES = 12
        private const val MAX_KEY_FILE_SNIPPET_LINES = 120
        private const val STAGE_COMPLETION_RULE_ID = "stage-completion-checks"
        private val REQUIRED_REQUIREMENTS_SECTION_MARKERS = listOf(
            listOf("## Functional Requirements", "## 功能需求"),
            listOf("## Non-Functional Requirements", "## 非功能需求"),
            listOf("## User Stories", "## 用户故事"),
            listOf("## Acceptance Criteria", "## 验收标准"),
        )
        private val REQUIRED_DESIGN_SECTION_MARKERS = listOf(
            listOf("## Architecture Design", "## 架构设计"),
            listOf("## Technology Choices", "## 技术选型"),
            listOf("## Data Model", "## 数据模型"),
            listOf("## API Design", "## API 设计"),
            listOf(
                "## Non-Functional Design",
                "## 非功能设计",
                "## Non-Functional Requirements",
                "## 非功能需求",
            ),
        )
        private const val MAX_KEY_FILE_SNIPPET_CHARS = 4000
        private const val MAX_WORKFLOW_SOURCE_EXCERPT_LINES = 60
        private const val MAX_WORKFLOW_SOURCE_EXCERPT_CHARS = 2000
        private const val SOURCE_SNAPSHOT_DEPTH = 4
        private const val MAX_SOURCE_FILES_PER_DIR = 18
        private const val AUDIT_ACTION_GENERATE_CURRENT_PHASE = "GENERATE_CURRENT_PHASE"
        private const val AUDIT_ACTION_REVISE_CURRENT_PHASE = "REVISE_CURRENT_PHASE"
        private const val AUDIT_ACTION_DRAFT_CLARIFICATION = "DRAFT_CURRENT_PHASE_CLARIFICATION"
        private const val AUDIT_STATUS_SUCCESS = "SUCCESS"
        private const val AUDIT_STATUS_FAILED = "FAILED"
        private const val AUDIT_STATUS_VALIDATION_FAILED = "VALIDATION_FAILED"

        fun getInstance(project: Project): SpecEngine = project.service()
    }
}

class DocumentRevisionConflictException(
    val workflowId: String,
    val phase: SpecPhase,
    val expectedRevision: Long,
    val actualRevision: Long,
) : IllegalStateException(
    "Document revision conflict: expected $expectedRevision but was $actualRevision " +
        "(workflow=$workflowId, phase=${phase.name})",
)

/**
 * Spec 生成进度
 */
sealed class SpecGenerationProgress {
    data class Started(val phase: SpecPhase) : SpecGenerationProgress()
    data class Generating(val phase: SpecPhase, val progress: Double) : SpecGenerationProgress()
    data class Completed(val document: SpecDocument) : SpecGenerationProgress()
    data class ValidationFailed(val document: SpecDocument, val validation: ValidationResult) : SpecGenerationProgress()
    data class Failed(val error: String, val details: String?) : SpecGenerationProgress()
}
