package com.eacape.speccodingplugin.persistence

/**
 * Source-level ownership map for the repository's persistence surfaces.
 *
 * The goal is to keep storage responsibilities explicit and make any new
 * cross-store coordination visible before it leaks into UI classes.
 */
object PersistenceBoundaryCatalog {

    enum class StoreId {
        SESSION_MANAGER,
        SPEC_STORAGE,
        CHANGESET_STORE,
    }

    data class StoreBoundary(
        val id: StoreId,
        val sourceRelativePath: String,
        val summary: String,
        val persistedState: String,
        val apiMethods: Set<String>,
        val allowedCoordinators: Set<String>,
        val uiGuardrail: String,
    )

    data class CrossStoreCoordinatorRule(
        val relativePath: String,
        val stores: Set<StoreId>,
        val rationale: String,
    )

    data class UiDirectAccessRule(
        val relativePath: String,
        val stores: Set<StoreId>,
        val rationale: String,
    )

    val storeBoundaries: List<StoreBoundary> = listOf(
        StoreBoundary(
            id = StoreId.SESSION_MANAGER,
            sourceRelativePath = "src/main/kotlin/com/eacape/speccodingplugin/session/SessionManager.kt",
            summary = "Own chat sessions, message transcripts, workflow-chat bindings, branching, search, and context snapshot history.",
            persistedState = "Project-local SQLite session database tables for sessions, messages, and context snapshots.",
            apiMethods = setOf(
                "createSession",
                "renameSession",
                "updateSessionSpecTaskId",
                "updateWorkflowChatBinding",
                "clearWorkflowChatTaskBinding",
                "deleteSession",
                "getSession",
                "listSessions",
                "findReusableWorkflowChatSession",
                "addMessage",
                "listMessages",
                "searchSessions",
                "listChildSessions",
                "forkSession",
                "compareSessions",
                "saveContextSnapshot",
                "listContextSnapshots",
                "continueFromSnapshot",
            ),
            allowedCoordinators = setOf(
                "ChatPersistenceCoordinator",
                "HistoryPanel",
                "SpecTaskExecutionService",
                "SpecWorkflowPanel",
                "WorkflowChatActionRouter",
            ),
            uiGuardrail = "UI may open, browse, or bind chat sessions, but workflow artifact persistence must stay behind SpecStorage-backed application services.",
        ),
        StoreBoundary(
            id = StoreId.SPEC_STORAGE,
            sourceRelativePath = "src/main/kotlin/com/eacape/speccodingplugin/spec/SpecStorage.kt",
            summary = "Own workflow documents, metadata, audit history, workflow snapshots, baselines, and workspace bootstrap files.",
            persistedState = "Project `.spec-coding/specs` filesystem workspace with workflow.yaml, artifact files, `.history`, baselines, and audit logs.",
            apiMethods = setOf(
                "saveDocument",
                "listDocumentHistory",
                "loadDocumentSnapshot",
                "deleteDocumentSnapshot",
                "pruneDocumentHistory",
                "listWorkflowSnapshots",
                "checkWorkflowSnapshotConsistency",
                "loadWorkflowSnapshot",
                "loadWorkflowSnapshotArtifact",
                "pinDeltaBaseline",
                "listDeltaBaselines",
                "loadDeltaBaselineWorkflow",
                "loadDeltaBaselineArtifact",
                "loadConfigPinSnapshot",
                "loadDocument",
                "saveWorkflow",
                "saveWorkflowTransition",
                "saveConfigPinSnapshot",
                "loadWorkflow",
                "listWorkflowMetadata",
                "openWorkflow",
                "listWorkflowSources",
                "readWorkflowSourceText",
                "importWorkflowSource",
                "listAuditEvents",
                "appendAuditEvent",
                "listWorkflows",
                "deleteWorkflow",
                "archiveWorkflow",
                "initializeWorkspace",
                "initializeWorkflowWorkspace",
            ),
            allowedCoordinators = setOf(
                "RequirementsSectionRepairService",
                "SpecArtifactQuickFixService",
                "SpecDeltaService",
                "SpecEngine",
                "SpecStageTransitionCoordinator",
                "SpecTaskCompletionService",
                "SpecTaskExecutionService",
                "SpecTasksQuickFixService",
                "SpecVerificationService",
                "SpecWorkspaceRecoveryService",
                "WorkflowChatActionRouter",
                "WorkflowChatContextAssembler",
                "WorkflowChatExecutionContextResolver",
            ),
            uiGuardrail = "UI should reach spec persistence through SpecEngine, task services, verification services, or dedicated application coordinators instead of importing SpecStorage directly.",
        ),
        StoreBoundary(
            id = StoreId.CHANGESET_STORE,
            sourceRelativePath = "src/main/kotlin/com/eacape/speccodingplugin/rollback/ChangesetStore.kt",
            summary = "Own retained changeset history used for rollback, editor insight, and timeline affordances.",
            persistedState = "Project `.spec-coding/changesets.json` cache with bounded retained changesets.",
            apiMethods = setOf(
                "save",
                "getAll",
                "getById",
                "getRecent",
                "delete",
                "clear",
                "count",
            ),
            allowedCoordinators = setOf(
                "ChatPersistenceCoordinator",
                "ChangesetTimelinePanel",
                "EditorInsightResolver",
                "RollbackManager",
            ),
            uiGuardrail = "UI may render rollback timeline or editor hints, but should not mix rollback persistence with workflow file storage orchestration.",
        ),
    )

    val coveredApiMethods: Set<String> = storeBoundaries
        .flatMap(StoreBoundary::apiMethods)
        .toSet()

    val crossStoreCoordinators: List<CrossStoreCoordinatorRule> = listOf(
        CrossStoreCoordinatorRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/session/WorkflowChatActionRouter.kt",
            stores = setOf(StoreId.SESSION_MANAGER, StoreId.SPEC_STORAGE),
            rationale = "Workflow chat task actions need session binding updates plus workflow/task state reads from spec storage-backed services.",
        ),
        CrossStoreCoordinatorRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/spec/SpecTaskExecutionService.kt",
            stores = setOf(StoreId.SESSION_MANAGER, StoreId.SPEC_STORAGE),
            rationale = "Task execution persists workflow run state and appends conversation transcript evidence in one application service.",
        ),
        CrossStoreCoordinatorRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/persistence/ChatPersistenceCoordinator.kt",
            stores = setOf(StoreId.SESSION_MANAGER, StoreId.CHANGESET_STORE),
            rationale = "Chat persistence coordinator centralizes session restore and changeset affordances so Swing UI stops coordinating multiple stores directly.",
        ),
    )

    val uiDirectAccessRules: List<UiDirectAccessRule> = listOf(
        UiDirectAccessRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChangesetTimelinePanel.kt",
            stores = setOf(StoreId.CHANGESET_STORE),
            rationale = "Timeline UI renders retained rollback history only.",
        ),
        UiDirectAccessRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/ui/editor/EditorInsightResolver.kt",
            stores = setOf(StoreId.CHANGESET_STORE),
            rationale = "Editor insight uses recent changeset history for annotations only.",
        ),
        UiDirectAccessRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/ui/history/HistoryPanel.kt",
            stores = setOf(StoreId.SESSION_MANAGER),
            rationale = "History UI browses and resumes conversation sessions only.",
        ),
        UiDirectAccessRule(
            relativePath = "src/main/kotlin/com/eacape/speccodingplugin/ui/spec/SpecWorkflowPanel.kt",
            stores = setOf(StoreId.SESSION_MANAGER),
            rationale = "Workflow UI only resolves reusable workflow-chat session bindings.",
        ),
    )

    val forbiddenUiStores: Set<StoreId> = setOf(StoreId.SPEC_STORAGE)
}
