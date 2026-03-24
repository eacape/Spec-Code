package com.eacape.speccodingplugin.spec

internal fun CodeContextPack.renderForPrompt(): String {
    return buildString {
        appendLine("## Local Repository Code Context")
        appendLine("Use this auto-collected repository grounding to align the artifact with the real codebase.")
        appendLine("- Stage: ${stageId.name}")
        appendLine("- Focus: ${focus.renderLabel()}")
        appendLine("- Context status: ${if (hasAutoContext()) "available" else "degraded"}")

        projectStructure
            ?.takeIf(ProjectStructureSummary::hasSignals)
            ?.let { structure ->
                appendLine()
                appendLine("### Project Structure Summary")
                if (structure.summary.isNotBlank()) {
                    appendLine("- ${structure.summary}")
                }
                if (structure.keyPaths.isNotEmpty()) {
                    appendLine("- Key paths: ${structure.keyPaths.joinToString(", ")}")
                }
            }

        if (confirmedRelatedFiles.isNotEmpty()) {
            appendLine()
            appendLine("### Confirmed Related Files")
            confirmedRelatedFiles.forEach { path ->
                appendLine("- `$path`")
            }
        }

        if (candidateFiles.isNotEmpty()) {
            appendLine()
            appendLine("### Candidate Files To Reuse Or Inspect")
            candidateFiles.take(MAX_RENDERED_CANDIDATE_FILES).forEach { candidate ->
                val signalSummary = candidate.signals
                    .sortedBy { signal -> SIGNAL_ORDER[signal] ?: Int.MAX_VALUE }
                    .joinToString(", ") { signal -> signal.renderLabel() }
                appendLine("- `${candidate.path}`${if (signalSummary.isBlank()) "" else " ($signalSummary)"}")
            }
        }

        appendLine()
        appendLine("### Code Change Summary")
        appendLine("- Source: ${changeSummary.source.renderLabel()}")
        appendLine("- Availability: ${if (changeSummary.available) "available" else "unavailable"}")
        appendLine("- Summary: ${changeSummary.summary.ifBlank { "No code change summary was collected." }}")
        if (changeSummary.files.isNotEmpty()) {
            changeSummary.files.take(MAX_RENDERED_CHANGE_FILES).forEach { file ->
                appendLine("- ${file.status.renderLabel()}: `${file.path}`")
            }
        }

        if (verificationEntryPoints.isNotEmpty()) {
            appendLine()
            appendLine("### Verification Entry Points")
            verificationEntryPoints.take(MAX_RENDERED_VERIFICATION_ENTRY_POINTS).forEach { entryPoint ->
                appendLine(
                    "- `${entryPoint.commandId}`: ${entryPoint.commandPreview} " +
                        "(working dir: `${entryPoint.workingDirectory}`)",
                )
            }
        }

        if (degradationReasons.isNotEmpty()) {
            appendLine()
            appendLine("### Code Context Degradation Notes")
            degradationReasons.forEach { reason ->
                appendLine("- $reason")
            }
        }
    }.trimEnd()
}

private fun CodeContextCollectionFocus.renderLabel(): String {
    return when (this) {
        CodeContextCollectionFocus.CURRENT_CAPABILITIES -> "current capabilities, entry points, and constraints"
        CodeContextCollectionFocus.ARCHITECTURE_BOUNDARIES -> "module boundaries, extension points, and implementation constraints"
        CodeContextCollectionFocus.IMPLEMENTATION_ENTRYPOINTS -> "implementation entry points, touched files, tests, and verification paths"
    }
}

private fun CodeContextCandidateSignal.renderLabel(): String {
    return when (this) {
        CodeContextCandidateSignal.EXPLICIT_SELECTION -> "explicit hint"
        CodeContextCandidateSignal.CONFIRMED_RELATED_FILE -> "confirmed related file"
        CodeContextCandidateSignal.VCS_CHANGE -> "vcs change"
        CodeContextCandidateSignal.WORKSPACE_CANDIDATE -> "workspace candidate"
        CodeContextCandidateSignal.KEY_PROJECT_FILE -> "key project file"
    }
}

private fun CodeChangeSource.renderLabel(): String {
    return when (this) {
        CodeChangeSource.VCS_STATUS -> "git status"
        CodeChangeSource.WORKSPACE_CANDIDATES -> "workspace candidates"
        CodeChangeSource.NONE -> "none"
    }
}

private fun CodeChangeFileStatus.renderLabel(): String {
    return when (this) {
        CodeChangeFileStatus.ADDED -> "ADDED"
        CodeChangeFileStatus.MODIFIED -> "MODIFIED"
        CodeChangeFileStatus.REMOVED -> "REMOVED"
        CodeChangeFileStatus.MISSING -> "MISSING"
        CodeChangeFileStatus.UNTRACKED -> "UNTRACKED"
        CodeChangeFileStatus.CONFLICTED -> "CONFLICTED"
        CodeChangeFileStatus.UNKNOWN -> "UNKNOWN"
    }
}

private val SIGNAL_ORDER = mapOf(
    CodeContextCandidateSignal.EXPLICIT_SELECTION to 0,
    CodeContextCandidateSignal.CONFIRMED_RELATED_FILE to 1,
    CodeContextCandidateSignal.VCS_CHANGE to 2,
    CodeContextCandidateSignal.WORKSPACE_CANDIDATE to 3,
    CodeContextCandidateSignal.KEY_PROJECT_FILE to 4,
)

private const val MAX_RENDERED_CANDIDATE_FILES = 12
private const val MAX_RENDERED_CHANGE_FILES = 12
private const val MAX_RENDERED_VERIFICATION_ENTRY_POINTS = 6
