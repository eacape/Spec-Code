package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.engine.CliToolInfo
import com.eacape.speccodingplugin.spec.SpecArtifactService
import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecGenerationResult
import com.eacape.speccodingplugin.spec.SpecStorage
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadiness
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessInput
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
import java.util.ArrayDeque

class SpecWorkflowCreateCoordinatorAcceptanceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var trackingStore: SpecWorkflowFirstRunTrackingStore
    private lateinit var artifactService: SpecArtifactService
    private lateinit var engine: SpecEngine

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        trackingStore = SpecWorkflowFirstRunTrackingStore()
        artifactService = SpecArtifactService(project)
        engine = SpecEngine(project, SpecStorage.getInstance(project)) { SpecGenerationResult.Failure("unused") }
    }

    @Test
    fun `create should materialize bundled demo first artifact before recording first run success`() {
        val demoRoot = tempDir.resolve("bundled-demo")
        val demoProject = SpecWorkflowBundledDemoProjectSupport.materialize(demoRoot)
        val demoReadme = Files.readString(demoProject.readmePath)
        assertTrue(demoReadme.contains("Add overdue badge to todo labels"))
        assertTrue(demoReadme.contains("Expected first visible artifact: `tasks.md`"))

        val timestamps = ArrayDeque(listOf(1_000L, 121_000L))
        val coordinator = SpecWorkflowCreateCoordinator(
            createWorkflow = { request ->
                engine.createWorkflow(
                    title = request.title,
                    description = request.description,
                    template = request.template,
                    verifyEnabled = request.verifyEnabled,
                    changeIntent = request.changeIntent,
                    baselineWorkflowId = request.baselineWorkflowId,
                )
            },
            recordCreateAttempt = { template, timestamp ->
                trackingStore.recordWorkflowCreateAttempt(template, timestamp)
            },
            recordCreateSuccess = { workflowId, template, timestamp ->
                trackingStore.recordWorkflowCreateSuccess(workflowId, template, timestamp)
            },
            firstVisibleArtifactExists = { workflowId, artifactFileName ->
                Files.isRegularFile(artifactService.locateArtifact(workflowId, artifactFileName))
            },
            currentTimeMillis = {
                check(timestamps.isNotEmpty()) { "Missing timestamp for acceptance test" }
                timestamps.removeFirst()
            },
        )

        val outcome = coordinator.create(
            SpecWorkflowCreateRequest(
                title = "Add overdue badge to todo labels",
                description = "Add an [OVERDUE] prefix for overdue items in TodoFormatter.labelFor.",
                template = WorkflowTemplate.QUICK_TASK,
                verifyEnabled = false,
                changeIntent = SpecChangeIntent.FULL,
                baselineWorkflowId = null,
            ),
        ).getOrThrow()

        assertTrue(outcome.firstVisibleArtifactMaterialized)
        assertEquals("tasks.md", outcome.expectedFirstVisibleArtifactFileName)
        assertTrue(Files.isRegularFile(artifactService.locateArtifact(outcome.workflow.id, "tasks.md")))

        val snapshot = trackingStore.snapshot()
        assertEquals(1, snapshot.createAttemptCount)
        assertEquals(1, snapshot.createSuccessCount)
        assertEquals(1_000L, snapshot.firstAttemptAt)
        assertEquals(121_000L, snapshot.firstSuccessAt)
        assertEquals(outcome.workflow.id, snapshot.lastSuccessWorkflowId)
        assertEquals("tasks.md", snapshot.lastSuccessArtifactFileName)

        val status = SpecWorkflowFirstRunStatusCoordinator.build(
            readiness = readySnapshot(),
            tracking = snapshot,
        )
        assertTrue(status.details.any { it.contains("2:00") && it.contains("5:00") })
    }

    private fun readySnapshot() = LocalEnvironmentReadiness.evaluate(
        LocalEnvironmentReadinessInput(
            projectPath = tempDir,
            projectWritable = true,
            gitRepositoryDetected = true,
            configuredClaudePath = "",
            configuredCodexPath = "",
            claudeInfo = CliToolInfo(
                available = true,
                path = "claude",
                version = "1.0.0",
            ),
            codexInfo = CliToolInfo(
                available = false,
                path = "codex",
            ),
        ),
    )
}
