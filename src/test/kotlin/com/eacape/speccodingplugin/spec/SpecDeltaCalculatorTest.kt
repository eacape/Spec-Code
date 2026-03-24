package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDeltaCalculatorTest {

    @Test
    fun `compareWorkflows should mark added modified removed and unchanged`() {
        val baseline = SpecWorkflow(
            id = "wf-baseline",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirement-v1",
                ),
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = "design-v1",
                ),
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = "tasks-v1",
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "baseline",
            description = "baseline",
            createdAt = 1L,
            updatedAt = 2L,
        )

        val target = SpecWorkflow(
            id = "wf-target",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirement-v1",
                ),
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = "design-v2",
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "target",
            description = "target",
            createdAt = 3L,
            updatedAt = 4L,
        )

        val delta = SpecDeltaCalculator.compareWorkflows(
            baselineWorkflow = baseline,
            targetWorkflow = target,
        )

        assertEquals("wf-baseline", delta.baselineWorkflowId)
        assertEquals("wf-target", delta.targetWorkflowId)
        assertEquals(SpecDeltaStatus.UNCHANGED, delta.phaseDeltas.first { it.phase == SpecPhase.SPECIFY }.status)
        assertEquals(SpecDeltaStatus.MODIFIED, delta.phaseDeltas.first { it.phase == SpecPhase.DESIGN }.status)
        assertEquals(SpecDeltaStatus.REMOVED, delta.phaseDeltas.first { it.phase == SpecPhase.IMPLEMENT }.status)
        assertEquals(0, delta.count(SpecDeltaStatus.ADDED))
        assertEquals(1, delta.count(SpecDeltaStatus.MODIFIED))
        assertEquals(1, delta.count(SpecDeltaStatus.REMOVED))
        assertEquals(1, delta.count(SpecDeltaStatus.UNCHANGED))
        assertTrue(delta.hasChanges())
    }

    @Test
    fun `compareWorkflows should include verification artifact task summary and related files summary`() {
        val baseline = SpecWorkflow(
            id = "wf-baseline",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = tasksMarkdown(
                        """
                        ### T-001: Keep API stable
                        ```spec-task
                        status: IN_PROGRESS
                        priority: P0
                        dependsOn: []
                        relatedFiles:
                          - src/main/kotlin/A.kt
                        verificationResult:
                          conclusion: PASS
                          runId: verify-run-1
                          summary: baseline pass
                          at: "2026-03-10T09:00:00Z"
                        ```

                        ### T-002: Remove legacy code
                        ```spec-task
                        status: PENDING
                        priority: P1
                        dependsOn:
                          - T-001
                        relatedFiles:
                          - src/main/kotlin/Legacy.kt
                        verificationResult: null
                        ```
                        """.trimIndent(),
                    ),
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "baseline",
            description = "baseline",
            verifyEnabled = true,
            createdAt = 1L,
            updatedAt = 2L,
        )

        val target = SpecWorkflow(
            id = "wf-target",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = tasksMarkdown(
                        """
                        ### T-001: Keep API stable
                        ```spec-task
                        status: COMPLETED
                        priority: P0
                        dependsOn: []
                        relatedFiles:
                          - src/main/kotlin/A.kt
                          - src/main/kotlin/B.kt
                        verificationResult:
                          conclusion: FAIL
                          runId: verify-run-2
                          summary: target failed
                          at: "2026-03-11T10:00:00Z"
                        ```

                        ### T-003: Add new flow
                        ```spec-task
                        status: PENDING
                        priority: P1
                        dependsOn:
                          - T-001
                        relatedFiles:
                          - src/main/kotlin/NewFlow.kt
                        verificationResult: null
                        ```
                        """.trimIndent(),
                    ),
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "target",
            description = "target",
            verifyEnabled = true,
            createdAt = 3L,
            updatedAt = 4L,
        )

        val delta = SpecDeltaCalculator.compareWorkflows(
            baselineWorkflow = baseline,
            targetWorkflow = target,
            baselineVerificationContent = verificationMarkdown(
                conclusion = "PASS",
                runId = "verify-run-1",
                summary = "baseline pass",
                executedAt = "2026-03-10T09:00:00Z",
            ),
            targetVerificationContent = verificationMarkdown(
                conclusion = "FAIL",
                runId = "verify-run-2",
                summary = "target failed",
                executedAt = "2026-03-11T10:00:00Z",
            ),
            workspaceCandidateFiles = listOf(
                "src/main/kotlin/B.kt",
                "src/test/kotlin/SpecDeltaCalculatorTest.kt",
            ),
            codeChangeSummary = CodeChangeSummary(
                source = CodeChangeSource.VCS_STATUS,
                available = true,
                summary = "Git working tree reports 2 changed file(s).",
                files = listOf(
                    CodeChangeFile(
                        path = "src/main/kotlin/B.kt",
                        status = CodeChangeFileStatus.MODIFIED,
                        addedLineCount = 12,
                        removedLineCount = 3,
                        symbolChanges = listOf("+ fun runNewFlow()"),
                        apiChanges = listOf("+ public fun runNewFlow()"),
                    ),
                    CodeChangeFile(
                        path = "src/main/kotlin/NewFlow.kt",
                        status = CodeChangeFileStatus.ADDED,
                        addedLineCount = 18,
                        removedLineCount = 0,
                        symbolChanges = listOf("+ class NewFlow"),
                    ),
                ),
            ),
        )
        val verificationArtifact = delta.artifactDeltas.first { artifact ->
            artifact.artifact == SpecDeltaArtifact.VERIFICATION
        }
        assertEquals(SpecDeltaStatus.MODIFIED, verificationArtifact.status)
        assertTrue(verificationArtifact.unifiedDiff.contains("+ conclusion: FAIL"))

        assertEquals(listOf("T-003"), delta.taskSummary.addedTaskIds)
        assertEquals(listOf("T-002"), delta.taskSummary.removedTaskIds)
        assertEquals(listOf("T-001"), delta.taskSummary.completedTaskIds)
        assertEquals(listOf("T-001"), delta.taskSummary.statusChangedTaskIds)
        assertEquals(listOf("T-001"), delta.taskSummary.metadataChangedTaskIds)

        val relatedA = delta.relatedFilesSummary.files.first { file -> file.path == "src/main/kotlin/A.kt" }
        val relatedB = delta.relatedFilesSummary.files.first { file -> file.path == "src/main/kotlin/B.kt" }
        val relatedLegacy = delta.relatedFilesSummary.files.first { file -> file.path == "src/main/kotlin/Legacy.kt" }
        assertEquals(SpecDeltaStatus.UNCHANGED, relatedA.status)
        assertEquals(SpecDeltaStatus.ADDED, relatedB.status)
        assertEquals(SpecDeltaStatus.REMOVED, relatedLegacy.status)
        assertTrue(delta.relatedFilesSummary.workspaceCandidateFiles.contains("src/test/kotlin/SpecDeltaCalculatorTest.kt"))

        val codeChangeB = delta.codeChangesSummary.files.first { file -> file.path == "src/main/kotlin/B.kt" }
        val codeChangeNewFlow = delta.codeChangesSummary.files.first { file -> file.path == "src/main/kotlin/NewFlow.kt" }
        assertEquals(CodeChangeSource.VCS_STATUS, delta.codeChangesSummary.source)
        assertEquals(SpecDeltaStatus.MODIFIED, codeChangeB.status)
        assertEquals(12, codeChangeB.addedLineCount)
        assertTrue(codeChangeB.symbolChanges.contains("+ fun runNewFlow()"))
        assertEquals(SpecDeltaStatus.ADDED, codeChangeNewFlow.status)
        assertTrue(delta.codeChangesSummary.workspaceCandidateFiles.contains("src/test/kotlin/SpecDeltaCalculatorTest.kt"))

        assertEquals(VerificationConclusion.PASS, delta.verificationSummary.baselineArtifact.conclusion)
        assertEquals(VerificationConclusion.FAIL, delta.verificationSummary.targetArtifact.conclusion)
        assertEquals("verify-run-2", delta.verificationSummary.targetArtifact.runId)
        assertEquals(1, delta.verificationSummary.taskResultChanges.size)
        assertEquals("T-001", delta.verificationSummary.taskResultChanges.first().taskId)
    }

    @Test
    fun `compareWorkflows should treat normalized content as unchanged`() {
        val baseline = SpecWorkflow(
            id = "wf-a",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "line-1\r\nline-2\r\n",
                )
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "A",
            description = "A",
            createdAt = 1L,
            updatedAt = 2L,
        )

        val target = SpecWorkflow(
            id = "wf-b",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "line-1\nline-2",
                ),
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = "new-design",
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "B",
            description = "B",
            createdAt = 1L,
            updatedAt = 2L,
        )

        val delta = SpecDeltaCalculator.compareWorkflows(
            baselineWorkflow = baseline,
            targetWorkflow = target,
        )

        assertEquals(SpecDeltaStatus.UNCHANGED, delta.phaseDeltas.first { it.phase == SpecPhase.SPECIFY }.status)
        assertEquals(SpecDeltaStatus.ADDED, delta.phaseDeltas.first { it.phase == SpecPhase.DESIGN }.status)
        assertEquals(1, delta.count(SpecDeltaStatus.ADDED))
        assertFalse(delta.phaseDeltas.isEmpty())
    }

    private fun document(
        phase: SpecPhase,
        content: String,
    ): SpecDocument {
        return SpecDocument(
            id = "doc-${phase.name.lowercase()}",
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = phase.displayName,
                description = "test",
            ),
            validationResult = ValidationResult(valid = true),
        )
    }

    private fun tasksMarkdown(content: String): String {
        return "# Implement Document\n\n$content\n"
    }

    private fun verificationMarkdown(
        conclusion: String,
        runId: String,
        summary: String,
        executedAt: String,
    ): String {
        return """
            # Verification Document

            ## Verification Scope
            - Workflow: `wf`

            ## Result
            conclusion: $conclusion
            runId: $runId
            at: "$executedAt"
            summary: $summary
        """.trimIndent()
    }
}
