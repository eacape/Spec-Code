package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecTaskPlanningGroundingSupportTest {

    @Test
    fun `enrichTasksMarkdown should bind matching implementation files`() {
        val enriched = SpecTaskPlanningGroundingSupport.enrichTasksMarkdown(
            markdown = """
                ## Task List

                ### T-001: Update SpecWorkflowPanel session reuse
                ```spec-task
                status: PENDING
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```
                - [ ] Update SpecWorkflowPanel to reuse the shared workflow chat session.

                ## Implementation Steps
                1. Update the implementation and regression coverage.
            """.trimIndent(),
            codeContextPack = CodeContextPack(
                phase = SpecPhase.IMPLEMENT,
                candidateFiles = listOf(
                    CodeContextCandidateFile(
                        path = "src/main/kotlin/com/eacape/speccodingplugin/ui/spec/SpecWorkflowPanel.kt",
                        signals = setOf(CodeContextCandidateSignal.VCS_CHANGE),
                    ),
                    CodeContextCandidateFile(
                        path = "src/main/kotlin/com/eacape/speccodingplugin/session/SessionManager.kt",
                        signals = setOf(CodeContextCandidateSignal.WORKSPACE_CANDIDATE),
                    ),
                ),
                changeSummary = CodeChangeSummary(
                    source = CodeChangeSource.VCS_STATUS,
                    files = listOf(
                        CodeChangeFile(
                            path = "src/main/kotlin/com/eacape/speccodingplugin/ui/spec/SpecWorkflowPanel.kt",
                            status = CodeChangeFileStatus.MODIFIED,
                        ),
                    ),
                    summary = "Git working tree reports 1 changed file(s).",
                    available = true,
                ),
            ),
        )

        val parsed = SpecTaskMarkdownParser.parse(enriched).tasks.single()
        assertTrue(parsed.relatedFiles.contains("src/main/kotlin/com/eacape/speccodingplugin/ui/spec/SpecWorkflowPanel.kt"))
    }

    @Test
    fun `enrichTasksMarkdown should add explicit reason when no reliable file mapping exists`() {
        val enriched = SpecTaskPlanningGroundingSupport.enrichTasksMarkdown(
            markdown = """
                ## Task List

                ### T-001: Clarify rollout scope
                ```spec-task
                status: PENDING
                priority: P1
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```
                - [ ] Clarify the rollout plan before implementation.

                ## Implementation Steps
                1. Confirm the execution approach.
            """.trimIndent(),
            codeContextPack = CodeContextPack(
                phase = SpecPhase.IMPLEMENT,
                candidateFiles = listOf(
                    CodeContextCandidateFile(
                        path = "src/main/kotlin/com/eacape/speccodingplugin/ui/spec/SpecWorkflowPanel.kt",
                        signals = setOf(CodeContextCandidateSignal.WORKSPACE_CANDIDATE),
                    ),
                    CodeContextCandidateFile(
                        path = "src/main/kotlin/com/eacape/speccodingplugin/session/SessionManager.kt",
                        signals = setOf(CodeContextCandidateSignal.WORKSPACE_CANDIDATE),
                    ),
                ),
                changeSummary = CodeChangeSummary(
                    source = CodeChangeSource.WORKSPACE_CANDIDATES,
                    files = listOf(
                        CodeChangeFile(
                            path = "src/main/kotlin/com/eacape/speccodingplugin/ui/spec/SpecWorkflowPanel.kt",
                            status = CodeChangeFileStatus.UNKNOWN,
                        ),
                    ),
                    summary = "Workspace candidate files are available.",
                    available = true,
                ),
            ),
        )

        val parsed = SpecTaskMarkdownParser.parse(enriched).tasks.single()
        assertEquals(emptyList<String>(), parsed.relatedFiles)
        assertTrue(
            enriched.contains(
                SpecTaskPlanningGroundingSupport.buildRelatedFilesReasonLine(
                    "candidate files were available, but none could be matched to this task with enough confidence; confirm the affected files before execution.",
                ),
            ),
        )
    }
}
