package com.eacape.speccodingplugin.spec

internal object ArtifactDraftStateSupport {

    private val trackedPhases = SpecPhase.entries
    private val trackedStages = trackedPhases.map(SpecPhase::toStageId).toSet()

    fun initializeForStageStates(stageStates: Map<StageId, StageState>): Map<StageId, ArtifactDraftState> {
        if (stageStates.isEmpty()) {
            return emptyMap()
        }
        return trackedPhases
            .map(SpecPhase::toStageId)
            .filter { stageId -> stageStates[stageId]?.active == true }
            .associateWith { ArtifactDraftState.UNMATERIALIZED }
    }

    fun resolvePersistedStates(
        persistedStates: Map<StageId, ArtifactDraftState>,
        documents: Map<SpecPhase, SpecDocument>,
        auditEvents: List<SpecAuditEvent>,
    ): Map<StageId, ArtifactDraftState> {
        val normalizedPersisted = persistedStates
            .filterKeys(trackedStages::contains)
            .toMutableMap()
        val documentSavedPhases = auditEvents
            .asSequence()
            .filter { event -> event.eventType == SpecAuditEventType.DOCUMENT_SAVED }
            .mapNotNull { event -> event.details["phase"]?.trim() }
            .filter(String::isNotBlank)
            .toSet()

        trackedPhases.forEach { phase ->
            val stageId = phase.toStageId()
            if (normalizedPersisted.containsKey(stageId)) {
                return@forEach
            }
            normalizedPersisted[stageId] = deriveState(
                stageId = stageId,
                content = documents[phase]?.content,
                hasMaterializationAudit = documentSavedPhases.contains(phase.name),
            )
        }
        return normalizedPersisted
    }

    fun deriveState(
        stageId: StageId,
        content: String?,
        hasMaterializationAudit: Boolean,
    ): ArtifactDraftState {
        require(stageId in trackedStages) {
            "Artifact draft state is only tracked for requirements/design/tasks artifacts: $stageId"
        }
        val normalizedContent = normalizeContent(content).trim()
        if (normalizedContent.isBlank()) {
            return ArtifactDraftState.UNMATERIALIZED
        }
        if (matchesDefaultSkeleton(stageId, normalizedContent)) {
            return ArtifactDraftState.UNMATERIALIZED
        }
        if (!hasMaterializationAudit && containsTemplatePlaceholder(stageId, normalizedContent)) {
            return ArtifactDraftState.UNMATERIALIZED
        }
        return ArtifactDraftState.MATERIALIZED
    }

    fun defaultSkeletonFor(stageId: StageId): String {
        val raw = when (stageId) {
            StageId.REQUIREMENTS -> REQUIREMENTS_SKELETON
            StageId.DESIGN -> DESIGN_SKELETON
            StageId.TASKS -> TASKS_SKELETON
            StageId.VERIFY -> VERIFICATION_SKELETON
            StageId.IMPLEMENT,
            StageId.ARCHIVE,
            -> throw IllegalArgumentException("Stage $stageId has no artifact skeleton.")
        }
        return normalizeContent(raw.trimIndent())
    }

    private fun matchesDefaultSkeleton(stageId: StageId, normalizedContent: String): Boolean {
        return normalizeContent(normalizedContent) == defaultSkeletonFor(stageId)
    }

    private fun containsTemplatePlaceholder(stageId: StageId, normalizedContent: String): Boolean {
        val markers = placeholderMarkers(stageId)
        return markers.any { marker -> normalizedContent.contains(marker, ignoreCase = false) }
    }

    private fun placeholderMarkers(stageId: StageId): List<String> {
        return when (stageId) {
            StageId.REQUIREMENTS -> listOf(
                "TODO: Describe required behavior.",
                "TODO: Describe performance, security, and reliability constraints.",
                "As a <role>, I want <capability>, so that <benefit>.",
                "TODO: Add measurable acceptance criteria.",
            )
            StageId.DESIGN -> listOf(
                "TODO: Describe the architecture and module boundaries.",
                "TODO: List selected technologies and rationale.",
                "TODO: Describe key entities and relationships.",
                "TODO: Describe interfaces and contract changes.",
                "TODO: Capture performance, security, and operability choices.",
            )
            StageId.TASKS -> listOf(
                "TODO: Add implementation details.",
                "TODO: Break work into executable steps.",
                "TODO: Update related files and validation steps.",
                "TODO: Add unit and integration checks.",
            )
            StageId.VERIFY,
            StageId.IMPLEMENT,
            StageId.ARCHIVE,
            -> emptyList()
        }
    }

    private fun normalizeContent(content: String?): String {
        val normalized = content
            .orEmpty()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        return if (normalized.endsWith("\n")) normalized else "$normalized\n"
    }

    private const val REQUIREMENTS_SKELETON = """
        # Requirements Document

        ## Functional Requirements
        - [ ] TODO: Describe required behavior.

        ## Non-Functional Requirements
        - [ ] TODO: Describe performance, security, and reliability constraints.

        ## User Stories
        As a <role>, I want <capability>, so that <benefit>.

        ## Acceptance Criteria
        - [ ] TODO: Add measurable acceptance criteria.
    """

    private const val DESIGN_SKELETON = """
        # Design Document

        ## Architecture Design
        - TODO: Describe the architecture and module boundaries.

        ## Technology Stack
        - TODO: List selected technologies and rationale.

        ## Data Model
        - TODO: Describe key entities and relationships.

        ## API Design
        - TODO: Describe interfaces and contract changes.

        ## Non-Functional Design
        - TODO: Capture performance, security, and operability choices.
    """

    private const val TASKS_SKELETON = """
        # Implement Document

        ## Task List

        ### T-001: Bootstrap implementation
        ```spec-task
        status: PENDING
        priority: P0
        dependsOn: []
        relatedFiles: []
        verificationResult: null
        ```
        - [ ] TODO: Add implementation details.

        ## Implementation Steps
        1. TODO: Break work into executable steps.
        2. TODO: Update related files and validation steps.

        ## Test Plan
        - [ ] TODO: Add unit and integration checks.
    """

    private const val VERIFICATION_SKELETON = """
        # Verification Document

        ## Verification Scope
        - TODO: Describe affected tasks and files.

        ## Verification Method
        - TODO: Describe automated and manual verification approach.

        ## Commands
        ```bash
        # TODO: add verification commands
        ```

        ## Result
        conclusion: WARN
        summary: TODO
    """
}
