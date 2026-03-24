package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecDeltaServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage
    private lateinit var artifactService: SpecArtifactService
    private lateinit var specEngine: SpecEngine
    private lateinit var deltaService: SpecDeltaService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
        artifactService = SpecArtifactService(project)
        specEngine = SpecEngine(project, storage) { SpecGenerationResult.Failure("unused") }
        deltaService = SpecDeltaService(
            project = project,
            specEngine = specEngine,
            storage = storage,
            artifactService = artifactService,
            workspaceCandidateFilesProvider = { listOf("src/test/kotlin/SpecDeltaServiceTest.kt") },
            codeChangeSummaryProvider = {
                CodeChangeSummary(
                    source = CodeChangeSource.VCS_STATUS,
                    available = true,
                    summary = "Git working tree reports 2 changed file(s).",
                    files = listOf(
                        CodeChangeFile(
                            path = "src/main/kotlin/Regression.kt",
                            status = CodeChangeFileStatus.MODIFIED,
                            addedLineCount = 9,
                            removedLineCount = 2,
                            symbolChanges = listOf("+ fun verifyRegression()"),
                            apiChanges = listOf("+ public fun verifyRegression()"),
                        ),
                        CodeChangeFile(
                            path = "src/test/kotlin/SpecDeltaServiceTest.kt",
                            status = CodeChangeFileStatus.MODIFIED,
                            addedLineCount = 4,
                            removedLineCount = 0,
                        ),
                    ),
                )
            },
        )
    }

    @Test
    fun `compareByDeltaBaseline should load verification artifact from pinned snapshot and audit selection`() {
        val workflowId = "wf-delta-service"
        storage.saveWorkflow(workflow(workflowId)).getOrThrow()
        storage.saveDocument(
            workflowId = workflowId,
            document = document(
                phase = SpecPhase.IMPLEMENT,
                content = tasksMarkdown(
                    """
                    ### T-001: Ship baseline
                    ```spec-task
                    status: IN_PROGRESS
                    priority: P0
                    dependsOn: []
                    relatedFiles:
                      - src/main/kotlin/Baseline.kt
                    verificationResult:
                      conclusion: PASS
                      runId: verify-run-1
                      summary: baseline pass
                      at: "2026-03-10T09:00:00Z"
                    ```
                    """.trimIndent(),
                ),
            ),
        ).getOrThrow()
        artifactService.writeArtifact(
            workflowId = workflowId,
            stageId = StageId.VERIFY,
            content = verificationMarkdown(
                conclusion = "PASS",
                runId = "verify-run-1",
                summary = "baseline pass",
                executedAt = "2026-03-10T09:00:00Z",
            ),
        )
        storage.saveWorkflow(
            storage.loadWorkflow(workflowId).getOrThrow().copy(updatedAt = 5L, verifyEnabled = true),
        ).getOrThrow()

        val baselineSnapshot = storage.listWorkflowSnapshots(workflowId)
            .first { it.trigger == SpecSnapshotTrigger.WORKFLOW_SAVE_AFTER && it.files.contains("verification.md") }
        val baselineRef = storage.pinDeltaBaseline(
            workflowId = workflowId,
            snapshotId = baselineSnapshot.snapshotId,
            label = "before-verify-regression",
        ).getOrThrow()

        storage.saveDocument(
            workflowId = workflowId,
            document = document(
                phase = SpecPhase.IMPLEMENT,
                content = tasksMarkdown(
                    """
                    ### T-001: Ship baseline
                    ```spec-task
                    status: COMPLETED
                    priority: P0
                    dependsOn: []
                    relatedFiles:
                      - src/main/kotlin/Baseline.kt
                      - src/main/kotlin/Regression.kt
                    verificationResult:
                      conclusion: FAIL
                      runId: verify-run-2
                      summary: target failed
                      at: "2026-03-11T10:00:00Z"
                    ```

                    ### T-002: Add regression coverage
                    ```spec-task
                    status: PENDING
                    priority: P1
                    dependsOn:
                      - T-001
                    relatedFiles:
                      - src/test/kotlin/SpecDeltaServiceTest.kt
                    verificationResult: null
                    ```
                    """.trimIndent(),
                ),
            ),
        ).getOrThrow()
        artifactService.writeArtifact(
            workflowId = workflowId,
            stageId = StageId.VERIFY,
            content = verificationMarkdown(
                conclusion = "FAIL",
                runId = "verify-run-2",
                summary = "target failed",
                executedAt = "2026-03-11T10:00:00Z",
            ),
        )

        val delta = deltaService.compareByDeltaBaseline(
            workflowId = workflowId,
            baselineId = baselineRef.baselineId,
        ).getOrThrow()

        val verificationArtifact = delta.artifactDeltas.first { artifact ->
            artifact.artifact == SpecDeltaArtifact.VERIFICATION
        }
        assertEquals(SpecDeltaStatus.MODIFIED, verificationArtifact.status)
        assertTrue(verificationArtifact.unifiedDiff.contains("+ conclusion: FAIL"))
        assertEquals(listOf("T-002"), delta.taskSummary.addedTaskIds)
        assertEquals(listOf("T-001"), delta.taskSummary.completedTaskIds)
        assertTrue(
            delta.relatedFilesSummary.workspaceCandidateFiles.contains("src/test/kotlin/SpecDeltaServiceTest.kt"),
        )
        assertEquals(VerificationConclusion.FAIL, delta.verificationSummary.targetArtifact.conclusion)

        val auditEvents = storage.listAuditEvents(workflowId).getOrThrow()
            .filter { event -> event.eventType == SpecAuditEventType.DELTA_BASELINE_SELECTED }
        val compareEvent = auditEvents.last()
        assertEquals("COMPARE", compareEvent.details["action"])
        assertEquals("PINNED_BASELINE", compareEvent.details["baselineKind"])
        assertEquals(baselineRef.baselineId, compareEvent.details["baselineId"])
    }

    @Test
    fun `exportReport should generate stable markdown and html files with audit trail`() {
        val baselineWorkflowId = "wf-export-base"
        val targetWorkflowId = "wf-export-target"
        storage.saveWorkflow(workflow(baselineWorkflowId)).getOrThrow()
        storage.saveWorkflow(workflow(targetWorkflowId)).getOrThrow()

        storage.saveDocument(
            workflowId = baselineWorkflowId,
            document = document(
                phase = SpecPhase.IMPLEMENT,
                content = tasksMarkdown(
                    """
                    ### T-001: Keep baseline stable
                    ```spec-task
                    status: IN_PROGRESS
                    priority: P0
                    dependsOn: []
                    relatedFiles:
                      - src/main/kotlin/Baseline.kt
                    verificationResult:
                      conclusion: PASS
                      runId: verify-run-1
                      summary: baseline pass
                      at: "2026-03-10T09:00:00Z"
                    ```
                    """.trimIndent(),
                ),
            ),
        ).getOrThrow()
        storage.saveDocument(
            workflowId = targetWorkflowId,
            document = document(
                phase = SpecPhase.IMPLEMENT,
                content = tasksMarkdown(
                    """
                    ### T-001: Keep baseline stable
                    ```spec-task
                    status: COMPLETED
                    priority: P0
                    dependsOn: []
                    relatedFiles:
                      - src/main/kotlin/Baseline.kt
                      - src/main/kotlin/Exported.kt
                    verificationResult:
                      conclusion: FAIL
                      runId: verify-run-2
                      summary: target failed
                      at: "2026-03-11T10:00:00Z"
                    ```

                    ### T-002: Export delta report
                    ```spec-task
                    status: PENDING
                    priority: P1
                    dependsOn:
                      - T-001
                    relatedFiles:
                      - src/test/kotlin/com/eacape/speccodingplugin/spec/SpecDeltaServiceTest.kt
                    verificationResult: null
                    ```
                    """.trimIndent(),
                ),
            ),
        ).getOrThrow()
        artifactService.writeArtifact(
            workflowId = baselineWorkflowId,
            stageId = StageId.VERIFY,
            content = verificationMarkdown(
                conclusion = "PASS",
                runId = "verify-run-1",
                summary = "baseline pass",
                executedAt = "2026-03-10T09:00:00Z",
            ),
        )
        artifactService.writeArtifact(
            workflowId = targetWorkflowId,
            stageId = StageId.VERIFY,
            content = verificationMarkdown(
                conclusion = "FAIL",
                runId = "verify-run-2",
                summary = "target failed",
                executedAt = "2026-03-11T10:00:00Z",
            ),
        )

        val delta = deltaService.compareByWorkflowId(
            baselineWorkflowId = baselineWorkflowId,
            targetWorkflowId = targetWorkflowId,
        ).getOrThrow()

        val markdown = deltaService.exportMarkdown(delta)
        val html = deltaService.exportHtml(delta)

        assertTrue(markdown.startsWith("---\n"))
        assertTrue(markdown.contains("reportType: spec-delta"))
        assertTrue(markdown.contains("## Artifact Diffs"))
        assertTrue(markdown.contains("```diff"))
        assertTrue(markdown.contains("## Task Changes"))
        assertTrue(markdown.contains("## Code Changes Summary"))
        assertTrue(markdown.contains("symbols: + fun verifyRegression()"))
        assertTrue(markdown.contains("## Related Files Summary"))
        assertTrue(markdown.contains("## Verification Summary"))
        assertTrue(markdown.contains("+++ target/verification.md"))
        assertEquals(markdown, deltaService.exportMarkdown(delta))

        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("<h2>Replay Metadata</h2>"))
        assertTrue(html.contains("<h2>Artifact Diffs</h2>"))
        assertTrue(html.contains("<h2>Code Changes Summary</h2>"))
        assertTrue(html.contains("verifyRegression"))
        assertTrue(html.contains("&lt;!DOCTYPE html&gt;").not())
        assertTrue(html.contains("+++ target/verification.md"))
        assertEquals(html, deltaService.exportHtml(delta))

        val markdownExport = deltaService.exportReport(delta, SpecDeltaExportFormat.MARKDOWN).getOrThrow()
        val htmlExport = deltaService.exportReport(delta, SpecDeltaExportFormat.HTML).getOrThrow()

        assertEquals(
            "spec-delta-wf-export-target-workflow-wf-export-base.md",
            markdownExport.fileName,
        )
        assertEquals(
            "spec-delta-wf-export-target-workflow-wf-export-base.html",
            htmlExport.fileName,
        )
        assertTrue(Files.exists(markdownExport.filePath))
        assertTrue(Files.exists(htmlExport.filePath))
        assertEquals(markdown, Files.readString(markdownExport.filePath))
        assertEquals(html, Files.readString(htmlExport.filePath))

        val exportEvents = storage.listAuditEvents(targetWorkflowId).getOrThrow()
            .filter { event -> event.eventType == SpecAuditEventType.DELTA_EXPORTED }
        assertEquals(2, exportEvents.size)
        assertEquals("MARKDOWN", exportEvents.first().details["format"])
        assertEquals("HTML", exportEvents.last().details["format"])
        assertEquals(".spec-coding/exports/delta/spec-delta-wf-export-target-workflow-wf-export-base.html", exportEvents.last().details["file"])
    }

    private fun workflow(id: String): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = id,
            description = "delta-service-test",
            verifyEnabled = true,
            currentStage = StageId.VERIFY,
        )
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
                description = "delta-service-test",
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
