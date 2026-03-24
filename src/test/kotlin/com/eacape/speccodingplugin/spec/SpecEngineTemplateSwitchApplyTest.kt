package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecEngineTemplateSwitchApplyTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
    }

    @Test
    fun `cloneWorkflowWithTemplate should create a migrated copy and keep source workflow unchanged`() {
        writeProjectConfig(
            """
            schemaVersion: 1
            defaultTemplate: DIRECT_IMPLEMENT
            """.trimIndent() + "\n",
        )
        val engine = SpecEngine(project, storage) { SpecGenerationResult.Failure("unused") }
        val created = engine.createWorkflow(
            title = "Direct implement workflow",
            description = "clone apply",
        ).getOrThrow()

        val preview = engine.previewTemplateSwitch(created.id, WorkflowTemplate.FULL_SPEC).getOrThrow()
        val cloned = engine.cloneWorkflowWithTemplate(
            workflowId = created.id,
            previewId = preview.previewId,
            title = "Migrated full spec workflow",
            description = "created by task 66",
        ).getOrThrow()
        val sourceWorkflowDir = workflowDirFor(created.id)
        val clonedWorkflowDir = workflowDirFor(cloned.workflow.id)

        assertEquals(created.id, cloned.sourceWorkflowId)
        assertEquals(preview.previewId, cloned.previewId)
        assertNotEquals(created.id, cloned.workflow.id)
        assertEquals("Migrated full spec workflow", cloned.workflow.title)
        assertEquals("created by task 66", cloned.workflow.description)
        assertEquals(WorkflowTemplate.FULL_SPEC, cloned.workflow.template)
        assertEquals(StageId.IMPLEMENT, cloned.workflow.currentStage)
        assertTrue(cloned.workflow.stageStates.getValue(StageId.REQUIREMENTS).active)
        assertTrue(cloned.workflow.stageStates.getValue(StageId.DESIGN).active)
        assertEquals(setOf("tasks.md"), cloned.copiedArtifacts.toSet())
        assertEquals(setOf("requirements.md", "design.md"), cloned.generatedArtifacts.toSet())
        assertTrue(Files.exists(clonedWorkflowDir.resolve("requirements.md")))
        assertTrue(Files.exists(clonedWorkflowDir.resolve("design.md")))
        assertTrue(Files.exists(clonedWorkflowDir.resolve("tasks.md")))
        assertFalse(Files.exists(sourceWorkflowDir.resolve("requirements.md")))
        assertFalse(Files.exists(sourceWorkflowDir.resolve("design.md")))

        val sourceReloaded = engine.reloadWorkflow(created.id).getOrThrow()
        assertEquals(WorkflowTemplate.DIRECT_IMPLEMENT, sourceReloaded.template)
        assertFalse(sourceReloaded.stageStates.getValue(StageId.REQUIREMENTS).active)
        assertFalse(sourceReloaded.stageStates.getValue(StageId.DESIGN).active)

        val clonedEvent = loadAuditEvents(cloned.workflow.id)
            .last { it.eventType == SpecAuditEventType.WORKFLOW_CLONED_WITH_TEMPLATE }
        assertEquals(created.id, clonedEvent.details["sourceWorkflowId"])
        assertEquals("DIRECT_IMPLEMENT", clonedEvent.details["sourceTemplate"])
        assertEquals("FULL_SPEC", clonedEvent.details["targetTemplate"])
        assertEquals("TARGET", clonedEvent.details["cloneRole"])
        assertEquals("tasks.md", clonedEvent.details["copiedArtifacts"])
        assertEquals(
            setOf("requirements.md", "design.md"),
            clonedEvent.details["generatedArtifacts"].orEmpty().split(',').filter(String::isNotBlank).toSet(),
        )

        val sourceEvent = loadAuditEvents(created.id)
            .last { it.eventType == SpecAuditEventType.WORKFLOW_CLONED_WITH_TEMPLATE }
        assertEquals(cloned.workflow.id, sourceEvent.details["targetWorkflowId"])
        assertEquals("SOURCE", sourceEvent.details["cloneRole"])
    }

    @Test
    fun `applyTemplateSwitch and rollbackTemplateSwitch should fail because templates are locked`() {
        val engine = SpecEngine(project, storage) { SpecGenerationResult.Failure("unused") }
        val created = engine.createWorkflow(
            title = "Locked workflow",
            description = "template locked",
        ).getOrThrow()

        val preview = engine.previewTemplateSwitch(created.id, WorkflowTemplate.DIRECT_IMPLEMENT).getOrThrow()
        val applyFailure = engine.applyTemplateSwitch(created.id, preview.previewId).exceptionOrNull()
        val rollbackFailure = engine.rollbackTemplateSwitch(created.id, "switch-1").exceptionOrNull()

        assertTrue(applyFailure is TemplateMutationLockedError)
        assertTrue(rollbackFailure is TemplateMutationLockedError)
        assertTrue(applyFailure?.message.orEmpty().contains(created.id))
        assertTrue(rollbackFailure?.message.orEmpty().contains(created.id))
    }

    private fun writeProjectConfig(content: String) {
        val configPath = tempDir.resolve(".spec-coding").resolve("config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, content)
    }

    private fun workflowDirFor(workflowId: String): Path {
        return tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflowId)
    }

    private fun loadAuditEvents(workflowId: String): List<SpecAuditEvent> {
        val auditPath = workflowDirFor(workflowId)
            .resolve(".history")
            .resolve("audit.yaml")
        assertTrue(Files.exists(auditPath))
        return SpecAuditLogCodec.decodeDocuments(Files.readString(auditPath))
    }
}
