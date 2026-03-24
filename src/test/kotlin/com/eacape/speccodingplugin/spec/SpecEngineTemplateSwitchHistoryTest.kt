package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SpecEngineTemplateSwitchHistoryTest {

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
    fun `listTemplateSwitchHistory should stay empty for template clone migrations`() {
        val engine = SpecEngine(project, storage) { SpecGenerationResult.Failure("unused") }
        val created = engine.createWorkflow(
            title = "History Workflow",
            description = "history",
        ).getOrThrow()

        val preview = engine.previewTemplateSwitch(created.id, WorkflowTemplate.DIRECT_IMPLEMENT).getOrThrow()
        val cloned = engine.cloneWorkflowWithTemplate(
            workflowId = created.id,
            previewId = preview.previewId,
        ).getOrThrow()

        val sourceHistory = engine.listTemplateSwitchHistory(created.id).getOrThrow()
        val clonedHistory = engine.listTemplateSwitchHistory(cloned.workflow.id).getOrThrow()

        assertTrue(sourceHistory.isEmpty())
        assertTrue(clonedHistory.isEmpty())
    }

    @Test
    fun `cloneWorkflowWithTemplate should record source and target workflow ids in audit trail`() {
        val engine = SpecEngine(project, storage) { SpecGenerationResult.Failure("unused") }
        val created = engine.createWorkflow(
            title = "Audit Workflow",
            description = "clone audit",
        ).getOrThrow()

        val preview = engine.previewTemplateSwitch(created.id, WorkflowTemplate.DIRECT_IMPLEMENT).getOrThrow()
        val cloned = engine.cloneWorkflowWithTemplate(
            workflowId = created.id,
            previewId = preview.previewId,
        ).getOrThrow()

        val sourceAudit = storage.listAuditEvents(created.id).getOrThrow()
            .last { it.eventType == SpecAuditEventType.WORKFLOW_CLONED_WITH_TEMPLATE }
        val clonedAudit = storage.listAuditEvents(cloned.workflow.id).getOrThrow()
            .last { it.eventType == SpecAuditEventType.WORKFLOW_CLONED_WITH_TEMPLATE }

        assertEquals(cloned.workflow.id, sourceAudit.details["targetWorkflowId"])
        assertEquals(created.id, clonedAudit.details["sourceWorkflowId"])
        assertEquals("SOURCE", sourceAudit.details["cloneRole"])
        assertEquals("TARGET", clonedAudit.details["cloneRole"])
    }
}
