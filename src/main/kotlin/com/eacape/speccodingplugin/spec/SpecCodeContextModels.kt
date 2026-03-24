package com.eacape.speccodingplugin.spec

enum class CodeContextCollectionFocus {
    CURRENT_CAPABILITIES,
    ARCHITECTURE_BOUNDARIES,
    IMPLEMENTATION_ENTRYPOINTS,
}

enum class CodeContextCandidateSignal {
    EXPLICIT_SELECTION,
    CONFIRMED_RELATED_FILE,
    VCS_CHANGE,
    WORKSPACE_CANDIDATE,
    KEY_PROJECT_FILE,
}

enum class CodeChangeSource {
    VCS_STATUS,
    WORKSPACE_CANDIDATES,
    NONE,
}

enum class CodeChangeFileStatus {
    ADDED,
    MODIFIED,
    REMOVED,
    MISSING,
    UNTRACKED,
    CONFLICTED,
    UNKNOWN,
}

data class CodeContextCollectionStrategy(
    val phase: SpecPhase,
    val focus: CodeContextCollectionFocus,
    val candidateFileBudget: Int,
    val keyPathBudget: Int,
    val includeVerificationEntryPoints: Boolean,
    val includeDiffSummary: Boolean = true,
    val includeProjectStructure: Boolean = true,
    val includeConfirmedRelatedFiles: Boolean = true,
    val includeExplicitFileHints: Boolean = true,
) {
    companion object {
        fun forPhase(phase: SpecPhase): CodeContextCollectionStrategy {
            return when (phase) {
                SpecPhase.SPECIFY -> CodeContextCollectionStrategy(
                    phase = phase,
                    focus = CodeContextCollectionFocus.CURRENT_CAPABILITIES,
                    candidateFileBudget = 6,
                    keyPathBudget = 6,
                    includeVerificationEntryPoints = false,
                )

                SpecPhase.DESIGN -> CodeContextCollectionStrategy(
                    phase = phase,
                    focus = CodeContextCollectionFocus.ARCHITECTURE_BOUNDARIES,
                    candidateFileBudget = 10,
                    keyPathBudget = 8,
                    includeVerificationEntryPoints = false,
                )

                SpecPhase.IMPLEMENT -> CodeContextCollectionStrategy(
                    phase = phase,
                    focus = CodeContextCollectionFocus.IMPLEMENTATION_ENTRYPOINTS,
                    candidateFileBudget = 14,
                    keyPathBudget = 10,
                    includeVerificationEntryPoints = true,
                )
            }
        }
    }
}

data class ProjectStructureSummary(
    val topLevelDirectories: List<String> = emptyList(),
    val topLevelFiles: List<String> = emptyList(),
    val keyPaths: List<String> = emptyList(),
    val summary: String = "",
) {
    fun hasSignals(): Boolean {
        return topLevelDirectories.isNotEmpty() ||
            topLevelFiles.isNotEmpty() ||
            keyPaths.isNotEmpty() ||
            summary.isNotBlank()
    }
}

data class CodeContextCandidateFile(
    val path: String,
    val signals: Set<CodeContextCandidateSignal> = emptySet(),
)

data class CodeChangeFile(
    val path: String,
    val status: CodeChangeFileStatus = CodeChangeFileStatus.UNKNOWN,
    val addedLineCount: Int = 0,
    val removedLineCount: Int = 0,
    val symbolChanges: List<String> = emptyList(),
    val apiChanges: List<String> = emptyList(),
)

data class CodeChangeSummary(
    val source: CodeChangeSource = CodeChangeSource.NONE,
    val files: List<CodeChangeFile> = emptyList(),
    val summary: String = "",
    val available: Boolean = false,
) {
    fun hasChanges(): Boolean = files.isNotEmpty()

    companion object {
        fun unavailable(summary: String): CodeChangeSummary {
            return CodeChangeSummary(
                source = CodeChangeSource.NONE,
                summary = summary,
                available = false,
            )
        }
    }
}

data class CodeVerificationEntryPoint(
    val commandId: String,
    val displayName: String,
    val workingDirectory: String,
    val commandPreview: String,
)

data class CodeContextPack(
    val phase: SpecPhase,
    val stageId: StageId = phase.toStageId(),
    val strategy: CodeContextCollectionStrategy = CodeContextCollectionStrategy.forPhase(phase),
    val projectStructure: ProjectStructureSummary? = null,
    val confirmedRelatedFiles: List<String> = emptyList(),
    val explicitFileHints: List<String> = emptyList(),
    val candidateFiles: List<CodeContextCandidateFile> = emptyList(),
    val changeSummary: CodeChangeSummary = CodeChangeSummary(),
    val verificationEntryPoints: List<CodeVerificationEntryPoint> = emptyList(),
    val degradationReasons: List<String> = emptyList(),
) {
    val focus: CodeContextCollectionFocus
        get() = strategy.focus

    fun hasAutoContext(): Boolean {
        return projectStructure?.hasSignals() == true ||
            confirmedRelatedFiles.isNotEmpty() ||
            explicitFileHints.isNotEmpty() ||
            candidateFiles.isNotEmpty() ||
            changeSummary.available ||
            verificationEntryPoints.isNotEmpty()
    }

    fun isDegraded(): Boolean = degradationReasons.isNotEmpty()
}
