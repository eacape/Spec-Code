package com.eacape.speccodingplugin.spec

/**
 * Source-level extraction map for [SpecEngine].
 *
 * This keeps the current orchestration seams explicit so later refactors can
 * move logic out of SpecEngine without first reverse-engineering a 2k+ line file.
 */
object SpecEngineResponsibilityCatalog {

    enum class Priority {
        P0,
        P1,
    }

    enum class ResponsibilityId {
        WORKFLOW_LIFECYCLE,
        ARTIFACT_GENERATION,
        STAGE_TRANSITION_PREFLIGHT,
        TEMPLATE_SWITCH_AND_CLONE,
        HISTORY_AND_RECOVERY,
    }

    data class ResponsibilitySlice(
        val id: ResponsibilityId,
        val summary: String,
        val plannedTarget: String,
        val currentCollaborators: Set<String>,
        val apiMethods: Set<String>,
        val migrationGuardrail: String,
        val priority: Priority,
    )

    val slices: List<ResponsibilitySlice> = listOf(
        ResponsibilitySlice(
            id = ResponsibilityId.WORKFLOW_LIFECYCLE,
            summary = "Create/load/update/pause/resume/archive workflows and keep active cache + metadata pinning consistent.",
            plannedTarget = "SpecWorkflowLifecycleService",
            currentCollaborators = setOf(
                "SpecProjectConfigService",
                "SpecStorage",
                "SpecArtifactService",
            ),
            apiMethods = setOf(
                "createWorkflow",
                "loadWorkflow",
                "reloadWorkflow",
                "listWorkflows",
                "listWorkflowMetadata",
                "openWorkflow",
                "updateWorkflowMetadata",
                "pauseWorkflow",
                "resumeWorkflow",
                "deleteWorkflow",
                "archiveWorkflow",
            ),
            migrationGuardrail = "Keep activeWorkflows cache, config pin snapshots, and workflow metadata writes in the same extraction step.",
            priority = Priority.P0,
        ),
        ResponsibilitySlice(
            id = ResponsibilityId.ARTIFACT_GENERATION,
            summary = "Prepare generation context, manage clarification retries, and persist artifact/source updates for the current workflow phase.",
            plannedTarget = "SpecArtifactGenerationCoordinator",
            currentCollaborators = setOf(
                "SpecGenerator",
                "SpecCodeContextService",
                "SpecArtifactService",
                "SpecStorage",
            ),
            apiMethods = setOf(
                "listWorkflowSources",
                "importWorkflowSource",
                "generateCurrentPhase",
                "draftCurrentPhaseClarification",
                "updateDocumentContent",
                "saveClarificationRetryState",
            ),
            migrationGuardrail = "Do not split request preparation away from artifact writeback until workflow source usage audit stays in one place.",
            priority = Priority.P0,
        ),
        ResponsibilitySlice(
            id = ResponsibilityId.STAGE_TRANSITION_PREFLIGHT,
            summary = "Own stage advance/rollback preview, gate preflight, warning confirmation, and completion readiness checks.",
            plannedTarget = "SpecStageTransitionCoordinator",
            currentCollaborators = setOf(
                "SpecGateRuleEngine",
                "SpecProjectConfigService",
                "SpecImplementStageReadiness",
                "SpecStorage",
                "HookManager",
            ),
            apiMethods = setOf(
                "advanceWorkflow",
                "jumpToStage",
                "rollbackToStage",
                "proceedToNextPhase",
                "goBackToPreviousPhase",
                "previewStageTransition",
                "completeWorkflow",
            ),
            migrationGuardrail = "Keep gate evaluation, warning confirmation audit, and stage metadata persistence together to avoid split-brain transition semantics.",
            priority = Priority.P0,
        ),
        ResponsibilitySlice(
            id = ResponsibilityId.TEMPLATE_SWITCH_AND_CLONE,
            summary = "Preview template changes, clone workflows across templates, and own template-switch audit/history semantics.",
            plannedTarget = "SpecTemplateMutationService",
            currentCollaborators = setOf(
                "SpecProjectConfigService",
                "SpecArtifactService",
                "SpecStorage",
            ),
            apiMethods = setOf(
                "previewTemplateSwitch",
                "cloneWorkflowWithTemplate",
                "applyTemplateSwitch",
                "rollbackTemplateSwitch",
                "listTemplateSwitchHistory",
            ),
            migrationGuardrail = "Keep preview cache, artifact impact inspection, and switch history parsing together until real apply/rollback exits the locked state.",
            priority = Priority.P1,
        ),
        ResponsibilitySlice(
            id = ResponsibilityId.HISTORY_AND_RECOVERY,
            summary = "Expose document/workflow snapshots, delta baselines, and load paths needed for recovery-style reopen or comparison flows.",
            plannedTarget = "SpecWorkflowHistoryService",
            currentCollaborators = setOf(
                "SpecStorage",
                "SpecHistoryModels",
            ),
            apiMethods = setOf(
                "listDocumentHistory",
                "loadDocumentSnapshot",
                "deleteDocumentSnapshot",
                "pruneDocumentHistory",
                "listWorkflowSnapshots",
                "loadWorkflowSnapshot",
                "pinDeltaBaseline",
                "listDeltaBaselines",
                "loadDeltaBaselineWorkflow",
            ),
            migrationGuardrail = "Keep snapshot and delta-baseline reads behind one facade so UI code does not learn storage layout details.",
            priority = Priority.P1,
        ),
    )

    val coveredApiMethods: Set<String> = slices
        .flatMap(ResponsibilitySlice::apiMethods)
        .toSet()

    fun responsibilityFor(apiMethod: String): ResponsibilitySlice? {
        return slices.firstOrNull { apiMethod in it.apiMethods }
    }
}
