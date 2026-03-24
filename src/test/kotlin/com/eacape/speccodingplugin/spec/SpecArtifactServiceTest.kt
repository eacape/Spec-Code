package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecArtifactServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var service: SpecArtifactService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        service = SpecArtifactService(project)
    }

    @Test
    fun `locate read write should resolve deterministic path and use utf8 content`() {
        val workflowId = "wf-artifact-path"
        val targetPath = service.locateArtifact(workflowId, StageId.REQUIREMENTS)
        assertEquals(
            tempDir
                .resolve(".spec-coding")
                .resolve("specs")
                .resolve(workflowId)
                .resolve("requirements.md"),
            targetPath,
        )

        assertEquals(null, service.readArtifact(workflowId, StageId.REQUIREMENTS))

        val writtenPath = service.writeArtifact(
            workflowId = workflowId,
            stageId = StageId.REQUIREMENTS,
            content = "# Requirements\n\n## Functional Requirements\n- [ ] TODO",
        )
        assertEquals(targetPath, writtenPath)
        assertTrue(Files.exists(writtenPath))

        val content = service.readArtifact(workflowId, StageId.REQUIREMENTS)
        assertNotNull(content)
        assertTrue(content!!.contains("Functional Requirements"))
    }

    @Test
    fun `ensureMissingArtifacts should scaffold only missing files for full spec template`() {
        val workflowId = "wf-full-spec"
        val policy = SpecTemplatePolicy(
            definition = WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC),
            verifyEnabledByDefault = false,
            implementEnabledByDefault = true,
        )

        val first = service.ensureMissingArtifacts(
            workflowId = workflowId,
            template = WorkflowTemplate.FULL_SPEC,
            templatePolicy = policy,
        )
        val firstCreated = first.filter { it.created }.map { it.path.fileName.toString() }.toSet()
        assertEquals(setOf("requirements.md", "design.md", "tasks.md"), firstCreated)
        assertFalse(
            Files.exists(
                tempDir
                    .resolve(".spec-coding")
                    .resolve("specs")
                    .resolve(workflowId)
                    .resolve("verification.md"),
            ),
        )

        val tasksPath = service.locateArtifact(workflowId, StageId.TASKS)
        service.writeArtifact(workflowId, StageId.TASKS, "# Implement Document\n\ncustom\n")
        val second = service.ensureMissingArtifacts(
            workflowId = workflowId,
            template = WorkflowTemplate.FULL_SPEC,
            templatePolicy = policy,
        )
        assertTrue(second.all { !it.created })
        assertEquals("# Implement Document\n\ncustom\n", Files.readString(tasksPath))
    }

    @Test
    fun `ensureMissingArtifacts should add minimal tasks skeleton for direct implement template`() {
        val workflowId = "wf-direct-implement"
        val policy = SpecTemplatePolicy(
            definition = WorkflowTemplates.definitionOf(WorkflowTemplate.DIRECT_IMPLEMENT),
            verifyEnabledByDefault = true,
            implementEnabledByDefault = true,
        )

        val results = service.ensureMissingArtifacts(
            workflowId = workflowId,
            template = WorkflowTemplate.DIRECT_IMPLEMENT,
            templatePolicy = policy,
        )

        val created = results.filter { it.created }.map { it.path.fileName.toString() }.toSet()
        assertEquals(setOf("tasks.md", "verification.md"), created)

        val tasksContent = Files.readString(
            tempDir
                .resolve(".spec-coding")
                .resolve("specs")
                .resolve(workflowId)
                .resolve("tasks.md"),
        )
        assertTrue(tasksContent.contains("```spec-task"))
        assertTrue(tasksContent.contains("T-001"))
    }

    @Test
    fun `previewRequiredArtifacts should report backfill strategies without writing files`() {
        val workflowId = "wf-preview-artifacts"
        val directImplementPolicy = SpecTemplatePolicy(
            definition = WorkflowTemplates.definitionOf(WorkflowTemplate.DIRECT_IMPLEMENT),
            verifyEnabledByDefault = false,
            implementEnabledByDefault = true,
        )
        service.ensureMissingArtifacts(
            workflowId = workflowId,
            template = WorkflowTemplate.DIRECT_IMPLEMENT,
            templatePolicy = directImplementPolicy,
        )

        val fullSpecPolicy = SpecTemplatePolicy(
            definition = WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC),
            verifyEnabledByDefault = false,
            implementEnabledByDefault = true,
        )
        val preview = service.previewRequiredArtifacts(
            workflowId = workflowId,
            template = WorkflowTemplate.FULL_SPEC,
            templatePolicy = fullSpecPolicy,
        )

        assertEquals(listOf("requirements.md", "design.md", "tasks.md"), preview.map { it.fileName })
        assertEquals(
            TemplateSwitchArtifactStrategy.GENERATE_SKELETON,
            preview.first { it.fileName == "requirements.md" }.strategy,
        )
        assertEquals(
            TemplateSwitchArtifactStrategy.GENERATE_SKELETON,
            preview.first { it.fileName == "design.md" }.strategy,
        )
        assertEquals(
            TemplateSwitchArtifactStrategy.REUSE_EXISTING,
            preview.first { it.fileName == "tasks.md" }.strategy,
        )
        assertFalse(Files.exists(service.locateArtifact(workflowId, StageId.REQUIREMENTS)))
        assertFalse(Files.exists(service.locateArtifact(workflowId, StageId.DESIGN)))
    }
}
