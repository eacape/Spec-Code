package com.eacape.speccodingplugin.spec

import java.nio.file.Path

enum class SpecDeltaStatus {
    ADDED,
    MODIFIED,
    REMOVED,
    UNCHANGED,
}

enum class SpecDeltaArtifact(
    val displayName: String,
    val fileName: String,
    val phase: SpecPhase?,
    val stageId: StageId,
) {
    REQUIREMENTS(
        displayName = "Requirements",
        fileName = "requirements.md",
        phase = SpecPhase.SPECIFY,
        stageId = StageId.REQUIREMENTS,
    ),
    DESIGN(
        displayName = "Design",
        fileName = "design.md",
        phase = SpecPhase.DESIGN,
        stageId = StageId.DESIGN,
    ),
    TASKS(
        displayName = "Tasks",
        fileName = "tasks.md",
        phase = SpecPhase.IMPLEMENT,
        stageId = StageId.TASKS,
    ),
    VERIFICATION(
        displayName = "Verification",
        fileName = "verification.md",
        phase = null,
        stageId = StageId.VERIFY,
    ),
}

enum class SpecDeltaBaselineKind {
    WORKFLOW,
    SNAPSHOT,
    PINNED_BASELINE,
}

data class SpecDeltaComparisonContext(
    val baselineKind: SpecDeltaBaselineKind,
    val baselineWorkflowId: String,
    val targetWorkflowId: String,
    val snapshotId: String? = null,
    val baselineId: String? = null,
)

enum class SpecDeltaExportFormat(
    val fileExtension: String,
    val mediaType: String,
) {
    MARKDOWN(
        fileExtension = "md",
        mediaType = "text/markdown",
    ),
    HTML(
        fileExtension = "html",
        mediaType = "text/html",
    ),
}

data class SpecArtifactDelta(
    val artifact: SpecDeltaArtifact,
    val status: SpecDeltaStatus,
    val baselineContent: String?,
    val targetContent: String?,
    val baselineDocument: SpecDocument? = null,
    val targetDocument: SpecDocument? = null,
    val addedLineCount: Int = 0,
    val removedLineCount: Int = 0,
    val unifiedDiff: String = "",
)

data class SpecPhaseDelta(
    val phase: SpecPhase,
    val status: SpecDeltaStatus,
    val baselineDocument: SpecDocument?,
    val targetDocument: SpecDocument?,
    val addedLineCount: Int = 0,
    val removedLineCount: Int = 0,
    val unifiedDiff: String = "",
)

data class SpecTaskDelta(
    val taskId: String,
    val title: String,
    val baselineTask: StructuredTask?,
    val targetTask: StructuredTask?,
    val changedFields: List<String>,
) {
    val status: SpecDeltaStatus
        get() = when {
            baselineTask == null && targetTask != null -> SpecDeltaStatus.ADDED
            baselineTask != null && targetTask == null -> SpecDeltaStatus.REMOVED
            changedFields.isNotEmpty() -> SpecDeltaStatus.MODIFIED
            else -> SpecDeltaStatus.UNCHANGED
        }
}

data class SpecTaskDeltaSummary(
    val changes: List<SpecTaskDelta> = emptyList(),
    val addedTaskIds: List<String> = emptyList(),
    val removedTaskIds: List<String> = emptyList(),
    val completedTaskIds: List<String> = emptyList(),
    val cancelledTaskIds: List<String> = emptyList(),
    val statusChangedTaskIds: List<String> = emptyList(),
    val metadataChangedTaskIds: List<String> = emptyList(),
) {
    fun count(status: SpecDeltaStatus): Int {
        return changes.count { change -> change.status == status }
    }

    fun hasChanges(): Boolean = changes.isNotEmpty()
}

data class SpecRelatedFileDelta(
    val path: String,
    val status: SpecDeltaStatus,
    val baselineTaskIds: List<String> = emptyList(),
    val targetTaskIds: List<String> = emptyList(),
    val presentInWorkspace: Boolean = false,
)

data class SpecRelatedFilesDeltaSummary(
    val files: List<SpecRelatedFileDelta> = emptyList(),
    val workspaceCandidateFiles: List<String> = emptyList(),
) {
    fun count(status: SpecDeltaStatus): Int {
        return files.count { file -> file.status == status }
    }

    fun hasChanges(): Boolean {
        return files.any { file -> file.status != SpecDeltaStatus.UNCHANGED }
    }
}

data class SpecCodeFileDelta(
    val path: String,
    val status: SpecDeltaStatus,
    val workspaceStatus: CodeChangeFileStatus? = null,
    val baselineTaskIds: List<String> = emptyList(),
    val targetTaskIds: List<String> = emptyList(),
    val presentInWorkspaceDiff: Boolean = false,
    val presentInWorkspaceCandidates: Boolean = false,
    val addedLineCount: Int = 0,
    val removedLineCount: Int = 0,
    val symbolChanges: List<String> = emptyList(),
    val apiChanges: List<String> = emptyList(),
)

data class SpecCodeChangesDeltaSummary(
    val source: CodeChangeSource = CodeChangeSource.NONE,
    val summary: String = "",
    val files: List<SpecCodeFileDelta> = emptyList(),
    val workspaceCandidateFiles: List<String> = emptyList(),
    val degradationReasons: List<String> = emptyList(),
) {
    fun count(status: SpecDeltaStatus): Int {
        return files.count { file -> file.status == status }
    }

    fun hasChanges(): Boolean {
        return files.any { file -> file.status != SpecDeltaStatus.UNCHANGED }
    }
}

data class SpecVerificationArtifactSummary(
    val documentAvailable: Boolean = false,
    val conclusion: VerificationConclusion? = null,
    val runId: String? = null,
    val executedAt: String? = null,
    val summary: String = "",
)

data class SpecTaskVerificationDelta(
    val taskId: String,
    val baselineResult: TaskVerificationResult?,
    val targetResult: TaskVerificationResult?,
) {
    val status: SpecDeltaStatus
        get() = when {
            baselineResult == null && targetResult != null -> SpecDeltaStatus.ADDED
            baselineResult != null && targetResult == null -> SpecDeltaStatus.REMOVED
            baselineResult != targetResult -> SpecDeltaStatus.MODIFIED
            else -> SpecDeltaStatus.UNCHANGED
        }
}

data class SpecVerificationDeltaSummary(
    val baselineArtifact: SpecVerificationArtifactSummary = SpecVerificationArtifactSummary(),
    val targetArtifact: SpecVerificationArtifactSummary = SpecVerificationArtifactSummary(),
    val taskResultChanges: List<SpecTaskVerificationDelta> = emptyList(),
) {
    val artifactChanged: Boolean
        get() = baselineArtifact != targetArtifact

    fun hasChanges(): Boolean {
        return artifactChanged || taskResultChanges.any { change -> change.status != SpecDeltaStatus.UNCHANGED }
    }
}

data class SpecWorkflowDelta(
    val baselineWorkflowId: String,
    val targetWorkflowId: String,
    val phaseDeltas: List<SpecPhaseDelta>,
    val artifactDeltas: List<SpecArtifactDelta> = emptyList(),
    val taskSummary: SpecTaskDeltaSummary = SpecTaskDeltaSummary(),
    val codeChangesSummary: SpecCodeChangesDeltaSummary = SpecCodeChangesDeltaSummary(),
    val relatedFilesSummary: SpecRelatedFilesDeltaSummary = SpecRelatedFilesDeltaSummary(),
    val verificationSummary: SpecVerificationDeltaSummary = SpecVerificationDeltaSummary(),
    val comparisonContext: SpecDeltaComparisonContext? = null,
) {
    private val effectiveArtifactDeltas: List<SpecArtifactDelta>
        get() = if (artifactDeltas.isNotEmpty()) {
            artifactDeltas
        } else {
            phaseDeltas.map(SpecPhaseDelta::toArtifactDelta)
        }

    fun count(status: SpecDeltaStatus): Int {
        return effectiveArtifactDeltas.count { artifactDelta -> artifactDelta.status == status }
    }

    fun hasChanges(): Boolean {
        return effectiveArtifactDeltas.any { artifactDelta -> artifactDelta.status != SpecDeltaStatus.UNCHANGED } ||
            taskSummary.hasChanges() ||
            codeChangesSummary.hasChanges() ||
            relatedFilesSummary.hasChanges() ||
            verificationSummary.hasChanges()
    }
}

data class SpecDeltaExportResult(
    val workflowId: String,
    val format: SpecDeltaExportFormat,
    val fileName: String,
    val filePath: Path,
)

private fun SpecPhaseDelta.toArtifactDelta(): SpecArtifactDelta {
    return SpecArtifactDelta(
        artifact = when (phase) {
            SpecPhase.SPECIFY -> SpecDeltaArtifact.REQUIREMENTS
            SpecPhase.DESIGN -> SpecDeltaArtifact.DESIGN
            SpecPhase.IMPLEMENT -> SpecDeltaArtifact.TASKS
        },
        status = status,
        baselineContent = baselineDocument?.content,
        targetContent = targetDocument?.content,
        baselineDocument = baselineDocument,
        targetDocument = targetDocument,
        addedLineCount = addedLineCount,
        removedLineCount = removedLineCount,
        unifiedDiff = unifiedDiff,
    )
}
