package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.GenerationOptions
import com.eacape.speccodingplugin.spec.SpecClarificationDraft
import com.eacape.speccodingplugin.spec.SpecGenerationProgress
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.WorkflowSourceUsage
import java.util.Locale
import java.util.UUID

internal data class SpecWorkflowGenerationContext(
    val workflowId: String,
    val phase: SpecPhase,
    val options: GenerationOptions,
)

internal data class SpecWorkflowActiveGenerationRequest(
    val workflowId: String,
    val providerId: String?,
    val requestId: String,
)

internal enum class SpecWorkflowTimelineEntryState {
    ACTIVE,
    DONE,
    FAILED,
    INFO,
}

internal data class SpecWorkflowTimelineEntry(
    val text: String,
    val state: SpecWorkflowTimelineEntryState,
)

internal sealed interface SpecWorkflowGenerationContextResolution {
    data class Success(
        val context: SpecWorkflowGenerationContext,
    ) : SpecWorkflowGenerationContextResolution

    data class Failure(
        val statusMessage: String?,
    ) : SpecWorkflowGenerationContextResolution
}

internal sealed interface SpecWorkflowGenerationLaunchPlan {
    data class ResumeGeneration(
        val workflowId: String,
        val input: String,
        val options: GenerationOptions,
        val shouldShowRetryContextReuse: Boolean,
    ) : SpecWorkflowGenerationLaunchPlan

    data class RequestClarification(
        val context: SpecWorkflowGenerationContext,
        val input: String,
        val options: GenerationOptions,
        val suggestedDetails: String,
        val seedQuestionsMarkdown: String?,
        val seedStructuredQuestions: List<String>,
        val clarificationRound: Int,
        val shouldClearProcessTimeline: Boolean,
        val shouldShowRetryContextReuse: Boolean,
        val retryLastError: String?,
    ) : SpecWorkflowGenerationLaunchPlan
}

internal data class SpecWorkflowPreparedClarificationDraftRequest(
    val context: SpecWorkflowGenerationContext,
    val input: String,
    val safeSuggestedDetails: String,
    val requestOptions: GenerationOptions,
    val composeMode: ArtifactComposeActionMode,
    val activeRequest: SpecWorkflowActiveGenerationRequest,
    val seedStructuredQuestions: List<String>,
    val clarificationRound: Int,
    val loadingStatusText: String,
    val initialTimelineEntries: List<SpecWorkflowTimelineEntry>,
)

internal data class SpecWorkflowClarificationDraftResult(
    val phase: SpecPhase,
    val questionsMarkdown: String,
    val structuredQuestions: List<String>,
    val errorText: String?,
    val statusText: String?,
    val timelineEntry: SpecWorkflowTimelineEntry,
)

internal data class SpecWorkflowPreparedGenerationRequest(
    val workflowId: String,
    val input: String,
    val requestOptions: GenerationOptions,
    val composeMode: ArtifactComposeActionMode,
    val activeRequest: SpecWorkflowActiveGenerationRequest,
)

internal data class SpecWorkflowGenerationProgressTracker(
    val modelCallRecorded: Boolean = false,
    val normalizeRecorded: Boolean = false,
)

internal data class SpecWorkflowGenerationProgressUpdate(
    val tracker: SpecWorkflowGenerationProgressTracker,
    val timelineEntries: List<SpecWorkflowTimelineEntry> = emptyList(),
    val progressFraction: Double? = null,
    val shouldClearRetry: Boolean = false,
    val retryConfirmedContext: String? = null,
    val retryLastError: String? = null,
    val statusText: String? = null,
    val shouldShowGenerationFailed: Boolean = false,
    val shouldExitClarificationMode: Boolean = false,
    val clearInputOnExit: Boolean = false,
    val shouldReloadWorkflow: Boolean = false,
    val validationFailure: ValidationResult? = null,
)

internal class SpecWorkflowGenerationCoordinator(
    private val providerDisplayName: (String) -> String,
    private val renderFailureMessage: (Throwable?, String) -> String,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
    private val randomToken: () -> String = { UUID.randomUUID().toString().substring(0, 8) },
) {

    fun resolveGenerationContext(
        selectedWorkflowId: String?,
        currentWorkflow: SpecWorkflow?,
        providerId: String?,
        modelId: String?,
        workflowSourceUsage: WorkflowSourceUsage,
    ): SpecWorkflowGenerationContextResolution {
        val workflowId = selectedWorkflowId?.trim().orEmpty()
        if (workflowId.isBlank()) {
            return SpecWorkflowGenerationContextResolution.Failure(statusMessage = null)
        }
        val workflow = currentWorkflow?.takeIf { it.id == workflowId }
        if (workflow == null) {
            return SpecWorkflowGenerationContextResolution.Failure(
                statusMessage = SpecCodingBundle.message(
                    "spec.workflow.error",
                    SpecCodingBundle.message("common.unknown"),
                ),
            )
        }
        val normalizedProviderId = providerId?.trim()
        if (normalizedProviderId.isNullOrBlank()) {
            return SpecWorkflowGenerationContextResolution.Failure(
                statusMessage = SpecCodingBundle.message("spec.workflow.generation.providerRequired"),
            )
        }
        val normalizedModelId = modelId?.trim().orEmpty()
        if (normalizedModelId.isBlank()) {
            return SpecWorkflowGenerationContextResolution.Failure(
                statusMessage = SpecCodingBundle.message(
                    "spec.workflow.generation.modelRequired",
                    providerDisplayName(normalizedProviderId),
                ),
            )
        }
        return SpecWorkflowGenerationContextResolution.Success(
            context = SpecWorkflowGenerationContext(
                workflowId = workflowId,
                phase = workflow.currentPhase,
                options = GenerationOptions(
                    providerId = normalizedProviderId,
                    model = normalizedModelId,
                    workflowSourceUsage = workflowSourceUsage,
                    composeActionMode = workflow.resolveComposeActionMode(workflow.currentPhase),
                ),
            ),
        )
    }

    fun buildLaunchPlan(
        input: String,
        pendingRetry: ClarificationRetryPayload?,
        context: SpecWorkflowGenerationContext,
    ): SpecWorkflowGenerationLaunchPlan {
        val effectiveInput = input.ifBlank { pendingRetry?.input.orEmpty() }
        val seededContext = when {
            input.isNotBlank() -> input
            !pendingRetry?.confirmedContext.isNullOrBlank() -> pendingRetry?.confirmedContext.orEmpty()
            else -> effectiveInput
        }
        val shouldResumeWithConfirmedContext = pendingRetry?.confirmed == true &&
            input.isBlank() &&
            pendingRetry.confirmedContext.isNotBlank()
        if (shouldResumeWithConfirmedContext) {
            return SpecWorkflowGenerationLaunchPlan.ResumeGeneration(
                workflowId = context.workflowId,
                input = effectiveInput,
                options = context.options.copy(
                    confirmedContext = pendingRetry.confirmedContext,
                    clarificationWriteback = pendingRetry.toWritebackPayload(),
                ),
                shouldShowRetryContextReuse = true,
            )
        }
        return SpecWorkflowGenerationLaunchPlan.RequestClarification(
            context = context,
            input = effectiveInput,
            options = context.options.copy(
                confirmedContext = pendingRetry?.confirmedContext,
            ),
            suggestedDetails = seededContext,
            seedQuestionsMarkdown = pendingRetry?.questionsMarkdown,
            seedStructuredQuestions = pendingRetry?.structuredQuestions.orEmpty(),
            clarificationRound = (pendingRetry?.clarificationRound ?: 0) + 1,
            shouldClearProcessTimeline = pendingRetry == null,
            shouldShowRetryContextReuse = pendingRetry != null,
            retryLastError = pendingRetry?.lastError,
        )
    }

    fun prepareClarificationDraft(
        context: SpecWorkflowGenerationContext,
        input: String,
        options: GenerationOptions = context.options,
        suggestedDetails: String = input,
        seedQuestionsMarkdown: String? = null,
        seedStructuredQuestions: List<String> = emptyList(),
        clarificationRound: Int = 1,
    ): SpecWorkflowPreparedClarificationDraftRequest {
        val requestOptions = withGenerationRequestId(
            workflowId = context.workflowId,
            phase = context.phase,
            options = options,
        )
        val composeMode = requestOptions.composeActionMode ?: ArtifactComposeActionMode.GENERATE
        val safeSuggestedDetails = suggestedDetails.ifBlank { input }
        return SpecWorkflowPreparedClarificationDraftRequest(
            context = context,
            input = input,
            safeSuggestedDetails = safeSuggestedDetails,
            requestOptions = requestOptions,
            composeMode = composeMode,
            activeRequest = SpecWorkflowActiveGenerationRequest(
                workflowId = context.workflowId,
                providerId = requestOptions.providerId,
                requestId = requestOptions.requestId.orEmpty(),
            ),
            seedStructuredQuestions = seedStructuredQuestions,
            clarificationRound = clarificationRound,
            loadingStatusText = ArtifactComposeActionUiText.clarificationGenerating(composeMode),
            initialTimelineEntries = buildList {
                if (!seedQuestionsMarkdown.isNullOrBlank()) {
                    add(
                        SpecWorkflowTimelineEntry(
                            text = SpecCodingBundle.message("spec.workflow.process.clarify.lastRoundReused"),
                            state = SpecWorkflowTimelineEntryState.INFO,
                        ),
                    )
                }
                add(
                    SpecWorkflowTimelineEntry(
                        text = SpecCodingBundle.message("spec.workflow.process.clarify.prepare"),
                        state = SpecWorkflowTimelineEntryState.DONE,
                    ),
                )
                add(
                    SpecWorkflowTimelineEntry(
                        text = SpecCodingBundle.message("spec.workflow.process.clarify.request", clarificationRound),
                        state = SpecWorkflowTimelineEntryState.ACTIVE,
                    ),
                )
            },
        )
    }

    fun buildClarificationDraftResult(
        prepared: SpecWorkflowPreparedClarificationDraftRequest,
        draft: SpecClarificationDraft?,
        error: Throwable?,
    ): SpecWorkflowClarificationDraftResult {
        val errorText = if (draft == null) {
            renderFailureMessage(error, SpecCodingBundle.message("common.unknown"))
        } else {
            null
        }
        return SpecWorkflowClarificationDraftResult(
            phase = draft?.phase ?: prepared.context.phase,
            questionsMarkdown = buildClarificationMarkdown(
                draft = draft,
                error = error,
            ),
            structuredQuestions = draft?.questions ?: prepared.seedStructuredQuestions,
            errorText = errorText,
            statusText = errorText?.let { message ->
                SpecCodingBundle.message("spec.workflow.error", message)
            },
            timelineEntry = if (draft == null) {
                SpecWorkflowTimelineEntry(
                    text = SpecCodingBundle.message(
                        "spec.workflow.process.clarify.failed",
                        errorText ?: SpecCodingBundle.message("common.unknown"),
                    ),
                    state = SpecWorkflowTimelineEntryState.FAILED,
                )
            } else {
                SpecWorkflowTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.clarify.ready"),
                    state = SpecWorkflowTimelineEntryState.DONE,
                )
            },
        )
    }

    fun prepareGeneration(
        workflowId: String,
        phase: SpecPhase,
        input: String,
        options: GenerationOptions,
    ): SpecWorkflowPreparedGenerationRequest {
        val requestOptions = withGenerationRequestId(
            workflowId = workflowId,
            phase = phase,
            options = options,
        )
        return SpecWorkflowPreparedGenerationRequest(
            workflowId = workflowId,
            input = input,
            requestOptions = requestOptions,
            composeMode = requestOptions.composeActionMode ?: ArtifactComposeActionMode.GENERATE,
            activeRequest = SpecWorkflowActiveGenerationRequest(
                workflowId = workflowId,
                providerId = requestOptions.providerId,
                requestId = requestOptions.requestId.orEmpty(),
            ),
        )
    }

    fun advanceGenerationProgress(
        prepared: SpecWorkflowPreparedGenerationRequest,
        tracker: SpecWorkflowGenerationProgressTracker,
        progress: SpecGenerationProgress,
    ): SpecWorkflowGenerationProgressUpdate {
        return when (progress) {
            is SpecGenerationProgress.Started -> {
                SpecWorkflowGenerationProgressUpdate(
                    tracker = tracker,
                    timelineEntries = listOf(
                        SpecWorkflowTimelineEntry(
                            text = ArtifactComposeActionUiText.processPrepare(prepared.composeMode),
                            state = SpecWorkflowTimelineEntryState.ACTIVE,
                        ),
                    ),
                    progressFraction = 0.0,
                )
            }

            is SpecGenerationProgress.Generating -> {
                val timelineEntries = mutableListOf<SpecWorkflowTimelineEntry>()
                var nextTracker = tracker
                if (!tracker.modelCallRecorded) {
                    timelineEntries += SpecWorkflowTimelineEntry(
                        text = ArtifactComposeActionUiText.processCall(
                            prepared.composeMode,
                            (progress.progress * 100).toInt().coerceIn(0, 100),
                        ),
                        state = SpecWorkflowTimelineEntryState.ACTIVE,
                    )
                    nextTracker = nextTracker.copy(modelCallRecorded = true)
                }
                if (progress.progress >= 0.5 && !tracker.normalizeRecorded) {
                    timelineEntries += SpecWorkflowTimelineEntry(
                        text = ArtifactComposeActionUiText.processNormalize(prepared.composeMode),
                        state = SpecWorkflowTimelineEntryState.ACTIVE,
                    )
                    nextTracker = nextTracker.copy(normalizeRecorded = true)
                }
                SpecWorkflowGenerationProgressUpdate(
                    tracker = nextTracker,
                    timelineEntries = timelineEntries,
                    progressFraction = progress.progress,
                )
            }

            is SpecGenerationProgress.Completed -> {
                SpecWorkflowGenerationProgressUpdate(
                    tracker = tracker,
                    timelineEntries = listOf(
                        SpecWorkflowTimelineEntry(
                            text = ArtifactComposeActionUiText.processValidate(prepared.composeMode),
                            state = SpecWorkflowTimelineEntryState.DONE,
                        ),
                        SpecWorkflowTimelineEntry(
                            text = ArtifactComposeActionUiText.processSave(prepared.composeMode),
                            state = SpecWorkflowTimelineEntryState.DONE,
                        ),
                        SpecWorkflowTimelineEntry(
                            text = ArtifactComposeActionUiText.processCompleted(prepared.composeMode),
                            state = SpecWorkflowTimelineEntryState.DONE,
                        ),
                    ),
                    shouldClearRetry = true,
                    shouldExitClarificationMode = true,
                    clearInputOnExit = true,
                    shouldReloadWorkflow = true,
                )
            }

            is SpecGenerationProgress.ValidationFailed -> {
                val firstValidationError = progress.validation.errors.firstOrNull()
                    ?: SpecCodingBundle.message("common.unknown")
                SpecWorkflowGenerationProgressUpdate(
                    tracker = tracker,
                    timelineEntries = listOf(
                        SpecWorkflowTimelineEntry(
                            text = ArtifactComposeActionUiText.processValidate(prepared.composeMode),
                            state = SpecWorkflowTimelineEntryState.ACTIVE,
                        ),
                        SpecWorkflowTimelineEntry(
                            text = ArtifactComposeActionUiText.processValidationFailed(
                                prepared.composeMode,
                                firstValidationError,
                            ),
                            state = SpecWorkflowTimelineEntryState.FAILED,
                        ),
                    ),
                    retryConfirmedContext = prepared.requestOptions.confirmedContext,
                    retryLastError = firstValidationError,
                    statusText = buildValidationFailureStatus(progress.validation),
                    shouldReloadWorkflow = true,
                    validationFailure = progress.validation,
                )
            }

            is SpecGenerationProgress.Failed -> {
                SpecWorkflowGenerationProgressUpdate(
                    tracker = tracker,
                    timelineEntries = listOf(
                        SpecWorkflowTimelineEntry(
                            text = ArtifactComposeActionUiText.processFailed(prepared.composeMode, progress.error),
                            state = SpecWorkflowTimelineEntryState.FAILED,
                        ),
                    ),
                    retryConfirmedContext = prepared.requestOptions.confirmedContext,
                    retryLastError = progress.error,
                    statusText = SpecCodingBundle.message("spec.workflow.error", progress.error),
                    shouldShowGenerationFailed = true,
                )
            }
        }
    }

    fun buildInterruptedProgressUpdate(
        prepared: SpecWorkflowPreparedGenerationRequest,
        interruptedMessage: String,
    ): SpecWorkflowGenerationProgressUpdate {
        return SpecWorkflowGenerationProgressUpdate(
            tracker = SpecWorkflowGenerationProgressTracker(),
            timelineEntries = listOf(
                SpecWorkflowTimelineEntry(
                    text = ArtifactComposeActionUiText.processFailed(prepared.composeMode, interruptedMessage),
                    state = SpecWorkflowTimelineEntryState.FAILED,
                ),
            ),
            retryConfirmedContext = prepared.requestOptions.confirmedContext,
            retryLastError = interruptedMessage,
            statusText = SpecCodingBundle.message("spec.workflow.error", interruptedMessage),
            shouldShowGenerationFailed = true,
        )
    }

    fun buildClarificationMarkdown(
        draft: SpecClarificationDraft?,
        error: Throwable? = null,
    ): String {
        if (draft == null) {
            val base = SpecCodingBundle.message("spec.workflow.clarify.noQuestions")
            val reason = renderFailureMessage(error, SpecCodingBundle.message("common.unknown"))
            return buildString {
                appendLine(base)
                appendLine()
                appendLine("```text")
                appendLine(reason)
                appendLine("```")
            }.trimEnd()
        }
        if (draft.rawContent.isNotBlank()) {
            return draft.rawContent
        }
        if (draft.questions.isNotEmpty()) {
            return buildString {
                appendLine("## ${SpecCodingBundle.message("spec.detail.clarify.questions.title")}")
                draft.questions.forEachIndexed { index, question ->
                    appendLine("${index + 1}. $question")
                }
            }.trimEnd()
        }
        return SpecCodingBundle.message("spec.workflow.clarify.noQuestions")
    }

    private fun withGenerationRequestId(
        workflowId: String,
        phase: SpecPhase,
        options: GenerationOptions,
    ): GenerationOptions {
        val existing = options.requestId?.trim().orEmpty()
        if (existing.isNotBlank()) {
            return options.copy(requestId = existing)
        }
        val phaseToken = phase.name.lowercase(Locale.ROOT)
        val requestId = "spec-$workflowId-$phaseToken-${currentTimeMillis()}-${randomToken()}"
        return options.copy(requestId = requestId)
    }

    private fun buildValidationFailureStatus(validation: ValidationResult): String {
        val firstError = validation.errors.firstOrNull()
        if (firstError.isNullOrBlank()) {
            return SpecCodingBundle.message("spec.workflow.validation.failed.unknown")
        }
        return if (validation.errors.size > 1) {
            SpecCodingBundle.message(
                "spec.workflow.validation.failed.more",
                firstError,
                validation.errors.size - 1,
            )
        } else {
            SpecCodingBundle.message("spec.workflow.validation.failed", firstError)
        }
    }
}
